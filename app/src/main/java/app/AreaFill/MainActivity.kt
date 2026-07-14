package app.AreaFill

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.*
import org.json.JSONArray
import kotlin.collections.List
import kotlin.collections.Set
import kotlin.math.*

// ─── MainActivity ──────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RectaFormeApp() }
    }
}

// ─── Navigation screens ───────────────────────────────────────────────────────
enum class Screen { HOME, PLAYING, RESULTS, NO_LIVES, LIVES_INFO }

// ─── Colour palette ────────────────────────────────────────────────────────────
object Palette {
    val rose    = Color(0xFFFF7396)
    val amber   = Color(0xFFE0A96D)
    val emerald = Color(0xFF5B9A68)
    val indigo  = Color(0xFF6F76D9)
    val sky     = Color(0xFF76C7FF)
    val violet  = Color(0xFFC378E0)
    val teal    = Color(0xFF61B59C)
    val fuchsia = Color(0xFFD973C3)
    val orange  = Color(0xFFE38546)
    val red     = Color(0xFFE86464)
    val pink    = Color(0xFFED7AA1)
    val cyan    = Color(0xFF5CBAD1)
    val purple  = Color(0xFF9C71D6)
    val slate   = Color(0xFF8F9CA6)
    val blue    = Color(0xFF5B8FD6)

    fun fromName(name: String) = when (name.lowercase().trim()) {
        "rose"    -> rose
        "amber"   -> amber
        "emerald" -> emerald
        "indigo"  -> indigo
        "sky"     -> sky
        "violet"  -> violet
        "teal"    -> teal
        "fuchsia" -> fuchsia
        "orange"  -> orange
        "red"     -> red
        "pink"    -> pink
        "cyan"    -> cyan
        "purple"  -> purple
        "blue"    -> blue
        else      -> slate
    }
}

// ─── Data models ───────────────────────────────────────────────────────────────
enum class ClueShape { SQUARE, TALL, WIDE }
enum class Constraint { FORCED, ANY }

data class SolvedRect(val r1: Int, val c1: Int, val r2: Int, val c2: Int)

data class Clue(
    val row: Int,
    val col: Int,
    val value: Int?,
    val shape: ClueShape,
    val constraint: Constraint,
    val colorName: String,
    val solution: SolvedRect
) {
    val color      get() = Palette.fromName(colorName)
    val lightColor get() = color.copy(alpha = 0.18f)
}

data class PlacedRect(
    val r1: Int, val c1: Int,
    val r2: Int, val c2: Int,
    val clueIdx: Int,
    val valid: Boolean
) {
    val rMin get() = min(r1, r2)
    val rMax get() = max(r1, r2)
    val cMin get() = min(c1, c2)
    val cMax get() = max(c1, c2)
    val area get() = (rMax - rMin + 1) * (cMax - cMin + 1)
}

data class Puzzle(
    val id: String,
    val name: String,
    val size: Int,
    val desc: String,
    val clues: List<Clue>
)

data class ValidationResult(val isValid: Boolean, val clueIdx: Int)

// ─── JSON Puzzle Loader ────────────────────────────────────────────────────────
fun loadPuzzlesFromAssets(context: Context): List<Puzzle> {
    return try {
        val files = context.assets.list("") ?: emptyArray()
        val filename = when {
            files.contains("rectaforme_puzzles_dataset.json") -> "rectaforme_puzzles_dataset.json"
            files.contains("puzzles.json")                    -> "puzzles.json"
            else -> return emptyList()
        }
        val json = context.assets.open(filename).bufferedReader().readText()
        parsePuzzlesJson(json)
    } catch (e: Exception) {
        emptyList()
    }
}

fun parsePuzzlesJson(json: String): List<Puzzle> {
    val cleaned = json
        .replace(Regex(",\\s*\\]"), "]")
        .replace(Regex(",\\s*\\}"), "}")

    val arr     = JSONArray(cleaned)
    val puzzles = mutableListOf<Puzzle>()

    for (i in 0 until arr.length()) {
        try {
            val obj  = arr.getJSONObject(i)
            val id   = obj.getString("id")
            val name = obj.getString("name")
            val size = obj.getInt("size")
            val desc = if (obj.has("desc")) obj.getString("desc")
            else "💡 Remplissez la grille ${size}×${size} !"

            val cluesArr = obj.getJSONArray("clues")
            val rectsArr = obj.getJSONArray("expected_rects")

            val expectedRects = (0 until rectsArr.length()).map { ri ->
                val r = rectsArr.getJSONObject(ri)
                SolvedRect(r.getInt("r1"), r.getInt("c1"), r.getInt("r2"), r.getInt("c2"))
            }

            val clues = mutableListOf<Clue>()
            for (ci in 0 until cluesArr.length()) {
                try {
                    val cObj  = cluesArr.getJSONObject(ci)
                    val row   = cObj.getInt("r")
                    val col   = cObj.getInt("c")
                    val value = if (cObj.isNull("val")) null else cObj.getInt("val")
                    val shape = when (cObj.getString("shape").lowercase().trim()) {
                        "tall"  -> ClueShape.TALL
                        "wide"  -> ClueShape.WIDE
                        else    -> ClueShape.SQUARE
                    }
                    val constraint = when (cObj.getString("constraint").lowercase().trim()) {
                        "forced" -> Constraint.FORCED
                        else     -> Constraint.ANY
                    }
                    val color = cObj.getString("color")
                    val solution = expectedRects.firstOrNull { sr ->
                        val rMin = min(sr.r1, sr.r2); val rMax = max(sr.r1, sr.r2)
                        val cMin = min(sr.c1, sr.c2); val cMax = max(sr.c1, sr.c2)
                        row in rMin..rMax && col in cMin..cMax
                    } ?: continue

                    clues.add(Clue(row, col, value, shape, constraint, color, solution))
                } catch (_: Exception) {}
            }

            if (clues.isEmpty()) continue
            puzzles.add(Puzzle(id, name, size, desc, clues))
        } catch (_: Exception) {}
    }
    return puzzles
}

// ─── Sound helpers ─────────────────────────────────────────────────────────────
fun playTone(freq: Double, durationMs: Int, volume: Float = 0.3f) {
    Thread {
        try {
            val sampleRate = 44100
            val samples    = sampleRate * durationMs / 1000
            val buf        = ShortArray(samples)
            for (k in 0 until samples) {
                val angle = 2.0 * PI * k * freq / sampleRate
                buf[k] = (sin(angle) * Short.MAX_VALUE * volume).toInt().toShort()
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buf.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(buf, 0, buf.size)
            track.play()
            Thread.sleep(durationMs.toLong() + 50)
            track.release()
        } catch (_: Exception) {}
    }.start()
}

fun soundSelect()  = playTone(440.0, 80, 0.2f)
fun soundSuccess() { playTone(523.25, 100, 0.2f); Thread.sleep(100); playTone(659.25, 150, 0.2f) }
fun soundError()   = playTone(120.0, 200, 0.2f)
fun soundWin() {
    listOf(523.25, 587.33, 659.25, 783.99, 880.0, 1046.5).forEachIndexed { i, f ->
        Thread.sleep(i * 70L)
        playTone(f, 180, 0.18f)
    }
}

// ─── AdMob ─────────────────────────────────────────────────────────────────────
object AdIds {
    private val DEBUG = BuildConfig.DEBUG
    val APP_OPEN = if (DEBUG) "ca-app-pub-3940256099942544/9257395921" else "ca-app-pub-2498267529185476/7422907016"
    val REWARDED = if (DEBUG) "ca-app-pub-3940256099942544/5224354917" else "ca-app-pub-2498267529185476/7422907016"
    val BANNER   = if (DEBUG) "ca-app-pub-3940256099942544/6300978111" else "ca-app-pub-2498267529185476/7384456564"
}

class AreaFillApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MobileAds.initialize(this)
        AppOpenAdManager.loadAd(this)
    }
}

