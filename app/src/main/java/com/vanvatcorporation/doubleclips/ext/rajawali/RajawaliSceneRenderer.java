package com.vanvatcorporation.doubleclips.ext.rajawali;

import android.content.Context;
import android.graphics.Color;
import android.view.MotionEvent;

import org.rajawali3d.renderer.Renderer;
import org.rajawali3d.math.vector.Vector3;
import org.rajawali3d.lights.DirectionalLight;
import org.rajawali3d.materials.Material;

public class RajawaliSceneRenderer extends Renderer {

    private String sceneConfigJson;

    public RajawaliSceneRenderer(Context context, String sceneConfigJson) {
        super(context);
        this.sceneConfigJson = sceneConfigJson;
        setFrameRate(30);
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
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void seekTo(float playheadTime) {
        // Unfortunately standard Rajawali animations are based on elapsed time automatically.
        // For accurate video editing previews, we need to manually update scenes or set animation time.
        // Since playheadTime changes, we could potentially update states here if needed.
    }

    @Override
    public void onOffsetsChanged(float v, float v1, float v2, float v3, int i, int i1) { }

    @Override
    public void onTouchEvent(MotionEvent motionEvent) { }
}
