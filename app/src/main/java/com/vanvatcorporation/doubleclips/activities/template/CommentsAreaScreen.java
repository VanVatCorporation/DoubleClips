package com.vanvatcorporation.doubleclips.activities.template;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.vanvatcorporation.doubleclips.FFmpegEdit;
import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.activities.EditingActivity;
import com.vanvatcorporation.doubleclips.activities.MainActivity;
import com.vanvatcorporation.doubleclips.activities.editing.BaseEditSpecificAreaScreen;
import com.vanvatcorporation.doubleclips.activities.main.MainAreaScreen;
import com.vanvatcorporation.doubleclips.activities.main.TemplateAreaScreen;
import com.vanvatcorporation.doubleclips.helper.DateHelper;
import com.vanvatcorporation.doubleclips.helper.IOHelper;
import com.vanvatcorporation.doubleclips.helper.IOImageHelper;
import com.vanvatcorporation.doubleclips.helper.ImageHelper;
import com.vanvatcorporation.doubleclips.helper.NumberHelper;
import com.vanvatcorporation.doubleclips.helper.StringFormatHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CommentsAreaScreen extends BaseEditSpecificAreaScreen {

    public RecyclerView commentsContainer;
    public CommentAdapter commentsAdapter;
    public EditText commentArea;
    public List<TemplateAreaScreen.TemplateData.TemplateComment> commentList;


    public CommentsAreaScreen(Context context) {
        super(context);
    }

    public CommentsAreaScreen(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CommentsAreaScreen(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public CommentsAreaScreen(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void init()
    {
        super.init();
        commentArea = findViewById(R.id.commentArea);

        commentsContainer = findViewById(R.id.commentContainer);


        commentsContainer.setLayoutManager(new LinearLayoutManager(getContext()));


        commentList = new ArrayList<>();
        commentsAdapter = new CommentAdapter(getContext(), commentList);
        commentsContainer.setAdapter(commentsAdapter);


        onClose.add(() -> {
            commentArea.clearFocus();
        });

        animationScreen = AnimationScreen.ToTop;
    }




    public void fetchComments(TemplateAreaScreen.TemplateData data) {

        // TODO: Fetch {$data} comments from server.

        TemplateAreaScreen.TemplateData.TemplateComment[] serverData = new TemplateAreaScreen.TemplateData.TemplateComment[10];
        for (int i = 0; i < serverData.length; i++) {
            serverData[i] = new TemplateAreaScreen.TemplateData.TemplateComment();
            serverData[i].username = "viet2007ht";
            serverData[i].content = "Lorem itsumef dfskdjfnksjdgnksdjfksjfksdjajknfksdjfnksjdnfksdn\n\n\n\n\n\nfkjsndfkjsdnfksjdnfksjdnfksjdnfksjdnfksjdfnjk";
            serverData[i].commentTimestamp = System.currentTimeMillis();
            serverData[i].heartCount = 15000;

            TemplateAreaScreen.TemplateData.TemplateComment comment = serverData[i];
            commentsContainer.post(() -> {
                addComment(comment);
            });

        }


    }

    private void addComment(TemplateAreaScreen.TemplateData.TemplateComment serverDatum) {
        commentsAdapter.commentList.add(serverDatum);
        commentsAdapter.notifyItemInserted(commentsAdapter.commentList.size() - 1);
//        commentsContainer.scrollToPosition(adapter.commentList.size() - 1);

    }


    public class CommentAdapter extends RecyclerView.Adapter<CommentViewHolder>
    {

        private List<TemplateAreaScreen.TemplateData.TemplateComment> commentList;
        private Context context;

        // Constructor
        public CommentAdapter(Context context, List<TemplateAreaScreen.TemplateData.TemplateComment> commentList) {
            this.context = context;
            this.commentList = commentList;
        }
        @Override
        public CommentViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context).inflate(R.layout.cpn_comment_element, parent, false);
            return new CommentViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull CommentViewHolder holder, int position) {

            TemplateAreaScreen.TemplateData.TemplateComment commentItem = commentList.get(position);

            holder.commenterUsername.setText(commentItem.username);
            holder.commentDate.setText(DateHelper.convertTimestampToDateTimeStringFormat(commentItem.commentTimestamp));
            holder.commentContent.setText(commentItem.content);
            holder.heartCount.setText(NumberHelper.abbreviateNumber(commentItem.heartCount));

            holder.moreButton.setOnClickListener(v -> {

            });
            holder.heartButton.setOnClickListener(v -> {

            });
            holder.commenterAvatar.setOnClickListener(v -> {

            });
            holder.commenterUsername.setOnClickListener(v -> {

            });
            holder.commentDate.setOnClickListener(v -> {

            });
            holder.commentContent.setOnClickListener(v -> {

            });
            holder.heartCount.setOnClickListener(v -> {

            });



        }


        @Override
        public int getItemCount() {
            return commentList.size();
        }
    }
    public static class CommentViewHolder extends RecyclerView.ViewHolder {
        TextView commenterUsername, commentDate, commentContent, heartCount;
        ImageView commenterAvatar;
        ImageButton moreButton, heartButton;
        public CommentViewHolder(@NonNull View itemView) {
            super(itemView);

            commenterUsername = itemView.findViewById(R.id.commenterText);
            commentDate = itemView.findViewById(R.id.commentDate);
            commentContent = itemView.findViewById(R.id.commentContentText);
            commenterAvatar = itemView.findViewById(R.id.commenterAvatar);

            heartCount = itemView.findViewById(R.id.heartCount);

            moreButton = itemView.findViewById(R.id.moreButton);
            heartButton = itemView.findViewById(R.id.heartButton);
        }
    }


}
