package com.endresserror.xiaoblecam;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Encodes received JPEGs and decoded G.711 audio to an MP4 without blocking the stream reader. */
final class Mp4Recorder {
    interface Listener {
        void onComplete(String displayName);
        void onError(String message);
    }

    private static final int AUDIO_RATE = 8000;
    private static final int MAX_PENDING_SAMPLES = 256;
    private final Context context;
    private final Listener listener;
    private final int width;
    private final int height;
    private final ArrayBlockingQueue<Input> inputs = new ArrayBlockingQueue<>(96);
    private final ArrayDeque<Sample> pending = new ArrayDeque<>();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final MediaCodec videoCodec;
    private final MediaCodec audioCodec;
    private final android.view.Surface inputSurface;
    private final MediaMuxer muxer;
    private final Uri outputUri;
    private final File outputFile;
    private final ParcelFileDescriptor outputDescriptor;
    private final String displayName;
    private final Thread worker;
    private volatile boolean stopping;
    private volatile boolean finished;
    private boolean muxerStarted;
    private boolean videoEos;
    private boolean audioEos;
    private boolean audioInputEnded;
    private int videoTrack = -1;
    private int audioTrack = -1;
    private long audioPtsUs = System.nanoTime() / 1000L;
    private long droppedInputs;

    static Mp4Recorder create(Context context, int width, int height, Listener listener) throws IOException {
        return new Mp4Recorder(context, width, height, listener);
    }

