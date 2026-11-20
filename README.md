# Sanwa Production App (Android)

_Dulu bernama "QR-IPCS Scanner"._

Aplikasi Android untuk scan QR produksi dan langsung melihat data Plan dari server **Lot-Card**, tanpa membuka browser.

## Fitur utama (sesuai izin akun)

- **Lihat Data Plan** (izin `mobile.scan`, read-only) — scan QR → data Plan lengkap.
- **Input Manual** (izin `mobile.manual`: Input Manual, Operator Subcont, Admin) — scan QR → lembar Assembly/Inspection Manual: NIK, tanggal/shift, mesin & parameter per sub-proses, Total Check OK, NG per kategori + posisi, dan Lot Material Assembly (Admin juga bisa memilih/mengganti BOM). Simpan (draft) atau Simpan & Submit.
- **Input Perolehan** (izin `mobile.input`, untuk operator stage **Auto**) — scan QR → form yang sama dengan form scan operator di web:
  - Qty (Winding: Total Check OK), NG per kategori + posisi N (Winding)
  - Penerus Shift (operator utama memilih; penerus mengisi bagiannya sendiri)
  - Additional Material Winding (muncul bila Total Check OK > Qty Plan)
  - parameter Assembly, catatan

  Aturan simpan di server SAMA PERSIS dengan web (`AutoResultService`). Stage Manual tetap lewat web.

Halaman utama menampilkan menu sesuai izin akun.

## Fitur

- **Login sekali** dengan NIK + password akun Lot-Card. Login tetap aktif sampai:
  - logout,
  - dicabut Super Admin (menu *User → Login Aplikasi HP*),
  - akun dinonaktifkan,
  - password diganti, atau
  - izin "Pakai aplikasi Scanner QR di HP" dicabut dari role-nya.
- **Scan QR** dengan kamera belakang. Tersedia tombol senter dan pilihan **Ketik nomor** kalau QR rusak.
  - QR produksi berisi nomor lot (mis. `26060001`).
  - Nomor turunan (`26060001-2`) juga bisa; turunannya ditandai kuning.
- **Data yang tampil** (disusun oleh server):
  - ringkasan Plan;
  - progres per stage (Winding / Assembly / Inspection): status approve, operator, Penerus Shift, OK/NG, NG per kategori, parameter;
  - rincian per turunan;
  - BOM & lot material;
  - rework.
- **Dua alamat server:**
  - **Lokal** (Wi-Fi kantor yang satu segmen dengan server) dicoba lebih dulu.
  - **Publik** dipakai otomatis kalau lokal tidak tersambung.
- **Sertifikat self-signed:** saat pertama kali tersambung, aplikasi menampilkan sidik jari SHA-256 sertifikat server. Setelah pengguna menekan **Percayai**, sertifikat itu disimpan dan dipakai untuk alamat lokal maupun publik.
- Alamat `http://` juga bisa, misalnya server uji `http://200.200.64.13:1313`.

Butuh Android 8.0 ke atas. Ikon & logo: logo PT Sanwa (`app/src/main/res/mipmap-*/ic_launcher_foreground.png`, `drawable-xxxhdpi/logo_sanwa.png`, `drawable-xxxhdpi/logo_badge.png`), diambil dari `public/images/logo.png` aplikasi web.

## Membuat APK (GitHub Actions)

Setiap ada perubahan di branch `main`, GitHub otomatis membuat APK. Bisa juga dijalankan manual: tab **Actions → Build APK → Run workflow**.

Hasilnya bisa diunduh dari:
- tab **Releases** → `Sanwa-Production-App-1.0.N.apk` (sampai v1.0.10: `QR-IPCS-Scanner-1.0.N.apk`), atau
- tab **Actions** → run terakhir → **Artifacts**.

Nomor versi naik sendiri setiap build, jadi APK baru bisa dipasang menimpa yang lama tanpa menghapus aplikasi.

## Memasang di HP

1. Unduh file `.apk` ke HP, lalu buka. Kalau diminta, izinkan "Pasang aplikasi tidak dikenal" untuk browser/File Manager.
2. Buka **Sanwa Production App** → **Pengaturan server**:
   - Alamat lokal: `https://200.200.64.4:2443`
   - Alamat publik: `https://IP-PUBLIK:PORT`
   - Tekan **Tes koneksi**, lalu **Percayai** sertifikatnya. Sebaiknya cocokkan sidik jarinya dengan yang dipegang IT.
   - Tekan **Simpan**.
3. Login dengan NIK & password Lot-Card, lalu arahkan kamera ke QR produksi.

## Kunci tanda tangan APK

APK ditandatangani dengan `signing/lotcard-scanner.jks`:
- alias: `lotcard`
- password: `lotcard-sanwa`

**Jangan hapus atau ganti file ini.** Android hanya mau memasang update yang ditandatangani dengan kunci yang sama.

Repo ini private. Kalau ingin kunci yang lebih rahasia:
1. Buat keystore baru.
2. Isi GitHub Secrets berikut:
   - `LOTCARD_KEYSTORE_BASE64` (isi file .jks dalam base64)
   - `LOTCARD_KEYSTORE_PASSWORD`
   - `LOTCARD_KEY_ALIAS`
   - `LOTCARD_KEY_PASSWORD`
3. Hapus aplikasi lama dari HP, lalu pasang APK baru. Ini wajib sekali saja, karena kuncinya berganti.

## Build di PC (opsional)

Buka folder ini di Android Studio (Koala atau lebih baru, JDK 17), lalu pilih **Build → Build APK(s)**.

## API server yang dipakai

Semua di `lot-card`, file `app/Http/Controllers/Api/MobileController.php`:

| Method | Path | Keterangan |
|---|---|---|
| GET  | `/api/mobile/ping` | cek alamat server (tanpa login) |
| POST | `/api/mobile/login` | `nik`, `password`, `device_name` → `token` |
| GET  | `/api/mobile/me` | cek token |
| POST | `/api/mobile/logout` | hapus token HP ini |
| GET  | `/api/mobile/plans/{kode}` | data Plan (lihat `app/Services/MobilePlanPresenter.php`) |
| GET  | `/api/mobile/input/{kode}` | form input perolehan (`MobileInputPresenter.php`) |
| POST | `/api/mobile/input/{kode}` | simpan perolehan (`AutoResultService.php`) |
| GET  | `/api/mobile/bom-detail/{id}/lots` | lot material tersedia (Additional Material) |

Tampilan data disusun server dalam bentuk "bagian" (judul, label–nilai, tabel). Jadi isi/label bisa diubah dari server tanpa build ulang aplikasi.
