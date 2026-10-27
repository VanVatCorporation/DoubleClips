package com.vanvatcorporation.doubleclips.helper;

import java.io.Serializable;

/** Which list of templates a screen shows. Travels in an Intent so the preview pager can keep loading the same list. */
public class TemplateFeedSource implements Serializable {
    public enum Kind { FEED, SEARCH, LIKED, BOOKMARKED, MINE }

    public final Kind kind;
    /** Only for SEARCH. */
    public final String query;

    private TemplateFeedSource(Kind kind, String query) {
        this.kind = kind;
        this.query = query == null ? "" : query;
    }

    /** The per-account suggestion feed (the Templates tab). */
    public static TemplateFeedSource feed() { return new TemplateFeedSource(Kind.FEED, null); }
    public static TemplateFeedSource search(String query) { return new TemplateFeedSource(Kind.SEARCH, query); }
    /** The signed-in account's Liked list (Profile). */
    public static TemplateFeedSource liked() { return new TemplateFeedSource(Kind.LIKED, null); }
    /** The signed-in account's Saved (bookmarked) list (Profile). */
    public static TemplateFeedSource bookmarked() { return new TemplateFeedSource(Kind.BOOKMARKED, null); }
    /** The templates the signed-in account posted itself (Profile -> My templates), newest first. */
    public static TemplateFeedSource mine() { return new TemplateFeedSource(Kind.MINE, null); }
}