// Loads a single App Open ad and shows it once, on the app's cold start.
object AppOpenAdManager {
    private var appOpenAd: AppOpenAd? = null
    private var isLoadingAd = false
    private var isShowingAd = false
    private var hasShownOnce = false

    fun loadAd(context: Context) {
        if (isLoadingAd || appOpenAd != null) return
        isLoadingAd = true
        AppOpenAd.load(
            context, AdIds.APP_OPEN, AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenAd = ad
                    isLoadingAd = false
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoadingAd = false
                }
            }
        )
    }

    // Returns true once it's done trying (shown, already handled, or nothing to show yet is not final —
    // caller should keep polling until this returns true or it gives up after a timeout).
    fun maybeShow(activity: Activity): Boolean {
        if (hasShownOnce || isShowingAd) return true
        val ad = appOpenAd ?: return false
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                appOpenAd = null; isShowingAd = false; hasShownOnce = true
            }
            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                appOpenAd = null; isShowingAd = false; hasShownOnce = true
            }
        }
        isShowingAd = true
        ad.show(activity)
        return true
    }

    fun giveUpWaiting() { hasShownOnce = true }
}

// ─── Lives ─────────────────────────────────────────────────────────────────────
class LivesManager(context: Context) {
    companion object { const val MAX_LIVES = 3 }

    private val prefs = context.applicationContext.getSharedPreferences("areafill_prefs", Context.MODE_PRIVATE)
    private val regenIntervalMs = 60 * 60 * 1000L

    private var livesPref: Int
        get() = prefs.getInt("lives", MAX_LIVES)
        set(value) = prefs.edit().putInt("lives", value).apply()

    private var regenStartTs: Long
        get() = prefs.getLong("regen_start_ts", 0L)
        set(value) = prefs.edit().putLong("regen_start_ts", value).apply()

    fun currentLives(): Int {
        var lives = livesPref
        var start = regenStartTs
        if (lives < MAX_LIVES && start > 0L) {
            val elapsed = System.currentTimeMillis() - start
            val regenCount = (elapsed / regenIntervalMs).toInt()
            if (regenCount > 0) {
                lives = (lives + regenCount).coerceAtMost(MAX_LIVES)
                start = if (lives >= MAX_LIVES) 0L else start + regenCount * regenIntervalMs
                livesPref = lives
                regenStartTs = start
            }
        }
        return lives
    }

    fun loseLife(): Int {
        val current = currentLives()
        if (current <= 0) return 0
        val next = current - 1
        livesPref = next
        if (regenStartTs == 0L) regenStartTs = System.currentTimeMillis()
        return next
    }

    fun addLife(amount: Int = 1): Int {
        val next = (currentLives() + amount).coerceAtMost(MAX_LIVES)
        livesPref = next
        if (next >= MAX_LIVES) regenStartTs = 0L
        return next
    }

    fun millisUntilNextLife(): Long {
        if (currentLives() >= MAX_LIVES) return 0L
        val start = regenStartTs
        if (start == 0L) return regenIntervalMs
        val elapsed = System.currentTimeMillis() - start
        return (regenIntervalMs - (elapsed % regenIntervalMs)).coerceIn(0L, regenIntervalMs)
    }
}

// ─── Progress (completed / unlocked levels) ─────────────────────────────────────
class ProgressManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("areafill_prefs", Context.MODE_PRIVATE)

    var completedLevels: Set<Int>
        get() = prefs.getString("completed_levels", "")!!
            .split(",")
            .mapNotNull { it.toIntOrNull() }
            .toSet()
        private set(value) = prefs.edit().putString("completed_levels", value.joinToString(",")).apply()

    fun markCompleted(levelIdx: Int) {
        completedLevels = completedLevels + levelIdx
    }
}

// ─── Hints ─────────────────────────────────────────────────────────────────────
class HintsManager(context: Context) {
    companion object {
        const val INITIAL_HINTS      = 5
        const val MAX_AD_HINTS_A_DAY = 5
    }

    private val prefs = context.applicationContext.getSharedPreferences("areafill_prefs", Context.MODE_PRIVATE)

    var balance: Int
        get() = prefs.getInt("hints_balance", INITIAL_HINTS)
        private set(value) = prefs.edit().putInt("hints_balance", value).apply()

    private var adHintsDay: Int
        get() = prefs.getInt("ad_hints_day", -1)
        set(value) = prefs.edit().putInt("ad_hints_day", value).apply()

    private var adHintsCountToday: Int
        get() = prefs.getInt("ad_hints_count", 0)
        set(value) = prefs.edit().putInt("ad_hints_count", value).apply()

    private fun todayEpochDay(): Int = (System.currentTimeMillis() / (24 * 60 * 60 * 1000L)).toInt()

    private fun rolloverIfNeeded() {
        val today = todayEpochDay()
        if (adHintsDay != today) {
            adHintsDay = today
            adHintsCountToday = 0
        }
    }

    fun adHintsRemainingToday(): Int {
        rolloverIfNeeded()
        return (MAX_AD_HINTS_A_DAY - adHintsCountToday).coerceAtLeast(0)
    }

    fun consume(): Boolean {
        if (balance <= 0) return false
        balance -= 1
        return true
    }

    // Grants +1 hint credit from a watched ad, respecting the daily cap.
    fun addFromAd(): Boolean {
        rolloverIfNeeded()
        if (adHintsCountToday >= MAX_AD_HINTS_A_DAY) return false
        adHintsCountToday += 1
        balance += 1
        return true
    }
}

