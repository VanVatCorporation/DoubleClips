package com.vanvatcorporation.doubleclips.internal;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoEncoderCore {
    private static final String TAG = "VideoEncoderCore";

    private MediaCodec mEncoder;
    private Surface mInputSurface;

    private MediaCodec.BufferInfo mBufferInfo;

    public VideoEncoderCore(int width, int height, int bitRate, int frameRate) throws IOException {
        mBufferInfo = new MediaCodec.BufferInfo();

        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);

        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mInputSurface = mEncoder.createInputSurface();
        mEncoder.start();
    }

    public Surface getInputSurface() {
        return mInputSurface;
    }

    public void release() {
        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
    }

    public MediaCodec getEncoder() {
        return mEncoder;
    }

    public MediaCodec.BufferInfo getBufferInfo() {
        return mBufferInfo;
    }
}
