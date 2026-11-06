package com.vanvatcorporation.doubleclips.assets;

import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.helper.BlockedAuthors;
import com.vanvatcorporation.doubleclips.helper.HiddenTemplates;

import java.util.List;

/**
 * Settings > Hidden content (desktop: HiddenContentWindow, iOS: HiddenContentView): what the user hid, with a way back. Two lists, both
 * kept on this phone only: the asset authors hidden in the Assets tab, and the templates hidden in the template preview. "Show again"
 * brings one back (the Assets list redraws at once; the Templates feed reloads to bring a template back), "Show all" brings back the
 * whole list.
 */
public class HiddenContentSheet extends BottomSheetDialogFragment {

    private LinearLayout content;
    private Runnable onClosed;
    private final Runnable authorsListener = this::redraw;
    private final HiddenTemplates.Listener templatesListener = unhid -> redraw();

    /** @param onClosed runs when the sheet closes (Settings updates its summary line) */
    public static void show(FragmentActivity activity, Runnable onClosed) {
        HiddenContentSheet sheet = new HiddenContentSheet();
        sheet.onClosed = onClosed;
        sheet.show(activity.getSupportFragmentManager(), "hidden_content");
    }

    /** "Hidden: 2 asset authors, 1 templates", for the line in Settings. */
    public static String summary(Context context) {
        return context.getString(R.string.hidden_content_summary,
                BlockedAuthors.getInstance(context).names().size(), HiddenTemplates.getInstance(context).all().size());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.sheet_hidden_content, container, false);
        content = root.findViewById(R.id.hiddenContent);
        BlockedAuthors.getInstance(requireContext()).addListener(authorsListener);
        HiddenTemplates.getInstance(requireContext()).addListener(templatesListener);
        redraw();
        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetDialog dialog = (BottomSheetDialog) getDialog();
            View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) sheet.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
            BottomSheetBehavior<?> behavior = dialog.getBehavior();
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            behavior.setSkipCollapsed(true);
        }
    }

    @Override
    public void onDestroyView() {
        BlockedAuthors.getInstance(requireContext()).removeListener(authorsListener);
        HiddenTemplates.getInstance(requireContext()).removeListener(templatesListener);
        content = null;
        super.onDestroyView();
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        if (onClosed != null) {
            Runnable r = onClosed;
            onClosed = null;
            r.run();
        }
    }

    private void redraw() {
        if (content == null || !isAdded()) return;
        Context context = requireContext();
        content.removeAllViews();

        // ---- asset authors
        final BlockedAuthors authors = BlockedAuthors.getInstance(context);
        List<String> names = authors.names();
        LinearLayout authorRows = section(R.string.hidden_content_authors, names.isEmpty() ? null : authors::unblockAll);
        if (names.isEmpty()) authorRows.addView(emptyLine(R.string.hidden_content_none_authors));
        for (final String name : names) {
            authorRows.addView(row("@" + name, null, () -> authors.unblock(name)));
        }

        // ---- templates
        final HiddenTemplates templates = HiddenTemplates.getInstance(context);
        List<HiddenTemplates.Entry> hidden = templates.all();
        LinearLayout templateRows = section(R.string.hidden_content_templates, hidden.isEmpty() ? null : templates::unhideAll);
        if (hidden.isEmpty()) templateRows.addView(emptyLine(R.string.hidden_content_none_templates));
        for (final HiddenTemplates.Entry e : hidden) {
            String title = e.title == null || e.title.trim().isEmpty() ? e.id : e.title.trim();
            String by = e.author == null || e.author.trim().isEmpty() ? null : "@" + e.author.trim();
            templateRows.addView(row(title, by, () -> templates.unhide(e.id)));
        }
    }

    /** A titled card added to the list; {@code showAll} null = no "Show all" button (nothing to show). Returns where its rows go. */
    private LinearLayout section(int titleRes, @Nullable Runnable showAll) {
        View card = LayoutInflater.from(requireContext()).inflate(R.layout.item_hidden_section, content, false);
        ((TextView) card.findViewById(R.id.sectionTitle)).setText(titleRes);
        View button = card.findViewById(R.id.sectionShowAll);
        if (showAll == null) button.setVisibility(View.GONE);
        else button.setOnClickListener(v -> showAll.run());
        content.addView(card);
        return card.findViewById(R.id.sectionRows);
    }

    private View row(String title, @Nullable String subtitle, Runnable showAgain) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_hidden_row, content, false);
        ((TextView) row.findViewById(R.id.rowTitle)).setText(title);
        TextView sub = row.findViewById(R.id.rowSubtitle);
        if (subtitle == null) sub.setVisibility(View.GONE);
        else sub.setText(subtitle);
        row.findViewById(R.id.rowShowAgain).setOnClickListener(v -> showAgain.run());
        return row;
    }

    private TextView emptyLine(int textRes) {
        TextView t = new TextView(requireContext());
        t.setText(textRes);
        t.setTextSize(13);
        t.setPadding(0, 8, 0, 8);
        t.setTextColor(0xFF8E8E93);
        return t;
    }
}
