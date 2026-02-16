# 📱 WebRTC Native Video Call App (Android & iOS)

![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-purple?style=flat&logo=kotlin)
![Swift](https://img.shields.io/badge/Swift-5.0-orange?style=flat&logo=swift)
![WebRTC](https://img.shields.io/badge/WebRTC-M114-blue)

Bu proje, **Native Android (Kotlin)** ve **Native iOS (Swift)** kullanarak geliştirilmiştir,.Bir görüntülü görüşme uygulamasıdır. Bağlantı altyapısı için Google WebRTC ve sinyalleşme (signaling) için Socket.io kullanılmıştır.

## 🚀 Özellikler

* **1'e 1 Görüntülü ve Sesli Görüşme:** P2P (Eşler arası) bağlantı.
* **Kamera Kontrolleri:** Ön/Arka kamera geçişi, kamera açma/kapatma.
* **Ses Kontrolleri:** Mikrofonu sessize alma (Mute).
* **Bağlantı Yönetimi:** Bağlanıyor, Bağlandı ve Sonlandırıldı durumları.
* **Cross-Platform:** Android ve iOS cihazlar birbiriyle görüşebilir.

## 📂 Proje Yapısı

```bash
.
├── android-app/       # Native Android (Kotlin) kaynak kodları
├── ios-app/           # Native iOS (Swift) kaynak kodları
└── signaling-server/  # Node.js tabanlı sinyal sunucusu (Gerekli)
