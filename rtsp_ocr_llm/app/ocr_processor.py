import asyncio
import json
import logging
import multiprocessing as mp
import os
import threading
import time
from typing import Optional

import numpy as np

from lm_processor import analyze_with_lm

logger = logging.getLogger(__name__)


# ── subprocess worker ─────────────────────────────────────────────────────────

def _ocr_worker(input_q: mp.Queue, output_q: mp.Queue) -> None:
    os.environ["PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK"] = "True"

    import paddle
    device = "gpu" if paddle.device.is_compiled_with_cuda() else "cpu"
    logger.info("OCR worker using device: %s", device)

    from paddleocr import PaddleOCR
    ocr = PaddleOCR(
        lang="en",
        device=device,
        text_detection_model_name="PP-OCRv5_mobile_det",
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
    )
    output_q.put({"type": "ready"})

    while True:
        msg = input_q.get()
        if msg is None:
            break
        frame = msg["frame"]
        try:
            items = []
            for res in ocr.predict(frame):
                for text, conf, poly in zip(
                    res.get("rec_texts", []),
                    res.get("rec_scores", []),
                    res.get("dt_polys", []),
                ):
                    if conf >= 0.5 and text.strip():
                        items.append({
                            "text": text.strip(),
                            "confidence": round(float(conf), 3),
                            "bbox": [[int(p[0]), int(p[1])] for p in poly],
                        })
            output_q.put({"type": "result", "items": items})
        except Exception as exc:
            output_q.put({"type": "result", "items": [], "error": str(exc)})


# ── main class ────────────────────────────────────────────────────────────────

