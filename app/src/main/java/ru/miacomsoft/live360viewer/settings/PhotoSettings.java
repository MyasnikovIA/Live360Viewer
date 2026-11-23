package ru.miacomsoft.live360viewer.settings;

import android.content.Context;
import android.content.SharedPreferences;

public final class PhotoSettings {

    public static final String PREFS   = "photo_settings";
    public static final String KEY_RES = "photo_res";
    public static final String KEY_VIEW_MODE = "view_mode";

    // ★ Только ДВА валидных значения на камере Huawei CV60
    public static final int RES_5MP  = 0x03;
    public static final int RES_13MP = 0x04;
    public static final int DEFAULT_RES = RES_5MP;

    // ★ Режимы просмотра
    public static final int VIEW_MODE_SPHERE = 0;   // 3D-сфера
    public static final int VIEW_MODE_FLAT   = 1;   // плоская equirect-панорама
    public static final int DEFAULT_VIEW_MODE = VIEW_MODE_SPHERE;

    private static final int[] ALL = { RES_5MP, RES_13MP };

    private PhotoSettings() {}

    public static int[] allCodes() { return ALL.clone(); }

    public static String label(int code) {
        switch (code) {
            case RES_5MP:  return "5 МП (0x03)";
            case RES_13MP: return "13 МП (0x04)";
            default:       return "0x" + Integer.toHexString(code) + " (невалидный)";
        }
    }

    public static boolean isValid(int code) {
        return code == RES_5MP || code == RES_13MP;
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

    // ============ Режим просмотра ============

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