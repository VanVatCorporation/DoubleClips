package com.vanvatcorporation.doubleclips;

import android.opengl.GLES20;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Blends two canvas-sized pictures through a transition mask (data-driven {@code "mode": "mask"} styles: wipes, circles,
 * slices, clock, dissolve): B shows where the mask is white, A where it is black. Same maths as iOS
 * {@code ClipCompositor.transitionMask} + {@code masked}, computed per pixel in the fragment shader (Core Image builds
 * a mask picture; here the mask value is a formula). Both inputs are offscreen layers (v up, canvas top = v 1), the
 * same ones the built-in blend shader reads. GL thread only; used by the export and the preview.
 *
 * <p>Like {@link EffectLayerRenderer}: if the shader can't be built on a device it switches itself off (the mask
 * transition then draws nothing for its window) instead of taking the export / preview down.
 */
public final class TransitionMaskRenderer {

    private static final String TAG = "TransitionMaskRenderer";

    private static final String VS =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n";

    /** uShape: 0 wipe, 1 circle, 2 noise, 3 slices, 4 clock. All lengths in pixels, y UP (like Core Image). */
    private static final String FS =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform sampler2D uTextureA;\n" +
            "uniform sampler2D uTextureB;\n" +
            "uniform vec2 uRes;\n" +
            "uniform float uShape;\n" +
            "uniform float uReveal;\n" +
            "uniform float uAngle;\n" +    // radians (wipe, slices)
            "uniform float uSoft;\n" +
            "uniform vec2 uCenter;\n" +    // circle centre, 0..1 from the left / TOP
            "uniform float uGrain;\n" +
            "uniform float uCount;\n" +
            "uniform float uStart;\n" +    // clock, radians clockwise from 12 o'clock
            "uniform float uFlag;\n" +     // wipe: normalized, circle: invert
            "float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }\n" +
            "float maskValue(vec2 p) {\n" +
            "    float r = uReveal;\n" +
            "    float w = uRes.x;\n" +
            "    float h = uRes.y;\n" +
            "    vec2 c = uRes * 0.5;\n" +
            "    float fallback = r > 0.5 ? 1.0 : 0.0;\n" +
            "    if (uShape < 0.5) {\n" +           // wipe
            "        vec2 d = vec2(cos(uAngle), sin(uAngle));\n" +
            "        vec2 g = uFlag > 0.5 ? d / uRes : d;\n" +
            "        float denom = uFlag > 0.5 ? abs(d.x) + abs(d.y) : abs(w * d.x) + abs(h * d.y);\n" +
            "        float g2 = dot(g, g);\n" +
            "        if (denom <= 0.0 || g2 <= 0.0) return fallback;\n" +
            "        float halfSoft = max(uSoft * 0.5, 0.5 * sqrt(g2) / denom);\n" +
            "        float edge = -0.5 + r;\n" +
            "        float s = dot(p - c, g) / denom;\n" +
            "        return clamp((edge + halfSoft - s) / (2.0 * halfSoft), 0.0, 1.0);\n" +
            "    } else if (uShape < 1.5) {\n" +    // circle
            "        vec2 center = vec2(w * uCenter.x, h * (1.0 - uCenter.y));\n" +
            "        vec2 far2 = max(center, uRes - center);\n" +
            "        float far = length(far2);\n" +
            "        float radius = far * (uFlag > 0.5 ? 1.0 - r : r);\n" +
            "        float soft = max(2.0, far * uSoft);\n" +
            "        float d = length(p - center);\n" +
            "        return uFlag > 0.5 ? clamp((d - radius) / soft, 0.0, 1.0) : clamp(1.0 - (d - radius) / soft, 0.0, 1.0);\n" +
            "    } else if (uShape < 2.5) {\n" +    // noise
            "        float grain = max(1.0, uGrain);\n" +
            "        float n = hash(floor(p / grain));\n" +
            "        float gain = 1.0 / max(uSoft, 0.001);\n" +
            "        return clamp(gain * (n - (1.0 - r)), 0.0, 1.0);\n" +
            "    } else if (uShape < 3.5) {\n" +    // slices
            "        float len = abs(w * cos(uAngle)) + abs(h * sin(uAngle));\n" +
            "        float count = max(1.0, floor(uCount + 0.5));\n" +
            "        float band = len / count;\n" +
            "        vec2 q = p - c;\n" +
            "        float u = q.x * cos(uAngle) + q.y * sin(uAngle) + len * 0.5;\n" +
            "        if (u < 0.0 || u >= len) return 0.0;\n" +
            "        float within = u - floor(u / band) * band;\n" +
            "        return clamp(band * r - within + 0.5, 0.0, 1.0);\n" +
            "    } else {\n" +                      // clock
            "        vec2 q = p - c;\n" +
            "        float startAngle = 1.5707963 - uStart;\n" +
            "        float delta = mod(startAngle - atan(q.y, q.x), 6.2831853);\n" +
            "        float aa = 1.0 / max(length(q), 1.0);\n" +
            "        return clamp((6.2831853 * r - delta) / aa + 0.5, 0.0, 1.0);\n" +
            "    }\n" +
            "}\n" +
            "void main() {\n" +
            "    vec4 a = texture2D(uTextureA, vTexCoord);\n" +
            "    vec4 b = texture2D(uTextureB, vTexCoord);\n" +
            "    float m = 0.0;\n" +
            "    if (uReveal >= 1.0) m = 1.0;\n" +
            "    else if (uReveal > 0.0) m = maskValue(vTexCoord * uRes);\n" +
            "    gl_FragColor = mix(a, b, m);\n" +
            "}\n";

