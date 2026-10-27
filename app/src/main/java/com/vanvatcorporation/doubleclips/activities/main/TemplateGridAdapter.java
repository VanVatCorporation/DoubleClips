package com.vanvatcorporation.doubleclips.activities.main;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.main.TemplateAreaScreen.TemplateData;
import com.vanvatcorporation.doubleclips.helper.AlgorithmHelper;
import com.vanvatcorporation.doubleclips.helper.NumberHelper;
import com.vanvatcorporation.doubleclips.helper.TemplateApi;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The grid of template cards, shared by the Templates tab and the Profile Liked / Saved lists.
 * Every bind reads the newest numbers from {@link TemplateApi#overlay}, so a like made in the preview shows here too.
 */
public class TemplateGridAdapter extends RecyclerView.Adapter<TemplateGridAdapter.ViewHolder> {

    public interface OnTemplateClick {
        /** The whole list as loaded so far, and the tapped position in it. */
        void onTemplateClick(List<TemplateData> items, int position);
    }

    public interface Keep {
        boolean keep(TemplateData data);
    }

    /** A few threads for thumbnails / avatars, instead of one new thread per bind (lists are long now). */
    private static final ExecutorService IMAGE_POOL = Executors.newFixedThreadPool(3);

    private final Context context;
    private final OnTemplateClick onClick;
    private final List<TemplateData> items = new ArrayList<>();

    public TemplateGridAdapter(Context context, OnTemplateClick onClick) {
        this.context = context;
        this.onClick = onClick;
    }

    public List<TemplateData> getItems() { return items; }

    public void clear() {
        items.clear();
        notifyDataSetChanged();
    }

    public void addAll(List<TemplateData> more) {
        if (more.isEmpty()) return;
        int start = items.size();
        items.addAll(more);
        notifyItemRangeInserted(start, more.size());
    }

    /** One template's numbers changed somewhere (preview, another list): repaint just its card. */
    public void notifyTemplateChanged(String templateId) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getTemplateId().equalsIgnoreCase(templateId)) {
                notifyItemChanged(i);
                return;
            }
        }
    }

    public void refreshAll() {
        notifyDataSetChanged();
    }

    /** Drops the cards that no longer belong (a template unliked in the preview leaves the Liked list). */
    public void retain(Keep keep) {
        boolean changed = false;
        for (Iterator<TemplateData> it = items.iterator(); it.hasNext(); ) {
            TemplateData d = it.next();
            TemplateApi.overlay(context, d);
            if (!keep.keep(d)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.cpn_template_element, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final TemplateData item = items.get(position);
        TemplateApi.overlay(context, item);
        holder.boundId = item.getTemplateId();

        holder.templateTitle.setText(item.getTemplateTitle());
        holder.templateTitle.setOnClickListener(v -> holder.wholeView.performClick());
        holder.authorTitle.setText("@" + item.getTemplateAuthor());

        holder.viewText.setText(NumberHelper.abbreviateNumber(item.getViewCount()));
        holder.heartText.setText(NumberHelper.abbreviateNumber(item.getHeartCount()));
        holder.useText.setText(NumberHelper.abbreviateNumber(item.getUseCount()));

        // The row may be recycled for another template before the image arrives: only paint if it is still ours.
        holder.templatePreview.setImageDrawable(null);
        holder.authorPreview.setImageDrawable(null);

        IMAGE_POOL.execute(() -> {
            try {
                final Bitmap thumbnail = item.getCacheThumbnailBitmap(context);
                holder.wholeView.post(() -> {
                    if (thumbnail == null || !item.getTemplateId().equals(holder.boundId)) return;
                    holder.templatePreview.setImageBitmap(thumbnail);
                    int targetWidth = holder.wholeView.getWidth();
                    int[] res = AlgorithmHelper.scaleByWidth(targetWidth, thumbnail.getWidth(), thumbnail.getHeight());

                    // enforce 9:16 ratio cap
                    int maxHeight = (res[0] * 16) / 9;
                    if (res[1] > maxHeight) res[1] = maxHeight;

                    ViewGroup.LayoutParams dimension = holder.templatePreview.getLayoutParams();
                    dimension.width = res[0];
                    dimension.height = res[1];
                    holder.templatePreview.setLayoutParams(dimension);
                });
            } catch (Exception e) {
                LoggingManager.LogExceptionToNoteOverlay(context, e);
            }
        });

        IMAGE_POOL.execute(() -> {
            try {
                final Bitmap avatar = item.getCacheAuthorAvatarBitmap(context);
                if (avatar != null) {
                    holder.wholeView.post(() -> {
                        if (item.getTemplateId().equals(holder.boundId)) holder.authorPreview.setImageBitmap(avatar);
                    });
                }
            } catch (Exception e) {
                LoggingManager.LogExceptionToNoteOverlay(context, e);
            }
        });

        holder.wholeView.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) onClick.onTemplateClick(items, pos);
        });
        holder.wholeView.setOnLongClickListener(v -> true);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView templateTitle;
        ImageView templatePreview;
        TextView authorTitle;
        ImageView authorPreview;
        TextView viewText, heartText, useText;
        View wholeView;
        /** The template this row currently shows (images arrive late; see onBindViewHolder). */
        String boundId;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            wholeView = itemView;

            templateTitle = itemView.findViewById(R.id.titleText);
            templatePreview = itemView.findViewById(R.id.previewImage);
            authorTitle = itemView.findViewById(R.id.authorText);
            authorPreview = itemView.findViewById(R.id.authorAvatar);

            viewText = itemView.findViewById(R.id.viewCount);
            heartText = itemView.findViewById(R.id.heartCount);
            useText = itemView.findViewById(R.id.useCount);
        }
    }
}
