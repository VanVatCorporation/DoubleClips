package com.vanvatcorporation.doubleclips.ext.rajawali;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.view.animation.AccelerateDecelerateInterpolator;

import com.google.gson.Gson;

import org.rajawali3d.Object3D;
import org.rajawali3d.animation.RotateAnimation3D;
import org.rajawali3d.materials.Material;
import org.rajawali3d.materials.textures.ATexture;
import org.rajawali3d.materials.textures.Texture;
import org.rajawali3d.math.vector.Vector3;
import org.rajawali3d.primitives.Cube;
import org.rajawali3d.renderer.Renderer;

import java.util.List;

public class RajawaliRendering {


    public static Bitmap loadFrame(int frameIndex) {
        String path = "/storage/emulated/0/frames/frame_" + String.format("%04d", frameIndex) + ".png";
        return BitmapFactory.decodeFile(path);
    }
    public static void infuseFrameIntoObject(Object3D obj, Bitmap bitmap) throws ATexture.TextureException {
        Texture streamingTexture = new Texture("videoTexture", bitmap);
        Material material = new Material();
        material.addTexture(streamingTexture);
        material.setColorInfluence(0); // Use texture only
        obj.setMaterial(material);

    }

    /*
    @Override
protected void onRender(long elapsedTime, double deltaTime) {
    super.onRender(elapsedTime, deltaTime);

    int frameIndex = (int)(elapsedTime / (1000 / 30)); // 30 FPS
    Bitmap frameBitmap = BitmapFactory.decodeFile("/storage/emulated/0/frames/frame_" + String.format("%04d", frameIndex) + ".png");

    if (frameBitmap != null) {
        Texture newTexture = new Texture("frameTexture", frameBitmap);
        cube.getMaterial().removeTextureByName("frameTexture");
        cube.getMaterial().addTexture(newTexture);
    }

    cube.rotate(Vector3.Axis.Y, 1.0); // Optional rotation
}

    * */

    public static void buildEffect(String jsonString, Renderer renderer)
    {
        Gson gson = new Gson();
        SceneConfig config = gson.fromJson(jsonString, SceneConfig.class);


        // Set up camera
        List<Double> camPos = config.camera.position;
        renderer.getCurrentCamera().setPosition(camPos.get(0), camPos.get(1), camPos.get(2));

        List<Double> camLook = config.camera.lookAt;
        renderer.getCurrentCamera().setLookAt(camLook.get(0), camLook.get(1), camLook.get(2));

// Loop through objects
        for (ObjectConfig obj : config.objects) {
            if ("cube".equals(obj.type)) {
                Cube cube = new Cube((float) obj.size);

                // Material
                Material material = new Material();
                material.setColor(Color.parseColor(obj.material.color));
                //material.setTransparent(obj.material.transparent);
                cube.setMaterial(material);
                cube.setAlpha((float) obj.material.alpha);

                // Position
                cube.setPosition(obj.position.get(0), obj.position.get(1), obj.position.get(2));
                renderer.getCurrentScene().addChild(cube);

                // Animations
                for (AnimationConfig anim : obj.animations) {
                    if ("rotate".equals(anim.type)) {
//                        RotateAnimation3D rotate = new RotateAnimation3D(
//                                Vector3.Axis.valueOf(anim.axis),
//                                anim.angle
//                        );
                        RotateAnimation3D rotate = new RotateAnimation3D(
                                0, 0, 0
                        );
                        rotate.setDurationMilliseconds(anim.duration);
                        rotate.setTransformable3D(cube);

                        // Easing
                        if ("accelerateDecelerate".equals(anim.easing)) {
                            rotate.setInterpolator(new AccelerateDecelerateInterpolator());
                        }

                        renderer.getCurrentScene().registerAnimation(rotate);
                        rotate.play();
                    }
                }
            }
        }

    }













    public class SceneConfig {
        public CameraConfig camera;
        public List<ObjectConfig> objects;
    }

    public class CameraConfig {
        public List<Double> position;
        public List<Double> lookAt;
    }

    public class ObjectConfig {
        public String type;
        public double size;
        public List<Double> position;
        public MaterialConfig material;
        public List<AnimationConfig> animations;
    }

    public class MaterialConfig {
        public String color;
        public boolean transparent;
        public double alpha;
    }

    public class AnimationConfig {
        public String type;
        public String axis;
        public double angle;
        public int duration;
        public String easing;
    }

}
