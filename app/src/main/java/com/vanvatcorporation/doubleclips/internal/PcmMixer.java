package com.vanvatcorporation.doubleclips.internal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

public class PcmMixer {
    
    // Mixes two or more 16-bit PCM short buffers
    public static void mixBuffers(ShortBuffer outBuffer, ShortBuffer[] inputBuffers, float[] volumes) {
        int length = outBuffer.capacity();
        outBuffer.clear();
        
        for (int i = 0; i < length; i++) {
            float mixed = 0f;
            for (int k = 0; k < inputBuffers.length; k++) {
                if (inputBuffers[k] != null && i < inputBuffers[k].capacity()) {
                    short sample = inputBuffers[k].get(i);
                    mixed += (sample * volumes[k]);
                }
            }
            
            // Hard clipping protection
            if (mixed > Short.MAX_VALUE) mixed = Short.MAX_VALUE;
            if (mixed < Short.MIN_VALUE) mixed = Short.MIN_VALUE;
            
            outBuffer.put(i, (short) mixed);
        }
    }
    
    public static ShortBuffer bytesToShorts(ByteBuffer byteBuffer) {
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        return byteBuffer.asShortBuffer();
    }
}
