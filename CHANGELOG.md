# Changelog - YoPlayer

All notable changes to YoPlayer are documented in this file.

## [0.1.1] - 2026-03-30

### 🎧 Bit-Perfect USB Audio Driver
- Implemented **"Эксклюзивный доступ к HQ звуку на USB"** (Bit-Perfect Direct USB Audio access):
  - Direct bit-perfect PCM audio streaming to external USB DACs bypassing Android system mixer.
  - Native C++ lock-free PCM RingBuffer and isochronous transfer engine (`usb_dac_native`).
  - Automatic sample rate (44.1 / 48 / 88.2 / 96 / 192 kHz) and bit depth configuration on track change without resampling.
  - Automatic playback pause (`player.pause()`) and clean resource release when USB DAC cable is disconnected.

### 📱 Telegram Music Streaming & Selective Chat Filtering
- Added **Telegram Audio** account integration (*Settings ➔ Telegram Audio*):
  - User-controlled API ID & API Hash inputs (protecting GPL-3.0 compliance & privacy).
  - Local HTTP stream proxy (`TelegramStreamProxy`) for range-request streaming into Media3 without forced full file downloads.
  - **Automatic Noise Filtering**: Automatically filters out voice messages (`isVoiceMessage`) and video notes / circles (`isVideoNote`).
  - **Selective Chat & Channel Selector**: Toggle individual Telegram chats, channels, or Saved Messages ("Избранное") for music scanning.

### 🎨 UI & Ergonomics
- Swapped action buttons in Now Playing for better ergonomics:
  - **Favorite Heart (`♡`)**: Placed next to song title at the top.
  - **Lyrics (`🎵`)**: Placed in the bottom action bar.
- Fully removed Audio Bookmarks (`🔖`) from Now Playing top bar and Library.

## [0.1.0] - 2026-03-30
- Initial release of YoPlayer (`open.yo.player`).
