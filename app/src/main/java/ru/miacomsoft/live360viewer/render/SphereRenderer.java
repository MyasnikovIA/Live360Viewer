package ru.miacomsoft.live360viewer.render;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Рисует сферу с текстурой, взятой из SurfaceTexture.
 *
 * ВАЖНО: SurfaceTexture, отдаваемый MediaCodec через Surface, является
 * ВНЕШНЕЙ (external) текстурой. Биндить её нужно через
 * GL_TEXTURE_EXTERNAL_OES, а в шейдере объявлять samplerExternalOES.
 * Иначе updateTexImage() падает с GL_INVALID_OPERATION (0x502).
 */
public class SphereRenderer implements GLSurfaceView.Renderer,
        SurfaceTexture.OnFrameAvailableListener {

    private static final String TAG = "SphereRenderer";
    private static final int SPHERE_LAT = 40;
    private static final int SPHERE_LON = 80;

    public interface OnSurfaceReadyListener {
        void onSurfaceReady(SurfaceTexture st);
    }

    private final float[] proj = new float[16];
    private final float[] view = new float[16];
    private final float[] vp   = new float[16];

    private int program;
    private int aPos, aTex, uMVP, uTex;
    private int texId;
    private FloatBuffer posBuf, texBuf;
    private ShortBuffer idxBuf;
    private int idxCount;

    private SurfaceTexture surfaceTexture;
    private final Object texLock = new Object();
    private boolean frameAvailable;

    private float yaw = 0f;
    private float pitch = 0f;

    private int viewW = 1, viewH = 1;

    private OnSurfaceReadyListener readyListener;

    public SphereRenderer() {}

    public SurfaceTexture getSurfaceTexture() { return surfaceTexture; }

    public void setOnSurfaceReadyListener(OnSurfaceReadyListener l) {
        this.readyListener = l;
    }

    public void setRotation(float dYaw, float dPitch) {
        yaw += dYaw;
        pitch += dPitch;
        if (pitch > 80f) pitch = 80f;
        if (pitch < -80f) pitch = -80f;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        buildSphere();
        buildProgram();

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        texId = t[0];

        // ★ Биндим как EXTERNAL_OES, а не TEXTURE_2D
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        synchronized (texLock) {
            if (surfaceTexture != null) {
                try { surfaceTexture.release(); } catch (Exception ignored) {}
                surfaceTexture = null;
            }
            surfaceTexture = new SurfaceTexture(texId);
            surfaceTexture.setOnFrameAvailableListener(this);
            frameAvailable = false;
        }
        Log.i(TAG, "onSurfaceCreated: SurfaceTexture created @ " + surfaceTexture);

        if (readyListener != null) {
            readyListener.onSurfaceReady(surfaceTexture);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        viewW = w; viewH = h;
        GLES20.glViewport(0, 0, w, h);
        float aspect = (float) w / h;
        Matrix.perspectiveM(proj, 0, 90f, aspect, 0.1f, 100f);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        synchronized (texLock) {
            if (frameAvailable && surfaceTexture != null) {
                surfaceTexture.updateTexImage();
                frameAvailable = false;
            }
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        Matrix.setIdentityM(view, 0);
        Matrix.rotateM(view, 0, -pitch, 1, 0, 0);
        Matrix.rotateM(view, 0, -yaw, 0, 1, 0);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);

        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        // ★ Тот же тип — EXTERNAL_OES
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glUniformMatrix4fv(uMVP, 1, false, vp, 0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, posBuf);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuf);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, idxCount, GLES20.GL_UNSIGNED_SHORT, idxBuf);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aTex);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        synchronized (texLock) { frameAvailable = true; }
    }

    // ---------- helpers ----------

    private void buildSphere() {
        int vCount = (SPHERE_LAT + 1) * (SPHERE_LON + 1);
        float[] pos = new float[vCount * 3];
        float[] tex = new float[vCount * 2];
        int p = 0, t = 0;
        for (int i = 0; i <= SPHERE_LAT; i++) {
            double phi = Math.PI * i / SPHERE_LAT;
            for (int j = 0; j <= SPHERE_LON; j++) {
                double theta = 2.0 * Math.PI * j / SPHERE_LON;
                float x = (float) (Math.sin(phi) * Math.cos(theta));
                float y = (float) Math.cos(phi);
                float z = (float) (Math.sin(phi) * Math.sin(theta));
                pos[p++] = x; pos[p++] = y; pos[p++] = z;
                tex[t++] = 1f - (float) j / SPHERE_LON;
                tex[t++] = (float) i / SPHERE_LAT;
            }
        }
        short[] idx = new short[SPHERE_LAT * SPHERE_LON * 6];
        int k = 0;
        for (int i = 0; i < SPHERE_LAT; i++) {
            for (int j = 0; j < SPHERE_LON; j++) {
                int a = i * (SPHERE_LON + 1) + j;
                int b = a + SPHERE_LON + 1;
                idx[k++] = (short) a; idx[k++] = (short) b; idx[k++] = (short) (a + 1);
                idx[k++] = (short) (a + 1); idx[k++] = (short) b; idx[k++] = (short) (b + 1);
            }
        }
        idxCount = idx.length;

        posBuf = ByteBuffer.allocateDirect(pos.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        posBuf.put(pos).position(0);

        texBuf = ByteBuffer.allocateDirect(tex.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        texBuf.put(tex).position(0);

        idxBuf = ByteBuffer.allocateDirect(idx.length * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        idxBuf.put(idx).position(0);
    }

    private void buildProgram() {
        String vs =
                "uniform mat4 uMVP;\n" +
                        "attribute vec4 aPos;\n" +
                        "attribute vec2 aTex;\n" +
                        "varying vec2 vTex;\n" +
                        "void main(){ gl_Position = uMVP * aPos; vTex = aTex; }\n";

        // ★ samplerExternalOES + директива #extension
        String fs =
                "#extension GL_OES_EGL_image_external : require\n" +
                        "precision mediump float;\n" +
                        "varying vec2 vTex;\n" +
                        "uniform samplerExternalOES uTex;\n" +
                        "void main(){ gl_FragColor = texture2D(uTex, vTex); }\n";

        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) {
            Log.e(TAG, "buildProgram: shader compile failed");
            return;
        }
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, v);
        GLES20.glAttachShader(program, f);
        GLES20.glLinkProgram(program);

        int[] ok = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "program link: " + GLES20.glGetProgramInfoLog(program));
        }

        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aTex = GLES20.glGetAttribLocation(program, "aTex");
        uMVP = GLES20.glGetUniformLocation(program, "uMVP");
        uTex = GLES20.glGetUniformLocation(program, "uTex");
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "shader compile: " + GLES20.glGetShaderInfoLog(s));
            return 0;
        }
        return s;
    }
}