package com.vanvatcorporation.doubleclips.helper;

/** One hot search, as the server ranks it (the "Trending" list and the ticker in the Templates tab's search bar). */
public final class TrendingSearch {
    public final String text;
    /** How many searches it had lately (shown on the right of the list); null = the server didn't send it. */
    public final Integer count;
    /** Gets the little flame. */
    public final boolean hot;

    public TrendingSearch(String text, Integer count, boolean hot) {
        this.text = text;
        this.count = count;
        this.hot = hot;
    }
}
