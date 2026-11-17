package com.yoopen.player.data.update

import android.os.Build
import com.yoopen.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Looks up the newest published build of each channel from GitHub Releases. */
@Singleton
class AppReleaseSource @Inject constructor(
    baseClient: OkHttpClient,
) {
    private val client = baseClient.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "YoPlayer/${BuildConfig.VERSION_NAME} ($PROJECT_URL)")
                    .build()
            )
        }
        .build()

    /** Newest release of [channel], or null when the channel has nothing installable for this device. */
    suspend fun latest(channel: UpdateChannel): AppRelease? = withContext(Dispatchers.IO) {
        val releases = JSONArray(getBody(GITHUB_RELEASES_API))
        parseLatestGithubRelease(releases, channel, Build.SUPPORTED_ABIS.toList())
    }

    private fun getBody(url: String): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $url")
            return response.body.string()
        }
    }

    companion object {
        private const val PROJECT_URL = "https://github.com/RDKGL/YoPlayer"

        internal const val PUBLISHED_PACKAGE = "com.yoopen.player"
        private const val GITHUB_RELEASES_API =
            "https://api.github.com/repos/RDKGL/YoPlayer/releases?per_page=30"

        internal fun parseLatestGithubRelease(
            releases: JSONArray,
            channel: UpdateChannel,
            supportedAbis: List<String>
        ): AppRelease? {
            return (0 until releases.length())
                .mapNotNull { releases.optJSONObject(it) }
                .filter { release ->
                    val isPrerelease = release.optBoolean("prerelease")
                    val isDraft = release.optBoolean("draft")
                    if (isDraft) return@filter false
                    // STABLE channel: official releases only (not prereleases).
                    // ALPHA channel: all published builds (prereleases and official releases).
                    if (channel == UpdateChannel.STABLE) !isPrerelease else true
                }
                .mapNotNull { release ->
                    val tag = release.optString("tag_name")
                    val asset = pickAsset(release.optJSONArray("assets"), supportedAbis) ?: return@mapNotNull null
                    AppRelease(
                        channel = channel,
                        versionName = tag.removePrefix("v"),
                        versionCode = null,
                        apkUrl = asset.optString("browser_download_url"),
                        apkSizeBytes = asset.optLong("size", -1L).takeIf { it > 0 },
                        pageUrl = release.optString("html_url").ifBlank { "$PROJECT_URL/releases/tag/$tag" },
                    )
                }
                .firstOrNull()
        }

        internal fun parseLatestAlpha(releases: JSONArray, supportedAbis: List<String>): AppRelease? =
            parseLatestGithubRelease(releases, UpdateChannel.ALPHA, supportedAbis)

        private fun pickAsset(assets: JSONArray?, supportedAbis: List<String>): JSONObject? {
            if (assets == null) return null
            val apks = (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .filter {
                    it.optString("name").endsWith(".apk") && it.optString("browser_download_url").isNotBlank()
                }
            return supportedAbis.firstNotNullOfOrNull { abi ->
                apks.firstOrNull { it.optString("name").endsWith("-$abi.apk") }
            } ?: apks.firstOrNull()
        }
    }
}
