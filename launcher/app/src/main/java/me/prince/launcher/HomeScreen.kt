package me.prince.launcher

import android.content.Context
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.animateDpAsState
import android.app.Activity
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.tv.material3.Glow
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Pure black switches OLED pixels off entirely
private val Background = Color.Black
private val TextPrimary = Color.White
private val TextSecondary = Color.White.copy(alpha = 0.75f)

private val TileWidth = 200.dp
private val TileHeight = 112.dp
private val FavouriteTileWidth = 240.dp
private val FavouriteTileHeight = 135.dp
private val HeroHeight = 230.dp
const val BACKDROP_HEIGHT_FRACTION = 0.72f
private const val HERO_ROTATE_MS = 10_000L
private const val TRENDING_REFRESH_MS = 6 * 60 * 60_000L
// The first TLS handshake after start often fails (AdGuard's HTTPS filtering?); the retry works
private const val TRENDING_RETRY_MS = 15_000L
private val TileShape = RoundedCornerShape(14.dp)
private val FavouriteTileShape = RoundedCornerShape(16.dp)
// Left/right margin for text; rows use it as content padding so tiles can run off the screen edge
private val EdgePadding = 56.dp
// How far the page scrolls before the backdrop has fully dimmed
private val BackdropFadeDistance = 300.dp

// OLED burn-in care: static UI drifts a few px, dims when idle and at night. Set to false on LCD TVs.
private const val OLED_CARE = true
private const val IDLE_DIM_MS = 60_000L
private const val IDLE_DIM = 0.65f
private const val NIGHT_DIM = 0.25f
private const val SHIFT_INTERVAL_MS = 120_000L
private val ShiftOrbit = listOf(0 to 0, 3 to 1, 4 to -2, 1 to -4, -3 to -3, -4 to 1, -1 to 4, 2 to 3)
private val MenuShape = RoundedCornerShape(28.dp)
private const val RECENT_COUNT = 8
private const val SHEEN_MS = 700
private const val WEATHER_REFRESH_MS = 30 * 60_000L

