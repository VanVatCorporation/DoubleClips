package com.vanvatcorporation.drivekit;

/** viewer < editor < manager < owner. Roles travel as lowercase strings. */
public final class Roles {
    private Roles() {}
    public static final int NONE = 0, VIEWER = 1, EDITOR = 2, MANAGER = 3, OWNER = 4;
    public static final String[] GRANTABLE = {"viewer", "editor", "manager"};

    public static int rank(String role) {
        if (role == null) return NONE;
        switch (role) {
            case "viewer": return VIEWER;
            case "editor": return EDITOR;
            case "manager": return MANAGER;
            case "owner": return OWNER;
            default: return NONE;
        }
    }
}
