package com.yt.sockshare;

/** Hitung kecepatan upload/download (byte per detik, dihaluskan) dari dua penghitung total. */
public class Meter {

    private long lastT = 0;
    private long lastUp = 0;
    private long lastDown = 0;
    private boolean has = false;

    public long upSpeed = 0;
    public long downSpeed = 0;

    public void reset() {
        has = false;
        upSpeed = 0;
        downSpeed = 0;
    }

    /** Panggil tiap ~1 detik dengan total terbaru. */
    public void update(long up, long down) {
        long now = System.nanoTime();
        if (!has || up < lastUp || down < lastDown) { // mulai baru / penghitung di-reset
            has = true;
            lastT = now;
            lastUp = up;
            lastDown = down;
            upSpeed = 0;
            downSpeed = 0;
            return;
        }
        double dt = (now - lastT) / 1e9;
        if (dt < 0.4) return;
        double u = (up - lastUp) / dt;
        double d = (down - lastDown) / dt;
        upSpeed = (long) (upSpeed * 0.35 + u * 0.65);
        downSpeed = (long) (downSpeed * 0.35 + d * 0.65);
        lastT = now;
        lastUp = up;
        lastDown = down;
    }
}
