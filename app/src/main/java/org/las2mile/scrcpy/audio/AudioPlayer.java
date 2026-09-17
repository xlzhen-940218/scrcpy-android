package org.las2mile.scrcpy.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioPlayer {
    private static final String TAG = "AudioPlayer";

    public static final int CODEC_RAW = 0x00726177;
    public static final int CODEC_OPUS = 0x6F707573;
    public static final int CODEC_AAC = 0x00616163;
    public static final int CODEC_FLAC = 0x666C6163;

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private AudioTrack audioTrack;
    private MediaCodec audioDecoder;
    private int codecId;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    public AudioPlayer(int codecId) {
        this.codecId = codecId;
    }

    public void start() {
        int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufferSize = Math.max(minBufferSize, 16384);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG)
                            .build())
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        } else {
            audioTrack = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
            );
        }

        audioTrack.play();
        isRunning.set(true);

        if (codecId == CODEC_OPUS || codecId == CODEC_AAC || codecId == CODEC_FLAC) {
            String mime = codecId == CODEC_OPUS ? "audio/opus" : (codecId == CODEC_AAC ? "audio/mp4a-latm" : "audio/flac");
            try {
                MediaFormat format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, 2);
                audioDecoder = MediaCodec.createDecoderByType(mime);
                audioDecoder.configure(format, null, null, 0);
                audioDecoder.start();
            } catch (Exception e) {
                Log.e(TAG, "Failed to create audio decoder for " + mime, e);
                if (audioDecoder != null) {
                    try {
                        audioDecoder.release();
                    } catch (Exception ignored) {}
                    audioDecoder = null;
                }
            }
        }
    }

    public synchronized void playSample(byte[] data, long pts, boolean isConfig) {
        if (!isRunning.get() || audioTrack == null) {
            return;
        }

        if (codecId == CODEC_RAW || audioDecoder == null) {
            try {
                audioTrack.write(data, 0, data.length);
            } catch (Exception ignored) {}
            return;
        }

        try {
            int inputIndex = audioDecoder.dequeueInputBuffer(10000);
            if (inputIndex >= 0) {
                ByteBuffer inputBuffer;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    inputBuffer = audioDecoder.getInputBuffer(inputIndex);
                } else {
                    inputBuffer = audioDecoder.getInputBuffers()[inputIndex];
                }
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(data);
                    int flags = isConfig ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0;
                    audioDecoder.queueInputBuffer(inputIndex, 0, data.length, pts, flags);
                }
            }

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputIndex = audioDecoder.dequeueOutputBuffer(bufferInfo, 0);
            while (outputIndex >= 0) {
                ByteBuffer outputBuffer;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    outputBuffer = audioDecoder.getOutputBuffer(outputIndex);
                } else {
                    outputBuffer = audioDecoder.getOutputBuffers()[outputIndex];
                }
                if (outputBuffer != null && bufferInfo.size > 0 && audioTrack != null) {
                    outputBuffer.position(bufferInfo.offset);
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                    byte[] pcm = new byte[bufferInfo.size];
                    outputBuffer.get(pcm);
                    audioTrack.write(pcm, 0, pcm.length);
                }
                audioDecoder.releaseOutputBuffer(outputIndex, false);
                outputIndex = audioDecoder.dequeueOutputBuffer(bufferInfo, 0);
            }
        } catch (IllegalStateException e) {
            // MediaCodec in released or transitioning state during teardown
        } catch (Exception e) {
            Log.e(TAG, "Error decoding audio sample", e);
        }
    }

    public synchronized void stop() {
        isRunning.set(false);
        if (audioDecoder != null) {
            try {
                audioDecoder.stop();
            } catch (Exception ignored) {
            }
            try {
                audioDecoder.release();
            } catch (Exception ignored) {
            }
            audioDecoder = null;
        }
        if (audioTrack != null) {
            try {
                audioTrack.stop();
            } catch (Exception ignored) {
            }
            try {
                audioTrack.release();
            } catch (Exception ignored) {
            }
            audioTrack = null;
        }
    }
}