// ─── GameState ─────────────────────────────────────────────────────────────────
class GameState(
    private val puzzle: Puzzle,
    private val onInvalidPlacement: () -> Unit = {}
) {
    val size  = puzzle.size
    val clues = puzzle.clues

    var placedRects    by mutableStateOf(listOf<PlacedRect>())
    var history        = mutableListOf<List<PlacedRect>>()
    var isWon          by mutableStateOf(false)
    var elapsedSeconds by mutableStateOf(0)

    // ── Stats for star rating ──────────────────────────────────────────────
    var hintsUsed by mutableStateOf(0)
    var undosUsed by mutableStateOf(0)

    /**
     * Star rating:
     *  3 ⭐ → no hints AND ≤ 2 undos
     *  2 ⭐ → ≤ 1 hint  AND ≤ 5 undos
     *  1 ⭐ → otherwise
     */
    fun computeStars(): Int = when {
        hintsUsed == 0 && undosUsed <= 2 -> 3
        hintsUsed <= 1 && undosUsed <= 5  -> 2
        else                              -> 1
    }

    fun validate(r1: Int, c1: Int, r2: Int, c2: Int): ValidationResult {
        val rMin = min(r1, r2); val rMax = max(r1, r2)
        val cMin = min(c1, c2); val cMax = max(c1, c2)
        val w    = cMax - cMin + 1
        val h    = rMax - rMin + 1
        val area = w * h

        val found = clues.mapIndexedNotNull { i, cl ->
            if (cl.row in rMin..rMax && cl.col in cMin..cMax) Pair(cl, i) else null
        }
        if (found.size != 1) return ValidationResult(false, -1)
        val (cl, idx) = found[0]

        if (cl.value != null && area != cl.value) return ValidationResult(false, idx)

        if (cl.constraint == Constraint.FORCED) {
            return when (cl.shape) {
                ClueShape.SQUARE -> ValidationResult(w == h, idx)
                ClueShape.TALL   -> ValidationResult(h > w,  idx)
                ClueShape.WIDE   -> ValidationResult(w > h,  idx)
            }
        }
        return ValidationResult(true, idx)
    }

    fun placeRect(r1: Int, c1: Int, r2: Int, c2: Int) {
        val result = validate(r1, c1, r2, c2)
        val rMin   = min(r1, r2); val rMax = max(r1, r2)
        val cMin   = min(c1, c2); val cMax = max(c1, c2)

        history.add(placedRects)

        val filtered = placedRects.filter { p ->
            rMax < p.rMin || rMin > p.rMax || cMax < p.cMin || cMin > p.cMax
        }

        if (result.isValid) {
            placedRects = filtered + PlacedRect(r1, c1, r2, c2, result.clueIdx, true)
            Thread { soundSuccess() }.start()
            checkWin()
        } else {
            placedRects = filtered
            Thread { soundError() }.start()
            onInvalidPlacement()
        }
    }

    fun removeRect(r: Int, c: Int) {
        val target = placedRects.find { p -> r in p.rMin..p.rMax && c in p.cMin..p.cMax } ?: return
        history.add(placedRects)
        placedRects = placedRects - target
        Thread { soundSelect() }.start()
    }

    fun undo() {
        if (history.isEmpty()) return
        undosUsed++
        placedRects = history.removeAt(history.lastIndex)
        Thread { soundSelect() }.start()
    }

    fun reset() {
        history.clear()
        placedRects    = emptyList()
        isWon          = false
        elapsedSeconds = 0
        hintsUsed      = 0
        undosUsed      = 0
    }

    fun hint() {
        if (isWon) return
        hintsUsed++
        val solvedRects = clues.mapIndexed { i, cl ->
            PlacedRect(cl.solution.r1, cl.solution.c1, cl.solution.r2, cl.solution.c2, i, true)
        }
        val missing = solvedRects.firstOrNull { s ->
            placedRects.none { p ->
                p.clueIdx == s.clueIdx &&
                        p.rMin == s.rMin && p.rMax == s.rMax &&
                        p.cMin == s.cMin && p.cMax == s.cMax
            }
        } ?: return

        history.add(placedRects)
        val filtered = placedRects.filter { p ->
            missing.rMax < p.rMin || missing.rMin > p.rMax ||
                    missing.cMax < p.cMin || missing.cMin > p.cMax
        }
        placedRects = filtered + missing
        Thread { soundSuccess() }.start()
        checkWin()
    }

    private fun checkWin() {
        val valid = placedRects.filter { it.valid }
        val allCluesCovered = clues.indices.all { idx -> valid.count { it.clueIdx == idx } == 1 }
        if (!allCluesCovered) return
        if (valid.sumOf { it.area } == size * size) {
            isWon = true
            Thread { soundWin() }.start()
        }
    }
}

