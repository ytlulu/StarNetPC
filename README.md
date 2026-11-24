# StarDevNett (SockShare) — Android + Windows

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

## Fitur baru (v1.2)

- Kecepatan **download / upload per detik** + **total download / upload** sesi (Android & Windows)
- Windows: grafik kecepatan 30 detik terakhir, jumlah koneksi aktif
- Windows: **installer** — adb sudah ikut terpasang di `platform-tools\`, tidak perlu file lain
- Handshake SOCKS5 di-pipeline (lebih cepat tiap buka koneksi lewat WiFi Direct), buffer lebih besar,
  pengiriman paket TUN diblok per 16×MSS (Android mode CONNECT)

## Pakai di Windows

1. Install `StarDevNett-Setup.exe` (tanpa admin; dipasang per-user).
2. HP: **MULAI BAGIKAN**.
3. PC: sambung WiFi ke `DIRECT-SS-<nama>` **atau** colok USB (USB debugging aktif) → buka StarDevNett →
   pilih mode → **SAMBUNG**.

Port 1080 · user `yt` · password `sockshare` adalah bawaan app HP; bisa diubah di jendela.
Proxy sistem Windows diatur otomatis dan dikembalikan saat putus/tutup.
Aplikasi yang tidak ikut proxy sistem: set manual SOCKS5 `127.0.0.1:10808` (tanpa login).

## Tanda tangan APK (supaya APK baru bisa menimpa yang lama)

Tanpa pengaturan ini, tiap build memakai kunci baru → APK baru harus meng-uninstall yang lama dulu.
Sekali saja, buat keystore (Termux / PC):

```
keytool -genkeypair -keystore sockshare.keystore -alias sockshare -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 sockshare.keystore      # hasilnya ditempel ke secret pertama
```

Di GitHub: **Settings → Secrets and variables → Actions → New repository secret**:

- `ANDROID_KEYSTORE_BASE64` — isi base64 di atas
- `ANDROID_KEYSTORE_PASSWORD` — password keystore
- `ANDROID_KEY_ALIAS` — `sockshare`

Simpan file `sockshare.keystore` baik-baik (jangan di-commit).

## Catatan

- `android/bin/` dan `android/gen/` tidak ikut repo; AIDE membuatnya sendiri saat build.
- Upload dari browser HP kadang melewatkan folder berawalan titik: kalau `.github/` tidak ikut,
  buat manual lewat *Add file → Create new file* dengan nama `.github/workflows/android.yml` dan
  `.github/workflows/windows.yml`, lalu tempel isinya.
- SmartScreen Windows bisa memperingatkan untuk installer baru: *More info → Run anyway*.
