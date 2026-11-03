package com.vanvatcorporation.doubleclips.activities;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.main.TemplateAreaScreen.TemplateData;
import com.vanvatcorporation.doubleclips.activities.main.TemplateGridAdapter;
import com.vanvatcorporation.doubleclips.helper.TemplateApi;
import com.vanvatcorporation.doubleclips.helper.TemplateFeedSource;
import com.vanvatcorporation.doubleclips.helper.TemplatePager;
import com.vanvatcorporation.doubleclips.helper.TemplateSearchHints;
import com.vanvatcorporation.doubleclips.helper.TemplateSearchHistory;
import com.vanvatcorporation.doubleclips.helper.TemplateSearchParse;
import com.vanvatcorporation.doubleclips.helper.TrendingSearch;
import com.vanvatcorporation.doubleclips.impl.AppCompatActivityImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The search window, opened from the Templates tab's search bar (the same screen as on iOS and Desktop).
 *
 * <ul>
 *   <li>Empty field: recent searches (this phone), "You may like" and the ranked "Trending" list (server).</li>
 *   <li>Typing: suggestions from the server (instant local matches first); the arrow copies one into the field.</li>
 *   <li>Search / Enter / a tapped row: the results, paged from the server by the same pager the Templates tab uses.
 *       Tapping the field again goes back to the suggestions.</li>
 * </ul>
 * An empty Search press searches the hot term that was showing in the bar (it is the field's hint).
 */
public class TemplateSearchActivity extends AppCompatActivityImpl {
    /** Extra: the hot search that was showing in the bar (String). */
    public static final String EXTRA_PLACEHOLDER = "SearchPlaceholder";
    /** Extra: titles of templates the app already has, as suggestions when the server has none (ArrayList of String). */
    public static final String EXTRA_TITLES = "SearchFallbackTitles";

    private static final int HANDOFF_MAX = 100;
    private static final int PREFETCH_DISTANCE = 6;
    private static final long SUGGEST_DEBOUNCE_MS = 250;
    private static final int HISTORY_COLLAPSED = 2;

    private enum Phase { INPUT, RESULTS }

    private View rootView, clearButton, submitButton, inputScroll, resultsPane;
    private EditText field;
    private LinearLayout inputContainer;
    private RecyclerView resultsRecycler;
    private SwipeRefreshLayout refresh;
    private TextView emptyText;

    private TemplateGridAdapter adapter;
    private TemplatePager pager;
    private TemplateSearchHistory history;
    private TemplateSearchHints hints;

    private String placeholder = "";
    private final List<String> fallbackTitles = new ArrayList<>();
    private Phase phase = Phase.INPUT;
    private boolean showAllHistory = false;
    /** True while the code (not the user) writes into the field: the text watcher stays quiet. */
    private boolean settingText = false;

    private List<String> serverSuggestions = new ArrayList<>();
    private String serverSuggestionsFor = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingSuggest;

    private final Runnable refreshInput = () -> { if (phase == Phase.INPUT) renderInput(); };
    private final TemplateApi.StateListener stateListener = id -> {
        if (adapter != null) adapter.notifyTemplateChanged(id);
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_template_search);

        Intent intent = getIntent();
        String given = intent.getStringExtra(EXTRA_PLACEHOLDER);
        placeholder = given == null ? "" : given.trim();
        List<String> titles = intent.getStringArrayListExtra(EXTRA_TITLES);
        if (titles != null) fallbackTitles.addAll(titles);

        history = TemplateSearchHistory.getInstance(this);
        hints = TemplateSearchHints.getInstance();

        rootView = findViewById(R.id.searchRoot);
        field = findViewById(R.id.searchField);
        clearButton = findViewById(R.id.searchClearButton);
        submitButton = findViewById(R.id.searchSubmitButton);
        inputScroll = findViewById(R.id.searchInputScroll);
        inputContainer = findViewById(R.id.searchInputContainer);
        resultsPane = findViewById(R.id.searchResultsPane);
        resultsRecycler = findViewById(R.id.searchResults);
        refresh = findViewById(R.id.searchRefresh);
        emptyText = findViewById(R.id.searchEmptyText);

        findViewById(R.id.searchBackButton).setOnClickListener(v -> finish());
        submitButton.setOnClickListener(v -> submit(null));
        clearButton.setOnClickListener(v -> {
            setField("");
            showInput();
            field.requestFocus();
            showKeyboard();
        });

        field.setHint(placeholder.isEmpty() ? getString(R.string.template_search_hint) : placeholder);
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL) {
                submit(null);
                return true;
            }
            return false;
        });
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                if (settingText) return;
                clearButton.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                if (phase != Phase.INPUT) showInput();
                renderInput();
                scheduleSuggestions();
            }
        });
        // Tapping the field in the results = back to the suggestions.
        field.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus && phase == Phase.RESULTS) showInput(); });
        field.setOnClickListener(v -> {
            if (phase == Phase.RESULTS) showInput();
            showKeyboard();
        });

        inputScroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> { if (y != oldY) hideKeyboard(); });

        setupResults();

        hints.refresh(this, false);
        renderInput();

        // Let the screen finish opening, then raise the keyboard.
        handler.postDelayed(() -> {
            if (phase == Phase.INPUT) {
                field.requestFocus();
                showKeyboard();
            }
        }, 350);
    }

    @Override
    protected void onStart() {
        super.onStart();
        hints.addListener(refreshInput);
        history.addListener(refreshInput);
        TemplateApi.addStateListener(stateListener);
    }

    @Override
    protected void onStop() {
        hints.removeListener(refreshInput);
        history.removeListener(refreshInput);
        TemplateApi.removeStateListener(stateListener);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---- results -------------------------------------------------------------------------------------------------

    private void setupResults() {
        final StaggeredGridLayoutManager layoutManager = new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        layoutManager.setGapStrategy(StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS);
        resultsRecycler.setLayoutManager(layoutManager);

        adapter = new TemplateGridAdapter(this, this::openPreview);
        resultsRecycler.setAdapter(adapter);

        pager = new TemplatePager(this, new TemplatePager.Listener() {
            @Override public void onReset() {
                adapter.clear();
                emptyText.setVisibility(View.GONE);
            }
            @Override public void onPage(List<TemplateData> items, boolean hasMore) {
                adapter.addAll(items);
                updateEmpty();
            }
            @Override public void onLoading(boolean loading) {
                if (!loading) refresh.setRefreshing(false);
            }
            @Override public void onError(String message) {
                refresh.setRefreshing(false);
                Toast.makeText(TemplateSearchActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });

        resultsRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy <= 0) return;
                int[] last = layoutManager.findLastVisibleItemPositions(null);
                int max = 0;
                for (int p : last) max = Math.max(max, p);
                if (max >= adapter.getItemCount() - PREFETCH_DISTANCE) pager.loadNext();
            }
        });

        refresh.setOnRefreshListener(() -> pager.start(pager.getSource()));
    }

    private void updateEmpty() {
        emptyText.setVisibility(phase == Phase.RESULTS && adapter.getItemCount() == 0 && !pager.hasMore() && !pager.isLoading()
                ? View.VISIBLE : View.GONE);
    }

    private void openPreview(List<TemplateData> items, int position) {
        Intent intent = new Intent(this, TemplatePreviewActivity.class);
        int end = Math.min(items.size(), position + HANDOFF_MAX);
        TemplateData[] handoff = new ArrayList<>(items.subList(position, end)).toArray(new TemplateData[0]);
        intent.putExtra("TemplateDataList", handoff);
        intent.putExtra("TemplateFeedSource", pager.getSource());
        intent.putExtra("TemplateNextCursor", end == items.size() ? pager.getCursor() : null);
        startActivity(intent);
    }

    // ---- actions ---------------------------------------------------------------------------------------------------

    private String typed() {
        return field.getText().toString().trim();
    }

    private void setField(String text) {
        settingText = true;
        field.setText(text);
        field.setSelection(field.getText().length());
        settingText = false;
        clearButton.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Search / Enter / a tapped row. {@code chosen} null = what is in the field (or the hot term in the bar when it is empty). */
    private void submit(@Nullable String chosen) {
        String query = (chosen != null ? chosen : typed()).trim();
        if (query.isEmpty()) query = placeholder;          // an empty Search press searches the hot term in the bar
        if (query.isEmpty()) return;

        if (pendingSuggest != null) handler.removeCallbacks(pendingSuggest);
        setField(query);
        hideKeyboard();
        rootView.requestFocus();                            // the field lets go, so tapping it again is a "back to suggestions"
        showResults();

        history.add(query);
        TemplateApi.logSearch(this, query);
        refresh.setRefreshing(true);
        pager.start(TemplateFeedSource.search(query));
    }

    private void showInput() {
        phase = Phase.INPUT;
        inputScroll.setVisibility(View.VISIBLE);
        resultsPane.setVisibility(View.GONE);
        submitButton.setVisibility(View.VISIBLE);
        renderInput();
    }

    private void showResults() {
        phase = Phase.RESULTS;
        inputScroll.setVisibility(View.GONE);
        resultsPane.setVisibility(View.VISIBLE);
        submitButton.setVisibility(View.GONE);
    }

    // ---- suggestions / start page ----------------------------------------------------------------------------------

    private void renderInput() {
        inputContainer.removeAllViews();
        String typed = typed();
        if (typed.isEmpty()) addStartPage(); else addSuggestionRows(typed);
    }

    private void addStartPage() {
        // Recent searches
        final List<String> all = history.items();
        List<String> rows = showAllHistory ? all : all.subList(0, Math.min(HISTORY_COLLAPSED, all.size()));
        for (final String item : rows) {
            View row = newRow(R.drawable.baseline_history_24, item);
            icon(row).setColorFilter(themeColor(android.R.attr.textColorPrimary));
            action(row, R.drawable.baseline_close_24, v -> history.remove(item));
            row.setOnClickListener(v -> submit(item));
            inputContainer.addView(row);
        }
        if (all.size() > HISTORY_COLLAPSED) {
            TextView toggle = new TextView(this);
            toggle.setText(showAllHistory ? R.string.template_search_show_less : R.string.template_search_show_all);
            toggle.setTextColor(themeColor(android.R.attr.textColorSecondary));
            toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            toggle.setTypeface(toggle.getTypeface(), android.graphics.Typeface.BOLD);
            toggle.setGravity(Gravity.CENTER);
            toggle.setPadding(0, dp(12), 0, dp(12));
            toggle.setOnClickListener(v -> {
                showAllHistory = !showAllHistory;
                renderInput();
            });
            inputContainer.addView(toggle, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        // You may like
        List<String> forYou = hints.forYou();
        if (!forYou.isEmpty()) {
            inputContainer.addView(sectionTitle(R.string.template_search_you_may_like));
            for (final String item : forYou) {
                View row = newRow(R.drawable.ic_search_dot, item);
                row.setOnClickListener(v -> submit(item));
                inputContainer.addView(row);
            }
        }

        // Trending
        List<TrendingSearch> trending = hints.trending();
        if (!trending.isEmpty()) {
            inputContainer.addView(sectionTitle(R.string.template_search_trending));
            int primary = themeColor(androidx.appcompat.R.attr.colorPrimary);
            int secondary = themeColor(android.R.attr.textColorSecondary);
            for (int rank = 0; rank < trending.size(); rank++) {
                final TrendingSearch item = trending.get(rank);
                View row = newRow(0, item.text);
                icon(row).setVisibility(View.GONE);
                TextView rankView = row.findViewById(R.id.rowRank);
                rankView.setVisibility(View.VISIBLE);
                rankView.setText(String.valueOf(rank + 1));
                rankView.setTextColor(rank < 3 ? primary : secondary);
                row.findViewById(R.id.rowFlame).setVisibility(item.hot ? View.VISIBLE : View.GONE);
                if (item.count != null) {
                    TextView count = row.findViewById(R.id.rowCount);
                    count.setVisibility(View.VISIBLE);
                    count.setText(TemplateSearchParse.compactCount(item.count));
                }
                row.setOnClickListener(v -> submit(item.text));
                inputContainer.addView(row);
            }
        }
    }

    private void addSuggestionRows(String typed) {
        int secondary = themeColor(android.R.attr.textColorSecondary);
        for (final String suggestion : shownSuggestions(typed)) {
            View row = newRow(R.drawable.baseline_search_24, suggestion);
            icon(row).setColorFilter(secondary);
            // The typed part grey, the rest in the normal colour (like CapCut).
            int n = TemplateSearchParse.typedPrefixLength(suggestion, typed);
            if (n > 0) {
                SpannableString spanned = new SpannableString(suggestion);
                spanned.setSpan(new ForegroundColorSpan(secondary), 0, n, 0);
                ((TextView) row.findViewById(R.id.rowText)).setText(spanned);
            }
            // Copy into the field and keep typing.
            action(row, R.drawable.ic_search_arrow_up_left, v -> {
                setField(suggestion);
                renderInput();
                scheduleSuggestions();
            });
            row.setOnClickListener(v -> submit(suggestion));
            inputContainer.addView(row);
        }
    }

    private List<String> shownSuggestions(String typed) {
        if (serverSuggestionsFor.equals(typed.toLowerCase(Locale.ROOT)) && !serverSuggestions.isEmpty()) return serverSuggestions;
        // Instant matches from the phone while the server answers (and when it cannot).
        return TemplateSearchParse.localSuggestions(typed, history.items(), hints.trending(), fallbackTitles);
    }

    private void scheduleSuggestions() {
        if (pendingSuggest != null) handler.removeCallbacks(pendingSuggest);
        final String query = typed();
        if (phase != Phase.INPUT || query.isEmpty()) {
            serverSuggestions = new ArrayList<>();
            serverSuggestionsFor = "";
            return;
        }
        pendingSuggest = () -> TemplateApi.fetchSuggestions(this, query, 10, new TemplateApi.ApiCallback<List<String>>() {
            @Override public void onSuccess(List<String> list) {
                if (isFinishing() || phase != Phase.INPUT || !query.equals(typed())) return;   // the user moved on
                serverSuggestions = list;
                serverSuggestionsFor = query.toLowerCase(Locale.ROOT);
                renderInput();
            }
            @Override public void onError(String message) { /* the local suggestions stay */ }
        });
        handler.postDelayed(pendingSuggest, SUGGEST_DEBOUNCE_MS);
    }

    // ---- small view helpers ----------------------------------------------------------------------------------------

    private View newRow(int iconRes, CharSequence text) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_search_row, inputContainer, false);
        ImageView icon = row.findViewById(R.id.rowIcon);
        if (iconRes != 0) icon.setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.rowText)).setText(text);
        return row;
    }

    private static ImageView icon(View row) {
        return row.findViewById(R.id.rowIcon);
    }

    private void action(View row, int iconRes, View.OnClickListener onClick) {
        ImageButton button = row.findViewById(R.id.rowAction);
        button.setImageResource(iconRes);
        button.setColorFilter(themeColor(android.R.attr.textColorSecondary));
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(onClick);
        if (iconRes == R.drawable.baseline_close_24) button.setContentDescription(getString(R.string.template_search_clear));
    }

    private TextView sectionTitle(int stringRes) {
        TextView title = new TextView(this);
        title.setText(stringRes);
        title.setTextColor(themeColor(android.R.attr.textColorPrimary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(dp(16), dp(24), dp(16), dp(10));
        return title;
    }

    private int themeColor(int attr) {
        TypedArray a = obtainStyledAttributes(new int[]{attr});
        try {
            ColorStateList list = a.getColorStateList(0);
            return list == null ? 0xFF808080 : list.getDefaultColor();
        } finally {
            a.recycle();
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }
}
