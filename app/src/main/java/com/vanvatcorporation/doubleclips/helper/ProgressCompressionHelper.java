package com.vanvatcorporation.doubleclips.helper;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.format.Formatter;

import com.vanvatcorporation.doubleclips.ProjectZip;
import com.vanvatcorporation.doubleclips.activities.main.MainAreaScreen;
import com.vanvatcorporation.doubleclips.constants.Constants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Project ZIP export / import for Android: the Uri, storage and project-list side. The streaming itself (what goes in,
 * how it is compressed, progress, safe extraction) is {@link ProjectZip}, which has no Android types.
 * <p>
 * Both calls block, so run them on a background thread; progress arrives on that thread, already throttled.
 * <ul>
 *   <li><b>Export</b> plans the file list, streams the ZIP to the destination and checks that the destination holds
 *       at least what was written. A failed or cancelled export removes the partial file.</li>
 *   <li><b>Import</b> extracts into a staging folder next to the projects folder and only then moves each project into
 *       place, so a cancelled, failed or corrupt import never leaves half a project behind. An existing project is
 *       never overwritten: the import becomes "Name (1)". The stored project path is re-anchored to where the project
 *       really lives now.</li>
 * </ul>
 */
public final class ProgressCompressionHelper {

    private static final String STAGING_PREFIX = "import-staging-";
    /** Free space to keep after an import, on top of the project itself. */
    private static final long SAFETY_MARGIN_BYTES = 32L * 1024 * 1024;
    private static final AtomicInteger ACTIVE_IMPORTS = new AtomicInteger();

    private ProgressCompressionHelper() { }

    /** The device doesn't have room for the project. The message is written for the user. */
    public static final class InsufficientStorageException extends IOException {
        public InsufficientStorageException(String message) { super(message); }
    }

    // ------------------------------------------------------------------ export

