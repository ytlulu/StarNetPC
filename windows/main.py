"""StarDevNett untuk Windows - penerima koneksi data seluler dari HP (SockShare)."""
import collections
import os
import queue
import sys
import threading
import time

import relay
import winnet

APP_NAME = "StarDevNett"
VERSION = "1.1-win"


def fmt_size(b):
    b = max(0, int(b))
    if b < 1024:
        return "%d B" % b
    v = b / 1024.0
    if v < 1024:
        return ("%.1f KB" if v < 100 else "%.0f KB") % v
    v /= 1024.0
    if v < 1024:
        return ("%.1f MB" if v < 100 else "%.0f MB") % v
    return "%.2f GB" % (v / 1024.0)


def fmt_speed(bps):
    return fmt_size(bps).replace(" B", " B/s").replace("KB", "KB/s").replace("MB", "MB/s").replace("GB", "GB/s")


class Meter:
    """Kecepatan up/down (byte/detik, dihaluskan) dari penghitung total."""

    def __init__(self):
        self.reset()

    def reset(self):
        self.t = None
        self.up0 = self.down0 = 0
        self.up = self.down = 0.0

    def update(self, up, down, now=None):
        now = time.time() if now is None else now
        if self.t is None or up < self.up0 or down < self.down0:
            self.t, self.up0, self.down0 = now, up, down
            self.up = self.down = 0.0
            return
        dt = now - self.t
        if dt < 0.4:
            return
        self.up = self.up * 0.35 + ((up - self.up0) / dt) * 0.65
        self.down = self.down * 0.35 + ((down - self.down0) / dt) * 0.65
        self.t, self.up0, self.down0 = now, up, down


def resource_path(rel):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, rel)


class Controller:
    """Logika utama, terpisah dari GUI supaya bisa dipakai juga lewat --cli."""

    def __init__(self, log):
        self.log = log
        self.relay = None
        self.stats = relay.Stats()
        self.running = False
        self.cfg = None
        self.host = ""

    def resolve_host(self, cfg):
        """Return (host, port) HP sumber."""
        if cfg["mode"] == "usb":
            ok, msg = winnet.adb_forward(int(cfg["usb_port"]), int(cfg["port"]))
            self.log(("USB: " if ok else "USB GAGAL: ") + msg)
            if not ok:
                return None, 0
            return "127.0.0.1", int(cfg["usb_port"])
        host = (cfg["host"] or "").strip()
        if host:
            return host, int(cfg["port"])
        self.log("Mencari HP sumber...")
        for h in winnet.candidate_hosts():
            if relay.probe(h, cfg["port"], 1.2):
                self.log("HP sumber ketemu: %s" % h)
                return h, int(cfg["port"])
        self.log("HP sumber tidak ketemu. Sambungkan WiFi ke hotspot HP "
                 "(DIRECT-SS-...) dan pastikan 'MULAI BAGIKAN' aktif di HP.")
        return None, 0

    def start(self, cfg):
        if self.running:
            return False
        if winnet.proxy_dirty():
            try:
                winnet.restore_system_proxy()
                self.log("Proxy sistem dari sesi sebelumnya dipulihkan.")
            except Exception:
                pass
        host, port = self.resolve_host(cfg)
        if not host:
            return False
        up = relay.Upstream(host, port, cfg["user"], cfg["pass"])
        ok, msg, ms = relay.self_test(up)
        if not ok:
            self.log("Tes ke HP gagal: %s" % msg)
            if "password" in msg or "auth" in msg:
                self.log("Cek username/password SOCKS (bawaan: yt / sockshare).")
            elif cfg["mode"] == "usb":
                self.log("Pastikan 'MULAI BAGIKAN' aktif di HP dan USB debugging menyala.")
            return False
        self.log("Tes OK lewat HP (%s, %d ms)" % (msg, ms))
        self.stats = relay.Stats()
        self.relay = relay.Relay(int(cfg["local_port"]), up, self.stats)
        try:
            self.relay.start()
        except OSError as e:
            self.log("Port lokal %s tidak bisa dipakai: %s" % (cfg["local_port"], e))
            return False
        if cfg["set_proxy"]:
            try:
                if winnet.set_system_proxy(int(cfg["local_port"])):
                    self.log("Proxy sistem Windows aktif -> 127.0.0.1:%s" % cfg["local_port"])
            except Exception as e:
                self.log("Gagal set proxy sistem: %s" % e)
        self.cfg = cfg
        self.host = "%s:%d" % (host, port)
        self.running = True
        self.log("Tersambung. Relay lokal 127.0.0.1:%s (HTTP + SOCKS5)" % cfg["local_port"])
        return True

    def stop(self):
        if not self.running and not winnet.proxy_dirty():
            return
        self.running = False
        if self.relay:
            self.relay.stop()
            self.relay = None
        try:
            winnet.restore_system_proxy()
        except Exception:
            pass
        if self.cfg and self.cfg.get("mode") == "usb":
            winnet.adb_forward_remove(int(self.cfg["usb_port"]))
        self.log("Terputus. Proxy sistem dikembalikan.")


