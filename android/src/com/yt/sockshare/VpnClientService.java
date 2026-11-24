package com.yt.sockshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mode CONNECT (HP penerima): tangkap semua trafik lewat VpnService,
 * TCP diteruskan ke SOCKS5 HP sumber, DNS (UDP 53) diubah jadi DNS-over-TCP lewat SOCKS5.
 * UDP lain (voice Discord, game, QUIC) lewat SOCKS5 UDP ASSOCIATE. IPv4 saja.
 */
public class VpnClientService extends VpnService {

    public static volatile boolean running = false;
    public static volatile String lastError = "";
    public static final AtomicInteger flowsN = new AtomicInteger();
    public static final AtomicLong upBytes = new AtomicLong();
    public static final AtomicLong downBytes = new AtomicLong();

    private static final int MSS = 1360;
    private static final byte[] EMPTY = new byte[0];

    private String srvHost, user, pass;
    private int srvPort;
    private ParcelFileDescriptor pfd;
    private FileInputStream tunIn;
    private FileOutputStream tunOut;
    private volatile boolean stop = false;
    private final ConcurrentHashMap<Long, Flow> table = new ConcurrentHashMap<Long, Flow>();
    private final ConcurrentHashMap<Integer, UdpFlow> udpTable = new ConcurrentHashMap<Integer, UdpFlow>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Random rnd = new Random();
    private static volatile VpnClientService inst = null;
    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback wcb;
    private volatile Network wifi = null;

    @Override
    public int onStartCommand(Intent it, int fl, int id) {
        if (it == null || running) return START_NOT_STICKY;
        srvHost = it.getStringExtra("host");
        srvPort = ProxyService.PORT;
        user = ProxyService.USER;
        pass = ProxyService.PASS;
        lastError = "";
        upBytes.set(0);
        downBytes.set(0);
        flowsN.set(0);

        startForeground(2, notif());

        try {
            VpnService.Builder b = new VpnService.Builder();
            b.setSession("SockShare")
                    .addAddress("10.8.0.2", 24)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("8.8.8.8")
                    .setMtu(1400);
            try {
                b.addDisallowedApplication(getPackageName()); // socket app sendiri tidak masuk tunnel
            } catch (Exception e) {
            }
            pfd = b.establish();
        } catch (Exception e) {
            lastError = "vpn: " + e.getMessage();
        }
        if (pfd == null) {
            if (lastError.length() == 0) lastError = "VPN tidak bisa dibuat (izin ditolak?)";
            stopSelf();
            return START_NOT_STICKY;
        }
        tunIn = new FileInputStream(pfd.getFileDescriptor());
        tunOut = new FileOutputStream(pfd.getFileDescriptor());
        running = true;
        inst = this;
        requestWifi();
        pool.execute(new Runnable() {
            public void run() {
                loop();
            }
        });
        return START_NOT_STICKY;
    }

