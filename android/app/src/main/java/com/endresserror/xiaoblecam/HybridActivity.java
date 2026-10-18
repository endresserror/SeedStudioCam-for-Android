package com.endresserror.xiaoblecam;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.UUID;

/** Wi-Fi carries media; BLE carries connection and camera commands. */
public final class HybridActivity extends Activity {
    private static final String WIFI_SSID = "XIAO-Cam-Video";
    private static final String WIFI_PASSWORD = "REPLACE_WITH_UNIQUE_PASSWORD";
    private static final UUID SERVICE = UUID.fromString("8bbd0001-4b55-4f6b-8a8c-7a5f0d9b1000");
    private static final UUID CONTROL = UUID.fromString("8bbd0003-4b55-4f6b-8a8c-7a5f0d9b1000");
    private static final int REQ_PERMISSIONS = 20;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private ImageView image;
    private LinearLayout rootLayout;
    private Button connectButton;
    private Button qualityButton;
    private Button fullScreenButton;
    private Button recordButton;
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic control;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private Network wifiNetwork;
    private volatile Socket mediaSocket;
    private volatile boolean running;
    private AudioTrack audioTrack;
    private int frameSize = 5; // QVGA starts in motion mode; VGA remains available for detail.
    private int frames;
    private long lastFrameMs;
    private int audioPackets;
    private boolean scanning;
    private boolean connecting;
    private boolean manualDisconnect;
    private boolean fullScreen;
    private volatile int wifiRetryCount;
    private int lastFrameWidth;
    private int lastFrameHeight;
    private Mp4Recorder recorder;
    private long recordingStartedAtMs;
    private final Runnable recordingClock = new Runnable() {
        @Override public void run() {
            if (recorder == null) return;
            long seconds = Math.max(0, (System.currentTimeMillis() - recordingStartedAtMs) / 1000);
            recordButton.setText(String.format(java.util.Locale.US, "■ 録画中 %02d:%02d", seconds / 60, seconds % 60));
            ui.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        if (state != null && state.getBoolean("fullScreen")) setFullScreen(true);
        BluetoothManager manager = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        connectivity = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        connectButton.setOnClickListener(v -> {
            if (gatt != null || scanning) disconnect(); else ensurePermissionsAndScan();
        });
        qualityButton.setOnClickListener(v -> toggleImageMode());
        recordButton.setOnClickListener(v -> toggleRecording());
        ensurePermissionsAndScan();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("fullScreen", fullScreen);
        super.onSaveInstanceState(state);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); rootLayout = root; root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(16, 12, 16, 12);
        status = new TextView(this); status.setText("XIAO Cam • Wi-Fi映像 / BLE操作"); status.setTextSize(16);
        connectButton = new Button(this); connectButton.setText("カメラを検索");
        qualityButton = new Button(this); qualityButton.setText("QVGA動体 • JPEG品質16");
        fullScreenButton = new Button(this); fullScreenButton.setText("全画面表示");
        recordButton = new Button(this); recordButton.setText("● 録画開始");
        image = new ImageView(this); image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(status); root.addView(connectButton); root.addView(qualityButton); root.addView(fullScreenButton); root.addView(recordButton);
        root.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        fullScreenButton.setOnClickListener(v -> setFullScreen(!fullScreen));
        image.setOnClickListener(v -> { if (fullScreen) setFullScreen(false); });
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 33) return new String[]{Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES};
        if (Build.VERSION.SDK_INT >= 31) return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private void ensurePermissionsAndScan() {
        for (String permission : requiredPermissions()) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(requiredPermissions(), REQ_PERMISSIONS); return;
        }
        if (adapter == null || !adapter.isEnabled()) { setStatus("Bluetoothを有効にしてください"); return; }
        startScan();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_PERMISSIONS) {
            boolean granted = true; for (int result : results) granted &= result == PackageManager.PERMISSION_GRANTED;
            if (granted) ensurePermissionsAndScan(); else setStatus("Bluetooth権限が必要です");
        }
    }

    private void startScan() {
        if (scanning || adapter == null) return;
        manualDisconnect = false;
        scanning = true; connectButton.setText("検索中…"); setStatus("BLEカメラを検索中…");
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        adapter.getBluetoothLeScanner().startScan(null, settings, scanCallback);
        ui.postDelayed(() -> { if (scanning) { stopScan(); connectButton.setText("再検索"); setStatus("見つかりません。カメラの電源を確認してください"); } }, 15000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            BluetoothDevice device = result.getDevice();
            boolean serviceMatch = result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null
                    && result.getScanRecord().getServiceUuids().stream().anyMatch(id -> SERVICE.equals(id.getUuid()));
            if (!"XIAO-Cam".equals(name) && !serviceMatch) return;
            synchronized (HybridActivity.this) { if (connecting || gatt != null) return; connecting = true; }
            stopScan(); setStatus("Bluetooth操作リンクを接続中…");
            BluetoothGatt resultGatt = device.connectGatt(HybridActivity.this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            synchronized (HybridActivity.this) { gatt = resultGatt; if (resultGatt == null) connecting = false; }
        }
        @Override public void onScanFailed(int errorCode) { scanning = false; setStatus("BLE検索エラー " + errorCode); }
    };

    private void stopScan() {
        if (scanning && adapter != null && adapter.getBluetoothLeScanner() != null) adapter.getBluetoothLeScanner().stopScan(scanCallback);
        scanning = false;
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt connection, int statusCode, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connecting = false; connection.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ui.post(() -> {
                    boolean retry = !manualDisconnect && !isFinishing();
                    manualDisconnect = false;
                    releaseAll(); setStatus(statusCode == BluetoothGatt.GATT_SUCCESS ? "カメラとのBLE接続が切れました" : "BLE再接続を準備中…");
                    if (retry) ui.postDelayed(HybridActivity.this::startScan, Math.min(5000, 800 + wifiRetryCount * 300));
                });
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt connection, int statusCode) {
            android.bluetooth.BluetoothGattService service = connection.getService(SERVICE);
            control = service == null ? null : service.getCharacteristic(CONTROL);
            if (control == null) { ui.post(() -> setStatus("BLE制御サービスが見つかりません")); return; }
            writeControl("SUBSCRIBE");
            ui.postDelayed(() -> writeControl("START"), 150);
            ui.postDelayed(() -> applyImageMode(frameSize), 450);
            ui.post(() -> { connectButton.setText("切断"); startAudio(); requestCameraWifi(); });
        }
    };

    private void writeControl(String command) {
        BluetoothGatt connection = gatt; BluetoothGattCharacteristic characteristic = control;
        if (connection == null || characteristic == null) return;
        byte[] value = command.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (Build.VERSION.SDK_INT >= 33) connection.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        else { characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); characteristic.setValue(value); connection.writeCharacteristic(characteristic); }
    }

    private void requestCameraWifi() {
        if (Build.VERSION.SDK_INT < 29) { setStatus("カメラWi-Fi接続にはAndroid 10以降が必要です"); return; }
        if (wifiCallback != null) return;
        // If the user has already joined the camera AP in Android Settings,
        // probe Wi-Fi networks directly. A manually selected network is not
        // always delivered to a WifiNetworkSpecifier request on every build.
        try {
            ArrayList<Network> wifiNetworks = new ArrayList<>();
            for (Network candidate : connectivity.getAllNetworks()) {
                NetworkCapabilities caps = connectivity.getNetworkCapabilities(candidate);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifiNetworks.add(candidate);
            }
            if (!wifiNetworks.isEmpty()) {
                Thread probe = new Thread(() -> {
                    for (Network candidate : wifiNetworks) {
                        try (Socket socket = candidate.getSocketFactory().createSocket()) {
                            socket.connect(new InetSocketAddress("192.168.4.1", 8080), 1500);
                            wifiNetwork = candidate;
                            startMediaSocket(candidate);
                            return;
                        } catch (Exception ignored) { }
                    }
                    ui.post(this::requestCameraWifiSpecifier);
                }, "xiao-wifi-probe");
                probe.setDaemon(true);
                probe.start();
                return;
            }
        } catch (SecurityException ignored) { }
        requestCameraWifiSpecifier();
    }

    private void requestCameraWifiSpecifier() {
        if (wifiCallback != null || gatt == null) return;
        setStatus("画面の確認でカメラWi-Fiへ接続してください…");
        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder().setSsid(WIFI_SSID).setWpa2Passphrase(WIFI_PASSWORD).build();
        NetworkRequest request = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build();
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                wifiNetwork = network; startMediaSocket(network);
            }
            @Override public void onLost(Network network) {
                if (wifiNetwork == network) {
                    wifiNetwork = null; closeMediaSocket();
                    ui.post(() -> { setStatus("カメラWi-Fiを再接続中…"); resetWifiRequestAndRetry(); });
                }
            }
            @Override public void onUnavailable() {
                ui.post(() -> { setStatus("カメラWi-Fiへ再接続中…"); resetWifiRequestAndRetry(); });
            }
        };
        connectivity.requestNetwork(request, wifiCallback, 30000);
        wifiRetryCount++;
    }

    private void resetWifiRequestAndRetry() {
        ConnectivityManager.NetworkCallback old = wifiCallback;
        wifiCallback = null;
        if (old != null && connectivity != null) { try { connectivity.unregisterNetworkCallback(old); } catch (Exception ignored) { } }
        int delay = Math.min(10000, 800 * (1 << Math.min(wifiRetryCount, 3)));
        ui.postDelayed(() -> { if (gatt != null && wifiCallback == null) requestCameraWifi(); }, delay);
    }

    private void startMediaSocket(Network network) {
        closeMediaSocket(); running = true;
        Thread reader = new Thread(() -> {
            long retryDelayMs = 300;
            long lastPayloadAtMs = System.currentTimeMillis();
            while (running && network == wifiNetwork) {
                Socket socket = null;
                try {
                    socket = network.getSocketFactory().createSocket();
                    socket.connect(new InetSocketAddress("192.168.4.1", 8080), 5000);
                    socket.setSoTimeout(4000); mediaSocket = socket;
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    retryDelayMs = 300;
                    wifiRetryCount = 0;
                    ui.post(() -> setStatus("Wi-Fi映像・音声受信中 • BLE操作接続済み"));
                    while (running && network == wifiNetwork) {
                        byte[] header = new byte[24]; input.readFully(header);
                        if (header[0] != 'X' || header[1] != 'S' || header[2] != 'A' || header[3] != 'V' || header[4] != 1) throw new java.io.IOException("invalid media header");
                        ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                        int type = Byte.toUnsignedInt(header[5]); long length = Integer.toUnsignedLong(h.getInt(20));
                        if (length > 2_000_000) throw new java.io.IOException("invalid media length");
                        byte[] payload = new byte[(int)length]; input.readFully(payload);
                        lastPayloadAtMs = System.currentTimeMillis();
                        wifiRetryCount = 0;
                        if (type == 1) showJpeg(payload); else if (type == 4) playMulaw(payload);
                    }
                } catch (Exception error) {
                    if (running && network == wifiNetwork) ui.post(() -> setStatus("映像リンクを再接続中…"));
                } finally {
                    if (socket != null) try { socket.close(); } catch (Exception ignored) { }
                    if (mediaSocket == socket) mediaSocket = null;
                }
                if (running && network == wifiNetwork && System.currentTimeMillis() - lastPayloadAtMs >= 8000) {
                    wifiNetwork = null;
                    closeMediaSocket();
                    ui.post(() -> { setStatus("映像データが止まったためWi-Fiを再接続中…"); resetWifiRequestAndRetry(); });
                    return;
                }
                if (running && network == wifiNetwork) try { Thread.sleep(retryDelayMs); retryDelayMs = Math.min(5000, retryDelayMs * 2); } catch (InterruptedException ignored) { return; }
            }
        }, "xiao-wifi-media");
        reader.setDaemon(true); reader.start();
    }

    private void showJpeg(byte[] jpeg) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bitmap == null) return;
        long now = System.currentTimeMillis(); frames++;
        lastFrameWidth = bitmap.getWidth(); lastFrameHeight = bitmap.getHeight();
        Mp4Recorder activeRecorder = recorder;
        if (activeRecorder != null) activeRecorder.offerVideo(bitmap);
        int fps = lastFrameMs == 0 ? 0 : (int)(1000 / Math.max(1, now - lastFrameMs)); lastFrameMs = now;
        ui.post(() -> { image.setImageBitmap(bitmap); setStatus("映像受信中 • " + bitmap.getWidth() + "×" + bitmap.getHeight() + " • 約" + fps + " fps • 音声 " + audioPackets + " blocks • BLE操作"); });
    }

    private void startAudio() {
        int min = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, 8000, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 4096), AudioTrack.MODE_STREAM);
        audioTrack.play();
    }

    private void playMulaw(byte[] bytes) {
        AudioTrack track = audioTrack; if (track == null) return;
        short[] pcm = new short[bytes.length];
        for (int i = 0; i < bytes.length; i++) pcm[i] = decodeMulaw(bytes[i]);
        track.write(pcm, 0, pcm.length, AudioTrack.WRITE_NON_BLOCKING); audioPackets++;
        Mp4Recorder activeRecorder = recorder;
        if (activeRecorder != null) activeRecorder.offerAudio(pcm);
    }

    private void toggleRecording() {
        if (recorder != null) {
            Mp4Recorder current = recorder; recorder = null;
            ui.removeCallbacks(recordingClock);
            recordButton.setText("保存中…"); recordButton.setEnabled(false);
            current.stop();
            return;
        }
        if (lastFrameWidth <= 0 || lastFrameHeight <= 0) {
            Toast.makeText(this, "映像を受信してから録画を開始してください", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            recorder = Mp4Recorder.create(this, lastFrameWidth, lastFrameHeight, new Mp4Recorder.Listener() {
                @Override public void onComplete(String displayName) {
                    ui.post(() -> {
                        recordButton.setEnabled(true); recordButton.setText("● 録画開始");
                        Toast.makeText(HybridActivity.this, "録画をMovies/XIAO Camに保存しました: " + displayName, Toast.LENGTH_LONG).show();
                    });
                }
                @Override public void onError(String message) {
                    ui.post(() -> {
                        recordButton.setEnabled(true); recordButton.setText("● 録画開始");
                        Toast.makeText(HybridActivity.this, message, Toast.LENGTH_LONG).show();
                    });
                }
            });
            recordingStartedAtMs = System.currentTimeMillis();
            recordButton.setText("■ 録画中 00:00"); ui.postDelayed(recordingClock, 1000);
        } catch (Exception error) {
            recorder = null;
            Toast.makeText(this, "録画を開始できません: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static short decodeMulaw(byte raw) {
        int u = (~raw) & 0xff, value = ((u & 0x0f) << 3) + 0x84;
        value <<= (u & 0x70) >> 4;
        return (short)(((u & 0x80) != 0) ? (0x84 - value) : (value - 0x84));
    }

    private void toggleImageMode() {
        if (control == null) return;
        frameSize = frameSize == 8 ? 5 : frameSize == 5 ? 4 : 8;
        applyImageMode(frameSize);
        String label = imageModeLabel(frameSize);
        qualityButton.setText(label);
        setStatus(label + "へ切替中…");
    }

    private void applyImageMode(int size) {
        int quality = size == 8 ? 10 : size == 5 ? 16 : 20;
        writeControl("SET size " + size);
        ui.postDelayed(() -> writeControl("SET quality " + quality), 140);
        ui.postDelayed(() -> writeControl("SET motion " + (size == 8 ? 0 : 1)), 280);
    }

    private String imageModeLabel(int size) {
        if (size == 8) return "VGA高画質 • JPEG品質10";
        if (size == 5) return "QVGA動体 • JPEG品質16";
        return "QQVGA高速 • JPEG品質20";
    }

    private void setFullScreen(boolean enabled) {
        fullScreen = enabled;
        setRequestedOrientation(enabled ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (enabled) flags |= View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        getWindow().setFlags(enabled ? WindowManager.LayoutParams.FLAG_FULLSCREEN : 0, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        image.setScaleType(enabled ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(!enabled);
        rootLayout.setPadding(enabled ? 0 : 16, enabled ? 0 : 12, enabled ? 0 : 16, enabled ? 0 : 12);
        status.setVisibility(enabled ? View.GONE : View.VISIBLE);
        connectButton.setVisibility(enabled ? View.GONE : View.VISIBLE);
        qualityButton.setVisibility(enabled ? View.GONE : View.VISIBLE);
        fullScreenButton.setVisibility(enabled ? View.GONE : View.VISIBLE);
        recordButton.setVisibility(enabled ? View.GONE : View.VISIBLE);
    }

    private void closeMediaSocket() {
        running = false; Socket socket = mediaSocket; mediaSocket = null;
        if (socket != null) try { socket.close(); } catch (Exception ignored) { }
    }

    private void disconnect() {
        manualDisconnect = true;
        Mp4Recorder activeRecorder = recorder;
        if (activeRecorder != null) { recorder = null; activeRecorder.stop(); }
        ui.removeCallbacks(recordingClock);
        stopScan(); writeControl("STOP"); closeMediaSocket();
        if (wifiCallback != null && connectivity != null) { try { connectivity.unregisterNetworkCallback(wifiCallback); } catch (Exception ignored) { } wifiCallback = null; }
        wifiNetwork = null;
        BluetoothGatt current = gatt;
        if (current != null) { current.disconnect(); current.close(); }
        releaseAll();
    }

    private void releaseAll() {
        closeMediaSocket();
        if (wifiCallback != null && connectivity != null) {
            try { connectivity.unregisterNetworkCallback(wifiCallback); } catch (Exception ignored) { }
            wifiCallback = null;
        }
        BluetoothGatt current = gatt; gatt = null; control = null;
        if (current != null) current.close();
        wifiNetwork = null; frames = 0; lastFrameMs = 0; audioPackets = 0; frameSize = 5;
        qualityButton.setText(imageModeLabel(frameSize)); connectButton.setText("カメラを検索");
        if (audioTrack != null) { audioTrack.pause(); audioTrack.flush(); audioTrack.release(); audioTrack = null; }
    }

    private void setStatus(String value) { status.setText(value); }
    @Override public void onBackPressed() { if (fullScreen) setFullScreen(false); else super.onBackPressed(); }
    @Override protected void onDestroy() { disconnect(); super.onDestroy(); }
}
