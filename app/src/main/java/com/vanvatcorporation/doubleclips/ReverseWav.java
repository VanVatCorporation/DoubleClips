package com.vanvatcorporation.doubleclips;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * The reversed twin of a clip's preview audio. The editor plays a clip's sound from {@code PreviewClips/<name>.wav} (16-bit PCM, made
 * once by FFmpeg); a reversed clip plays {@code <name>.reversed.wav} instead: the same file with its sample frames in the opposite order.
 * Made on demand and kept; it is a few MB for a minute of the preview's mono 22 kHz sound, so it takes a moment. Plain java.io.
 */
public final class ReverseWav {

    private ReverseWav() {}

    /** {@code PreviewClips/clip.wav} -> {@code PreviewClips/clip.reversed.wav}. */
    public static File reversedFileOf(File wav) {
        String name = wav.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return new File(wav.getParentFile(), base + ".reversed.wav");
    }

    /** The reversed twin of {@code wav}, made now if it is missing or older than the source; null when it can't be made (no file, not 16-bit PCM). */
    public static File ensure(File wav) {
        if (wav == null || !wav.isFile()) return null;
        File out = reversedFileOf(wav);
        try {
            if (out.isFile() && out.length() > 0 && out.lastModified() >= wav.lastModified()) return out;
            return build(wav, out) ? out : null;
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return null;
        }
    }

    /** Length in seconds of the PCM in a WAV, or -1 when it can't be read. */
    public static double lengthSeconds(File wav) {
        try (RandomAccessFile raf = new RandomAccessFile(wav, "r")) {
            Layout l = Layout.read(raf);
            return l == null ? -1 : l.dataBytes / (double) (l.channels * 2) / l.sampleRate;
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private static final class Layout {
        int channels, sampleRate;
        long dataStart, dataBytes, sizeFieldAt;

        static Layout read(RandomAccessFile raf) throws IOException {
            long fileLen = raf.length();
            byte[] head = new byte[12];
            if (fileLen < 12) return null;
            raf.seek(0);
            raf.readFully(head);
            if (head[0] != 'R' || head[1] != 'I' || head[2] != 'F' || head[3] != 'F' || head[8] != 'W' || head[9] != 'A') return null;
            Layout l = new Layout();
            boolean pcm16 = false;
            long pos = 12;
            byte[] chunk = new byte[8];
            while (pos + 8 <= fileLen) {
                raf.seek(pos);
                raf.readFully(chunk);
                long size = le32(chunk, 4);
                String id = new String(chunk, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
                if (id.equals("fmt ")) {
                    byte[] fmt = new byte[16];
                    raf.readFully(fmt);
                    int format = le16(fmt, 0);
                    l.channels = le16(fmt, 2);
                    l.sampleRate = (int) le32(fmt, 4);
                    int bits = le16(fmt, 14);
                    pcm16 = (format == 1 || format == 0xFFFE) && bits == 16;
                } else if (id.equals("data")) {
                    l.dataStart = pos + 8;
                    l.sizeFieldAt = pos + 4;
                    long available = fileLen - l.dataStart;
                    l.dataBytes = (size == 0 || size == 0xFFFFFFFFL || size > available) ? available : size; // a streamed file says 0 / -1
                    break;
                }
                pos += 8 + size + (size & 1);
            }
            if (!pcm16 || l.channels <= 0 || l.sampleRate <= 0 || l.dataStart <= 0) return null;
            l.dataBytes -= l.dataBytes % (l.channels * 2L);
            return l.dataBytes > 0 ? l : null;
        }
    }

    private static boolean build(File wav, File out) throws IOException {
        File tmp = new File(out.getPath() + ".tmp");
        try (RandomAccessFile in = new RandomAccessFile(wav, "r"); RandomAccessFile dst = new RandomAccessFile(tmp, "rw")) {
            Layout l = Layout.read(in);
            if (l == null) return false;
            dst.setLength(0);

            // everything before the samples (RIFF + fmt + any LIST), with the two size fields made true for the data we write
            byte[] head = new byte[(int) l.dataStart];
            in.seek(0);
            in.readFully(head);
            put32(head, 4, l.dataStart - 8 + l.dataBytes);
            put32(head, (int) l.sizeFieldAt, l.dataBytes);
            dst.write(head);

            int frameBytes = l.channels * 2;
            final int blockFrames = 16384;
            byte[] block = new byte[blockFrames * frameBytes];
            byte[] flipped = new byte[blockFrames * frameBytes];
            long remaining = l.dataBytes / frameBytes;
            while (remaining > 0) {
                int take = (int) Math.min(blockFrames, remaining);
                in.seek(l.dataStart + (remaining - take) * frameBytes);
                in.readFully(block, 0, take * frameBytes);
                for (int f = 0; f < take; f++) System.arraycopy(block, (take - 1 - f) * frameBytes, flipped, f * frameBytes, frameBytes);
                dst.write(flipped, 0, take * frameBytes);
                remaining -= take;
            }
        }
        if (out.exists() && !out.delete()) return false;
        if (!tmp.renameTo(out)) return false;
        //noinspection ResultOfMethodCallIgnored
        out.setLastModified(Math.max(System.currentTimeMillis(), wav.lastModified()));
        return true;
    }

    private static int le16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }

    private static long le32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    private static void put32(byte[] b, int o, long v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }
}
