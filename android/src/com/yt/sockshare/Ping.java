package com.yt.sockshare;

/** Kumpulan sampel latensi (ms): rata-rata & jitter dari 10 sampel terakhir. -1 = gagal. */
public class Ping {

    private final int[] s = new int[10];
    private int n = 0;
    private int pos = 0;
    public volatile int last = -1;

    public synchronized void add(int ms) {
        last = ms;
        s[pos] = ms;
        pos = (pos + 1) % s.length;
        if (n < s.length) n++;
    }

    public synchronized void reset() {
        n = 0;
        pos = 0;
        last = -1;
    }

    /** Rata-rata sampel sukses, -1 bila belum ada. */
    public synchronized int avg() {
        int sum = 0, c = 0;
        for (int i = 0; i < n; i++) if (s[i] >= 0) { sum += s[i]; c++; }
        return c == 0 ? -1 : sum / c;
    }

    /** Rata-rata selisih antar sampel berurutan (ms). */
    public synchronized int jitter() {
        int start = (n < s.length) ? 0 : pos;
        int prev = -1, sum = 0, c = 0;
        for (int i = 0; i < n; i++) {
            int v = s[(start + i) % s.length];
            if (v < 0) { prev = -1; continue; }
            if (prev >= 0) { sum += Math.abs(v - prev); c++; }
            prev = v;
        }
        return c == 0 ? 0 : sum / c;
    }

    /** 0 = bagus (hijau), 1 = sedang (kuning), 2 = buruk (merah), -1 = belum ada. */
    public static int level(int ms) {
        if (ms < 0) return -1;
        return ms < 80 ? 0 : (ms < 160 ? 1 : 2);
    }

    public static String text(int ms) {
        return ms < 0 ? "-- ms" : ms + " ms";
    }
}
