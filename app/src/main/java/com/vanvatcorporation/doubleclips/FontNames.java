package com.vanvatcorporation.doubleclips;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Reads the PostScript name (name table, id 6) of a .ttf / .otf / .ttc font file.
 * <p>
 * iOS can only draw a font by its PostScript name ({@code textStyle.fontName}); the project's Fonts folder is
 * registered when the project opens, but a style that carries no name falls back to the system font. So when this
 * build writes a style that uses an imported font, it writes the font's name too.
 * <p>
 * For a collection (.ttc) the first font is used. Returns null for anything it can't read, never throws.
 * Plain Java, no Android types.
 */
public final class FontNames {

    private static final int TAG_TTCF = 0x74746366, TAG_TRUE = 0x74727565, TAG_OTTO = 0x4F54544F, TAG_NAME = 0x6E616D65;
    private static final int NAME_ID_POSTSCRIPT = 6;

    private FontNames() { }

    public static String postScriptName(File file) {
        if (file == null || !file.isFile()) return null;
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            long length = f.length();
            if (length < 16) return null;
            f.seek(0);
            int tag = f.readInt();
            long sfnt = 0;
            if (tag == TAG_TTCF) {
                f.seek(12);
                sfnt = f.readInt() & 0xFFFFFFFFL; // offset of the first font
            } else if (tag != 0x00010000 && tag != TAG_TRUE && tag != TAG_OTTO) {
                return null;
            }

            f.seek(sfnt + 4);
            int numTables = f.readUnsignedShort();
            long nameOffset = -1;
            for (int i = 0; i < Math.min(numTables, 256); i++) {
                f.seek(sfnt + 12 + 16L * i);
                int t = f.readInt();
                f.skipBytes(4); // checksum
                long offset = f.readInt() & 0xFFFFFFFFL;
                if (t == TAG_NAME) {
                    nameOffset = offset;
                    break;
                }
            }
            if (nameOffset < 0 || nameOffset + 6 > length) return null;

            f.seek(nameOffset);
            f.readUnsignedShort(); // format
            int count = f.readUnsignedShort();
            int stringOffset = f.readUnsignedShort();

            String best = null;
            int bestRank = Integer.MAX_VALUE;
            for (int i = 0; i < Math.min(count, 4096); i++) {
                f.seek(nameOffset + 6 + 12L * i);
                int platform = f.readUnsignedShort();
                f.readUnsignedShort(); // encoding
                f.readUnsignedShort(); // language
                int nameId = f.readUnsignedShort();
                int len = f.readUnsignedShort();
                int off = f.readUnsignedShort();
                if (nameId != NAME_ID_POSTSCRIPT || len <= 0 || len > 512) continue;
                // Windows (3) and Unicode (0) names are UTF-16BE, Macintosh (1) ones single-byte.
                int rank = platform == 3 ? 0 : platform == 0 ? 1 : platform == 1 ? 2 : 3;
                if (rank >= bestRank) continue;
                long at = nameOffset + stringOffset + off;
                if (at + len > length) continue;
                byte[] bytes = new byte[len];
                f.seek(at);
                f.readFully(bytes);
                String s = platform == 1 ? new String(bytes, StandardCharsets.ISO_8859_1) : new String(bytes, StandardCharsets.UTF_16BE);
                s = s.trim();
                if (s.isEmpty()) continue;
                best = s;
                bestRank = rank;
            }
            return best;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