    private Notification notif() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("vpn", "VPN", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, "vpn");
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), 0);
        b.setContentTitle("SockShare terhubung")
                .setContentText("Lewat " + srvHost + ":" + srvPort)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pi)
                .setOngoing(true);
        return b.build();
    }

    // ---------------- loop utama TUN ----------------

    private void requestWifi() {
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        wcb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                wifi = n;
            }

            @Override
            public void onLost(Network n) {
                if (n.equals(wifi)) wifi = null;
            }
        };
        try {
            cm.requestNetwork(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wcb);
        } catch (Exception e) {
            lastError = "wifi: " + e.getMessage();
        }
    }

    private void loop() {
        byte[] p = new byte[32768];
        StructPollfd[] fds = new StructPollfd[] {new StructPollfd()};
        try {
            fds[0].fd = tunIn.getFD();
        } catch (IOException e) {
            return;
        }
        fds[0].events = (short) OsConstants.POLLIN;
        while (!stop) {
            try {
                int r = Os.poll(fds, 200); // TUN non-blocking: tunggu data tanpa spin CPU
                if (r <= 0) continue;
                int n = tunIn.read(p);
                if (n < 0) break;
                if (n < 20) continue;
                handle(p, n);
            } catch (ErrnoException e) {
                if (stop) break;
            } catch (IOException e) {
                if (stop) break;
                lastError = "tun: " + e.getMessage();
            } catch (Exception e) {
                lastError = "pkt: " + e;
            }
        }
        if (!stop) cleanup(true);
    }

    private void handle(byte[] p, int n) {
        if ((p[0] >> 4) != 4) return;
        int ihl = (p[0] & 15) * 4;
        if ((u16(p, 6) & 0x3FFF) != 0) return; // fragment tidak didukung
        int proto = p[9] & 255;
        int src = i32(p, 12);
        int dst = i32(p, 16);
        if (proto == 6) tcp(p, n, ihl, src, dst);
        else if (proto == 17) udp(p, n, ihl, src, dst);
    }

    // ---------------- TCP ----------------

    private void tcp(byte[] p, int n, int t, int src, final int dst) {
        final int sp = u16(p, t);
        final int dp = u16(p, t + 2);
        int seq = i32(p, t + 4);
        int ack = i32(p, t + 8);
        int doff = (p[t + 12] & 0xF0) >> 2;
        int fl = p[t + 13] & 0xFF;
        int win = u16(p, t + 14);
        int plen = n - t - doff;
        if (plen < 0) return;
        long key = ((long) sp << 48) | ((long) dp << 32) | (dst & 0xFFFFFFFFL);
        final Flow f = table.get(key);

        if ((fl & 2) != 0 && (fl & 16) == 0) { // SYN
            if (f != null) return; // SYN ulang
            final Flow nf = new Flow(src, sp, dst, dp, key);
            nf.cSeq = seq + 1;
            nf.sSeq = rnd.nextInt();
            nf.sUna = nf.sSeq;
            nf.cWnd = win;
            table.put(key, nf);
            flowsN.incrementAndGet();
            pool.execute(new Runnable() {
                public void run() {
                    open(nf);
                }
            });
            return;
        }
        if (f == null) return;
        if ((fl & 4) != 0) { // RST dari klien
            f.finish();
            return;
        }
        synchronized (f) {
            if (!f.established || f.closed) return;
            f.cWnd = win;
            if ((fl & 16) != 0) {
                if ((ack - f.sUna) >= 0 && (f.sSeq - ack) >= 0) f.sUna = ack;
                f.notifyAll();
            }
            boolean ackNeeded = false;
            if (plen > 0) {
                if (seq == f.cSeq) {
                    f.up.add(Arrays.copyOfRange(p, t + doff, t + doff + plen));
                    f.cSeq += plen;
                }
                ackNeeded = true;
            }
            if ((fl & 1) != 0 && !f.finRcvd && f.cSeq == seq + plen) {
                f.cSeq++;
                f.finRcvd = true;
                f.up.add(EMPTY);
                ackNeeded = true;
            }
            if (ackNeeded) send(f, 16, f.sSeq, f.cSeq, null, 0, 0, false);
        }
    }

    private void open(final Flow f) {
        Socket s;
        try {
            s = socksConnect(f.dstIp, f.dstPort);
        } catch (Exception e) {
            lastError = "connect: " + e.getMessage();
            synchronized (f) {
                send(f, 20, 0, f.cSeq, null, 0, 0, false); // RST|ACK
            }
            f.finish();
            return;
        }
        synchronized (f) {
            f.sock = s;
            f.established = true;
            send(f, 18, f.sSeq, f.cSeq, null, 0, 0, true); // SYN|ACK
            f.sSeq++;
        }
        pool.execute(new Runnable() { // klien -> SOCKS
            public void run() {
                try {
                    OutputStream o = f.sock.getOutputStream();
                    while (true) {
                        byte[] d = f.up.take();
                        if (d.length == 0) {
                            f.sock.shutdownOutput();
                            break;
                        }
                        o.write(d);
                        upBytes.addAndGet(d.length);
                    }
                    synchronized (f) {
                        f.upDone = true;
                    }
                    f.checkDone();
                } catch (Exception e) {
                    f.abort();
                }
            }
        });
        // SOCKS -> klien (di thread ini): baca blok besar, lalu pecah per MSS
        byte[] buf = new byte[MSS * 16];
        try {
            InputStream in = f.sock.getInputStream();
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                int off = 0;
                while (off < n) {
                    int len = Math.min(MSS, n - off);
                    synchronized (f) {
                        while (!f.closed && (f.sSeq - f.sUna) > 0 && (f.sSeq - f.sUna) + len > f.cWnd) {
                            f.wait(500);
                        }
                        if (f.closed) return;
                        send(f, 24, f.sSeq, f.cSeq, buf, off, len, false); // PSH|ACK
                        f.sSeq += len;
                    }
                    off += len;
                }
                downBytes.addAndGet(n);
            }
            synchronized (f) {
                if (f.closed) return;
                send(f, 17, f.sSeq, f.cSeq, null, 0, 0, false); // FIN|ACK
                f.sSeq++;
                f.finSent = true;
            }
            f.checkDone();
        } catch (Exception e) {
            f.abort();
        }
    }

    private class Flow {
        final int srcIp, srcPort, dstIp, dstPort;
        final long key;
        int cSeq, sSeq, sUna, cWnd;
        volatile Socket sock;
        boolean established, closed, finRcvd, finSent, upDone;
        final LinkedBlockingQueue<byte[]> up = new LinkedBlockingQueue<byte[]>();

        Flow(int srcIp, int srcPort, int dstIp, int dstPort, long key) {
            this.srcIp = srcIp;
            this.srcPort = srcPort;
            this.dstIp = dstIp;
            this.dstPort = dstPort;
            this.key = key;
        }

        void finish() {
            synchronized (this) {
                if (closed) return;
                closed = true;
                notifyAll();
            }
            if (table.remove(key) != null) flowsN.decrementAndGet();
            try {
                if (sock != null) sock.close();
            } catch (IOException e) {
            }
            up.offer(EMPTY);
        }

        void abort() {
            synchronized (this) {
                if (closed) return;
                if (established) send(this, 20, sSeq, cSeq, null, 0, 0, false);
            }
            finish();
        }

        void checkDone() {
            boolean d;
            synchronized (this) {
                d = finSent && finRcvd && upDone;
            }
            if (d) finish();
        }
    }

    // ---------------- DNS (UDP 53 -> DNS over TCP via SOCKS5) ----------------

    private void udp(byte[] p, int n, int t, final int src, final int dst) {
        final int sp = u16(p, t);
        int dp = u16(p, t + 2);
        int ulen = u16(p, t + 4);
        int end = Math.min(n, t + ulen);
        if (end <= t + 8) return;
        final byte[] q = Arrays.copyOfRange(p, t + 8, end);
        if (dp != 53) { // voice/game/QUIC: lewat SOCKS5 UDP ASSOCIATE
            udpForward(src, sp, dst, dp, q);
            return;
        }
        pool.execute(new Runnable() {
            public void run() {
                Socket s = null;
                try {
                    s = socksConnect(dst, 53);
                    s.setSoTimeout(8000);
                    OutputStream o = s.getOutputStream();
                    o.write(new byte[] {(byte) (q.length >> 8), (byte) q.length});
                    o.write(q);
                    o.flush();
                    InputStream in = s.getInputStream();
                    int len = (rdb(in) << 8) | rdb(in);
                    byte[] r = readFully(in, len);
                    sendUdp(dst, 53, src, sp, r);
                } catch (Exception e) {
                    lastError = "dns: " + e.getMessage();
                } finally {
                    try {
                        if (s != null) s.close();
                    } catch (IOException e) {
                    }
                }
            }
        });
    }

    private final UdpFlow.Binder udpBinder = new UdpFlow.Binder() {
        public void bindTcp(Socket s) throws IOException {
            Network wn = wifi;
            if (wn != null) wn.bindSocket(s); // paksa lewat WiFi hotspot
        }

        public void bindUdp(DatagramSocket d) throws IOException {
            Network wn = wifi;
            if (wn != null) wn.bindSocket(d);
        }
    };

    private final UdpFlow.Sink udpSink = new UdpFlow.Sink() {
        public void packet(UdpFlow f, int remoteIp, int remotePort, byte[] d, int off, int len) {
            sendUdp(remoteIp, remotePort, f.srcIp, f.srcPort, d);
            downBytes.addAndGet(len);
        }

        public void sent(int n) {
            upBytes.addAndGet(n);
        }

        public void closed(UdpFlow f) {
            udpTable.remove(Integer.valueOf(f.srcPort), f);
        }
    };

    private void udpForward(int src, int sp, int dst, int dp, byte[] data) {
        Integer k = Integer.valueOf(sp);
        UdpFlow f = udpTable.get(k);
        if (f == null || f.isClosed()) {
            if (udpTable.size() >= 256) return;
            UdpFlow nf = new UdpFlow(srvHost, srvPort, user, pass, src, sp, udpBinder, udpSink);
            UdpFlow old = udpTable.putIfAbsent(k, nf);
            if (old != null && !old.isClosed()) {
                f = old;
            } else {
                if (old != null) udpTable.put(k, nf);
                f = nf;
                nf.start(pool);
            }
        }
        f.send(dst, dp, data);
    }

    private void sendUdp(int sIp, int sPort, int dIp, int dPort, byte[] d) {
        int total = 28 + d.length;
        byte[] b = new byte[total];
        b[0] = 0x45;
        put16(b, 2, total);
        b[6] = 0x40;
        b[8] = 64;
        b[9] = 17;
        put32(b, 12, sIp);
        put32(b, 16, dIp);
        put16(b, 10, cksum(b, 0, 20, 0));
        put16(b, 20, sPort);
        put16(b, 22, dPort);
        put16(b, 24, 8 + d.length);
        System.arraycopy(d, 0, b, 28, d.length); // checksum UDP = 0 (valid untuk IPv4)
        write(b);
    }

    // ---------------- SOCKS5 klien ----------------

    private Socket socksConnect(int ip, int port) throws IOException {
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            Network wn = wifi;
            if (wn != null) wn.bindSocket(s); // paksa lewat WiFi hotspot
            s.connect(new InetSocketAddress(srvHost, srvPort), 8000);
            s.setSoTimeout(12000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            boolean auth = user.length() > 0;
            // pipeline: greeting + auth + request dikirim sekaligus (server membacanya berurutan)
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (auth) {
                byte[] u = user.getBytes("UTF-8");
                byte[] w = pass.getBytes("UTF-8");
                bo.write(new byte[] {5, 1, 2}, 0, 3);
                bo.write(1);
                bo.write(u.length);
                bo.write(u, 0, u.length);
                bo.write(w.length);
                bo.write(w, 0, w.length);
            } else {
                bo.write(new byte[] {5, 1, 0}, 0, 3);
            }
            byte[] rq = new byte[] {5, 1, 0, 1,
                    (byte) (ip >>> 24), (byte) (ip >>> 16), (byte) (ip >>> 8), (byte) ip,
                    (byte) (port >> 8), (byte) port};
            bo.write(rq, 0, rq.length);
            out.write(bo.toByteArray());
            out.flush();
            if (rdb(in) != 5) throw new IOException("bukan SOCKS5");
            int m = rdb(in);
            if (m != (auth ? 2 : 0)) throw new IOException("metode auth ditolak");
            if (auth) {
                rdb(in);
                if (rdb(in) != 0) throw new IOException("username/password salah");
            }
            rdb(in);
            int rep = rdb(in);
            rdb(in);
            int at = rdb(in);
            int sk = (at == 1) ? 6 : (at == 4) ? 18 : rdb(in) + 2;
            readFully(in, sk);
            if (rep != 0) throw new IOException("SOCKS reply " + rep);
            s.setSoTimeout(0);
            return s;
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException x) {
            }
            throw e;
        }
    }

    // ---------------- bangun paket ----------------

    private void send(Flow f, int flags, int seq, int ack, byte[] d, int off, int len, boolean mss) {
        int th = mss ? 24 : 20;
        int total = 20 + th + len;
        byte[] b = new byte[total];
        b[0] = 0x45;
        put16(b, 2, total);
        b[6] = 0x40;
        b[8] = 64;
        b[9] = 6;
        put32(b, 12, f.dstIp);
        put32(b, 16, f.srcIp);
        put16(b, 10, cksum(b, 0, 20, 0));
        int t = 20;
        put16(b, t, f.dstPort);
        put16(b, t + 2, f.srcPort);
        put32(b, t + 4, seq);
        put32(b, t + 8, ack);
        b[t + 12] = (byte) ((th / 4) << 4);
        b[t + 13] = (byte) flags;
        put16(b, t + 14, 65535);
        if (mss) {
            b[t + 20] = 2;
            b[t + 21] = 4;
            put16(b, t + 22, MSS);
        }
        if (len > 0) System.arraycopy(d, off, b, t + th, len);
        long pseudo = ((f.dstIp >>> 16) & 0xFFFF) + (f.dstIp & 0xFFFF)
                + ((f.srcIp >>> 16) & 0xFFFF) + (f.srcIp & 0xFFFF) + 6 + th + len;
        put16(b, t + 16, cksum(b, t, th + len, pseudo));
        write(b);
    }

    private void write(byte[] b) {
        try {
            synchronized (tunOut) {
                tunOut.write(b);
            }
        } catch (IOException e) {
            if (!stop) lastError = "write: " + e.getMessage();
        }
    }

    private static int cksum(byte[] b, int off, int len, long init) {
        long sum = init;
        int i = off;
        int end = off + len;
        while (i + 1 < end) {
            sum += ((b[i] & 255) << 8) | (b[i + 1] & 255);
            i += 2;
        }
        if (i < end) sum += (b[i] & 255) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }

    private static int u16(byte[] b, int o) {
        return ((b[o] & 255) << 8) | (b[o + 1] & 255);
    }

    private static int i32(byte[] b, int o) {
        return ((b[o] & 255) << 24) | ((b[o + 1] & 255) << 16) | ((b[o + 2] & 255) << 8) | (b[o + 3] & 255);
    }

    private static void put16(byte[] b, int o, int v) {
        b[o] = (byte) (v >> 8);
        b[o + 1] = (byte) v;
    }

    private static void put32(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }

    private static int rdb(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int o = 0;
        while (o < n) {
            int k = in.read(b, o, n - o);
            if (k < 0) throw new EOFException();
            o += k;
        }
        return b;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ---------------- stop ----------------

    public static void requestStop() {
        VpnClientService x = inst;
        if (x != null) x.cleanup(true);
    }

    private synchronized void cleanup(boolean stopSvc) {
        stop = true;
        running = false;
        inst = null;
        ArrayList<Flow> all = new ArrayList<Flow>(table.values());
        for (Flow f : all) f.finish();
        ArrayList<UdpFlow> ua = new ArrayList<UdpFlow>(udpTable.values());
        for (UdpFlow u : ua) u.close();
        udpTable.clear();
        try {
            if (cm != null && wcb != null) cm.unregisterNetworkCallback(wcb);
        } catch (Exception e) {
        }
        wcb = null;
        wifi = null;
        try {
            if (pfd != null) pfd.close(); // menutup TUN = melepas VPN
        } catch (IOException e) {
        }
        pfd = null;
        pool.shutdownNow();
        if (stopSvc) {
            stopForeground(true);
            stopSelf();
        }
    }

    @Override
    public void onRevoke() {
        cleanup(true);
    }

    @Override
    public void onDestroy() {
        cleanup(false);
        super.onDestroy();
    }
}
