package com.vanvatcorporation.doubleclips.internal;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoDecoderCore {
    private static final String TAG = "VideoDecoderCore";

    private MediaExtractor mExtractor;
    private MediaCodec mDecoder;
    private int mTrackIndex = -1;
    private boolean mIsEOS = false;

    public VideoDecoderCore(String sourcePath, Surface outputSurface) throws IOException {
        mExtractor = new MediaExtractor();
        mExtractor.setDataSource(sourcePath);

        for (int i = 0; i < mExtractor.getTrackCount(); i++) {
            MediaFormat format = mExtractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                mExtractor.selectTrack(i);
                mTrackIndex = i;
                mDecoder = MediaCodec.createDecoderByType(mime);
                mDecoder.configure(format, outputSurface, null, 0);
                mDecoder.start();
                break;
            }
        }
        
        if (mTrackIndex == -1) {
            throw new RuntimeException("No video track found in " + sourcePath);
        }
    }

    public boolean isEOS() {
        return mIsEOS;
    }

    public boolean drainExtractorToDecoder() {
        if (mIsEOS) return false;
        
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
