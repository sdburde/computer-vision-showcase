package com.rtspcamera;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.view.Surface;
import androidx.core.app.NotificationCompat;
import java.net.Inet4Address;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RtspService extends Service {

    private static final String TAG = "RtspService";
    private static final String CHANNEL_ID = "RtspCameraChannel";
    private static final int NOTIFICATION_ID = 1;

    private final IBinder binder = new LocalBinder();
    private ExecutorService executor;
    private ServerSocket serverSocket;
    private boolean running = false;
    private Surface previewSurface;
    private StatusCallback statusCallback;
    private CameraStreamer cameraStreamer;
    private int rtspPort = 8554;
    private int lensFacing = CameraCharacteristics.LENS_FACING_BACK;
    private boolean isLandscape = true;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public interface StatusCallback {
        void onStatusChanged(String status);
        void onUrlChanged(String url);
    }

    public class LocalBinder extends Binder {
        RtspService getService() { return RtspService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        executor = Executors.newCachedThreadPool();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            rtspPort = intent.getIntExtra("port", 8554);
            lensFacing = intent.getIntExtra("lensFacing", CameraCharacteristics.LENS_FACING_BACK);
            isLandscape = intent.getBooleanExtra("isLandscape", true);
        }
        startForeground(NOTIFICATION_ID, buildNotification("Starting RTSP server..."));
        startRtspServer();
        return START_NOT_STICKY;
    }

    private void startRtspServer() {
        // Acquire WakeLock to keep CPU running even when screen is off
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RtspCamera:CpuLock");
            wakeLock.acquire();
        }

        // Acquire WifiLock to keep network alive and high-performing
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifiManager != null) {
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RtspCamera:WifiLock");
            wifiLock.acquire();
        }

        executor.submit(() -> {
            try {
                serverSocket = new ServerSocket(rtspPort);
                running = true;

                String ip = getDeviceIp();
                String rtspUrl = "rtsp://" + ip + ":" + rtspPort + "/live";
                updateNotification("Streaming at " + rtspUrl);

                if (statusCallback != null) {
                    statusCallback.onStatusChanged("Running");
                    statusCallback.onUrlChanged(rtspUrl);
                }

                // Start unconditionally — previewSurface may be null yet; setPreviewSurface()
                // will add it to the capture session once the TextureView is ready.
                cameraStreamer = new CameraStreamer(this, previewSurface, lensFacing, isLandscape);
                cameraStreamer.start();

                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        Log.d(TAG, "Client connected: " + client.getInetAddress());
                        if (statusCallback != null)
                            statusCallback.onStatusChanged("Connected");
                        executor.submit(new RtspClientHandler(client, cameraStreamer));
                    } catch (Exception e) {
                        if (running) Log.e(TAG, "Client error", e);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Server error", e);
                if (statusCallback != null)
                    statusCallback.onStatusChanged("Error: " + e.getMessage());
            }
        });
    }

    public void stopStream() {
        running = false;
        if (cameraStreamer != null) cameraStreamer.stop();
        
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
            wifiLock = null;
        }

        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception e) {
            Log.e(TAG, "Error closing server", e);
        }
    }

    public void setPreviewSurface(Surface surface) {
        this.previewSurface = surface;
        if (cameraStreamer != null) cameraStreamer.setPreviewSurface(surface);
    }

    public void setStatusCallback(StatusCallback cb) {
        this.statusCallback = cb;
        if (running) {
            String ip = getDeviceIp();
            cb.onStatusChanged("Running");
            cb.onUrlChanged("rtsp://" + ip + ":" + rtspPort + "/live");
        }
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
        } catch (Exception e) {
            Log.e(TAG, "getDeviceIp failed", e);
        }
        return "127.0.0.1";
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "RTSP Camera Stream", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps RTSP camera stream running");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RTSP Camera")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        stopStream();
        executor.shutdown();
        super.onDestroy();
    }
}
