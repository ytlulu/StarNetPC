# StarNett (StarDevNett) — Android + Windows

Berbagi data seluler antar perangkat. **HP sumber** membuka SOCKS5 + WiFi Direct;
**HP penerima** atau **PC Windows** menyambung dan memakai kuota HP sumber.

```
android/              proyek AIDE (buka langsung di AIDE PRO, tanpa build.gradle)
windows/              aplikasi Windows (Python) + installer.iss
ci/android-gradle/    proyek Gradle khusus CI (menunjuk ke android/, tidak mengganggu AIDE)
.github/workflows/    android.yml -> APK   |   windows.yml -> Setup.exe
```

Satu repo, dua output. Push ke GitHub → tab **Actions**:

| Workflow | Hasil (artifact / Releases) |
|---|---|
| Build Android APK | `StarDevNett-Android.apk` |
| Build Windows Installer | `StarDevNett-Setup.exe` (installer) + `StarDevNett-Portable.zip` |

Push tag `v1.2` (dst.) untuk menaruh keduanya di halaman **Releases**.

## Fitur baru (v1.4)

- **Ping (ms)**: HP sumber = latensi internet lewat seluler; HP penerima & Windows = latensi ke HP sumber
  + latensi internet lewat HP sumber, plus jitter. Hijau < 80 ms, kuning < 160 ms, merah di atasnya.
- **Mode Hemat / Seimbang / Speed** (berlaku untuk perangkat yang dipakai):
  - *Hemat*: batas ±400 KB/s per arah, buffer kecil, tanpa wake lock; di Android berhenti otomatis jika baterai < 15%.
  - *Seimbang*: tanpa batas, wake lock aktif.
  - *Speed*: tanpa batas, buffer maksimal, + WiFi lock mode performa di Android.
  - Mode Hemat membatasi kecepatan, bukan mengurangi data per byte; pakai batas itu untuk menjaga kuota.
- **Notifikasi hidup (Android)**: ↓/↑ per detik, ms, total data; HP sumber juga menampilkan nama perangkat yang tersambung.
- Nama diganti: **StarNett** (nama WiFi Direct bawaan `DIRECT-SS-StarNett`; nama package internal tetap
  `com.yt.sockshare` supaya APK baru bisa menimpa yang lama).
- Windows otomatis memakai password lama jika HP sumber masih versi lama.

## Fitur v1.3: UDP

Sebelumnya semua UDP (kecuali DNS) dibuang, jadi **voice Discord** ("RTC Connecting" terus), game online,
panggilan WhatsApp/Meet, dan QUIC tidak bisa lewat. Sekarang HP sumber mendukung **SOCKS5 UDP ASSOCIATE**
dan mode CONNECT di HP penerima meneruskan UDP lewat situ. **HP sumber dan HP penerima harus sama-sama
v1.3** (HP sumber lama menolak UDP). Windows: proxy sistem hanya untuk TCP; UDP di PC belum didukung.

## Fitur v1.2

- Kecepatan **download / upload per detik** + **total download / upload** sesi (Android & Windows)
- Windows: grafik kecepatan 30 detik terakhir, jumlah koneksi aktif
- Windows: **installer** — adb sudah ikut terpasang di `platform-tools\`, tidak perlu file lain
- DNS cache 30 detik + alamat IPv4 dicoba dulu di HP sumber
- Handshake SOCKS5 di-pipeline (lebih cepat tiap buka koneksi lewat WiFi Direct), buffer lebih besar,
  pengiriman paket TUN diblok per 16×MSS (Android mode CONNECT)

## Pakai di Windows

1. Install `StarDevNett-Setup.exe` (tanpa admin; dipasang per-user).
2. HP: **MULAI BAGIKAN**.
3. PC: sambung WiFi ke `DIRECT-SS-<nama>` **atau** colok USB (USB debugging aktif) → buka StarDevNett →
   pilih mode → **SAMBUNG**.

Port 1080 · user `yt` · password `starnett` (password lama `sockshare` tetap diterima HP sumber) adalah bawaan app HP; bisa diubah di jendela.
Proxy sistem Windows diatur otomatis dan dikembalikan saat putus/tutup.
Aplikasi yang tidak ikut proxy sistem: set manual SOCKS5 `127.0.0.1:10808` (tanpa login).

## Tanda tangan APK (supaya APK baru bisa menimpa yang lama)

Tanpa pengaturan ini, tiap build memakai kunci baru → APK baru harus meng-uninstall yang lama dulu.
Sekali saja, buat keystore (Termux / PC):

```
keytool -genkeypair -keystore starnett.keystore -alias starnett -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 starnett.keystore      # hasilnya ditempel ke secret pertama
```

Di GitHub: **Settings → Secrets and variables → Actions → New repository secret**:

- `ANDROID_KEYSTORE_BASE64` — isi base64 di atas
- `ANDROID_KEYSTORE_PASSWORD` — password keystore
- `ANDROID_KEY_ALIAS` — `starnett`

Simpan file `starnett.keystore` baik-baik (jangan di-commit).

## Catatan

- `android/bin/` dan `android/gen/` tidak ikut repo; AIDE membuatnya sendiri saat build.
- Upload dari browser HP kadang melewatkan folder berawalan titik: kalau `.github/` tidak ikut,
  buat manual lewat *Add file → Create new file* dengan nama `.github/workflows/android.yml` dan
  `.github/workflows/windows.yml`, lalu tempel isinya.
- SmartScreen Windows bisa memperingatkan untuk installer baru: *More info → Run anyway*.