@Composable
fun RectaFormeApp() {
    val context = LocalContext.current

    var puzzles   by remember { mutableStateOf<List<Puzzle>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val loaded = loadPuzzlesFromAssets(context)
                withContext(Dispatchers.Main) {
                    if (loaded.isEmpty()) loadError = "Aucun niveau trouvé.\nVérifiez le fichier JSON dans assets/"
                    else puzzles = loaded
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { loadError = "Erreur : ${e.message}" }
            }
        }
    }

    // ── Loading ──────────────────────────────────────────────────────────────
    if (puzzles == null && loadError == null) {
        Box(Modifier.fillMaxSize().background(Color(0xFFF4F4F1)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(color = Palette.amber, strokeWidth = 3.dp)
                Text("Chargement des niveaux…", color = Palette.amber, fontWeight = FontWeight.Bold)
            }
        }
        return
    }

    if (loadError != null) {
        Box(Modifier.fillMaxSize().background(Color(0xFFF4F4F1)).padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("⚠️", fontSize = 48.sp)
                Text(loadError!!, color = Color(0xFFE86464), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, fontSize = 14.sp)
            }
        }
        return
    }

    val allPuzzles = puzzles!!
    val activity   = context as? Activity
    val livesManager    = remember { LivesManager(context) }
    val progressManager = remember { ProgressManager(context) }
    val hintsManager     = remember { HintsManager(context) }

    var currentScreen   by remember { mutableStateOf(Screen.HOME) }
    var levelIdx        by remember { mutableStateOf(0) }
    var darkMode        by remember { mutableStateOf(false) }
    var completedLevels by remember { mutableStateOf(progressManager.completedLevels) }
    var lives           by remember { mutableStateOf(livesManager.currentLives()) }
    var hintsBalance    by remember { mutableStateOf(hintsManager.balance) }
    var adHintsRemainingToday by remember { mutableStateOf(hintsManager.adHintsRemainingToday()) }

    // Keep the status bar / navigation bar icons legible: white in dark mode, dark otherwise.
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !darkMode
        controller.isAppearanceLightNavigationBars = !darkMode
    }

    fun handleInvalidPlacement() {
        val remaining = livesManager.loseLife()
        lives = remaining
        if (remaining <= 0) currentScreen = Screen.HOME
    }

    var gameState by remember {
        mutableStateOf(GameState(allPuzzles[0], onInvalidPlacement = { handleInvalidPlacement() }))
    }

    // Try to show the App Open ad once it finishes loading (or give up after ~3s).
    LaunchedEffect(Unit) {
        activity?.let { act ->
            repeat(15) {
                if (AppOpenAdManager.maybeShow(act)) return@LaunchedEffect
                delay(200)
            }
            AppOpenAdManager.giveUpWaiting()
        }
    }

    // Passive life regen check while the app is open.
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            lives = livesManager.currentLives()
        }
    }

    LaunchedEffect(levelIdx, currentScreen) {
        if (currentScreen == Screen.PLAYING) {
            while (true) {
                delay(1000)
                if (!gameState.isWon) gameState.elapsedSeconds++
            }
        }
    }

    // Auto-advance: when won → show results; results screen handles the 3-second countdown
    LaunchedEffect(gameState.isWon) {
        if (gameState.isWon) {
            progressManager.markCompleted(levelIdx)
            completedLevels = progressManager.completedLevels
            delay(600)                        // brief pause to let win animation play
            currentScreen = Screen.RESULTS
        }
    }

    fun goNextLevel() {
        val next  = if (levelIdx < allPuzzles.lastIndex) levelIdx + 1 else 0
        levelIdx      = next
        gameState     = GameState(allPuzzles[next], onInvalidPlacement = { handleInvalidPlacement() })
        currentScreen = Screen.PLAYING
    }

    // Uses one hint from the free balance (5 on install, replenished by watching ads).
    fun useHint() {
        if (hintsManager.consume()) {
            hintsBalance = hintsManager.balance
            gameState.hint()
        }
    }

    // Called after a rewarded ad completes: grants and immediately spends one hint credit
    // (capped at HintsManager.MAX_AD_HINTS_A_DAY per calendar day).
    fun grantHintFromAd() {
        if (hintsManager.addFromAd()) {
            hintsManager.consume()
            hintsBalance = hintsManager.balance
            adHintsRemainingToday = hintsManager.adHintsRemainingToday()
            gameState.hint()
        }
    }

    MaterialTheme {
        when (currentScreen) {
            Screen.HOME -> HomeScreen(
                darkMode = darkMode,
                onPlay   = { currentScreen = if (lives > 0) Screen.PLAYING else Screen.NO_LIVES }
            )

            Screen.NO_LIVES -> NoLivesScreen(
                darkMode     = darkMode,
                livesManager = livesManager,
                onLifeGained = { newLives ->
                    lives = newLives
                    currentScreen = Screen.PLAYING
                },
                onBack = { currentScreen = Screen.HOME }
            )

            Screen.PLAYING -> GameScreen(
                puzzle           = allPuzzles[levelIdx],
                levelIdx         = levelIdx,
                gameState        = gameState,
                darkMode         = darkMode,
                lives            = lives,
                completedLevels  = completedLevels,
                hintsBalance     = hintsBalance,
                adHintsRemainingToday = adHintsRemainingToday,
                onUseHint        = { useHint() },
                onWatchAdForHint = { grantHintFromAd() },
                onDarkModeToggle = { darkMode = !darkMode },
                onBack           = { currentScreen = Screen.HOME },
                onNextLevel      = { /* disabled during play */ },
                onLivesClick     = { currentScreen = Screen.LIVES_INFO }
            )

            Screen.LIVES_INFO -> LivesInfoScreen(
                darkMode     = darkMode,
                lives        = lives,
                livesManager = livesManager,
                onLifeGained = { newLives -> lives = newLives },
                onBack       = { currentScreen = Screen.PLAYING }
            )

            Screen.RESULTS -> ResultsScreen(
                puzzle       = allPuzzles[levelIdx],
                levelIdx     = levelIdx,
                totalLevels  = allPuzzles.size,
                gameState    = gameState,
                darkMode     = darkMode,
                onNextLevel  = { goNextLevel() },
                onReplay     = {
                    gameState.reset()
                    currentScreen = Screen.PLAYING
                }
            )
        }
    }
}

// ─── Back arrow icon ────────────────────────────────────────────────────────────
// Hand-drawn chevron instead of a text glyph, so it sits pixel-centred in its box
// regardless of font metrics/baseline quirks.
@Composable
fun BackArrowIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val strokeW = w * 0.16f
        val tipX      = w * 0.30f
        val edgeX     = w * 0.72f
        val topY      = h * 0.22f
        val midY      = h * 0.5f
        val bottomY   = h * 0.78f

        val path = Path().apply {
            moveTo(edgeX, topY)
            lineTo(tipX, midY)
            lineTo(edgeX, bottomY)
        }
        drawPath(
            path  = path,
            color = color,
            style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

// ─── Hearts raw ────────────────────────────────────────────────────────────────
@Composable
fun HeartsRow(
    lives: Int,
    maxLives: Int = LivesManager.MAX_LIVES,
    fontSize: TextUnit = 16.sp,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(maxLives) { i ->
            Text(if (i < lives) "❤️" else "🖤", fontSize = fontSize)
        }
    }
}

// ─── Home Screen ───────────────────────────────────────────────────────────────
@Composable
fun HomeScreen(
    darkMode: Boolean,
    onPlay: () -> Unit
) {
    val bgColor = if (darkMode) Color(0xFF15171C) else Color(0xFFF4F4F1)

    val context = LocalContext.current
    val appIconBitmap = remember {
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap()?.asImageBitmap()
    }

    Box(
        Modifier.fillMaxSize().background(bgColor).statusBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(28.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(
                Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Palette.amber.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                appIconBitmap?.let {
                    Image(bitmap = it, contentDescription = null, modifier = Modifier.size(64.dp))
                }
            }

            Text("AreaFill", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Palette.amber)

            Button(
                onClick = onPlay,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.amber)
            ) {
                Text("▶ Jouer", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color.White)
            }
        }
    }
}

// ─── No Lives Screen ───────────────────────────────────────────────────────────
@Composable
fun NoLivesScreen(
    darkMode: Boolean,
    livesManager: LivesManager,
    onLifeGained: (Int) -> Unit,
    onBack: () -> Unit
) {
    val context  = LocalContext.current
    val activity = context as? Activity

    val bgColor    = if (darkMode) Color(0xFF15171C) else Color(0xFFF4F4F1)
    val cardColor  = if (darkMode) Color(0xFF1E222B) else Color.White
    val textColor  = if (darkMode) Color.White       else Color(0xFF1E293B)
    val mutedColor = if (darkMode) Color(0xFF94A3B8) else Color(0xFF64748B)

    var remainingMs by remember { mutableStateOf(livesManager.millisUntilNextLife()) }
    var rewardedAd  by remember { mutableStateOf<RewardedAd?>(null) }

    // Load a rewarded ad for this screen.
    LaunchedEffect(Unit) {
        RewardedAd.load(
            context, AdIds.REWARDED, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) { rewardedAd = ad }
                override fun onAdFailedToLoad(error: LoadAdError) { rewardedAd = null }
            }
        )
    }

    // Tick the countdown and detect free hourly regen.
    LaunchedEffect(Unit) {
        while (true) {
            val current = livesManager.currentLives()
            if (current > 0) {
                onLifeGained(current)
                return@LaunchedEffect
            }
            remainingMs = livesManager.millisUntilNextLife()
            delay(1000)
        }
    }

    val totalSeconds = (remainingMs / 1000).coerceAtLeast(0)
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60

    Box(
        Modifier.fillMaxSize().background(bgColor).statusBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(28.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text("💔", fontSize = 64.sp)
            Text("Plus de vies", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = textColor)

            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardColor),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Prochaine vie dans", fontSize = 12.sp, color = mutedColor, fontWeight = FontWeight.Bold)
                    Text(
                        "%02d:%02d".format(mins, secs),
                        fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace, color = Palette.amber
                    )
                }
            }

            Button(
                onClick = {
                    val ad = rewardedAd
                    val act = activity
                    if (ad != null && act != null) {
                        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                            override fun onAdDismissedFullScreenContent() { rewardedAd = null }
                        }
                        ad.show(act) { livesManager.addLife(1) }
                    }
                },
                enabled = rewardedAd != null,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
            ) {
                Text(
                    if (rewardedAd != null) "🎬 Regarder une pub pour +1 vie" else "Chargement…",
                    fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Color.White
                )
            }

            TextButton(onClick = onBack) {
                Text("← Retour", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = mutedColor)
            }
        }
    }
}

