package io.opencam.webcam;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/** All persisted settings, with sensible defaults. */
public final class Prefs {

    public static final String PORT = "port";
    public static final String DEVICE_NAME = "device_name";
    public static final String CODEC = "codec";           // jpg | avc | hevc
    public static final String RESOLUTION = "resolution"; // WxH string
    public static final String FPS = "fps";
    public static final String BITRATE = "bitrate";       // kbps
    public static final String AUDIO_ENABLED = "audio_enabled";
    public static final String AUDIO_SAMPLE_RATE = "audio_sample_rate";
    public static final String AUDIO_BITRATE = "audio_bitrate"; // kbps
    public static final String NSD_ENABLED = "nsd_enabled";
    public static final String JPEG_QUALITY = "jpeg_quality";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c.getApplicationContext());
    }

    public static int port(Context c) {
        return sp(c).getInt(PORT, 4747);
    }

    public static String deviceName(Context c) {
        return sp(c).getString(DEVICE_NAME, android.os.Build.MODEL);
    }

    public static String codec(Context c) {
        return sp(c).getString(CODEC, "jpg");
    }

    public static String resolution(Context c) {
        return sp(c).getString(RESOLUTION, "1280x720");
    }

    public static int fps(Context c) {
        return sp(c).getInt(FPS, 30);
    }

    public static int bitrateKbps(Context c) {
        return sp(c).getInt(BITRATE, 4000);
    }

    public static boolean audioEnabled(Context c) {
        return sp(c).getBoolean(AUDIO_ENABLED, true);
    }

    public static int audioSampleRate(Context c) {
        return sp(c).getInt(AUDIO_SAMPLE_RATE, 44100);
    }

    public static int audioBitrateKbps(Context c) {
        return sp(c).getInt(AUDIO_BITRATE, 128);
    }

    public static boolean nsdEnabled(Context c) {
        return sp(c).getBoolean(NSD_ENABLED, true);
    }

    public static int jpegQuality(Context c) {
        return sp(c).getInt(JPEG_QUALITY, 85);
    }

    public static void putInt(Context c, String key, int value) {
        sp(c).edit().putInt(key, value).apply();
    }

    public static void putString(Context c, String key, String value) {
        sp(c).edit().putString(key, value).apply();
    }

    public static void putBoolean(Context c, String key, boolean value) {
        sp(c).edit().putBoolean(key, value).apply();
    }
}
