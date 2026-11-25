package com.yt.sockshare;

/** Mode kinerja: Hemat / Seimbang / Speed (berlaku untuk sisi HP ini). */
public class Modes {

    public static final int HEMAT = 0;
    public static final int SEIMBANG = 1;
    public static final int SPEED = 2;

    /** Batas kecepatan mode Hemat per arah (byte/detik) ~ 3,2 Mbps. */
    public static final long HEMAT_RATE = 400L * 1024L;
    public static final int HEMAT_BATTERY_STOP = 15; // persen

    public static final Limiter up = new Limiter();
    public static final Limiter down = new Limiter();
    private static volatile int mode = SEIMBANG;

    public static int get() {
        return mode;
    }

    public static void set(int m) {
        if (m < HEMAT || m > SPEED) m = SEIMBANG;
        mode = m;
        long r = (m == HEMAT) ? HEMAT_RATE : 0;
        up.setRate(r);
        down.setRate(r);
    }

    public static String name(int m) {
        return m == HEMAT ? "Hemat" : (m == SPEED ? "Speed" : "Seimbang");
    }

    /** Ukuran buffer socket (byte). */
    public static int bufSize() {
        return mode == HEMAT ? 65536 : 262144;
    }

    public static boolean wantWakeLock() {
        return mode != HEMAT;
    }

    public static boolean wantWifiLock() {
        return mode == SPEED;
    }
}
