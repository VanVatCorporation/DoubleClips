package com.vanvatcorporation.doubleclips.assets;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
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
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;

import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.helper.TemplateSearchHistory;
import com.vanvatcorporation.doubleclips.helper.TemplateSearchParse;
import com.vanvatcorporation.doubleclips.helper.TrendingSearch;
import com.vanvatcorporation.drivekit.AssetsClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Assets search window (desktop: AssetSearchOverlay, iOS: AssetSearchView), the same flow as the Templates one:
 * <ul>
 *   <li>Empty field: your recent searches, "You may like" and the ranked "Trending" list from the server.</li>
 *   <li>Typing: suggestions from the server (instant matches from this phone first); the arrow copies one into the field.</li>
 *   <li>Search / Enter / a tapped row: the results, paged from the server by the same {@link AssetsBrowserView} the tab uses (kind
 *       chips, sort, Get). Tapping the field again goes back to the suggestions.</li>
 * </ul>
 * An empty Search press searches the hot term that was showing in the bar (the field's hint). A submitted search is remembered here
 * and, when signed in, told to the server, which ranks "Trending" from them. It is a full-screen window over whatever opened it: the
 * Assets tab, or the editor's Assets sheet (then media and fonts go into the open project, {@link AssetTarget}).
 */
public class AssetSearchFragment extends DialogFragment {

    private static final String ARG_PLACEHOLDER = "placeholder";
    private static final String ARG_TITLES = "titles";
    private static final long SUGGEST_DEBOUNCE_MS = 250;
    private static final int HISTORY_COLLAPSED = 2;
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);

    private enum Phase { INPUT, RESULTS }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private AssetTarget target;
    private AssetDetailSheet.ProjectsProvider projects;

    private View rootView, clearButton, submitButton, inputScroll;
    private EditText field;
    private LinearLayout inputContainer;
    private AssetsBrowserView results;

    private TemplateSearchHistory history;
    private AssetSearchHints hints;
    private String placeholder = "";
    private final List<String> fallbackTitles = new ArrayList<>();
    private Phase phase = Phase.INPUT;
    private boolean showAllHistory;
    /** True while the code (not the user) writes into the field: the text watcher stays quiet. */
    private boolean settingText;

    private List<String> serverSuggestions = new ArrayList<>();
    private String serverSuggestionsFor = "";
    private Runnable pendingSuggest;
    private int suggestTicket;

    private final Runnable refreshInput = () -> { if (phase == Phase.INPUT && getView() != null) renderInput(); };

    /**
     * @param placeholder the hot search showing in the bar when it was tapped (null = none)
     * @param titles      titles of assets the screen already shows: suggestions when the server has none
     * @param target      where media and fonts go (the editor's project), or null in the tab
     * @param projects    the projects a font can go into from the tab (null in the editor)
     */
    public static void show(FragmentActivity activity, @Nullable String placeholder, List<String> titles, @Nullable AssetTarget target,
                            @Nullable AssetDetailSheet.ProjectsProvider projects) {
        AssetSearchFragment f = new AssetSearchFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PLACEHOLDER, placeholder);
        args.putStringArrayList(ARG_TITLES, new ArrayList<>(titles));
        f.setArguments(args);
        f.target = target;
        f.projects = projects;
        f.show(activity.getSupportFragmentManager(), "asset_search");
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Dialog dialog = super.onCreateDialog(savedInstanceState);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            Window window = dialog.getWindow();
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // the layout paints the background itself
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        hints.addListener(refreshInput);
        history.addListener(refreshInput);
    }

    @Override
    public void onStop() {
        hints.removeListener(refreshInput);
        history.removeListener(refreshInput);
        super.onStop();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_asset_search, container, false);
        Bundle args = getArguments();
        String given = args == null ? null : args.getString(ARG_PLACEHOLDER);
        placeholder = given == null ? "" : given.trim();
        List<String> titles = args == null ? null : args.getStringArrayList(ARG_TITLES);
        if (titles != null) fallbackTitles.addAll(titles);

        history = AssetSearchHistory.get(requireContext());
        hints = AssetSearchHints.getInstance();

        rootView = root.findViewById(R.id.assetSearchRoot);
        field = root.findViewById(R.id.asset_searchField);
        clearButton = root.findViewById(R.id.asset_searchClearButton);
        submitButton = root.findViewById(R.id.asset_searchSubmitButton);
        inputScroll = root.findViewById(R.id.asset_searchInputScroll);
        inputContainer = root.findViewById(R.id.asset_searchInputContainer);
        results = root.findViewById(R.id.searchResultsBrowser);
        results.setTarget(target);
        results.setProjectsProvider(projects);

        root.findViewById(R.id.asset_searchBackButton).setOnClickListener(v -> dismissAllowingStateLoss());
        submitButton.setOnClickListener(v -> submit(null));
        clearButton.setOnClickListener(v -> {
            setField("");
            showInput();
            field.requestFocus();
            showKeyboard();
        });

        field.setHint(placeholder.isEmpty() ? getString(R.string.assets_search_hint) : placeholder);
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

        hints.refresh(requireContext(), false);
        renderInput();

        // Let the window finish opening, then raise the keyboard.
        handler.postDelayed(() -> {
            if (phase == Phase.INPUT && field != null && isAdded()) {
                field.requestFocus();
                showKeyboard();
            }
        }, 350);
        return root;
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        suggestTicket++;
        super.onDestroyView();
    }

    // ---- actions -----------------------------------------------------------------------------------------

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
        if (query.isEmpty()) query = placeholder;           // an empty Search press searches the hot term in the bar
        if (query.isEmpty()) return;

        if (pendingSuggest != null) handler.removeCallbacks(pendingSuggest);
        suggestTicket++;
        setField(query);
        hideKeyboard();
        rootView.requestFocus();                             // the field lets go, so tapping it again is a "back to suggestions"
        showResults();

        history.add(query);
        logSearch(query);
        results.setQuery(query);                             // loads the first page for this query
    }

    /** Signed-in searches only (one person can't push a term up); best effort, never in the way of the results. */
    private void logSearch(String query) {
        if (!AssetsSession.hasSession(requireContext())) return;
        final AssetsClient client = AssetsSession.get(requireContext());
        IO.execute(() -> {
            try {
                client.logSearch(query);
            } catch (IOException | RuntimeException ignored) {
                // the ranking just misses one search
            }
        });
    }

    private void showInput() {
        phase = Phase.INPUT;
        inputScroll.setVisibility(View.VISIBLE);
        results.setVisibility(View.GONE);
        submitButton.setVisibility(View.VISIBLE);
        renderInput();
    }

    private void showResults() {
        phase = Phase.RESULTS;
        inputScroll.setVisibility(View.GONE);
        results.setVisibility(View.VISIBLE);
        submitButton.setVisibility(View.GONE);
    }

    // ---- suggestions / start page ------------------------------------------------------------------------

    private void renderInput() {
        if (inputContainer == null) return;
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
            TextView toggle = new TextView(requireContext());
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
        final AssetsClient client = AssetsSession.get(requireContext());
        pendingSuggest = () -> {
            final int ticket = ++suggestTicket;
            IO.execute(() -> {
                try {
                    List<String> found = client.suggest(query, 10);
                    handler.post(() -> {
                        // the user typed on, searched, or left meanwhile
                        if (!isAdded() || getView() == null || ticket != suggestTicket || phase != Phase.INPUT || !query.equals(typed())) return;
                        serverSuggestions = found;
                        serverSuggestionsFor = query.toLowerCase(Locale.ROOT);
                        renderInput();
                    });
                } catch (IOException | RuntimeException ignored) {
                    // the phone-side suggestions stay
                }
            });
        };
        handler.postDelayed(pendingSuggest, SUGGEST_DEBOUNCE_MS);
    }

    // ---- small view helpers ------------------------------------------------------------------------------

    private View newRow(int iconRes, CharSequence text) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_search_row, inputContainer, false);
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
        TextView title = new TextView(requireContext());
        title.setText(stringRes);
        title.setTextColor(themeColor(android.R.attr.textColorPrimary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(dp(16), dp(24), dp(16), dp(10));
        return title;
    }

    private int themeColor(int attr) {
        TypedArray a = requireContext().obtainStyledAttributes(new int[]{attr});
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
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }
}