    private boolean broken;
    private int program, aPos, aTex;
    private FloatBuffer quad;

    /** Draws the blend into the currently bound framebuffer (viewport already set), like TransitionBlendShader.draw. */
    public void draw(int textureA, int textureB, OpenGLEdit.MaskSpec mask, int width, int height) {
        if (broken || mask == null) return;
        try {
            if (program == 0) build();
            drawInternal(textureA, textureB, mask, width, height);
        } catch (RuntimeException e) {
            broken = true;
            Log.w(TAG, "Mask transitions are switched off for this session: " + e.getMessage());
        }
    }

    public void release() {
        if (program != 0) GLES20.glDeleteProgram(program);
        program = 0;
    }

    private void drawInternal(int texA, int texB, OpenGLEdit.MaskSpec m, int width, int height) {
        GLES20.glUseProgram(program);
        float shape;
        switch (m.shape) {
            case "circle": shape = 1f; break;
            case "noise": shape = 2f; break;
            case "slices": shape = 3f; break;
            case "clock": shape = 4f; break;
            default: shape = 0f; // wipe
        }
        GLES20.glUniform2f(loc("uRes"), width, height);
        GLES20.glUniform1f(loc("uShape"), shape);
        GLES20.glUniform1f(loc("uReveal"), m.reveal);
        GLES20.glUniform1f(loc("uAngle"), (float) Math.toRadians(m.angleDegrees));
        GLES20.glUniform1f(loc("uSoft"), m.softness);
        GLES20.glUniform2f(loc("uCenter"), m.cx, m.cy);
        GLES20.glUniform1f(loc("uGrain"), m.grain);
        GLES20.glUniform1f(loc("uCount"), m.count);
        GLES20.glUniform1f(loc("uStart"), (float) Math.toRadians(m.startDegrees));
        GLES20.glUniform1f(loc("uFlag"), (shape == 1f ? m.invert : m.normalized) ? 1f : 0f);

        quad.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aPos);
        quad.position(2);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texA);
        GLES20.glUniform1i(loc("uTextureA"), 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texB);
        GLES20.glUniform1i(loc("uTextureB"), 1);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aTex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    private int loc(String name) { return GLES20.glGetUniformLocation(program, name); }

    private void build() {
        int vs = compile(GLES20.GL_VERTEX_SHADER, VS);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FS);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] status = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new RuntimeException("Mask shader link failed: " + log);
        }
        program = p;
        aPos = GLES20.glGetAttribLocation(p, "aPosition");
        aTex = GLES20.glGetAttribLocation(p, "aTexCoord");
        float[] data = {-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f};
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder());
        quad = bb.asFloatBuffer();
        quad.put(data);
        quad.position(0);
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
            throw new RuntimeException("Mask shader compile failed: " + log);
        }
        return shader;
    }
}
