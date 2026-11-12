package me.prince.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

private const val TAG = "Trending"
private const val API = "https://api.themoviedb.org/3"
private const val IMAGES = "https://image.tmdb.org/t/p"
private const val MAX_TITLES = 8
private const val STREMIO = "com.stremio.one"

// Which country's streaming catalogue to match (tmdb.region in local.properties), else the TV's own country
private val REGION = BuildConfig.TMDB_REGION.ifBlank { Locale.getDefault().country.ifBlank { "US" } }.uppercase(Locale.US)
// all, movie or tv (tmdb.feed in local.properties)
private val FEED = BuildConfig.TMDB_FEED.ifBlank { "all" }

// TMDB watch-provider id → the Android TV app that streams it. The button opens the first installed one.
// Add your own: provider ids are listed at https://api.themoviedb.org/3/watch/providers/tv (needs your key).
private val PROVIDER_APPS = mapOf(
    8 to "com.netflix.ninja",                       // Netflix
    9 to "com.amazon.amazonvideo.livingroom",       // Amazon Prime Video
    119 to "com.amazon.amazonvideo.livingroom",     // Amazon Prime Video
    337 to "com.disney.disneyplus",                 // Disney+
    122 to "in.startv.hotstar",                     // Hotstar
    2336 to "in.startv.hotstar",                    // JioHotstar
    350 to "com.apple.atve.androidtv.appletv",      // Apple TV+
    15 to "com.hulu.livingroomplus",                // Hulu
    531 to "com.cbs.ott",                           // Paramount+
)

data class TrendingTitle(
    val title: String,
    val meta: String,
    val overview: String,
    // Kept compressed (~200KB); decoded on demand, since 8 decoded 1280px backdrops cost ~30MB
    val backdropJpeg: ByteArray,
    // Blurred copy for glass surfaces drawn over it
    val frosted: ImageBitmap,
    // Null when TMDB has no logo or it's too dark to read on the backdrop. Compressed like the backdrop.
    val logoPng: ByteArray?,
    val action: Intent?,
    val actionLabel: String?,
)

// This week's trending titles from TMDB. Empty (and the section hidden) without a key or network.
// Get a free key at themoviedb.org → Settings → API, then put tmdb.key=... in local.properties.
fun fetchTrending(context: Context): List<TrendingTitle> {
    val key = BuildConfig.TMDB_KEY
    if (key.isBlank()) return emptyList()
    return try {
        val results = JSONObject(tmdbGet("/trending/$FEED/week", key)).getJSONArray("results")
        results.objects()
            .filter { it.optString("media_type") in setOf("movie", "tv") && !it.isNull("backdrop_path") }
            .take(MAX_TITLES)
            .mapNotNull { item ->
                // One bad title (missing image, odd JSON) shouldn't drop the whole row
                try {
                    toTitle(context, key, item)
                } catch (e: IOException) {
                    Log.w(TAG, "Skipping ${item.optInt("id")}", e)
                    null
                } catch (e: JSONException) {
                    Log.w(TAG, "Skipping ${item.optInt("id")}", e)
                    null
                }
            }
    } catch (e: IOException) {
        Log.w(TAG, "Trending fetch failed", e)
        emptyList()
    } catch (e: JSONException) {
        Log.w(TAG, "Unexpected TMDB response", e)
        emptyList()
    }
}

// [opens]: the button, when it's already known (the app whose top titles these are); else looked up per title
private fun toTitle(context: Context, key: String, item: JSONObject, opens: Pair<Intent?, String?>? = null): TrendingTitle {
    val type = item.getString("media_type")
    val append = if (opens == null) "external_ids,watch/providers,images" else "images"
    val details = JSONObject(tmdbGet("/$type/${item.getInt("id")}?append_to_response=$append&include_image_language=en,null", key))
    val title = item.optString("title").ifBlank { item.optString("name") }
    val year = item.optString("release_date").ifBlank { item.optString("first_air_date") }.take(4)
    val genre = details.optJSONArray("genres")?.optJSONObject(0)?.optString("name").orEmpty()
    val rating = item.optDouble("vote_average", 0.0)
    val meta = listOf(
        if (type == "tv") "Series" else "Movie",
        year,
        genre,
        if (rating > 0) String.format(Locale.US, "★ %.1f", rating) else "",
    ).filter { it.isNotBlank() }.joinToString("  ·  ")

    val logoPath = details.optJSONObject("images")?.optJSONArray("logos")?.objects()
        ?.firstOrNull { it.optString("iso_639_1") == "en" }
        ?.optString("file_path")
    val logoPng = logoPath?.let { httpBytes("$IMAGES/w500$it") }?.takeIf { isLightEnough(decode(it)) }

    val (action, label) = opens ?: watchAction(context, type, details)
    val backdropJpeg = httpBytes("$IMAGES/w1280${item.getString("backdrop_path")}")
    return TrendingTitle(
        title = title,
        meta = meta,
        overview = item.optString("overview"),
        backdropJpeg = backdropJpeg,
        frosted = frostedBackdrop(decode(backdropJpeg), BACKDROP_HEIGHT_FRACTION),
        logoPng = logoPng,
        action = action,
        actionLabel = label,
    )
}

