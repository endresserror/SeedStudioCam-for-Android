package com.endresserror.xiaoblecam;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

public final class MainActivity extends Activity {
    private static final UUID SERVICE = UUID.fromString("8bbd0001-4b55-4f6b-8a8c-7a5f0d9b1000");
    private static final UUID STREAM = UUID.fromString("8bbd0002-4b55-4f6b-8a8c-7a5f0d9b1000");
    private static final UUID CONTROL = UUID.fromString("8bbd0003-4b55-4f6b-8a8c-7a5f0d9b1000");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int REQ_PERMISSIONS = 20;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private ImageView image;
    private Button connectButton;
    private Button resolutionButton;
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic streamCharacteristic;
    private BluetoothGattCharacteristic controlCharacteristic;
    private AudioTrack audioTrack;
    private Assembly assembly;
    private long lastFrameMs;
    private int frames;
    private int audioPackets;
    private int currentSize = 1;
    private boolean scanning;
    private boolean connecting;

    private static final class Assembly {
        final int id, count;
        final byte[][] parts;
        int received;
        Assembly(int id, int count) { this.id = id; this.count = count; this.parts = new byte[count][]; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        BluetoothManager manager = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        connectButton.setOnClickListener(v -> {
            if (gatt != null || scanning) disconnect(); else ensurePermissionsAndScan();
        });
        resolutionButton.setOnClickListener(v -> toggleResolution());
        ensurePermissionsAndScan();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18, 18, 18, 18);
        status = new TextView(this); status.setText("XIAO BLE Cam"); status.setTextSize(16);
        connectButton = new Button(this); connectButton.setText("カメラを検索");
        resolutionButton = new Button(this); resolutionButton.setText("320×240 詳細表示");
        image = new ImageView(this); image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(status); root.addView(connectButton); root.addView(resolutionButton); root.addView(image, imageParams);
        setContentView(root);
    }

    private String[] permissions() {
        if (Build.VERSION.SDK_INT >= 31) return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private void ensurePermissionsAndScan() {
        for (String p : permissions()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(permissions(), REQ_PERMISSIONS); return;
        }
        if (adapter == null || !adapter.isEnabled()) { setStatus("Bluetoothを有効にしてください"); return; }
        startScan();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] p, int[] results) {
        super.onRequestPermissionsResult(requestCode, p, results);
        if (requestCode == REQ_PERMISSIONS) {
            boolean granted = true;
            for (int result : results) granted &= result == PackageManager.PERMISSION_GRANTED;
            if (granted) ensurePermissionsAndScan(); else setStatus("BLE検索にはBluetooth権限が必要です");
        }
    }