private data class MenuTarget(val app: TvApp, val canMove: Boolean)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    reloadKey: Int,
    homePresses: Int,
    resumes: Int,
    visible: Boolean,
    onLaunch: (TvApp) -> Unit,
    onAppInfo: (TvApp) -> Unit,
    onWifi: () -> Unit,
    onSettings: () -> Unit,
    onPicture: () -> Unit,
    onSound: () -> Unit,
    onOpenTrending: (Intent) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf(emptyList<TvApp>()) }
    var favouriteIds by remember { mutableStateOf(loadFavourites(context)) }
    var recentIds by remember { mutableStateOf(emptyList<String>()) }
    var inputs by remember { mutableStateOf(emptyList<TvApp>()) }
    var focusedApp by remember { mutableStateOf<TvApp?>(null) }
    var menu by remember { mutableStateOf<MenuTarget?>(null) }
    var menuBackdrop by remember { mutableStateOf<ImageBitmap?>(null) }
    val window = (context as Activity).window
    // Capture and blur the screen first so the menu opens over frosted glass
    fun openMenu(target: MenuTarget) {
        captureBlurredWindow(window) {
            menuBackdrop = it
            menu = target
        }
    }
    var movingId by remember { mutableStateOf<String?>(null) }
    var swallowKeyUp by remember { mutableStateOf<Key?>(null) }
    var refocus by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val favouritesRow = rememberLazyListState()
    val firstTile = remember { FocusRequester() }
    val heroButton = remember { FocusRequester() }

    LaunchedEffect(reloadKey) {
        apps = withContext(Dispatchers.IO) { loadApps(context) }
    }
    LaunchedEffect(resumes) {
        recentIds = withContext(Dispatchers.IO) { recentPackages(context) }
        inputs = withContext(Dispatchers.IO) { loadInputs(context) }
    }

    // The HDMI device that names itself over CEC (the Apple TV) is the hero; any other inputs get a row
    val hdmiDevice = inputs.firstOrNull { it.detail != null }
    val otherInputs = inputs.filter { it.packageName != hdmiDevice?.packageName }
    val hdmiAsleep by HdmiDevice.asleep.collectAsState()

    val byPackage = remember(apps) { apps.associateBy { it.packageName } }
    val favourites = favouriteIds.mapNotNull { byPackage[it] }
    // Favourites are already one row up
    val recents = recentIds.filter { it !in favouriteIds }.mapNotNull { byPackage[it] }.take(RECENT_COUNT)
    val others = apps.filter { it.packageName !in favouriteIds }

    fun updateFavourites(ids: List<String>) {
        favouriteIds = ids
        saveFavourites(context, ids)
    }

    // Move mode: left/right shifts the tile, OK or Back finishes
    fun handleMoveKey(key: Key): Boolean {
        val id = movingId ?: return false
        val from = favourites.indexOfFirst { it.packageName == id }
        val to = when (key) {
            Key.DirectionLeft -> from - 1
            Key.DirectionRight -> from + 1
            Key.DirectionCenter, Key.Enter, Key.Back, Key.Escape -> {
                movingId = null
                // Otherwise the release reaches the tile and launches the app
                swallowKeyUp = key
                return true
            }
            else -> return true
        }
        if (from < 0 || to !in favourites.indices) return true
        updateFavourites(favourites.map { it.packageName }.toMutableList().apply { add(to, removeAt(from)) })
        // Focus stays on the moved tile but nothing scrolls it into view, so do it by hand
        if (!favouritesRow.isFullyVisible(to)) {
            val first = favouritesRow.firstVisibleItemIndex
            scope.launch { favouritesRow.animateScrollToItem(if (to <= first) to else first + 1) }
        }
        return true
    }

    // Home starts on the Apple TV button when there is one, else the first favourite
    LaunchedEffect(apps.isNotEmpty(), hdmiDevice != null, homePresses, refocus) {
        if (apps.isEmpty()) return@LaunchedEffect
        movingId = null
        scroll.animateScrollTo(0)
        favouritesRow.scrollToItem(0)
        // On cold start the tile may not be attached for a few frames; if focus is never set,
        // Android picks an arbitrary element (seen: the Settings pill)
        (if (hdmiDevice != null) heroButton else firstTile).requestFocusWhenAttached()
    }

    val shownApp = focusedApp ?: favourites.firstOrNull() ?: others.firstOrNull()
    val glow by animateColorAsState(shownApp?.glow ?: Background, tween(600), label = "glow")

    var trending by remember { mutableStateOf(emptyList<TrendingTitle>()) }
    var heroIndex by remember { mutableIntStateOf(0) }
    var heroFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // No key: nothing to fetch, ever (the retry below would otherwise wake up every 15 s for nothing)
        if (BuildConfig.TMDB_KEY.isBlank()) return@LaunchedEffect
        while (true) {
            // Keep the old titles if a refresh fails
            val fresh = withContext(Dispatchers.IO) { fetchTrending(context) }
            if (fresh.isNotEmpty()) {
                trending = fresh
                heroIndex = 0
            }
            // A network blip at boot shouldn't hide the row for six hours
            delay(if (trending.isEmpty()) TRENDING_RETRY_MS else TRENDING_REFRESH_MS)
        }
    }
    LaunchedEffect(heroIndex, heroFocused, trending) {
        if (heroFocused || trending.size < 2) return@LaunchedEffect
        delay(HERO_ROTATE_MS)
        heroIndex = (heroIndex + 1) % trending.size
    }
    val hero = trending.getOrNull(heroIndex)
    // Only the previous, current and next backdrops stay decoded
    val backdrops = remember(trending) { mutableStateMapOf<Int, ImageBitmap>() }
    LaunchedEffect(trending, heroIndex) {
        if (trending.isEmpty()) return@LaunchedEffect
        val keep = listOf(heroIndex, heroIndex + 1, heroIndex - 1).map { it.mod(trending.size) }
        (backdrops.keys - keep.toSet()).forEach { backdrops.remove(it) }
        for (i in keep) {
            if (i !in backdrops) backdrops[i] = withContext(Dispatchers.Default) { trending[i].decodeBackdrop() }
        }
    }
    fun stepHero(by: Int) {
        heroIndex = (heroIndex + by).mod(trending.size)
    }

    val now = rememberNow()
    // Trending only stands in for the hero when there's no HDMI device; otherwise the living wallpaper is the backdrop
    val showTrending = hdmiDevice == null && hero != null
    val part = dayPart(now.hour)
    // Decoded only while the home screen is on screen; ~8 MB freed the moment an app or input takes over
    val wallpaper by produceState<Wallpaper?>(null, visible, part, showTrending) {
        value = if (visible && !showTrending) withContext(Dispatchers.Default) { loadWallpaper(context, part) } else null
    }
    val driftClock = rememberDriftClock(visible && !showTrending)
    var keyPresses by remember { mutableIntStateOf(0) }
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(keyPresses, resumes) {
        idle = false
        delay(IDLE_DIM_MS)
        idle = true
    }
    val night = now.hour >= 22 || now.hour < 6
    val idleDim = if (OLED_CARE && idle) IDLE_DIM else 0f
    val nightDim = if (OLED_CARE && night) NIGHT_DIM else 0f
    val dim by animateFloatAsState(
        targetValue = 1 - (1 - idleDim) * (1 - nightDim),
        animationSpec = tween(if (idle) 3000 else 250),
        label = "dim",
    )

    val density = LocalDensity.current
    val shiftStep by produceState(resumes, resumes) {
        while (true) {
            delay(SHIFT_INTERVAL_MS)
            value++
        }
    }
    val (shiftX, shiftY) = if (OLED_CARE) ShiftOrbit[shiftStep % ShiftOrbit.size] else (0 to 0)
    val shift by animateIntOffsetAsState(
        with(density) { IntOffset(shiftX.dp.roundToPx(), shiftY.dp.roundToPx()) },
        tween(2000),
        label = "shift",
    )

    val edgeMargin = with(density) { 40.dp.toPx() }
    MaterialTheme(colorScheme = darkColorScheme()) {
        CompositionLocalProvider(
            LocalBringIntoViewSpec provides remember(edgeMargin) { minimalScroll(edgeMargin) },
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = UiText),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent {
                        val down = it.type == KeyEventType.KeyDown
                        if (down) keyPresses++
                        when {
                            !down && it.key == swallowKeyUp -> {
                                swallowKeyUp = null
                                true
                            }
                            // Swallow key-ups too while moving, e.g. the OK release from the menu
                            movingId != null -> !down || handleMoveKey(it.key)
                            else -> false
                        }
                    }
                    .drawWithContent {
                        drawContent()
                        if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
                    }
                    .background(Background)
                    .drawBehind {
                        // The wallpaper brings its own light; the app-colour glow is for the plain black screen
                        if (!showTrending && wallpaper != null) return@drawBehind
                        drawRect(
                            Brush.radialGradient(
                                0f to glow.copy(alpha = 0.9f),
                                0.45f to glow.copy(alpha = 0.3f),
                                1f to Color.Transparent,
                                // With a backdrop up top, the glow sits behind the shelves instead
                                center = Offset(size.width * 0.36f, size.height * if (showTrending) 0.85f else 0.42f),
                                radius = size.width * 0.6f,
                            )
                        )
                        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                    }
            ) {
                // Scrolling down dims the backdrop and lifts it a little slower than the page (parallax)
                val fadeDistance = with(density) { BackdropFadeDistance.toPx() }
                val fade = (scroll.value / fadeDistance).coerceIn(0f, 1f)
                val backdropAlpha = 1f - 0.75f * fade
                val parallax = scroll.value * 0.4f
                val configuration = LocalConfiguration.current
                val screenWidth = with(density) { configuration.screenWidthDp.dp.toPx() }
                val screenSize = with(density) { Size(screenWidth, configuration.screenHeightDp.dp.toPx()) }
                val glassSource = if (showTrending) {
                    backdrops[heroIndex]?.let { Backdrop(it, backdropAlpha, parallax) }
                    hero?.let {
                        val height = screenWidth * it.frosted.height / it.frosted.width
                        GlassSource(it.frosted, { Rect(0f, -parallax, screenWidth, height - parallax) }, { backdropAlpha })
                    }
                } else {
                    // Read in the draw phase so scrolling and drift only redraw
                    val wallpaperDim = { 0.5f * (scroll.value / fadeDistance).coerceIn(0f, 1f) }
                    LivingWallpaper(wallpaper, driftClock, wallpaperDim)
                    wallpaper?.let {
                        GlassSource(it.frosted, { wallpaperBounds(screenSize, driftClock.value) }, { 1f - wallpaperDim() })
                    }
                }
                CompositionLocalProvider(LocalGlassSource provides glassSource) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .offset { shift }
                        .verticalScroll(scroll)
                ) {
                    Spacer(Modifier.height(36.dp))
                    Header(now, onWifi, onSettings, onPicture, onSound)
                    val movingLabel = movingId?.let { id -> byPackage[id]?.label }
                    if (hdmiDevice != null) {
                        Spacer(Modifier.height(18.dp))
                        HdmiHero(
                            device = hdmiDevice,
                            asleep = hdmiAsleep,
                            label = movingLabel?.let { "Moving $it  ·  ◀ ▶ to move, OK to finish" },
                            button = heroButton,
                            onOpen = { onLaunch(hdmiDevice) },
                        )
                        Spacer(Modifier.height(4.dp))
                    } else if (showTrending && hero != null) {
                        Spacer(Modifier.height(18.dp))
                        Hero(
                            title = hero,
                            label = movingLabel?.let { "Moving $it  ·  ◀ ▶ to move, OK to finish" } ?: "Trending this week",
                            index = heroIndex,
                            count = trending.size,
                            onOpen = { hero.action?.let(onOpenTrending) },
                            onStep = ::stepHero,
                            onFocusChange = { heroFocused = it },
                        )
                        Spacer(Modifier.height(4.dp))
                    } else {
                        Spacer(Modifier.height(132.dp))
                        AppTitle(shownApp?.label.orEmpty(), moving = movingId != null)
                    }
                    val onFocused = { app: TvApp -> focusedApp = app }
                    val onMenu = { app: TvApp -> openMenu(MenuTarget(app, canMove = false)) }
                    Shelf(
                        favourites,
                        favouritesRow,
                        firstTile.takeIf { favourites.isNotEmpty() },
                        onLaunch,
                        { openMenu(MenuTarget(it, canMove = true)) },
                        onFocused,
                        FavouriteTileWidth,
                        FavouriteTileHeight,
                        FavouriteTileShape,
                    )
                    if (otherInputs.isNotEmpty()) {
                        ShelfLabel("Inputs")
                        Shelf(otherInputs, rememberLazyListState(), null, onLaunch, {}, onFocused)
                    }
                    if (recents.isNotEmpty()) {
                        ShelfLabel("Recent")
                        Shelf(recents, rememberLazyListState(), null, onLaunch, onMenu, onFocused)
                    }
                    ShelfLabel("All apps")
                    Shelf(others, rememberLazyListState(), firstTile.takeIf { favourites.isEmpty() }, onLaunch, onMenu, onFocused)
                    Spacer(Modifier.height(40.dp))
                }
                }
            }
        }

        menu?.let { (app, canMove) ->
            val isFavourite = app.packageName in favouriteIds
            AppMenu(
                app = app,
                backdrop = menuBackdrop,
                isFavourite = isFavourite,
                canMove = canMove,
                onDismiss = { menu = null },
                onMove = {
                    menu = null
                    movingId = app.packageName
                },
                onToggleFavourite = {
                    menu = null
                    updateFavourites(if (isFavourite) favouriteIds - app.packageName else favouriteIds + app.packageName)
                    // The focused tile just jumped rows (or vanished); start again from the first tile
                    refocus++
                },
                onAppInfo = {
                    menu = null
                    onAppInfo(app)
                },
            )
        }
    }
}