# ======================= CLI =======================

def run_cli(argv):
    import argparse
    ap = argparse.ArgumentParser(prog=APP_NAME, description="StarDevNett (CLI)")
    ap.add_argument("--cli", action="store_true")
    ap.add_argument("--mode", choices=["wifi", "usb"])
    ap.add_argument("--host")
    ap.add_argument("--port", type=int)
    ap.add_argument("--user")
    ap.add_argument("--password")
    ap.add_argument("--local-port", type=int)
    ap.add_argument("--no-system-proxy", action="store_true")
    a = ap.parse_args(argv)
    cfg = winnet.load_cfg()
    if a.mode: cfg["mode"] = a.mode
    if a.host: cfg["host"] = a.host
    if a.port: cfg["port"] = a.port
    if a.user is not None: cfg["user"] = a.user
    if a.password is not None: cfg["pass"] = a.password
    if a.local_port: cfg["local_port"] = a.local_port
    if a.no_system_proxy: cfg["set_proxy"] = False
    c = Controller(lambda m: print(time.strftime("%H:%M:%S"), m, flush=True))
    if not c.start(cfg):
        return 1
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        c.stop()
    return 0


# ======================= GUI =======================

BG = "#0D1117"
CARD = "#161B22"
STROKE = "#2A313A"
ACCENT = "#E0A94A"
ACCENT_TXT = "#1A0F0A"
TXT = "#F4F2EE"
MUTED = "#B9B4AC"
OK = "#5FD08A"
BAD = "#FF6B6B"


