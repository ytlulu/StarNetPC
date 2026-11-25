package com.yt.sockshare;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.DhcpInfo;
import android.net.VpnService;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    // Warna tema StarDevNett
    private static final int ACCENT = Color.parseColor("#E0A94A");
    private static final int ACCENT_TEXT = Color.parseColor("#1A0F0A");
    private static final int TEXT_MAIN = Color.parseColor("#F4F2EE");
    private static final int TEXT_MUTED = Color.parseColor("#B9B4AC");
    private static final int CARD_BG = Color.parseColor("#9E0D1117");
    private static final int CARD_STROKE = Color.parseColor("#29FFFFFF");
    private static final int FIELD_BG = Color.parseColor("#14FFFFFF");
    private static final int FIELD_STROKE = Color.parseColor("#29FFFFFF");
    private static final int GHOST_BG = Color.parseColor("#0FFFFFFF");
    private static final int GHOST_STROKE = Color.parseColor("#2EFFFFFF");

    private Button btnS, btnC;
    private EditText etName, etPass;
    private SharedPreferences sp;
    private TextView tv;
    private TextView tvConnVal, tvTotalVal;
    private TextView tvPingVal, tvPingSub, tvModeDesc;
    private final Button[] modeBtn = new Button[3];
    private TextView tvDownVal, tvDownSub, tvUpVal, tvUpSub;
    private final Meter meter = new Meter();
    private boolean wasActive = false;
    private long shownUp = 0, shownDown = 0;
    private final Handler h = new Handler();
    private final Runnable tick = new Runnable() {
        public void run() {
            refresh();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Modes.set(sp().getInt("mode", Modes.SEIMBANG));
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);

        // --- root: background foto + overlay gradasi ---
        FrameLayout root = new FrameLayout(this);

        ImageView bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setImageResource(R.drawable.bg_main);
        root.addView(bg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View overlay = new View(this);
        overlay.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {Color.parseColor("#B8060914"), Color.parseColor("#2E060914"),
                        Color.parseColor("#4D060914"), Color.parseColor("#E6060914")}));
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, (int) (28 * density), pad, pad);
        content.setMinimumHeight(ViewGroup.LayoutParams.MATCH_PARENT);
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- header ---
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo_lotus);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final float logoRadius = 10 * density;
        logo.setOutlineProvider(new android.view.ViewOutlineProvider() {
            public void getOutline(View v, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), logoRadius);
            }
        });
        logo.setClipToOutline(true);
        int logoSize = (int) (34 * density);
        header.addView(logo, new LinearLayout.LayoutParams(logoSize, logoSize));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding((int) (10 * density), 0, 0, 0);
        TextView title = new TextView(this);
        title.setText("StarDevNett");
        title.setTextColor(TEXT_MAIN);
        title.setTextSize(17);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        TextView sub = new TextView(this);
        sub.setText("Berbagi data seluler antar HP");
        sub.setTextColor(TEXT_MUTED);
        sub.setTextSize(11);
        titles.addView(title);
        titles.addView(sub);
        header.addView(titles);
        content.addView(header);

        View spacer = new View(this);
        content.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (140 * density)));

        // --- kartu SHARE ---
        LinearLayout cardShare = card(density);
        cardShare.addView(sectionLabel("BAGIKAN \u00b7 HP SUMBER", density));
        cardShare.addView(fieldLabel("Nama WiFi", density));
        etName = field(this, sp(), "wname", "StarDevNett", density, false);
        cardShare.addView(etName);
        cardShare.addView(gap(density, 10));
        cardShare.addView(fieldLabel("Password", density));
        etPass = field(this, sp(), "wpass", "starnett123", density, true);
        cardShare.addView(etPass);
        cardShare.addView(gap(density, 14));
        btnS = primaryButton(this, "MULAI BAGIKAN", density);
        btnS.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleShare();
            }
        });
        cardShare.addView(btnS);
        content.addView(cardShare);
        content.addView(gap(density, 12));

        // --- kartu CONNECT ---
        LinearLayout cardConnect = card(density);
        cardConnect.addView(sectionLabel("SAMBUNG \u00b7 HP PENERIMA", density));
        TextView hint = new TextView(this);
        hint.setText("Sambungkan dulu ke WiFi hotspot HP sumber, lalu tekan tombol di bawah.");
        hint.setTextColor(TEXT_MUTED);
        hint.setTextSize(12);
        hint.setPadding(0, 0, 0, (int) (12 * density));
        cardConnect.addView(hint);
        btnC = ghostButton(this, "CONNECT", density);
        btnC.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleConnect();
            }
        });
        cardConnect.addView(btnC);
        content.addView(cardConnect);
        content.addView(gap(density, 10));

        // --- kartu statistik: kecepatan + total download/upload ---
        LinearLayout speedRow = new LinearLayout(this);
        speedRow.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout statDown = statCard(density);
        statDown.addView(statTitle("\u2193 Download", density));
        tvDownVal = statValue("0 B/s", density);
        statDown.addView(tvDownVal);
        tvDownSub = statSub("Total 0 B", density);
        statDown.addView(tvDownSub);
        LinearLayout.LayoutParams lpDown = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpDown.rightMargin = (int) (5 * density);
        speedRow.addView(statDown, lpDown);

        LinearLayout statUp = statCard(density);
        statUp.addView(statTitle("\u2191 Upload", density));
        tvUpVal = statValue("0 B/s", density);
        statUp.addView(tvUpVal);
        tvUpSub = statSub("Total 0 B", density);
        statUp.addView(tvUpSub);
        LinearLayout.LayoutParams lpUp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpUp.leftMargin = (int) (5 * density);
        speedRow.addView(statUp, lpUp);

        content.addView(speedRow);
        content.addView(gap(density, 10));

        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout statConn = statCard(density);
        statConn.addView(statTitle("Koneksi", density));
        tvConnVal = statValue("0 aktif", density);
        statConn.addView(tvConnVal);
        LinearLayout.LayoutParams lpConn = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpConn.rightMargin = (int) (5 * density);
        statsRow.addView(statConn, lpConn);

        LinearLayout statTotal = statCard(density);
        statTotal.addView(statTitle("Total data", density));
        tvTotalVal = statValue("0 B", density);
        statTotal.addView(tvTotalVal);
        LinearLayout.LayoutParams lpTotal = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lpTotal.leftMargin = (int) (5 * density);
        statsRow.addView(statTotal, lpTotal);

        content.addView(statsRow);
        content.addView(gap(density, 10));

        // --- kartu ping (ms) ---
        LinearLayout statPing = statCard(density);
        statPing.addView(statTitle("Ping", density));
        tvPingVal = statValue("-- ms", density);
        statPing.addView(tvPingVal);
        tvPingSub = statSub("Belum tersambung", density);
        statPing.addView(tvPingSub);
        content.addView(statPing);
        content.addView(gap(density, 12));

        // --- mode: Hemat / Seimbang / Speed ---
        content.addView(sectionLabel("MODE", density));
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] modeNames = new String[] {"Hemat", "Seimbang", "Speed"};
        for (int i = 0; i < 3; i++) {
            final int mi = i;
            modeBtn[i] = ghostButton(this, modeNames[i], density);
            modeBtn[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    setMode(mi);
                }
            });
            LinearLayout.LayoutParams lpm = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lpm.leftMargin = (int) (8 * density);
            modeRow.addView(modeBtn[i], lpm);
        }
        content.addView(modeRow);
        tvModeDesc = statSub("", density);
        content.addView(tvModeDesc);
        content.addView(gap(density, 12));

        // --- kartu PC / Laptop ---
        LinearLayout cardPc = card(density);
        cardPc.addView(sectionLabel("SAMBUNGKAN KE PC / LAPTOP", density));
        TextView pcHint = new TextView(this);
        pcHint.setText("Pasang StarDevNett untuk Windows, lalu pilih WiFi Direct (sambung ke hotspot di atas) atau Kabel USB (aktifkan USB Debugging) dan tekan SAMBUNG.");
        pcHint.setTextColor(TEXT_MUTED);
        pcHint.setTextSize(12);
        pcHint.setLineSpacing((int) (3 * density), 1f);
        pcHint.setPadding(0, 0, 0, (int) (12 * density));
        cardPc.addView(pcHint);

        LinearLayout pcInfoRow = new LinearLayout(this);
        pcInfoRow.setOrientation(LinearLayout.HORIZONTAL);
        pcInfoRow.setGravity(Gravity.CENTER_VERTICAL);
        final TextView pcInfo = new TextView(this);
        pcInfo.setText("Port 1080  \u00b7  yt / starnett");
        pcInfo.setTextColor(TEXT_MAIN);
        pcInfo.setTextSize(13);
        pcInfo.setTypeface(pcInfo.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lpInfo = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pcInfoRow.addView(pcInfo, lpInfo);

        Button btnCopy = new Button(this);
        btnCopy.setText("SALIN");
        btnCopy.setAllCaps(false);
        btnCopy.setTextColor(ACCENT_TEXT);
        btnCopy.setTextSize(12);
        btnCopy.setTypeface(btnCopy.getTypeface(), android.graphics.Typeface.BOLD);
        int copyPadH = (int) (14 * density), copyPadV = (int) (8 * density);
        btnCopy.setPadding(copyPadH, copyPadV, copyPadH, copyPadV);
        btnCopy.setMinHeight(0);
        btnCopy.setMinWidth(0);
        btnCopy.setBackground(roundedDrawable(ACCENT, 0, (int) (10 * density)));
        btnCopy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                copyPcInfo();
            }
        });
        pcInfoRow.addView(btnCopy);
        cardPc.addView(pcInfoRow);

        content.addView(cardPc);
        content.addView(gap(density, 14));

        // --- status text (detail live) ---
        tv = new TextView(this);
        tv.setTextColor(TEXT_MUTED);
        tv.setTextSize(12);
        tv.setLineSpacing((int) (4 * density), 1f);
        content.addView(tv);

        setContentView(root);

        sp = sp();
        updateModeButtons();
    }

    private void setMode(int m) {
        Modes.set(m);
        sp().edit().putInt("mode", m).apply();
        ProxyService.applyModeNow();
        VpnClientService.applyModeNow();
        updateModeButtons();
    }

    private void updateModeButtons() {
        int m = Modes.get();
        for (int i = 0; i < 3; i++) {
            if (modeBtn[i] == null) continue;
            boolean sel = (i == m);
            modeBtn[i].setTextColor(sel ? ACCENT_TEXT : TEXT_MAIN);
            modeBtn[i].setBackground(sel
                    ? roundedDrawable(ACCENT, 0, (int) (12 * getResources().getDisplayMetrics().density))
                    : roundedDrawable(GHOST_BG, GHOST_STROKE, (int) (12 * getResources().getDisplayMetrics().density)));
        }
        if (tvModeDesc != null) {
            tvModeDesc.setText(m == Modes.HEMAT
                    ? "Batas \u00b1" + (Modes.HEMAT_RATE / 1024) + " KB/s per arah, tanpa wake lock, berhenti otomatis jika baterai < "
                            + Modes.HEMAT_BATTERY_STOP + "%."
                    : (m == Modes.SPEED
                            ? "Tanpa batas, WiFi mode performa + wake lock, buffer maksimal. Cocok untuk voice & game."
                            : "Tanpa batas, wake lock aktif. Pilihan sehari-hari."));
        }
    }

    private int levelColor(int lv) {
        if (lv == 0) return Color.parseColor("#5FD08A");
        if (lv == 1) return ACCENT;
        if (lv == 2) return Color.parseColor("#FF6B6B");
        return TEXT_MAIN;
    }

    private SharedPreferences sp() {
        return getSharedPreferences("cfg", MODE_PRIVATE);
    }

    private View gap(float density, int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (dp * density)));
        return v;
    }

    private GradientDrawable roundedDrawable(int fill, int stroke, int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radiusPx);
        if (stroke != 0) d.setStroke((int) (1 * getResources().getDisplayMetrics().density), stroke);
        return d;
    }

    private LinearLayout card(float density) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * density);
        c.setPadding(pad, pad, pad, pad);
        GradientDrawable bgd = new GradientDrawable();
        bgd.setColor(CARD_BG);
        bgd.setCornerRadius(18 * density);
        bgd.setStroke((int) (1 * density), CARD_STROKE);
        c.setBackground(bgd);
        return c;
    }

    private LinearLayout statCard(float density) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int padH = (int) (12 * density), padV = (int) (10 * density);
        c.setPadding(padH, padV, padH, padV);
        GradientDrawable bgd = new GradientDrawable();
        bgd.setColor(CARD_BG);
        bgd.setCornerRadius(14 * density);
        bgd.setStroke((int) (1 * density), CARD_STROKE);
        c.setBackground(bgd);
        return c;
    }

    private TextView statTitle(String text, float density) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(TEXT_MUTED);
        t.setTextSize(10);
        t.setPadding(0, 0, 0, (int) (2 * density));
        return t;
    }

    private TextView statValue(String text, float density) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(TEXT_MAIN);
        t.setTextSize(15);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private TextView statSub(String text, float density) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(TEXT_MUTED);
        t.setTextSize(11);
        t.setPadding(0, (int) (2 * density), 0, 0);
        return t;
    }

    private TextView sectionLabel(String text, float density) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(TEXT_MAIN);
        t.setTextSize(12);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, 0, 0, (int) (12 * density));
        return t;
    }

    private TextView fieldLabel(String text, float density) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(TEXT_MUTED);
        t.setTextSize(11);
        t.setPadding(0, 0, 0, (int) (5 * density));
        return t;
    }

    private EditText field(Activity a, SharedPreferences sp, String key, String def, float density, boolean password) {
        EditText e = new EditText(a);
        e.setText(sp.getString(key, def));
        e.setTextColor(TEXT_MAIN);
        e.setHintTextColor(TEXT_MUTED);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setInputType(password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        int padH = (int) (12 * density), padV = (int) (11 * density);
        e.setPadding(padH, padV, padH, padV);
        GradientDrawable bgd = new GradientDrawable();
        bgd.setColor(FIELD_BG);
        bgd.setCornerRadius(10 * density);
        bgd.setStroke((int) (1 * density), FIELD_STROKE);
        e.setBackground(bgd);
        return e;
    }

    private Button primaryButton(Activity a, String text, float density) {
        Button btn = new Button(a);
        btn.setText(text);
        btn.setTextColor(ACCENT_TEXT);
        btn.setTextSize(14);
        btn.setTypeface(btn.getTypeface(), android.graphics.Typeface.BOLD);
        btn.setAllCaps(false);
        btn.setPadding(0, (int) (13 * density), 0, (int) (13 * density));
        btn.setBackground(roundedDrawable(ACCENT, 0, (int) (12 * density)));
        btn.setMinHeight((int) (48 * density));
        return btn;
    }

    private Button ghostButton(Activity a, String text, float density) {
        Button btn = new Button(a);
        btn.setText(text);
        btn.setTextColor(TEXT_MAIN);
        btn.setTextSize(14);
        btn.setTypeface(btn.getTypeface(), android.graphics.Typeface.BOLD);
        btn.setAllCaps(false);
        btn.setPadding(0, (int) (13 * density), 0, (int) (13 * density));
        GradientDrawable d = roundedDrawable(GHOST_BG, GHOST_STROKE, (int) (12 * density));
        btn.setBackground(d);
        btn.setMinHeight((int) (48 * density));
        return btn;
    }

    private void copyPcInfo() {
        String g = gateway();
        String host = (g != null) ? g : "192.168.49.1";
        String text = "Host: " + host + " (atau 127.0.0.1 kalau via USB/ADB forward)\n"
                + "Port: 1080\nUser: yt\nPass: starnett";
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("StarDevNett PC info", text));
        Toast.makeText(this, "Info proxy disalin. Buka StarDevNett di PC lalu tekan SAMBUNG.", Toast.LENGTH_LONG).show();
    }

    private String cleanName() {
        String n = etName.getText().toString().replaceAll("[^A-Za-z0-9]", "");
        return n.length() > 22 ? n.substring(0, 22) : n;
    }

    private boolean validShare() {
        String pw = etPass.getText().toString();
        if (cleanName().length() == 0 || pw.length() < 8 || pw.length() > 63) {
            Toast.makeText(this, "Nama: huruf/angka. Password: 8-63 karakter", Toast.LENGTH_LONG).show();
            return false;
        }
        sp.edit().putString("wname", cleanName()).putString("wpass", pw).commit();
        return true;
    }

    private Intent shareIntent() {
        Intent i = new Intent(this, ProxyService.class);
        i.putExtra("wname", "DIRECT-SS-" + cleanName());
        i.putExtra("wpass", etPass.getText().toString());
        return i;
    }

    private void toggleShare() {
        if (ProxyService.running) {
            ProxyService.requestStop();
            stopService(new Intent(this, ProxyService.class));
            return;
        }
        if (!validShare()) return;
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION}, 8);
            return;
        }
        startSvc(shareIntent());
    }

    private void toggleConnect() {
        if (VpnClientService.running) {
            VpnClientService.requestStop();
            stopService(new Intent(this, VpnClientService.class));
            return;
        }
        if (gateway() == null) {
            // belum tersambung ke WiFi hotspot HP sumber -> langsung buka WiFi Settings
            Toast.makeText(this, "Sambungkan dulu ke WiFi hotspot HP sumber", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            return;
        }
        Intent p = VpnService.prepare(this);
        if (p != null) {
            startActivityForResult(p, 7);
        } else {
            startVpn();
        }
    }

    private void startVpn() {
        String host = gateway();
        if (host == null) return;
        Intent i = new Intent(this, VpnClientService.class);
        i.putExtra("host", host);
        startSvc(i);
    }

    private void startSvc(Intent i) {
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 7 && res == RESULT_OK) startVpn();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == 8) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
                startSvc(shareIntent());
            } else {
                Toast.makeText(this, "Izin Lokasi dibutuhkan untuk WiFi Direct", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** IP HP sumber = gateway WiFi hotspot (otomatis). */
    private String gateway() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            DhcpInfo d = wm.getDhcpInfo();
            if (d == null || d.gateway == 0) return null;
            int g = d.gateway;
            return (g & 255) + "." + ((g >> 8) & 255) + "." + ((g >> 16) & 255) + "." + ((g >>> 24) & 255);
        } catch (Exception e) {
            return null;
        }
    }

    private void refresh() {
        boolean on = ProxyService.running;
        boolean von = VpnClientService.running;
        btnS.setText(on ? "STOP BAGIKAN" : "MULAI BAGIKAN");
        btnC.setText(von ? "STOP CONNECT" : "CONNECT");

        // kartu statistik: share atau connect, mana pun yang aktif
        long connCount = on ? ProxyService.conns.get() : (von ? VpnClientService.flowsN.get() : 0);
        tvConnVal.setText(connCount + (on ? " aktif" : (von ? " flow" : " aktif")));

        boolean active = on || von;
        if (active) {
            long up = on ? ProxyService.upBytes.get() : VpnClientService.upBytes.get();
            long down = on ? ProxyService.downBytes.get() : VpnClientService.downBytes.get();
            if (!wasActive) meter.reset();
            meter.update(up, down);
            shownUp = up;
            shownDown = down;
        } else if (wasActive) {
            meter.reset(); // berhenti: kecepatan 0, total sesi terakhir tetap tampil
        }
        wasActive = active;
        tvDownVal.setText(Fmt.speed(meter.downSpeed));
        tvDownSub.setText("Total " + Fmt.size(shownDown));
        tvUpVal.setText(Fmt.speed(meter.upSpeed));
        tvUpSub.setText("Total " + Fmt.size(shownUp));
        tvTotalVal.setText(Fmt.size(shownUp + shownDown));

        if (active) {
            Ping pn = von ? VpnClientService.pingNet : ProxyService.ping;
            int a = pn.avg();
            tvPingVal.setText(Ping.text(a));
            tvPingVal.setTextColor(levelColor(Ping.level(a)));
            if (von) {
                tvPingSub.setText("HP sumber " + Ping.text(VpnClientService.pingHop.avg())
                        + "  \u00b7  internet " + Ping.text(a) + "  \u00b7  jitter " + pn.jitter() + " ms");
            } else {
                tvPingSub.setText("Internet via seluler  \u00b7  jitter " + pn.jitter() + " ms  \u00b7  "
                        + (ProxyService.clientCount > 0 ? ProxyService.clientCount + " perangkat" : "belum ada perangkat"));
            }
        } else {
            tvPingVal.setText("-- ms");
            tvPingVal.setTextColor(TEXT_MAIN);
            tvPingSub.setText("Belum tersambung");
        }

        StringBuilder s = new StringBuilder();
        s.append("SHARE: ").append(on ? "AKTIF" : "mati").append('\n');
        if (on) {
            if (ProxyService.wifiName.length() > 0) {
                s.append("  WiFi: ").append(ProxyService.wifiName).append('\n');
                s.append("  Password: ").append(ProxyService.wifiPass).append('\n');
            } else {
                s.append("  membuat WiFi Direct...\n");
            }
            s.append("  data seluler: ").append(ProxyService.cellular != null ? "terikat" : "belum siap").append('\n');
            if (ProxyService.lastError.length() > 0) s.append("  error: ").append(ProxyService.lastError).append('\n');
        }
        String g = gateway();
        s.append("CONNECT: ").append(von ? "AKTIF" : "mati").append('\n');
        s.append("  HP sumber (otomatis): ").append(g == null ? "belum tersambung WiFi" : g).append('\n');
        if (VpnClientService.lastError.length() > 0) s.append("  error: ").append(VpnClientService.lastError).append('\n');
        tv.setText(s.toString());
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }
}
