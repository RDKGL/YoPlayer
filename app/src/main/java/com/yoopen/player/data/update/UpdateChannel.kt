package com.yoopen.player.data.update

/**
 * Where builds of a channel are published. All builds are published via GitHub Releases
 * (https://github.com/RDKGL/YoPlayer/releases).
 */
enum class UpdateChannel(val storageKey: String) {
    STABLE("stable"),
    ALPHA("alpha");

    companion object {
        fun fromStorageKey(key: String?): UpdateChannel? = entries.firstOrNull { it.storageKey == key }

        /**
         * The channel the running build was distributed through.
         * Version names with a pre-release suffix (e.g., -alpha.53, -beta.1) belong to ALPHA.
         */
        fun ofVersionName(versionName: String): UpdateChannel =
            if ('-' in versionName) ALPHA else STABLE
    }
}

/**
 * Semantic version parser handling:
 * - Full releases: `v1.0`, `1.0`, `1.0.1`, `1.2.0`
 * - Pre-releases: `v1.1-alpha.1`, `1.0.0-rc.2`, `0.4.0-alpha.53`
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preReleaseBuild: Int? = null,
) : Comparable<SemanticVersion> {

    val isPreRelease: Boolean get() = preReleaseBuild != null

    override fun compareTo(other: SemanticVersion): Int {
        val coreComparison = compareValuesBy(
            this, other,
            SemanticVersion::major,
            SemanticVersion::minor,
            SemanticVersion::patch
        )
        if (coreComparison != 0) return coreComparison

        // Same major.minor.patch:
        // A full release is newer than a pre-release (e.g. 1.0.0 > 1.0.0-alpha.1)
        return when {
            this.preReleaseBuild == null && other.preReleaseBuild == null -> 0
            this.preReleaseBuild == null && other.preReleaseBuild != null -> 1
            this.preReleaseBuild != null && other.preReleaseBuild == null -> -1
            else -> this.preReleaseBuild!!.compareTo(other.preReleaseBuild!!)
        }
    }

    companion object {
        private val REGEX = Regex(
            """^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-(?:alpha|beta|rc|dev)\.(\d+))?.*$""",
            RegexOption.IGNORE_CASE
        )

        fun parse(value: String): SemanticVersion? {
            val trimmed = value.trim()
            val match = REGEX.matchEntire(trimmed) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val patch = match.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            val pre = match.groupValues.getOrNull(4)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
            return SemanticVersion(major, minor, patch, pre)
        }
    }
}

/** A published build that can be offered to the user. */
data class AppRelease(
    val channel: UpdateChannel,
    val versionName: String,
    val versionCode: Long?,
    val apkUrl: String,
    val apkSizeBytes: Long?,
    val pageUrl: String,
)

/** Whether [release] is newer than the running build. */
internal fun AppRelease.isNewerThan(installedVersionName: String, installedVersionCode: Long): Boolean {
    // 1. If release metadata contains a valid versionCode, check it first
    if (versionCode != null && versionCode > 0L) {
        if (versionCode > installedVersionCode) return true
        if (versionCode < installedVersionCode) return false
    }

    // 2. Semantic version comparison from versionName (e.g. 1.0.1 > 1.0)
    val candidate = SemanticVersion.parse(versionName) ?: return false
    val installed = SemanticVersion.parse(installedVersionName) ?: return true
    return candidate > installed
}
