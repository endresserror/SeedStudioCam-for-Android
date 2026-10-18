# XIAO Cam

Seeed XIAO ESP32S3 Sense用ファームウェアとAndroidアプリです。BLEでカメラを操作し、Wi-Fiで映像と音声を受信します。

## Wi-Fiの設定

カメラはSSID `XIAO-Cam-Video` のWPA2アクセスポイントを起動し、`192.168.4.1:8080` で映像と音声を配信します。

使用前に、次の2ファイルにある `REPLACE_WITH_UNIQUE_PASSWORD` を、共通のWPA2パスフレーズ（8〜63文字）に置き換えてください。

- `firmware/src/hybrid_main.cpp`
- `android/app/src/main/java/com/endresserror/xiaoblecam/HybridActivity.java`

パスフレーズは公開リポジトリにコミットしないでください。

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



## ライセンス

犯罪利用以外でしたらお好きにしてください。