class OCRProcessor:
    def __init__(self, interval_sec: int = 0):
        """interval_sec=0 → manual-only mode; >0 → auto + manual."""
        self.interval_sec = interval_sec
        self._latest_frame: Optional[np.ndarray] = None
        self._frame_lock   = threading.Lock()
        self._clients      = None
        self._clients_lock = None
        self._event_loop: Optional[asyncio.AbstractEventLoop] = None
        self._stop_event   = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self.last_result: list = []
        self.last_lm_result: dict = {}
        self._input_q: Optional[mp.Queue]  = None
        self._output_q: Optional[mp.Queue] = None
        self._worker_proc: Optional[mp.Process] = None
        self._worker_ready  = threading.Event()  # set once PaddleOCR is loaded
        self._ocr_lock      = threading.Lock()   # prevents concurrent OCR runs
        self._captures: list = []                # accumulated capture batches
        self._captures_lock = threading.Lock()

    # ── frame update (called from capture thread) ─────────────────────────────

    def update_frame(self, frame: np.ndarray) -> None:
        with self._frame_lock:
            self._latest_frame = frame.copy()

    # ── worker subprocess ─────────────────────────────────────────────────────

    def _start_worker(self) -> None:
        ctx = mp.get_context("spawn")
        self._input_q  = ctx.Queue()
        self._output_q = ctx.Queue()
        self._worker_proc = ctx.Process(
            target=_ocr_worker,
            args=(self._input_q, self._output_q),
            daemon=True,
            name="paddle-ocr-worker",
        )
        self._worker_proc.start()
        logger.info("OCR worker process started (pid %d), waiting for ready…", self._worker_proc.pid)
        msg = self._output_q.get(timeout=120)
        assert msg.get("type") == "ready", f"unexpected: {msg}"
        logger.info("PaddleOCR ready")

    def _init_worker(self) -> None:
        """Start the PaddleOCR subprocess in a background thread, then signal ready."""
        try:
            self._start_worker()
            self._worker_ready.set()
        except Exception as exc:
            logger.error("Failed to start OCR worker: %s", exc)

    # ── core OCR + LM pipeline ────────────────────────────────────────────────

    def _run_ocr(self, frame: np.ndarray) -> list:
        self._input_q.put({"frame": frame})
        try:
            msg = self._output_q.get(timeout=30)
            if msg.get("error"):
                logger.error("OCR worker error: %s", msg["error"])
            return msg.get("items", [])
        except Exception as exc:
            logger.error("OCR result timeout/error: %s", exc)
            return []

    def _broadcast_ocr(self, items: list, elapsed_ms: int) -> None:
        if not (self._event_loop and self._clients is not None):
            return
        payload = json.dumps({
            "type":       "ocr",
            "ts":         round(time.time(), 3),
            "elapsed_ms": elapsed_ms,
            "count":      len(items),
            "results":    items,
        })
        asyncio.run_coroutine_threadsafe(self._broadcast(payload), self._event_loop)

    def _broadcast_lm(self, result: dict) -> None:
        if not (self._event_loop and self._clients is not None):
            return
        payload = json.dumps({
            "type":   "lm",
            "ts":     round(time.time(), 3),
            "result": result,
        })
        asyncio.run_coroutine_threadsafe(self._broadcast(payload), self._event_loop)

    def _run_full_cycle(self, frame: np.ndarray, lm_in_thread: bool) -> None:
        """OCR → broadcast OCR → LM → broadcast LM.
        lm_in_thread=True  → LM runs in a new daemon thread (auto mode).
        lm_in_thread=False → LM runs synchronously, caller holds _ocr_lock.
        """
        logger.info("Running OCR on frame…")
        t0 = time.monotonic()
        items = self._run_ocr(frame)
        elapsed_ms = round((time.monotonic() - t0) * 1000)
        self.last_result = items
        logger.info("OCR done in %d ms — %d regions found", elapsed_ms, len(items))

        self._broadcast_ocr(items, elapsed_ms)

        if items:
            if lm_in_thread:
                threading.Thread(
                    target=self._lm_step, args=(items,), daemon=True, name="lm-auto"
                ).start()
            else:
                self._lm_step(items)
        else:
            # No text — still signal LM done so the frontend can re-enable the button
            empty = {"arranged_text": "", "qa_pairs": [], "raw_text": "", "error": "No text detected"}
            self.last_lm_result = empty
            if not lm_in_thread:
                self._broadcast_lm(empty)

    def _lm_step(self, items: list, num_captures: int = 1) -> None:
        logger.info("LM analysis started for %d items (%d captures)…", len(items), num_captures)
        result = analyze_with_lm(items, num_captures=num_captures)
        self.last_lm_result = result
        self._broadcast_lm(result)

    # ── capture (OCR only, accumulate) ────────────────────────────────────────

    def capture(self) -> bool:
        """OCR current frame and add results to the capture buffer. No LM."""
        if not self._ocr_lock.acquire(blocking=False):
            return False
        threading.Thread(target=self._capture_cycle, daemon=True, name="ocr-capture").start()
        return True

    def _capture_cycle(self) -> None:
        try:
            if not self._worker_ready.wait(timeout=120):
                self._push_ws({"type": "capture_error", "error": "OCR worker not ready"})
                return
            with self._frame_lock:
                frame = self._latest_frame.copy() if self._latest_frame is not None else None
            if frame is None:
                self._push_ws({"type": "capture_error", "error": "No frame — wait for stream"})
                return

            t0 = time.monotonic()
            items = self._run_ocr(frame)
            elapsed_ms = round((time.monotonic() - t0) * 1000)
            self.last_result = items

            with self._captures_lock:
                self._captures.append(items)
                cap_num   = len(self._captures)
                total_frags = sum(len(c) for c in self._captures)

            logger.info("Capture %d done in %d ms — %d fragments (total: %d)",
                        cap_num, elapsed_ms, len(items), total_frags)
            self._push_ws({
                "type":        "capture",
                "cap_num":     cap_num,
                "count":       len(items),
                "total_frags": total_frags,
                "elapsed_ms":  elapsed_ms,
                "items":       items,
            })
        finally:
            self._ocr_lock.release()

    # ── solve (LM on all accumulated captures) ────────────────────────────────

    def solve(self) -> bool:
        """Run LM on all accumulated captures."""
        if not self._ocr_lock.acquire(blocking=False):
            return False
        threading.Thread(target=self._solve_cycle, daemon=True, name="ocr-solve").start()
        return True

    def _solve_cycle(self) -> None:
        try:
            with self._captures_lock:
                captures = list(self._captures)
            if not captures:
                empty = {"arranged_text": "", "qa_pairs": [], "raw_text": "",
                         "error": "Nothing captured yet — use Capture first"}
                self.last_lm_result = empty
                self._broadcast_lm(empty)
                return
            # Flatten: spatial-sort within each capture, preserve capture order
            all_items = []
            for batch in captures:
                all_items.extend(sorted(
                    batch,
                    key=lambda it: (min(p[1] for p in it["bbox"]) // 40,
                                    min(p[0] for p in it["bbox"]))
                ))
            self._lm_step(all_items, num_captures=len(captures))
            self.clear_captures()
        finally:
            self._ocr_lock.release()

    # ── clear captured buffer ─────────────────────────────────────────────────

    def clear_captures(self) -> None:
        with self._captures_lock:
            self._captures.clear()
        logger.info("Capture buffer cleared")
        self._push_ws({"type": "cleared"})

    @property
    def capture_stats(self) -> dict:
        with self._captures_lock:
            n = len(self._captures)
            total = sum(len(c) for c in self._captures)
        return {"captures": n, "total_fragments": total}

    def _push_ws(self, data: dict) -> None:
        if not (self._event_loop and self._clients is not None):
            return
        asyncio.run_coroutine_threadsafe(
            self._broadcast(json.dumps(data)), self._event_loop
        )

    # ── manual trigger ────────────────────────────────────────────────────────

    def trigger(self) -> bool:
        """
        Trigger one OCR+LM cycle immediately (manual mode).
        Returns False if a cycle is already running or worker isn't ready.
        The _ocr_lock is held for the entire OCR+LM pipeline so the
        frontend button stays disabled until results are broadcast.
        """
        if not self._ocr_lock.acquire(blocking=False):
            return False
        threading.Thread(target=self._triggered_cycle, daemon=True, name="ocr-trigger").start()
        return True

    def _triggered_cycle(self) -> None:
        try:
            if not self._worker_ready.wait(timeout=120):
                logger.error("OCR worker not ready, aborting trigger")
                self._broadcast_lm({"error": "OCR worker not ready", "arranged_text": "", "qa_pairs": []})
                return

            with self._frame_lock:
                frame = self._latest_frame.copy() if self._latest_frame is not None else None

            if frame is None:
                logger.warning("No frame available for triggered OCR")
                self._broadcast_lm({"error": "No frame yet — wait for the stream", "arranged_text": "", "qa_pairs": []})
                return

            self._run_full_cycle(frame, lm_in_thread=False)
        finally:
            self._ocr_lock.release()

    # ── auto-loop (only used when interval_sec > 0) ───────────────────────────

    def _loop(self) -> None:
        if not self._worker_ready.wait(timeout=120):
            logger.error("OCR worker not ready, auto-loop aborted")
            return

        while not self._stop_event.is_set():
            with self._frame_lock:
                frame = self._latest_frame.copy() if self._latest_frame is not None else None

            if frame is not None:
                # Use non-blocking acquire; skip cycle if manual trigger is running
                if self._ocr_lock.acquire(blocking=False):
                    try:
                        self._run_full_cycle(frame, lm_in_thread=True)
                    finally:
                        self._ocr_lock.release()
            else:
                logger.info("OCR: no frame available yet, skipping")

            self._stop_event.wait(self.interval_sec)

    # ── broadcast ─────────────────────────────────────────────────────────────

    async def _broadcast(self, payload: str) -> None:
        if self._clients is None:
            return
        with self._clients_lock:
            clients = set(self._clients)
        dead: set = set()
        for ws in clients:
            try:
                await ws.send_text(payload)
            except Exception:
                dead.add(ws)
        if dead:
            with self._clients_lock:
                self._clients -= dead

    # ── lifecycle ─────────────────────────────────────────────────────────────

    def start(self, loop: asyncio.AbstractEventLoop, clients: set, clients_lock: threading.Lock) -> None:
        self._event_loop   = loop
        self._clients      = clients
        self._clients_lock = clients_lock
        self._stop_event.clear()

        # Always start the worker subprocess (needed for both auto and manual)
        threading.Thread(target=self._init_worker, daemon=True, name="ocr-init").start()

        if self.interval_sec > 0:
            logger.info("OCR auto-loop every %ds", self.interval_sec)
            self._thread = threading.Thread(target=self._loop, daemon=True, name="ocr-loop")
            self._thread.start()
        else:
            logger.info("OCR manual mode — use /ocr/trigger or the Get Answer button")

    def stop(self) -> None:
        self._stop_event.set()
        if self._input_q:
            self._input_q.put(None)
        if self._worker_proc and self._worker_proc.is_alive():
            self._worker_proc.join(timeout=5)
            if self._worker_proc.is_alive():
                self._worker_proc.terminate()
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=5)
