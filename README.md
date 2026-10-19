# RK Launcher

Launcher Android native bergaya iOS dengan prioritas pada perangkat lama. Project menggunakan Kotlin dan Android Views tanpa library UI pihak ketiga.

## Fitur yang tersedia di versi 1.0.0

- Memilih RK Launcher sebagai aplikasi Home/default launcher Android.
- Menemukan aplikasi yang memiliki aktivitas launcher, menampilkan ikon/nama, dan membuka aplikasi dengan satu sentuhan.
- Layar favorit, dock dengan hingga empat aplikasi, pencarian aplikasi, dan menu tekan-lama untuk mengelola favorit atau membuka info aplikasi.
- Panel kontrol bergaya kaca: slider kecerahan khusus jendela launcher, volume media, senter dengan izin kamera saat diperlukan, dan pintasan ke halaman Setelan Android.
- Dynamic Island simulasi yang dapat dinyalakan/dimatikan dan menampilkan level baterai yang dibaca dari Android.
- Pusat notifikasi opsional melalui `NotificationListenerService`; hanya aktif setelah akses notifikasi diberikan manual oleh pengguna di Setelan Android, dengan daftar lokal yang dibatasi hingga 40 item.
- Tema gradien Ocean Blue, Plum Glass, dan Mono Dark.
- Mode Lite aktif secara default; tidak ada animasi berat, jaringan, akun, tracker, atau service persisten.
- Pratinjau visual lock screen (bukan pengganti lock screen keamanan Android).
- Daftar aplikasi dan pemuatan ikon berlangsung dengan daftar yang dibatasi dan pemuatan tampilan GridView secara bertahap.

## Kompatibilitas

- Minimum SDK: Android 8.0 Oreo (API 26).
- Target SDK: API 28 untuk menjaga kompatibilitas dengan mekanisme visibilitas paket pada perangkat launcher lawas dan membatasi perubahan perilaku pada versi Android yang lebih baru. Penggunaan untuk distribusi Google Play memerlukan peninjauan serta pembaruan target SDK sesuai kebijakan Play yang berlaku.
- Build menggunakan Android Gradle Plugin 8.7.3, Gradle 8.9, Kotlin 2.0.21, dan JDK 17.

## Cara build melalui GitHub

1. Buat repository baru di GitHub.
2. Unggah semua isi folder ini ke root repository, termasuk folder `.github`.
3. Buka tab **Actions** dan jalankan workflow **Build RK Launcher APK** (atau tunggu workflow berjalan setelah push).
4. Setelah selesai, buka run workflow tersebut dan unduh artifact **rk-launcher-debug-apk**.
5. Pindahkan APK ke perangkat Android untuk pengujian. Android mungkin meminta izin memasang aplikasi dari sumber tersebut.

Workflow GitHub Actions akan menyiapkan Java dan Android SDK, membangun `assembleDebug`, lalu menyimpan APK sebagai artifact. Build yang berhasil di GitHub tetap perlu diuji pada perangkat target.

## Build lokal

Diperlukan JDK 17, Android SDK Platform 35, Android Build Tools 35.0.0, dan Gradle 8.9. Dari folder project jalankan:

```bash
gradle assembleDebug
```

APK debug akan ditempatkan di `app/build/outputs/apk/debug/app-debug.apk`. Untuk penggunaan lokal jangka panjang, buat Gradle Wrapper di lingkungan yang memiliki Gradle terpasang:

```bash
gradle wrapper --gradle-version 8.9
gradlew assembleDebug
```

Android Studio juga dapat digunakan untuk membuka folder project dan menyinkronkan konfigurasi Gradle jika konfigurasi Gradle pada komputer tersedia.

## Memilih launcher default

Setelah APK terpasang, tekan tombol Home perangkat. Pilih **RK Launcher** pada daftar aplikasi Home, lalu pilih **Selalu** bila tersedia. Kamu juga dapat membuka menu **Setelan → Pilih launcher default** di dalam RK Launcher.

## Hal yang sengaja tidak diklaim

Launcher pihak ketiga tidak dapat menggantikan keseluruhan iOS. Pada Android biasa, perubahan Wi-Fi/Bluetooth langsung dibatasi oleh sistem; tombol RK Launcher membuka halaman Setelan terkait. Slider kecerahan versi ini mengatur kecerahan jendela launcher saja. Pratinjau lock screen tidak menggantikan proteksi layar kunci. Dynamic Island di sini adalah simulasi, bukan Live Activities milik Apple. Control Center, pusat notifikasi iPhone, Siri/Apple Intelligence, Face ID, iMessage, AirDrop, dan iCloud tidak diduplikasi secara penuh.

## Pemeriksaan sebelum rilis

- Build berhasil untuk debug/release.
- Instalasi dan pemilihan launcher diuji pada Android 8.0/8.1.
- Pembukaan aplikasi, pencarian, favorit, dock, tema, dan tombol kembali diuji.
- Senter diuji dengan izin diberikan/ditolak dan perangkat tanpa flash.
- Pusat notifikasi diuji setelah akses khusus diaktifkan/dimatikan, termasuk notifikasi yang dihapus dari sistem.
- Uji rotasi, proses aplikasi dihentikan sistem, restart perangkat, dan perangkat dengan RAM rendah.
- Periksa Logcat untuk crash dan ANR.

Kode ini adalah fondasi versi pertama, bukan jaminan bahwa bug mustahil terjadi. Jangan publikasikan sebagai "anti-bug 100%" sebelum pengujian perangkat nyata.

## Lisensi

MIT. Lihat `LICENSE`.
