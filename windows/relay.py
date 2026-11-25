"""
Relay lokal StarDevNett untuk Windows.

Menerima koneksi di 127.0.0.1:<port> (HTTP proxy + HTTPS CONNECT + SOCKS5 tanpa login,
semuanya di satu port), lalu meneruskan ke SOCKS5 HP sumber (dengan user/pass)
lewat WiFi Direct atau USB (adb forward). DNS di-resolve di HP (atyp=3 / domain).
"""
import socket
import threading
import struct
import time


class Stats:
    def __init__(self):
        self.lock = threading.Lock()
        self.conns = 0
        self.total = 0
        self.up = 0      # PC -> internet (upload)
        self.down = 0    # internet -> PC (download)
        self.last_error = ""

    @property
    def bytes(self):
        return self.up + self.down

    def add_bytes(self, n, down):
        with self.lock:
            if down:
                self.down += n
            else:
                self.up += n

    def conn(self, d):
        with self.lock:
            self.conns += d
            if d > 0:
                self.total += d


class Limiter:
    """Pembatas kecepatan (byte/detik) bersama antar thread. rate <= 0 = tanpa batas."""

    def __init__(self):
        self.rate = 0
        self._next = 0.0
        self._lock = threading.Lock()

    def acquire(self, n):
        r = self.rate
        if r <= 0 or n <= 0:
            return
        with self._lock:
            now = time.monotonic()
            if self._next < now - 0.1:
                self._next = now - 0.1
            self._next += n / float(r)
            wait = self._next - now
        if wait > 0.002:
            time.sleep(min(wait, 1.5))


# mode kinerja: batas per arah (byte/detik) & ukuran buffer baca
MODES = {
    "hemat": {"rate": 400 * 1024, "buf": 16384},
    "seimbang": {"rate": 0, "buf": 65536},
    "speed": {"rate": 0, "buf": 262144},
}


class Upstream:
    def __init__(self, host, port, user, password):
        self.host = host
        self.port = int(port)
        self.user = user or ""
        self.password = password or ""


def _rd(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError("koneksi putus")
        buf += chunk
    return buf


def socks5_connect(up, host, port, timeout=10):
    """Buka koneksi ke (host, port) lewat SOCKS5 HP sumber. Return socket."""
    s = socket.create_connection((up.host, up.port), timeout=timeout)
    try:
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 262144)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 262144)
        s.settimeout(12)
        auth = len(up.user) > 0
        # request CONNECT: domain (atyp 3) atau IP literal
        try:
            ip4 = socket.inet_pton(socket.AF_INET, host)
            req = b"\x05\x01\x00\x01" + ip4
        except OSError:
            try:
                ip6 = socket.inet_pton(socket.AF_INET6, host)
                req = b"\x05\x01\x00\x04" + ip6
            except OSError:
                h = host.encode("idna")
                if len(h) > 255:
                    raise IOError("hostname terlalu panjang")
                req = b"\x05\x01\x00\x03" + bytes([len(h)]) + h
        req += struct.pack(">H", port)
        # Pipeline: greeting + auth + request dikirim sekaligus (hemat 2 round-trip
        # per koneksi di WiFi Direct). Server HP membaca stream secara berurutan.
        if auth:
            u = up.user.encode("utf-8")
            p = up.password.encode("utf-8")
            s.sendall(b"\x05\x01\x02" + b"\x01" + bytes([len(u)]) + u
                      + bytes([len(p)]) + p + req)
        else:
            s.sendall(b"\x05\x01\x00" + req)
        ver, method = _rd(s, 2)
        if ver != 5:
            raise IOError("bukan SOCKS5")
        if method != (2 if auth else 0):
            raise IOError("metode auth ditolak HP")
        if auth:
            _, st = _rd(s, 2)
            if st != 0:
                raise IOError("username/password salah")
        _, rep, _, atyp = _rd(s, 4)
        if atyp == 1:
            _rd(s, 6)
        elif atyp == 4:
            _rd(s, 18)
        elif atyp == 3:
            ln = _rd(s, 1)[0]
            _rd(s, ln + 2)
        if rep != 0:
            raise IOError("SOCKS reply %d" % rep)
        s.settimeout(None)
        return s
    except Exception:
        try:
            s.close()
        except OSError:
            pass
        raise


