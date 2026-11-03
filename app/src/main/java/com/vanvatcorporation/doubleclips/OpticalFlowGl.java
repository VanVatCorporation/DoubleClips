package com.vanvatcorporation.doubleclips;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Optical flow and motion blur on the GPU (OpenGL ES 3.0): the picture BETWEEN two decoded frames of a clip (a slowed-down
 * clip's in-between frames), made by finding how things move from the first frame to the second and pulling both frames
 * along that motion, and the streak of motion blur along the same motion. The same three shaders and the same passes as
 * Desktop's OpticalFlowGl, ported from GLSL 330 to GLSL ES 3.00.
 *
 * <p>Per output picture, fragment passes only:
 * <ol>
 * <li>a motion pyramid, coarse to fine (one search pass per level): a symmetric block match at the new frame's own pixels,
 *     so there are no holes; the finest level gets one more pass in half pixels;</li>
 * <li>compose: frame A pulled forward and frame B pulled back by that motion, mixed, with a plain cross-fade where the match
 *     is bad; or</li>
 * <li>streak: the picture smeared along the motion ("Speed" passes, the last one mixes the sharp picture back in: Blend).</li>
 * </ol>
 * The two frames come in as mipmapped RGBA8 textures (see {@link #createFrameTexture}, {@link #blitFrame}) in the layout the
 * image shader samples (row 0 = top of the picture), and the result is such a texture too, so everything after it (mask,
 * chroma key, transform) treats it as any picture.
 *
 * <p>GL thread only. {@link #ready()} is false when this GPU or context can't do it (no ES 3.0, no half float render
 * targets, a shader that doesn't build): the caller then draws the plain frame.
 */
final class OpticalFlowGl {

    // Per pyramid level: search radius (candidates are start + step * (i, j), i and j from -radius to radius), step in that
    // level's pixels, block half size, block sampling stride. Tuned on Desktop against ground truth.
    private static final int[] COARSE = {8, 1, 4, 2}, MID = {2, 1, 3, 2}, FINE = {2, 1, 3, 2};
    // The extra pass on the finest level: half pixel steps around what it found.
    private static final int SUB_RADIUS = 1, SUB_WIN = 1, SUB_STRIDE = 1;
    private static final float SUB_STEP = 0.5f;
    private static final float BIAS = 0.002f;
    // Compose: the match cost relative to the block's contrast, and the jump of the motion between neighbours (flow pixels),
    // from "use the warped picture" (Lo) to "use the cross-fade" (Hi).
    private static final float COST_LO = 0.4f, COST_HI = 1.0f, JUMP_LO = 3.0f, JUMP_HI = 8.0f;

    /** The blur is applied at most this many times to a picture however many the user types (the model keeps the number). */
    static final int MAX_BLUR_PASSES = 24;
    private static final int MAX_BLUR_TAPS = 48;
    /** The scene cut check looks at both frames shrunk to this size. */
    private static final int CUT_W = 128, CUT_H = 72;

    private static final String FULLSCREEN_VERTEX =
            "#version 300 es\n" +
            "out vec2 vUV;\n" +
            "void main() {\n" +
            "    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n" +
            "    vUV = p;\n" +
            "    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n" +
            "}\n";

    private static final String FRAGMENT_HEADER =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "precision highp sampler2D;\n";

    /** One pyramid level: a symmetric block match (the Desktop opticalflow_search.frag). */
    private static final String SEARCH_FRAGMENT = FRAGMENT_HEADER +
            "uniform sampler2D uA;\n" +
            "uniform sampler2D uB;\n" +
            "uniform sampler2D uPrev;\n" +      // the coarser level's result (only read when uHasPrev == 1)
            "uniform int uLod;\n" +
            "uniform int uHasPrev;\n" +
            "uniform float uAlpha;\n" +
            "uniform int uRadius;\n" +
            "uniform float uStep;\n" +
            "uniform int uWin;\n" +
            "uniform int uStride;\n" +
            "uniform float uBias;\n" +
            "out vec4 outFlow;\n" +
            "const int MAX_SAMPLES = 64;\n" +
            "float diffs[MAX_SAMPLES];\n" +
            "float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }\n" +
            "void main() {\n" +
            "    vec2 size = vec2(textureSize(uA, uLod));\n" +
            "    vec2 p = gl_FragCoord.xy;\n" +
            "    vec2 start = vec2(0.0);\n" +
            "    if (uHasPrev == 1) {\n" +
            "        vec2 prevSize = vec2(textureSize(uPrev, 0));\n" +
            "        start = texture(uPrev, p / size).xy * (size / prevSize);\n" +
            "    }\n" +
            "    float lod = float(uLod);\n" +
            "    float best = 1e9;\n" +
            "    vec2 bestD = start;\n" +
            "    for (int j = -uRadius; j <= uRadius; j++) {\n" +
            "        for (int i = -uRadius; i <= uRadius; i++) {\n" +
            "            vec2 off = uStep * vec2(float(i), float(j));\n" +
            "            vec2 d = start + off;\n" +
            "            vec2 pa = p - uAlpha * d;\n" +
            "            vec2 pb = p + (1.0 - uAlpha) * d;\n" +
            "            int n = 0;\n" +
            "            for (int y = -uWin; y <= uWin; y += uStride) {\n" +
            "                for (int x = -uWin; x <= uWin; x += uStride) {\n" +
            "                    vec2 o = vec2(float(x), float(y));\n" +
            "                    float a = luma(textureLod(uA, (pa + o) / size, lod).rgb);\n" +
            "                    float b = luma(textureLod(uB, (pb + o) / size, lod).rgb);\n" +
            "                    if (n < MAX_SAMPLES) { diffs[n] = a - b; n++; }\n" +
            "                }\n" +
            "            }\n" +
            "            float mean = 0.0;\n" +
            "            for (int k = 0; k < n; k++) mean += diffs[k];\n" +
            "            mean /= float(n);\n" +
            "            float sad = 0.0;\n" +
            "            for (int k = 0; k < n; k++) sad += abs(diffs[k] - mean);\n" +
            "            float cost = sad / float(n) + uBias * length(off);\n" +
            "            if (cost < best) { best = cost; bestD = d; }\n" +
            "        }\n" +
            "    }\n" +
            "    vec2 pa0 = p - uAlpha * bestD;\n" +
            "    float sumA = 0.0;\n" +
            "    int m = 0;\n" +
            "    for (int y = -uWin; y <= uWin; y += uStride) {\n" +
            "        for (int x = -uWin; x <= uWin; x += uStride) {\n" +
            "            if (m < MAX_SAMPLES) {\n" +
            "                diffs[m] = luma(textureLod(uA, (pa0 + vec2(float(x), float(y))) / size, lod).rgb);\n" +
            "                sumA += diffs[m];\n" +
            "                m++;\n" +
            "            }\n" +
            "        }\n" +
            "    }\n" +
            "    float meanA = sumA / float(m);\n" +
            "    float contrast = 0.0;\n" +
            "    for (int k = 0; k < m; k++) contrast += abs(diffs[k] - meanA);\n" +
            "    outFlow = vec4(bestD, best, contrast / float(m));\n" +
            "}\n";

    /** The new frame: A pulled forward, B pulled back, mixed; a cross-fade where the match is bad (opticalflow_compose.frag). */
    private static final String COMPOSE_FRAGMENT = FRAGMENT_HEADER +
            "uniform sampler2D uA;\n" +
            "uniform sampler2D uB;\n" +
            "uniform sampler2D uFlow;\n" +
            "uniform float uAlpha;\n" +
            "uniform float uCostLo;\n" +
            "uniform float uCostHi;\n" +
            "uniform float uIncLo;\n" +
            "uniform float uIncHi;\n" +
            "in vec2 vUV;\n" +
            "out vec4 outColor;\n" +
            "void main() {\n" +
            "    vec4 f = texture(uFlow, vUV);\n" +
            "    vec2 duv = f.xy / vec2(textureSize(uFlow, 0));\n" +
            "    vec4 a = textureLod(uA, vUV - uAlpha * duv, 0.0);\n" +
            "    vec4 b = textureLod(uB, vUV + (1.0 - uAlpha) * duv, 0.0);\n" +
            "    vec4 warped = mix(a, b, uAlpha);\n" +
            "    vec4 plain = mix(textureLod(uA, vUV, 0.0), textureLod(uB, vUV, 0.0), uAlpha);\n" +
            "    vec2 px = 1.0 / vec2(textureSize(uFlow, 0));\n" +
            "    float jump = max(max(length(texture(uFlow, vUV + vec2(px.x, 0.0)).xy - f.xy), length(texture(uFlow, vUV - vec2(px.x, 0.0)).xy - f.xy)),\n" +
            "                     max(length(texture(uFlow, vUV + vec2(0.0, px.y)).xy - f.xy), length(texture(uFlow, vUV - vec2(0.0, px.y)).xy - f.xy)));\n" +
            "    float bad = max(smoothstep(uCostLo, uCostHi, f.z / (f.w + 0.015)), smoothstep(uIncLo, uIncHi, jump));\n" +
            "    outColor = mix(warped, plain, bad);\n" +
            "}\n";

    /** Motion blur, one pass: the picture smeared along the motion (motionblur_streak.frag). */
    private static final String STREAK_FRAGMENT = FRAGMENT_HEADER +
            "uniform sampler2D uSrc;\n" +
            "uniform sampler2D uSharp;\n" +
            "uniform sampler2D uFlow;\n" +
            "uniform float uLength;\n" +
            "uniform int uDirection;\n" +
            "uniform float uBlend;\n" +
            "uniform float uCostLo;\n" +
            "uniform float uCostHi;\n" +
            "uniform int uMaxTaps;\n" +
            "in vec2 vUV;\n" +
            "out vec4 outColor;\n" +
            "void main() {\n" +
            "    vec4 f = texture(uFlow, vUV);\n" +
            "    vec2 size = vec2(textureSize(uSrc, 0));\n" +
            "    vec2 duv = f.xy / vec2(textureSize(uFlow, 0));\n" +
            "    float trust = 1.0 - smoothstep(uCostLo, uCostHi, f.z / (f.w + 0.015));\n" +
            "    vec2 s = duv * uLength * trust;\n" +
            "    float lengthPx = length(s * size);\n" +
            "    float t0 = -0.5, t1 = 0.5;\n" +
            "    if (uDirection == 1) { t0 = 0.0; t1 = 1.0; }\n" +
            "    else if (uDirection == 2) { t0 = -1.0; t1 = 0.0; }\n" +
            "    int taps = clamp(int(ceil(lengthPx)) + 1, 1, uMaxTaps);\n" +
            "    vec4 sum = vec4(0.0);\n" +
            "    for (int i = 0; i < taps; i++) {\n" +
            "        float t = mix(t0, t1, (float(i) + 0.5) / float(taps));\n" +
            "        sum += textureLod(uSrc, vUV - t * s, 0.0);\n" +
            "    }\n" +
            "    vec4 smeared = sum / float(taps);\n" +
            "    outColor = mix(smeared, textureLod(uSharp, vUV, 0.0), uBlend);\n" +
            "}\n";

    /** A frame shrunk to a small mosaic (for the scene cut check): a read of a coarse mip level. */
    private static final String MOSAIC_FRAGMENT = FRAGMENT_HEADER +
            "uniform sampler2D uA;\n" +
            "uniform float uLod;\n" +
            "in vec2 vUV;\n" +
            "out vec4 outColor;\n" +
            "void main() { outColor = textureLod(uA, vUV, uLod); }\n";

    /** The video frame (an external OES texture) copied into a plain texture: the SAME quad as the clip shader (see blitFrame). */
    private static final String BLIT_VERTEX =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
            "}\n";
    private static final String BLIT_FRAGMENT =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "void main() { gl_FragColor = texture2D(uTexture, vTexCoord); }\n";

    // Same table as TransformShader.QUAD_VERTICES (x, y, u, v): drawn into a texture with an identity matrix it gives the
    // picture in the layout ImageTransformShader samples (v = 0 is the top row).
    private static final float[] BLIT_QUAD = {
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f,
    };

    /** Motion blur for one picture: {@code length} and {@code blend} 0 ... 1, {@code direction} 0 both / 1 forward / 2 backward. */
    static final class Blur {
        final float length, blend;
        final int direction, passes;

        Blur(float length, float blend, int direction, int passes) {
            this.length = length;
            this.blend = blend;
            this.direction = direction;
            this.passes = Math.max(1, Math.min(MAX_BLUR_PASSES, passes));
        }

        long signature() {
            return 1 + (long) (length * 1000) + 1001L * (long) (blend * 1000) + 1_002_001L * direction + 3_000_000_000L * passes;
        }
    }

    /** What one clip keeps for its optical flow: the pyramid and where the made picture goes. */
    static final class State {
        final int width, height, finest, levels;
        final int outTex, outFbo;
        final int[] flowTex, flowFbo;     // per level, index 0 = the finest
        final int subTex, subFbo;         // the finest level's half pixel pass writes here
        /** The pair seen last for the scene cut check, and whether it is a cut. */
        long cutPair = Long.MIN_VALUE;
        boolean cut;
        /** What the out texture holds: the pair, and what of it (-1 = nothing yet, 2+ = a mix at that alpha) and its blur. */
        long contentPair = Long.MIN_VALUE;
        long contentKind = -1;
        long contentBlur = 0;
        /** The sharp made frame (a mix), and the two textures the blur passes swap between: made when a blur first needs them. */
        int baseTex, baseFbo, pingTex, pingFbo, pongTex, pongFbo;

        private State(int width, int height, int finest, int levels, int outTex, int outFbo, int[] flowTex, int[] flowFbo,
                      int subTex, int subFbo) {
            this.width = width;
            this.height = height;
            this.finest = finest;
            this.levels = levels;
            this.outTex = outTex;
            this.outFbo = outFbo;
            this.flowTex = flowTex;
            this.flowFbo = flowFbo;
            this.subTex = subTex;
            this.subFbo = subFbo;
        }
    }

    private final int flowSide;
    private boolean built = false, failed = false;
    private String failure = "";
    private int searchProgram, composeProgram, streakProgram, mosaicProgram, blitProgram, vao, blitVao, blitVbo;
    private int uSA, uSB, uSPrev, uSLod, uSHasPrev, uSAlpha, uSRadius, uSStep, uSWin, uSStride, uSBias;
    private int uCA, uCB, uCFlow, uCAlpha, uCCostLo, uCCostHi, uCIncLo, uCIncHi;
    private int uMSrc, uMSharp, uMFlow, uMLength, uMDirection, uMBlend, uMCostLo, uMCostHi, uMMaxTaps;
    private int uQA, uQLod;
    private int uBTexture, uBTexMatrix;
    private int mosaicTexA, mosaicFboA, mosaicTexB, mosaicFboB;
    private final ByteBuffer mosaicA = ByteBuffer.allocateDirect(CUT_W * CUT_H * 4).order(ByteOrder.nativeOrder());
    private final ByteBuffer mosaicB = ByteBuffer.allocateDirect(CUT_W * CUT_H * 4).order(ByteOrder.nativeOrder());
    private final float[] identity = new float[16];

    /** @param flowSide the long side, in pixels, of the finest level of the motion pyramid (see {@link OpticalFlowMath}) */
    OpticalFlowGl(int flowSide) {
        this.flowSide = Math.max(16, flowSide);
        android.opengl.Matrix.setIdentityM(identity, 0);
    }

    /** Why it is unavailable (for the export log); empty while it is fine. */
    String failure() {
        return failure;
    }

    /** Builds the shaders on first use; false (for good) if this GPU or context can't do it. */
    boolean ready() {
        if (built) return true;
        if (failed) return false;
        try {
            String version = GLES20.glGetString(GLES20.GL_VERSION);
            if (version == null || !version.contains("OpenGL ES 3")) {
                throw new RuntimeException("needs OpenGL ES 3.0 (this context is " + version + ")");
            }
            searchProgram = buildProgram(FULLSCREEN_VERTEX, SEARCH_FRAGMENT);
            composeProgram = buildProgram(FULLSCREEN_VERTEX, COMPOSE_FRAGMENT);
            streakProgram = buildProgram(FULLSCREEN_VERTEX, STREAK_FRAGMENT);
            mosaicProgram = buildProgram(FULLSCREEN_VERTEX, MOSAIC_FRAGMENT);
            blitProgram = buildProgram(BLIT_VERTEX, BLIT_FRAGMENT);
            uSA = loc(searchProgram, "uA");
            uSB = loc(searchProgram, "uB");
            uSPrev = loc(searchProgram, "uPrev");
            uSLod = loc(searchProgram, "uLod");
            uSHasPrev = loc(searchProgram, "uHasPrev");
            uSAlpha = loc(searchProgram, "uAlpha");
            uSRadius = loc(searchProgram, "uRadius");
            uSStep = loc(searchProgram, "uStep");
            uSWin = loc(searchProgram, "uWin");
            uSStride = loc(searchProgram, "uStride");
            uSBias = loc(searchProgram, "uBias");
            uCA = loc(composeProgram, "uA");
            uCB = loc(composeProgram, "uB");
            uCFlow = loc(composeProgram, "uFlow");
            uCAlpha = loc(composeProgram, "uAlpha");
            uCCostLo = loc(composeProgram, "uCostLo");
            uCCostHi = loc(composeProgram, "uCostHi");
            uCIncLo = loc(composeProgram, "uIncLo");
            uCIncHi = loc(composeProgram, "uIncHi");
            uMSrc = loc(streakProgram, "uSrc");
            uMSharp = loc(streakProgram, "uSharp");
            uMFlow = loc(streakProgram, "uFlow");
            uMLength = loc(streakProgram, "uLength");
            uMDirection = loc(streakProgram, "uDirection");
            uMBlend = loc(streakProgram, "uBlend");
            uMCostLo = loc(streakProgram, "uCostLo");
            uMCostHi = loc(streakProgram, "uCostHi");
            uMMaxTaps = loc(streakProgram, "uMaxTaps");
            uQA = loc(mosaicProgram, "uA");
            uQLod = loc(mosaicProgram, "uLod");
            uBTexture = loc(blitProgram, "uTexture");
            uBTexMatrix = loc(blitProgram, "uTexMatrix");

            int[] ids = new int[1];
            GLES30.glGenVertexArrays(1, ids, 0);
            vao = ids[0];
            // The blit's quad lives in a buffer of its own, so it needs no client side arrays (not allowed next to a vertex
            // array object); the clip shaders' own attribute state is put back after every use.
            int[] buffers = new int[1];
            GLES20.glGenBuffers(1, buffers, 0);
            blitVbo = buffers[0];
            GLES30.glGenVertexArrays(1, ids, 0);
            blitVao = ids[0];
            FloatBuffer quad = ByteBuffer.allocateDirect(BLIT_QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(BLIT_QUAD).position(0);
            int previousVao = getInt(GLES30.GL_VERTEX_ARRAY_BINDING), previousBuffer = getInt(GLES20.GL_ARRAY_BUFFER_BINDING);
            GLES30.glBindVertexArray(blitVao);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, blitVbo);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, BLIT_QUAD.length * 4, quad, GLES20.GL_STATIC_DRAW);
            int aPosition = GLES20.glGetAttribLocation(blitProgram, "aPosition");
            int aTexCoord = GLES20.glGetAttribLocation(blitProgram, "aTexCoord");
            GLES20.glEnableVertexAttribArray(aPosition);
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, 0);
            GLES20.glEnableVertexAttribArray(aTexCoord);
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, 8);
            GLES30.glBindVertexArray(previousVao);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, previousBuffer);

            // Half float colour attachments are optional in ES 3.0: try one now, so "can't" is known before a clip needs it.
            int probe = flowTexture(8, 8);
            try {
                fboFor(probe);
            } finally {
                GLES20.glDeleteTextures(1, new int[]{probe}, 0);
            }
            built = true;
            return true;
        } catch (RuntimeException e) {
            failed = true;
            failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return false;
        }
    }

    private static int loc(int program, String name) {
        return GLES20.glGetUniformLocation(program, name);
    }

    // ---- frames -------------------------------------------------------------------------------------

    /** A mipmapped RGBA8 texture for one decoded frame (all levels allocated, so glGenerateMipmap works on any size). */
    static int createFrameTexture(int w, int h) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        int levels = 1 + (int) Math.floor(Math.log(Math.max(w, h)) / Math.log(2));
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, levels, GLES30.GL_RGBA8, w, h);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return tex[0];
    }

    /** A framebuffer with {@code texture} (level 0) as its colour target. */
    static int createFramebuffer(int texture) {
        return fboFor(texture);
    }

    /**
     * Copies the video frame in {@code oesTexture} (seen through the surface's texture matrix, which carries the rotation and
     * crop) into {@code targetFbo}'s texture, then builds its mip levels. The result is the picture in the layout the image
     * shader samples. Puts the GL state it changes back.
     */
    void blitFrame(int oesTexture, float[] texMatrix, int targetFbo, int targetTexture, int width, int height) {
        Saved saved = save();
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFbo);
            GLES20.glViewport(0, 0, width, height);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES20.glUseProgram(blitProgram);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
            GLES20.glUniform1i(uBTexture, 0);
            GLES20.glUniformMatrix4fv(uBTexMatrix, 1, false, texMatrix, 0);
            GLES30.glBindVertexArray(blitVao);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, targetTexture);
            GLES30.glGenerateMipmap(GLES20.GL_TEXTURE_2D);   // the pyramid samples the frames at coarser sizes
        } finally {
            restore(saved);
        }
    }

    // ---- per clip --------------------------------------------------------------------------------------

    /** The textures and targets for a clip of this picture size. */
    State createState(int width, int height) {
        int[] plan = OpticalFlowMath.plan(width, height, flowSide);
        int finest = plan[0], levels = plan[1];
        int[] flowTex = new int[levels], flowFbo = new int[levels];
        for (int k = 0; k < levels; k++) {
            flowTex[k] = flowTexture(OpticalFlowMath.mipSize(width, finest + k), OpticalFlowMath.mipSize(height, finest + k));
            flowFbo[k] = fboFor(flowTex[k]);
        }
        int subTex = flowTexture(OpticalFlowMath.mipSize(width, finest), OpticalFlowMath.mipSize(height, finest));
        int subFbo = fboFor(subTex);
        int outTex = plainTexture(width, height);
        int outFbo = fboFor(outTex);
        return new State(width, height, finest, levels, outTex, outFbo, flowTex, flowFbo, subTex, subFbo);
    }

    void destroyState(State s) {
        if (s == null) return;
        deleteTex(s.outTex);
        deleteFbo(s.outFbo);
        deleteTex(s.subTex);
        deleteFbo(s.subFbo);
        deleteTex(s.baseTex);
        deleteFbo(s.baseFbo);
        deleteTex(s.pingTex);
        deleteFbo(s.pingFbo);
        deleteTex(s.pongTex);
        deleteFbo(s.pongFbo);
        for (int k = 0; k < s.levels; k++) {
            deleteTex(s.flowTex[k]);
            deleteFbo(s.flowFbo[k]);
        }
    }

    /** Frees what the engine itself holds (programs, the small scene cut targets). */
    void close() {
        if (!built) return;
        GLES20.glDeleteProgram(searchProgram);
        GLES20.glDeleteProgram(composeProgram);
        GLES20.glDeleteProgram(streakProgram);
        GLES20.glDeleteProgram(mosaicProgram);
        GLES20.glDeleteProgram(blitProgram);
        GLES30.glDeleteVertexArrays(1, new int[]{vao}, 0);
        GLES30.glDeleteVertexArrays(1, new int[]{blitVao}, 0);
        GLES20.glDeleteBuffers(1, new int[]{blitVbo}, 0);
        deleteTex(mosaicTexA);
        deleteFbo(mosaicFboA);
        deleteTex(mosaicTexB);
        deleteFbo(mosaicFboB);
        built = false;
    }

    /**
     * The picture {@code alpha} of the way from frame A ({@code texA}, 0) to frame B ({@code texB}, 1), as a texture the image
     * shader can draw; {@code pairId} says which two frames these are (the same id = the same pair, so a repeated call with
     * the same alpha costs nothing). With {@code interpolate} false the picture is frame B itself and only the motion between
     * the two is used (a clip with motion blur but no optical flow). {@code blur} (or null) then smears the picture along that
     * motion. Returns {@code texA} or {@code texB} themselves when the answer is just one of the frames. Puts the GL state it
     * changes back.
     */
    int render(State s, long pairId, int texA, int texB, double alpha, boolean interpolate, Blur blur) {
        OpticalFlowMath.Mode mode = interpolate ? OpticalFlowMath.modeFor(alpha) : OpticalFlowMath.Mode.SECOND;
        boolean needsMotion = blur != null || mode == OpticalFlowMath.Mode.MIX;
        if (needsMotion && s.cutPair != pairId) {
            s.cutPair = pairId;
            s.cut = isSceneCut(texA, texB, s.width, s.height);
        }
        // A scene cut has no in-between picture and no motion to smear: the nearer frame is held (ffmpeg's scene change
        // detection does the same).
        if (needsMotion && s.cut) {
            blur = null;
            if (mode == OpticalFlowMath.Mode.MIX) mode = alpha < 0.5 ? OpticalFlowMath.Mode.FIRST : OpticalFlowMath.Mode.SECOND;
        }
        if (blur == null && mode != OpticalFlowMath.Mode.MIX) {
            return mode == OpticalFlowMath.Mode.FIRST ? texA : texB;
        }
        long kind = mode == OpticalFlowMath.Mode.FIRST ? 0 : mode == OpticalFlowMath.Mode.SECOND ? 1 : 2 + (long) (alpha * 10000.0);
        long blurSignature = blur == null ? 0 : blur.signature();
        if (s.contentPair == pairId && s.contentKind == kind && s.contentBlur == blurSignature) return s.outTex; // same picture again

        Saved saved = save();
        try {
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES30.glBindVertexArray(vao);
            // the motion is looked for at the moment of the picture: A for the first frame, B for the second, between for a mix
            runPyramid(s, texA, texB, mode == OpticalFlowMath.Mode.FIRST ? 0f : mode == OpticalFlowMath.Mode.SECOND ? 1f : (float) alpha);
            if (blur == null) {
                compose(s, texA, texB, (float) alpha, s.outFbo);
            } else {
                int base;
                if (mode == OpticalFlowMath.Mode.MIX) {
                    ensureBase(s);
                    compose(s, texA, texB, (float) alpha, s.baseFbo);
                    base = s.baseTex;
                } else {
                    base = mode == OpticalFlowMath.Mode.FIRST ? texA : texB;
                }
                smear(s, base, blur);
            }
        } finally {
            restore(saved);
        }
        s.contentPair = pairId;
        s.contentKind = kind;
        s.contentBlur = blurSignature;
        return s.outTex;
    }

    // ---- the passes ------------------------------------------------------------------------------

    private void runPyramid(State s, int texA, int texB, float alpha) {
        GLES20.glUseProgram(searchProgram);
        GLES20.glUniform1i(uSA, 0);
        GLES20.glUniform1i(uSB, 1);
        GLES20.glUniform1i(uSPrev, 2);
        GLES20.glUniform1f(uSAlpha, alpha);
        GLES20.glUniform1f(uSBias, BIAS);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texA);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texB);

        int previous = 0; // the texture of the coarser level; 0 = none
        for (int k = s.levels - 1; k >= 0; k--) {
            int[] p = k == s.levels - 1 ? COARSE : (k == 0 ? FINE : MID);
            searchPass(s, k, previous, p[0], p[1], p[2], p[3], s.flowFbo[k]);
            previous = s.flowTex[k];
        }
        // half pixel refinement of the finest level (written to its own texture, never read and written at once)
        searchPass(s, 0, previous, SUB_RADIUS, SUB_STEP, SUB_WIN, SUB_STRIDE, s.subFbo);
    }

    private void searchPass(State s, int k, int previousTexture, int radius, float step, int win, int stride, int targetFbo) {
        int lod = s.finest + k;
        int lw = OpticalFlowMath.mipSize(s.width, lod), lh = OpticalFlowMath.mipSize(s.height, lod);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFbo);
        GLES20.glViewport(0, 0, lw, lh);
        GLES20.glUniform1i(uSLod, lod);
        GLES20.glUniform1i(uSRadius, radius);
        GLES20.glUniform1f(uSStep, step);
        GLES20.glUniform1i(uSWin, win);
        GLES20.glUniform1i(uSStride, stride);
        GLES20.glUniform1i(uSHasPrev, previousTexture != 0 ? 1 : 0);
        // Unit 2 holds the coarser level, or nothing: never a texture that is also the target of this very pass (a texture
        // read and written at once is undefined, even when the shader's branch doesn't sample it).
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexture);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
    }

    private void compose(State s, int texA, int texB, float alpha, int targetFbo) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFbo);
        GLES20.glViewport(0, 0, s.width, s.height);
        GLES20.glUseProgram(composeProgram);
        GLES20.glUniform1i(uCA, 0);
        GLES20.glUniform1i(uCB, 1);
        GLES20.glUniform1i(uCFlow, 2);
        GLES20.glUniform1f(uCAlpha, alpha);
        GLES20.glUniform1f(uCCostLo, COST_LO);
        GLES20.glUniform1f(uCCostHi, COST_HI);
        GLES20.glUniform1f(uCIncLo, JUMP_LO);
        GLES20.glUniform1f(uCIncHi, JUMP_HI);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texA);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texB);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, s.subTex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
    }

    /**
     * The blur passes: {@code base} (the sharp picture) smeared {@code blur.passes} times along the motion in the finest flow
     * level, each pass reading the one before; the last one mixes the sharp picture back in by Blend and writes the out texture.
     */
    private void smear(State s, int base, Blur blur) {
        ensureBlurTargets(s);
        GLES20.glUseProgram(streakProgram);
        GLES20.glUniform1i(uMSrc, 0);
        GLES20.glUniform1i(uMSharp, 1);
        GLES20.glUniform1i(uMFlow, 2);
        GLES20.glUniform1f(uMLength, blur.length);
        GLES20.glUniform1i(uMDirection, blur.direction);
        GLES20.glUniform1f(uMCostLo, COST_LO);
        GLES20.glUniform1f(uMCostHi, COST_HI);
        GLES20.glUniform1i(uMMaxTaps, MAX_BLUR_TAPS);
        GLES20.glViewport(0, 0, s.width, s.height);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, base);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, s.subTex);

        int source = base;
        for (int i = 0; i < blur.passes; i++) {
            boolean last = i == blur.passes - 1;
            int targetFbo = last ? s.outFbo : (i % 2 == 0 ? s.pingFbo : s.pongFbo);
            int targetTex = i % 2 == 0 ? s.pingTex : s.pongTex;
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFbo);
            GLES20.glUniform1f(uMBlend, last ? blur.blend : 0f);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
            source = targetTex;
        }
    }

    // ---- scene cuts -----------------------------------------------------------------------------------

    /**
     * True when the two frames are different shots (see {@link OpticalFlowMath#isSceneCut}): both are shrunk to a small mosaic
     * on the GPU and read back (two tiny reads, once per pair of frames).
     */
    private boolean isSceneCut(int texA, int texB, int width, int height) {
        if (mosaicTexA == 0) {
            mosaicTexA = plainTexture(CUT_W, CUT_H);
            mosaicFboA = fboFor(mosaicTexA);
            mosaicTexB = plainTexture(CUT_W, CUT_H);
            mosaicFboB = fboFor(mosaicTexB);
        }
        Saved saved = save();
        try {
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES30.glBindVertexArray(vao);
            GLES20.glUseProgram(mosaicProgram);
            GLES20.glUniform1i(uQA, 0);
            GLES20.glUniform1f(uQLod, Math.max(0f, (float) (Math.log(Math.max(width, height) / (double) CUT_W) / Math.log(2))));
            GLES20.glViewport(0, 0, CUT_W, CUT_H);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            mosaicInto(texA, mosaicFboA, mosaicA);
            mosaicInto(texB, mosaicFboB, mosaicB);
        } finally {
            restore(saved);
        }
        return OpticalFlowMath.isSceneCut(mosaicA, mosaicB, CUT_W, CUT_H);
    }

    private void mosaicInto(int texture, int fbo, ByteBuffer into) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
        into.clear();
        GLES20.glReadPixels(0, 0, CUT_W, CUT_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, into);
    }

    // ---- GL objects -------------------------------------------------------------------------------------

    private static void ensureBase(State s) {
        if (s.baseTex != 0) return;
        s.baseTex = plainTexture(s.width, s.height);
        s.baseFbo = fboFor(s.baseTex);
    }

    private static void ensureBlurTargets(State s) {
        if (s.pingTex == 0) {
            s.pingTex = plainTexture(s.width, s.height);
            s.pingFbo = fboFor(s.pingTex);
            s.pongTex = plainTexture(s.width, s.height);
            s.pongFbo = fboFor(s.pongTex);
        }
    }

    private static int plainTexture(int w, int h) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, w, h);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return tex[0];
    }

    /** Half float RGBA: x, y of the motion in the level's pixels, the match cost, the block's contrast. */
    private static int flowTexture(int w, int h) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, w, h);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return tex[0];
    }

    private static int fboFor(int texture) {
        int previous = getInt(GLES20.GL_FRAMEBUFFER_BINDING);
        int[] fbo = new int[1];
        GLES20.glGenFramebuffers(1, fbo, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, previous);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            GLES20.glDeleteFramebuffers(1, fbo, 0);
            throw new RuntimeException("framebuffer incomplete: 0x" + Integer.toHexString(status)
                    + " (this GPU can't render to half float textures?)");
        }
        return fbo[0];
    }

    private static void deleteTex(int tex) {
        if (tex != 0) GLES20.glDeleteTextures(1, new int[]{tex}, 0);
    }

    private static void deleteFbo(int fbo) {
        if (fbo != 0) GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
    }

    private static int buildProgram(String vertexSrc, String fragmentSrc) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc);
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);
        int[] status = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(prog);
            GLES20.glDeleteProgram(prog);
            throw new RuntimeException("shader link failed: " + log);
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return prog;
    }

    private static int compileShader(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("shader compile failed: " + log);
        }
        return shader;
    }

    // ---- the caller's GL state, put back after every use ------------------------------------------------

    private static final class Saved {
        int framebuffer, program, vao, arrayBuffer, activeTexture;
        final int[] viewport = new int[4];
        boolean blend, scissor;
    }

    private static int getInt(int pname) {
        int[] v = new int[1];
        GLES20.glGetIntegerv(pname, v, 0);
        return v[0];
    }

    private static Saved save() {
        Saved s = new Saved();
        s.framebuffer = getInt(GLES20.GL_FRAMEBUFFER_BINDING);
        s.program = getInt(GLES20.GL_CURRENT_PROGRAM);
        s.vao = getInt(GLES30.GL_VERTEX_ARRAY_BINDING);
        s.arrayBuffer = getInt(GLES20.GL_ARRAY_BUFFER_BINDING);
        s.activeTexture = getInt(GLES20.GL_ACTIVE_TEXTURE);
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, s.viewport, 0);
        s.blend = GLES20.glIsEnabled(GLES20.GL_BLEND);
        s.scissor = GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST);
        return s;
    }

    private static void restore(Saved s) {
        GLES30.glBindVertexArray(s.vao);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, s.arrayBuffer);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, s.framebuffer);
        GLES20.glViewport(s.viewport[0], s.viewport[1], s.viewport[2], s.viewport[3]);
        if (s.blend) GLES20.glEnable(GLES20.GL_BLEND); else GLES20.glDisable(GLES20.GL_BLEND);
        if (s.scissor) GLES20.glEnable(GLES20.GL_SCISSOR_TEST); else GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glUseProgram(s.program);
        GLES20.glActiveTexture(s.activeTexture);
    }
}