private fun LazyListState.isFullyVisible(index: Int): Boolean {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    return item.offset >= 0 && item.offset + item.size <= info.viewportEndOffset - info.afterContentPadding
}

// Retries for a few frames: freshly composed tiles aren't attached immediately
private suspend fun FocusRequester.requestFocusWhenAttached() {
    repeat(30) {
        withFrameNanos { }
        try {
            requestFocus()
            return
        } catch (e: IllegalStateException) {
            // not attached yet, try next frame
        }
    }
}

// Fallback heading when there's no trending row (no TMDB key or offline)
@Composable
private fun AppTitle(label: String, moving: Boolean) {
    Row(Modifier.padding(horizontal = EdgePadding), verticalAlignment = Alignment.Bottom) {
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 44.sp,
            fontFamily = UiDisplay,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (moving) {
            Spacer(Modifier.width(20.dp))
            Text(
                "◀ ▶ to move, OK to finish",
                color = TextSecondary,
                fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
    }
}

// Full-width art behind the header, faded so the text on the left and the shelves below stay readable.
// Top-aligned so frostedBackdrop() can line its blurred copy up with it.
@Composable
private fun Backdrop(image: ImageBitmap, alpha: Float, parallax: Float) {
    Crossfade(image, animationSpec = tween(1200), label = "backdrop") { shown ->
        Image(
            shown,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(BACKDROP_HEIGHT_FRACTION)
                .graphicsLayer {
                    this.alpha = alpha
                    translationY = -parallax
                    // Fade the image itself out (not paint black over it) so the glow behind the shelves shows through without a seam
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.5f), 0.25f to Color.Transparent))
                    drawRect(
                        Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.2f), 0.6f to Color.Black),
                        blendMode = BlendMode.DstIn,
                    )
                    drawRect(
                        Brush.verticalGradient(0.5f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                },
        )
    }
}