// ─── Lives Info Screen (tap on hearts) ──────────────────────────────────────────
@Composable
fun LivesInfoScreen(
    darkMode: Boolean,
    lives: Int,
    livesManager: LivesManager,
    onLifeGained: (Int) -> Unit,
    onBack: () -> Unit
) {
    val context  = LocalContext.current
    val activity = context as? Activity

    val bgColor    = if (darkMode) Color(0xFF15171C) else Color(0xFFF4F4F1)
    val cardColor  = if (darkMode) Color(0xFF1E222B) else Color.White
    val textColor  = if (darkMode) Color.White       else Color(0xFF1E293B)
    val mutedColor = if (darkMode) Color(0xFF94A3B8) else Color(0xFF64748B)

    var remainingMs by remember { mutableStateOf(livesManager.millisUntilNextLife()) }
    var rewardedAd  by remember { mutableStateOf<RewardedAd?>(null) }

    // Load a rewarded ad for this screen.
    LaunchedEffect(Unit) {
        RewardedAd.load(
            context, AdIds.REWARDED, AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) { rewardedAd = ad }
                override fun onAdFailedToLoad(error: LoadAdError) { rewardedAd = null }
            }
        )
    }

    // Tick the countdown and pick up passive hourly regen while this screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            val current = livesManager.currentLives()
            if (current != lives) onLifeGained(current)
            remainingMs = livesManager.millisUntilNextLife()
            delay(1000)
        }
    }

    val totalSeconds = (remainingMs / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val mins  = (totalSeconds % 3600) / 60
    val secs  = totalSeconds % 60
    val atMax = lives >= LivesManager.MAX_LIVES

    Box(
        Modifier.fillMaxSize().background(bgColor).statusBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(28.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            HeartsRow(lives = lives, fontSize = 40.sp)
            Text("Vies", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = textColor)

            if (!atMax) {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Column(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Prochaine vie dans", fontSize = 12.sp, color = mutedColor, fontWeight = FontWeight.Bold)
                        Text(
                            "%02d:%02d:%02d".format(hours, mins, secs),
                            fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace, color = Palette.amber
                        )
                    }
                }
            }

            Button(
                onClick = {
                    val ad = rewardedAd
                    val act = activity
                    if (ad != null && act != null) {
                        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                            override fun onAdDismissedFullScreenContent() { rewardedAd = null }
                        }
                        ad.show(act) { onLifeGained(livesManager.addLife(1)) }
                    }
                },
                enabled = rewardedAd != null && !atMax,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
            ) {
                Text(
                    when {
                        atMax             -> "Vies au maximum"
                        rewardedAd != null -> "🎬 Regarder une pub pour +1 vie"
                        else               -> "Chargement…"
                    },
                    fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Color.White
                )
            }

            TextButton(onClick = onBack) {
                Text("← Retour", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = mutedColor)
            }
        }
    }
}

// ─── AdMob Banner ──────────────────────────────────────────────────────────────
@Composable
fun AdMobBanner(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            AdView(ctx).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = AdIds.BANNER
                loadAd(AdRequest.Builder().build())
            }
        }
    )
}

