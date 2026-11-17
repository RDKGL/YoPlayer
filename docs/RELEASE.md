# Release Checklist - YoPlayer

YoPlayer releases are shipped from `main` after local checks and a basic device smoke test pass.

## Versioning

Version values live in `gradle.properties`:

```properties
APP_VERSION_NAME=1.0
APP_VERSION_CODE=2
```

For every public release:

1. Update `APP_VERSION_NAME`.
2. Increment `APP_VERSION_CODE`.
3. Update `CHANGELOG.md`.
4. Tag the release as `v<APP_VERSION_NAME>`, for example `v1.0`.

## Required Local Checks

Run these before tagging:

```sh
./gradlew :app:compileDebugKotlin
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease
```

## Publishing

1. Ensure `main` is clean and pushed to `https://github.com/RDKGL/YoPlayer`.
2. Create a tag: `git tag v1.0`.
3. Push the tag: `git push origin v1.0`.
4. Create a GitHub release on `https://github.com/RDKGL/YoPlayer/releases`.
5. Attach the release APK artifact (`app-arm64-v8a-release.apk`) and paste the changelog section.
