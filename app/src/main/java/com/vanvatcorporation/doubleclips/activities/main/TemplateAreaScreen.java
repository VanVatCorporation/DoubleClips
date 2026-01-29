package com.vanvatcorporation.doubleclips.activities.main;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.gson.Gson;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.TemplatePreviewActivity;
import com.vanvatcorporation.doubleclips.externalUtils.Random;
import com.vanvatcorporation.doubleclips.helper.AlgorithmHelper;
import com.vanvatcorporation.doubleclips.helper.ImageHelper;
import com.vanvatcorporation.doubleclips.helper.NumberHelper;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

public class TemplateAreaScreen extends BaseAreaScreen {
    public List<TemplateData> templateList;
    public RecyclerView templateRecyclerView;
    public TemplateDataAdapter templateAdapter;
    public SwipeRefreshLayout templateSwipeRefreshLayout;



    public TemplateAreaScreen(Context context) {
        super(context);
    }

    public TemplateAreaScreen(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TemplateAreaScreen(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public TemplateAreaScreen(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }


    @Override
    public void init() {
        super.init();

        templateRecyclerView = findViewById(R.id.templateRecyclerView);
        templateSwipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);




        StaggeredGridLayoutManager layoutManager = new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        layoutManager.setGapStrategy(StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS);
        templateRecyclerView.setLayoutManager(layoutManager);


        templateList = new ArrayList<>();
        templateAdapter = new TemplateDataAdapter(getContext(), templateList);
        templateRecyclerView.setAdapter(templateAdapter);

        templateSwipeRefreshLayout.setOnRefreshListener(this::reloadingProject);


        templateSwipeRefreshLayout.setRefreshing(true);
        reloadingProject();
    }



    public void addTemplate(TemplateData data)
    {
        templateList.add(data);
        templateAdapter.notifyItemInserted(templateList.size() - 1);
    }

    public void fetchTemplate() {
        ExecutorService executorService = Executors.newSingleThreadExecutor();

        executorService.execute(() -> {


            try {

                URL url = new URL("https://app.vanvatcorp.com/doubleclips/api/fetch-templates");
                HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
                connection.setRequestMethod("GET");

                // Read the response
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();



                TemplateData[] serverData = new Gson().newBuilder().create().fromJson(response.toString(), TemplateData[].class);



                templateSwipeRefreshLayout.post(() -> {
                    templateAdapter.notifyDataSetChanged();
                    for (TemplateData data : serverData) {
                        addTemplate(data);
                    }
                    templateSwipeRefreshLayout.setRefreshing(false);
                });
            }
            catch (Exception e)
            {
                LoggingManager.LogExceptionToNoteOverlay(getContext(), e);

                templateSwipeRefreshLayout.post(() -> {
                    templateSwipeRefreshLayout.setRefreshing(false);
                });
            }
        });

    }

    public void reloadingProject()
    {
        templateList.clear();
        templateAdapter.notifyDataSetChanged();
        fetchTemplate();


//        projectList.clear();
//        projectAdapter.notifyDataSetChanged();
//        String projectsFolderPath = IOHelper.CombinePath(IOHelper.getPersistentDataPath(getContext()), "projects");
//        File file = new File(projectsFolderPath);
//        if(file.listFiles() == null) {
//            projectSwipeRefreshLayout.setRefreshing(false);
//            return;
//        }
//        for (File directory : Objects.requireNonNull(file.listFiles())) {
//            if(directory.isDirectory())
//            {
//                TemplateData data = TemplateData.loadProperties(getContext(), directory.getAbsolutePath());
//
//                if(data != null)
//                {
//                    projectList.add(data);
//                    projectAdapter.notifyItemInserted(projectList.size() - 1);
//                }
//            }
//
//        }
//
    }










    public static class TemplateData implements Serializable {

        public String version;
        private String templateAuthor;
        private String templateId;
        private String templateTitle;
        private String templateDescription;
        private String ffmpegCommand;
        private String templateSnapshotLink;
        private String templateVideoLink;
        private long templateTimestamp;
        private long templateDuration;
        private int templateTotalClip;
        private String[] additionalResourceName;
        private int viewCount;
        private int useCount;
        private int heartCount;
        private TemplateComment[] comments = new TemplateComment[0];
        private int bookmarkCount;

        public TemplateData(String templateAuthor, String templateId, String templateTitle, String templateDescription, String ffmpegCommand, String templateSnapshotLink, String templateVideoLink, long templateTimestamp, long templateDuration, int templateTotalClip, String[] additionalResourceName) {
            this.templateAuthor = templateAuthor;
            this.templateId = templateId;
            this.templateTitle = templateTitle;
            this.templateDescription = templateDescription;
            this.ffmpegCommand = ffmpegCommand;
            this.templateSnapshotLink = templateSnapshotLink;
            this.templateVideoLink = templateVideoLink;
            this.templateTimestamp = templateTimestamp;
            this.templateDuration = templateDuration;
            this.templateTotalClip = templateTotalClip;
            this.additionalResourceName = additionalResourceName;
        }


        public String getTemplateAuthor() {
            return templateAuthor;
        }
        public String getTemplateId() {
            return templateId;
        }
        public String getTemplateTitle() {
            return templateTitle;
        }
        public String getTemplateDescription() {
            return templateDescription;
        }
        public String getFfmpegCommand() {
            return ffmpegCommand;
        }
        public String getTemplateSnapshotLink() {
            return templateSnapshotLink;
        }
        public String getTemplateVideoLink() {
            return templateVideoLink;
        }
        public long getTemplateTimestamp() {
            return templateTimestamp;
        }
        public long getTemplateDuration() {
            return templateDuration;
        }
        public int getTemplateClipCount() {
            return templateTotalClip;
        }
        public String[] getTemplateAdditionalResourcesName() {
            return additionalResourceName;
        }

        public int getViewCount() {
            return viewCount;
        }
        public int getUseCount() {
            return useCount;
        }
        public int getHeartCount() {
            return heartCount;
        }
        public TemplateComment[] getComments() {
            return comments;
        }
        public int getBookmarkCount() {
            return bookmarkCount;
        }


        public String getTemplateLocation() {
            return "/" + templateAuthor + "/" + templateId;
        }


        public void setTemplateDuration(long amount) {
            templateDuration = amount;
        }
        public void setFfmpegCommand(String cmd) {
            ffmpegCommand = cmd;
        }








        public static class TemplateComment {
            public int heartCount;
            TemplateComment[] replies;

            public TemplateComment() {

            }
        }

    }
    public class TemplateDataAdapter extends RecyclerView.Adapter<TemplateDataViewHolder>
    {

        private List<TemplateData> templateList;
        private Context context;

        // Constructor
        public TemplateDataAdapter(Context context, List<TemplateData> templateList) {
            this.context = context;
            this.templateList = templateList;
        }
        @Override
        public TemplateDataViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context).inflate(R.layout.cpn_template_element, parent, false);
            return new TemplateDataViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull TemplateDataViewHolder holder, int position) {

            TemplateData projectItem = templateList.get(position);

            holder.templateTitle.setText(projectItem.getTemplateTitle());
            holder.templateTitle.setOnClickListener(v -> {
                holder.wholeView.performClick();
            });

            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    Bitmap thumbnailBitmap = ImageHelper.getImageBitmapFromNetwork(context, projectItem.getTemplateSnapshotLink());

                    if(thumbnailBitmap != null)
                    {
                        holder.wholeView.post(() -> {
                            holder.templatePreview.setImageBitmap(thumbnailBitmap);
                            int targetWidth = holder.wholeView.getWidth();

                            int imageWidth = thumbnailBitmap.getWidth();
                            int imageHeight = thumbnailBitmap.getHeight();

                            int[] res = AlgorithmHelper.scaleByWidth(targetWidth, imageWidth, imageHeight);

                            // enforce 9:16 ratio cap
                            int maxHeight = (res[0] * 16) / 9;
                            if (res[1] > maxHeight) {
                                res[1] = maxHeight;
                            }

                            ViewGroup.LayoutParams imageDimension = holder.templatePreview.getLayoutParams();
                            imageDimension.width = res[0];
                            imageDimension.height = res[1];
                            holder.templatePreview.setLayoutParams(imageDimension);
                        });
                    }
                } catch (Exception e) {
                    LoggingManager.LogExceptionToNoteOverlay(context, e);
                }
            });


            holder.authorTitle.setText("@viet2007ht");
            holder.authorTitle.setOnClickListener(v -> {
                // TODO: Author profile destination.
//                holder.wholeView.performClick();
            });

            holder.viewText.setText(NumberHelper.abbreviateNumber(projectItem.getViewCount()));
            holder.heartText.setText(NumberHelper.abbreviateNumber(projectItem.getHeartCount()));
            holder.useText.setText(NumberHelper.abbreviateNumber(projectItem.getUseCount()));



            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    Bitmap avatarBitmap = ImageHelper.getImageBitmapFromNetwork(getContext(), "https://account.vanvatcorp.com/viet2007ht/avatar.png");

                    if(avatarBitmap != null)
                    {
                        holder.wholeView.post(() -> holder.authorPreview.setImageBitmap(avatarBitmap));
                    }
                } catch (Exception e) {
                    LoggingManager.LogExceptionToNoteOverlay(context, e);
                }
            });


            holder.wholeView.setOnClickListener(v -> {
                @SuppressLint("UnsafeOptInUsageError") Intent intent = new Intent(context, TemplatePreviewActivity.class);
                intent.putExtra("TemplateData", projectItem);
                context.startActivity(intent);
            });
            holder.wholeView.setOnLongClickListener(v -> {
                return true;
            });
        }


        @Override
        public int getItemCount() {
            return templateList.size();
        }
    }
    public static class TemplateDataViewHolder extends RecyclerView.ViewHolder {
        TextView templateTitle;
        ImageView templatePreview;
        TextView authorTitle;
        ImageView authorPreview;
        TextView viewText, heartText, useText;
        View wholeView;
        public TemplateDataViewHolder(@NonNull View itemView) {
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
