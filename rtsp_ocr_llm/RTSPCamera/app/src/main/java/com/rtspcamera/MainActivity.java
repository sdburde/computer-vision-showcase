package com.rtspcamera;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.content.res.Configuration;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.net.Inet4Address;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.INTERNET
    };

    private TextureView textureView;
    private View previewCard;
    private Button btnStartStop;
    private Button btnRear;
    private Button btnFront;
    private Button btnLandscape;
    private Button btnPortrait;
    private EditText etPort;
    private TextView tvUrl;
    private TextView tvStatusBadge;
    private TextView tvIpAddress;
    private TextView tvCameraBadge;
    private ImageButton btnCopyUrl;

    private RtspService rtspService;
    private boolean isBound = false;
    private boolean isStreaming = false;
    private boolean isRearCamera = true;
    private boolean isLandscape = true;
    private Surface previewSurface;
    private String currentUrl = "";

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            RtspService.LocalBinder binder = (RtspService.LocalBinder) service;
            rtspService = binder.getService();
            isBound = true;
            if (previewSurface != null) {
                rtspService.setPreviewSurface(previewSurface);
            }
            rtspService.setStatusCallback(new RtspService.StatusCallback() {
                @Override
                public void onStatusChanged(String status) {
                    runOnUiThread(() -> updateStatusBadge(status));
                }
                @Override
                public void onUrlChanged(String url) {
                    runOnUiThread(() -> {
                        currentUrl = url;
                        tvUrl.setText(url);
                        tvUrl.setTextColor(0xFF4CAF50);
                    });
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView  = findViewById(R.id.textureView);
        previewCard  = findViewById(R.id.previewCard);
        btnStartStop = findViewById(R.id.btnStartStop);
        btnRear      = findViewById(R.id.btnRear);
        btnFront     = findViewById(R.id.btnFront);
        btnLandscape = findViewById(R.id.btnLandscape);
        btnPortrait  = findViewById(R.id.btnPortrait);
        etPort       = findViewById(R.id.etPort);
        tvUrl        = findViewById(R.id.tvUrl);
        tvStatusBadge = findViewById(R.id.tvStatusBadge);
        tvIpAddress  = findViewById(R.id.tvIpAddress);
        tvCameraBadge = findViewById(R.id.tvCameraBadge);
        btnCopyUrl   = findViewById(R.id.btnCopyUrl);

        String ip = getDeviceIp();
        tvIpAddress.setText("● " + (ip.equals("127.0.0.1") ? "No Wi-Fi" : ip));

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
                previewSurface = new Surface(st);
                if (rtspService != null) rtspService.setPreviewSurface(previewSurface);
                configureTransform(w, h);
            }
            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {
                configureTransform(w, h);
            }
            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
                previewSurface = null;
                return true;
            }
            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {}
        });

        btnStartStop.setOnClickListener(v -> {
            if (!isStreaming) startStreaming();
            else stopStreaming();
        });

        btnRear.setOnClickListener(v -> selectCamera(true));
        btnFront.setOnClickListener(v -> selectCamera(false));
        btnLandscape.setOnClickListener(v -> selectOrientation(true));
        btnPortrait.setOnClickListener(v -> selectOrientation(false));

        btnCopyUrl.setOnClickListener(v -> {
            if (!currentUrl.isEmpty()) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("RTSP URL", currentUrl));
                Toast.makeText(this, "URL copied!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Start stream first", Toast.LENGTH_SHORT).show();
            }
        });

        if (!hasPermissions()) requestPermissions();
        checkBatteryOptimization();

        previewCard.post(this::resizePreview);
    }

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Toast.makeText(this, "Tip: Disable battery optimization for this app in settings to prevent streaming from stopping.", 
                    Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        previewCard.post(() -> {
            resizePreview();
            configureTransform(textureView.getWidth(), textureView.getHeight());
        });
    }

    private void resizePreview() {
        int cardWidth = previewCard.getWidth();
        if (cardWidth == 0) return;
        int natural = isLandscape ? (cardWidth * 9 / 16) : (cardWidth * 16 / 9);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        int height = Math.min(natural, maxH);
        ViewGroup.LayoutParams lp = previewCard.getLayoutParams();
        lp.height = height;
        previewCard.setLayoutParams(lp);
        
        // Also update transform
        textureView.post(() -> configureTransform(textureView.getWidth(), textureView.getHeight()));
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (textureView == null || viewWidth == 0 || viewHeight == 0) return;
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        
        // Match the stream orientation. 
        // We use 1440x1080 (4:3) or similar as a reference to keep the aspect ratio 
        // consistent with the uncropped sensor output.
        float bufferW = isLandscape ? 1440f : 1080f;
        float bufferH = isLandscape ? 1080f : 1440f;
        
        RectF bufferRect = new RectF(0, 0, bufferW, bufferH);
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();

        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
        float scale = Math.max(
                (float) viewHeight / bufferH,
                (float) viewWidth / bufferW);
        matrix.postScale(scale, scale, centerX, centerY);
        textureView.setTransform(matrix);
    }

    private void selectCamera(boolean rear) {
        if (isStreaming) {
            Toast.makeText(this, "Stop stream to change camera", Toast.LENGTH_SHORT).show();
            return;
        }
        isRearCamera = rear;
        tvCameraBadge.setText(rear ? "REAR" : "FRONT");
        btnRear.setTextColor(0xFFFFFFFF);
        btnRear.setBackgroundResource(rear ? R.drawable.bg_toggle_selected : R.drawable.bg_toggle_unselected);
        btnFront.setTextColor(0xFFFFFFFF);
        btnFront.setBackgroundResource(rear ? R.drawable.bg_toggle_unselected : R.drawable.bg_toggle_selected);
    }

    private void selectOrientation(boolean landscape) {
        if (isStreaming) {
            Toast.makeText(this, "Stop stream to change orientation", Toast.LENGTH_SHORT).show();
            return;
        }
        isLandscape = landscape;
        btnLandscape.setTextColor(0xFFFFFFFF);
        btnLandscape.setBackgroundResource(landscape ? R.drawable.bg_toggle_selected : R.drawable.bg_toggle_unselected);
        btnPortrait.setTextColor(0xFFFFFFFF);
        btnPortrait.setBackgroundResource(landscape ? R.drawable.bg_toggle_unselected : R.drawable.bg_toggle_selected);
        resizePreview();
    }

    private void updateStatusBadge(String status) {
        if (status.contains("Running") || status.contains("Connected")) {
            tvStatusBadge.setText("● LIVE");
            tvStatusBadge.setTextColor(0xFF4CAF50);
        } else if (status.contains("Error")) {
            tvStatusBadge.setText("● ERROR");
            tvStatusBadge.setTextColor(0xFFFF5252);
        }
    }

    private void startStreaming() {
        if (!hasPermissions()) { requestPermissions(); return; }

        int port = 8554;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
            if (port < 1024 || port > 65535) { port = 8554; etPort.setText("8554"); }
        } catch (NumberFormatException e) {
            etPort.setText("8554");
        }

        int lensFacing = isRearCamera ? CameraCharacteristics.LENS_FACING_BACK
                                      : CameraCharacteristics.LENS_FACING_FRONT;

        Intent serviceIntent = new Intent(this, RtspService.class);
        serviceIntent.putExtra("port", port);
        serviceIntent.putExtra("lensFacing", lensFacing);
        serviceIntent.putExtra("isLandscape", isLandscape);
        ContextCompat.startForegroundService(this, serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        isStreaming = true;
        btnStartStop.setText("⏹  Stop Stream");
        btnStartStop.setTextColor(0xFFFFFFFF);
        btnStartStop.setBackgroundResource(R.drawable.bg_stop_button);
        tvStatusBadge.setText("● LIVE");
        tvStatusBadge.setTextColor(0xFF4CAF50);
    }

    private void stopStreaming() {
        if (isBound) {
            rtspService.stopStream();
            unbindService(serviceConnection);
            isBound = false;
        }
        stopService(new Intent(this, RtspService.class));

        isStreaming = false;
        btnStartStop.setText("▶  Start Stream");
        btnStartStop.setTextColor(0xFFFFFFFF);
        btnStartStop.setBackgroundResource(R.drawable.bg_start_button);
        tvStatusBadge.setText("● IDLE");
        tvStatusBadge.setTextColor(0xFF888888);
        currentUrl = "";
        tvUrl.setText("Start stream to get URL");
        tvUrl.setTextColor(0xFF555555);
    }

    private String getDeviceIp() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network network = cm.getActiveNetwork();
            if (network != null) {
                LinkProperties lp = cm.getLinkProperties(network);
                if (lp != null) {
                    for (LinkAddress addr : lp.getLinkAddresses()) {
                        if (addr.getAddress() instanceof Inet4Address
                                && !addr.getAddress().isLoopbackAddress()) {
                            return addr.getAddress().getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private boolean hasPermissions() {
        for (String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Permissions required to stream camera",
                        Toast.LENGTH_LONG).show();
                    return;
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }
}
