package com.yt.sockshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collection;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ProxyService extends Service {

    // status untuk UI (satu proses, cukup static)
    public static final int PORT = 1080;
    public static final String USER = "yt";
    public static final String PASS = "starnett";

    private static volatile ProxyService inst = null;
    public static volatile boolean running = false;
    public static volatile Network cellular = null;
    public static volatile String lastError = "";
    public static final AtomicInteger conns = new AtomicInteger();
    public static final AtomicLong upBytes = new AtomicLong();   // dari HP penerima -> internet
    public static final AtomicLong downBytes = new AtomicLong(); // dari internet -> HP penerima

    public static volatile String wifiName = "";
    public static volatile String wifiPass = "";
    private WifiP2pManager p2p;
    private WifiP2pManager.Channel ch;
    private final Handler hd = new Handler(Looper.getMainLooper());

    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback cb;
    private Socks5Server server;
    private PowerManager.WakeLock wl;
    private WifiManager.WifiLock wfl;
    private final Meter meter = new Meter();
    public static final Ping ping = new Ping();      // latensi internet lewat seluler
    public static volatile String clients = "";      // nama perangkat yang tersambung (WiFi Direct)
    public static volatile int clientCount = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || running) return START_NOT_STICKY;

        int port = PORT;
        String user = USER;
        String pass = PASS;
        String wn = intent.getStringExtra("wname");
        String wp = intent.getStringExtra("wpass");
        if (wn != null && wn.length() > 0) fixedName = wn;
        if (wp != null && wp.length() >= 8) fixedPass = wp;

        lastError = "";
        conns.set(0);
        upBytes.set(0);
        downBytes.set(0);

        startForeground(1, buildNotification(port));

        applyMode();
        ping.reset();
        clients = "";
        clientCount = 0;

        requestCellular();

        try {
            server = new Socks5Server(port, user, pass);
            server.start();
            running = true;
            inst = this;
            startGroup();
            startTicker();
        } catch (Exception e) {
            lastError = "server: " + e.getMessage();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void requestCellular() {
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        cb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                cellular = n;
            }

            @Override
            public void onLost(Network n) {
                if (n.equals(cellular)) cellular = null;
            }
        };
        try {
            cm.requestNetwork(req, cb);
        } catch (Exception e) {
            lastError = "cellular: " + e.getMessage();
        }
    }

    /** Terapkan wake lock / WiFi lock sesuai mode (dipanggil saat start & ganti mode). */
    public static void applyModeNow() {
        ProxyService x = inst;
        if (x != null) x.applyMode();
    }

    private synchronized void applyMode() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (Modes.wantWakeLock()) {
                if (wl == null) wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "starnett:proxy");
                if (!wl.isHeld()) wl.acquire();
            } else if (wl != null && wl.isHeld()) {
                wl.release();
            }
            if (Modes.wantWifiLock()) {
                if (wfl == null) {
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    int m = Build.VERSION.SDK_INT >= 29 ? 4 : WifiManager.WIFI_MODE_FULL_HIGH_PERF; // 4 = low latency
                    wfl = wm.createWifiLock(m, "starnett:wifi");
                }
                if (!wfl.isHeld()) wfl.acquire();
            } else if (wfl != null && wfl.isHeld()) {
                wfl.release();
            }
        } catch (Exception e) {
            lastError = "mode: " + e.getMessage();
        }
    }

    // Ticker: ping internet (seluler), nama perangkat, notifikasi hidup, batas baterai mode Hemat
    private void startTicker() {
        final Thread pt = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    Network net = cellular;
                    long t0 = System.nanoTime();
                    Socket s = new Socket();
                    int ms = -1;
                    try {
                        if (net != null) net.bindSocket(s);
                        s.connect(new InetSocketAddress("1.1.1.1", 443), 3000);
                        ms = (int) ((System.nanoTime() - t0) / 1000000L);
                    } catch (Exception e) {
                    } finally {
                        try {
                            s.close();
                        } catch (Exception e) {
                        }
                    }
                    ping.add(ms);
                    try {
                        Thread.sleep(4000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        });
        pt.setDaemon(true);
        pt.start();
        hd.postDelayed(new Runnable() {
            public void run() {
                if (!running) return;
                meter.update(upBytes.get(), downBytes.get());
                refreshClients();
                updateNotification();
                if (Modes.get() == Modes.HEMAT && batteryLow()) {
                    lastError = "Berhenti otomatis: baterai di bawah " + Modes.HEMAT_BATTERY_STOP + "% (mode Hemat).";
                    cleanup(true);
                    return;
                }
                hd.postDelayed(this, 2000);
            }
        }, 2000);
    }

    private boolean batteryLow() {
        try {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return false;
            int lvl = b.getIntExtra("level", -1);
            int sc = b.getIntExtra("scale", 100);
            int status = b.getIntExtra("status", -1);
            boolean charging = status == 2 || status == 5; // CHARGING / FULL
            return !charging && lvl >= 0 && (lvl * 100 / sc) < Modes.HEMAT_BATTERY_STOP;
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshClients() {
        if (p2p == null || ch == null) return;
        try {
            p2p.requestGroupInfo(ch, new WifiP2pManager.GroupInfoListener() {
                public void onGroupInfoAvailable(WifiP2pGroup g) {
                    if (g == null) {
                        clients = "";
                        clientCount = 0;
                        return;
                    }
                    Collection<android.net.wifi.p2p.WifiP2pDevice> l = g.getClientList();
                    StringBuilder sb = new StringBuilder();
                    int c = 0;
                    if (l != null) {
                        for (android.net.wifi.p2p.WifiP2pDevice d : l) {
                            if (c > 0) sb.append(", ");
                            String nm = d.deviceName;
                            if (nm == null || nm.length() == 0) nm = d.deviceAddress;
                            sb.append(nm);
                            c++;
                        }
                    }
                    clients = sb.toString();
                    clientCount = c;
                }
            });
        } catch (Exception e) {
        }
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(1, buildNotification(PORT));
        } catch (Exception e) {
        }
    }

    private Notification buildNotification(int port) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("proxy", "Proxy", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, "proxy");
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);
        String line1 = "\u2193 " + Fmt.speed(meter.downSpeed) + "  \u2191 " + Fmt.speed(meter.upSpeed)
                + "  \u00b7  " + Ping.text(ping.avg());
        String line2 = (clientCount > 0 ? clientCount + " perangkat: " + clients : "Menunggu perangkat tersambung")
                + "\nTotal " + Fmt.size(upBytes.get() + downBytes.get()) + "  \u00b7  port " + port;
        b.setContentTitle("StarNett aktif \u00b7 " + Modes.name(Modes.get()))
                .setContentText(line1)
                .setStyle(new Notification.BigTextStyle().bigText(line1 + "\n" + line2))
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setOngoing(true);
        return b.build();
    }

    // WiFi Direct: HP ini jadi group owner (bukan hotspot bawaan), IP tetap 192.168.49.1
    private void startGroup() {
        try {
            p2p = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
            ch = p2p.initialize(this, Looper.getMainLooper(), null);
            p2p.removeGroup(ch, new WifiP2pManager.ActionListener() {
                public void onSuccess() {
                    createGroup();
                }

                public void onFailure(int r) {
                    createGroup();
                }
            });
        } catch (Exception e) {
            lastError = "p2p: " + e.getMessage();
        }
    }

    // Nama & password tetap (Android 10+) supaya HP B otomatis tersambung lagi
    private String fixedName = "DIRECT-SS-StarNett";
    private String fixedPass = "starnett123";

    private boolean createFixed(WifiP2pManager.ActionListener l) {
        if (Build.VERSION.SDK_INT < 29) {
            lastError = "Nama WiFi custom butuh Android 10+; HP ini pakai nama WiFi Direct bawaan sistem.";
            return false;
        }
        try {
            Class<?> bc = Class.forName("android.net.wifi.p2p.WifiP2pConfig$Builder");
            Object b = bc.newInstance();
            bc.getMethod("setNetworkName", String.class).invoke(b, fixedName);
            bc.getMethod("setPassphrase", String.class).invoke(b, fixedPass);
            Object cfg = bc.getMethod("build").invoke(b);
            Method m = WifiP2pManager.class.getMethod("createGroup",
                    WifiP2pManager.Channel.class, WifiP2pConfig.class, WifiP2pManager.ActionListener.class);
            m.invoke(p2p, ch, cfg, l);
            return true;
        } catch (Exception e) {
            lastError = "Nama WiFi custom ditolak sistem (" + e.getClass().getSimpleName()
                    + "); pakai nama WiFi Direct bawaan HP.";
            return false;
        }
    }

    private void createGroup() {
        if (!running || p2p == null) return;
        WifiP2pManager.ActionListener l = new WifiP2pManager.ActionListener() {
            public void onSuccess() {
                readGroup(0);
            }

            public void onFailure(int r) {
                lastError = "WiFi Direct gagal (kode " + r + "). Nyalakan WiFi dan Lokasi.";
            }
        };
        try {
            if (!createFixed(l)) p2p.createGroup(ch, l);
        } catch (Exception e) {
            lastError = "p2p: " + e.getMessage();
        }
    }

    private void readGroup(final int tries) {
        if (!running || p2p == null) return;
        p2p.requestGroupInfo(ch, new WifiP2pManager.GroupInfoListener() {
            public void onGroupInfoAvailable(WifiP2pGroup g) {
                if (g != null && g.getPassphrase() != null) {
                    wifiName = g.getNetworkName();
                    wifiPass = g.getPassphrase();
                } else if (tries < 20) {
                    hd.postDelayed(new Runnable() {
                        public void run() {
                            readGroup(tries + 1);
                        }
                    }, 500);
                }
            }
        });
    }

    private void stopGroup() {
        wifiName = "";
        wifiPass = "";
        hd.removeCallbacksAndMessages(null);
        try {
            if (p2p != null && ch != null) p2p.removeGroup(ch, null);
        } catch (Exception e) {
        }
        p2p = null;
        ch = null;
    }

    public static void requestStop() {
        ProxyService x = inst;
        if (x != null) x.cleanup(true);
    }

    private synchronized void cleanup(boolean stopSvc) {
        running = false;
        inst = null;
        stopGroup();
        if (server != null) {
            server.stop();
            server = null;
        }
        try {
            if (cm != null && cb != null) cm.unregisterNetworkCallback(cb);
        } catch (Exception e) {
        }
        cb = null;
        cellular = null;
        if (wl != null && wl.isHeld()) wl.release();
        if (wfl != null && wfl.isHeld()) wfl.release();
        if (stopSvc) {
            stopForeground(true);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        cleanup(false);
        super.onDestroy();
    }
}
