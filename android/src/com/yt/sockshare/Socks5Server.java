package com.yt.sockshare;

import android.net.Network;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
                if (!(u.equals(user) && p.equals(pass))) {
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

            if (cmd != 1) { // UDP ASSOCIATE / BIND belum didukung
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

    private Socket connect(String host, int dport) throws IOException {
        Network net = ProxyService.cellular;
        InetAddress[] addrs;
        if (net != null) {
            addrs = net.getAllByName(host);
        } else {
            addrs = InetAddress.getAllByName(host);
        }
        IOException last = null;
        for (int i = 0; i < addrs.length; i++) {
            Socket s = new Socket();
            try {
                if (net != null) net.bindSocket(s);
                s.setTcpNoDelay(true);
                s.setReceiveBufferSize(262144);
                s.setSendBufferSize(262144);
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
            } catch (IOException e) {
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