// ─── Results Screen ────────────────────────────────────────────────────────────
@Composable
fun ResultsScreen(
    puzzle: Puzzle,
    levelIdx: Int,
    totalLevels: Int,
    gameState: GameState,
    darkMode: Boolean,
    onNextLevel: () -> Unit,
    onReplay: () -> Unit
) {
    val bgColor    = if (darkMode) Color(0xFF15171C) else Color(0xFFF4F4F1)
    val cardColor  = if (darkMode) Color(0xFF1E222B) else Color.White
    val textColor  = if (darkMode) Color.White       else Color(0xFF1E293B)
    val mutedColor = if (darkMode) Color(0xFF94A3B8) else Color(0xFF64748B)

    val stars       = gameState.computeStars()
    val isLastLevel = levelIdx == totalLevels - 1

    // ── 3-second countdown before auto-advance ──────────────────────────────
    var countdown by remember { mutableStateOf(3) }
    LaunchedEffect(Unit) {
        repeat(3) {
            delay(1000)
            countdown--
        }
        onNextLevel()
    }

    // Animated countdown progress (1f → 0f over 3 seconds)
    val progressAnim = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        progressAnim.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 3000, easing = LinearEasing)
        )
    }

    // Pulsing stars animation
    val starPulse = rememberInfiniteTransition(label = "starPulse")
    val starScale by starPulse.animateFloat(
        initialValue = 1f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            tween(600, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "starScale"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(bgColor)
            .statusBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // ── Trophy + title ───────────────────────────────────────────
            Text("🏆", fontSize = 72.sp)

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Niveau terminé !",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Palette.amber
                )
                Text(
                    puzzle.name,
                    fontSize = 14.sp,
                    color = mutedColor,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Niveau ${levelIdx + 1} / $totalLevels",
                    fontSize = 12.sp,
                    color = mutedColor.copy(alpha = 0.7f)
                )
            }

            // ── Stars ────────────────────────────────────────────────────
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Palette.amber.copy(alpha = if (darkMode) 0.15f else 0.10f)
                ),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(
                    Modifier.padding(vertical = 24.dp, horizontal = 16.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Animated stars raw
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(3) { i ->
                            val filled = i < stars
                            Text(
                                text = if (filled) "⭐" else "☆",
                                fontSize = 44.sp,
                                modifier = if (filled) Modifier.scale(starScale) else Modifier
                            )
                        }
                    }

                    // Star label
                    Text(
                        text = when (stars) {
                            3    -> "Parfait ! Aucune aide utilisée 🎯"
                            2    -> "Très bien ! Presque parfait 👍"
                            else -> "Bien joué ! Tu peux faire mieux 💪"
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        textAlign = TextAlign.Center
                    )

                    // Star criteria hint (only if not 3 stars)
                    if (stars < 3) {
                        Text(
                            text = when {
                                gameState.hintsUsed == 0 -> "↩️ Moins de 3 annulations → 3 ⭐"
                                gameState.undosUsed <= 2 -> "💡 Sans indices → 3 ⭐"
                                else                     -> "💡 Sans indices + ≤ 2 annulations → 3 ⭐"
                            },
                            fontSize = 11.sp,
                            color = mutedColor,
                            textAlign = TextAlign.Center,
                            fontStyle = FontStyle.Italic
                        )
                    }
                }
            }

            // ── Auto-advance countdown bar ────────────────────────────────
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardColor),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (isLastLevel) "🔄 Retour au début dans…" else "▶ Niveau suivant dans…",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = mutedColor
                        )
                        Text(
                            "${countdown}s",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            color = Palette.amber
                        )
                    }

                    // Animated progress bar (depletes left→right)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Palette.amber.copy(alpha = 0.18f))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progressAnim.value)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(Palette.amber)
                        )
                    }
                }
            }

            // ── Action buttons ────────────────────────────────────────────
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // "Next level" / "Restart" – tap to skip countdown
                Button(
                    onClick = onNextLevel,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isLastLevel) Palette.amber else Color(0xFF10B981)
                    )
                ) {
                    Text(
                        text = if (isLastLevel) "🔄 Recommencer depuis le début" else "Niveau suivant ▶",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                }

                // "Replay this level"
                OutlinedButton(
                    onClick = onReplay,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = textColor)
                ) {
                    Text("🔄 Rejouer ce niveau", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

// ─── Game Screen ───────────────────────────────────────────────────────────────
@Composable
fun GameScreen(
    puzzle: Puzzle,
    levelIdx: Int,
    gameState: GameState,
    darkMode: Boolean,
    lives: Int,
    completedLevels: Set<Int>,
    hintsBalance: Int,
    adHintsRemainingToday: Int,
    onUseHint: () -> Unit,
    onWatchAdForHint: () -> Unit,
    onDarkModeToggle: () -> Unit,
    onBack: () -> Unit,
    onNextLevel: () -> Unit,
    onLivesClick: () -> Unit
) {
    val bgColor    = if (darkMode) Color(0xFF15171C) else Color(0xFFF4F4F1)
    val cardColor  = if (darkMode) Color(0xFF1E222B) else Color.White
    val textColor  = if (darkMode) Color.White       else Color(0xFF1E293B)
    val mutedColor = if (darkMode) Color(0xFF94A3B8) else Color(0xFF64748B)

    val context  = LocalContext.current
    val activity = context as? Activity

    var showHintConfirm  by remember { mutableStateOf(false) }
    var showHintGranted  by remember { mutableStateOf(false) }
    var rewardedAd by remember { mutableStateOf<RewardedAd?>(null) }

    val canUseHint = hintsBalance > 0
    val canWatchAdForHint = adHintsRemainingToday > 0

    // Pre-load a rewarded ad whenever the daily cap hasn't been reached yet.
    LaunchedEffect(adHintsRemainingToday) {
        if (canWatchAdForHint && rewardedAd == null) {
            RewardedAd.load(
                context, AdIds.REWARDED, AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) { rewardedAd = ad }
                    override fun onAdFailedToLoad(error: LoadAdError) { rewardedAd = null }
                }
            )
        }
    }

    fun watchAdForHint() {
        val ad = rewardedAd
        val act = activity
        if (ad != null && act != null) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() { rewardedAd = null }
            }
            // onUserEarnedReward — the user watched the video to completion.
            ad.show(act) { showHintGranted = true }
        }
    }

    if (showHintConfirm) {
        AlertDialog(
            onDismissRequest = { showHintConfirm = false },
            title = { Text("💡 Indice") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Un indice révèle un rectangle de la solution.")
                    Text(
                        if (canUseHint) "• Indices disponibles : $hintsBalance" else "• Plus d'indice en stock",
                        fontSize = 12.sp
                    )
                    Text(
                        if (canWatchAdForHint) "• Pubs restantes aujourd'hui : $adHintsRemainingToday / ${HintsManager.MAX_AD_HINTS_A_DAY}"
                        else "• Limite quotidienne de pubs atteinte, reviens demain",
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                when {
                    canUseHint -> TextButton(onClick = { showHintConfirm = false; onUseHint() }) {
                        Text("Utiliser l'indice", fontWeight = FontWeight.Bold)
                    }
                    canWatchAdForHint -> TextButton(
                        onClick = { showHintConfirm = false; watchAdForHint() },
                        enabled = rewardedAd != null
                    ) {
                        Text(if (rewardedAd != null) "🎬 Regarder une pub" else "⏳ Chargement…", fontWeight = FontWeight.Bold)
                    }
                    else -> TextButton(onClick = { showHintConfirm = false }) { Text("OK") }
                }
            },
            dismissButton = {
                if (canUseHint && canWatchAdForHint) {
                    TextButton(
                        onClick = { showHintConfirm = false; watchAdForHint() },
                        enabled = rewardedAd != null
                    ) {
                        Text(if (rewardedAd != null) "🎬 Regarder une pub" else "⏳ Chargement…")
                    }
                } else {
                    TextButton(onClick = { showHintConfirm = false }) { Text("Annuler") }
                }
            }
        )
    }

    // Shown once the rewarded video has actually been watched, before the hint is revealed.
    if (showHintGranted) {
        AlertDialog(
            onDismissRequest = { showHintGranted = false; onWatchAdForHint() },
            title = { Text("🎉 Indice débloqué !") },
            text = { Text("Merci d'avoir regardé la vidéo. Ton indice va être révélé sur la grille.") },
            confirmButton = {
                TextButton(onClick = { showHintGranted = false; onWatchAdForHint() }) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    Box(Modifier.fillMaxSize().background(bgColor).statusBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            // ── Header ──────────────────────────────────────────────────────
            Box(
                Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(cardColor)
                        .border(1.dp, if (darkMode) Color(0xFF1E293B) else Color(0xFFE2E8F0), RoundedCornerShape(14.dp))
                ) {
                    BackArrowIcon(color = Palette.amber, modifier = Modifier.size(20.dp))
                }

                Text(
                    "AreaFill",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    color = Palette.amber,
                    modifier = Modifier.align(Alignment.Center)
                )

                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HeartsRow(
                        lives = lives,
                        fontSize = 15.sp,
                        modifier = Modifier.clickable(onClick = onLivesClick)
                    )
                    IconButton(
                        onClick = onDarkModeToggle,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(cardColor)
                            .border(1.dp, if (darkMode) Color(0xFF1E293B) else Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                    ) {
                        Text(if (darkMode) "☀️" else "🌙", fontSize = 18.sp)
                    }
                }
            }

            // ── Stats bar ────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val covered = gameState.placedRects.sumOf { it.area }
                val total   = puzzle.size * puzzle.size
                val pct     = (covered.toFloat() / total * 100).toInt().coerceIn(0, 100)
                val mins    = gameState.elapsedSeconds / 60
                val secs    = gameState.elapsedSeconds % 60

                listOf("Temps" to "%02d:%02d".format(mins, secs), "Grille" to "$pct%").forEach { (label, value) ->
                    Card(
                        Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = cardColor),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Column(Modifier.padding(8.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, fontSize = 9.sp, color = mutedColor, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = textColor)
                        }
                    }
                }
            }

            // ── Grid card ────────────────────────────────────────────────────
            Card(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = cardColor),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(puzzle.name, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = textColor)
                        if (completedLevels.contains(levelIdx)) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF10B981).copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text("✓ Terminé", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                            }
                        }
                    }

                    Text(
                        puzzle.desc,
                        fontSize = 11.sp, color = mutedColor,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        textAlign = TextAlign.Start, fontWeight = FontWeight.Medium
                    )

                    GameGrid(
                        gameState = gameState,
                        darkMode  = darkMode,
                        onPlace   = { r1, c1, r2, c2 -> gameState.placeRect(r1, c1, r2, c2) },
                        onRemove  = { r, c -> gameState.removeRect(r, c) }
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick  = { gameState.undo() },
                            enabled  = gameState.history.isNotEmpty(),
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape    = RoundedCornerShape(12.dp),
                            colors   = ButtonDefaults.outlinedButtonColors(contentColor = textColor)
                        ) {
                            Text("↩ Annuler", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        OutlinedButton(
                            onClick  = { showHintConfirm = true },
                            enabled  = canUseHint || canWatchAdForHint,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape    = RoundedCornerShape(12.dp),
                            colors   = ButtonDefaults.outlinedButtonColors(contentColor = textColor)
                        ) {
                            Text(
                                if (canUseHint) "💡 Indice ($hintsBalance)" else "💡 Indice",
                                fontWeight = FontWeight.Bold, fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            // ── Legend ───────────────────────────────────────────────────────
            ShapeLegendCard(cardColor = cardColor, textColor = textColor, mutedColor = mutedColor)
            }
            AdMobBanner(Modifier.navigationBarsPadding())
        }
    }
}

