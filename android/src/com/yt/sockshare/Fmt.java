package com.yt.sockshare;

/** Format angka untuk UI: ukuran data dan kecepatan (tanpa dependensi Android). */
public class Fmt {

    public static String size(long b) {
        if (b < 0) b = 0;
        if (b < 1024) return b + " B";
        double v = b / 1024.0;
        if (v < 1024) return one(v) + " KB";
        v /= 1024.0;
        if (v < 1024) return one(v) + " MB";
        v /= 1024.0;
        return two(v) + " GB";
    }

    public static String speed(long bps) {
        if (bps < 0) bps = 0;
        if (bps < 1024) return bps + " B/s";
        double v = bps / 1024.0;
        if (v < 1024) return one(v) + " KB/s";
        v /= 1024.0;
        return two(v) + " MB/s";
    }

    private static String one(double v) {
        return String.format(java.util.Locale.US, v < 100 ? "%.1f" : "%.0f", v);
    }

    private static String two(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}
