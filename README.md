# XIAO Cam

Seeed XIAO ESP32S3 Sense用ファームウェアとAndroidアプリです。BLEでカメラを操作し、Wi-Fiで映像と音声を受信します。

## Wi-Fiの設定

カメラはSSID `XIAO-Cam-Video` のWPA2アクセスポイントを起動し、`192.168.4.1:8080` で映像と音声を配信します。

使用する端末のビルド前に、次の2ファイルにある `REPLACE_WITH_UNIQUE_PASSWORD` を、同じWPA2パスフレーズ（8〜63文字）に置き換えてください。変更はローカルの作業コピーにとどめ、パスフレーズを公開リポジトリへコミットしないでください。

- `firmware/src/hybrid_main.cpp`
- `android/app/src/main/java/com/endresserror/xiaoblecam/HybridActivity.java`

## ビルド

必要なツールはPlatformIO Core、JDK 17、Android SDK 35です。対象ボードはSeeed XIAO ESP32S3 Senseです。他のESP32カメラでは、ピン配置やマイク設定の変更が必要です。

ファームウェアは `firmware` ディレクトリでビルドします。

```sh
pio run -e xiao_esp32s3_sense_hybrid
```

Androidアプリは `android` ディレクトリでビルドします。

```sh
./gradlew assembleDebug
```



## BLEペアリング

アプリの初回接続時にAndroidのペア設定画面が表示されます。承認すると、BLE接続が暗号化され、端末にボンディング情報が保存されます。XIAO ESP32S3 Senseには画面や入力器がないため、初回ペアリングは確認コードを使わない方式です。信頼できる場所で、接続する端末を確認してから承認してください。ペアリングに失敗した場合は、AndroidのBluetooth設定からXIAO Camの登録を削除し、再接続してください。

BLEの制御と映像・音声通知には暗号化された接続が必要です。Wi-Fi映像・音声ストリームはカメラのWPA2アクセスポイントを通じて送信されます。

## ライセンス

犯罪利用以外でしたらお好きにしてください。
