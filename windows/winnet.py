"""Helper Windows: proxy sistem, deteksi gateway, adb forward, config."""
import json
import os
import re
import subprocess
import sys

IS_WIN = sys.platform.startswith("win")
APPDIR = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "StarDevNett")
CFG = os.path.join(APPDIR, "config.json")
BACKUP = os.path.join(APPDIR, "proxy_backup.json")
NOWIN = 0x08000000 if IS_WIN else 0  # CREATE_NO_WINDOW

DEFAULTS = {
    "last_host": "",         # HP sumber terakhir yang berhasil (dicoba duluan)
    "port": 1080,
    "user": "yt",
    "pass": "starnett",
    "local_port": 10808,
    "set_proxy": True,
    "usb_port": 11080,
}


def load_cfg():
    try:
        with open(CFG, "r", encoding="utf-8") as f:
            d = json.load(f)
        out = dict(DEFAULTS)
        out.update(d)
        if out.get("pass") == "sockshare":  # password bawaan versi lama
            out["pass"] = "starnett"
        return out
    except Exception:
        return dict(DEFAULTS)


def save_cfg(d):
    try:
        os.makedirs(APPDIR, exist_ok=True)
        with open(CFG, "w", encoding="utf-8") as f:
            json.dump(d, f, indent=2)
    except Exception:
        pass


def _run(cmd, timeout=8):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                          creationflags=NOWIN, errors="replace")


# ---------------- deteksi HP sumber ----------------

def candidate_hosts():
    """192.168.49.1 (IP tetap WiFi Direct) + semua default gateway."""
    c = ["192.168.49.1"]
    try:
        if IS_WIN:
            out = _run(["route", "print", "-4"]).stdout
            for ln in out.splitlines():
                p = ln.split()
                if len(p) >= 5 and p[0] == "0.0.0.0" and p[1] == "0.0.0.0":
                    gw = p[2]
                    if re.match(r"^\d+\.\d+\.\d+\.\d+$", gw) and gw not in c:
                        c.append(gw)
        else:
            out = _run(["ip", "route"]).stdout
            for m in re.finditer(r"default via (\d+\.\d+\.\d+\.\d+)", out):
                if m.group(1) not in c:
                    c.append(m.group(1))
    except Exception:
        pass
    return c


# ---------------- adb (mode USB) ----------------

def find_adb():
    if getattr(sys, "frozen", False):
        here = os.path.dirname(sys.executable)
    else:
        here = os.path.dirname(os.path.abspath(__file__))
    exe = "adb.exe" if IS_WIN else "adb"
    for d in (os.path.join(here, "platform-tools"), here, os.getcwd(),
              os.path.join(os.getcwd(), "platform-tools")):
        p = os.path.join(d, exe)
        if os.path.isfile(p):
            return p
    for d in os.environ.get("PATH", "").split(os.pathsep):
        p = os.path.join(d, exe)
        if os.path.isfile(p):
            return p
    return None


def adb_forward(local_port, remote_port):
    """Return (ok, pesan)."""
    adb = find_adb()
    if not adb:
        return False, ("adb tidak ketemu. Taruh adb.exe (platform-tools) di folder yang sama "
                       "dengan aplikasi ini, atau tambahkan ke PATH.")
    try:
        r = _run([adb, "forward", "tcp:%d" % local_port, "tcp:%d" % remote_port], timeout=15)
        if r.returncode != 0:
            return False, (r.stderr or r.stdout).strip() or "adb forward gagal"
        return True, "adb forward tcp:%d -> HP:%d" % (local_port, remote_port)
    except Exception as e:
        return False, str(e)


def adb_forward_remove(local_port):
    adb = find_adb()
    if adb:
        try:
            _run([adb, "forward", "--remove", "tcp:%d" % local_port], timeout=8)
        except Exception:
            pass


# ---------------- proxy sistem Windows ----------------

_KEY = r"Software\Microsoft\Windows\CurrentVersion\Internet Settings"


def _notify():
    try:
        import ctypes
        w = ctypes.windll.Wininet
        w.InternetSetOptionW(0, 39, 0, 0)  # SETTINGS_CHANGED
        w.InternetSetOptionW(0, 37, 0, 0)  # REFRESH
    except Exception:
        pass


def _read(k, name, default):
    import winreg
    try:
        v, t = winreg.QueryValueEx(k, name)
        return {"v": v, "t": t}
    except OSError:
        return {"v": default, "t": None}


def set_system_proxy(port):
    if not IS_WIN:
        return False
    import winreg
    os.makedirs(APPDIR, exist_ok=True)
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _KEY, 0,
                        winreg.KEY_READ | winreg.KEY_WRITE) as k:
        if not os.path.exists(BACKUP):  # jangan timpa backup asli
            bk = {n: _read(k, n, None) for n in ("ProxyEnable", "ProxyServer", "ProxyOverride")}
            with open(BACKUP, "w", encoding="utf-8") as f:
                json.dump(bk, f)
        winreg.SetValueEx(k, "ProxyServer", 0, winreg.REG_SZ, "127.0.0.1:%d" % port)
        winreg.SetValueEx(k, "ProxyOverride", 0, winreg.REG_SZ,
                          "localhost;127.*;10.*;192.168.*;<local>")
        winreg.SetValueEx(k, "ProxyEnable", 0, winreg.REG_DWORD, 1)
    _notify()
    return True


def restore_system_proxy():
    if not IS_WIN:
        return False
    import winreg
    bk = None
    try:
        with open(BACKUP, "r", encoding="utf-8") as f:
            bk = json.load(f)
    except Exception:
        pass
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _KEY, 0,
                        winreg.KEY_READ | winreg.KEY_WRITE) as k:
        if bk is None:
            # tidak ada backup: cukup matikan proxy kalau memang punya kita
            try:
                cur, _ = winreg.QueryValueEx(k, "ProxyServer")
            except OSError:
                cur = ""
            if str(cur).startswith("127.0.0.1:"):
                winreg.SetValueEx(k, "ProxyEnable", 0, winreg.REG_DWORD, 0)
        else:
            for name, kind in (("ProxyEnable", winreg.REG_DWORD),
                               ("ProxyServer", winreg.REG_SZ),
                               ("ProxyOverride", winreg.REG_SZ)):
                item = bk.get(name) or {}
                if item.get("t") is None:
                    try:
                        winreg.DeleteValue(k, name)
                    except OSError:
                        pass
                else:
                    winreg.SetValueEx(k, name, 0, item["t"], item["v"])
    try:
        os.remove(BACKUP)
    except OSError:
        pass
    _notify()
    return True


def proxy_dirty():
    """True kalau sesi sebelumnya crash dan proxy sistem belum dikembalikan."""
    return os.path.exists(BACKUP)