// ─── Game Grid ─────────────────────────────────────────────────────────────────
@Composable
fun GameGrid(
    gameState: GameState,
    darkMode: Boolean,
    onPlace: (Int, Int, Int, Int) -> Unit,
    onRemove: (Int, Int) -> Unit
) {
    val size        = gameState.size
    val clues       = gameState.clues
    val placedRects = gameState.placedRects

    var gridSizePx  by remember { mutableStateOf(0f) }
    var dragStart   by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var dragCurrent by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var isDragging  by remember { mutableStateOf(false) }

    val cellBorderColor = if (darkMode) Color(0xFF475569) else Color(0xFFCBD5E1)
    val gridBorderColor = if (darkMode) Color(0xFF475569) else Color(0xFFCBD5E1)
    val gridBgColor     = if (darkMode) Color(0xFF1E222B) else Color.White

    // Seuil en pixels au-delà duquel on considère que c'est un vrai drag
    val TAP_SLOP = 10f

    fun offsetToCell(offset: Offset): Pair<Int, Int>? {
        if (gridSizePx == 0f) return null
        val cellSize = gridSizePx / size
        val col = (offset.x / cellSize).toInt().coerceIn(0, size - 1)
        val row = (offset.y / cellSize).toInt().coerceIn(0, size - 1)
        return Pair(row, col)
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(gridBgColor)
            .border(1.dp, gridBorderColor, RoundedCornerShape(16.dp))
            .onGloballyPositioned { gridSizePx = it.size.width.toFloat() }
            .pointerInput(gameState.isWon) {
                if (gameState.isWon) return@pointerInput

                awaitPointerEventScope {
                    while (true) {
                        // ── Attendre un premier appui ──────────────────────
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startPos = down.position
                        val startCell = offsetToCell(startPos) ?: continue

                        var currentPos = startPos
                        var movedBeyondSlop = false

                        dragStart   = startCell
                        dragCurrent = startCell
                        isDragging  = false

                        soundSelect()

                        // ── Suivre le mouvement jusqu'au relâchement ───────
                        var pointerUp = false
                        while (!pointerUp) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull() ?: break

                            if (change.changedToUp()) {
                                // Doigt levé
                                pointerUp = true
                                change.consume()
                            } else {
                                currentPos = change.position
                                val delta = (currentPos - startPos).getDistance()

                                if (delta > TAP_SLOP) {
                                    movedBeyondSlop = true
                                    isDragging = true
                                }

                                if (movedBeyondSlop) {
                                    dragCurrent = offsetToCell(currentPos)
                                }
                                change.consume()
                            }
                        }

                        isDragging = false
                        val s = dragStart
                        val c = dragCurrent

                        if (s != null && c != null) {
                            if (!movedBeyondSlop) {
                                onRemove(s.first, s.second)
                            } else if (s != c) {
                                // Drag → placer un nouveau rectangle
                                onPlace(s.first, s.second, c.first, c.second)
                            } else {
                                onRemove(s.first, s.second)
                            }
                        }

                        dragStart   = null
                        dragCurrent = null
                    }
                }
            }
    ) {
        val cellSize = if (gridSizePx > 0) gridSizePx / size else 0f

        Canvas(Modifier.fillMaxSize()) {
            for (i in 0..size) {
                val pos = i * cellSize
                val sw  = if (i == 0 || i == size) 2.5f else 1.2f
                drawLine(cellBorderColor, Offset(pos, 0f), Offset(pos, gridSizePx), sw)
                drawLine(cellBorderColor, Offset(0f, pos), Offset(gridSizePx, pos), sw)
            }

            placedRects.filter { it.valid }.forEach { rect ->
                val clue        = clues.getOrNull(rect.clueIdx)
                val fillColor   = clue?.lightColor ?: Color.Gray.copy(0.1f)
                val borderColor = clue?.color      ?: Color.Gray
                val inset = 3f
                val left   = rect.cMin * cellSize + inset
                val top    = rect.rMin * cellSize + inset
                val right  = (rect.cMax + 1) * cellSize - inset
                val bottom = (rect.rMax + 1) * cellSize - inset
                val rectF  = Rect(left, top, right, bottom)
                drawRoundRect(fillColor, rectF.topLeft, rectF.size, CornerRadius(12f))
                val path = Path().apply { addRoundRect(RoundRect(rectF, CornerRadius(12f))) }
                drawPath(path, borderColor, style = Stroke(2.2f))
            }

            val s = dragStart; val c = dragCurrent
            if (isDragging && s != null && c != null && cellSize > 0f) {
                val rMin = min(s.first, c.first);  val rMax = max(s.first, c.first)
                val cMin = min(s.second, c.second); val cMax = max(s.second, c.second)
                val inset = 3f
                val left   = cMin * cellSize + inset
                val top    = rMin * cellSize + inset
                val right  = (cMax + 1) * cellSize - inset
                val bottom = (rMax + 1) * cellSize - inset
                val rectF  = Rect(left, top, right, bottom)
                drawRoundRect(Color(0xFF6F76D9).copy(alpha = 0.12f), rectF.topLeft, rectF.size, CornerRadius(12f))
                val path = Path().apply { addRoundRect(RoundRect(rectF, CornerRadius(12f))) }
                drawPath(path, Color(0xFF6F76D9), style = Stroke(2.2f))
            }
        }

        if (cellSize > 0f) {
            clues.forEach { clue ->
                val left = clue.col * cellSize
                val top  = clue.row * cellSize
                Box(
                    Modifier.offset { IntOffset(left.toInt(), top.toInt()) }.size(cellSize.toDp()),
                    contentAlignment = Alignment.Center
                ) {
                    if (clue.value == null) {
                        val w = when (clue.shape) { ClueShape.TALL -> 0.5f; else -> 0.75f }
                        val h = when (clue.shape) { ClueShape.WIDE -> 0.5f; else -> 0.75f }
                        Box(
                            Modifier
                                .fillMaxWidth(w).fillMaxHeight(h)
                                .clip(RoundedCornerShape(4.dp))
                                .background(clue.color.copy(alpha = 0.75f))
                        )
                    } else {
                        ClueToken(clue = clue, cellSizeDp = cellSize.toDp())
                    }
                }
            }
        }

        // ── Label dimensionnel pendant le drag ─────────────────────────────
        val s = dragStart; val c = dragCurrent
        if (isDragging && s != null && c != null && cellSize > 0f) {
            val rMin = min(s.first, c.first);  val rMax = max(s.first, c.first)
            val cMin = min(s.second, c.second); val cMax = max(s.second, c.second)
            val w = cMax - cMin + 1; val h = rMax - rMin + 1
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            ((cMin + cMax + 1) / 2f * cellSize - 36).toInt(),
                            ((rMax + 1) * cellSize + 4).toInt()
                        )
                    }
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF6F76D9))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("$w × $h = ${w * h}", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ─── Extension ────────────────────────────────────────────────────────────────
@Composable
fun Float.toDp(): Dp = with(LocalDensity.current) { this@toDp.toDp() }

// ─── Clue Token ───────────────────────────────────────────────────────────────
@Composable
fun ClueToken(clue: Clue, cellSizeDp: Dp) {
    val isAny        = clue.constraint == Constraint.ANY
    val rawFontSp    = cellSizeDp.value * 0.30f
    val fontSizeSp   = rawFontSp.coerceIn(9f, 18f)
    val badgeFraction = when {
        cellSizeDp.value < 36f -> 0.72f
        cellSizeDp.value < 52f -> 0.64f
        else                   -> 0.58f
    }

    Box(Modifier.fillMaxSize().padding(1.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw   = 2.0f
            val dash = if (isAny) PathEffect.dashPathEffect(floatArrayOf(5f, 4f)) else null
            when {
                isAny -> {
                    listOf(
                        Rect(size.width * 0.28f, size.height * 0.05f, size.width * 0.72f, size.height * 0.95f),
                        Rect(size.width * 0.05f, size.height * 0.28f, size.width * 0.95f, size.height * 0.72f)
                    ).forEach { r ->
                        val p = Path().apply { addRoundRect(RoundRect(r, CornerRadius(6f))) }
                        drawPath(p, clue.color, style = Stroke(sw, pathEffect = dash))
                    }
                }
                clue.shape == ClueShape.SQUARE -> {
                    val r = Rect(size.width * 0.06f, size.height * 0.06f, size.width * 0.94f, size.height * 0.94f)
                    drawPath(Path().apply { addRoundRect(RoundRect(r, CornerRadius(8f))) }, clue.color, style = Stroke(sw))
                }
                clue.shape == ClueShape.TALL -> {
                    val r = Rect(size.width * 0.25f, size.height * 0.06f, size.width * 0.75f, size.height * 0.94f)
                    drawPath(Path().apply { addRoundRect(RoundRect(r, CornerRadius(8f))) }, clue.color, style = Stroke(sw))
                }
                clue.shape == ClueShape.WIDE -> {
                    val r = Rect(size.width * 0.06f, size.height * 0.25f, size.width * 0.94f, size.height * 0.75f)
                    drawPath(Path().apply { addRoundRect(RoundRect(r, CornerRadius(8f))) }, clue.color, style = Stroke(sw))
                }
            }
        }
        Box(
            Modifier
                .fillMaxSize(badgeFraction)
                .border(1.5.dp, Color.White.copy(alpha = 0.60f), RoundedCornerShape(5.dp))
                .clip(RoundedCornerShape(5.dp))
                .background(clue.color),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text          = clue.value.toString(),
                fontSize      = fontSizeSp.sp,
                fontWeight    = FontWeight.ExtraBold,
                color         = Color.White,
                textAlign     = TextAlign.Center,
                maxLines      = 1,
                softWrap      = false,
                letterSpacing = (-0.5).sp
            )
        }
    }
}

