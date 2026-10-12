package com.vanvatcorporation.doubleclips;

import android.opengl.GLES20;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Draws effect clips (adjustment layers) on the GPU. Used by BOTH the export (OpenGLEditNative.exportTimeline) and the
 * preview (PreviewEngine), so an effect looks the same in each. GL thread only.
 *
 * <p>An effect clip filters everything drawn BEFORE it (the tracks above it in the list), for as long as it lasts;
 * layers drawn after it sit on top, untouched. So {@link #apply} runs in the middle of a frame:
 * <ol>
 *   <li>copy what is on the main framebuffer so far into a texture (the "capture"),</li>
 *   <li>the style's <b>channels</b> (the "look", iOS {@code ClipCompositor.looked}): colour + transform in one pass,
 *       then the post pass (exposure, red tint, white mix, edge fill / slide, squeeze, motion blur), then the
 *       Gaussian blur,</li>
 *   <li>the style's <b>filters</b>, in order, each a full-screen pass (blur, colour controls, photo effects, sepia,
 *       vignette, bloom, pixelate, dot screen),</li>
 *   <li>write the result back over the main framebuffer (blended over the filter chain's input when the style mixes).</li>
 * </ol>
 * Every intermediate picture is canvas-sized with u to the right and v UP (canvas top = v 1), the same layout the
 * transition / blur layers use, so OpenGLEditNative.PostFxShader and GaussianBlurShader are reused as they are.
 * Everything time-dependent was evaluated on the CPU already ({@link OpenGLEdit.EffectCommand}); this class only draws.
 *
 * <p>If a shader fails to compile or link on some device the renderer switches itself off for the rest of the session
 * (the picture is then left untouched) instead of taking the export / preview down with it.
 */
public final class EffectLayerRenderer {

    private static final String TAG = "EffectLayerRenderer";

    // ---- shaders -----------------------------------------------------------------------------

    private static final String VS_FULL =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n";

    private static final String VS_LOOK =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "uniform mat4 uMvpMatrix;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uMvpMatrix * aPosition;\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n";

    private static final String FS_HEAD =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform sampler2D uTexture;\n";

    /** Same colour maths as the clip shaders (ImageTransformShader), so an effect's colour channels match a clip's. */
    private static final String FS_LOOK = FS_HEAD +
            "uniform float uOpacity;\n" +
            "uniform float uHueDegrees;\n" +
            "uniform float uSaturation;\n" +
            "uniform float uBrightness;\n" +
            "uniform float uContrast;\n" +
            "uniform float uTemperatureKelvin;\n" +
            "void main() {\n" +
            "    vec4 color = texture2D(uTexture, vTexCoord);\n" +
            "    vec3 rgb = color.rgb;\n" +
            "    float y = dot(rgb, vec3(0.299, 0.587, 0.114));\n" +
            "    float u = dot(rgb, vec3(-0.14713, -0.28886, 0.43600));\n" +
            "    float v = dot(rgb, vec3(0.61500, -0.51499, -0.10001));\n" +
            "    float hueRad = radians(uHueDegrees);\n" +
            "    float cosH = cos(hueRad);\n" +
            "    float sinH = sin(hueRad);\n" +
            "    float u2 = (u * cosH - v * sinH) * uSaturation;\n" +
            "    float v2 = (u * sinH + v * cosH) * uSaturation;\n" +
            "    float y2 = clamp((y - 0.5) * uContrast + 0.5 + uBrightness * 0.1, 0.0, 1.0);\n" +
            "    rgb = vec3(y2 + 1.13983 * v2, y2 - 0.39465 * u2 - 0.58060 * v2, y2 + 2.03211 * u2);\n" +
            "    float tempNorm = clamp((uTemperatureKelvin - 6500.0) / 6500.0, -1.0, 1.0);\n" +
            "    rgb.r *= (1.0 + tempNorm * 0.3);\n" +
            "    rgb.b *= (1.0 - tempNorm * 0.3);\n" +
            "    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), color.a * uOpacity);\n" +
            "}\n";

    private static final String FS_COPY = FS_HEAD +
            "uniform float uAlpha;\n" +
            "void main() {\n" +
            "    vec4 c = texture2D(uTexture, vTexCoord);\n" +
            "    gl_FragColor = vec4(c.rgb, c.a * uAlpha);\n" +
            "}\n";

    /** CIColorControls: saturation about the luma, contrast about mid grey, brightness added. */
    private static final String FS_COLOR = FS_HEAD +
            "uniform float uBrightness;\n" +
            "uniform float uContrast;\n" +
            "uniform float uSaturation;\n" +
            "void main() {\n" +
            "    vec4 c = texture2D(uTexture, vTexCoord);\n" +
            "    float l = dot(c.rgb, vec3(0.299, 0.587, 0.114));\n" +
            "    vec3 rgb = mix(vec3(l), c.rgb, uSaturation);\n" +
            "    rgb = (rgb - 0.5) * uContrast + 0.5 + uBrightness;\n" +
            "    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);\n" +
            "}\n";

    /**
     * CIPhotoEffect*: hand-tuned colour curves in the spirit of each Core Image preset (Core Image's exact tables are
     * not public). uMode: 0 noir, 1 chrome, 2 fade, 3 instant, 4 mono, 5 process, 6 tonal, 7 transfer.
     */
    private static final String FS_PHOTO = FS_HEAD +
            "uniform float uMode;\n" +
            "void main() {\n" +
            "    vec4 c = texture2D(uTexture, vTexCoord);\n" +
            "    float l = dot(c.rgb, vec3(0.299, 0.587, 0.114));\n" +
            "    vec3 rgb = c.rgb;\n" +
            "    if (uMode < 0.5) {\n" +
            "        float g = clamp((l - 0.5) * 1.55 + 0.46, 0.0, 1.0);\n" +
            "        g = g * g * (3.0 - 2.0 * g) * 0.35 + g * 0.65;\n" +
            "        rgb = vec3(g);\n" +
            "    } else if (uMode < 1.5) {\n" +
            "        rgb = mix(vec3(l), rgb, 1.2);\n" +
            "        rgb = (rgb - 0.5) * 1.12 + 0.5;\n" +
            "        rgb *= vec3(1.03, 1.0, 0.95);\n" +
            "    } else if (uMode < 2.5) {\n" +
            "        rgb = mix(vec3(l), rgb, 0.8);\n" +
            "        rgb = rgb * 0.82 + 0.1;\n" +
            "    } else if (uMode < 3.5) {\n" +
            "        rgb = mix(vec3(l), rgb, 1.1);\n" +
            "        rgb = rgb * vec3(1.06, 1.0, 0.86) + vec3(0.03, 0.02, 0.05);\n" +
            "        rgb = (rgb - 0.5) * 0.95 + 0.5;\n" +
            "    } else if (uMode < 4.5) {\n" +
            "        rgb = vec3(l * 0.92 + 0.04);\n" +
            "    } else if (uMode < 5.5) {\n" +
            "        rgb = mix(vec3(l), rgb, 1.15);\n" +
            "        rgb *= vec3(0.93, 1.0, 1.1);\n" +
            "        rgb = (rgb - 0.5) * 1.08 + 0.5;\n" +
            "    } else if (uMode < 6.5) {\n" +
            "        rgb = vec3(clamp((l - 0.5) * 0.9 + 0.5, 0.0, 1.0));\n" +
            "    } else {\n" +
            "        rgb = mix(vec3(l), rgb, 1.1);\n" +
            "        rgb *= vec3(1.1, 1.0, 0.88);\n" +
            "        rgb = (rgb - 0.5) * 1.05 + 0.5;\n" +
            "    }\n" +
            "    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), c.a);\n" +
            "}\n";

    private static final String FS_SEPIA = FS_HEAD +
            "uniform float uIntensity;\n" +
            "void main() {\n" +
            "    vec4 c = texture2D(uTexture, vTexCoord);\n" +
            "    vec3 s = vec3(dot(c.rgb, vec3(0.393, 0.769, 0.189)),\n" +
            "                  dot(c.rgb, vec3(0.349, 0.686, 0.168)),\n" +
            "                  dot(c.rgb, vec3(0.272, 0.534, 0.131)));\n" +
            "    gl_FragColor = vec4(mix(c.rgb, clamp(s, 0.0, 1.0), clamp(uIntensity, 0.0, 1.0)), c.a);\n" +
            "}\n";

    /** Darkens toward the corners. d = 0 at the centre, 1 in the corners (an ellipse on a non-square canvas, like CIVignette). */
    private static final String FS_VIGNETTE = FS_HEAD +
            "uniform float uIntensity;\n" +
            "uniform float uRadius;\n" +
            "void main() {\n" +
            "    vec4 c = texture2D(uTexture, vTexCoord);\n" +
            "    float d = length((vTexCoord - 0.5) * 2.0) * 0.7071;\n" +
            "    float k = d * (1.4 / max(uRadius, 0.05));\n" +
            "    float dark = smoothstep(0.35, 1.05, k);\n" +
            "    float f = 1.0 - clamp(uIntensity * dark * 0.8, 0.0, 1.0);\n" +
            "    gl_FragColor = vec4(c.rgb * f, c.a);\n" +
            "}\n";

    private static final String FS_PIXEL = FS_HEAD +
            "uniform vec2 uCell;\n" + // cell size in texture units
            "void main() {\n" +
            "    vec2 uv = (floor(vTexCoord / uCell) + 0.5) * uCell;\n" +
            "    gl_FragColor = texture2D(uTexture, min(uv, vec2(1.0)));\n" +
            "}\n";

    /** CIDotScreen: a rotated sine grid thresholded against the picture's brightness (black-and-white dots). */
    private static final String FS_DOT = FS_HEAD +
            "uniform vec2 uRes;\n" +
            "uniform vec2 uCenter;\n" +   // in texture units
            "uniform float uAngle;\n" +
            "uniform float uWidth;\n" +   // dot pitch in pixels
            "uniform float uSharpness;\n" +
            "void main() {\n" +
            "    vec4 c = texture2D(uTexture, vTexCoord);\n" +
            "    vec2 q = (vTexCoord - uCenter) * uRes;\n" +
            "    float s = sin(uAngle);\n" +
            "    float co = cos(uAngle);\n" +
            "    vec2 r = vec2(co * q.x + s * q.y, -s * q.x + co * q.y);\n" +
            "    float k = 3.14159265 / max(uWidth, 1.0);\n" +
            "    float pattern = sin(r.x * k) * sin(r.y * k) * 4.0;\n" +
            "    float avg = (c.r + c.g + c.b) / 3.0;\n" +
            "    float g = clamp((avg * 10.0 - 5.0 + pattern) * (0.3 + uSharpness * 2.0) + 0.5, 0.0, 1.0);\n" +
            "    gl_FragColor = vec4(vec3(g), c.a);\n" +
            "}\n";

    /** Bloom, step 1: the highlights (what glows). */
    private static final String FS_BRIGHT = FS_HEAD +
            "void main() {\n" +
            "    vec4 c = texture2D(uTexture, vTexCoord);\n" +
            "    gl_FragColor = vec4(max(c.rgb - 0.55, 0.0) * 2.2, 1.0);\n" +
            "}\n";

    /** Bloom, step 3: the picture plus its blurred highlights. */
    private static final String FS_ADD = FS_HEAD +
            "uniform sampler2D uTexture2;\n" +
            "uniform float uAmount;\n" +
            "void main() {\n" +
            "    vec4 s = texture2D(uTexture, vTexCoord);\n" +
            "    vec3 g = texture2D(uTexture2, vTexCoord).rgb;\n" +
            "    gl_FragColor = vec4(clamp(s.rgb + g * uAmount, 0.0, 1.0), s.a);\n" +
            "}\n";

    // photoEffect names -> FS_PHOTO's uMode
    private static final String[] PHOTO_NAMES = {"noir", "chrome", "fade", "instant", "mono", "process", "tonal", "transfer"};

    // ---- GL objects ---------------------------------------------------------------------------

    private static final class Prog {
        final int id, aPos, aTex;
        private final Map<String, Integer> locs = new HashMap<>();

        Prog(String vertex, String fragment) {
            id = link(vertex, fragment);
            aPos = GLES20.glGetAttribLocation(id, "aPosition");
            aTex = GLES20.glGetAttribLocation(id, "aTexCoord");
        }

        int loc(String name) {
            Integer l = locs.get(name);
            if (l == null) {
                l = GLES20.glGetUniformLocation(id, name);
                locs.put(name, l);
            }
            return l;
        }

        void use() { GLES20.glUseProgram(id); }
        void f(String name, float v) { GLES20.glUniform1f(loc(name), v); }
        void f2(String name, float a, float b) { GLES20.glUniform2f(loc(name), a, b); }
    }

    /** A canvas-sized RGBA texture that can be drawn into. */
    private static final class Target {
        final int framebuffer, texture, width, height;

        Target(int width, int height) {
            this.width = width;
            this.height = height;
            texture = newTexture(width, height, GLES20.GL_RGBA);
            int[] fb = new int[1];
            GLES20.glGenFramebuffers(1, fb, 0);
            framebuffer = fb[0];
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0);
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                throw new RuntimeException("Effect framebuffer incomplete: 0x" + Integer.toHexString(status));
            }
        }

        void bindCleared() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }

        void release() {
            GLES20.glDeleteFramebuffers(1, new int[]{framebuffer}, 0);
            GLES20.glDeleteTextures(1, new int[]{texture}, 0);
        }
    }

    private static final int POOL_SIZE = 4;

    private boolean ready, broken;
    private int width, height;
    private int captureTexture;
    private Target[] pool;

    private Prog look, copy, color, photo, sepia, vignette, pixel, dot, bright, add;
    private OpenGLEditNative.PostFxShader postShader;
    private OpenGLEditNative.GaussianBlurShader blurShader;
    private FloatBuffer fullQuad, lookQuad;

    // ---- public --------------------------------------------------------------------------------

    /**
     * Applies one effect clip to the picture on the currently bound main framebuffer (framebuffer 0, {@code width} x
     * {@code height}), in place. Leaves blending enabled (SRC_ALPHA, ONE_MINUS_SRC_ALPHA) and the main framebuffer
     * bound, as the frame loops expect.
     */
    public void apply(OpenGLEdit.EffectCommand cmd, int width, int height) {
        if (broken || cmd == null || cmd.isNoOp() || width <= 0 || height <= 0) return;
        try {
            prepare(width, height);
            draw(cmd);
        } catch (RuntimeException e) {
            broken = true;
            Log.w(TAG, "Effects are switched off for this session: " + e.getMessage());
        } finally {
            if (ready) restoreMainFramebuffer();
        }
    }

    /** Frees every GL object (GL thread, context current). Safe to call twice. */
    public void release() {
        if (pool != null) {
            for (Target t : pool) if (t != null) t.release();
            pool = null;
        }
        if (captureTexture != 0) {
            GLES20.glDeleteTextures(1, new int[]{captureTexture}, 0);
            captureTexture = 0;
        }
        ready = false;
    }

    // ---- setup ---------------------------------------------------------------------------------

    private void prepare(int w, int h) {
        if (look == null) {
            look = new Prog(VS_LOOK, FS_LOOK);
            copy = new Prog(VS_FULL, FS_COPY);
            color = new Prog(VS_FULL, FS_COLOR);
            photo = new Prog(VS_FULL, FS_PHOTO);
            sepia = new Prog(VS_FULL, FS_SEPIA);
            vignette = new Prog(VS_FULL, FS_VIGNETTE);
            pixel = new Prog(VS_FULL, FS_PIXEL);
            dot = new Prog(VS_FULL, FS_DOT);
            bright = new Prog(VS_FULL, FS_BRIGHT);
            add = new Prog(VS_FULL, FS_ADD);
            postShader = new OpenGLEditNative.PostFxShader();
            postShader.init();
            blurShader = new OpenGLEditNative.GaussianBlurShader();
            blurShader.init();
            // x, y, u, v. Full-screen: v up (canvas top = v 1), like every offscreen layer.
            fullQuad = quad(new float[]{
                    -1f, -1f, 0f, 0f,
                     1f, -1f, 1f, 0f,
                    -1f,  1f, 0f, 1f,
                     1f,  1f, 1f, 1f});
            // The look quad is placed by the clip-style mvp (quad y = -1 is the TOP edge), so its v runs the other way round.
            lookQuad = quad(new float[]{
                    -1f, -1f, 0f, 1f,
                     1f, -1f, 1f, 1f,
                    -1f,  1f, 0f, 0f,
                     1f,  1f, 1f, 0f});
        }
        if (!ready || w != width || h != height) {
            release();
            width = w;
            height = h;
            // RGB on purpose: glCopyTexSubImage2D may only copy components the framebuffer has, and an encoder
            // surface can be RGB. The picture so far is opaque anyway.
            captureTexture = newTexture(w, h, GLES20.GL_RGB);
            pool = new Target[POOL_SIZE];
            for (int i = 0; i < POOL_SIZE; i++) pool[i] = new Target(w, h);
            ready = true;
        }
    }

    // ---- one effect ----------------------------------------------------------------------------

    private void draw(OpenGLEdit.EffectCommand cmd) {
        GLES20.glDisable(GLES20.GL_BLEND);

        // 1. The picture so far.
        restoreMainFramebuffer();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, captureTexture);
        GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);

        // 2. The channels' look.
        int cur = captureTexture;
        if (cmd.look != null) cur = runLook(cmd, cur);
        int afterLook = cur;

        // 3. The filters, in order. afterLook stays untouched so a mixing style can blend with it at the end.
        for (OpenGLEdit.FilterStep step : cmd.steps) cur = runStep(step, cur, afterLook);

        // 4. Back onto the frame: the old picture is replaced (the result is canvas-sized; what a transform leaves empty
        //    is black, like iOS).
        restoreMainFramebuffer();
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        if (!cmd.steps.isEmpty() && cmd.mix < 0.9999f) {
            drawCopy(afterLook, 1f);
            drawCopy(cur, Math.max(cmd.mix, 0f));
        } else {
            drawCopy(cur, 1f);
        }
    }

    private int runLook(OpenGLEdit.EffectCommand cmd, int src) {
        int cur = src;
        if (cmd.lookPass) {
            Target dst = free(cur);
            dst.bindCleared();
            look.use();
            GLES20.glUniformMatrix4fv(look.loc("uMvpMatrix"), 1, false, cmd.lookMvp, 0);
            look.f("uOpacity", cmd.opacity);
            look.f("uHueDegrees", cmd.hue);
            look.f("uSaturation", cmd.saturation);
            look.f("uBrightness", cmd.brightness);
            look.f("uContrast", cmd.contrast);
            look.f("uTemperatureKelvin", cmd.temperatureKelvin);
            drawQuad(look, lookQuad, cur, 0);
            cur = dst.texture;
        }
        if (cmd.post != null) {
            Target dst = free(cur);
            dst.bindCleared();
            postShader.draw(cur, cmd.post, width, height);
            cur = dst.texture;
        }
        float sigma = cmd.blurWidthFraction * width;
        if (sigma > 0.25f) cur = blur(cur, sigma, -1);
        return cur;
    }

    /** Runs one filter op over {@code src}; returns the texture holding the result (src itself when the op does nothing). */
    private int runStep(OpenGLEdit.FilterStep step, int src, int keep) {
        switch (step.op) {
            case "blur": {
                float sigma = height * step.v("radius");
                return sigma > 0.1f ? blur(src, sigma, keep) : src;
            }
            case "colorControls": {
                Target dst = free(src, keep);
                dst.bindCleared();
                color.use();
                color.f("uBrightness", step.v("brightness"));
                color.f("uContrast", step.v("contrast"));
                color.f("uSaturation", step.v("saturation"));
                drawQuad(color, fullQuad, src, 0);
                return dst.texture;
            }
            case "photoEffect": {
                int mode = 0;
                for (int i = 0; i < PHOTO_NAMES.length; i++) if (PHOTO_NAMES[i].equals(step.name)) mode = i;
                Target dst = free(src, keep);
                dst.bindCleared();
                photo.use();
                photo.f("uMode", mode);
                drawQuad(photo, fullQuad, src, 0);
                return dst.texture;
            }
            case "sepia": {
                float intensity = step.v("intensity");
                if (intensity < 0.001f) return src;
                Target dst = free(src, keep);
                dst.bindCleared();
                sepia.use();
                sepia.f("uIntensity", intensity);
                drawQuad(sepia, fullQuad, src, 0);
                return dst.texture;
            }
            case "vignette": {
                float intensity = step.v("intensity");
                if (intensity < 0.001f) return src;
                Target dst = free(src, keep);
                dst.bindCleared();
                vignette.use();
                vignette.f("uIntensity", intensity);
                vignette.f("uRadius", step.v("radius"));
                drawQuad(vignette, fullQuad, src, 0);
                return dst.texture;
            }
            case "pixellate": {
                float scale = step.v("scale");
                if (scale < 0.0001f) return src;
                float cell = Math.max(2f, height * scale); // pixels
                Target dst = free(src, keep);
                dst.bindCleared();
                pixel.use();
                pixel.f2("uCell", cell / width, cell / height);
                drawQuad(pixel, fullQuad, src, 0);
                return dst.texture;
            }
            case "dotScreen": {
                float pitch = Math.max(3f, height * step.v("width"));
                Target dst = free(src, keep);
                dst.bindCleared();
                dot.use();
                dot.f2("uRes", width, height);
                dot.f2("uCenter", 0.5f, 0.5f);
                dot.f("uAngle", step.v("angle"));
                dot.f("uWidth", pitch);
                dot.f("uSharpness", step.v("sharpness"));
                drawQuad(dot, fullQuad, src, 0);
                return dst.texture;
            }
            case "bloom": {
                float amount = step.v("intensity");
                float sigma = height * step.v("radius") * 0.6f;
                if (amount < 0.001f || sigma < 0.1f) return src;
                Target highlights = free(src, keep);
                highlights.bindCleared();
                bright.use();
                drawQuad(bright, fullQuad, src, 0);
                int glow = blur(highlights.texture, sigma, keep, src);
                Target dst = free(src, glow, keep);
                dst.bindCleared();
                add.use();
                add.f("uAmount", amount * 1.2f);
                drawQuad(add, fullQuad, src, glow);
                return dst.texture;
            }
            default:
                return src; // an op this build doesn't draw (the export screen warns, see OpenGLEdit.SUPPORTED_EFFECT_OPS)
        }
    }

    /**
     * Gaussian blur (horizontal then vertical) of {@code src}; the result is in a texture other than {@code keep} /
     * {@code alsoKeep}. {@code src} itself may be overwritten unless it is one of those.
     */
    private int blur(int src, float sigma, int keep, int... alsoKeep) {
        int[] busy = new int[2 + alsoKeep.length];
        busy[0] = src;
        busy[1] = keep;
        System.arraycopy(alsoKeep, 0, busy, 2, alsoKeep.length);
        Target a = free(busy);
        a.bindCleared();
        blurShader.draw(src, 1f, 0f, width, height, sigma);

        busy[0] = a.texture; // src is free to be overwritten now, unless it was kept
        Target b = free(busy);
        b.bindCleared();
        blurShader.draw(a.texture, 0f, 1f, width, height, sigma);
        return b.texture;
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** The first pool target whose texture is not in {@code busy}. */
    private Target free(int... busy) {
        outer:
        for (Target t : pool) {
            for (int b : busy) if (b == t.texture) continue outer;
            return t;
        }
        throw new IllegalStateException("No free effect layer");
    }

    private void restoreMainFramebuffer() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, width, height);
    }

    private void drawCopy(int texture, float alpha) {
        copy.use();
        copy.f("uAlpha", alpha);
        drawQuad(copy, fullQuad, texture, 0);
    }

    /** Draws {@code quad} with {@code p} (already in use, uniforms set) sampling {@code tex0} (and {@code tex1} as uTexture2 when non-zero). */
    private void drawQuad(Prog p, FloatBuffer quad, int tex0, int tex1) {
        quad.position(0);
        GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(p.aPos);
        quad.position(2);
        GLES20.glVertexAttribPointer(p.aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(p.aTex);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex0);
        GLES20.glUniform1i(p.loc("uTexture"), 0);
        if (tex1 != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex1);
            GLES20.glUniform1i(p.loc("uTexture2"), 1);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(p.aPos);
        GLES20.glDisableVertexAttribArray(p.aTex);
    }

    private static FloatBuffer quad(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }

    private static int newTexture(int w, int h, int format) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, w, h, 0, format, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return tex[0];
    }

    private static int link(String vertexSrc, String fragmentSrc) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc);
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);
        int[] status = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(prog);
            GLES20.glDeleteProgram(prog);
            throw new RuntimeException("Effect shader link failed: " + log);
        }
        return prog;
    }

    private static int compile(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Effect shader compile failed: " + log);
        }
        return shader;
    }
}