def run_gui():
    import tkinter as tk
    from tkinter import ttk, messagebox

    root = tk.Tk()
    root.title("%s - Berbagi data seluler" % APP_NAME)
    root.configure(bg=BG)
    root.geometry("470x820")
    root.minsize(440, 760)
    try:
        root.iconbitmap(resource_path(os.path.join("assets", "icon.ico")))
    except Exception:
        pass

    cfg = winnet.load_cfg()
    logq = queue.Queue()
    ctl = Controller(lambda m: logq.put(m))
    busy = {"v": False}

    def lbl(parent, text, size=9, color=MUTED, bold=False, **kw):
        return tk.Label(parent, text=text, bg=parent["bg"], fg=color,
                        font=("Segoe UI", size, "bold" if bold else "normal"), **kw)

    def card(parent):
        f = tk.Frame(parent, bg=CARD, highlightbackground=STROKE, highlightthickness=1)
        f.pack(fill="x", padx=16, pady=6)
        inner = tk.Frame(f, bg=CARD)
        inner.pack(fill="x", padx=14, pady=12)
        return inner

    def entry(parent, var, show=None, width=None):
        e = tk.Entry(parent, textvariable=var, bg="#1F2630", fg=TXT, insertbackground=TXT,
                     relief="flat", highlightthickness=1, highlightbackground=STROKE,
                     highlightcolor=ACCENT, font=("Segoe UI", 10), show=show or "")
        if width:
            e.config(width=width)
        return e

    # ---- header ----
    head = tk.Frame(root, bg=BG)
    head.pack(fill="x", padx=16, pady=(14, 4))
    try:
        img = tk.PhotoImage(file=resource_path(os.path.join("assets", "logo.png")))
        img = img.subsample(max(1, img.width() // 40))
        tk.Label(head, image=img, bg=BG).pack(side="left")
        root._logo = img
    except Exception:
        pass
    ht = tk.Frame(head, bg=BG)
    ht.pack(side="left", padx=10)
    lbl(ht, APP_NAME, 15, TXT, True).pack(anchor="w")
    lbl(ht, "Penerima data seluler dari HP  \u00b7  v%s" % VERSION, 9).pack(anchor="w")

    # ---- kartu sambungan ----
    c1 = card(root)
    lbl(c1, "SAMBUNG KE HP SUMBER", 9, TXT, True).pack(anchor="w", pady=(0, 8))

    v_mode = tk.StringVar(value=cfg["mode"])
    mrow = tk.Frame(c1, bg=CARD)
    mrow.pack(fill="x")
    for text, val in (("WiFi Direct", "wifi"), ("Kabel USB (adb)", "usb")):
        tk.Radiobutton(mrow, text=text, value=val, variable=v_mode, bg=CARD, fg=TXT,
                       selectcolor="#1F2630", activebackground=CARD, activeforeground=TXT,
                       font=("Segoe UI", 10), command=lambda: on_mode()).pack(side="left", padx=(0, 16))

    v_host = tk.StringVar(value=cfg["host"])
    v_port = tk.StringVar(value=str(cfg["port"]))
    v_user = tk.StringVar(value=cfg["user"])
    v_pass = tk.StringVar(value=cfg["pass"])
    v_local = tk.StringVar(value=str(cfg["local_port"]))
    v_sys = tk.BooleanVar(value=bool(cfg["set_proxy"]))

    lbl(c1, "IP HP sumber (kosongkan = otomatis)", 9).pack(anchor="w", pady=(10, 2))
    e_host = entry(c1, v_host)
    e_host.pack(fill="x", ipady=4)

    row = tk.Frame(c1, bg=CARD)
    row.pack(fill="x", pady=(10, 0))
    for i, (t, v, sh) in enumerate((("Port SOCKS", v_port, None), ("Username", v_user, None),
                                    ("Password", v_pass, "*"))):
        col = tk.Frame(row, bg=CARD)
        col.pack(side="left", fill="x", expand=True, padx=(0 if i == 0 else 6, 0))
        lbl(col, t, 9).pack(anchor="w", pady=(0, 2))
        entry(col, v, sh, width=8).pack(fill="x", ipady=4)

    # ---- kartu lokal ----
    c2 = card(root)
    lbl(c2, "PROXY LOKAL DI PC", 9, TXT, True).pack(anchor="w", pady=(0, 6))
    r2 = tk.Frame(c2, bg=CARD)
    r2.pack(fill="x")
    lbl(r2, "Port lokal", 9).pack(side="left")
    entry(r2, v_local, width=8).pack(side="left", padx=8, ipady=3)
    tk.Checkbutton(c2, text="Atur proxy sistem Windows otomatis (browser, Windows Update, dll)",
                   variable=v_sys, bg=CARD, fg=TXT, selectcolor="#1F2630",
                   activebackground=CARD, activeforeground=TXT, font=("Segoe UI", 9),
                   wraplength=400, justify="left").pack(anchor="w", pady=(8, 0))
    lbl(c2, "Aplikasi yang tidak ikut proxy sistem: set manual SOCKS5 127.0.0.1:port lokal "
            "(tanpa login).", 8, wraplength=410, justify="left").pack(anchor="w", pady=(6, 0))

    # ---- tombol ----
    bt = tk.Frame(root, bg=BG)
    bt.pack(fill="x", padx=16, pady=(8, 2))
    btn = tk.Button(bt, text="SAMBUNG", bg=ACCENT, fg=ACCENT_TXT, activebackground="#F0BC62",
                    relief="flat", font=("Segoe UI", 11, "bold"), cursor="hand2",
                    command=lambda: toggle())
    btn.pack(fill="x", ipady=8)

    # ---- status & statistik ----
    st = card(root)
    top = tk.Frame(st, bg=CARD)
    top.pack(fill="x")
    lbl(top, "Status", 8).pack(side="left")
    tv_status = lbl(top, "Terputus", 10, MUTED, True)
    tv_status.pack(side="left", padx=(6, 0))
    tv_conn = lbl(top, "0 koneksi", 9, MUTED)
    tv_conn.pack(side="right")

    srow = tk.Frame(st, bg=CARD)
    srow.pack(fill="x", pady=(8, 0))
    cd = tk.Frame(srow, bg=CARD)
    cd.pack(side="left", expand=True, fill="x")
    lbl(cd, "\u2193 Download", 8, OK).pack(anchor="w")
    tv_dspeed = lbl(cd, "0 B/s", 14, TXT, True)
    tv_dspeed.pack(anchor="w")
    tv_dtotal = lbl(cd, "Total 0 B", 9, MUTED)
    tv_dtotal.pack(anchor="w")
    cu = tk.Frame(srow, bg=CARD)
    cu.pack(side="left", expand=True, fill="x")
    lbl(cu, "\u2191 Upload", 8, ACCENT).pack(anchor="w")
    tv_uspeed = lbl(cu, "0 B/s", 14, TXT, True)
    tv_uspeed.pack(anchor="w")
    tv_utotal = lbl(cu, "Total 0 B", 9, MUTED)
    tv_utotal.pack(anchor="w")

    tv_all = lbl(st, "Total data sesi: 0 B", 9, MUTED)
    tv_all.pack(anchor="w", pady=(8, 4))
    graph = tk.Canvas(st, height=56, bg="#0A0E14", highlightthickness=1,
                      highlightbackground=STROKE)
    graph.pack(fill="x")

    # ---- log ----
    logbox = tk.Text(root, height=9, bg="#0A0E14", fg=MUTED, relief="flat", font=("Consolas", 9),
                     highlightthickness=1, highlightbackground=STROKE, wrap="word", state="disabled")
    logbox.pack(fill="both", expand=True, padx=16, pady=(6, 14))

    def log(msg):
        logbox.config(state="normal")
        logbox.insert("end", time.strftime("%H:%M:%S ") + msg + "\n")
        logbox.see("end")
        logbox.config(state="disabled")

    def on_mode():
        usb = v_mode.get() == "usb"
        e_host.config(state="disabled" if usb else "normal")

    def read_cfg():
        try:
            c = {
                "mode": v_mode.get(),
                "host": v_host.get().strip(),
                "port": int(v_port.get()),
                "user": v_user.get(),
                "pass": v_pass.get(),
                "local_port": int(v_local.get()),
                "set_proxy": bool(v_sys.get()),
                "usb_port": cfg.get("usb_port", 11080),
            }
            if not (0 < c["port"] < 65536 and 0 < c["local_port"] < 65536):
                raise ValueError
            return c
        except ValueError:
            messagebox.showerror(APP_NAME, "Port harus angka 1-65535.")
            return None

    def set_busy(b):
        busy["v"] = b
        btn.config(state="disabled" if b else "normal")

    def toggle():
        if busy["v"]:
            return
        if ctl.running:
            set_busy(True)
            def work_stop():
                ctl.stop()
                root.after(0, lambda: (set_busy(False), after_state()))
            threading.Thread(target=work_stop, daemon=True).start()
            return
        c = read_cfg()
        if not c:
            return
        winnet.save_cfg(c)
        set_busy(True)
        tv_status.config(text="Menyambung...", fg=ACCENT)
        def work_start():
            ctl.start(c)
            root.after(0, lambda: (set_busy(False), after_state()))
        threading.Thread(target=work_start, daemon=True).start()

    def after_state():
        if ctl.running:
            btn.config(text="PUTUSKAN", bg="#2B3340", fg=TXT, activebackground="#38424F")
            tv_status.config(text="Tersambung", fg=OK)
        else:
            btn.config(text="SAMBUNG", bg=ACCENT, fg=ACCENT_TXT, activebackground="#F0BC62")
            tv_status.config(text="Terputus", fg=MUTED)

    meter = Meter()
    hist = collections.deque(maxlen=60)  # ~30 detik terakhir (tick 500 ms)
    last = {"up": 0, "down": 0}

    def draw_graph():
        graph.delete("all")
        w = max(graph.winfo_width(), 10)
        h = max(graph.winfo_height(), 10)
        if len(hist) < 2:
            return
        peak = max(1.0, max(max(d, u) for d, u in hist))
        step = w / float(hist.maxlen - 1)
        x0 = w - step * (len(hist) - 1)
        for idx, color in ((0, OK), (1, ACCENT)):
            pts = []
            for k, pair in enumerate(hist):
                pts.append(x0 + k * step)
                pts.append(h - 3 - (h - 8) * (pair[idx] / peak))
            graph.create_line(*pts, fill=color, width=2, smooth=True)
        graph.create_text(w - 4, 4, text=fmt_speed(peak), fill=MUTED, anchor="ne",
                          font=("Segoe UI", 7))

    def tick():
        try:
            while True:
                log(logq.get_nowait())
        except queue.Empty:
            pass
        if ctl.running:
            s_ = ctl.stats
            last["up"], last["down"] = s_.up, s_.down
            meter.update(s_.up, s_.down)
            tv_conn.config(text="%d koneksi" % s_.conns)
            hist.append((meter.down, meter.up))
        else:
            meter.reset()
            tv_conn.config(text="0 koneksi")
            if hist:
                hist.clear()
        tv_dspeed.config(text=fmt_speed(meter.down))
        tv_uspeed.config(text=fmt_speed(meter.up))
        tv_dtotal.config(text="Total " + fmt_size(last["down"]))
        tv_utotal.config(text="Total " + fmt_size(last["up"]))
        tv_all.config(text="Total data sesi: " + fmt_size(last["up"] + last["down"]))
        draw_graph()
        root.after(500, tick)

    def on_close():
        try:
            ctl.stop()
        finally:
            root.destroy()

    root.protocol("WM_DELETE_WINDOW", on_close)
    on_mode()
    log("%s %s siap. Sambungkan WiFi ke hotspot HP (DIRECT-SS-...) lalu tekan SAMBUNG." % (APP_NAME, VERSION))
    if winnet.proxy_dirty():
        try:
            winnet.restore_system_proxy()
            log("Proxy sistem dari sesi sebelumnya dipulihkan.")
        except Exception:
            pass
    tick()
    root.mainloop()


if __name__ == "__main__":
    if "--cli" in sys.argv:
        sys.exit(run_cli(sys.argv[1:]))
    run_gui()