    private Mp4Recorder(Context context, int width, int height, Listener listener) throws IOException {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.width = width;
        this.height = height;
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        this.displayName = "XIAO_Cam_" + stamp + ".mp4";
        Uri uri = null;
        File file = null;
        ParcelFileDescriptor descriptor = null;
        MediaMuxer createdMuxer = null;
        MediaCodec createdVideo = null;
        MediaCodec createdAudio = null;
        android.view.Surface createdSurface = null;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/XIAO Cam");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
                uri = this.context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("録画ファイルを作成できません");
                descriptor = this.context.getContentResolver().openFileDescriptor(uri, "rw");
                if (descriptor == null) throw new IOException("録画ファイルを開けません");
                createdMuxer = new MediaMuxer(descriptor.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } else {
                File directory = this.context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                if (directory == null) throw new IOException("録画フォルダーを利用できません");
                if (!directory.exists() && !directory.mkdirs()) throw new IOException("録画フォルダーを作成できません");
                file = new File(directory, displayName);
                createdMuxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            }

            MediaFormat videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(700_000, width * height * 6));
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 10);
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            createdVideo = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            createdVideo.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            createdSurface = createdVideo.createInputSurface();
            createdVideo.start();

            MediaFormat audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_RATE, 1);
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 32_000);
            audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);
            createdAudio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            createdAudio.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            createdAudio.start();
        } catch (Exception failure) {
            if (createdVideo != null) { try { createdVideo.stop(); } catch (Exception ignored) { } try { createdVideo.release(); } catch (Exception ignored) { } }
            if (createdAudio != null) { try { createdAudio.stop(); } catch (Exception ignored) { } try { createdAudio.release(); } catch (Exception ignored) { } }
            if (createdSurface != null) createdSurface.release();
            if (createdMuxer != null) { try { createdMuxer.release(); } catch (Exception ignored) { } }
            if (descriptor != null) { try { descriptor.close(); } catch (Exception ignored) { } }
            if (uri != null) this.context.getContentResolver().delete(uri, null, null);
            if (failure instanceof IOException) throw (IOException)failure;
            throw new IOException("録画エンコーダーを開始できません", failure);
        }
        this.outputUri = uri;
        this.outputFile = file;
        this.outputDescriptor = descriptor;
        this.muxer = createdMuxer;
        this.videoCodec = createdVideo;
        this.audioCodec = createdAudio;
        this.inputSurface = createdSurface;
        this.worker = new Thread(this::runEncoder, "xiao-mp4-encoder");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    void offerVideo(Bitmap bitmap) {
        if (!stopping && bitmap != null && !inputs.offer(new VideoInput(bitmap))) droppedInputs++;
    }

    void offerAudio(short[] pcm) {
        if (!stopping && pcm != null && !inputs.offer(new AudioInput(pcm.clone()))) droppedInputs++;
    }

    void stop() {
        if (stopping || finished) return;
        stopping = true;
    }

    private void runEncoder() {
        String error = null;
        try {
            while (!stopping || !inputs.isEmpty()) {
                Input input = inputs.poll(20, TimeUnit.MILLISECONDS);
                if (input instanceof VideoInput) drawVideo(((VideoInput)input).bitmap);
                else if (input instanceof AudioInput) feedAudio(((AudioInput)input).pcm);
                drain(videoCodec, true);
                drain(audioCodec, false);
            }
            videoCodec.signalEndOfInputStream();
            signalAudioEnd();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while ((!videoEos || !audioEos) && System.nanoTime() < deadline) {
                drain(videoCodec, true);
                drain(audioCodec, false);
                Thread.sleep(5);
            }
            if (!muxerStarted) throw new IOException("映像または音声データがありません");
            flushPending();
        } catch (Exception failure) {
            error = failure.getMessage() == null ? "録画に失敗しました" : failure.getMessage();
        } finally {
            try { inputSurface.release(); } catch (Exception ignored) { }
            try { videoCodec.stop(); } catch (Exception ignored) { }
            try { audioCodec.stop(); } catch (Exception ignored) { }
            try { videoCodec.release(); } catch (Exception ignored) { }
            try { audioCodec.release(); } catch (Exception ignored) { }
            if (muxerStarted) { try { muxer.stop(); } catch (Exception ignored) { } }
            try { muxer.release(); } catch (Exception ignored) { }
            if (outputDescriptor != null) { try { outputDescriptor.close(); } catch (Exception ignored) { } }
            if (error == null && muxerStarted) {
                if (outputUri != null) {
                    ContentValues values = new ContentValues(); values.put(MediaStore.Video.Media.IS_PENDING, 0);
                    context.getContentResolver().update(outputUri, values, null, null);
                }
                notifyComplete();
            } else {
                if (outputUri != null) context.getContentResolver().delete(outputUri, null, null);
                if (outputFile != null) outputFile.delete();
                notifyError(error == null ? "録画に失敗しました" : error);
            }
            finished = true;
        }
    }

    private void drawVideo(Bitmap bitmap) {
        Canvas canvas = inputSurface.lockCanvas(null);
        try {
            canvas.drawColor(Color.BLACK);
            float scale = Math.min((float)width / bitmap.getWidth(), (float)height / bitmap.getHeight());
            float left = (width - bitmap.getWidth() * scale) / 2f;
            float top = (height - bitmap.getHeight() * scale) / 2f;
            canvas.drawBitmap(bitmap, null, new RectF(left, top, left + bitmap.getWidth() * scale, top + bitmap.getHeight() * scale), paint);
        } finally {
            inputSurface.unlockCanvasAndPost(canvas);
        }
    }

    private void feedAudio(short[] pcm) throws Exception {
        int offset = 0;
        while (offset < pcm.length) {
            int index = audioCodec.dequeueInputBuffer(10_000);
            if (index < 0) continue;
            ByteBuffer input = audioCodec.getInputBuffer(index);
            if (input == null) { audioCodec.queueInputBuffer(index, 0, 0, audioPtsUs, 0); continue; }
            input.clear(); input.order(ByteOrder.LITTLE_ENDIAN);
            int samples = Math.min(pcm.length - offset, input.remaining() / 2);
            for (int i = 0; i < samples; i++) input.putShort(pcm[offset + i]);
            audioCodec.queueInputBuffer(index, 0, samples * 2, audioPtsUs, 0);
            audioPtsUs += samples * 1_000_000L / AUDIO_RATE;
            offset += samples;
        }
    }

    private void signalAudioEnd() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!audioInputEnded && System.nanoTime() < deadline) {
            int index = audioCodec.dequeueInputBuffer(10_000);
            if (index >= 0) {
                audioCodec.queueInputBuffer(index, 0, 0, audioPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                audioInputEnded = true;
            }
        }
    }

    private void drain(MediaCodec codec, boolean video) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int index = codec.dequeueOutputBuffer(info, 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                int track = muxer.addTrack(codec.getOutputFormat());
                if (video) videoTrack = track; else audioTrack = track;
                maybeStartMuxer();
                continue;
            }
            if (index < 0) continue;
            ByteBuffer output = codec.getOutputBuffer(index);
            if (output != null && info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                byte[] bytes = new byte[info.size];
                output.position(info.offset); output.limit(info.offset + info.size); output.get(bytes);
                Sample sample = new Sample(video, bytes, info.presentationTimeUs, info.flags);
                if (muxerStarted) writeSample(sample); else if (pending.size() < MAX_PENDING_SAMPLES) pending.addLast(sample);
            }
            boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            codec.releaseOutputBuffer(index, false);
            if (eos) { if (video) videoEos = true; else audioEos = true; }
        }
    }

    private void maybeStartMuxer() throws Exception {
        if (!muxerStarted && videoTrack >= 0 && audioTrack >= 0) {
            muxer.start(); muxerStarted = true; flushPending();
        }
    }

    private void flushPending() throws Exception {
        if (!muxerStarted) return;
        while (!pending.isEmpty()) writeSample(pending.removeFirst());
    }

    private void writeSample(Sample sample) throws Exception {
        int track = sample.video ? videoTrack : audioTrack;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, sample.bytes.length, sample.ptsUs, sample.flags);
        muxer.writeSampleData(track, ByteBuffer.wrap(sample.bytes), info);
    }

    private void notifyComplete() {
        if (listener != null) listener.onComplete(displayName);
    }

    private void notifyError(String message) {
        if (listener != null) listener.onError(message);
    }

    private interface Input { }
    private static final class VideoInput implements Input { final Bitmap bitmap; VideoInput(Bitmap bitmap) { this.bitmap = bitmap; } }
    private static final class AudioInput implements Input { final short[] pcm; AudioInput(short[] pcm) { this.pcm = pcm; } }
    private static final class Sample {
        final boolean video; final byte[] bytes; final long ptsUs; final int flags;
        Sample(boolean video, byte[] bytes, long ptsUs, int flags) { this.video = video; this.bytes = bytes; this.ptsUs = ptsUs; this.flags = flags; }
    }
}