// An installed app that streams it in your region, else Stremio's page for it (if Stremio is installed)
private fun watchAction(context: Context, type: String, details: JSONObject): Pair<Intent?, String?> {
    val pm = context.packageManager
    val providers = details.optJSONObject("watch/providers")?.optJSONObject("results")
        ?.optJSONObject(REGION)?.optJSONArray("flatrate")?.objects().orEmpty()
    for (provider in providers) {
        val pkg = PROVIDER_APPS[provider.optInt("provider_id")] ?: continue
        val launch = pm.getLeanbackLaunchIntentForPackage(pkg) ?: continue
        return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) to "Watch on ${provider.optString("provider_name")}"
    }
    val imdb = details.optJSONObject("external_ids")?.optString("imdb_id").orEmpty()
    if (imdb.isBlank() || pm.getLeanbackLaunchIntentForPackage(STREMIO) == null) return null to null
    val stremioType = if (type == "tv") "series" else "movie"
    return Intent(Intent.ACTION_VIEW, Uri.parse("stremio:///detail/$stremioType/$imdb"))
        .setPackage(STREMIO)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) to "Open in Stremio"
}

fun TrendingTitle.decodeBackdrop(): ImageBitmap = decode(backdropJpeg).asImageBitmap()
fun TrendingTitle.decodeLogo(): ImageBitmap? = logoPng?.let { decode(it).asImageBitmap() }

// Streaming apps whose tile fills the top of the home screen with their most popular titles, like tvOS's top shelf:
// package → TMDB watch-provider ids ("|" = either). YouTube has no TMDB catalogue. Ids: /watch/providers/tv?watch_region=IN
val TOP_SHELF_PROVIDERS = mapOf(
    "com.netflix.ninja" to "8",
    "com.amazon.amazonvideo.livingroom" to "119",
    "in.startv.hotstar" to "2336",                    // JioHotstar
    "com.sonyliv" to "237",                           // Sony LIV
    "com.apple.atve.androidtv.appletv" to "350",      // Apple TV app (Apple TV+)
)
private const val TOP_SHELF_TITLES = 6
// "Popular now", not "popular ever": shows that started in the last two years (with any recent episode, 25-year-old
// procedurals and wrestling win), movies released in the last year
private const val TOP_SHELF_SHOW_DAYS = 730L
private const val TOP_SHELF_MOVIE_DAYS = 365L
// News, reality, soap, talk
private const val TOP_SHELF_SKIP_TV_GENRES = "10763,10764,10766,10767"

// What's popular on one streaming app in REGION right now. TMDB has no per-service charts, so this is TMDB
// popularity among titles that app streams. The button opens the app (TMDB doesn't know Netflix/Prime title ids).
fun fetchTopShelf(context: Context, packageName: String, appLabel: String): List<TrendingTitle> {
    val key = BuildConfig.TMDB_KEY
    val providers = TOP_SHELF_PROVIDERS[packageName]
    if (key.isBlank() || providers == null) return emptyList()
    val open = context.packageManager.getLeanbackLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val today = java.time.LocalDate.now()
    val common = "watch_region=$REGION&with_watch_providers=${Uri.encode(providers)}&with_watch_monetization_types=flatrate" +
        "&sort_by=popularity.desc&include_adult=false"
    return try {
        val shows = JSONObject(tmdbGet(
            "/discover/tv?$common&first_air_date.gte=${today.minusDays(TOP_SHELF_SHOW_DAYS)}&without_genres=$TOP_SHELF_SKIP_TV_GENRES", key
        )).getJSONArray("results").objects().filter { !it.isNull("backdrop_path") }.onEach { it.put("media_type", "tv") }
        val movies = JSONObject(tmdbGet(
            "/discover/movie?$common&primary_release_date.gte=${today.minusDays(TOP_SHELF_MOVIE_DAYS)}", key
        )).getJSONArray("results").objects().filter { !it.isNull("backdrop_path") }.onEach { it.put("media_type", "movie") }
        // Alternate show, movie, show...: movie popularity runs far higher, so a merged sort would be all movies
        (0 until maxOf(shows.size, movies.size)).flatMap { listOfNotNull(shows.getOrNull(it), movies.getOrNull(it)) }
            .take(TOP_SHELF_TITLES)
            .mapNotNull { item ->
                try {
                    toTitle(context, key, item, open to "Open $appLabel")
                } catch (e: IOException) {
                    Log.w(TAG, "Skipping ${item.optInt("id")}", e)
                    null
                } catch (e: JSONException) {
                    Log.w(TAG, "Skipping ${item.optInt("id")}", e)
                    null
                }
            }
    } catch (e: IOException) {
        Log.w(TAG, "Top shelf fetch failed for $packageName", e)
        emptyList()
    } catch (e: JSONException) {
        Log.w(TAG, "Unexpected TMDB response for $packageName", e)
        emptyList()
    }
}

// Accepts both the v3 API key and the v4 read access token (a JWT, starts with "eyJ")
private fun tmdbGet(path: String, key: String): String {
    val url = "$API$path"
    if (key.startsWith("eyJ")) return httpGet(url, mapOf("Authorization" to "Bearer $key"))
    val separator = if ('?' in path) '&' else '?'
    return httpGet("$url${separator}api_key=$key")
}

private fun decode(bytes: ByteArray): Bitmap =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("Undecodable image")

// Many TMDB logos are black text, invisible on a dark backdrop; sample opaque pixels and reject dark ones
private fun isLightEnough(logo: Bitmap): Boolean {
    var total = 0f
    var count = 0
    for (x in 0 until logo.width step 8) {
        for (y in 0 until logo.height step 8) {
            val pixel = logo.getPixel(x, y)
            if (pixel ushr 24 < 128) continue
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            total += (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            count++
        }
    }
    return count > 0 && total / count > 0.45f
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
