package com.rtspcamera;

import android.content.Context;
import android.hardware.camera2.*;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

public class CameraStreamer {

    private static final String TAG = "CameraStreamer";
    private int videoWidth   = 1280;
    private int videoHeight  = 720;
    static final int VIDEO_FPS     = 30; // Increased for "best" quality
    private static final int VIDEO_BITRATE = 3_000_000; // Increased to 3 Mbps for better quality

    private final Context context;
    private final int lensFacing;
    private final boolean isLandscape;
    private volatile Surface previewSurface;
    private volatile boolean stopped = false;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CameraCharacteristics cameraCharacteristics;
    private MediaCodec mediaCodec;
    private Surface encoderSurface; // kept so we can recreate capture sessions
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private volatile byte[] spsNal;
    private volatile byte[] ppsNal;

    // Per-client queues — each connected client gets its own independent copy of every frame
    private final CopyOnWriteArrayList<LinkedBlockingQueue<byte[]>> clientQueues =
        new CopyOnWriteArrayList<>();

    public CameraStreamer(Context context, Surface previewSurface, int lensFacing, boolean isLandscape) {
        this.context = context;
        this.previewSurface = previewSurface;
        this.lensFacing = lensFacing;
        this.isLandscape = isLandscape;
        
        if (isLandscape) {
            this.videoWidth = 1280;
            this.videoHeight = 720;
        } else {
            this.videoWidth = 720;
            this.videoHeight = 1280;
        }
    }

    /**
     * Called when the TextureView surface becomes available (possibly after start()).
     * Restarts the capture session to add the preview output.
     */
    public void setPreviewSurface(Surface surface) {
        this.previewSurface = surface;
        // If camera is already open, restart session to include preview
        if (cameraDevice != null && encoderSurface != null && cameraCharacteristics != null) {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            createCaptureSession(cameraDevice, encoderSurface, cameraCharacteristics);
        }
    }

    public int getVideoFps() { return VIDEO_FPS; }

    public LinkedBlockingQueue<byte[]> createClientQueue() {
        LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<>(30); // 1 s max at 30 fps
        clientQueues.add(q);
        return q;
    }

    public void removeClientQueue(LinkedBlockingQueue<byte[]> q) {
        clientQueues.remove(q);
    }

    public void start() {
        stopped = false;
        startBackgroundThread();
        setupMediaCodec();
    }

