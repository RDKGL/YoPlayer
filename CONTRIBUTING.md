# Contributing to YoPlayer 🎵

Thanks for your interest in improving YoPlayer! This guide covers the
conventions and steps for contributing to the project.

## Getting Started

```sh
git clone https://github.com/RDKGL/YoPlayer.git
cd YoPlayer

# Build Release APK
./gradlew :app:assembleRelease
```

Requirements: **JDK 21**, Android **compile/target SDK 37**, min SDK 30 (Android 11+).

## Local Verification Before Submitting PRs

Run local verification tasks before opening a PR:

```sh
./gradlew :app:compileDebugKotlin
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest
```

Run a specific unit test class:

```sh
./gradlew :app:testDebugUnitTest --tests "open.yo.player.SomeTestClass"
```

> Unit tests use **JUnit 5 (JUnit Jupiter)** (`useJUnitPlatform()`). Use `org.junit.jupiter.api.Test`.

## Core Code Conventions

- **State Slicing**: Slice `PlayerUiState` rather than observing the whole state to avoid recomposition loops on playback ticks.
- **Immutable Collections**: Prefer `ImmutableList<T>` over `List<T>` for Compose parameters (`kotlinx.collections.immutable`).
- **Safe Navigation**: Use `navController.navigateSafely(...)` to prevent navigation state crashes on fast taps.
- **Logging**: Use Timber (`Timber.d()`, `Timber.e()`) instead of `android.util.Log`.
- **String Resources**: Keep all user-facing strings in `res/values*/strings.xml`.

## Pull Requests & Issues

- Describe changes clearly and verify local test suites pass before submitting PRs.
- License: GNU General Public License v3.0 (GPL-3.0).
