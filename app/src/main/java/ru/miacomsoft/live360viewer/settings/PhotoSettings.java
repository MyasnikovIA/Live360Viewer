package ru.miacomsoft.live360viewer.settings;

import android.content.Context;
import android.content.SharedPreferences;

public final class PhotoSettings {

    public static final String PREFS   = "photo_settings";
    public static final String KEY_RES = "photo_res";
    public static final String KEY_VIDEO_RES = "video_res";
    public static final String KEY_VIEW_MODE = "view_mode";

    public static final int RES_5MP  = 0x03;
    public static final int RES_13MP = 0x04;
    public static final int DEFAULT_RES = RES_5MP;

    // ★ Расширенный список кодов разрешений превью для камер Huawei
    public static final int VID_RES_640_360   = 0x00;
    public static final int VID_RES_800_450   = 0x01;
    public static final int VID_RES_960_540   = 0x02;
    public static final int VID_RES_1280_720  = 0x03;
    public static final int VID_RES_1280_640  = 0x0A; // Дефолт для CV60
    public static final int VID_RES_1920_1080 = 0x0B;

    public static final int DEFAULT_VIDEO_RES = VID_RES_1280_640;

    public static final int VIEW_MODE_SPHERE = 0;
    public static final int VIEW_MODE_FLAT   = 1;
    public static final int DEFAULT_VIEW_MODE = VIEW_MODE_SPHERE;

    private static final int[] ALL = { RES_5MP, RES_13MP };
    private static final int[] ALL_VIDEO = {
            VID_RES_640_360,
            VID_RES_800_450,
            VID_RES_960_540,
            VID_RES_1280_720,
            VID_RES_1280_640,
            VID_RES_1920_1080
    };

    private PhotoSettings() {}

    public static int[] allCodes() { return ALL.clone(); }
    public static int[] allVideoCodes() { return ALL_VIDEO.clone(); }

    public static String label(int code) {
        switch (code) {
            case RES_5MP:  return "5 МП (0x03)";
            case RES_13MP: return "13 МП (0x04)";
            default:       return "0x" + Integer.toHexString(code);
        }
    }

    public static String labelVideoRes(int code) {
        switch (code) {
            case VID_RES_640_360:   return "640×360 (0x00)";
            case VID_RES_800_450:   return "800×450 (0x01)";
            case VID_RES_960_540:   return "960×540 (0x02)";
            case VID_RES_1280_720:  return "1280×720 (0x03)";
            case VID_RES_1280_640:  return "1280×640 (0x0A)";
            case VID_RES_1920_1080: return "1920×1080 (0x0B)";
            default:                return "0x" + Integer.toHexString(code);
        }
    }

    public static boolean isValid(int code) {
        return code == RES_5MP || code == RES_13MP;
    }

    public static boolean isValidVideoRes(int code) {
        for (int c : ALL_VIDEO) if (c == code) return true;
        return false;
    }

    public static int getResolution(Context ctx) {
        int saved = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_RES, DEFAULT_RES);
        if (!isValid(saved)) {
            saved = DEFAULT_RES;
            setResolution(ctx, saved);
        }
        return saved;
    }

    public static void setResolution(Context ctx, int code) {
        if (!isValid(code)) code = DEFAULT_RES;
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_RES, code).apply();
    }

    public static int getVideoResolution(Context ctx) {
        int saved = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_VIDEO_RES, DEFAULT_VIDEO_RES);
        if (!isValidVideoRes(saved)) {
            saved = DEFAULT_VIDEO_RES;
            setVideoResolution(ctx, saved);
        }
        return saved;
    }

    public static void setVideoResolution(Context ctx, int code) {
        if (!isValidVideoRes(code)) code = DEFAULT_VIDEO_RES;
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_VIDEO_RES, code).apply();
    }

    public static int getVideoWidth(int code) {
        switch (code) {
            case VID_RES_640_360: return 640;
            case VID_RES_800_450: return 800;
            case VID_RES_960_540: return 960;
            case VID_RES_1280_720: return 1280;
            case VID_RES_1920_1080: return 1920;
            default: return 1280;
        }
    }

    public static int getVideoHeight(int code) {
        switch (code) {
            case VID_RES_640_360: return 360;
            case VID_RES_800_450: return 450;
            case VID_RES_960_540: return 540;
            case VID_RES_1280_720: return 720;
            case VID_RES_1920_1080: return 1080;
            default: return 640;
        }
    }

    public static int[] allViewModes() {
        return new int[] { VIEW_MODE_SPHERE, VIEW_MODE_FLAT };
    }

    public static String labelView(int mode) {
        switch (mode) {
            case VIEW_MODE_SPHERE: return "Сфера (3D)";
            case VIEW_MODE_FLAT:   return "Плоский (360×180)";
            default:               return "Неизвестный режим";
        }
    }

    public static boolean isValidViewMode(int mode) {
        return mode == VIEW_MODE_SPHERE || mode == VIEW_MODE_FLAT;
    }

    public static int getViewMode(Context ctx) {
        int saved = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_VIEW_MODE, DEFAULT_VIEW_MODE);
        if (!isValidViewMode(saved)) {
            saved = DEFAULT_VIEW_MODE;
            setViewMode(ctx, saved);
        }
        return saved;
    }

    public static void setViewMode(Context ctx, int mode) {
        if (!isValidViewMode(mode)) mode = DEFAULT_VIEW_MODE;
        ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_VIEW_MODE, mode).apply();
    }
}