def _pump(a, b, stats, down, rl):
    lim = rl.down_lim if down else rl.up_lim
    try:
        while True:
            data = a.recv(rl.bufsize)
            if not data:
                break
            lim.acquire(len(data))
            b.sendall(data)
            stats.add_bytes(len(data), down)
    except OSError:
        pass
    finally:
        for x, how in ((b, socket.SHUT_WR),):
            try:
                x.shutdown(how)
            except OSError:
                pass


def _tunnel(client, remote, rl):
    stats = rl.stats
    t = threading.Thread(target=_pump, args=(remote, client, stats, True, rl), daemon=True)
    t.start()
    _pump(client, remote, stats, False, rl)
    t.join(timeout=30)


def _split_hostport(hp, default_port):
    hp = hp.strip()
    if hp.startswith("["):  # [ipv6]:port
        end = hp.find("]")
        host = hp[1:end]
        rest = hp[end + 1:]
        port = int(rest[1:]) if rest.startswith(":") else default_port
        return host, port
    if hp.count(":") == 1:
        h, p = hp.rsplit(":", 1)
        return h, int(p)
    return hp, default_port


class Relay:
    def __init__(self, listen_port, upstream, stats=None, bind="127.0.0.1"):
        self.listen_port = int(listen_port)
        self.up = upstream
        self.stats = stats or Stats()
        self.bind = bind
        self._srv = None
        self._stop = False
        self._socks = set()
        self._lock = threading.Lock()
        self.up_lim = Limiter()
        self.down_lim = Limiter()
        self.bufsize = 65536
        self.set_mode("seimbang")

    def set_mode(self, name):
        m = MODES.get(name, MODES["seimbang"])
        self.up_lim.rate = m["rate"]
        self.down_lim.rate = m["rate"]
        self.bufsize = m["buf"]
        self.mode = name

    # ---- lifecycle ----
    def start(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind((self.bind, self.listen_port))
        s.listen(128)
        self._srv = s
        self._stop = False
        threading.Thread(target=self._accept_loop, daemon=True).start()

    def stop(self):
        self._stop = True
        try:
            if self._srv:
                self._srv.close()
        except OSError:
            pass
        with self._lock:
            socks = list(self._socks)
        for x in socks:
            try:
                x.close()
            except OSError:
                pass

    def _accept_loop(self):
        while not self._stop:
            try:
                c, _ = self._srv.accept()
            except OSError:
                break
            threading.Thread(target=self._handle, args=(c,), daemon=True).start()

    def _track(self, s, add=True):
        with self._lock:
            (self._socks.add if add else self._socks.discard)(s)

    # ---- per-connection ----
    def _handle(self, c):
        self.stats.conn(1)
        self._track(c)
        remote = None
        try:
            c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            c.settimeout(30)
            first = c.recv(1, socket.MSG_PEEK)
            if not first:
                return
            if first == b"\x05":
                remote = self._socks_in(c)
            else:
                remote = self._http_in(c)
            if remote is not None:
                self._track(remote)
                c.settimeout(None)
                _tunnel(c, remote, self)
        except Exception as e:
            if not self._stop:
                self.stats.last_error = str(e)
        finally:
            for x in (c, remote):
                if x is not None:
                    self._track(x, False)
                    try:
                        x.close()
                    except OSError:
                        pass
            self.stats.conn(-1)

    # SOCKS5 tanpa auth (untuk aplikasi yang bisa diset SOCKS manual)
    def _socks_in(self, c):
        _rd(c, 1)
        nm = _rd(c, 1)[0]
        _rd(c, nm)
        c.sendall(b"\x05\x00")
        ver, cmd, _, atyp = _rd(c, 4)
        if ver != 5:
            return None
        if atyp == 1:
            host = socket.inet_ntop(socket.AF_INET, _rd(c, 4))
        elif atyp == 3:
            ln = _rd(c, 1)[0]
            host = _rd(c, ln).decode("utf-8", "replace")
        elif atyp == 4:
            host = socket.inet_ntop(socket.AF_INET6, _rd(c, 16))
        else:
            c.sendall(b"\x05\x08\x00\x01\x00\x00\x00\x00\x00\x00")
            return None
        port = struct.unpack(">H", _rd(c, 2))[0]
        if cmd != 1:
            c.sendall(b"\x05\x07\x00\x01\x00\x00\x00\x00\x00\x00")
            return None
        try:
            r = socks5_connect(self.up, host, port)
        except Exception as e:
            self.stats.last_error = "%s:%d -> %s" % (host, port, e)
            c.sendall(b"\x05\x04\x00\x01\x00\x00\x00\x00\x00\x00")
            return None
        c.sendall(b"\x05\x00\x00\x01\x00\x00\x00\x00\x00\x00")
        return r

    # HTTP proxy: CONNECT (HTTPS) dan request biasa (http://)
    def _http_in(self, c):
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = c.recv(8192)
            if not chunk:
                return None
            buf += chunk
            if len(buf) > 65536:
                return None
        head, _, rest = buf.partition(b"\r\n\r\n")
        lines = head.split(b"\r\n")
        try:
            method, target, version = lines[0].decode("latin-1").split(" ", 2)
        except ValueError:
            c.sendall(b"HTTP/1.1 400 Bad Request\r\n\r\n")
            return None

        if method.upper() == "CONNECT":
            host, port = _split_hostport(target, 443)
            try:
                r = socks5_connect(self.up, host, port)
            except Exception as e:
                self.stats.last_error = "%s:%d -> %s" % (host, port, e)
                c.sendall(b"HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
                return None
            c.sendall(b"HTTP/1.1 200 Connection established\r\n\r\n")
            if rest:
                r.sendall(rest)
                self.stats.add_bytes(len(rest), False)
            return r

        # request http:// biasa -> ubah ke bentuk origin-form lalu tunnel
        if not target.lower().startswith("http://"):
            c.sendall(b"HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
            return None
        no_scheme = target[7:]
        slash = no_scheme.find("/")
        hostport = no_scheme if slash < 0 else no_scheme[:slash]
        path = "/" if slash < 0 else no_scheme[slash:]
        host, port = _split_hostport(hostport, 80)
        try:
            r = socks5_connect(self.up, host, port)
        except Exception as e:
            self.stats.last_error = "%s:%d -> %s" % (host, port, e)
            c.sendall(b"HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
            return None
        new_lines = [("%s %s %s" % (method, path, version)).encode("latin-1")]
        for ln in lines[1:]:
            low = ln.lower()
            if low.startswith(b"proxy-connection:") or low.startswith(b"proxy-authorization:"):
                continue
            new_lines.append(ln)
        new_lines.append(b"Connection: close")
        payload = b"\r\n".join(new_lines) + b"\r\n\r\n" + rest
        r.sendall(payload)
        self.stats.add_bytes(len(payload), False)
        return r


def probe(host, port, timeout=1.5):
    try:
        s = socket.create_connection((host, int(port)), timeout=timeout)
        s.close()
        return True
    except OSError:
        return False


def self_test(up, test_host="example.com", test_port=80, timeout=10):
    """Tes lewat HP sumber: return (ok, pesan, ms)."""
    t0 = time.time()
    try:
        s = socks5_connect(up, test_host, test_port, timeout=timeout)
    except Exception as e:
        return False, str(e), 0
    try:
        s.settimeout(timeout)
        s.sendall(("GET / HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n" % test_host).encode())
        data = s.recv(64)
        ms = int((time.time() - t0) * 1000)
        if data.startswith(b"HTTP/"):
            return True, data.split(b"\r\n")[0].decode("latin-1"), ms
        return False, "balasan tidak dikenal", ms
    except Exception as e:
        return False, str(e), 0
    finally:
        s.close()
