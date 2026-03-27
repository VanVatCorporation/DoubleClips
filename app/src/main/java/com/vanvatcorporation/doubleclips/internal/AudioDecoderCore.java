package com.vanvatcorporation.doubleclips.internal;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.io.IOException;
import java.nio.ByteBuffer;

public class AudioDecoderCore {
    private static final String TAG = "AudioDecoderCore";

    private MediaExtractor mExtractor;
    private MediaCodec mDecoder;
    private int mTrackIndex = -1;
    private boolean mIsEOS = false;
    
    // Config retrieved from format
    private int mSampleRate;
    private int mChannelCount;

    public AudioDecoderCore(String sourcePath) throws IOException {
        mExtractor = new MediaExtractor();
        mExtractor.setDataSource(sourcePath);

        for (int i = 0; i < mExtractor.getTrackCount(); i++) {
            MediaFormat format = mExtractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                mExtractor.selectTrack(i);
                mTrackIndex = i;
                mSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                mChannelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                
                mDecoder = MediaCodec.createDecoderByType(mime);
                mDecoder.configure(format, null, null, 0);
                mDecoder.start();
                break;
            }
        }
    }

    public boolean hasAudioTrack() {
        return mTrackIndex != -1;
    }

    public int getSampleRate() {
        return mSampleRate;
    }

    public int getChannelCount() {
        return mChannelCount;
    }

    public boolean isEOS() {
        return mIsEOS;
    }

    public boolean drainExtractorToDecoder() {
        if (mIsEOS || mDecoder == null) return false;
        
        int inIndex = mDecoder.dequeueInputBuffer(10000);
        if (inIndex >= 0) {
            ByteBuffer buffer = mDecoder.getInputBuffer(inIndex);
            int sampleSize = mExtractor.readSampleData(buffer, 0);
            if (sampleSize < 0) {
                mDecoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                mIsEOS = true;
                return false;
            } else {
                long presentationTimeUs = mExtractor.getSampleTime();
                mDecoder.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0);
                mExtractor.advance();
                return true;
            }
        }
        return false;
    }

    public MediaCodec getDecoder() {
        return mDecoder;
    }

    public void release() {
        if (mDecoder != null) {
            mDecoder.stop();
            mDecoder.release();
            mDecoder = null;
        }
        if (mExtractor != null) {
            mExtractor.release();
            mExtractor = null;
        }
    }
}
