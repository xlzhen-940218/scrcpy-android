package org.las2mile.scrcpy.decoder;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public class VideoDecoder {
    private static final String TAG = "VideoDecoder";

    public static final int CODEC_H264 = 0x68323634;
    public static final int CODEC_H265 = 0x68323635;
    public static final int CODEC_AV1 = 0x00617631;

    private MediaCodec mCodec;
    private Worker mWorker;
    private final AtomicBoolean mIsConfigured = new AtomicBoolean(false);
    private String mimeType = "video/avc";

    public void setCodec(int codecId) {
        if (codecId == CODEC_H265) {
            mimeType = "video/hevc";
        } else if (codecId == CODEC_AV1) {
            mimeType = "video/av01";
        } else {
            mimeType = "video/avc";
        }
    }

    public void configure(Surface surface, int width, int height) {
        if (mWorker != null) {
            mWorker.configure(surface, width, height, mimeType);
        }
    }

    public void setSurface(Surface surface, int width, int height) {
        if (mWorker != null) {
            mWorker.setSurface(surface, width, height, mimeType);
        }
    }

    public void decodeSample(byte[] data, int offset, int size, long presentationTimeUs, int flags) {
        if (mWorker != null) {
            mWorker.decodeSample(data, offset, size, presentationTimeUs, flags);
        }
    }

    public void start() {
        if (mWorker == null) {
            mWorker = new Worker();
            mWorker.setRunning(true);
            mWorker.start();
        }
    }

    public void stop() {
        if (mWorker != null) {
            mWorker.setRunning(false);
            mWorker = null;
            mIsConfigured.set(false);
            if (mCodec != null) {
                try {
                    mCodec.stop();
                    mCodec.release();
                } catch (Exception ignored) {
                }
                mCodec = null;
            }
        }
    }

    private class Worker extends Thread {
        private final AtomicBoolean mIsRunning = new AtomicBoolean(false);
        private byte[] cachedCsd;

        private void setRunning(boolean isRunning) {
            mIsRunning.set(isRunning);
        }

        private synchronized void setSurface(Surface surface, int width, int height, String mime) {
            if (surface == null || !surface.isValid()) {
                Log.w(TAG, "setSurface: surface is null or invalid");
                return;
            }

            if (mCodec != null && mIsConfigured.get()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        mCodec.setOutputSurface(surface);
                        Log.d(TAG, "mCodec.setOutputSurface succeeded dynamically!");
                        return;
                    } catch (Exception e) {
                        Log.w(TAG, "setOutputSurface failed, will re-configure", e);
                    }
                }
            }

            configure(surface, width, height, mime);
        }

        private synchronized void configure(Surface surface, int width, int height, String mime) {
            if (surface == null || !surface.isValid()) {
                Log.w(TAG, "Cannot configure VideoDecoder: surface is null or invalid");
                return;
            }

            if (mIsConfigured.get() && mCodec != null) {
                mIsConfigured.set(false);
                try {
                    mCodec.stop();
                    mCodec.release();
                } catch (Exception ignored) {
                }
                mCodec = null;
            }

            try {
                MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                }
                if (cachedCsd != null) {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(cachedCsd));
                }
                mCodec = MediaCodec.createDecoderByType(mime);
                mCodec.configure(format, surface, null, 0);
                mCodec.start();
                mIsConfigured.set(true);
                Log.d(TAG, "VideoDecoder configured for " + mime + " (" + width + "x" + height + ")");
            } catch (Exception e) {
                Log.e(TAG, "Failed to create/configure video decoder for " + mime, e);
                mCodec = null;
                mIsConfigured.set(false);
            }
        }

        public synchronized void decodeSample(byte[] data, int offset, int size, long presentationTimeUs, int flags) {
            if ((flags & 2) != 0) { // BUFFER_FLAG_CODEC_CONFIG
                cachedCsd = Arrays.copyOfRange(data, offset, offset + size);
            }

            if (!mIsConfigured.get() || !mIsRunning.get() || mCodec == null) {
                return;
            }

            try {
                int index = mCodec.dequeueInputBuffer(10000);
                if (index < 0) {
                    index = mCodec.dequeueInputBuffer(20000);
                }
                if (index >= 0) {
                    ByteBuffer buffer;
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                        buffer = mCodec.getInputBuffers()[index];
                    } else {
                        buffer = mCodec.getInputBuffer(index);
                    }
                    if (buffer != null) {
                        buffer.clear();
                        buffer.put(data, offset, size);
                        mCodec.queueInputBuffer(index, 0, size, presentationTimeUs, flags);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error queuing input buffer", e);
            }
        }

        @Override
        public void run() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (mIsRunning.get()) {
                if (mIsConfigured.get() && mCodec != null) {
                    try {
                        int index = mCodec.dequeueOutputBuffer(info, 10000);
                        if (index >= 0) {
                            mCodec.releaseOutputBuffer(index, true);
                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                break;
                            }
                        }
                    } catch (IllegalStateException e) {
                        // Codec state transition
                    } catch (Exception e) {
                        Log.e(TAG, "Error dequeuing output buffer", e);
                    }
                } else {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
    }
}