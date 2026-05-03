import asyncio
import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles

from ocr_processor import OCRProcessor
from streamer import RTSPStreamer

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
)
logger = logging.getLogger(__name__)

RTSP_URL     = os.getenv("RTSP_URL", "rtsp://192.168.1.3:8554/live/")
STREAM_FPS   = int(os.getenv("STREAM_FPS", "5"))
OCR_INTERVAL = int(os.getenv("OCR_INTERVAL", "0"))   # 0 = manual-only
RTSP_CODEC   = os.getenv("RTSP_CODEC", "h264")  # h264 or h265

ocr      = OCRProcessor(interval_sec=OCR_INTERVAL)
streamer = RTSPStreamer(rtsp_url=RTSP_URL, fps=STREAM_FPS, ocr_processor=ocr, codec=RTSP_CODEC)


@asynccontextmanager
async def lifespan(app: FastAPI):
    loop = asyncio.get_event_loop()
    logger.info("Starting streamer  → %s @ %d FPS (%s)", RTSP_URL, STREAM_FPS, RTSP_CODEC)
    streamer.start(loop)
    mode = f"every {OCR_INTERVAL}s" if OCR_INTERVAL > 0 else "manual"
    logger.info("Starting OCR worker (%s)", mode)
    ocr.start(loop, streamer.clients, streamer._clients_lock)
    yield
    streamer.stop()
    ocr.stop()


app = FastAPI(title="RTSP OCR Streamer", lifespan=lifespan)
app.mount("/static", StaticFiles(directory="static"), name="static")


@app.get("/", response_class=HTMLResponse)
async def index():
    with open("static/index.html") as f:
        return HTMLResponse(f.read(), headers={"Cache-Control": "no-store"})


@app.websocket("/ws")
async def ws_endpoint(websocket: WebSocket):
    await websocket.accept()
    streamer.add_client(websocket)
    try:
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        pass
    except Exception:
        pass
    finally:
        streamer.remove_client(websocket)


@app.get("/health")
async def health():
    return {
        "status": "ok",
        "rtsp_url": RTSP_URL,
        "stream_fps": STREAM_FPS,
        "ocr_interval_sec": OCR_INTERVAL,
        "connected_clients": len(streamer.clients),
        "last_ocr_count": len(ocr.last_result),
    }


@app.get("/stream")
async def mjpeg_stream():
    """MJPEG video stream — works natively in any browser <img> tag."""
    frame_interval = 1.0 / STREAM_FPS

    async def generate():
        while True:
            await asyncio.sleep(frame_interval)
            with streamer._jpeg_lock:
                jpeg = streamer._latest_jpeg
            if jpeg:
                yield (
                    b"--frame\r\nContent-Type: image/jpeg\r\n\r\n"
                    + jpeg
                    + b"\r\n"
                )

    return StreamingResponse(
        generate(),
        media_type="multipart/x-mixed-replace; boundary=frame",
        headers={"Cache-Control": "no-cache"},
    )


@app.post("/ocr/trigger")
async def ocr_trigger():
    """Single-shot OCR + LM (used by main stream page)."""
    started = ocr.trigger()
    return {"status": "started" if started else "busy"}


@app.post("/ocr/capture")
async def ocr_capture():
    """OCR current frame and add to capture buffer (no LM)."""
    started = ocr.capture()
    return {"status": "started" if started else "busy"}


@app.post("/ocr/solve")
async def ocr_solve():
    """Run LM on all accumulated captures."""
    started = ocr.solve()
    return {"status": "started" if started else "busy"}


@app.post("/ocr/clear")
async def ocr_clear():
    """Clear the capture buffer."""
    ocr.clear_captures()
    return {"status": "cleared"}


@app.get("/ocr/captures")
async def ocr_captures():
    return ocr.capture_stats


@app.get("/ocr/latest")
async def ocr_latest():
    return {"results": ocr.last_result}


@app.get("/lm/latest")
async def lm_latest():
    return ocr.last_lm_result


@app.get("/ocr-results", response_class=HTMLResponse)
async def ocr_results_page():
    with open("static/ocr_results.html") as f:
        return HTMLResponse(f.read(), headers={"Cache-Control": "no-store"})
