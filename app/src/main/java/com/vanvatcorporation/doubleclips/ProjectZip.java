package com.vanvatcorporation.doubleclips;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Project export / import as a ZIP: the streaming core, with no Android types (the Uri / dialog side lives in
 * {@code ProgressCompressionHelper}), so it can be unit-tested and shared with the desktop port.
 * <p>
 * The archive layout is unchanged and identical on iOS / desktop: the project FOLDER is the single top-level entry
 * ({@code project_3/project.properties}, {@code project_3/Clips/...}).
 * <p>
 * What it does differently from the earlier helper, and why:
 * <ul>
 *   <li><b>Plans first.</b> Export walks the folder once, so the total byte count is exact before any compressing
 *       starts, and {@code Clips/Temp} (the regenerable frame cache) is left out, like iOS does.</li>
 *   <li><b>Media is not deflated.</b> Video, audio and images are already compressed; running deflate over them
 *       burns CPU for no size gain. They are written with compression level 0 (stored blocks inside the normal
 *       deflate framing): that needs no CRC pre-pass, which a true STORED entry would, because the output can't
 *       seek. Every unzip tool, and the iOS reader, accepts it. Only text (the timeline, properties, ...) is deflated.</li>
 *   <li><b>Big buffers.</b> 256 KB reads and writes, not 1 KB, and the destination is buffered.</li>
 *   <li><b>Progress that means something.</b> A byte count alone is wrong whenever a project has many small files:
 *       the bar races through the big videos, then sits at 99% while thousands of small files each pay for opening,
 *       creating and closing. So progress is measured in <i>work</i>, not bytes: a file is its (weighted) size plus a
 *       per-file overhead, and {@link CostModel} <b>measures that overhead while it runs</b> (time per small file vs
 *       time per big byte), so the bar is right on a fast phone and a slow SD card alike. Deflating / inflating weigh
 *       more per byte than copying ({@link #DEFLATE_FACTOR}, {@link #INFLATE_FACTOR}). Export knows every item from its
 *       plan. Import reads the ZIP's central directory first ({@link #readDirectory}, a few KB, no copy of the
 *       archive), which gives the same up-front list and the exact uncompressed size for a free-space check; when the
 *       archive can't be read that way it falls back to compressed bytes consumed / archive size. Reports are
 *       throttled to every 50 ms.</li>
 *   <li><b>Cancel</b> between chunks, and <b>safe extraction</b>: entries with {@code ..} or absolute paths are
 *       refused (zip-slip), macOS junk ({@code __MACOSX}, {@code ._*}, {@code .DS_Store}) is skipped.</li>
 * </ul>
 */
public final class ProjectZip {

    /** Chunk size for reading and writing. */
    public static final int BUFFER = 256 * 1024;
    /** Minimum time between two progress reports (the first and the last are always reported). */
    public static final long REPORT_INTERVAL_NANOS = 50_000_000L;
    /**
     * Starting guesses for {@link CostModel}, in "bytes of plain copying" for the per-file overhead. They only matter
     * until the first few files have been timed; after that the model uses what it measured. Only the bar's
     * smoothness depends on them, never correctness.
     */
    public static final long FILE_COST = 128 * 1024;
    public static final double DEFLATE_FACTOR = 4.0;
    public static final double INFLATE_FACTOR = 1.5;
    /** The data phase fills the bar up to here; the remainder is "Finishing..." (closing / moving), then 100% on done. */
    public static final double DATA_SHARE = 0.97;
    /** The regenerable frame cache, relative to the project folder; not exported. */
    public static final String TEMP_DIRECTORY = "Clips/Temp";

    /** Already-compressed formats: written without deflating (same list as the iOS port). */
    public static final Set<String> STORED_EXTENSIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "mp4", "mov", "m4v", "mkv", "webm", "3gp", "m4a", "mp3", "aac", "ogg", "opus", "flac",
            "jpg", "jpeg", "png", "heic", "gif", "webp", "zip")));

    private ProjectZip() { }

    // ------------------------------------------------------------------ contracts

    /** Receives progress. {@code fraction} is 0..1, or a negative number when the total isn't known. */
    public interface Listener {
        void onProgress(double fraction, String currentName);
    }

    /** Thread-safe cancel flag, checked between chunks. */
    public static final class Cancellation {
        private volatile boolean cancelled;

        public void cancel() { cancelled = true; }

        public boolean isCancelled() { return cancelled; }
    }

    /** Thrown when the {@link Cancellation} fired. Callers treat it as "stop quietly and clean up", not as a failure. */
    public static final class CancelledException extends IOException {
        public CancelledException() { super("Cancelled"); }
    }

    /** The archive can't be used as a project export (empty, or an unsafe path inside). */
    public static final class InvalidArchiveException extends IOException {
        public InvalidArchiveException(String message) { super(message); }
    }

    // ------------------------------------------------------------------ export

    public static final class PlannedFile {
        public final File file;
        public final String entryPath;
        public final long size;
        public final long modified;
        /** Whether it is written without deflating (already-compressed media). */
        public final boolean stored;

        PlannedFile(File file, String entryPath, long size, long modified) {
            this.file = file;
            this.entryPath = entryPath;
            this.size = size;
            this.modified = modified;
            this.stored = shouldStore(file.getName());
        }

        /** Per byte: 1 for plain copying, {@link #DEFLATE_FACTOR} for text that is deflated. */
        public double weight() { return stored ? 1.0 : DEFLATE_FACTOR; }

        /** Size in "copy-equivalent" bytes. */
        double weighted() { return size * weight(); }
    }

    public static final class Plan {
        public final String rootName;
        public final List<PlannedFile> files;
        public final long totalBytes;

        Plan(String rootName, List<PlannedFile> files, long totalBytes) {
            this.rootName = rootName;
            this.files = files;
            this.totalBytes = totalBytes;
        }

        double[] weights() {
            double[] w = new double[files.size()];
            for (int i = 0; i < w.length; i++) w[i] = files.get(i).weighted();
            return w;
        }
    }

    public static final class WriteResult {
        /** Files written (the archive has exactly this many entries). */
        public int entries;
        /** Size of the finished archive, as counted on the way out. */
        public long archiveBytes;
    }

    /** Walks {@code projectDir} once: every file that goes in, and how many bytes that is. */
    public static Plan plan(File projectDir, boolean excludeTemp, Cancellation cancel) throws IOException {
        if (projectDir == null || !projectDir.isDirectory()) throw new IOException("The project folder no longer exists.");
        List<PlannedFile> files = new ArrayList<>();
        long[] total = {0};
        String root = projectDir.getName();
        collect(projectDir, root, "", excludeTemp, files, total, cancel);
        if (files.isEmpty()) throw new IOException("The project folder is empty.");
        return new Plan(root, files, total[0]);
    }

    private static void collect(File dir, String root, String relative, boolean excludeTemp,
                                List<PlannedFile> out, long[] total, Cancellation cancel) throws IOException {
        String[] names = dir.list();
        if (names == null) throw new IOException("Couldn't read the project folder: " + dir);
        Arrays.sort(names); // a stable entry order, so two exports of the same project are the same archive
        for (String name : names) {
            if (cancel != null && cancel.isCancelled()) throw new CancelledException();
            if (isJunkName(name)) continue;
            File f = new File(dir, name);
            String rel = relative.isEmpty() ? name : relative + "/" + name;
            if (f.isDirectory()) {
                if (excludeTemp && rel.equals(TEMP_DIRECTORY)) continue;
                collect(f, root, rel, excludeTemp, out, total, cancel);
            } else if (f.isFile()) {
                long size = f.length();
                out.add(new PlannedFile(f, root + "/" + rel, size, f.lastModified()));
                total[0] += size;
            }
        }
    }

    /** True for entries that never belong in a project archive (macOS Finder litter). */
    static boolean isJunkName(String name) {
        return name.equals(".DS_Store") || name.startsWith("._");
    }

    /** True when the file's format is already compressed, so deflating it would only cost time. */
    public static boolean shouldStore(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && STORED_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * Writes the plan as a ZIP into {@code rawOut}, which is closed before this returns (so a failure while the
     * archive is finalised, e.g. out of space, is reported here and not swallowed).
     */
    public static WriteResult write(Plan plan, OutputStream rawOut, Listener listener, Cancellation cancel) throws IOException {
        CountingOutputStream counted = new CountingOutputStream(rawOut);
        ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(counted, BUFFER), StandardCharsets.UTF_8);
        Throttle report = new Throttle(listener);
        report.force(0, "Preparing...");
        byte[] buf = new byte[BUFFER];
        CostModel model = new CostModel(plan.weights(), System::nanoTime);
        int entries = 0;
        int index = 0;
        boolean ok = false;
        try {
            for (PlannedFile pf : plan.files) {
                if (cancel != null && cancel.isCancelled()) throw new CancelledException();
                ZipEntry entry = new ZipEntry(pf.entryPath);
                if (pf.modified > 0) entry.setTime(pf.modified);
                zip.setLevel(pf.stored ? Deflater.NO_COMPRESSION : Deflater.DEFAULT_COMPRESSION);
                zip.putNextEntry(entry);
                String name = pf.file.getName();
                model.begin(index);
                double weight = pf.weight();
                double fileDone = 0;
                try (FileInputStream in = new FileInputStream(pf.file)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancel != null && cancel.isCancelled()) throw new CancelledException();
                        zip.write(buf, 0, n);
                        fileDone += n * weight;
                        model.within(fileDone);
                        report.maybe(DATA_SHARE * model.fraction(), name);
                    }
                }
                zip.closeEntry();
                model.end(index);
                index++;
                entries++;
            }
            report.force(DATA_SHARE, "Finishing...");
            zip.finish();
            zip.close(); // flushes, writes the end of the archive, closes rawOut; a failure here is a real failure
            ok = true;
        } finally {
            if (!ok) {
                try { zip.close(); } catch (IOException ignored) { /* the original failure is the one to report */ }
            }
        }
        report.force(1.0, "Done");
        WriteResult result = new WriteResult();
        result.entries = entries;
        result.archiveBytes = counted.count;
        return result;
    }

    // ------------------------------------------------------------------ import

    public static final class ExtractResult {
        public int files;
        /** Uncompressed bytes written to disk. */
        public long bytes;
    }

    /** One file of an archive, as its central directory describes it. */
    public static final class DirectoryEntry {
        public final String name;
        public final long size;
        public final long compressedSize;
        public final int method;
        /** Position among the files that will be written: the item {@link CostModel} tracks it as. */
        final int index;

        DirectoryEntry(String name, long size, long compressedSize, int method, int index) {
            this.name = name;
            this.size = size;
            this.compressedSize = compressedSize;
            this.method = method;
            this.index = index;
        }

        /** Per byte: inflating costs more than copying, but a deflate level-0 / stored entry is only a copy. */
        public double weight() {
            boolean compressed = method == 8 && size > 0 && compressedSize < size * 0.9;
            return compressed ? INFLATE_FACTOR : 1.0;
        }

        /** Size in "copy-equivalent" bytes. */
        public double weighted() { return size * weight(); }
    }

    /** What an archive contains, known before extracting a byte: the basis for exact progress and a free-space check. */
    public static final class Directory {
        public final Map<String, DirectoryEntry> byName;
        public final int fileCount;
        /** Total uncompressed size of the files that will be written (junk entries excluded). */
        public final long totalBytes;
        final double[] weights;

        Directory(Map<String, DirectoryEntry> byName, int fileCount, long totalBytes, double[] weights) {
            this.byName = byName;
            this.fileCount = fileCount;
            this.totalBytes = totalBytes;
            this.weights = weights;
        }
    }

    private static final int SIG_EOCD = 0x06054b50, SIG_CEN = 0x02014b50, SIG_Z64_LOCATOR = 0x07064b50, SIG_Z64_EOCD = 0x06064b50;
    private static final long MAX_DIRECTORY_BYTES = 256L << 20;

    /**
     * Reads an archive's central directory from a seekable channel: a few KB at the end of the file, no pass over the
     * data. Returns null when the archive can't be read that way (no end-of-directory record: damaged, or not a ZIP);
     * the caller then extracts without up-front totals. Throws {@link InvalidArchiveException} for an entry that
     * would escape the destination, so a hostile archive is refused before anything is written.
     * Handles Zip64 and archive comments.
     */
    public static Directory readDirectory(FileChannel ch) throws IOException {
        long fileSize = ch.size();
        if (fileSize < 22) return null;
        int tailLen = (int) Math.min(fileSize, 22 + 65535);
        ByteBuffer tail = ByteBuffer.allocate(tailLen).order(ByteOrder.LITTLE_ENDIAN);
        readFully(ch, tail, fileSize - tailLen);
        int eocd = -1;
        for (int i = tailLen - 22; i >= 0; i--) {
            if (tail.getInt(i) == SIG_EOCD) { eocd = i; break; }
        }
        if (eocd < 0) return null;

        long total = tail.getShort(eocd + 10) & 0xFFFFL;
        long cdSize = tail.getInt(eocd + 12) & 0xFFFFFFFFL;
        long cdOffset = tail.getInt(eocd + 16) & 0xFFFFFFFFL;
        if (total == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) { // Zip64
            long eocdAbs = fileSize - tailLen + eocd;
            if (eocdAbs < 20) return null;
            ByteBuffer loc = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
            readFully(ch, loc, eocdAbs - 20);
            if (loc.getInt(0) != SIG_Z64_LOCATOR) return null;
            long z64 = loc.getLong(8);
            ByteBuffer rec = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
            readFully(ch, rec, z64);
            if (rec.getInt(0) != SIG_Z64_EOCD) return null;
            total = rec.getLong(32);
            cdSize = rec.getLong(40);
            cdOffset = rec.getLong(48);
        }
        if (cdSize < 0 || cdSize > MAX_DIRECTORY_BYTES || cdOffset < 0 || cdOffset + cdSize > fileSize) return null;

        ByteBuffer cd = ByteBuffer.allocate((int) cdSize).order(ByteOrder.LITTLE_ENDIAN);
        readFully(ch, cd, cdOffset);

        Map<String, DirectoryEntry> byName = new HashMap<>();
        List<Double> weights = new ArrayList<>();
        int files = 0;
        long bytes = 0;
        int pos = 0;
        while (pos + 46 <= cd.limit() && cd.getInt(pos) == SIG_CEN) {
            int method = cd.getShort(pos + 10) & 0xFFFF;
            long compressed = cd.getInt(pos + 20) & 0xFFFFFFFFL;
            long size = cd.getInt(pos + 24) & 0xFFFFFFFFL;
            int nameLen = cd.getShort(pos + 28) & 0xFFFF;
            int extraLen = cd.getShort(pos + 30) & 0xFFFF;
            int commentLen = cd.getShort(pos + 32) & 0xFFFF;
            if (pos + 46 + nameLen + extraLen + commentLen > cd.limit()) return null;
            byte[] nameBytes = new byte[nameLen];
            for (int k = 0; k < nameLen; k++) nameBytes[k] = cd.get(pos + 46 + k);
            String name = new String(nameBytes, StandardCharsets.UTF_8);
            if (size == 0xFFFFFFFFL || compressed == 0xFFFFFFFFL) { // sizes live in the Zip64 extra field
                int e = pos + 46 + nameLen, end = e + extraLen;
                while (e + 4 <= end) {
                    int id = cd.getShort(e) & 0xFFFF, len = cd.getShort(e + 2) & 0xFFFF;
                    if (id == 0x0001) {
                        int f = e + 4;
                        if (size == 0xFFFFFFFFL && f + 8 <= end) { size = cd.getLong(f); f += 8; }
                        if (compressed == 0xFFFFFFFFL && f + 8 <= end) compressed = cd.getLong(f);
                        break;
                    }
                    e += 4 + len;
                }
            }
            if (!name.endsWith("/")) {
                String rel = safeRelativePath(name); // throws for a hostile path; null for junk
                if (rel != null) {
                    DirectoryEntry entry = new DirectoryEntry(name, size, compressed, method, files);
                    byName.put(name, entry);
                    weights.add(entry.weighted());
                    files++;
                    bytes += size;
                }
            } else {
                safeRelativePath(name); // same refusal for directories
            }
            pos += 46 + nameLen + extraLen + commentLen;
        }
        double[] w = new double[weights.size()];
        for (int i = 0; i < w.length; i++) w[i] = weights.get(i);
        return new Directory(byName, files, bytes, w);
    }

    private static void readFully(FileChannel ch, ByteBuffer buf, long position) throws IOException {
        buf.clear();
        long p = position;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, p);
            if (n < 0) throw new IOException("Unexpected end of the archive");
            p += n;
        }
        buf.flip();
    }

    /**
     * Extracts a project ZIP into {@code destDir} (created if missing) in one streaming pass.
     *
     * @param directory   the archive's central directory ({@link #readDirectory}) for exact, cost-weighted progress;
     *                    null when it couldn't be read: progress then follows compressed bytes consumed.
     * @param archiveBytes size of the archive on disk, used only without a directory; negative when unknown (progress
     *                    is then reported as -1 = indeterminate).
     */
    public static ExtractResult extract(InputStream rawIn, Directory directory, long archiveBytes, File destDir,
                                        Listener listener, Cancellation cancel) throws IOException {
        if (!destDir.isDirectory() && !destDir.mkdirs()) throw new IOException("Couldn't create " + destDir);
        String destCanonical = destDir.getCanonicalPath();

        CountingInputStream counted = new CountingInputStream(rawIn);
        ZipInputStream zin = new ZipInputStream(new BufferedInputStream(counted, BUFFER), StandardCharsets.UTF_8);
        Throttle report = new Throttle(listener);
        boolean known = directory != null && directory.fileCount > 0;
        CostModel model = known ? new CostModel(directory.weights, System::nanoTime) : null;
        report.force(known || archiveBytes > 0 ? 0 : -1, "Preparing...");
        byte[] buf = new byte[BUFFER];
        ExtractResult result = new ExtractResult();
        try {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (cancel != null && cancel.isCancelled()) throw new CancelledException();
                String rel = safeRelativePath(entry.getName());
                if (rel == null) { zin.closeEntry(); continue; } // junk: skipped
                File target = new File(destDir, rel);
                // Belt and braces on top of safeRelativePath: whatever it resolves to must stay inside destDir.
                if (!target.getCanonicalPath().startsWith(destCanonical + File.separator)) {
                    throw new InvalidArchiveException("The ZIP contains a file outside the project folder: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Couldn't create " + target);
                    zin.closeEntry();
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Couldn't create " + parent);
                String name = target.getName();
                DirectoryEntry info = known ? directory.byName.get(entry.getName()) : null;
                double weight = info != null ? info.weight() : 1.0;
                if (info != null) model.begin(info.index);
                double fileDone = 0;
                try (FileOutputStream out = new FileOutputStream(target)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) {
                        if (cancel != null && cancel.isCancelled()) throw new CancelledException();
                        out.write(buf, 0, n);
                        result.bytes += n;
                        fileDone += n * weight;
                        double f;
                        if (known) {
                            if (info != null) model.within(fileDone);
                            f = DATA_SHARE * model.fraction();
                        } else {
                            f = archiveBytes > 0 ? DATA_SHARE * fraction(counted.count, archiveBytes) : -1;
                        }
                        report.maybe(f, name);
                    }
                }
                long time = entry.getTime();
                if (time > 0) //noinspection ResultOfMethodCallIgnored
                    target.setLastModified(time);
                result.files++;
                zin.closeEntry();
                if (info != null) model.end(info.index);
            }
        } finally {
            try { zin.close(); } catch (IOException ignored) { /* nothing left to report */ }
        }
        report.force(known || archiveBytes > 0 ? DATA_SHARE : -1, "Finishing...");
        return result;
    }

    /**
     * The entry name as a safe relative path ("a/b/c.txt"), null for entries to skip, or an exception for ones that
     * would escape the destination.
     */
    static String safeRelativePath(String entryName) throws InvalidArchiveException {
        String name = entryName.replace('\\', '/');
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            throw new InvalidArchiveException("The ZIP contains an absolute path: " + entryName);
        }
        List<String> parts = new ArrayList<>();
        for (String p : name.split("/")) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) throw new InvalidArchiveException("The ZIP contains a path that leaves the project folder: " + entryName);
            parts.add(p);
        }
        if (parts.isEmpty()) return null;
        if (parts.get(0).equals("__MACOSX")) return null;
        String last = parts.get(parts.size() - 1);
        if (isJunkName(last)) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ project layout (used by the importer)

    /**
     * The project folders inside an extracted export: the ZIP's top-level folders that hold {@code marker} (the
     * project's properties file), or {@code extracted} itself when the marker sits at its root (a ZIP made from the
     * inside of a project folder). Empty when it holds no project.
     */
    public static List<File> findProjectRoots(File extracted, String marker) {
        List<File> roots = new ArrayList<>();
        if (new File(extracted, marker).isFile()) {
            roots.add(extracted);
            return roots;
        }
        File[] children = extracted.listFiles(File::isDirectory);
        if (children != null) {
            Arrays.sort(children);
            for (File c : children) if (new File(c, marker).isFile()) roots.add(c);
        }
        return roots;
    }

    /** {@code parent/base}, or {@code parent/base (1)}, {@code (2)}, ... : the first name not taken. Never an existing path. */
    public static File uniqueDestination(File parent, String base) {
        File dest = new File(parent, base);
        for (int n = 1; dest.exists(); n++) dest = new File(parent, base + " (" + n + ")");
        return dest;
    }

    /** The " (n)" number of a destination made by {@link #uniqueDestination}, or 0 when it kept its own name. */
    public static int suffixOf(File dest, String base) {
        String name = dest.getName();
        if (name.equals(base)) return 0;
        String tail = name.substring(base.length());
        try { return Integer.parseInt(tail.substring(2, tail.length() - 1)); } catch (RuntimeException e) { return 0; }
    }

    // ------------------------------------------------------------------ progress model

    /**
     * Turns "item j of N is done / this far along" into a fraction of the whole job, measuring what an item costs
     * as it goes instead of assuming it.
     * <p>
     * Each item has a weight (its bytes, times {@link #DEFLATE_FACTOR} / {@link #INFLATE_FACTOR} when compressing
     * costs more than copying) and pays a fixed per-item overhead on top. The overhead, in units of "bytes of copying",
     * is {@code R = b / a}, where {@code b} is the measured time of a small item (nearly all overhead) and {@code a}
     * the measured time per weighted byte on big items. Until both have been seen it uses a starting guess
     * (a phone's typical copy speed and file-create cost). The fraction is {@code (work done) / (work total)} with
     * work = weighted bytes + R per item, and never goes backwards when R is re-estimated.
     * <p>
     * Pure logic with an injectable clock, so tests can simulate any device.
     */
    static final class CostModel {
        interface Clock { long nanos(); }

        /** Starting guesses: ~150 MB/s plain copy, and a per-file cost of {@link #FILE_COST} copy-bytes. */
        static final double PRIOR_SECONDS_PER_BYTE = 1.0 / 150e6;
        static final double PRIOR_SECONDS_PER_ITEM = FILE_COST * PRIOR_SECONDS_PER_BYTE;
        /** A "big" item's time is mostly bytes; every other item's time is mostly overhead. */
        static final double BIG_BYTES = 1024 * 1024;
        static final int MIN_SMALL_SAMPLES = 8;
        static final double MIN_BIG_TOTAL = 4 * 1024 * 1024;

        private final double[] weights;
        private final double totalWeight;
        private final Clock clock;
        private double doneWeight;      // weighted bytes of finished items
        private int doneItems;
        private int current = -1;
        private double currentDone;     // weighted bytes done of the current item
        private long currentStart;
        private double lastFraction;
        // measurements: items under BIG_BYTES ("small": mostly per-item overhead) and the rest ("big": mostly bytes)
        private double smallTime, smallWeight; private int smallCount;
        private double bigTime, bigWeight; private int bigCount;

        CostModel(double[] weights, Clock clock) {
            this.weights = weights;
            double t = 0;
            for (double w : weights) t += w;
            this.totalWeight = t;
            this.clock = clock;
        }

        void begin(int item) {
            current = item;
            currentDone = 0;
            currentStart = clock.nanos();
        }

        void within(double weightedBytesDone) { currentDone = weightedBytesDone; }

        void end(int item) {
            if (item != current) return;
            double seconds = (clock.nanos() - currentStart) / 1e9;
            double w = weights[item];
            if (w < BIG_BYTES) {
                smallTime += seconds; smallWeight += w; smallCount++;
            } else {
                bigTime += seconds; bigWeight += w; bigCount++;
            }
            doneWeight += w;
            doneItems++;
            current = -1;
            currentDone = 0;
        }

        /**
         * The per-item overhead in weighted bytes, from what was measured so far. The two unknowns are the time per
         * weighted byte {@code a} (seen on big items) and the time per item {@code b} (seen on the others, once the
         * bytes' share of their time is taken off). With only one of them measured the other is not guessed in
         * absolute time (a fast device would make that guess wildly wrong): the ratio guess {@link #FILE_COST} is
         * used, or, when only {@code b} is known, the typical copy speed, which errs towards a small overhead.
         */
        double overhead() {
            boolean haveBig = bigWeight >= MIN_BIG_TOTAL, haveSmall = smallCount >= MIN_SMALL_SAMPLES;
            double r;
            if (haveBig && haveSmall) {
                double a = bigTime / bigWeight, b = 0;
                for (int pass = 0; pass < 2; pass++) { // each one's time has a bit of the other in it
                    b = Math.max(0, (smallTime - a * smallWeight) / smallCount);
                    a = Math.max(1e-12, (bigTime - b * bigCount) / bigWeight);
                }
                r = b / a;
            } else if (haveSmall) {
                double b = Math.max(5e-6, (smallTime - PRIOR_SECONDS_PER_BYTE * smallWeight) / smallCount);
                r = b / PRIOR_SECONDS_PER_BYTE;
            } else {
                r = FILE_COST;
            }
            return Math.max(1024.0, Math.min(8.0 * 1024 * 1024, r));
        }

        double fraction() {
            if (weights.length == 0) return 1.0;
            double r = overhead();
            double total = totalWeight + r * weights.length;
            double done = doneWeight + r * doneItems;
            if (current >= 0) done += Math.min(currentDone, weights[current]) + r;
            double f = total <= 0 ? 1.0 : Math.min(1.0, done / total);
            if (f < lastFraction) f = lastFraction; // never backwards, even when the estimate of R moves
            lastFraction = f;
            return f;
        }
    }

    // ------------------------------------------------------------------ small helpers

    /** Total size of every file under {@code dir}, in bytes (a project can pass 2 GB, so not an int). */
    public static long folderSize(File dir) {
        if (dir == null) return 0;
        if (dir.isFile()) return dir.length();
        long size = 0;
        File[] children = dir.listFiles();
        if (children != null) for (File c : children) size += folderSize(c);
        return size;
    }

    /** Deletes a file or folder tree; best effort. */
    public static void deleteRecursively(File f) {
        if (f == null) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static double fraction(double done, double total) {
        if (total <= 0) return 1.0;
        return Math.min(1.0, done / total);
    }

    /** Calls the listener at most every {@link #REPORT_INTERVAL_NANOS}; {@link #force} always reports. */
    private static final class Throttle {
        private final Listener listener;
        private long last = System.nanoTime() - REPORT_INTERVAL_NANOS * 2;

        Throttle(Listener listener) { this.listener = listener; }

        void maybe(double fraction, String name) {
            if (listener == null) return;
            long now = System.nanoTime();
            if (now - last < REPORT_INTERVAL_NANOS) return;
            last = now;
            listener.onProgress(fraction, name);
        }

        void force(double fraction, String name) {
            if (listener == null) return;
            last = System.nanoTime();
            listener.onProgress(fraction, name);
        }
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        long count;

        CountingOutputStream(OutputStream out) { super(out); }

        @Override public void write(int b) throws IOException { out.write(b); count++; }

        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
    }

    private static final class CountingInputStream extends FilterInputStream {
        long count;

        CountingInputStream(InputStream in) { super(in); }

        @Override public int read() throws IOException {
            int b = in.read();
            if (b >= 0) count++;
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
    }
}
