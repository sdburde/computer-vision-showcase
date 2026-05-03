import asyncio
import logging
import threading
import time
from typing import Set

import cv2

logger = logging.getLogger(__name__)


class RTSPStreamer:
    def __init__(self, rtsp_url: str, fps: int = 5, ocr_processor=None, codec: str = "h264"):
        self.rtsp_url = rtsp_url
        self.fps = fps
        self.codec = codec.lower()  # kept for API compatibility
        self.clients: Set = set()
        self._clients_lock = threading.Lock()
        self._event_loop: asyncio.AbstractEventLoop | None = None
        self._stop_event = threading.Event()
        self._capture_thread: threading.Thread | None = None
        self._ocr = ocr_processor
        self._latest_jpeg: bytes | None = None
        self._jpeg_lock = threading.Lock()

    # ── client management ──────────────────────────────────────────────────────

    def add_client(self, ws) -> None:
        with self._clients_lock:
            self.clients.add(ws)
        logger.info("Client added — total: %d", len(self.clients))

    def remove_client(self, ws) -> None:
        with self._clients_lock:
            self.clients.discard(ws)
        logger.info("Client removed — total: %d", len(self.clients))

    # ── capture loop ───────────────────────────────────────────────────────────

    def _capture_loop(self) -> None:
        frame_interval = 1.0 / self.fps
        retry_delay = 2.0

        # Force TCP transport and disable all internal buffering for minimum latency.
        # Must be set before VideoCapture is constructed.
        import os
        os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = (
            "rtsp_transport;tcp"
            "|fflags;nobuffer"
            "|flags;low_delay"
            "|analyzeduration;1000000"
            "|probesize;1048576"
        )

        while not self._stop_event.is_set():
            logger.info("Connecting to %s", self.rtsp_url)
            cap = cv2.VideoCapture(self.rtsp_url, cv2.CAP_FFMPEG)
            cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)

            if not cap.isOpened():
                logger.error("Failed to open stream, retrying in %.0fs", retry_delay)
                self._stop_event.wait(retry_delay)
                retry_delay = min(retry_delay * 2, 60.0)
                continue

            logger.info("Stream opened")
            retry_delay = 2.0
            last_sent = 0.0

            while not self._stop_event.is_set():
                ret, frame = cap.read()
                if not ret:
                    logger.warning("Frame read failed — reconnecting")
                    break

                now = time.monotonic()
                if now - last_sent < frame_interval:
                    continue
                last_sent = now

                ok, jpeg = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 75])
                if not ok:
                    continue

                jpeg_bytes = jpeg.tobytes()

                with self._jpeg_lock:
                    self._latest_jpeg = jpeg_bytes

                if self._ocr is not None:
                    self._ocr.update_frame(frame)

                with self._clients_lock:
                    snap = set(self.clients)
                if self._event_loop and snap:
                    asyncio.run_coroutine_threadsafe(
                        self._broadcast(jpeg_bytes, snap), self._event_loop
                    )

            cap.release()

    async def _broadcast(self, data: bytes, clients: set) -> None:
        dead: set = set()
        for ws in clients:
            try:
                await ws.send_bytes(data)
            except Exception:
                dead.add(ws)
        if dead:
            with self._clients_lock:
                self.clients -= dead

    # ── public start / stop ────────────────────────────────────────────────────

    def start(self, loop: asyncio.AbstractEventLoop) -> None:
        self._event_loop = loop
        self._stop_event.clear()
        self._capture_thread = threading.Thread(
            target=self._capture_loop, daemon=True, name="rtsp-capture"
        )
        self._capture_thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._capture_thread:
            self._capture_thread.join(timeout=5)
