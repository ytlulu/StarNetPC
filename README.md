# StarDevNett untuk Windows

Penerima data seluler dari HP yang menjalankan **StarDevNett / SockShare** (mode **BAGIKAN**).
PC tersambung ke HP lewat **WiFi Direct** atau **kabel USB (adb)**, lalu semua trafik browser &
aplikasi yang ikut proxy sistem Windows lewat kuota HP.

## Cara pakai

1. Di HP: buka StarDevNett → **MULAI BAGIKAN**.
2. Di PC:
   - **WiFi Direct**: sambung WiFi PC ke `DIRECT-SS-<nama>` (password sesuai di HP).
   - **USB**: colok kabel, aktifkan *USB debugging*, taruh `adb.exe` (platform-tools) di folder
     yang sama dengan `StarDevNett-Windows.exe` atau di PATH.
3. Jalankan `StarDevNett-Windows.exe` → pilih mode → **SAMBUNG**.
4. Selesai. Tekan **PUTUSKAN** (atau tutup aplikasi) untuk mengembalikan proxy Windows.

IP HP dicari otomatis (192.168.49.1 / gateway). Port 1080, user `yt`, password `sockshare`
adalah bawaan aplikasi HP; semuanya bisa diubah di jendela kalau nanti HP diupdate.

## Yang didukung

- HTTP, HTTPS (CONNECT), dan SOCKS5 di satu port lokal `127.0.0.1:10808`
- DNS di-resolve di HP (tidak bocor ke jaringan PC)
- Proxy sistem Windows diatur otomatis dan dikembalikan saat putus / tutup / start berikutnya jika crash
- Aplikasi yang mengabaikan proxy sistem (game, beberapa klien desktop): set manual
  SOCKS5 `127.0.0.1:10808` tanpa username/password
- Tidak ada UDP (sama seperti versi HP) — QUIC/HTTP3 otomatis fallback ke TCP di browser

## Build .exe

**GitHub Actions** (tanpa PC): upload folder ini ke repo → tab *Actions* → *Build Windows EXE* →
*Run workflow* → unduh artifact `StarDevNett-Windows`. Push tag `v1.0` untuk dapat file di *Releases*.

**Lokal**: `build.bat` → `dist\StarDevNett-Windows.exe`.

## CLI (opsional)

```
StarDevNett-Windows.exe --cli --mode wifi
StarDevNett-Windows.exe --cli --host 192.168.49.1 --port 1080 --user yt --password sockshare
```

Catatan: Windows SmartScreen bisa memperingatkan untuk exe baru yang belum dikenal
(*More info → Run anyway*). Pengaturan tersimpan di `%APPDATA%\StarDevNett\config.json`.
