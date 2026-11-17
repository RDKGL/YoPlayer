<p align="center">
  <img src="assets/logo.png" alt="YoPlayer Logo" height="220"/>
</p>

<h1 align="center">YoPlayer</h1>

<p align="center">
  <b>A modern, local-first Android audio player with Bit-Perfect USB DAC support, AutoEQ headphone correction, and smart recommendations.</b>
</p>

<p align="center">
  <a href="https://github.com/RDKGL/YoPlayer/releases/latest">
    <img src="https://img.shields.io/github/v/release/RDKGL/YoPlayer?include_prereleases&logo=github&style=for-the-badge&label=Latest%20Release" alt="Latest Release">
  </a>
  <img src="https://img.shields.io/badge/License-GPLv3-blue?style=for-the-badge" alt="License GPLv3">
  <img src="https://img.shields.io/badge/Android-11%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 11+">
  <a href="https://github.com/PixelPlayerHQ/PixelPlayerOSS">
    <img src="https://img.shields.io/badge/Fork-PixelPlayerOSS-orange?style=for-the-badge" alt="Fork of PixelPlayerOSS">
  </a>
</p>

<p align="center">
  <img src="assets/1.jpg" alt="YoPlayer Screen 1" width="205"/>
  <img src="assets/2.jpg" alt="YoPlayer Screen 2" width="205"/>
  <img src="assets/3.jpg" alt="YoPlayer Screen 3" width="205"/>
  <img src="assets/4.jpg" alt="YoPlayer Screen 4" width="205"/>
</p>

---

## 🎵 Overview

**YoPlayer** (`com.yoopen.player`) is an open-source, audiophile-grade local audio player for Android built with Jetpack Compose and Material 3 Expressive guidelines. Designed for speed, flexibility, and pure high-resolution sound quality.

---

## ✨ Key Features

### 🎧 Audiophile Sound Engine & Bit-Perfect USB DAC
- **Direct USB DAC Output 2.0**: Native C++ lock-free Isochronous PCM ring buffer streaming raw audio directly to external USB DACs, bypassing the Android system audio mixer. Features dynamic clock synchronization (44.1 kHz, 48 kHz, 96 kHz, 192 kHz) and USB Alternate Setting audio activation.
- **Audio Inspector Sheet**: Tap the format badge in Now Playing to inspect track quality metrics (codec, sample rate, bit depth, bitrate, LOSSLESS and HI-RES badges), the complete signal path (Source ➔ Audio Engine ➔ Output Device), and DAC hardware master gain slider.
- **AutoEQ Headphone Integration**: Built-in target compensation profiles for popular headphones (Sony WH-1000XM4/XM5, Sennheiser HD600/650, AirPods Pro/Max, KZ ZSN Pro, Audio-Technica ATH-M50x, Galaxy Buds, etc.) powered by Harman Target curves.
- **10-Band Graphic Equalizer**: Advanced audio processing with Bass Boost, Virtualizer, and Loudness Enhancer.

### 🧠 Smart YoMix Queue & Library
- **Intelligent YoMix Recommendation**: Smart dynamic queue generator balancing track engagement (play count & duration), favorite status, artist & genre similarity, recency penalty, and artist diversity spacing.
- **Dynamic Auto-Queue**: Seamless auto-fill recommendations to keep playback flowing.
- **Tag Editing & Lyrics**: Built-in ID3v2/FLAC metadata editor and synced LRC lyrics integration.

### 🎨 Material 3 Expressive UI & Customization
- **Cover Art Styles**: Choose between 4 distinct styles:
  - **Standard**: Classic rounded corner rectangle (18 dp).
  - **Vinyl**: Rotating vinyl record with groove textures and real-time spinning animation during playback.
  - **Glow**: Dynamic ambient backlight aura that breathes to the music.
  - **Circle**: Modern circular artwork.
- **Player View Customization**:
  - **Text Sizing**: Configurable Mini, Medium, and Large typography for track title and artist.
  - **Title Alignment**: Left and Center alignment with balanced spacing.
  - **Title Position**: Adjust track title placement Above or Below artwork.
  - **Live Preview Customizer**: Real-time interactive customizer with automatic miniplayer collapsing.
- **Ergonomic Button Layout**: Favorite heart button placed top next to title; Lyrics button placed in the bottom action bar.
- **Dynamic Theming**: Album art palette extraction, OLED True-Black mode, and customizable navigation layouts.
- **Instant Launch**: Optimized cold start without splash screen delays.

---

## 🛠️ Build Requirements

| Component | Minimum Version |
| --- | --- |
| Android OS | 11.0+ (API Level 30+) |
| Java Development Kit | JDK 21 |
| Android SDK Target | API Level 37 |
| Build System | Gradle 8.11+ |

```sh
# Clone repository
git clone https://github.com/RDKGL/YoPlayer.git
cd YoPlayer

# Assemble Release APK
./gradlew :app:assembleRelease
```

---

## 📄 License & Acknowledgments

YoPlayer is licensed under the **GNU General Public License v3.0 (GPL-3.0)**.
Originally forked from [PixelPlayerHQ/PixelPlayerOSS](https://github.com/PixelPlayerHQ/PixelPlayerOSS).