    /**
     * Writes {@code projectDir} as a ZIP to {@code destination} (a document the user just created).
     *
     * @throws ProjectZip.CancelledException when {@code cancel} fired
     */
    public static ProjectZip.WriteResult exportProject(Context context, File projectDir, Uri destination,
                                                       ProjectZip.Listener listener, ProjectZip.Cancellation cancel) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        boolean ok = false;
        try {
            if (listener != null) listener.onProgress(0, "Preparing...");
            ProjectZip.Plan plan = ProjectZip.plan(projectDir, true, cancel);
            OutputStream out = resolver.openOutputStream(destination);
            if (out == null) throw new IOException("Couldn't open the destination file for writing.");
            ProjectZip.WriteResult result = ProjectZip.write(plan, out, listener, cancel); // closes `out`

            // The destination can't be re-read cheaply, but it can say how big it is: a file smaller than what was
            // written means the provider dropped data (out of space on the target, a sync that gave up).
            long stored = queryLength(resolver, destination);
            if (stored > 0 && stored < result.archiveBytes) {
                throw new IOException("The saved file is smaller than expected (" + stored + " of " + result.archiveBytes
                        + " bytes). The destination may be out of space.");
            }
            ok = true;
            return result;
        } finally {
            if (!ok) deleteQuietly(resolver, destination);
        }
    }

    // ------------------------------------------------------------------ import

    /**
     * Imports the project(s) in a ZIP into {@code projectsDir}.
     *
     * @return the project folders that now exist (usually one)
     * @throws ProjectZip.CancelledException       when {@code cancel} fired
     * @throws ProjectZip.InvalidArchiveException  for an unsafe path or a ZIP that holds no project
     * @throws InsufficientStorageException        when the device is too full
     */
    public static List<File> importProject(Context context, Uri zipUri, File projectsDir,
                                           ProjectZip.Listener listener, ProjectZip.Cancellation cancel) throws IOException {
        if (!projectsDir.isDirectory() && !projectsDir.mkdirs()) throw new IOException("Couldn't create the projects folder.");
        // Staging beside the projects folder: same volume, so moving a project in is a rename, not a copy.
        File filesRoot = projectsDir.getParentFile();
        if (filesRoot == null) throw new IOException("Unexpected projects folder location.");
        ACTIVE_IMPORTS.incrementAndGet();
        File staging = new File(filesRoot, STAGING_PREFIX + UUID.randomUUID());
        try {
            cleanStaleStaging(filesRoot);
            try (Source source = Source.open(context.getContentResolver(), zipUri)) {
                long needed = source.directory != null ? source.directory.totalBytes : Math.max(0, source.length);
                long free = new StatFs(filesRoot.getPath()).getAvailableBytes();
                if (free < needed + SAFETY_MARGIN_BYTES) {
                    throw new InsufficientStorageException("Not enough free storage to import this project. It needs about "
                            + Formatter.formatFileSize(context, needed + SAFETY_MARGIN_BYTES) + " and only "
                            + Formatter.formatFileSize(context, free) + " is free.");
                }
                ProjectZip.extract(source.stream, source.directory, source.length, staging, listener, cancel);
            }
            if (cancel != null && cancel.isCancelled()) throw new ProjectZip.CancelledException();
            if (listener != null) listener.onProgress(0.99, "Finishing...");

            List<File> roots = ProjectZip.findProjectRoots(staging, Constants.DEFAULT_PROJECT_PROPERTIES_FILENAME);
            if (roots.isEmpty()) {
                throw new ProjectZip.InvalidArchiveException("This ZIP doesn't contain a DoubleClips project (no "
                        + Constants.DEFAULT_PROJECT_PROPERTIES_FILENAME + " inside).");
            }
            List<File> imported = new ArrayList<>();
            for (File root : roots) imported.add(moveIntoPlace(context, root, staging, projectsDir));
            if (listener != null) listener.onProgress(1.0, "Done");
            return imported;
        } finally {
            ProjectZip.deleteRecursively(staging);
            ACTIVE_IMPORTS.decrementAndGet();
        }
    }

    /** Moves one extracted project into the projects folder under a name that isn't taken, and fixes its properties. */
    private static File moveIntoPlace(Context context, File root, File staging, File projectsDir) throws IOException {
        String baseName = root.equals(staging) ? "Imported project" : root.getName();
        File dest = ProjectZip.uniqueDestination(projectsDir, baseName);
        int suffix = ProjectZip.suffixOf(dest, baseName);
        if (!root.renameTo(dest)) throw new IOException("Couldn't move the imported project into place.");

        // The properties hold the exporting device's absolute path and an old timestamp: point them at what exists now.
        try {
            MainAreaScreen.ProjectData data = MainAreaScreen.ProjectData.loadProperties(context, dest.getPath());
            if (data != null) {
                if (suffix > 0 && data.getProjectTitle() != null) {
                    data.setProjectTitle(context, data.getProjectTitle() + " (" + suffix + ")", false);
                }
                data.setProjectPath(dest.getPath());
                data.setProjectTimestamp(System.currentTimeMillis()); // a fresh import shows up as the newest project
                data.setProjectSize(ProjectZip.folderSize(dest));
                data.savePropertiesAtProject(context);
            }
        } catch (RuntimeException e) {
            // A properties file this build can't parse: the project is still imported and the list will deal with it.
        }
        return dest;
    }

    /** Staging folders left behind by an import that was killed with the app; never touches one that is in use. */
    private static void cleanStaleStaging(File filesRoot) {
        if (ACTIVE_IMPORTS.get() > 1) return; // another import is running right now (this one counts itself)
        File[] stale = filesRoot.listFiles((dir, name) -> name.startsWith(STAGING_PREFIX));
        if (stale != null) for (File f : stale) ProjectZip.deleteRecursively(f);
    }

    // ------------------------------------------------------------------ source / helpers

    /**
     * The ZIP as a stream, plus its central directory when the document is a seekable file (nearly all local and
     * Downloads documents). A provider that can only stream still works: no directory, so progress follows the
     * compressed bytes read.
     */
    private static final class Source implements AutoCloseable {
        InputStream stream;
        ProjectZip.Directory directory;
        long length = -1;

        static Source open(ContentResolver resolver, Uri uri) throws IOException {
            Source s = new Source();
            s.length = queryLength(resolver, uri);
            ParcelFileDescriptor pfd = null;
            try {
                pfd = resolver.openFileDescriptor(uri, "r");
            } catch (FileNotFoundException | SecurityException | IllegalArgumentException ignored) {
                // no file descriptor for this provider: stream it below
            }
            if (pfd != null) {
                FileInputStream fis = new ParcelFileDescriptor.AutoCloseInputStream(pfd); // owns (and closes) the descriptor
                try {
                    FileChannel ch = fis.getChannel();
                    long size = ch.size();
                    if (size > 0) {
                        s.length = size;
                        s.directory = ProjectZip.readDirectory(ch);
                        ch.position(0);
                    }
                    s.stream = fis;
                    return s;
                } catch (ProjectZip.InvalidArchiveException e) {
                    try { fis.close(); } catch (IOException ignored) { /* the refusal is the news */ }
                    throw e;
                } catch (IOException e) { // not seekable after all
                    try { fis.close(); } catch (IOException ignored) { /* reopened as a plain stream below */ }
                    s.directory = null;
                }
            }
            InputStream in = resolver.openInputStream(uri);
            if (in == null) throw new FileNotFoundException("Couldn't open the ZIP.");
            s.stream = in;
            return s;
        }

        @Override
        public void close() throws IOException {
            if (stream != null) stream.close();
        }
    }

    /** Size of a document in bytes as its provider reports it, or -1. */
    private static long queryLength(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (RuntimeException ignored) {
            // some providers don't answer this
        }
        return -1;
    }

    private static void deleteQuietly(ContentResolver resolver, Uri uri) {
        try {
            if (!DocumentsContract.deleteDocument(resolver, uri)) resolver.delete(uri, null, null);
        } catch (Exception ignored) {
            // best effort: a leftover partial file is the lesser evil
        }
    }

    /** A sentence for the user for whatever went wrong. */
    public static String describe(Throwable e) {
        if (e instanceof InsufficientStorageException || e instanceof ProjectZip.InvalidArchiveException) return e.getMessage();
        String m = e.getMessage();
        if (m != null && (m.contains("ENOSPC") || m.toLowerCase().contains("no space left"))) {
            return "There isn't enough free storage space to finish.";
        }
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
