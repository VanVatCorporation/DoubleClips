package com.vanvatcorporation.doubleclips.helper;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.annotation.RequiresApi;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;

import com.vanvatcorporation.doubleclips.impl.java.RunnableImpl;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import java.net.URLConnection;

@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class ImageHelper {
//
//
//    public static Bitmap getRoundedCornerBitmap(Bitmap bitmap, int pixels) {
//
//        BitmapShader shader;
//        shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
//
//        Paint paint = new Paint();
//        paint.setAntiAlias(true);
//        paint.setShader(shader);
//
//        RectF rect = new RectF(0.0f, 0.0f, width, height);
//
//// rect contains the bounds of the shape
//// radius is the radius in pixels of the rounded corners
//// paint contains the shader that will texture the shape
//        canvas.drawRoundRect(rect, radius, radius, paint);
//    }
//
//
//    public static Bitmap getRoundedCornerBitmap(Bitmap bitmap, int pixels) {
//        Bitmap output = Bitmap.createBitmap(bitmap.getWidth(), bitmap
//                .getHeight(), Bitmap.Config.ARGB_8888);
//        Canvas canvas = new Canvas(output);
//
//        final int color = 0xff424242;
//        final Paint paint = new Paint();
//        final Rect rect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
//        final RectF rectF = new RectF(rect);
//        final float roundPx = pixels;
//
//        paint.setAntiAlias(true);
//        canvas.drawARGB(0, 0, 0, 0);
//        paint.setColor(color);
//        canvas.drawRoundRect(rectF, roundPx, roundPx, paint);
//
//        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
//        canvas.drawBitmap(bitmap, rect, rect, paint);
//
//        return output;
//    }
    public static Bitmap createBitmapFromDrawable(Context context, int drawableResId) {
        Drawable drawable = ContextCompat.getDrawable(context, drawableResId);
        if (drawable == null) {
            return null; // Handle null drawable case
        }

        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);

        return bitmap;
    }
    public static Bitmap createBitmapFromDrawable(Drawable drawable) {
        if (drawable == null) {
            return null; // Handle null drawable case
        }

        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);

        return bitmap;
    }
    public static Drawable createDrawableFromBitmap(Resources resources, Bitmap bitmap) {
        return new BitmapDrawable(resources, bitmap);
    }
    @RequiresApi(Build.VERSION_CODES.M)
    public static Icon createIconFromDrawable(Drawable drawable) {
        if (drawable == null) {
            return null; // Handle null drawable case
        }

        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);

        return Icon.createWithBitmap(bitmap);
    }

    public static void getImageBitmapFromNetwork(Context context, String urlInput, ImageView imageDestination) {
        Handler handler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                URL url = new URL(urlInput);
                HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
                connection.setDoInput(true);
                connection.connect();
                InputStream input = connection.getInputStream();
                Bitmap bitmap = BitmapFactory.decodeStream(input);

                input.close();

                // Update the ImageView on the main thread
                handler.post(() -> imageDestination.setImageBitmap(bitmap));
            } catch (Exception e) {
                LoggingManager.LogExceptionToNoteOverlay(context, e);
            }
        }).start();

    }
    public static void getImageBitmapFromNetwork(Context context, String urlInput, RunnableImpl runnable) {
        Handler handler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                URL url = new URL(urlInput);
                HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
                connection.setDoInput(true);
                connection.connect();
                InputStream input = connection.getInputStream();
                Bitmap bitmap = BitmapFactory.decodeStream(input);

                input.close();

                // Update the ImageView on the main thread
                handler.post(() -> runnable.runWithParam(bitmap));
            } catch (Exception e) {
                LoggingManager.LogExceptionToNoteOverlay(context, e);
            }
        }).start();

    }

    public static View drawStrokeShape(View view, int backgroundColor, int strokeColor)
    {
        ShapeDrawable shapeDrawable = new ShapeDrawable(createRoundRectShape(view.getResources()));
        ShapeDrawable shapeDrawable2 = new ShapeDrawable(createRoundRectShape(view.getResources()));
        shapeDrawable2.getPaint().setStyle(Paint.Style.FILL);
        shapeDrawable2.getPaint().setColor(backgroundColor);
        shapeDrawable.getPaint().setStyle(Paint.Style.STROKE);
        shapeDrawable.getPaint().setStrokeWidth(10);
        shapeDrawable.getPaint().setColor(strokeColor);
        shapeDrawable.setPadding(10, 10, 10, 10);
        Drawable[] layers = {shapeDrawable2, shapeDrawable};
        LayerDrawable combinedDrawable = new LayerDrawable(layers);
        view.setBackground(combinedDrawable);
        return view;
    }
    public static View drawStrokeShapeUnderCurrentBackground(View view, int backgroundColor, int strokeColor)
    {
        ShapeDrawable shapeDrawable = new ShapeDrawable(createRoundRectShape(view.getResources()));
        ShapeDrawable shapeDrawable2 = new ShapeDrawable(createRoundRectShape(view.getResources()));
        shapeDrawable2.getPaint().setStyle(Paint.Style.FILL);
        shapeDrawable2.getPaint().setColor(backgroundColor);
        shapeDrawable.getPaint().setStyle(Paint.Style.STROKE);
        shapeDrawable.getPaint().setStrokeWidth(10);
        shapeDrawable.getPaint().setColor(strokeColor);
        shapeDrawable.setPadding(10, 10, 10, 10);
        Drawable[] layers = {shapeDrawable2, shapeDrawable, view.getBackground()};
        LayerDrawable combinedDrawable = new LayerDrawable(layers);
        view.setBackground(combinedDrawable);
        return view;
    }

    public static Bitmap RotateDrawable(Drawable drawable, float rotation)
    {
        if (drawable == null) {
            return null; // Handle null drawable case
        }
        Bitmap bmpOriginal = createBitmapFromDrawable(drawable);
        Bitmap bmResult = Bitmap.createBitmap(bmpOriginal.getWidth(), bmpOriginal.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas tempCanvas = new Canvas(bmResult);
        tempCanvas.rotate(rotation, (float) bmpOriginal.getWidth(), (float) bmpOriginal.getHeight());
        tempCanvas.drawBitmap(bmpOriginal, 0, 0, null);

        return bmResult;
    }

    public static RoundRectShape createRoundRectShape(Resources resources) {
        // Convert 16dp to pixels
        float dp = 12f;
        float radius = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                resources.getDisplayMetrics()
        );

        // Define radii for corners (8 values: [top-left, top-right, bottom-right, bottom-left])
        float[] outerRadii = new float[]{
                radius, radius,   // Top-left corner
                radius, radius,   // Top-right corner
                radius, radius,   // Bottom-right corner
                radius, radius    // Bottom-left corner
        };

        // No inner radii (optional)
        float[] innerRadii = null;

        // Define rectangle inset (optional)
        RectF inset = new RectF(0f, 0f, 0f, 0f);

        // Initialize the RoundRectShape
        return new RoundRectShape(outerRadii, inset, innerRadii);
    }










//    public static Drawable getActivityIcon(Context context, String packageName, @DrawableRes int defaultIconRes) {
//        PackageManager pm = context.getPackageManager();
//        Intent intent = ExtendedUnityPlayerActivity.GetIntentFromStringPackageWithExternalBase(context, pm, packageName);
//        //Intent intent = new Intent();
//        //intent.setComponent(new ComponentName(packageName, activityName));
//        if(intent != null)
//        {
//            ResolveInfo resolveInfo = pm.resolveActivity(intent, 0);
//
//            if (resolveInfo != null) {
//                return resolveInfo.loadIcon(pm);
//            }
//        }
//        return AppCompatResources.getDrawable(context, (defaultIconRes == 0) ? context.getApplicationInfo().icon : defaultIconRes);
//    }
    public static Drawable getActivityIcon(Context context, Class<?> activity) {
        try
        {
            PackageManager pm = context.getPackageManager();
            ComponentName componentName = new ComponentName(context, activity);
            return pm.getActivityIcon(componentName);
        }
        catch (Exception e)
        {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
        }
        return null;
    }
}
