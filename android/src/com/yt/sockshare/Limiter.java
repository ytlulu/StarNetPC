package com.yt.sockshare;

/** Pembatas kecepatan (byte/detik) berbagi antar thread. rate <= 0 = tanpa batas. */
public class Limiter {

    private volatile long rate = 0;
    private long next = 0;

    public void setRate(long bytesPerSec) {
        rate = bytesPerSec;
    }

    public long getRate() {
        return rate;
    }

    /** Tunggu sampai n byte boleh lewat. */
    public void acquire(int n) throws InterruptedException {
        long r = rate;
        if (r <= 0 || n <= 0) return;
        long waitNs;
        synchronized (this) {
            long now = System.nanoTime();
            long burst = 100000000L; // toleransi 100 ms agar tidak patah-patah
            if (next < now - burst) next = now - burst;
            next += (long) ((double) n * 1000000000.0 / r);
            waitNs = next - now;
        }
        if (waitNs > 2000000L) {
            if (waitNs > 1500000000L) waitNs = 1500000000L;
            Thread.sleep(waitNs / 1000000L, (int) (waitNs % 1000000L));
        }
    }
}