    private void startBackgroundThread() {
        if (backgroundThread != null && backgroundThread.isAlive()) return;
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void setupMediaCodec() {
        try {
            // Adjust resolution to best match camera sensor while keeping orientation
            adjustResolution();

            mediaCodec = MediaCodec.createEncoderByType("video/avc");
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", videoWidth, videoHeight);
            format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            format.setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            format.setInteger(MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31);
            // Prevent encoder stall between frames (available since API 19)
            format.setLong("repeat-previous-frame-after", 1_000_000L / VIDEO_FPS); 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LATENCY, 0);
            }

            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = mediaCodec.createInputSurface();
            mediaCodec.setCallback(encoderCallback, backgroundHandler);
            mediaCodec.start();

            openCamera(encoderSurface);
        } catch (IOException e) {
            Log.e(TAG, "MediaCodec setup error", e);
        }
    }

    private void adjustResolution() {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String cameraId = getCameraId(manager);
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            
            Integer sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (sensorOrientation == null) sensorOrientation = 90;

            android.util.Size[] sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(MediaCodec.class);

            // To prevent ALL cropping, we use the sensor's native resolution.
            // Pick the largest size (up to 1080p) to ensure high quality without cropping.
            android.util.Size bestSize = sizes[0];
            long maxPixels = 0;
            for (android.util.Size s : sizes) {
                if (s.getWidth() <= 1920 && s.getHeight() <= 1920) {
                    long pixels = (long) s.getWidth() * s.getHeight();
                    if (pixels > maxPixels) {
                        maxPixels = pixels;
                        bestSize = s;
                    }
                }
            }

            boolean swapDimensions = (sensorOrientation == 90 || sensorOrientation == 270);
            
            // The encoder dimensions MUST match the orientation chosen by the user,
            // but we use the SENSOR'S aspect ratio to ensure we capture the whole frame.
            if (isLandscape) {
                videoWidth = swapDimensions ? bestSize.getHeight() : bestSize.getWidth();
                videoHeight = swapDimensions ? bestSize.getWidth() : bestSize.getHeight();
            } else {
                videoWidth = swapDimensions ? bestSize.getHeight() : bestSize.getWidth();
                videoHeight = swapDimensions ? bestSize.getWidth() : bestSize.getHeight();
            }

            // Ensure width/height are correctly aligned for the display mode
            if (isLandscape && videoWidth < videoHeight) {
                int tmp = videoWidth; videoWidth = videoHeight; videoHeight = tmp;
            } else if (!isLandscape && videoWidth > videoHeight) {
                int tmp = videoWidth; videoWidth = videoHeight; videoHeight = tmp;
            }

            Log.d(TAG, "Uncropped resolution selected: " + videoWidth + "x" + videoHeight + " (Sensor Native: " + bestSize.getWidth() + "x" + bestSize.getHeight() + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to adjust resolution", e);
        }
    }

    private String getCameraId(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics chars = manager.getCameraCharacteristics(id);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == lensFacing) return id;
        }
        return manager.getCameraIdList()[0];
    }

    /** Teardown codec + camera, then restart — used on camera disconnection. */
    private void restartCamera() {
        try {
            if (captureSession != null) { captureSession.close(); captureSession = null; }
            if (cameraDevice != null)   { cameraDevice.close();   cameraDevice = null;   }
            if (mediaCodec != null)     { mediaCodec.stop(); mediaCodec.release(); mediaCodec = null; }
        } catch (Exception e) {
            Log.e(TAG, "Restart cleanup error", e);
        }
        setupMediaCodec();
    }

    private final MediaCodec.Callback encoderCallback = new MediaCodec.Callback() {
        @Override public void onInputBufferAvailable(MediaCodec codec, int index) {}

        @Override
        public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
            ByteBuffer buffer = codec.getOutputBuffer(index);
            if (buffer == null) return;
            buffer.position(info.offset);
            buffer.limit(info.offset + info.size);
            byte[] data = new byte[info.size];
            buffer.get(data);
            processNalUnit(data, info.flags);
            codec.releaseOutputBuffer(index, false);
        }

        @Override
        public void onError(MediaCodec codec, MediaCodec.CodecException e) {
            Log.e(TAG, "Codec error", e);
        }

        @Override public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {}
    };

    private void processNalUnit(byte[] data, int flags) {
        if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            extractSpsAndPps(data);
            return;
        }
        byte[] nal = addStartCode(data);
        for (LinkedBlockingQueue<byte[]> q : clientQueues) {
            if (!q.offer(nal)) {
                q.poll(); // drop oldest — keep live
                q.offer(nal);
            }
        }
    }

    /**
     * Splits a combined SPS+PPS codec-config buffer into spsNal / ppsNal.
     * Handles both 3-byte (0x000001) and 4-byte (0x00000001) start codes.
     */
    private void extractSpsAndPps(byte[] data) {
        // Skip the first start code to find where the first NAL content begins
        int firstNalStart;
        if (data.length > 3 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
            firstNalStart = 4;
        } else if (data.length > 2 && data[0] == 0 && data[1] == 0 && data[2] == 1) {
            firstNalStart = 3;
        } else {
            return; // unexpected format
        }

        // Search for the start of the second NAL (PPS) — accept both 3- and 4-byte codes
        int secondStart = -1;
        for (int i = firstNalStart + 1; i < data.length - 2; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                secondStart = (i > 0 && data[i - 1] == 0) ? i - 1 : i;
                break;
            }
        }

        if (secondStart > 0) {
            spsNal = Arrays.copyOfRange(data, 0, secondStart);
            ppsNal = Arrays.copyOfRange(data, secondStart, data.length);
        }
    }

    private byte[] addStartCode(byte[] data) {
        byte[] out = new byte[data.length + 4];
        out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 1;
        System.arraycopy(data, 0, out, 4, data.length);
        return out;
    }

    private void openCamera(Surface encSurface) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String selectedId = getCameraId(manager);
            CameraCharacteristics chars = manager.getCameraCharacteristics(selectedId);

            manager.openCamera(selectedId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    cameraCharacteristics = chars;
                    createCaptureSession(camera, encSurface, chars);
                }
                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    if (!stopped) backgroundHandler.postDelayed(
                        () -> { if (!stopped) restartCamera(); }, 1000);
                }
                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    camera.close();
                    cameraDevice = null;
                    if (!stopped) backgroundHandler.postDelayed(
                        () -> { if (!stopped) restartCamera(); }, 1000);
                }
            }, backgroundHandler);
        } catch (Exception e) {
            Log.e(TAG, "Cannot open camera", e);
        }
    }

    private void createCaptureSession(CameraDevice camera, Surface encSurface, CameraCharacteristics chars) {
        try {
            List<Surface> outputs = new ArrayList<>();
            outputs.add(encSurface);
            if (previewSurface != null) outputs.add(previewSurface);

            camera.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        CaptureRequest.Builder builder =
                            camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        builder.addTarget(encSurface);
                        if (previewSurface != null) builder.addTarget(previewSurface);

                        // Professional camera settings for "best" quality
                        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
                        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST);

                        // Handle FPS range safely
                        android.util.Range<Integer>[] fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                        android.util.Range<Integer> bestRange = new android.util.Range<>(VIDEO_FPS, VIDEO_FPS);
                        if (fpsRanges != null) {
                            for (android.util.Range<Integer> range : fpsRanges) {
                                if (range.getUpper() >= VIDEO_FPS && range.getLower() <= VIDEO_FPS) {
                                    bestRange = range;
                                    break;
                                }
                            }
                        }
                        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestRange);

                        session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                    } catch (Exception e) {
                        Log.e(TAG, "Capture request failed", e);
                    }
                }
                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    Log.e(TAG, "Session config failed");
                }
            }, backgroundHandler);
        } catch (Exception e) {
            Log.e(TAG, "Create session failed", e);
        }
    }

    public byte[] getSpsNal() { return spsNal; }
    public byte[] getPpsNal() { return ppsNal; }

    public void stop() {
        stopped = true; // set first — prevents reconnect callbacks from firing
        clientQueues.clear();
        try {
            if (captureSession != null) { captureSession.close(); captureSession = null; }
            if (cameraDevice != null)   { cameraDevice.close();   cameraDevice = null;   }
            if (mediaCodec != null)     { mediaCodec.stop(); mediaCodec.release(); mediaCodec = null; }
            if (backgroundThread != null) {
                backgroundThread.quitSafely();
                backgroundThread.join();
                backgroundThread = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Stop error", e);
        }
    }
}
