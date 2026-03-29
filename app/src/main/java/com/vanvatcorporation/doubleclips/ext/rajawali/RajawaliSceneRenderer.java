package com.vanvatcorporation.doubleclips.ext.rajawali;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.opengl.GLES20;
import android.view.MotionEvent;

import org.rajawali3d.renderer.Renderer;
import org.rajawali3d.math.vector.Vector3;
import org.rajawali3d.lights.DirectionalLight;
import org.rajawali3d.materials.Material;
import org.rajawali3d.materials.textures.ATexture;
import org.rajawali3d.materials.textures.Texture;

import java.nio.IntBuffer;

import javax.microedition.khronos.opengles.GL10;

public class RajawaliSceneRenderer extends Renderer {

    private String sceneConfigJson;

    public interface ExportCallback {
        void onFrameRendered(Bitmap bitmap);
    }

    private ExportCallback exportCallback;
    private boolean isExporting = false;
    private int exportWidth, exportHeight;

    private Bitmap textureBitmapToUpdate = null;
    private boolean textureNeedsUpdate = false;
    private Texture dynamicTexture;

    public RajawaliSceneRenderer(Context context, String sceneConfigJson) {
        super(context);
        this.sceneConfigJson = sceneConfigJson;
        setFrameRate(30);
    }

    public void setExportCallback(int width, int height, ExportCallback callback) {
        this.exportCallback = callback;
        this.exportWidth = width;
        this.exportHeight = height;
        this.isExporting = true;
    }

    public void updateTextureBitmap(Bitmap bitmap) {
        this.textureBitmapToUpdate = bitmap;
        this.textureNeedsUpdate = true;
    }

    @Override
    protected void initScene() {
        // Set transparent background
        getCurrentScene().setBackgroundColor(Color.TRANSPARENT);

        // Add a default light
        DirectionalLight light = new DirectionalLight(1, 0.2, -1);
        light.setPower(1.0f);
        getCurrentScene().addLight(light);

        // Parse JSON and build effect
        if (sceneConfigJson != null && !sceneConfigJson.isEmpty() && !sceneConfigJson.equals("{}")) {
            try {
                RajawaliRendering.buildEffect(sceneConfigJson, this);

                // Note: To support dynamically mapping clips, RajawaliRendering should 
                // ideally return the main Object3D or we can find it by name.
                // For now, if we update a texture, we'll try to find the first Material to inject.
                // Or if dynamicTexture is setup here, we can replace it.
                dynamicTexture = new Texture("dynamicVideoTexture");
                // The actual mapping needs to be attached to the configured object.

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onRenderFrame(GL10 glUnused) {
        
        if (textureNeedsUpdate && textureBitmapToUpdate != null) {
            try {
                // If the dynamic texture is added to a material, we replace its bitmap
                if(dynamicTexture != null && dynamicTexture.getTextureId() != 0) {
                    // Update texture logic here in the future
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            textureNeedsUpdate = false;
        }

        super.onRenderFrame(glUnused);

        if (isExporting && exportCallback != null) {
            Bitmap bitmap = createBitmapFromGLSurface(exportWidth, exportHeight);
            exportCallback.onFrameRendered(bitmap);
        }
    }


    private Bitmap createBitmapFromGLSurface(int w, int h) {
        int bitmapBuffer[] = new int[w * h];
        int bitmapSource[] = new int[w * h];
        IntBuffer intBuffer = IntBuffer.wrap(bitmapBuffer);
        intBuffer.position(0);

        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, intBuffer);

        for (int i = 0; i < h; i++) {
            int offset1 = i * w;
            int offset2 = (h - i - 1) * w;
            for (int j = 0; j < w; j++) {
                int texturePixel = bitmapBuffer[offset1 + j];
                int blue = (texturePixel >> 16) & 0xff;
                int red = (texturePixel << 16) & 0x00ff0000;
                int pixel = (texturePixel & 0xff00ff00) | red | blue;
                bitmapSource[offset2 + j] = pixel;
            }
        }
        return Bitmap.createBitmap(bitmapSource, w, h, Bitmap.Config.ARGB_8888);
    }

    public void seekTo(float playheadTime) {
        // Unfortunately standard Rajawali animations are based on elapsed time automatically.
        // For accurate video editing previews, we need to manually update scenes or set animation time.
    }

    @Override
    public void onOffsetsChanged(float v, float v1, float v2, float v3, int i, int i1) { }

    @Override
    public void onTouchEvent(MotionEvent motionEvent) { }
}
