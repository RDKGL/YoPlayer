# Privacy Policy - YoPlayer

YoPlayer is local-first and does not include analytics, advertising SDKs, Firebase, Crashlytics, or Google Play Services runtime dependencies.

## Data Stored On Device

The app stores music library metadata, playlists, favorites, lyrics, preferences, playback state, artwork cache, and optional backup/restore data locally on the device.

## Network Access

Network features are completely optional and user-controlled:

- MusicBrainz lookup runs only when the user explicitly requests metadata enrichment for a track.
- LRCLIB lyric lookup is used only when online lyrics are enabled.
- Deezer artist artwork lookup is used only when online artist images are enabled.
- Update checks contact GitHub Releases API (`https://api.github.com/repos/RDKGL/YoPlayer/releases`) when the user checks for updates.

Server credentials and preferences are stored locally. The app does not sell, upload, or share user data.

## Files And Media

YoPlayer requests media/file permissions to scan and play local music, read artwork, edit metadata, and export/import user backups.

## Crash Reports

The app keeps crash logs locally for troubleshooting. It does not automatically upload crash reports.

## Contact

Open a GitHub issue on [github.com/RDKGL/YoPlayer](https://github.com/RDKGL/YoPlayer) for privacy questions.