    private void startScan() {
        if (adapter == null || scanning) return;
        setStatus("XIAO-Camを検索中…"); scanning = true;
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        adapter.getBluetoothLeScanner().startScan(null, settings, scanCallback);
        ui.postDelayed(() -> { if (scanning) { stopScan(); setStatus("見つかりません。ボードの電源とBLEファームウェアを確認してください"); } }, 15000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = result.getScanRecord() != null ? result.getScanRecord().getDeviceName() : null;
            String cachedName = null;
            try { cachedName = device.getName(); } catch (SecurityException ignored) { }
            boolean serviceMatch = result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null
                    && result.getScanRecord().getServiceUuids().stream().anyMatch(id -> SERVICE.equals(id.getUuid()));
            if ("XIAO-Cam".equals(name) || "XIAO-Cam".equals(cachedName) || serviceMatch) {
                synchronized (MainActivity.this) {
                    if (gatt != null || connecting) return;
                    connecting = true;
                }
                stopScan(); setStatus("接続中: " + (name != null ? name : "XIAO-Cam"));
                BluetoothGatt connection = device.connectGatt(MainActivity.this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                synchronized (MainActivity.this) { gatt = connection; if (connection == null) connecting = false; }
            }
        }
        @Override public void onScanFailed(int error) { scanning = false; setStatus("BLE検索エラー: " + error); }
    };

    private void stopScan() {
        if (scanning && adapter != null && adapter.getBluetoothLeScanner() != null) adapter.getBluetoothLeScanner().stopScan(scanCallback);
        scanning = false;
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt connection, int statusCode, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                synchronized (MainActivity.this) { connecting = false; }
                if (!connection.requestMtu(517)) connection.discoverServices();
                ui.post(() -> setStatus("BLE接続済み。サービスを確認中…"));
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized (MainActivity.this) { connecting = false; }
                ui.post(() -> {
                    releaseConnection(); setStatus("切断されました");
                    if (statusCode != BluetoothGatt.GATT_SUCCESS) ui.postDelayed(() -> {
                        if (gatt == null && !scanning) startScan();
                    }, 1000);
                });
            }
        }
        @Override public void onMtuChanged(BluetoothGatt connection, int mtu, int statusCode) {
            connection.discoverServices();
            ui.post(() -> setStatus("BLE接続済み (MTU " + mtu + ")。サービスを確認中…"));
        }
        @Override public void onServicesDiscovered(BluetoothGatt connection, int statusCode) {
            BluetoothGattService service = connection.getService(SERVICE);
            if (service == null) { ui.post(() -> setStatus("XIAO-Camサービスがありません。BLEファームウェアを確認してください")); return; }
            streamCharacteristic = service.getCharacteristic(STREAM);
            controlCharacteristic = service.getCharacteristic(CONTROL);
            connection.setCharacteristicNotification(streamCharacteristic, true);
            BluetoothGattDescriptor descriptor = streamCharacteristic.getDescriptor(CCCD);
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            connection.writeDescriptor(descriptor);
        }
        @Override public void onDescriptorWrite(BluetoothGatt connection, BluetoothGattDescriptor descriptor, int statusCode) {
            writeControl("SUBSCRIBE");
            ui.postDelayed(() -> writeControl("START"), 100);
            ui.post(() -> { connectButton.setText("切断"); setStatus("受信開始。映像とマイク音声を待っています…"); startAudio(); });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, byte[] value) {
            if (STREAM.equals(characteristic.getUuid())) onFragment(value);
        }
        @Override @SuppressWarnings("deprecation") public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic characteristic) {
            onCharacteristicChanged(connection, characteristic, characteristic.getValue());
        }
    };

    private void writeControl(String command) {
        BluetoothGatt connection = gatt;
        BluetoothGattCharacteristic characteristic = controlCharacteristic;
        if (connection == null || characteristic == null) return;
        if (Build.VERSION.SDK_INT >= 33) connection.writeCharacteristic(characteristic, command.getBytes(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        else { characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); characteristic.setValue(command.getBytes()); connection.writeCharacteristic(characteristic); }
    }

    private void toggleResolution() {
        if (gatt == null || controlCharacteristic == null) { setStatus("先にカメラへ接続してください"); return; }
        currentSize = currentSize == 1 ? 5 : 1;
        writeControl("SET size " + currentSize);
        resolutionButton.setText(currentSize == 1 ? "320×240 詳細表示" : "160×120 高速表示");
        setStatus(currentSize == 1 ? "160×120 高速表示に切り替え中…" : "320×240 詳細表示に切り替え中…");
    }

    private synchronized void onFragment(byte[] value) {
        if (value == null || value.length < 8) return;
        ByteBuffer b = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
        int id = Short.toUnsignedInt(b.getShort()), index = Short.toUnsignedInt(b.getShort());
        int count = Short.toUnsignedInt(b.getShort()), length = Short.toUnsignedInt(b.getShort());
        if (count == 0 || count > 4096 || index >= count || length > value.length - 8) return;
        if (assembly == null || assembly.id != id || assembly.count != count) assembly = new Assembly(id, count);
        if (assembly.parts[index] == null) { byte[] part = new byte[length]; System.arraycopy(value, 8, part, 0, length); assembly.parts[index] = part; assembly.received++; }
        if (assembly.received != assembly.count) return;
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        for (byte[] part : assembly.parts) { if (part == null) { assembly = null; return; } packet.write(part, 0, part.length); }
        byte[] data = packet.toByteArray(); assembly = null;
        if (data.length < 24 || data[0] != 'X' || data[1] != 'S' || data[2] != 'A' || data[3] != 'V') return;
        ByteBuffer h = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int type = Byte.toUnsignedInt(data[5]); long payloadLength = Integer.toUnsignedLong(h.getInt(20));
        if (payloadLength != data.length - 24) return;
        if (type == 1) onJpeg(data, 24, (int)payloadLength);
        else if (type == 4) onMulaw(data, 24, (int)payloadLength);
        else if (type == 3) {
            String text = new String(data, 24, (int)payloadLength);
            ui.post(() -> setStatus(text));
        }
    }

    private void onJpeg(byte[] data, int offset, int length) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, offset, length);
        if (bitmap == null) return;
        long now = System.currentTimeMillis(); frames++;
        int fps = lastFrameMs == 0 ? 0 : (int)(1000 / Math.max(1, now - lastFrameMs)); lastFrameMs = now;
        ui.post(() -> { image.setImageBitmap(bitmap); setStatus("映像受信中 • " + bitmap.getWidth() + "×" + bitmap.getHeight() + " • 約" + fps + " fps • 音声ブロック " + audioPackets); });
    }

    private void startAudio() {
        int size = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, 8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(size, 4096), AudioTrack.MODE_STREAM);
        audioTrack.play();
    }

    private void onMulaw(byte[] data, int offset, int length) {
        AudioTrack track = audioTrack; if (track == null || length == 0) return;
        short[] pcm = new short[length];
        for (int i = 0; i < length; i++) pcm[i] = decodeMulaw(data[offset + i]);
        track.write(pcm, 0, pcm.length, AudioTrack.WRITE_NON_BLOCKING); audioPackets++;
    }

    private static short decodeMulaw(byte raw) {
        int u = (~raw) & 0xff, t = ((u & 0x0f) << 3) + 0x84;
        t <<= (u & 0x70) >> 4;
        return (short)(((u & 0x80) != 0) ? (0x84 - t) : (t - 0x84));
    }

    private void disconnect() {
        stopScan(); writeControl("STOP");
        BluetoothGatt current = gatt;
        if (current != null) { current.disconnect(); current.close(); }
        releaseConnection();
    }

    private void releaseConnection() {
        BluetoothGatt current = gatt; gatt = null; streamCharacteristic = null; controlCharacteristic = null;
        if (current != null) current.close();
        assembly = null; lastFrameMs = 0; frames = 0; audioPackets = 0;
        currentSize = 1;
        resolutionButton.setText("320×240 詳細表示");
        if (audioTrack != null) { audioTrack.pause(); audioTrack.flush(); audioTrack.release(); audioTrack = null; }
        connectButton.setText("カメラを検索");
    }

    private void setStatus(String text) { status.setText(text); }
    @Override protected void onDestroy() { disconnect(); super.onDestroy(); }
}