// Up from favourites focuses the button; left/right flips titles there, otherwise they rotate on their own
@Composable
private fun Hero(
    title: TrendingTitle,
    label: String,
    index: Int,
    count: Int,
    onOpen: () -> Unit,
    onStep: (Int) -> Unit,
    onFocusChange: (Boolean) -> Unit,
) {
    Column(Modifier.height(HeroHeight).padding(horizontal = EdgePadding)) {
        Text(label, color = TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        if (title.logo != null) {
            Image(
                title.logo,
                contentDescription = title.title,
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                modifier = Modifier.height(68.dp).widthIn(max = 340.dp),
            )
        } else {
            Text(
                title.title,
                color = TextPrimary,
                fontSize = 40.sp,
                fontFamily = UiDisplay,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(68.dp).widthIn(max = 560.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(title.meta, color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Text(
            title.overview,
            color = TextSecondary,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (title.action != null) {
                Pill(
                    R.drawable.ic_play,
                    title.actionLabel,
                    onOpen,
                    Modifier
                        .onFocusChanged { onFocusChange(it.isFocused) }
                        .onPreviewKeyEvent {
                            if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (it.key) {
                                Key.DirectionLeft -> onStep(-1)
                                Key.DirectionRight -> onStep(1)
                                else -> return@onPreviewKeyEvent false
                            }
                            true
                        },
                )
                Spacer(Modifier.width(18.dp))
            }
            PageDots(index, count)
        }
    }
}

// Apple-style page control: the current page is a wider capsule
@Composable
private fun PageDots(index: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { dot ->
            val width by animateDpAsState(if (dot == index) 18.dp else 6.dp, tween(300), label = "dot")
            Box(
                Modifier
                    .size(width, 6.dp)
                    .background(Color.White.copy(alpha = if (dot == index) 0.95f else 0.35f), CircleShape)
            )
        }
    }
}

@Composable
private fun ShelfLabel(text: String) {
    Text(
        text,
        color = TextPrimary.copy(alpha = 0.92f),
        fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = EdgePadding, top = 14.dp),
    )
}

// Opens over a blurred capture of the screen, like tvOS context menus
@Composable
private fun AppMenu(
    app: TvApp,
    backdrop: ImageBitmap?,
    isFavourite: Boolean,
    canMove: Boolean,
    onDismiss: () -> Unit,
    onMove: () -> Unit,
    onToggleFavourite: () -> Unit,
    onAppInfo: () -> Unit,
) {
    val firstItem = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstItem.requestFocusWhenAttached() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val bounds = with(LocalDensity.current) { Rect(0f, 0f, maxWidth.toPx(), maxHeight.toPx()) }
            if (backdrop != null) {
                Image(backdrop, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            CompositionLocalProvider(LocalGlassSource provides backdrop?.let { GlassSource(it, { bounds }) }) {
                Column(
                    Modifier
                        .width(360.dp)
                        .glass(MenuShape)
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) {
                        Image(app.image, contentDescription = null, modifier = Modifier.size(56.dp, 32.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(12.dp))
                        Text(app.label, color = TextPrimary, fontSize = 20.sp, fontFamily = UiDisplay, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val itemModifier = Modifier.fillMaxWidth()
                    if (canMove) Pill(null, "Move", onMove, itemModifier.focusRequester(firstItem), fillWidth = true)
                    Pill(
                        null,
                        if (isFavourite) "Remove from favourites" else "Add to favourites",
                        onToggleFavourite,
                        if (canMove) itemModifier else itemModifier.focusRequester(firstItem),
                        fillWidth = true,
                    )
                    Pill(null, "App info", onAppInfo, itemModifier, fillWidth = true)
                }
            }
        }
    }
}

// Compose's TV default pins the focused item 30% from the edge, which scrolls the header away.
// Scroll only when the item would leave the screen, keeping a margin so the focus scale isn't clipped.
@OptIn(ExperimentalFoundationApi::class)
private fun minimalScroll(margin: Float) = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = offset - margin
        val end = offset + size + margin
        return when {
            start < 0 -> start
            end > containerSize -> end - containerSize
            else -> 0f
        }
    }
}

// Ticks on the minute boundary
@Composable
private fun rememberNow(): LocalDateTime {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    return now
}

@Composable
private fun Header(
    now: LocalDateTime,
    onWifi: () -> Unit,
    onSettings: () -> Unit,
    onPicture: () -> Unit,
    onSound: () -> Unit,
) {
    val context = LocalContext.current
    val is24h = DateFormat.is24HourFormat(context)
    val weather by produceState<Weather?>(null) {
        while (true) {
            value = withContext(Dispatchers.IO) { fetchWeather(context) } ?: value
            delay(WEATHER_REFRESH_MS)
        }
    }

    Row(Modifier.fillMaxWidth().padding(horizontal = EdgePadding), verticalAlignment = Alignment.CenterVertically) {
        Row {
            Text(
                now.format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm")),
                color = TextPrimary,
                fontSize = 54.sp,
                fontFamily = UiDisplay,
                fontWeight = FontWeight.Light,
                modifier = Modifier.alignByBaseline(),
            )
            if (!is24h) {
                Text(
                    " " + now.format(DateTimeFormatter.ofPattern("a")).uppercase(),
                    color = TextSecondary,
                    fontSize = 18.sp,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        Spacer(Modifier.width(20.dp))
        Column {
            Text(greeting(now.hour), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            val weatherText = weather?.let { "  ·  ${it.temperature}° ${it.summary}" }.orEmpty()
            Text(now.format(DateTimeFormatter.ofPattern("EEEE d MMMM")) + weatherText, color = TextSecondary, fontSize = 15.sp)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill(R.drawable.ic_picture, null, onPicture)
            Pill(R.drawable.ic_sound, null, onSound)
            Pill(R.drawable.ic_wifi, rememberNetworkLabel(), onWifi)
            Pill(R.drawable.ic_settings, "Settings", onSettings)
        }
    }
}

private fun greeting(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

// Glass capsule; focused it turns solid white with dark content, like tvOS buttons
@Composable
private fun Pill(
    @DrawableRes icon: Int?,
    label: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = TextPrimary,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Color.White.copy(alpha = 0.25f), 12.dp)),
    ) {
        Row(
            Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .glass(CircleShape, enabled = !focused)
                .padding(horizontal = if (label == null) 11.dp else 20.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
            }
            if (icon != null && label != null) Spacer(Modifier.width(8.dp))
            if (label != null) Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// No container: tiles float and run off the screen edge, like tvOS rows
@Composable
private fun Shelf(
    apps: List<TvApp>,
    state: LazyListState,
    firstTile: FocusRequester?,
    onLaunch: (TvApp) -> Unit,
    onMenu: (TvApp) -> Unit,
    onFocused: (TvApp) -> Unit,
    tileWidth: Dp = TileWidth,
    tileHeight: Dp = TileHeight,
    shape: RoundedCornerShape = TileShape,
) {
    if (apps.isEmpty()) return
    LazyRow(
        state = state,
        // Vertical room for the focus scale and glow
        contentPadding = PaddingValues(horizontal = EdgePadding, vertical = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
            val requester = if (index == 0) firstTile else null
            AppTile(
                app = app,
                onClick = { onLaunch(app) },
                onLongClick = { onMenu(app) },
                width = tileWidth,
                height = tileHeight,
                shape = shape,
                modifier = Modifier
                    .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                    .onFocusChanged { if (it.isFocused) onFocused(app) },
            )
        }
    }
}

// Focus lifts the tile (scale, glow in the app's colour, light rim) instead of drawing a thick border
@Composable
private fun AppTile(
    app: TvApp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    width: Dp,
    height: Dp,
    shape: RoundedCornerShape,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val sheen = remember { Animatable(1f) }
    LaunchedEffect(focused) {
        if (focused) {
            sheen.snapTo(0f)
            sheen.animateTo(1f, tween(SHEEN_MS, easing = FastOutSlowInEasing))
        }
    }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .size(width, height),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), shape = shape),
            focusedBorder = Border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.6f)), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(app.glow.copy(alpha = 0.55f), 20.dp)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    drawContent()
                    // Soft glare from the top-left while focused, plus a band that sweeps across once
                    if (focused) {
                        drawRect(
                            Brush.linearGradient(
                                0f to Color.White.copy(alpha = 0.16f),
                                0.45f to Color.Transparent,
                                end = Offset(size.width * 0.7f, size.height),
                            )
                        )
                    }
                    if (sheen.value < 1f) {
                        val band = size.width * 0.6f
                        val x = -band + (size.width + 2 * band) * sheen.value
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.28f), Color.Transparent),
                                start = Offset(x - band / 2, 0f),
                                end = Offset(x + band / 2, size.height),
                            )
                        )
                    }
                }
        ) {
            if (app.hasBanner) {
                Image(app.image, contentDescription = app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                GlassTileContent(app, shape)
            }
        }
    }
}

