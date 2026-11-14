# Changelog - YoPlayer

All notable changes to YoPlayer are documented in this file.

## [0.1.1] - 2026-03-30

### 🎧 Audiophile Engine & Bit-Perfect USB DAC
- **Direct USB DAC Output Driver**: Native C++ lock-free PCM ring buffer engine streaming raw audio directly to external USB DACs, bypassing the Android system audio mixer.
- **AutoEQ Headphone Integration**: Integrated AutoEQ target compensation profiles for popular headphones (Sony WH-1000XM4/XM5, Sennheiser HD 600/650, AirPods Pro/Max, KZ ZSN Pro, Audio-Technica ATH-M50x, Galaxy Buds2 Pro, etc.) with Harman Target curves.
- **Enhanced Audio Quality Badge**: Now Playing screen displays real-time bit depth (16-bit / 24-bit / 32-bit), sample rate (kHz), bitrate (kbps), and an active `⚡ Bit-Perfect USB` indicator.

### 🧠 Smart YoMix Engine
- **Upgraded Recommendation Algorithm**: Dynamically generates queues balancing play counts, completion rate, favorite status, artist/genre similarity, and recency penalty.
- **Continuous Queue Auto-Fill**: Automatically appends matching smart recommendations when approaching the end of queue.

### 🎨 UI & Ergonomics
- **Player View Customizer**: Added customizable track title alignment (Left / Center) and vertical position settings with live preview.
- **Ergonomic Button Swaps**:
  - Moved **Favorite Heart (`♡`)** next to the track title at the top.
  - Moved **Lyrics (`🎵`)** to the bottom action bar.
- Fully removed legacy Bookmarks button and navigation items.

## [0.1.0] - 2026-03-30
- Initial release of YoPlayer (`open.yo.player`).
