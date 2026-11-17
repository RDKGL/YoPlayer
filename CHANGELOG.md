# Changelog - YoPlayer

All notable changes to YoPlayer are documented in this file.

## [1.0] - 2026-03-30

### 🎧 Audiophile Engine & Bit-Perfect USB DAC 2.0
- **Audio Inspector Sheet**: Tap the format badge under the progress bar to inspect complete track quality metrics (codec, sample rate in kHz, bit depth, bitrate, LOSSLESS / HI-RES tags), full signal path (Source ➔ Audio Engine ➔ Output Device), and DAC hardware master gain slider in dB and %.
- **Isochronous Native Engine**: Upgraded C++ lock-free ring buffer engine with sub-sample millisecond accumulator for jitter-free clock synchronization (44.1k, 48k, 96k, 192k) with zero buffer underruns.
- **Alternate Setting Activation**: Added proper USB `setInterface` switching to activate the streaming audio pipe on UAC1 and UAC2 devices.
- **Seamless Live Handover**: Direct USB AudioSink dynamically routes playback to the DAC on connection mid-song and gracefully pauses when disconnected.
- **AutoEQ Headphone Integration**: Integrated target compensation profiles for popular headphones (Sony WH-1000XM4/XM5, Sennheiser HD 600/650, AirPods Pro/Max, KZ ZSN Pro, Audio-Technica ATH-M50x, Galaxy Buds2 Pro, etc.) with Harman Target curves.

### 🎨 Visual Customizer & Cover Styles
- **Cover Art Styles**: Added 4 distinct album art styles:
  - **Standard**: Classic 18 dp rounded corner rectangle.
  - **Vinyl**: Rotating vinyl disc with groove textures and real-time physical spinning animation during playback.
  - **Glow**: Ambient backlight aura pulsing to the rhythm of the track.
  - **Circle**: Modern circular crop.
- **Typography & Alignment**:
  - Added **Text Size** selector: Mini, Medium, and Large fonts for track title and artist.
  - Added **Title Alignment**: Left and Center alignments with balanced spacing against action buttons.
  - Added **Title Position**: Place title block Above or Below artwork.
- **Player Customizer Screen**: Overhauled with live interactive preview and automatic miniplayer collapsing so controls are never obscured.

### 🧹 Interface Cleanup & Optimization
- **Home Collage Removed**: Removed unused home collage settings and search registry entries for a cleaner appearance settings experience.
- **Full Internationalization**: Complete translations across all 13 supported languages.
- **R8 & Build Optimizations**: Minified release APK with C++ NDK native builds for `arm64-v8a` and `armeabi-v7a`.

## [0.1.0] - 2026-03-30
- Initial release of YoPlayer (`com.yoopen.player`).
