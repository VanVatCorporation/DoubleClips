package com.vanvatcorporation.drivekit;

import java.io.File;
import java.io.IOException;

/**
 * One chunked, resumable upload: POST /uploads, PUT chunks (Upload-Offset), POST /complete.
 * After a network error it asks the server how far it got and continues from there. Blocking: run it on a worker thread.
 */
public final class ResumableUpload {
    private ResumableUpload() {}

    /** Cancel flag. `check` lets another cancel source (e.g. the app's own Cancellation) be polled as well. */
    public static final class Cancel {
        public interface Check { boolean isCancelled(); }
        public volatile boolean cancelled;
        public Check check;
        public void cancel() { cancelled = true; }
        public boolean isCancelled() { return cancelled || (check != null && check.isCancelled()); }
    }

    public static final class CancelledException extends IOException {
        public CancelledException() { super("Cancelled"); }
    }

    public static Models.Node run(DriveClient client, File file, String parentId, String name, String mime,
                                  DriveClient.Progress progress, Cancel cancel) throws IOException {
        final long size = file.length();
        Models.UploadSession session = client.createUpload(parentId, name != null ? name : file.getName(), size, mime);
        try {
            long offset = session.received;
            int attempts = 0;
            while (offset < size) {
                if (cancel != null && cancel.isCancelled()) throw new CancelledException();
                long length = Math.min((long) session.chunkSize, size - offset);
                try {
                    final long base = offset;
                    DriveClient.ChunkResult r = client.putChunk(session.uploadId, file, offset, length,
                            (done, total) -> { if (progress != null) progress.onProgress(base + done, size); }, cancel);
                    offset = r.offset;
                    attempts = 0;
                } catch (DriveException | CancelledException e) {
                    throw e;                                            // a real server answer (quota, forbidden, 429...) or cancel
                } catch (IOException e) {
                    if (cancel != null && cancel.isCancelled()) throw new CancelledException();
                    if (++attempts > 5) throw e;
                    try { Thread.sleep(800L * attempts); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
                    offset = client.uploadReceived(session.uploadId);   // resume where the server says it is
                }
                if (progress != null) progress.onProgress(offset, size);
            }
            return client.completeUpload(session.uploadId);
        } catch (IOException | RuntimeException e) {
            client.cancelUpload(session.uploadId);
            throw e;
        }
    }
}