// Inputs and apps without a TV banner: icon top-left, name and detail bottom-left on glass
@Composable
private fun GlassTileContent(app: TvApp, shape: RoundedCornerShape) {
    Column(
        Modifier
            .fillMaxSize()
            .glass(shape)
            .padding(16.dp)
    ) {
        Image(app.image, contentDescription = null, modifier = Modifier.size(30.dp))
        Spacer(Modifier.weight(1f))
        Text(app.label, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (app.detail != null) {
            Text(app.detail, color = TextSecondary, fontSize = 13.sp, maxLines = 1)
        }
    }
}

// SSID is only readable with location permission + location on; otherwise falls back to "Wi-Fi"
@Composable
private fun rememberNetworkLabel(): String {
    val context = LocalContext.current
    var label by remember { mutableStateOf(networkLabel(context)) }
    DisposableEffect(Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { label = networkLabel(context) }
            override fun onLost(network: Network) { label = networkLabel(context) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { label = networkLabel(context) }
        }
        cm.registerDefaultNetworkCallback(callback)
        onDispose { cm.unregisterNetworkCallback(callback) }
    }
    return label
}

@Suppress("DEPRECATION") // connectionInfo is the only SSID API on Android 9
private fun networkLabel(context: Context): String {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    // AdGuard runs as a VPN, so the active network is the VPN; look at every network instead
    val transports = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
    if (transports.none { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }) return "Offline"
    return when {
        transports.any { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } -> "Ethernet"
        transports.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } -> {
            val ssid = context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo.ssid
            ssid?.trim('"')?.takeUnless { it.isBlank() || it == "<unknown ssid>" } ?: "Wi-Fi"
        }
        else -> "Online"
    }
}
