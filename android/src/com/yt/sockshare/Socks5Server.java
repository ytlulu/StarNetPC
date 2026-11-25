package com.yt.sockshare;

import android.net.Network;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** SOCKS5 (CONNECT only) - koneksi keluar di-bind ke jaringan seluler. */
public class Socks5Server {

    /** Ukuran buffer socket (byte) — tetap maksimal untuk kecepatan. */
    private static final int BUF = 262144;

    private final int port;
    private final String user;
    private final String pass;
    private ServerSocket ss;
    private volatile boolean stopped = false;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Set<Socket> open = Collections.synchronizedSet(new HashSet<Socket>());

    public Socks5Server(int port, String user, String pass) {
        this.port = port;
        this.user = user == null ? "" : user;
        this.pass = pass == null ? "" : pass;
    }

    public void start() throws IOException {
        ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(port));
        pool.execute(new Runnable() {
            public void run() {
                acceptLoop();
            }
        });
    }

    public void stop() {
        stopped = true;
        try {
            if (ss != null) ss.close();
        } catch (IOException e) {
        }
        ArrayList<Socket> copy;
        synchronized (open) {
            copy = new ArrayList<Socket>(open);
        }
        for (Socket s : copy) close(s);
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (!stopped && !ss.isClosed()) {
            try {
                final Socket c = ss.accept();
                pool.execute(new Runnable() {
                    public void run() {
                        handle(c);
                    }
                });
            } catch (IOException e) {
                if (stopped || ss.isClosed()) break;
                ProxyService.lastError = "accept: " + e.getMessage();
            }
        }
    }

    private void handle(Socket c) {
        Socket r = null;
        open.add(c);
        ProxyService.conns.incrementAndGet();
        try {
            c.setTcpNoDelay(true);
            c.setSoTimeout(15000); // timeout handshake
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();

            // --- greeting ---
            if (rd(in) != 5) return;
            int nm = rd(in);
            byte[] methods = readFully(in, nm);
            boolean needAuth = user.length() > 0;
            int want = needAuth ? 2 : 0;
            boolean ok = false;
            for (int i = 0; i < methods.length; i++) {
                if (methods[i] == want) ok = true;
            }
            if (!ok) {
                out.write(new byte[] {5, (byte) 0xFF});
                out.flush();
                return;
            }
            out.write(new byte[] {5, (byte) want});
            out.flush();

            // --- auth (RFC 1929) ---
            if (needAuth) {
                if (rd(in) != 1) return;
                int ul = rd(in);
                String u = new String(readFully(in, ul), "UTF-8");
                int pl = rd(in);
                String p = new String(readFully(in, pl), "UTF-8");
                // "sockshare" = password lama (HP/PC versi sebelumnya tetap bisa sambung)
                if (!(u.equals(user) && (p.equals(pass) || p.equals("sockshare")))) {
                    out.write(new byte[] {1, 1});
                    out.flush();
                    return;
                }
                out.write(new byte[] {1, 0});
                out.flush();
            }

            // --- request ---
            if (rd(in) != 5) return;
            int cmd = rd(in);
            rd(in); // RSV
            int atyp = rd(in);
            String host;
            if (atyp == 1) {
                host = InetAddress.getByAddress(readFully(in, 4)).getHostAddress();
            } else if (atyp == 3) {
                int l = rd(in);
                host = new String(readFully(in, l), "UTF-8");
            } else if (atyp == 4) {
                host = InetAddress.getByAddress(readFully(in, 16)).getHostAddress();
            } else {
                reply(out, 8);
                return;
            }
            int dport = (rd(in) << 8) | rd(in);

            if (cmd == 3) { // UDP ASSOCIATE (voice Discord, game, QUIC, dll)
                udpAssociate(c, out);
                return;
            }
            if (cmd != 1) { // BIND belum didukung
                reply(out, 7);
                return;
            }

            try {
                r = connect(host, dport);
            } catch (IOException e) {
                reply(out, 4);
                return;
            }
            open.add(r);
            reply(out, 0);
            c.setSoTimeout(0);

            pool.execute(new Pump(r, c, true));  // internet -> penerima = download
            new Pump(c, r, false).run();          // penerima -> internet = upload (blok sampai selesai)
        } catch (Exception e) {
            // koneksi putus / handshake tidak valid
        } finally {
            close(c);
            close(r);
            open.remove(c);
            if (r != null) open.remove(r);
            ProxyService.conns.decrementAndGet();
        }
    }

    // ---------------- UDP ASSOCIATE ----------------

    /**
     * Relay UDP: klien kirim datagram berbungkus header SOCKS5 ke port relay;
     * diteruskan lewat socket yang di-bind ke jaringan seluler. Hidup selama TCP kontrol terbuka.
     */
    private void udpAssociate(final Socket c, OutputStream out) throws IOException {
        final DatagramSocket relay = new DatagramSocket();
        final DatagramSocket outS = new DatagramSocket();
        try {
            Network net = ProxyService.cellular;
            if (net != null) net.bindSocket(outS);
            relay.setReceiveBufferSize(262144);
            outS.setReceiveBufferSize(262144);
            final int rport = relay.getLocalPort();
            out.write(new byte[] {5, 0, 0, 1, 0, 0, 0, 0, (byte) (rport >> 8), (byte) rport});
            out.flush();
            c.setSoTimeout(0);

            final InetAddress clientIp = c.getInetAddress();
            final InetAddress[] cAddr = new InetAddress[1];
            final int[] cPort = new int[1];

            // klien -> internet
            pool.execute(new Runnable() {
                public void run() {
                    byte[] buf = new byte[65535];
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        while (!stopped) {
                            p.setLength(buf.length);
                            relay.receive(p);
                            if (!p.getAddress().equals(clientIp)) continue;
                            cAddr[0] = p.getAddress();
                            cPort[0] = p.getPort();
                            int n = p.getLength();
                            if (n < 10 || buf[2] != 0) continue; // fragmen tidak didukung
                            int atyp = buf[3];
                            int o;
                            InetAddress dst;
                            if (atyp == 1) {
                                dst = InetAddress.getByAddress(java.util.Arrays.copyOfRange(buf, 4, 8));
                                o = 8;
                            } else if (atyp == 4) {
                                if (n < 22) continue;
                                dst = InetAddress.getByAddress(java.util.Arrays.copyOfRange(buf, 4, 20));
                                o = 20;
                            } else if (atyp == 3) {
                                int l = buf[4] & 255;
                                if (n < 7 + l) continue;
                                dst = resolve(new String(buf, 5, l, "UTF-8"))[0];
                                o = 5 + l;
                            } else {
                                continue;
                            }
                            int dp = ((buf[o] & 255) << 8) | (buf[o + 1] & 255);
                            o += 2;
                            if (n - o <= 0) continue;
                            outS.send(new DatagramPacket(buf, o, n - o, dst, dp));
                            ProxyService.upBytes.addAndGet(n - o);
                        }
                    } catch (Exception e) {
                        // ditutup
                    }
                }
            });

            // internet -> klien
            pool.execute(new Runnable() {
                public void run() {
                    byte[] buf = new byte[65535];
                    DatagramPacket p = new DatagramPacket(buf, 22, buf.length - 22);
                    try {
                        while (!stopped) {
                            p.setData(buf, 22, buf.length - 22);
                            outS.receive(p);
                            InetAddress ca = cAddr[0];
                            if (ca == null) continue;
                            byte[] a = p.getAddress().getAddress();
                            int n = p.getLength();
                            int hl = (a.length == 4) ? 10 : 22;
                            int st = 22 - hl;
                            buf[st] = 0;
                            buf[st + 1] = 0;
                            buf[st + 2] = 0;
                            buf[st + 3] = (byte) (a.length == 4 ? 1 : 4);
                            System.arraycopy(a, 0, buf, st + 4, a.length);
                            buf[st + 4 + a.length] = (byte) (p.getPort() >> 8);
                            buf[st + 5 + a.length] = (byte) p.getPort();
                            relay.send(new DatagramPacket(buf, st, hl + n, ca, cPort[0]));
                            ProxyService.downBytes.addAndGet(n);
                        }
                    } catch (Exception e) {
                        // ditutup
                    }
                }
            });

            // tahan sampai klien menutup TCP kontrol
            InputStream in = c.getInputStream();
            byte[] sink = new byte[64];
            while (in.read(sink) >= 0) {
            }
        } finally {
            try {
                relay.close();
            } catch (Exception e) {
            }
            try {
                outS.close();
            } catch (Exception e) {
            }
        }
    }

    // ---------------- DNS cache (hemat 1 lookup per koneksi baru) ----------------

    private final java.util.concurrent.ConcurrentHashMap<String, Object[]> dns =
            new java.util.concurrent.ConcurrentHashMap<String, Object[]>();

    private InetAddress[] resolve(String host) throws IOException {
        long now = System.currentTimeMillis();
        Object[] e = dns.get(host);
        if (e != null && ((Long) e[0]).longValue() > now) return (InetAddress[]) e[1];
        Network net = ProxyService.cellular;
        InetAddress[] addrs = (net != null) ? net.getAllByName(host) : InetAddress.getAllByName(host);
        // IPv4 dulu (jaringan seluler sering tanpa IPv6 -> hindari timeout 8 detik)
        InetAddress[] sorted = new InetAddress[addrs.length];
        int k = 0;
        for (int i = 0; i < addrs.length; i++) if (addrs[i].getAddress().length == 4) sorted[k++] = addrs[i];
        for (int i = 0; i < addrs.length; i++) if (addrs[i].getAddress().length != 4) sorted[k++] = addrs[i];
        if (dns.size() > 512) dns.clear();
        dns.put(host, new Object[] {Long.valueOf(now + 30000), sorted});
        return sorted;
    }

    private Socket connect(String host, int dport) throws IOException {
        Network net = ProxyService.cellular;
        InetAddress[] addrs = resolve(host);
        IOException last = null;
        for (int i = 0; i < addrs.length; i++) {
            Socket s = new Socket();
            try {
                if (net != null) net.bindSocket(s);
                s.setTcpNoDelay(true);
                s.setReceiveBufferSize(BUF);
                s.setSendBufferSize(BUF);
                s.connect(new InetSocketAddress(addrs[i], dport), 8000);
                return s;
            } catch (IOException e) {
                last = e;
                close(s);
            }
        }
        throw last != null ? last : new IOException("no address");
    }

    private static class Pump implements Runnable {
        private final Socket from;
        private final Socket to;
        private final boolean down;

        Pump(Socket from, Socket to, boolean down) {
            this.from = from;
            this.to = to;
            this.down = down;
        }

        public void run() {
            byte[] buf = new byte[65536];
            try {
                InputStream i = from.getInputStream();
                OutputStream o = to.getOutputStream();
                int n;
                while ((n = i.read(buf)) > 0) {
                    o.write(buf, 0, n);
                    if (down) ProxyService.downBytes.addAndGet(n);
                    else ProxyService.upBytes.addAndGet(n);
                }
            } catch (Exception e) {
            } finally {
                close(from);
                close(to);
            }
        }
    }

    private static void reply(OutputStream o, int code) throws IOException {
        o.write(new byte[] {5, (byte) code, 0, 1, 0, 0, 0, 0, 0, 0});
        o.flush();
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

    private static void close(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException e) {
        }
    }
}
