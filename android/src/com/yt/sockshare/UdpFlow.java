package com.yt.sockshare;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;

/**
 * Satu "flow" UDP lewat SOCKS5 UDP ASSOCIATE ke HP sumber (tanpa dependensi Android).
 * Satu flow = satu port UDP lokal di HP penerima (mis. socket voice Discord).
 * Kontrol TCP dibiarkan terbuka selama flow hidup; ditutup saat idle / error.
 */
public class UdpFlow {

    public interface Sink {
        /** Paket balasan dari server remote (IPv4). */
        void packet(UdpFlow f, int remoteIp, int remotePort, byte[] d, int off, int len);

        void sent(int n);

        void closed(UdpFlow f);
    }

    public interface Binder {
        void bindTcp(Socket s) throws IOException;

        void bindUdp(DatagramSocket d) throws IOException;
    }

    public static final int MAX_PAYLOAD = 1372;
    private static final long IDLE_MS = 60000;

    public final int srcIp;
    public final int srcPort;
    private final String host;
    private final int port;
    private final String user;
    private final String pass;
    private final Binder binder;
    private final Sink sink;

    private volatile boolean ready = false;
    private volatile boolean closed = false;
    private volatile long last = System.currentTimeMillis();
    private final ArrayList<byte[]> pend = new ArrayList<byte[]>();
    private Socket ctl;
    private DatagramSocket ds;
    private InetAddress srvAddr;
    private int relayPort;

    public UdpFlow(String host, int port, String user, String pass, int srcIp, int srcPort,
                   Binder binder, Sink sink) {
        this.host = host;
        this.port = port;
        this.user = user == null ? "" : user;
        this.pass = pass == null ? "" : pass;
        this.srcIp = srcIp;
        this.srcPort = srcPort;
        this.binder = binder;
        this.sink = sink;
    }

    public boolean isClosed() {
        return closed;
    }

    public void start(ExecutorService pool) {
        pool.execute(new Runnable() {
            public void run() {
                runFlow();
            }
        });
    }

    /** Kirim satu datagram ke (dstIp:dport) lewat HP sumber. */
    public void send(int dstIp, int dport, byte[] data) {
        if (data.length > MAX_PAYLOAD) return;
        last = System.currentTimeMillis();
        byte[] w = new byte[10 + data.length];
        w[3] = 1;
        put32(w, 4, dstIp);
        w[8] = (byte) (dport >> 8);
        w[9] = (byte) dport;
        System.arraycopy(data, 0, w, 10, data.length);
        synchronized (this) {
            if (closed) return;
            if (!ready) {
                if (pend.size() < 64) pend.add(w);
                return;
            }
        }
        out(w);
    }

    private void out(byte[] w) {
        try {
            ds.send(new DatagramPacket(w, w.length, srvAddr, relayPort));
            sink.sent(w.length - 10);
        } catch (IOException e) {
            close();
        }
    }

    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            pend.clear();
        }
        try {
            if (ds != null) ds.close();
        } catch (Exception e) {
        }
        try {
            if (ctl != null) ctl.close();
        } catch (Exception e) {
        }
        sink.closed(this);
    }

    private void runFlow() {
        try {
            Socket s = new Socket();
            ctl = s;
            s.setTcpNoDelay(true);
            binder.bindTcp(s);
            s.connect(new InetSocketAddress(host, port), 8000);
            s.setSoTimeout(12000);
            InputStream in = s.getInputStream();
            OutputStream o = s.getOutputStream();
            boolean auth = user.length() > 0;

            // pipeline: greeting + auth + UDP ASSOCIATE (0.0.0.0:0)
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
            byte[] rq = new byte[] {5, 3, 0, 1, 0, 0, 0, 0, 0, 0};
            bo.write(rq, 0, rq.length);
            o.write(bo.toByteArray());
            o.flush();

            if (rd(in) != 5) throw new IOException("bukan SOCKS5");
            if (rd(in) != (auth ? 2 : 0)) throw new IOException("metode auth ditolak");
            if (auth) {
                rd(in);
                if (rd(in) != 0) throw new IOException("username/password salah");
            }
            rd(in);
            int rep = rd(in);
            rd(in);
            int at = rd(in);
            int sk = (at == 1) ? 6 : (at == 4) ? 18 : rd(in) + 2;
            byte[] tail = readFully(in, sk);
            if (rep != 0) throw new IOException("UDP ASSOCIATE ditolak (" + rep + ")");
            relayPort = ((tail[sk - 2] & 255) << 8) | (tail[sk - 1] & 255);
            s.setSoTimeout(0);

            srvAddr = InetAddress.getByName(host);
            ds = new DatagramSocket();
            binder.bindUdp(ds);
            ds.setSoTimeout(1000);

            ArrayList<byte[]> flush;
            synchronized (this) {
                if (closed) return;
                ready = true;
                flush = new ArrayList<byte[]>(pend);
                pend.clear();
            }
            for (int i = 0; i < flush.size(); i++) out(flush.get(i));

            byte[] buf = new byte[4096];
            DatagramPacket dp = new DatagramPacket(buf, buf.length);
            while (!closed) {
                dp.setLength(buf.length);
                try {
                    ds.receive(dp);
                } catch (SocketTimeoutException e) {
                    if (System.currentTimeMillis() - last > IDLE_MS) break;
                    continue;
                }
                last = System.currentTimeMillis();
                int n = dp.getLength();
                if (n < 11 || buf[2] != 0 || buf[3] != 1) continue; // hanya IPv4, tanpa fragmen
                int rip = ((buf[4] & 255) << 24) | ((buf[5] & 255) << 16) | ((buf[6] & 255) << 8) | (buf[7] & 255);
                int rport = ((buf[8] & 255) << 8) | (buf[9] & 255);
                int dl = n - 10;
                if (dl > MAX_PAYLOAD) continue;
                byte[] d = new byte[dl];
                System.arraycopy(buf, 10, d, 0, dl);
                sink.packet(this, rip, rport, d, 0, dl);
            }
        } catch (Exception e) {
            // gagal / ditutup: flow dibuang, aplikasi akan kirim ulang -> flow baru
        } finally {
            close();
        }
    }

    private static void put32(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }

    private static int rd(InputStream in) throws IOException {
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
}
