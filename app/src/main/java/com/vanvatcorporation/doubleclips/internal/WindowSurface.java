package com.vanvatcorporation.doubleclips.internal;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLSurface;
import android.view.Surface;

public class WindowSurface {
    private EglCore mEglCore;
    private EGLSurface mEGLSurface = EGL14.EGL_NO_SURFACE;
    private Surface mSurface;
    private boolean mReleaseSurface;

    public WindowSurface(EglCore eglCore, Surface surface, boolean releaseSurface) {
        mEglCore = eglCore;
        mEGLSurface = mEglCore.createWindowSurface(surface);
        mSurface = surface;
        mReleaseSurface = releaseSurface;
    }

    public WindowSurface(EglCore eglCore, SurfaceTexture surfaceTexture) {
        mEglCore = eglCore;
        mEGLSurface = mEglCore.createWindowSurface(surfaceTexture);
    }

    public void release() {
        if (mEglCore != null) {
            if (mEGLSurface != EGL14.EGL_NO_SURFACE) {
                // Ignore failure
                EGL14.eglDestroySurface(mEglCore.getEglDisplay(), mEGLSurface);
                mEGLSurface = EGL14.EGL_NO_SURFACE;
            }
        }
        if (mSurface != null) {
            if (mReleaseSurface) {
                mSurface.release();
            }
            mSurface = null;
        }
    }

    public void makeCurrent() {
        mEglCore.makeCurrent(mEGLSurface);
    }

    public boolean swapBuffers() {
        return mEglCore.swapBuffers(mEGLSurface);
    }

    public void setPresentationTime(long nsecs) {
        mEglCore.setPresentationTime(mEGLSurface, nsecs);
    }

    public int getWidth() {
        return mEglCore.querySurface(mEGLSurface, EGL14.EGL_WIDTH);
    }

    public int getHeight() {
        return mEglCore.querySurface(mEGLSurface, EGL14.EGL_HEIGHT);
    }
}
