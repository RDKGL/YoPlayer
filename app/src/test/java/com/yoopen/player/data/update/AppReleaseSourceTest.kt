package com.yoopen.player.data.update

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test

class AppReleaseSourceTest {

    private val packageName = "com.yoopen.player"

    @Test
    fun `stable release picks latest non-prerelease from GitHub`() {
        val releases = JSONArray(
            listOf(
                release("v1.1-alpha.1", prerelease = true),
                release("v1.0", prerelease = false),
                release("v0.4.0", prerelease = false),
            ).joinToString(prefix = "[", postfix = "]")
        )

        val release = AppReleaseSource.parseLatestGithubRelease(releases, UpdateChannel.STABLE, listOf("arm64-v8a"))!!

        assertThat(release.versionName).isEqualTo("1.0")
        assertThat(release.apkUrl).endsWith("/YoPlayer-1.0-arm64-v8a.apk")
    }

    @Test
    fun `semantic version ordering correctly compares stable and alpha builds`() {
        val stable10 = AppRelease(UpdateChannel.STABLE, "1.0", null, "https://example.com/app.apk", null, "https://example.com")
        val stable101 = AppRelease(UpdateChannel.STABLE, "1.0.1", null, "https://example.com/app.apk", null, "https://example.com")
        val stable11 = AppRelease(UpdateChannel.STABLE, "1.1", null, "https://example.com/app.apk", null, "https://example.com")
        val alpha11 = AppRelease(UpdateChannel.ALPHA, "1.1-alpha.1", null, "https://example.com/app.apk", null, "https://example.com")

        assertThat(stable101.isNewerThan("1.0", 2)).isTrue()
        assertThat(stable11.isNewerThan("1.0", 2)).isTrue()
        assertThat(stable10.isNewerThan("1.0", 2)).isFalse()
        assertThat(stable10.isNewerThan("0.4.0-alpha.53", 2)).isTrue()
        assertThat(stable11.isNewerThan("1.1-alpha.1", 2)).isTrue()
        assertThat(alpha11.isNewerThan("1.0", 2)).isTrue()
    }

    @Test
    fun `newest alpha is picked by version even when the API lists it later`() {
        val releases = JSONArray(
            listOf(
                release("v0.3.0-alpha.9"),
                release("v0.3.0-alpha.16"),
                release("v0.3.0-alpha.15"),
            ).joinToString(prefix = "[", postfix = "]")
        )

        val latest = AppReleaseSource.parseLatestAlpha(releases, listOf("arm64-v8a"))!!

        assertThat(latest.versionName).isEqualTo("0.3.0-alpha.16")
        assertThat(latest.apkUrl).endsWith("/YoPlayer-0.3.0-alpha.16-arm64-v8a.apk")
    }

    @Test
    fun `alpha asset follows the device ABI preference order`() {
        val releases = JSONArray("[${release("v0.3.0-alpha.16")}]")

        val latest = AppReleaseSource.parseLatestAlpha(releases, listOf("armeabi-v7a", "arm64-v8a"))!!

        assertThat(latest.apkUrl).endsWith("-armeabi-v7a.apk")
    }

    @Test
    fun `drafts, stable tags and releases without a matching APK are not offered`() {
        val releases = JSONArray(
            listOf(
                release("v0.3.0-alpha.20", draft = true),
                release("v0.4.0", prerelease = false),
                release("v0.3.0-alpha.19", abis = listOf("x86_64")),
                release("v0.3.0-alpha.18"),
            ).joinToString(prefix = "[", postfix = "]")
        )

        assertThat(AppReleaseSource.parseLatestAlpha(releases, listOf("arm64-v8a"))!!.versionName)
            .isEqualTo("0.3.0-alpha.18")
        assertThat(AppReleaseSource.parseLatestAlpha(releases, listOf("x86"))).isNull()
    }

    @Test
    fun `alpha versions order by base version before build number`() {
        val olderBaseHigherBuild = alphaRelease("0.3.0-alpha.99")
        val newerBase = alphaRelease("0.4.0-alpha.1")

        assertThat(newerBase.isNewerThan("0.3.0-alpha.99", installedVersionCode = 300099)).isTrue()
        assertThat(olderBaseHigherBuild.isNewerThan("0.4.0-alpha.1", installedVersionCode = 400001)).isFalse()
        assertThat(alphaRelease("0.3.0-alpha.16").isNewerThan("0.3.0-alpha.16", 300016)).isFalse()
    }

    @Test
    fun `PR builds on the alpha key are offered any published alpha`() {
        assertThat(alphaRelease("0.3.0-alpha.16").isNewerThan("0.3.0-pr.137.ba94188", 3)).isTrue()
    }

    @Test
    fun `installed channel follows the version name published by each pipeline`() {
        assertThat(UpdateChannel.ofVersionName("0.3.0")).isEqualTo(UpdateChannel.STABLE)
        assertThat(UpdateChannel.ofVersionName("0.3.0-alpha.16")).isEqualTo(UpdateChannel.ALPHA)
        assertThat(UpdateChannel.ofVersionName("0.3.0-pr.137.ba94188")).isEqualTo(UpdateChannel.ALPHA)
    }

    @Test
    fun `calculateDelayUntil12Noon returns correct duration until next 12pm`() {
        val cal10am = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 10)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val delayFrom10am = UpdateCheckScheduler.calculateDelayUntil12Noon(cal10am.timeInMillis)
        assertThat(delayFrom10am).isEqualTo(2 * 60 * 60 * 1000L) // exactly 2 hours

        val cal14pm = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 14)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val delayFrom14pm = UpdateCheckScheduler.calculateDelayUntil12Noon(cal14pm.timeInMillis)
        assertThat(delayFrom14pm).isEqualTo(22 * 60 * 60 * 1000L) // exactly 22 hours
    }

    private fun alphaRelease(versionName: String) = AppRelease(
        channel = UpdateChannel.ALPHA,
        versionName = versionName,
        versionCode = null,
        apkUrl = "https://example.invalid/app.apk",
        apkSizeBytes = null,
        pageUrl = "https://example.invalid",
    )

    private fun release(
        tag: String,
        prerelease: Boolean = true,
        draft: Boolean = false,
        abis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
    ): String {
        val name = tag.removePrefix("v")
        val assets = abis.joinToString(prefix = "[", postfix = "]") { abi ->
            """{"name":"YoPlayer-$name-$abi.apk","size":37082259,
               "browser_download_url":"https://github.com/RDKGL/YoPlayer/releases/download/$tag/YoPlayer-$name-$abi.apk"}"""
        }
        return """{"tag_name":"$tag","prerelease":$prerelease,"draft":$draft,
                   "html_url":"https://github.com/RDKGL/YoPlayer/releases/tag/$tag","assets":$assets}"""
    }
}
