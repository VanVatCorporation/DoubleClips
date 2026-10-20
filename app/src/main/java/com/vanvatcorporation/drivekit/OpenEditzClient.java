package com.vanvatcorporation.drivekit;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The OpenEditz client: the part of the Drive API the editor needs, locked to the user's protected OpenEditz folder.
 * Everything starts from root() (POST /system/openeditz) and only follows children from there, so the editor can never
 * read or write outside OpenEditz, even though the same account owns the rest of the Drive. Blocking: use a worker thread.
 */
public final class OpenEditzClient {
    private final DriveClient client;
    private Models.Node root;
    private final Set<String> inside = new HashSet<>();

    public OpenEditzClient(DriveClient client) { this.client = client; }

    public DriveClient client() { return client; }

    public synchronized Models.Node root() throws DriveException {
        if (root == null) {
            root = client.ensureOpenEditz();
            inside.add(root.id);
        }
        return root;
    }

    /** Children of a folder inside OpenEditz (null = the OpenEditz folder itself). */
    public List<Models.Node> list(Models.Node folder) throws DriveException {
        Models.Node parent = scoped(folder);
        Models.Listing listing = client.list(parent.id);
        synchronized (this) { for (Models.Node n : listing.items) inside.add(n.id); }
        return listing.items;
    }

    public Models.Node createFolder(String name, Models.Node in) throws DriveException {
        Models.Node parent = scoped(in);
        Models.Node node = client.createFolder(parent.id, name);
        synchronized (this) { inside.add(node.id); }
        return node;
    }

    /** Walks/creates OpenEditz/<a>/<b>/... and returns the last folder. */
    public Models.Node ensureFolder(String... path) throws DriveException {
        Models.Node current = root();
        for (String name : path) {
            Models.Node found = null;
            for (Models.Node child : list(current)) {
                if (child.isFolder && child.name.equalsIgnoreCase(name)) { found = child; break; }
            }
            current = found != null ? found : createFolder(name, current);
        }
        return current;
    }

    /** Resumable upload into a folder inside OpenEditz (the 512 MB per file / daily caps apply). */
    public Models.Node upload(File file, Models.Node into, String name, String mime,
                              DriveClient.Progress progress, ResumableUpload.Cancel cancel) throws IOException {
        Models.Node parent = scoped(into);
        Models.Node node = ResumableUpload.run(client, file, parent.id, name, mime, progress, cancel);
        synchronized (this) { inside.add(node.id); }
        return node;
    }

    public File download(Models.Node node, File dir, DriveClient.Progress progress) throws DriveException {
        requireInside(node);
        return client.download(node, dir, progress);
    }

    public Models.Node rename(Models.Node node, String name) throws DriveException {
        requireInside(node);
        return client.rename(node.id, name);
    }

    /** Deletes for good (trash + purge) so the space comes back right away. Folders go with everything inside. */
    public void delete(Models.Node node) throws DriveException {
        requireInside(node);
        client.trash(node.id);
        client.purge(node.id);
        synchronized (this) { inside.remove(node.id); }
    }

    private Models.Node scoped(Models.Node folder) throws DriveException {
        if (folder == null) return root();
        requireInside(folder);
        return folder;
    }

    private synchronized void requireInside(Models.Node node) throws DriveException {
        if (!inside.contains(node.id)) throw new DriveException(403, "outside_openeditz", "That item is not inside the OpenEditz folder.");
    }
}