// ─── Shape Legend Card ────────────────────────────────────────────────────────
@Composable
fun ShapeLegendCard(cardColor: Color, textColor: Color, mutedColor: Color) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "GUIDE DES FORMES",
                fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp, color = textColor,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                textAlign = TextAlign.Center
            )
            val legendItems = listOf(
                Triple("Carré (imposé)",           ClueShape.SQUARE, false),
                Triple("Rectangle haut (imposé)",  ClueShape.TALL,   false),
                Triple("Rectangle large (imposé)", ClueShape.WIDE,   false),
                Triple("N'importe quelle forme",   ClueShape.SQUARE, true),
            )
            legendItems.chunked(2).forEach { row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { (label, shape, isAny) ->
                        Row(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF64748B).copy(alpha = 0.08f))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Canvas(Modifier.size(28.dp)) {
                                val sw   = 2.2f
                                val col  = Color(0xFF8F9CA6)
                                val dash = if (isAny) PathEffect.dashPathEffect(floatArrayOf(4f, 3f)) else null
                                when {
                                    isAny -> {
                                        listOf(
                                            Rect(size.width*.30f, size.height*.05f, size.width*.70f, size.height*.95f),
                                            Rect(size.width*.05f, size.height*.30f, size.width*.95f, size.height*.70f)
                                        ).forEach { r ->
                                            val p = Path().apply { addRoundRect(RoundRect(r, CornerRadius(5f))) }
                                            drawPath(p, col, style = Stroke(sw, pathEffect = dash))
                                        }
                                        val cr = Rect(size.width*.33f, size.height*.33f, size.width*.67f, size.height*.67f)
                                        drawRoundRect(col.copy(.35f), cr.topLeft, cr.size, CornerRadius(3f))
                                    }
                                    shape == ClueShape.SQUARE -> {
                                        val r = Rect(size.width*.06f, size.height*.06f, size.width*.94f, size.height*.94f)
                                        drawPath(Path().apply { addRoundRect(RoundRect(r, CornerRadius(5f))) }, col, style = Stroke(sw))
                                    }
                                    shape == ClueShape.TALL -> {
                                        val r = Rect(size.width*.28f, size.height*.06f, size.width*.72f, size.height*.94f)
                                        drawPath(Path().apply { addRoundRect(RoundRect(r, CornerRadius(5f))) }, col, style = Stroke(sw))
                                    }
                                    else -> {
                                        val r = Rect(size.width*.06f, size.height*.28f, size.width*.94f, size.height*.72f)
                                        drawPath(Path().apply { addRoundRect(RoundRect(r, CornerRadius(5f))) }, col, style = Stroke(sw))
                                    }
                                }
                            }
                            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = mutedColor, lineHeight = 13.sp)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Le numéro dans chaque jeton = surface exacte de la région à dessiner.",
                fontSize = 10.sp, color = mutedColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
                fontStyle = FontStyle.Italic
            )
        }
    }
}

// ─── Preview ───────────────────────────────────────────────────────────────────
@Preview(showBackground = true, showSystemUi = true)
@Composable
fun DefaultPreview() {
    RectaFormeApp()
}