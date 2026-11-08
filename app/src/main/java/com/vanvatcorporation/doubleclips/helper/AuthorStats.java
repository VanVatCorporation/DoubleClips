package com.vanvatcorporation.doubleclips.helper;

/**
 * The answer of GET /templates/stats (docs/doubleclips-template-api-v2.md -> "Author statistics"). Gson fills the public fields; a
 * missing part stays null / 0, so read it through the helpers below.
 */
public final class AuthorStats {
    public int days;
    public Totals totals;
    public Period period;
    /** The current period, one entry per local day, oldest first. */
    public Day[] series;
    /** The account's newest 100 templates. */
    public Item[] templates;
    public boolean templatesTruncated;

    /** All time: the templates' own counters, plus the distinct people who opened them (the author excluded). */
    public static final class Totals {
        public int templates;
        public long views, uses, likes, saves, comments;
        public int uniqueViewers;
    }

    /** Other people's activity over a whole period. */
    public static final class Counts {
        public long newViewers, uses, likes, saves, comments;
    }

    public static final class Period {
        public Counts current, previous;
    }

    public static final class Day {
        /** yyyy-MM-dd, the phone's local day. */
        public String date;
        public int newViewers, uses, likes, saves, comments;
    }

    public static final class Item {
        public String templateId, title;
        public long views, uses, likes, saves, comments;
    }

    public Totals totalsOrEmpty() { return totals != null ? totals : new Totals(); }
    public Counts current() { return period != null && period.current != null ? period.current : new Counts(); }
    public Counts previous() { return period != null && period.previous != null ? period.previous : new Counts(); }
    public Day[] seriesOrEmpty() { return series != null ? series : new Day[0]; }
    public Item[] templatesOrEmpty() { return templates != null ? templates : new Item[0]; }
}
