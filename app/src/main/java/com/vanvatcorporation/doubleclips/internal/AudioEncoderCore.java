package com.vanvatcorporation.doubleclips.internal;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import java.io.IOException;

public class AudioEncoderCore {
    private static final String TAG = "AudioEncoderCore";

    private MediaCodec mEncoder;
    private MediaCodec.BufferInfo mBufferInfo;
    
    private int mSampleRate;
    private int mChannelCount;

    public AudioEncoderCore(int sampleRate, int channelCount, int bitRate) throws IOException {
        mSampleRate = sampleRate;
        mChannelCount = channelCount;
        mBufferInfo = new MediaCodec.BufferInfo();

        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        mEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoder.start();
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
