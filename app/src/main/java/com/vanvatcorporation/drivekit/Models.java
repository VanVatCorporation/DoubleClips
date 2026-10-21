package com.vanvatcorporation.drivekit;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** JSON shapes of the Drive API (docs/vanvat-drive-internal-api.md). Gson fills the public fields. */
public final class Models {
    private Models() {}

    public static class Node {
        public String id, parentId, ownerId, createdBy, name, mimeType, sha256, createdAt, updatedAt, deletedAt, systemKey, myRole;
        public boolean isFolder;
        public long size;

        public boolean isSystemFolder() { return systemKey != null; }
        public boolean canEdit() { return Roles.rank(myRole) >= Roles.EDITOR; }
        public boolean canManage() { return Roles.rank(myRole) >= Roles.MANAGER; }
    }

    public static class PathItem { public String id, name; }

    public static class Usage {
        public long uploadedBytes, downloadedBytes, uploadLimit, downloadLimit;
        public String resetsAt;
        /** Account-wide compute quota in credits, shared with every Van Vat service (absent on older servers). */
        public Double computeUsed, computeLimit;
    }

    /** The subscription tier of the account (free, personal, pro...). */
    public static class Plan {
        public String key, name, expiresAt;
    }

    public static class Me {
        public String userId, username, email;
        public long quotaBytes, usedBytes;
        public int chunkSize;
        public Usage usage;
        /** Largest single file the plan allows (0 on older servers). */
        public long maxFileBytes;
        public Plan plan;
    }

    public static class Listing {
        public String role;
        public List<PathItem> path;
        public List<Node> items;
        public boolean hasMore;
    }

    public static class SharedItem { public Node node; public String permissionId, sharedBy; }

    public static class Person { public String permissionId, granteeId, role, username, email; }

    public static class LinkInfo {
        public String id, nodeId, token, url, expiresAt, createdAt;
        public boolean rawServing;
        public long downloadCount;
    }

    public static class AccessInfo {
        public static class Owner { public String id, username; }
        public String myRole;
        public Owner owner;
        public List<Person> people;
        public List<LinkInfo> links;
    }

    public static class PublicNode {
        public String id, name, mimeType, updatedAt;
        public boolean isFolder;
        public long size;
    }

    public static class PublicMeta { public boolean rawServing; public PublicNode node; }

    public static class PublicListing {
        public static class Root { public String id, name; }
        public Root root;
        public String parentId;
        public List<PublicNode> items;
    }

    public static class UploadSession { public String uploadId; public int chunkSize; public long received; }

    public static class ZipPlan {
        public int fileCount;
        public long totalBytes, remainingBytes;
        public boolean allowed;
        public String resetsAt;
        /** Separate verdicts (null from older servers): the daily download cap and the compute quota. */
        public Boolean downloadAllowed, computeAllowed;
        public Double computeCredits, computeRemainingCredits;
    }

    /** Dates arrive as ISO-8601 UTC ("2026-10-19T02:45:00.1234567Z"). */
    public static long parseDate(String iso) {
        if (iso == null) return 0;
        try {
            String s = iso.replaceAll("(\\.\\d{3})\\d+", "$1");
            boolean frac = s.contains(".");
            SimpleDateFormat f = new SimpleDateFormat(frac ? "yyyy-MM-dd'T'HH:mm:ss.SSSX" : "yyyy-MM-dd'T'HH:mm:ssX", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(s);
            return d == null ? 0 : d.getTime();
        } catch (Exception e) {
            return 0;
        }
    }
}
