package com.vanvatcorporation.doubleclips.assets;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.drivekit.CommunityAsset;

import java.util.ArrayList;
import java.util.List;

/** The grid of asset cards, shared by the Assets tab, its search results and the editor's Assets sheet. */
public final class AssetsGridAdapter extends RecyclerView.Adapter<AssetsGridAdapter.ViewHolder> {

    public interface OnAssetClick {
        void onAssetClick(CommunityAsset asset);
    }

    private static final int PREVIEW_WIDTH = 480;

    private final Context context;
    private final OnAssetClick onClick;
    private final List<CommunityAsset> items = new ArrayList<>();

    public AssetsGridAdapter(Context context, OnAssetClick onClick) {
        this.context = context;
        this.onClick = onClick;
    }

    public void setItems(List<CommunityAsset> shown) {
        items.clear();
        items.addAll(shown);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(context).inflate(R.layout.cpn_asset_element, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final CommunityAsset a = items.get(position);
        holder.title.setText(a.title);
        holder.owner.setText(a.ownerName == null || a.ownerName.isEmpty() ? "" : "@" + a.ownerName);
        holder.meta.setText(AssetUi.singular(context, a.kind) + " \u00b7 " + AssetUi.size(a.size) + " \u00b7 \u2193 " + AssetUi.count(a.downloads));
        holder.kindIcon.setImageResource(AssetUi.icon(a.kind));
        AssetImages.load(holder.preview, a.previewUrl, PREVIEW_WIDTH);
        holder.whole.setOnClickListener(v -> onClick.onAssetClick(a));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final View whole;
        final TextView title, owner, meta;
        final ImageView preview, kindIcon;

        ViewHolder(@NonNull View view) {
            super(view);
            whole = view.findViewById(R.id.wholeView);
            title = view.findViewById(R.id.titleText);
            owner = view.findViewById(R.id.ownerText);
            meta = view.findViewById(R.id.metaText);
            preview = view.findViewById(R.id.previewImage);
            kindIcon = view.findViewById(R.id.kindIcon);
        }
    }
}
