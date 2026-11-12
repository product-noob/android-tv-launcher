package me.prince.launcher

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette

// Favourites until changed from the long-press menu (Move / Add / Remove); anything not installed is skipped.
// Find package names with: adb shell pm list packages -3
private val DEFAULT_FAVOURITES = listOf(
    "com.netflix.ninja",                  // Netflix
    "com.google.android.youtube.tv",      // YouTube
    "com.amazon.amazonvideo.livingroom",  // Prime Video
    "in.startv.hotstar",                  // JioHotstar
    "com.disney.disneyplus",              // Disney+
)

private const val PREFS = "launcher"
private const val KEY_FAVOURITES = "favourites"
private const val RECENT_DAYS = 14L

private const val BANNER_WIDTH = 400
private const val BANNER_HEIGHT = 225
private val FALLBACK_GLOW = Color(0xFF3A4A8C)

data class TvApp(
    val packageName: String,
    val label: String,
    val image: ImageBitmap,
    val hasBanner: Boolean,
    val glow: Color,
    val launchIntent: Intent,
    val isInput: Boolean = false,
    // Second line on tiles without a banner, e.g. the HDMI port
    val detail: String? = null,
)

// All TV apps, alphabetical
fun loadApps(context: Context): List<TvApp> {
    val pm = context.packageManager
    val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
    val apps = pm.queryIntentActivities(query, 0)
        .filter { it.activityInfo.packageName != context.packageName }
        .distinctBy { it.activityInfo.packageName }
        .map { toTvApp(context, it) }
    return apps.sortedBy { it.label.lowercase() }
}

fun loadFavourites(context: Context): List<String> =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_FAVOURITES, null)
        ?.split(',')
        ?.filter { it.isNotBlank() }
        ?: DEFAULT_FAVOURITES

fun saveFavourites(context: Context, packages: List<String>) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_FAVOURITES, packages.joinToString(","))
        .apply()
}

// The All apps row's order after Move; apps not in it follow alphabetically
private const val KEY_ORDER = "order"

fun loadAppOrder(context: Context): List<String> =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_ORDER, null)
        ?.split(',')
        ?.filter { it.isNotBlank() }
        .orEmpty()

fun saveAppOrder(context: Context, packages: List<String>) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_ORDER, packages.joinToString(","))
        .apply()
}

// Most recent first. Empty until granted: adb shell appops set me.prince.launcher GET_USAGE_STATS allow
fun recentPackages(context: Context): List<String> {
    val usage = context.getSystemService(UsageStatsManager::class.java)
    val end = System.currentTimeMillis()
    // The screensaver counts as foreground use every time it runs
    val screensavers = Settings.Secure.getString(context.contentResolver, "screensaver_components")
        .orEmpty()
        .split(',')
        .map { it.substringBefore('/') }
    return usage.queryUsageStats(UsageStatsManager.INTERVAL_BEST, end - RECENT_DAYS * 86_400_000L, end)
        .filter { it.totalTimeInForeground > 0 && it.packageName !in screensavers }
        .groupBy { it.packageName }
        .mapValues { (_, stats) -> stats.maxOf { it.lastTimeUsed } }
        .entries
        .sortedByDescending { it.value }
        .map { it.key }
}

private fun toTvApp(context: Context, info: ResolveInfo): TvApp {
    val pm = context.packageManager
    val activity = info.activityInfo
    val banner = activity.loadBanner(pm)
    val drawable = banner ?: activity.loadIcon(pm)
    val bitmap = if (banner != null) {
        drawable.toBitmap(BANNER_WIDTH, BANNER_HEIGHT)
    } else {
        drawable.toBitmap(128, 128)
    }
    val intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        .setClassName(activity.packageName, activity.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    return TvApp(
        packageName = activity.packageName,
        label = info.loadLabel(pm).toString(),
        image = bitmap.asImageBitmap(),
        hasBanner = banner != null,
        glow = glowColor(bitmap),
        launchIntent = intent,
    )
}

// Brand colour of the banner, e.g. Netflix red rather than its white background
private fun glowColor(bitmap: android.graphics.Bitmap): Color {
    val palette = Palette.from(bitmap).generate()
    val swatch = palette.vibrantSwatch
        ?: palette.darkVibrantSwatch
        ?: palette.mutedSwatch
        ?: palette.dominantSwatch
        ?: return FALLBACK_GLOW
    val color = Color(swatch.rgb)
    if (color.luminance() > 0.8f || color.luminance() < 0.02f) return FALLBACK_GLOW
    return lerp(color, Color.Black, 0.15f)
}
