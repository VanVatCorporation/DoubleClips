package com.vanvatcorporation.doubleclips.ext.rajawali;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RelativeLayout;

import androidx.annotation.Nullable;

import com.vanvatcorporation.doubleclips.R;
import com.vanvatcorporation.doubleclips.impl.AppCompatActivityImpl;
import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import org.rajawali3d.Object3D;
import org.rajawali3d.lights.DirectionalLight;
import org.rajawali3d.materials.Material;
import org.rajawali3d.materials.textures.ATexture;
import org.rajawali3d.materials.textures.Texture;
import org.rajawali3d.math.vector.Vector3;
import org.rajawali3d.primitives.Cube;
import org.rajawali3d.renderer.Renderer;
import org.rajawali3d.view.SurfaceView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.microedition.khronos.opengles.GL10;

public class RajawaliExample extends AppCompatActivityImpl {
    Button button;
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        RelativeLayout layout = new RelativeLayout(this);
        layout.setLayoutParams(new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));



        button = new Button(this);
        button.setText("Click Me");
        RelativeLayout.LayoutParams buttonParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        button.setOnClickListener(v -> {

            SurfaceView surface = new SurfaceView(this);
            surface.setFrameRate(60);
            surface.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
            CubeRenderer renderer = new CubeRenderer(this, 1920, 1080);
            surface.setSurfaceRenderer(renderer);

            layout.addView(surface, new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
        });
        buttonParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        layout.addView(button, buttonParams);

        setContentView(layout);
    }



    public class CubeRenderer extends Renderer {

        private Object3D cube;

        int width, height;

        public CubeRenderer(Context context, int width, int height) {
            super(context);
            setFrameRate(60);

            this.width = width;
            this.height = height;
        }

        @Override
        protected void initScene() {
            try {
                // Set up camera
                getCurrentCamera().setPosition(0, 0, 6);
                getCurrentCamera().setLookAt(0, 0, 0);

                // Create cube
                cube = new Cube(2); // 2 units wide

                Texture streamingTexture = new Texture("videoTexture", BitmapFactory.decodeResource(getResources(), R.drawable.logo));
                Material material = new Material();
                material.addTexture(streamingTexture);
                //material.setColorInfluence(0); // Use texture only
                cube.setMaterial(material);
                cube.setPosition(0, 0, 0);

                getCurrentScene().addChild(cube);

                // Add light
                DirectionalLight light = new DirectionalLight(1, 0.2, -1);
                light.setPower(0.15f);
                getCurrentScene().addLight(light);
            } catch (ATexture.TextureException e) {
                LoggingManager.LogToPersistentDataPath(this.getContext(), LoggingManager.getStackTraceFromException(e));
            }
        }
        float speed = 1;

        @Override
        protected void onRender(long elapsedTime, double deltaTime) {
            super.onRender(elapsedTime, deltaTime);
            long elapsedTimeInMs = elapsedTime / 1000000;
            cube.rotate(Vector3.Axis.Y, deltaTime * 12 * speed); // Rotate cube on Y-axis
            cube.rotate(Vector3.Axis.X, deltaTime * 2 * speed); // Rotate cube on X-axis
            cube.rotate(Vector3.Axis.Z, deltaTime * 1 * speed); // Rotate cube on X-axis
            cube.setPosition(0, 0, -(double) elapsedTimeInMs / 1000);
//            cube.moveRight((elapsedTime <= 8000000000L ? -deltaTime * 0.1 : -deltaTime * 0.125 * speed));
//            cube.moveUp((elapsedTime <= 12000000000L ? 0.001f : -deltaTime * 0.5 * speed));
//            cube.moveForward((elapsedTime <= 6000000000L ? 0.001f : -deltaTime * 0.75 * speed));
            Button button1 = RajawaliExample.this.button;
            if(elapsedTime > 5000000000L) {
                button1.setTextColor(0xFFFF0000);
                speed = 5;
            }
            button1.post(() -> button.setText(elapsedTime + " | " + deltaTime));
        }
        @Override
        public void onRenderFrame(GL10 glUnused) {
            super.onRenderFrame(glUnused);

            ByteBuffer pixelBuffer = ByteBuffer.allocateDirect(width * height * 4);
            pixelBuffer.order(ByteOrder.nativeOrder());

            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer);

            // Now pixelBuffer contains the image data
        }


        @Override
        public void onOffsetsChanged(float v, float v1, float v2, float v3, int i, int i1) {

        }

        @Override
        public void onTouchEvent(MotionEvent motionEvent) {

        }
    }

}
