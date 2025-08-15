package com.vanvatcorporation.doubleclips.utils;

import android.content.Context;
import android.opengl.GLES20;

import com.vanvatcorporation.doubleclips.manager.LoggingManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class ShaderUtils {


    public static String readAssetFile(Context context, String filename) {
        try {

            InputStream is = context.getAssets().open(filename);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            reader.close();
            return sb.toString();
        }
        catch (Exception e)
        {
            LoggingManager.LogExceptionToNoteOverlay(context, e);
            return "";
        }
    }


//    public static String readAssetFile(Context context, String filename) {
//
//
//        try {
//            InputStream is = context.getAssets().open(filename);
//            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
//            StringBuilder sb = new StringBuilder();
//            String line;
//            while ((line = reader.readLine()) != null) {
//                sb.append(line.trim()).append("\n"); // strip trailing junk
//            }
//            reader.close();
//            return sb.toString();
//        }
//        catch (Exception e)
//        {
//            LoggingManager.LogExceptionToNoteOverlay(context, e);
//            return "";
//        }
//    }


    public static int buildShader(Context context, String vertexSrc, String fragmentSrc) {
        int vertexShader = loadShader(context, GLES20.GL_VERTEX_SHADER, vertexSrc);
        int fragmentShader = loadShader(context, GLES20.GL_FRAGMENT_SHADER, fragmentSrc);

        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        // Optional: check link status
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            LoggingManager.LogToNoteOverlay(context, "Shader link failed: " + log);
        }

        return program;
    }

    private static int loadShader(Context context, int type, String shaderSrc) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderSrc);
        GLES20.glCompileShader(shader);

        // Optional: check compile status
        int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            LoggingManager.LogToNoteOverlay(context, "Shader compile failed: " + log);
        }

        return shader;
    }

}
