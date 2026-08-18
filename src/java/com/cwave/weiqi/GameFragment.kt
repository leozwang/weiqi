package com.cwave.weiqi

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Lock
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.Fragment
import com.cwave.weiqi.katago.IKataGoBridge
import com.cwave.weiqi.katago.KataGoBridge
import com.cwave.weiqi.katago.KataGoBridgeEigen
import com.cwave.weiqi.katago.KataGoBridgeTPU
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

class GameFragment : Fragment() {
  private var bridge: IKataGoBridge = KataGoBridge()
  private val boardSize = 19
  private lateinit var billingManager: BillingManager

  enum class EngineBackend { AUTO, TPU, GPU, CPU }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    billingManager = BillingManager(requireContext(), lifecycleScope)
    val prefs = requireContext().getSharedPreferences("weiqi_settings", android.content.Context.MODE_PRIVATE)
    val savedBackendStr = prefs.getString("selected_backend", EngineBackend.AUTO.name) ?: EngineBackend.AUTO.name
    val initialBackend = try { EngineBackend.valueOf(savedBackendStr) } catch (e: Exception) { EngineBackend.AUTO }
    bridge = createEngineBridge(initialBackend)
  }

  private fun createEngineBridge(backendPreference: EngineBackend = EngineBackend.AUTO): IKataGoBridge {
    return when (backendPreference) {
      EngineBackend.TPU -> {
        if (KataGoBridgeTPU.isSupported()) {
          Log.i("GameFragment", "Explicit TPU backend requested. Initializing KataGoBridgeTPU.")
          KataGoBridgeTPU()
        } else {
          Log.w("GameFragment", "TPU backend not available, falling back to OpenCL GPU.")
          KataGoBridge()
        }
      }
      EngineBackend.GPU -> {
        Log.i("GameFragment", "Explicit GPU (OpenCL) backend requested. Initializing KataGoBridge.")
        KataGoBridge()
      }
      EngineBackend.CPU -> {
        Log.i("GameFragment", "Explicit CPU (Eigen) backend requested. Initializing KataGoBridgeEigen.")
        KataGoBridgeEigen()
      }
      EngineBackend.AUTO -> {
        if (WeiqiApplication.isPixelTpuSupported() && KataGoBridgeTPU.isSupported()) {
          val chipName = when {
            WeiqiApplication.isPixel11Family() -> "Pixel 11 (Tensor G6 / SantaFe TPU)"
            WeiqiApplication.isPixel9Family() -> "Pixel 9 (Tensor G4 / Zuma TPU)"
            else -> "Pixel Tensor TPU"
          }
          Log.i("GameFragment", "Auto backend: $chipName detected. Initializing KataGoBridgeTPU.")
          KataGoBridgeTPU()
        } else {
          Log.i("GameFragment", "Auto backend: Initializing KataGoBridge (OpenCL GPU).")
          KataGoBridge()
        }
      }
    }
  }

  enum class Stone { EMPTY, BLACK, WHITE }
  enum class GameMode { USER_BLACK, USER_WHITE, USER_BOTH, AI_BOTH }

  data class GameSettings(
    val mode: GameMode = GameMode.USER_BLACK,
    val handicap: Int = 0,
    val modelName: String = "model.bin.gz"
  )

  data class CandidateMove(
    val x: Int,
    val y: Int,
    val winrate: Double,
    val visits: Long
  )

  data class AnalysisResult(
    val winrate: Double = 0.5,
    val scoreLead: Double = 0.0,
    val visits: Long = 0,
    val ownership: DoubleArray = DoubleArray(361) { 0.0 },
    val candidates: List<CandidateMove> = emptyList()
  )

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    return ComposeView(requireContext()).apply {
      setContent {
        MaterialTheme {
          var isEngineInitialized by remember { mutableStateOf(false) }
          var isThinking by remember { mutableStateOf(true) }
          var engineError by remember { mutableStateOf<Int?>(null) }
          val context = androidx.compose.ui.platform.LocalContext.current
          val initialTuningText = when {
            bridge is KataGoBridgeTPU -> context.getString(R.string.tuning_tpu)
            bridge is KataGoBridge -> context.getString(R.string.tuning_gpu)
            else -> context.getString(R.string.initializing_engine)
          }
          var activeBridge by remember { mutableStateOf(bridge) }
          var statusText by remember { mutableStateOf(initialTuningText) }


          Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colors.background
          ) {
            GameScreen(
              bridge = activeBridge,
              onBridgeChange = { activeBridge = it },
              billingManager = billingManager,
              isEngineInitialized = isEngineInitialized,
              onEngineInitializedChange = { isEngineInitialized = it },
              isThinking = isThinking,
              onThinkingChange = { isThinking = it },
              statusText = statusText,
              onStatusTextChange = { statusText = it },
              engineError = engineError,
              onEngineErrorChange = { engineError = it }
            )

            if (engineError != null) {
              val errorCode = engineError!!
              val (title, message) = when (errorCode) {
                -1 -> context.getString(R.string.error_title_init_failed) to context.getString(R.string.error_msg_corrupt_assets)
                -2 -> context.getString(R.string.error_title_init_failed) to context.getString(R.string.error_msg_missing_model, errorCode)
                -4 -> context.getString(R.string.error_title_asset_failure) to context.getString(R.string.error_msg_corrupt_assets)
                -6 -> context.getString(R.string.error_title_asset_failure) to context.getString(R.string.error_msg_no_disk_space)
                in -15..-10 -> context.getString(R.string.error_title_init_failed) to context.getString(R.string.error_msg_gpu_error, -errorCode)
                in -18..-16 -> context.getString(R.string.error_title_init_failed) to context.getString(R.string.error_msg_engine_crash, -errorCode)
                else -> context.getString(R.string.error_title_init_failed) to context.getString(R.string.error_msg_generic_init_error, errorCode)
              }

              AlertDialog(
                onDismissRequest = { /* Force user to resolve or exit */ },
                shape = RoundedCornerShape(28.dp),
                title = {
                  Text(text = title, style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
                },
                text = {
                  Text(text = message, style = MaterialTheme.typography.body1)
                },
                confirmButton = {
                  Button(
                    onClick = {
                      engineError = null
                      // Trigger re-initialization of screen
                      isThinking = true
                      statusText = context.getString(R.string.initializing_engine)
                    },
                    shape = RoundedCornerShape(24.dp)
                  ) {
                    Text(context.getString(R.string.btn_retry), fontWeight = FontWeight.Bold)
                  }
                },
                dismissButton = {
                  TextButton(
                    onClick = {
                      engineError = null
                    }
                  ) {
                    Text(context.getString(R.string.btn_dismiss), fontWeight = FontWeight.Bold, color = Color.Gray)
                  }
                }
              )
            }

            if (!isEngineInitialized && isThinking && engineError == null) {
              Box(
                modifier = Modifier
                  .fillMaxSize()
                  .background(Color.Black.copy(alpha = 0.6f))
                  .pointerInput(Unit) {},
                contentAlignment = Alignment.Center
              ) {
                Card(
                  shape = RoundedCornerShape(24.dp),
                  elevation = 8.dp,
                  modifier = Modifier.padding(32.dp)
                ) {
                  Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                  ) {
                    CircularProgressIndicator(
                      color = MaterialTheme.colors.primary,
                      strokeWidth = 4.dp
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                      text = statusText,
                      style = MaterialTheme.typography.h6,
                      fontWeight = FontWeight.Bold,
                      textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                      text = "Please wait, this happens only once.",
                      style = MaterialTheme.typography.caption,
                      color = Color.Gray
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  @Composable
  fun GameScreen(
    bridge: IKataGoBridge,
    onBridgeChange: (IKataGoBridge) -> Unit,
    billingManager: BillingManager,
    isEngineInitialized: Boolean,
    onEngineInitializedChange: (Boolean) -> Unit,
    isThinking: Boolean,
    onThinkingChange: (Boolean) -> Unit,
    statusText: String,
    onStatusTextChange: (String) -> Unit,
    engineError: Int?,
    onEngineErrorChange: (Int?) -> Unit
  ) {
    val scope = rememberCoroutineScope()
    val isPremiumUnlocked by billingManager.isPremiumUnlocked.collectAsState()
    val productDetails by billingManager.productDetails.collectAsState()
    var boardState by remember { mutableStateOf(Array(boardSize) { Array(boardSize) { Stone.EMPTY } }) }
    var previewMove by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var lastMove by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var analysis by remember { mutableStateOf(AnalysisResult()) }
    var showAnalysis by remember { mutableStateOf(false) }
    
    val sharedPrefs = remember { requireContext().getSharedPreferences("weiqi_settings", android.content.Context.MODE_PRIVATE) }
    val initialModeName = remember { sharedPrefs.getString("current_mode", GameMode.USER_BLACK.name) ?: GameMode.USER_BLACK.name }
    val initialMode = remember(initialModeName) {
      try { GameMode.valueOf(initialModeName) } catch (e: Exception) { GameMode.USER_BLACK }
    }

    val initialBackendName = remember { sharedPrefs.getString("selected_backend", EngineBackend.AUTO.name) ?: EngineBackend.AUTO.name }
    val initialBackend = remember(initialBackendName) {
      try { EngineBackend.valueOf(initialBackendName) } catch (e: Exception) { EngineBackend.AUTO }
    }
    var currentBackend by remember { mutableStateOf(initialBackend) }

    val initialShowLatency = remember { sharedPrefs.getBoolean("show_engine_latency", false) }
    var showEngineLatency by remember { mutableStateOf(initialShowLatency) }
    var lastEngineLatencyMs by remember { mutableStateOf<Long?>(null) }

    val backendBadgeText = when (bridge) {
      is KataGoBridgeTPU -> {
        if (WeiqiApplication.isPixel11Family()) "TPU (Pixel 11)"
        else if (WeiqiApplication.isPixel9Family()) "TPU (Pixel 9)"
        else "TPU (Tensor)"
      }
      is KataGoBridge -> {
        if (WeiqiApplication.isPixel11Family()) "GPU (Pixel 11 OpenCL)"
        else if (WeiqiApplication.isPixel9Family()) "GPU (Pixel 9 OpenCL)"
        else "GPU (OpenCL)"
      }
      is KataGoBridgeEigen -> "CPU (Eigen)"
      else -> "KataGo"
    }

    val backendDetailText = when (bridge) {
      is KataGoBridgeTPU -> {
        if (WeiqiApplication.isPixel11Family()) "Google Tensor G6 (SantaFe TPU) via LiteRT"
        else if (WeiqiApplication.isPixel9Family()) "Google Tensor G4 (Zuma TPU) via LiteRT"
        else "Google Tensor TPU via LiteRT"
      }
      is KataGoBridge -> {
        if (WeiqiApplication.isPixel11Family()) "Pixel 11 ARM Immortalis/Mali GPU (OpenCL)"
        else if (WeiqiApplication.isPixel9Family()) "Pixel 9 ARM Mali-G715 GPU (OpenCL)"
        else "OpenCL Mobile GPU (Mali/Adreno)"
      }
      is KataGoBridgeEigen -> "CPU Eigen Multi-threaded Fallback"
      else -> "KataGo Engine"
    }

    var lastMoveText by remember { mutableStateOf("No moves yet") }
    var currentMode by remember { mutableStateOf(initialMode) }
    var currentTurn by remember { mutableStateOf(Stone.BLACK) }
    var aiAutoPlay by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val initialHandicap = remember { sharedPrefs.getInt("handicap", 0) }
    var handicap by remember { mutableStateOf(initialHandicap) }
    val initialModelName = remember { sharedPrefs.getString("current_model_name", "model.bin.gz") ?: "model.bin.gz" }
    var currentModelName by remember { mutableStateOf(initialModelName) }
    val initialVisits = remember { sharedPrefs.getInt("current_visits", 500) }
    var currentVisits by remember { mutableStateOf(initialVisits) }
    val initialGameInProgress = remember { sharedPrefs.getBoolean("game_in_progress", false) }
    var isGameInProgress by remember { mutableStateOf(initialGameInProgress) }

    var moveHistory by remember { mutableStateOf(listOf<String>()) }
    var historyIndex by remember { mutableStateOf(-1) }
    var consecutivePasses by remember { mutableStateOf(0) }
    var finalScoreText by remember { mutableStateOf<String?>(null) }

    fun saveGameState(
      history: List<String> = moveHistory,
      idx: Int = historyIndex,
      turn: Stone = currentTurn,
      score: String? = finalScoreText,
      passes: Int = consecutivePasses,
      backend: EngineBackend = currentBackend
    ) {
      val movesJson = org.json.JSONArray(history.take(idx + 1)).toString()
      sharedPrefs.edit()
        .putBoolean("game_in_progress", true)
        .putString("saved_moves", movesJson)
        .putString("current_turn", turn.name)
        .putString("final_score_text", score)
        .putInt("consecutive_passes", passes)
        .putString("current_mode", currentMode.name)
        .putInt("handicap", handicap)
        .putString("current_model_name", currentModelName)
        .putInt("current_visits", currentVisits)
        .putString("selected_backend", backend.name)
        .apply()
    }

    // Automatically update analysis when turn changes or analysis is toggled ON
    LaunchedEffect(currentTurn, showAnalysis) {
      if (showAnalysis && isEngineInitialized) {
        val isHumanTurn = when(currentMode) {
            GameMode.USER_BLACK -> currentTurn == Stone.BLACK
            GameMode.USER_WHITE -> currentTurn == Stone.WHITE
            GameMode.USER_BOTH -> true
            GameMode.AI_BOTH -> false
        }

        if (isHumanTurn && !isThinking) {
            val colorStr = if (currentTurn == Stone.WHITE) "white" else "black"
            withContext(Dispatchers.IO) {
                onStatusTextChange("Analyzing position...")
                val analysisVisits = (currentVisits * 0.4).toInt().coerceIn(100, 1000)
                val startTime = System.currentTimeMillis()
                bridge.sendGtpCommand("think $colorStr $analysisVisits")
                val elapsed = System.currentTimeMillis() - startTime
                lastEngineLatencyMs = elapsed
                Log.i("KataGoLatency", "[$backendBadgeText] think $colorStr completed in ${elapsed}ms")
                onStatusTextChange("Turn.")
            }
        }
        
        if (!isThinking) {
            val startTime = System.currentTimeMillis()
            analysis = getAnalysis(bridge, currentTurn)
            val elapsed = System.currentTimeMillis() - startTime
            lastEngineLatencyMs = elapsed
        }
      }
    }

    val context = requireContext()
    val soundPool = remember {
      android.media.SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
          android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_GAME)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        )
        .build()
    }
    val soundId = remember { mutableStateOf<Int?>(null) }
        
    LaunchedEffect(Unit) {
      withContext(Dispatchers.IO) {
        val path = copyAssetToFile("place_stone.mp3")
        if (path != null) {
          soundId.value = soundPool.load(path, 1)
        }
      }
    }

    fun playMoveSound() {
      soundId.value?.let { id ->
        soundPool.play(id, 1f, 1f, 0, 0, 1f)
      }
    }

    fun playPassSound() {
      try {
        val toneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 85)
        toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
      } catch (e: Exception) {
        soundId.value?.let { id ->
          soundPool.play(id, 0.8f, 0.8f, 1, 0, 0.65f)
        }
      }
    }

    suspend fun checkGameEnd() {
      if (consecutivePasses >= 2) {
        onStatusTextChange("Game ended. Scoring...")
        onThinkingChange(true)
        var scoreText: String? = null
        withContext(Dispatchers.IO) {
          val score = bridge.sendGtpCommand("final_score")
          if (score.startsWith("=")) {
            scoreText = score.substring(1).trim()
          }
        }
        if (scoreText != null) {
          finalScoreText = scoreText
          onStatusTextChange("Game Over")
          analysis = getAnalysis(bridge, currentTurn)
          saveGameState(score = scoreText)
        }
        onThinkingChange(false)
      }
    }

    suspend fun handleAiMove(color: Stone) {
      if (finalScoreText != null) return
      onThinkingChange(true)
      val turnName = if (color == Stone.BLACK) "Black" else "White"
      onStatusTextChange("AI ($turnName) is thinking...")
      genAiMove(color, bridge) { aiX, aiY, aiMoveStr, elapsedMs ->
        onThinkingChange(false)
        lastEngineLatencyMs = elapsedMs
        boardState = syncBoardState(bridge)
        if (aiX != -1 && aiY != -1) {
          lastMove = aiX to aiY
          playMoveSound()
          val colorStr = if (color == Stone.BLACK) "Black" else "White"
          val latencySuffix = if (showEngineLatency) " (${formatLatency(elapsedMs)})" else ""
          lastMoveText = "$colorStr (AI) played $aiMoveStr$latencySuffix"
          
          // Update history
          val newHistory = moveHistory.take(historyIndex + 1) + aiMoveStr
          val newIndex = historyIndex + 1
          moveHistory = newHistory
          historyIndex = newIndex
          consecutivePasses = 0
          
          val nextTurn = if (color == Stone.BLACK) Stone.WHITE else Stone.BLACK
          currentTurn = nextTurn
          onStatusTextChange("Turn.")
          saveGameState(history = newHistory, idx = newIndex, turn = nextTurn, score = finalScoreText, passes = 0)

          if (currentMode == GameMode.AI_BOTH && aiAutoPlay && finalScoreText == null) {
            scope.launch {
              kotlinx.coroutines.delay(600)
              if (currentMode == GameMode.AI_BOTH && aiAutoPlay && finalScoreText == null) {
                handleAiMove(nextTurn)
              }
            }
          }
        } else if (aiMoveStr == "PASS") {
          val latencySuffix = if (showEngineLatency) " (${formatLatency(elapsedMs)})" else ""
          val colorStr = if (color == Stone.BLACK) "Black" else "White"
          lastMoveText = "$colorStr (AI) passed.$latencySuffix"
          android.widget.Toast.makeText(context, R.string.msg_ai_passed, android.widget.Toast.LENGTH_SHORT).show()
          playPassSound()
          val newPasses = consecutivePasses + 1
          consecutivePasses = newPasses
          val nextTurn = if (color == Stone.BLACK) Stone.WHITE else Stone.BLACK
          currentTurn = nextTurn
          onStatusTextChange("Turn.")
          scope.launch { checkGameEnd() }
          saveGameState(turn = nextTurn, passes = newPasses)

          if (newPasses < 2 && currentMode == GameMode.AI_BOTH && aiAutoPlay && finalScoreText == null) {
            scope.launch {
              kotlinx.coroutines.delay(600)
              if (currentMode == GameMode.AI_BOTH && aiAutoPlay && finalScoreText == null) {
                handleAiMove(nextTurn)
              }
            }
          } else if (newPasses >= 2) {
            aiAutoPlay = false
          }
        } else if (aiMoveStr.lowercase() == "resign") {
            aiAutoPlay = false
            val winner = if (color == Stone.BLACK) "White" else "Black"
            onStatusTextChange("AI Resigned. $winner wins!")
            lastMoveText = "AI Resigned."
            val scoreText = "$winner wins by resignation"
            finalScoreText = scoreText
            saveGameState(score = scoreText)
        } else {
          aiAutoPlay = false
          onStatusTextChange("AI error.")
        }
      }
    }



    suspend fun startNewGame(mode: GameMode, h: Int, m: String, v: Int, backend: EngineBackend = currentBackend) {
      onThinkingChange(true)
      isGameInProgress = true

      val targetBridgeClass = when (backend) {
        EngineBackend.TPU -> KataGoBridgeTPU::class.java
        EngineBackend.GPU -> KataGoBridge::class.java
        EngineBackend.CPU -> KataGoBridgeEigen::class.java
        EngineBackend.AUTO -> if (WeiqiApplication.isPixelTpuSupported() && KataGoBridgeTPU.isSupported()) KataGoBridgeTPU::class.java else KataGoBridge::class.java
      }

      val needsReinit = bridge.javaClass != targetBridgeClass || m != currentModelName
      if (needsReinit) {
        val label = when (backend) {
          EngineBackend.TPU -> "TPU"
          EngineBackend.GPU -> "GPU (OpenCL)"
          EngineBackend.CPU -> "CPU (Eigen)"
          EngineBackend.AUTO -> "Auto"
        }
        onStatusTextChange("Re-initializing engine on $label...")
        try { bridge.shutdown() } catch (e: Exception) { Log.e("GameFragment", "Failed to shutdown previous bridge", e) }
        val newBridge = createEngineBridge(backend)
        this@GameFragment.bridge = newBridge
        onBridgeChange(newBridge)
        val res = initEngine(m)
        if (res != 0) {
          onStatusTextChange("Engine Init Failed: $res")
          onEngineErrorChange(res)
          onThinkingChange(false)
          return
        }
        currentModelName = m
        currentBackend = backend
      }

      onStatusTextChange("Starting new game...")
      withContext(Dispatchers.IO) {
        bridge.sendGtpCommand("clear_board")
        bridge.sendGtpCommand("set_max_visits $v")

        val komi = if (h > 0) 0.5 else 7.5
        bridge.sendGtpCommand("komi $komi")

        if (h > 0) {
          bridge.sendGtpCommand("fixed_handicap $h")
        }
      }

      boardState = syncBoardState(bridge)

      // If handicap stones were placed, they appear in moveHistory in the engine.
      // However, we want to reflect them in our UI's moveHistory too.
      val newMoveHistory = if (h > 0) {
        // Find which spots have black stones after fixed_handicap
        val hStones = mutableListOf<String>()
        for (r in 0 until boardSize) {
          for (c in 0 until boardSize) {
            if (boardState[r][c] == Stone.BLACK) {
              hStones.add(toGtpCoords(c, r))
            }
          }
        }
        hStones
      } else {
        emptyList<String>()
      }

      previewMove = null
      lastMove = null
      moveHistory = newMoveHistory
      historyIndex = newMoveHistory.size - 1
      consecutivePasses = 0
      finalScoreText = null
      analysis = AnalysisResult()
      currentMode = mode
      handicap = h
      currentVisits = v
      aiAutoPlay = (mode == GameMode.AI_BOTH)
      onThinkingChange(false)

      // KataGo sets turn to White after handicap
      currentTurn = if (h > 0) Stone.WHITE else Stone.BLACK
      onStatusTextChange("Turn.")
      lastMoveText = "No moves yet"

      saveGameState(
        history = newMoveHistory,
        idx = newMoveHistory.size - 1,
        turn = if (h > 0) Stone.WHITE else Stone.BLACK,
        score = null,
        passes = 0,
        backend = backend
      )

      if (currentMode == GameMode.USER_WHITE || (currentMode == GameMode.AI_BOTH) || (h > 0 && currentMode == GameMode.USER_BLACK)) {
        handleAiMove(currentTurn)
      }
    }


    suspend fun restoreSavedGame(
      movesJsonStr: String,
      mode: GameMode,
      h: Int,
      v: Int,
      turnStr: String,
      scoreText: String?,
      passes: Int,
      backend: EngineBackend = currentBackend
    ) {
      onThinkingChange(true)
      onStatusTextChange("Restoring saved game...")

      val moves = try {
        val arr = org.json.JSONArray(movesJsonStr)
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
          list.add(arr.getString(i))
        }
        list
      } catch (e: Exception) {
        emptyList<String>()
      }

      withContext(Dispatchers.IO) {
        bridge.sendGtpCommand("clear_board")
        bridge.sendGtpCommand("set_max_visits $v")
        val komi = if (h > 0) 0.5 else 7.5
        bridge.sendGtpCommand("komi $komi")

        if (h > 0) {
          bridge.sendGtpCommand("fixed_handicap $h")
        }

        val startIndex = if (h > 0) h else 0
        for (i in startIndex until moves.size) {
          val moveStr = moves[i]
          val color = if (h > 0) {
            if ((i - h) % 2 == 0) "white" else "black"
          } else {
            if (i % 2 == 0) "black" else "white"
          }
          bridge.sendGtpCommand("play $color $moveStr")
        }
      }

      boardState = syncBoardState(bridge)
      previewMove = null
      lastMove = if (moves.isNotEmpty()) {
        val lastMoveStr = moves.last()
        if (lastMoveStr.uppercase() != "PASS") fromGtpCoords(lastMoveStr) else null
      } else null

      moveHistory = moves
      historyIndex = moves.size - 1
      consecutivePasses = passes
      finalScoreText = scoreText
      analysis = AnalysisResult()
      currentMode = mode
      handicap = h
      currentVisits = v
      aiAutoPlay = false
      currentBackend = backend
      currentTurn = try { Stone.valueOf(turnStr) } catch (e: Exception) { Stone.BLACK }

      onThinkingChange(false)
      onStatusTextChange("Turn.")
      lastMoveText = if (moves.isNotEmpty()) {
        val lastMoveStr = moves.last()
        val turnColor = if ((moves.size - 1) % 2 == 0) "Black" else "White"
        if (lastMoveStr.uppercase() == "PASS") "$turnColor passed." else "$turnColor played $lastMoveStr"
      } else "No moves yet"
    }

    LaunchedEffect(isThinking, engineError) {
      if (isThinking && !isEngineInitialized && engineError == null) {
        val tuningMsg = when {
          bridge is KataGoBridgeTPU -> requireContext().getString(R.string.tuning_tpu)
          bridge is KataGoBridge -> requireContext().getString(R.string.tuning_gpu)
          else -> requireContext().getString(R.string.initializing_engine)
        }
        onStatusTextChange(tuningMsg)

        val result = initEngine(currentModelName)
        onThinkingChange(false)
        if (result == 0) {
          onEngineInitializedChange(true)
          onStatusTextChange("Engine ready.")
          if (isGameInProgress) {
            val savedMoves = sharedPrefs.getString("saved_moves", null)
            if (savedMoves != null) {
              val savedModeStr = sharedPrefs.getString("current_mode", GameMode.USER_BLACK.name) ?: GameMode.USER_BLACK.name
              val savedMode = try { GameMode.valueOf(savedModeStr) } catch (e: Exception) { GameMode.USER_BLACK }
              val savedHandicap = sharedPrefs.getInt("handicap", 0)
              val savedVisits = sharedPrefs.getInt("current_visits", 500)
              val savedTurn = sharedPrefs.getString("current_turn", Stone.BLACK.name) ?: Stone.BLACK.name
              val savedScoreText = sharedPrefs.getString("final_score_text", null)
              val savedPasses = sharedPrefs.getInt("consecutive_passes", 0)
              val savedBackendStr = sharedPrefs.getString("selected_backend", EngineBackend.AUTO.name) ?: EngineBackend.AUTO.name
              val savedBackend = try { EngineBackend.valueOf(savedBackendStr) } catch (e: Exception) { EngineBackend.AUTO }
              restoreSavedGame(savedMoves, savedMode, savedHandicap, savedVisits, savedTurn, savedScoreText, savedPasses, savedBackend)
            } else {
              showSettings = true
            }
          } else {
            showSettings = true
          }
        } else {
          onStatusTextChange("Engine Init Failed: $result")
          onEngineErrorChange(result)
        }
      }
    }


    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            Column {
              Text(
                text = "围棋 碁 GO!",
                style = MaterialTheme.typography.h6
              )
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Text(
                  text = backendBadgeText,
                  style = MaterialTheme.typography.caption,
                  color = MaterialTheme.colors.onPrimary.copy(alpha = 0.85f),
                  fontWeight = FontWeight.Bold
                )
                if (showEngineLatency && lastEngineLatencyMs != null) {
                  Text(
                    text = "• ${formatLatency(lastEngineLatencyMs)}",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.secondary,
                    fontWeight = FontWeight.ExtraBold
                  )
                }
              }
            }
          },
          backgroundColor = MaterialTheme.colors.primary,
          contentColor = MaterialTheme.colors.onPrimary,
          elevation = 4.dp,
          actions = {
            TextButton(
              onClick = { showAnalysis = !showAnalysis },
              modifier = Modifier.padding(end = 8.dp)
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Analytics,
                  tint = if (showAnalysis) MaterialTheme.colors.secondary else MaterialTheme.colors.onPrimary,
                  contentDescription = "Toggle AI Analysis",
                  modifier = Modifier.size(36.dp) // Enlarged by 50% (default icon size is 24dp)
                )
                Text(
                  text = context.getString(R.string.btn_analysis),
                  color = if (showAnalysis) MaterialTheme.colors.secondary else MaterialTheme.colors.onPrimary,
                  style = MaterialTheme.typography.button,
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }
        )
      }
    ) { paddingValues ->
          Column(
            modifier = Modifier
              .fillMaxSize()
              .padding(paddingValues),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            // Fixed-height Header
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .padding(horizontal = 10.dp, vertical = 4.dp),
              contentAlignment = Alignment.Center
            ) {
              Card(
                modifier = Modifier.fillMaxSize(),
                elevation = 4.dp,
                shape = RoundedCornerShape(16.dp)
              ) {
                Column(
                  modifier = Modifier.fillMaxSize(),
                  verticalArrangement = Arrangement.Center,
                  horizontalAlignment = Alignment.CenterHorizontally
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 12.dp).weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                  ) {
                    if (isEngineInitialized && finalScoreText == null) {
                      Box(
                        modifier = Modifier
                          .size(50.dp)
                          .clip(CircleShape)
                          .background(if (currentTurn == Stone.BLACK) Color.Black else Color.White)
                          .border(
                            width = if (currentTurn == Stone.WHITE) 2.dp else 0.dp,
                            color = if (currentTurn == Stone.WHITE) Color.Gray else Color.Transparent,
                            shape = CircleShape
                          )
                      )
                    }

                    if (finalScoreText != null) {
                      if (isEngineInitialized) Spacer(Modifier.width(16.dp))
                      Text(
                        text = "Game Over",
                        style = MaterialTheme.typography.h4,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colors.onSurface
                      )
                    }

                    if (!showAnalysis && finalScoreText == null && isEngineInitialized) {
                      Spacer(Modifier.width(16.dp))
                      Text(
                        text = lastMoveText,
                        style = MaterialTheme.typography.subtitle1,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.onSurface
                      )
                    }

                    if (showAnalysis && finalScoreText == null) {
                      val blackWinrate = analysis.winrate
                      val blackScoreLead = analysis.scoreLead

                      val winratePercent = (blackWinrate * 100).toInt()
                      val scoreLeadFormatted = String.format("%.1f", blackScoreLead)
                      val leadSign = if (blackScoreLead >= 0) "+" else ""

                      Spacer(Modifier.width(12.dp))
                      Divider(modifier = Modifier.height(20.dp).width(1.dp), color = Color.Gray.copy(alpha = 0.5f))
                      Spacer(Modifier.width(12.dp))

                      val latencyText = if (showEngineLatency && lastEngineLatencyMs != null) " (${formatLatency(lastEngineLatencyMs)})" else ""
                      Text(
                        text = "Black $winratePercent% $leadSign$scoreLeadFormatted Pts$latencyText",
                        style = MaterialTheme.typography.h6,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colors.primary
                      )
                    }
                  }

                  if (isThinking) {
                    LinearProgressIndicator(
                      modifier = Modifier.fillMaxWidth().height(4.dp),
                      color = MaterialTheme.colors.secondary
                    )
                  } else {
                    Spacer(Modifier.height(4.dp))
                  }
                }
              }
            }

            // Board Container
            Box(
              modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
              contentAlignment = Alignment.Center
            ) {
              Box(
                modifier = Modifier
                  .aspectRatio(1f)
                  .fillMaxWidth()
                  .background(Color(0xFFFFCC66))
              ) {
                val boardMargin = 0.03f

                Canvas(
                  modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isEngineInitialized, isThinking) {
                      if (!isEngineInitialized || isThinking) return@pointerInput
                        detectTapGestures { offset ->
                          val marginPx = size.width * boardMargin
                        val gridSizePx = size.width - 2 * marginPx
                        val stepPx = gridSizePx / (boardSize - 1)

                        val x = ((offset.x - marginPx) / stepPx).roundToInt().coerceIn(0, 18)
                        val y = ((offset.y - marginPx) / stepPx).roundToInt().coerceIn(0, 18)

                        if (boardState[y][x] == Stone.EMPTY) {
                          previewMove = x to y
                        }
                        }
                    }
                ) {
                  val marginPx = size.width * boardMargin
                  val gridSizePx = size.width - 2 * marginPx
                  val stepPx = gridSizePx / (boardSize - 1)
                  val stoneRadius = (stepPx / 2) * 0.98f

                  // Draw Grid Lines
                  val lineStart = marginPx
                  val lineEnd = marginPx + (boardSize - 1) * stepPx

                  for (i in 0 until boardSize) {
                    val pos = marginPx + i * stepPx
                    drawLine(
                      color = Color.Black.copy(alpha = 0.8f),
                      start = Offset(lineStart, pos),
                      end = Offset(lineEnd, pos),
                      strokeWidth = 1.dp.toPx()
                    )
                    drawLine(
                      color = Color.Black.copy(alpha = 0.8f),
                      start = Offset(pos, lineStart),
                      end = Offset(pos, lineEnd),
                      strokeWidth = 1.dp.toPx()
                    )
                  }

                  // Draw Hoshi
                  val hoshiIndices = listOf(3, 9, 15)
                  for (rowIdx in hoshiIndices) {
                    for (colIdx in hoshiIndices) {
                      drawCircle(
                        color = Color.Black,
                        radius = 3.dp.toPx(),
                        center = Offset(marginPx + colIdx * stepPx, marginPx + rowIdx * stepPx)
                      )
                    }
                  }

                  if (showAnalysis || finalScoreText != null) {
                    // Draw Ownership Dots/Squares (Territory Analysis)
                    for (row in 0 until boardSize) {
                      for (col in 0 until boardSize) {
                        val score = analysis.ownership[row * boardSize + col]
                        if (abs(score) > 0.1) {
                          val centerX = marginPx + col * stepPx
                          val centerY = marginPx + row * stepPx
                          
                          if (finalScoreText != null) {
                            // Draw final territory as small squares
                            val squareSize = stepPx * 0.45f
                            drawRect(
                              color = if (score > 0) Color.Black.copy(alpha = 0.5f) 
                                      else Color.White.copy(alpha = 0.6f),
                              topLeft = Offset(centerX - squareSize/2, centerY - squareSize/2),
                              size = Size(squareSize, squareSize)
                            )
                          } else {
                            // Normal analysis dots
                            drawCircle(
                              color = if (score > 0) Color.Black.copy(alpha = (score * 0.4).toFloat()) 
                              else Color.White.copy(alpha = (-score * 0.4).toFloat()),
                                radius = 3.dp.toPx(),
                              center = Offset(centerX, centerY)
                            )
                          }
                        }
                      }
                    }

                    // Draw Candidate Moves (Suggestions)
                    val bestMove = analysis.candidates.maxByOrNull { it.visits }

                    analysis.candidates.forEach { candidate ->
                      val centerX = marginPx + candidate.x * stepPx
                    val centerY = marginPx + candidate.y * stepPx
                    val candidateRadius = stoneRadius * 0.75f // 75% of stone size

                    if (candidate == bestMove) {
                      // Highlight best move with a dark red ring
                      drawCircle(
                        color = Color(0xFFB71C1C), // Dark Red
                        radius = stoneRadius * 0.9f,
                        center = Offset(centerX, centerY),
                        style = Stroke(width = 3.dp.toPx())
                      )
                    } else {
                      // Other candidates as solid light red dots
                      drawCircle(
                        color = Color(0xFFE57373), // Light Red
                        radius = candidateRadius,
                        center = Offset(centerX, centerY)
                      )
                    }

                    // Winrate text
                    val winrateText = "${(candidate.winrate * 100).toInt()}%"
                    val textPaint = android.graphics.Paint().apply {
                      color = if (candidate == bestMove) android.graphics.Color.parseColor("#B71C1C")
                      else android.graphics.Color.WHITE
                        textSize = 8.dp.toPx()
                      textAlign = android.graphics.Paint.Align.CENTER
                      isAntiAlias = true
                      typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                    drawIntoCanvas { canvas ->
                      canvas.nativeCanvas.drawText(
                        winrateText,
                        centerX,
                        centerY + 3.dp.toPx(),
                        textPaint
                      )
                    }
                    }
                  }

                  // Draw Stones
                  for (row in 0 until boardSize) {
                    for (col in 0 until boardSize) {
                      val stone = boardState[row][col]
                      if (stone != Stone.EMPTY) {
                        val centerX = marginPx + col * stepPx
                        val centerY = marginPx + row * stepPx

                        if (stone == Stone.BLACK) {
                          drawCircle(
                            brush = Brush.radialGradient(
                              colors = listOf(Color(0xFF333333), Color.Black),
                              center = Offset(centerX - stoneRadius * 0.3f, centerY - stoneRadius * 0.3f),
                              radius = stoneRadius * 1.5f
                            ),
                            radius = stoneRadius,
                            center = Offset(centerX, centerY)
                          )
                        } else {
                          drawCircle(
                            brush = Brush.radialGradient(
                              colors = listOf(Color.White, Color(0xFFDDDDDD)),
                              center = Offset(centerX - stoneRadius * 0.3f, centerY - stoneRadius * 0.3f),
                              radius = stoneRadius * 1.5f
                            ),
                            radius = stoneRadius,
                            center = Offset(centerX, centerY)
                          )
                          drawCircle(
                            color = Color.Black.copy(alpha = 0.1f),
                            radius = stoneRadius,
                            center = Offset(centerX, centerY),
                            style = Stroke(width = 0.5.dp.toPx())
                          )
                        }

                        // Mark Last Move
                        if (lastMove?.first == col && lastMove?.second == row) {
                          drawCircle(
                            color = if (stone == Stone.BLACK) Color.White else Color.Black,
                            radius = 4.dp.toPx(),
                            center = Offset(centerX, centerY)
                          )
                        }
                      }
                    }
                                 // Draw Preview Stone (Ghost Stone)
                  previewMove?.let { (px, py) ->
                    val centerX = marginPx + px * stepPx
                    val centerY = marginPx + py * stepPx
                    
                    if (currentTurn == Stone.BLACK) {
                      drawCircle(
                        brush = Brush.radialGradient(
                          colors = listOf(Color(0xFF333333).copy(alpha = 0.6f), Color.Black.copy(alpha = 0.6f)),
                          center = Offset(centerX - stoneRadius * 0.3f, centerY - stoneRadius * 0.3f),
                          radius = stoneRadius * 1.5f
                        ),
                        radius = stoneRadius,
                        center = Offset(centerX, centerY)
                      )
                      drawCircle(
                        color = Color.Black.copy(alpha = 0.15f),
                        radius = stoneRadius,
                        center = Offset(centerX, centerY),
                        style = Stroke(width = 0.5.dp.toPx())
                      )
                      
                      // Draw a solid white triangle in the center of the black preview stone
                      val path = androidx.compose.ui.graphics.Path().apply {
                        val size = 6.dp.toPx()
                        moveTo(centerX, centerY - size)
                        lineTo(centerX - size, centerY + size * 0.7f)
                        lineTo(centerX + size, centerY + size * 0.7f)
                        close()
                      }
                      drawPath(path = path, color = Color.White.copy(alpha = 0.8f))
                    } else {
                      drawCircle(
                        brush = Brush.radialGradient(
                          colors = listOf(Color.White.copy(alpha = 0.65f), Color(0xFFDDDDDD).copy(alpha = 0.65f)),
                          center = Offset(centerX - stoneRadius * 0.3f, centerY - stoneRadius * 0.3f),
                          radius = stoneRadius * 1.5f
                        ),
                        radius = stoneRadius,
                        center = Offset(centerX, centerY)
                      )
                      drawCircle(
                        color = Color.Black.copy(alpha = 0.15f),
                        radius = stoneRadius,
                        center = Offset(centerX, centerY),
                        style = Stroke(width = 0.5.dp.toPx())
                      )
                      
                      // Draw a solid black triangle in the center of the white preview stone
                      val path = androidx.compose.ui.graphics.Path().apply {
                        val size = 6.dp.toPx()
                        moveTo(centerX, centerY - size)
                        lineTo(centerX - size, centerY + size * 0.7f)
                        lineTo(centerX + size, centerY + size * 0.7f)
                        close()
                      }
                      drawPath(path = path, color = Color.Black.copy(alpha = 0.8f))
                    }
                  }
                }
              }
              }
            }

            // --- Top Row: Place / Pass / Auto Button ---
            Row(
              modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
              horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
              verticalAlignment = Alignment.CenterVertically
            ) {
              if (currentMode == GameMode.AI_BOTH) {
                // Autoplay Toggle (Auto / Pause)
                Button(
                  onClick = {
                    val willPlay = !aiAutoPlay
                    aiAutoPlay = willPlay
                    if (willPlay && !isThinking && finalScoreText == null) {
                      scope.launch { handleAiMove(currentTurn) }
                    }
                  },
                  modifier = Modifier.height(56.dp).width(120.dp),
                  shape = RoundedCornerShape(28.dp),
                  colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (aiAutoPlay) Color(0xFFE53935) else Color(0xFF43A047)
                  ),
                  elevation = ButtonDefaults.elevation(6.dp)
                ) {
                  Text(
                    text = if (aiAutoPlay) context.getString(R.string.btn_pause) else context.getString(R.string.btn_auto),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                  )
                }

                // Step Button (Execute single AI move when paused)
                Button(
                  onClick = {
                    if (!isThinking && finalScoreText == null) {
                      scope.launch { handleAiMove(currentTurn) }
                    }
                  },
                  enabled = !aiAutoPlay && !isThinking && finalScoreText == null,
                  modifier = Modifier.height(56.dp).width(110.dp),
                  shape = RoundedCornerShape(28.dp),
                  colors = ButtonDefaults.buttonColors(
                    backgroundColor = Color.LightGray.copy(alpha = 0.5f)
                  ),
                  elevation = ButtonDefaults.elevation(4.dp)
                ) {
                  Text(context.getString(R.string.btn_step), fontWeight = FontWeight.Bold)
                }
              } else {
                // Pass Button
                Button(
                  onClick = {
                    scope.launch {
                        val color = if (currentTurn == Stone.BLACK) "black" else "white"
                        bridge.sendGtpCommand("play $color pass")
                        playPassSound()
                        val newPasses = consecutivePasses + 1
                        consecutivePasses = newPasses
                        val colorStr = if (currentTurn == Stone.BLACK) "Black" else "White"
                        lastMoveText = "$colorStr passed."
                        val nextTurn = if (currentTurn == Stone.BLACK) Stone.WHITE else Stone.BLACK
                        currentTurn = nextTurn
                        
                        checkGameEnd()
                        saveGameState(turn = nextTurn, passes = newPasses)
 
                        if (finalScoreText == null) {
                            if (currentMode == GameMode.USER_BLACK && currentTurn == Stone.WHITE) {
                                handleAiMove(Stone.WHITE)
                            } else if (currentMode == GameMode.USER_WHITE && currentTurn == Stone.BLACK) {
                                handleAiMove(Stone.BLACK)
                            }
                        }
                    }
                  },
                  enabled = !isThinking && finalScoreText == null && (
                    currentMode == GameMode.USER_BOTH ||
                    (currentMode == GameMode.USER_BLACK && currentTurn == Stone.BLACK) ||
                    (currentMode == GameMode.USER_WHITE && currentTurn == Stone.WHITE)
                  ),
                  modifier = Modifier.height(56.dp).width(80.dp),
                  shape = RoundedCornerShape(28.dp),
                  colors = ButtonDefaults.buttonColors(
                    backgroundColor = Color.LightGray.copy(alpha = 0.5f)
                  )
                ) {
                  Text(context.getString(R.string.btn_pass), fontWeight = FontWeight.Bold)
                }
 
                // Place Button (Circular)
                Button(
                  onClick = {
                    val move = previewMove ?: return@Button
                    val (x, y) = move
                    val turnColor = currentTurn
                    scope.launch {
                      playMove(x, y, turnColor, bridge) { success, moveStr ->
                        if (success) {
                          boardState = syncBoardState(bridge)
                          val colorStr = if (turnColor == Stone.BLACK) "Black" else "White"
                          lastMoveText = "$colorStr played $moveStr"
                          val newHistory = moveHistory.take(historyIndex + 1) + moveStr
                          val newIndex = historyIndex + 1
                          moveHistory = newHistory
                          historyIndex = newIndex
                          consecutivePasses = 0
                          previewMove = null
                          lastMove = x to y
                          playMoveSound()
                          val nextTurn = if (turnColor == Stone.BLACK) Stone.WHITE else Stone.BLACK
                          currentTurn = nextTurn

                          saveGameState(history = newHistory, idx = newIndex, turn = nextTurn, score = finalScoreText, passes = 0)

                          if (currentMode == GameMode.USER_BLACK && currentTurn == Stone.WHITE) {
                            scope.launch { handleAiMove(Stone.WHITE) }
                          } else if (currentMode == GameMode.USER_WHITE && currentTurn == Stone.BLACK) {
                            scope.launch { handleAiMove(Stone.BLACK) }
                          } else {
                            onStatusTextChange("Turn.")
                          }
                        } else {
                          onStatusTextChange("Illegal move at ${toGtpCoords(x, y)}")
                          previewMove = null
                        }
                      }
                    }
                  },
                  enabled = previewMove != null && !isThinking && finalScoreText == null && (
                    currentMode == GameMode.USER_BOTH ||
                    (currentMode == GameMode.USER_BLACK && currentTurn == Stone.BLACK) ||
                    (currentMode == GameMode.USER_WHITE && currentTurn == Stone.WHITE)
                  ),
                  modifier = Modifier.size(80.dp),
                  shape = CircleShape,
                  elevation = ButtonDefaults.elevation(defaultElevation = 6.dp, pressedElevation = 12.dp)
                ) {
                  Text(context.getString(R.string.btn_place), fontWeight = FontWeight.Bold)
                }
              }
            }
 
            // --- Bottom Row: Undo - New Game - Redo ---
            Row(
              modifier = Modifier.padding(16.dp).fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
              verticalAlignment = Alignment.CenterVertically
            ) {
              // Undo Button
              IconButton(
                onClick = {
                  if (historyIndex >= 0) {
                    scope.launch {
                      bridge.sendGtpCommand("undo")
                      val newIndex = historyIndex - 1
                      historyIndex = newIndex
                      boardState = syncBoardState(bridge)
                      val nextTurn = if (currentTurn == Stone.BLACK) Stone.WHITE else Stone.BLACK
                      currentTurn = nextTurn
                      lastMove = if (newIndex >= 0) fromGtpCoords(moveHistory[newIndex]) else null
                      lastMoveText = if (newIndex >= 0) "Undone. Last move: ${moveHistory[newIndex]}" else "Undone to start"
                      previewMove = null
                      saveGameState(idx = newIndex, turn = nextTurn)
                    }
                  }
                },
                enabled = historyIndex >= 0 && !isThinking,
                modifier = Modifier.background(MaterialTheme.colors.surface, CircleShape).size(48.dp)
              ) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = MaterialTheme.colors.primary)
              }
 
              // New Game Button
              Button(
                onClick = { showSettings = true },
                modifier = Modifier.height(56.dp).width(140.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                  backgroundColor = MaterialTheme.colors.secondary,
                  contentColor = MaterialTheme.colors.onSecondary
                ),
                elevation = ButtonDefaults.elevation(4.dp)
              ) {
                Text(context.getString(R.string.btn_new_game), fontWeight = FontWeight.Bold)
              }

              // Redo Button
              IconButton(
                onClick = {
                  if (historyIndex < moveHistory.size - 1) {
                    scope.launch {
                      val newIndex = historyIndex + 1
                      historyIndex = newIndex
                      val moveStr = moveHistory[newIndex]
                      val color = if (currentTurn == Stone.BLACK) "black" else "white"
                      bridge.sendGtpCommand("play $color $moveStr")
                      boardState = syncBoardState(bridge)
                      lastMove = fromGtpCoords(moveStr)
                      lastMoveText = "Redone: $moveStr"
                      val nextTurn = if (currentTurn == Stone.BLACK) Stone.WHITE else Stone.BLACK
                      currentTurn = nextTurn
                      previewMove = null
                      saveGameState(idx = newIndex, turn = nextTurn)
                    }
                  }
                },
                enabled = historyIndex < moveHistory.size - 1 && !isThinking,
                modifier = Modifier.background(MaterialTheme.colors.surface, CircleShape).size(48.dp)
              ) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", tint = MaterialTheme.colors.primary)
              }
            }

            Spacer(modifier = Modifier.height(32.dp))
          }

          if (showSettings) {
            Dialog(
              onDismissRequest = { if (isEngineInitialized) showSettings = false }
            ) {
              Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colors.surface,
                elevation = 12.dp,
                modifier = Modifier
                  .fillMaxWidth()
                  .fillMaxHeight(0.85f)
              ) {
                Box(modifier = Modifier.fillMaxSize()) {
                  Column(
                    modifier = Modifier.fillMaxSize()
                  ) {
                    // --- Title ---
                    Text(
                      context.getString(R.string.title_new_game),
                      style = MaterialTheme.typography.h5,
                      fontWeight = FontWeight.Bold,
                      modifier = Modifier.padding(top = 24.dp, start = 24.dp, end = 24.dp, bottom = 16.dp)
                    )

                    // --- Scrollable Settings ---
                    Column(
                      modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                    ) {
                      if (!isPremiumUnlocked) {
                        Surface(
                          modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 20.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                              billingManager.launchPurchaseFlow(requireActivity())
                            },
                          color = MaterialTheme.colors.primary.copy(alpha = 0.08f),
                          border = BorderStroke(1.dp, MaterialTheme.colors.primary.copy(alpha = 0.3f))
                        ) {
                          Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                          ) {
                            Icon(
                              imageVector = Icons.Default.Stars,
                              contentDescription = "Unlock Premium",
                              tint = Color(0xFFFFD700), // Gold
                              modifier = Modifier.size(36.dp)
                            )
                            Column(modifier = Modifier.padding(start = 16.dp)) {
                              Text(
                                text = context.getString(R.string.iap_promo_title),
                                style = MaterialTheme.typography.subtitle1,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colors.primary
                              )
                              Spacer(Modifier.height(4.dp))
                              Text(
                                text = context.getString(R.string.iap_promo_desc),
                                style = MaterialTheme.typography.body2,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f)
                              )
                            }
                          }
                        }
                      }

                      // --- Hardware Acceleration Selection ---
                      Text(
                        context.getString(R.string.section_hardware_backend),
                        style = MaterialTheme.typography.overline,
                        color = MaterialTheme.colors.primary,
                        fontWeight = FontWeight.Bold
                      )
                      Spacer(Modifier.height(8.dp))

                      val backendsList = listOf(
                        EngineBackend.AUTO to (context.getString(R.string.backend_auto_title) to context.getString(R.string.backend_auto_desc)),
                        EngineBackend.TPU to (context.getString(R.string.backend_tpu_title) to context.getString(R.string.backend_tpu_desc)),
                        EngineBackend.GPU to (context.getString(R.string.backend_gpu_title) to context.getString(R.string.backend_gpu_desc)),
                        EngineBackend.CPU to (context.getString(R.string.backend_cpu_title) to context.getString(R.string.backend_cpu_desc))
                      )

                      backendsList.forEach { (b, info) ->
                        val (title, desc) = info
                        val isSelected = currentBackend == b
                        val isSupported = when (b) {
                          EngineBackend.TPU -> WeiqiApplication.isPixelTpuSupported() && KataGoBridgeTPU.isSupported()
                          else -> true
                        }

                        Surface(
                          modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = isSupported) {
                              currentBackend = b
                            },
                          color = if (isSelected) MaterialTheme.colors.primary.copy(alpha = 0.08f) else Color.Transparent,
                          border = BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colors.primary else Color.LightGray.copy(alpha = 0.5f)
                          )
                        ) {
                          Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                          ) {
                            Icon(
                              imageVector = Icons.Default.Memory,
                              contentDescription = null,
                              tint = if (isSelected) MaterialTheme.colors.primary else if (!isSupported) Color.LightGray else Color.Gray,
                              modifier = Modifier.size(28.dp)
                            )
                            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                              Text(
                                text = title,
                                style = MaterialTheme.typography.subtitle2,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colors.primary else if (!isSupported) Color.LightGray else MaterialTheme.colors.onSurface
                              )
                              Text(
                                text = if (!isSupported && b == EngineBackend.TPU) "Google Pixel devices only" else desc,
                                style = MaterialTheme.typography.caption,
                                color = if (!isSupported) Color.LightGray else Color.Gray
                              )
                            }
                            RadioButton(
                              selected = isSelected,
                              onClick = if (isSupported) { { currentBackend = b } } else null,
                              enabled = isSupported,
                              colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colors.primary)
                            )
                          }
                        }
                      }

                      Spacer(modifier = Modifier.height(24.dp))




                      // --- AI Strength Selection ---
                      Text(
                        context.getString(R.string.section_ai_strength),
                        style = MaterialTheme.typography.overline,
                        color = MaterialTheme.colors.primary,
                        fontWeight = FontWeight.Bold
                      )
                      Spacer(Modifier.height(8.dp))
                      
                      val levels = listOf(
                        context.getString(R.string.strength_easy) to 100,
                        context.getString(R.string.strength_amateur) to 500,
                        context.getString(R.string.strength_advanced) to 1000,
                        context.getString(R.string.strength_pro) to 2500
                      )
                      
                      androidx.compose.foundation.lazy.LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                      ) {
                        items(levels.size) { index ->
                          val (label, v) = levels[index]
                          val isSelected = currentVisits == v
                          val isPremiumItem = v >= 1000
                          Surface(
                            modifier = Modifier
                              .size(width = 100.dp, height = 56.dp)
                              .clip(RoundedCornerShape(16.dp))
                              .pointerInput(v, isPremiumUnlocked) {
                                detectTapGestures {
                                  if (isPremiumItem && !isPremiumUnlocked) {
                                    billingManager.launchPurchaseFlow(requireActivity())
                                  } else {
                                    currentVisits = v
                                  }
                                }
                              },
                            color = if (isSelected) MaterialTheme.colors.primary else MaterialTheme.colors.surface,
                            elevation = if (isSelected) 6.dp else 0.dp,
                            border = if (isSelected) null else BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.6f))
                          ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                              Column(
                                  modifier = Modifier.fillMaxSize(),
                                  horizontalAlignment = Alignment.CenterHorizontally,
                                  verticalArrangement = Arrangement.Center
                              ) {
                                Text(
                                  text = label,
                                  color = if (isSelected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
                                  style = MaterialTheme.typography.button,
                                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                                Text(
                                  text = context.getString(R.string.strength_visits, v),
                                  color = if (isSelected) MaterialTheme.colors.onPrimary.copy(alpha = 0.8f) else Color.Gray,
                                  style = MaterialTheme.typography.caption
                                )
                              }
                              if (isPremiumItem && !isPremiumUnlocked) {
                                Icon(
                                  imageVector = Icons.Default.Lock,
                                  contentDescription = "Premium Locked",
                                  tint = if (isSelected) MaterialTheme.colors.onPrimary.copy(alpha = 0.6f) else Color.Gray,
                                  modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 4.dp, end = 4.dp)
                                    .size(14.dp)
                                )
                              }
                            }
                          }
                        }
                      }

                      Spacer(modifier = Modifier.height(24.dp))

                      // --- Handicap Selection ---
                      Text(
                        context.getString(R.string.section_handicap),
                        style = MaterialTheme.typography.overline,
                        color = MaterialTheme.colors.primary,
                        fontWeight = FontWeight.Bold
                      )
                      Spacer(Modifier.height(8.dp))
                      
                      androidx.compose.foundation.lazy.LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                      ) {
                        items(10) { h ->
                          val isSelected = handicap == h
                          Surface(
                            modifier = Modifier
                              .size(width = 72.dp, height = 56.dp)
                              .clip(RoundedCornerShape(16.dp))
                              .pointerInput(h) {
                                detectTapGestures { handicap = h }
                              },
                            color = if (isSelected) MaterialTheme.colors.primary else MaterialTheme.colors.surface,
                            elevation = if (isSelected) 6.dp else 0.dp,
                            border = if (isSelected) null else BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.6f))
                          ) {
                            Box(contentAlignment = Alignment.Center) {
                              Text(
                                text = if (h == 0) context.getString(R.string.handicap_none) else h.toString(),
                                color = if (isSelected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
                                style = MaterialTheme.typography.button,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                              )
                            }
                          }
                        }
                      }

                      Spacer(modifier = Modifier.height(24.dp))

                      // --- Mode Selection ---
                      Text(
                        "PLAY AS",
                        style = MaterialTheme.typography.overline,
                        color = MaterialTheme.colors.primary,
                        fontWeight = FontWeight.Bold
                      )
                      Spacer(Modifier.height(8.dp))
                      
                      GameMode.values().forEach { mode ->
                        val isSelected = currentMode == mode
                        Surface(
                          modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .pointerInput(Unit) {
                              detectTapGestures { currentMode = mode }
                            },
                          color = if (isSelected) MaterialTheme.colors.primary.copy(alpha = 0.08f) else Color.Transparent,
                          border = BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colors.primary else Color.Transparent
                          )
                        ) {
                          Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                          ) {
                             val (label, desc, icon) = when(mode) {
                              GameMode.USER_BLACK -> Triple(
                                context.getString(R.string.mode_user_black_title),
                                context.getString(R.string.mode_user_black_desc),
                                Icons.Default.Person
                              )
                              GameMode.USER_WHITE -> Triple(
                                context.getString(R.string.mode_user_white_title),
                                context.getString(R.string.mode_user_white_desc),
                                Icons.Default.Person
                              )
                              GameMode.USER_BOTH -> Triple(
                                context.getString(R.string.mode_user_both_title),
                                context.getString(R.string.mode_user_both_desc),
                                Icons.Default.Groups
                              )
                              GameMode.AI_BOTH -> Triple(
                                context.getString(R.string.mode_ai_both_title),
                                context.getString(R.string.mode_ai_both_desc),
                                Icons.Default.SmartToy
                              )
                            }
                            
                            Box(
                              modifier = Modifier
                                .size(40.dp)
                                .background(
                                  if (isSelected) MaterialTheme.colors.primary.copy(alpha = 0.1f) else Color.LightGray.copy(alpha = 0.2f),
                                  CircleShape
                                ),
                              contentAlignment = Alignment.Center
                            ) {
                              Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colors.primary else Color.Gray,
                                modifier = Modifier.size(24.dp)
                              )
                            }
                            
                            Column(modifier = Modifier.padding(start = 16.dp)) {
                              Text(
                                text = label,
                                style = MaterialTheme.typography.subtitle1,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                              )
                              Text(text = desc, style = MaterialTheme.typography.caption)
                            }
                            
                            Spacer(Modifier.weight(1f))
                            
                            RadioButton(
                              selected = isSelected,
                              onClick = { currentMode = mode },
                              colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colors.primary)
                            )
                          }
                        }
                      }

                      // --- Diagnostics & Hardware Status ---

                      Spacer(Modifier.height(24.dp))
                      Text(
                        context.getString(R.string.section_diagnostics),
                        style = MaterialTheme.typography.overline,
                        color = MaterialTheme.colors.primary,
                        fontWeight = FontWeight.Bold
                      )
                      Spacer(Modifier.height(8.dp))

                      Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colors.primary.copy(alpha = 0.06f),
                        border = BorderStroke(1.dp, MaterialTheme.colors.primary.copy(alpha = 0.25f))
                      ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                          Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                              imageVector = Icons.Default.Memory,
                              contentDescription = null,
                              tint = MaterialTheme.colors.primary,
                              modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                              text = "Active Backend:",
                              style = MaterialTheme.typography.caption,
                              fontWeight = FontWeight.Bold,
                              color = Color.Gray
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                              text = backendBadgeText,
                              style = MaterialTheme.typography.caption,
                              fontWeight = FontWeight.ExtraBold,
                              color = MaterialTheme.colors.primary
                            )
                          }
                          Spacer(Modifier.height(6.dp))
                          Text(
                            text = backendDetailText,
                            style = MaterialTheme.typography.body2,
                            fontWeight = FontWeight.Medium
                          )
                          if (lastEngineLatencyMs != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                              text = "Latest Call Latency: ${formatLatency(lastEngineLatencyMs)}",
                              style = MaterialTheme.typography.caption,
                              color = Color.Gray
                            )
                          }
                        }
                      }

                      Spacer(Modifier.height(12.dp))

                      Surface(
                        modifier = Modifier
                          .fillMaxWidth()
                          .clip(RoundedCornerShape(12.dp))
                          .clickable {
                            val newValue = !showEngineLatency
                            showEngineLatency = newValue
                            sharedPrefs.edit().putBoolean("show_engine_latency", newValue).apply()
                          },
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.5f))
                      ) {
                        Row(
                          modifier = Modifier.padding(16.dp),
                          verticalAlignment = Alignment.CenterVertically
                        ) {
                          Column(modifier = Modifier.weight(1f)) {
                            Text(
                              text = context.getString(R.string.setting_show_latency),
                              style = MaterialTheme.typography.subtitle2,
                              fontWeight = FontWeight.Bold
                            )
                            Text(
                              text = context.getString(R.string.setting_show_latency_desc),
                              style = MaterialTheme.typography.caption,
                              color = Color.Gray
                            )
                          }
                          Switch(
                            checked = showEngineLatency,
                            onCheckedChange = {
                              showEngineLatency = it
                              sharedPrefs.edit().putBoolean("show_engine_latency", it).apply()
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colors.primary)
                          )
                        }
                      }

                      Spacer(Modifier.height(100.dp)) // Extra space for fixed footer
                    }
                  }

                  // --- Fixed Footer Buttons ---
                  Surface(
                    modifier = Modifier
                      .align(Alignment.BottomCenter)
                      .fillMaxWidth(),
                    color = MaterialTheme.colors.surface,
                    elevation = 8.dp
                  ) {
                    Row(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                      horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      TextButton(
                        onClick = { showSettings = false },
                        modifier = Modifier.height(48.dp)
                      ) {
                        Text(context.getString(R.string.btn_cancel), color = Color.Gray, fontWeight = FontWeight.Bold)
                      }
                       Button(
                        onClick = {
                          showSettings = false
                          sharedPrefs.edit()
                            .putString("current_mode", currentMode.name)
                            .putInt("handicap", handicap)
                            .putString("current_model_name", currentModelName)
                            .putInt("current_visits", currentVisits)
                            .putString("selected_backend", currentBackend.name)
                            .apply()
                          scope.launch {
                            startNewGame(currentMode, handicap, currentModelName, currentVisits, currentBackend)
                          }
                        },
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                          .height(48.dp)
                          .padding(horizontal = 8.dp),
                        elevation = ButtonDefaults.elevation(defaultElevation = 4.dp)
                      ) {
                        Text(context.getString(R.string.btn_start), fontWeight = FontWeight.Bold)
                      }
                    }
                  }
                }
              }
            }
          }

          if (finalScoreText != null) {
            AlertDialog(
                onDismissRequest = { /* Don't dismiss by clicking outside */ },
                shape = RoundedCornerShape(28.dp),
                backgroundColor = MaterialTheme.colors.surface,
                title = {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                      imageVector = Icons.Default.Stars,
                      contentDescription = null,
                      tint = Color(0xFFFFD700), // Gold
                      modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                      text = "Game Over",
                      style = MaterialTheme.typography.h5,
                      fontWeight = FontWeight.ExtraBold,
                      color = MaterialTheme.colors.onSurface
                    )
                  }
                },
                text = {
                    Column(
                      modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                      horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                          text = "The game has concluded.",
                          style = MaterialTheme.typography.body1,
                          color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Surface(
                          color = MaterialTheme.colors.primary.copy(alpha = 0.1f),
                          shape = RoundedCornerShape(16.dp),
                          border = BorderStroke(1.dp, MaterialTheme.colors.primary.copy(alpha = 0.2f))
                        ) {
                          Text(
                            text = finalScoreText ?: "",
                            style = MaterialTheme.typography.h4,
                            color = MaterialTheme.colors.primary,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                          )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            finalScoreText = null
                            showSettings = true
                        },
                        shape = RoundedCornerShape(24.dp),
                        elevation = ButtonDefaults.elevation(0.dp, 0.dp),
                        modifier = Modifier.padding(8.dp).fillMaxWidth().height(48.dp)
                    ) {
                        Text("NEW GAME", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                },
                dismissButton = {
                    TextButton(
                      onClick = { finalScoreText = null },
                      modifier = Modifier.padding(8.dp).fillMaxWidth().height(48.dp)
                    ) {
                      Text("BACK TO BOARD", fontWeight = FontWeight.Bold, color = Color.Gray)
                    }
                }
            )
          }
    }
  }

  private suspend fun initEngine(modelName: String): Int = withContext(Dispatchers.IO) {
    try {
      // Check disk space for safety first
      val usableSpace = requireContext().filesDir.usableSpace
      if (usableSpace < 50 * 1024 * 1024L) { // absolute minimum 50MB
        Log.e("GameFragment", "Aborting engine init: critical storage low ($usableSpace bytes).")
        return@withContext -6
      }

      val effectiveModelName = if (bridge is KataGoBridgeTPU) {
        if (WeiqiApplication.isPixel11Family() && hasAsset("model_tpu_p11.tflite")) {
          "model_tpu_p11.tflite"
        } else if (hasAsset("model_tpu_p9.tflite")) {
          "model_tpu_p9.tflite"
        } else if (hasAsset("model_tpu.tflite")) {
          "model_tpu.tflite"
        } else {
          modelName
        }
      } else {
        modelName
      }


      val configPath = copyAssetToFile("gtp.cfg")
      val modelPath = copyAssetToFile(effectiveModelName)
      if (configPath == null || modelPath == null) {
        Log.e("GameFragment", "Failed to extract assets: cfg=$configPath, model=$modelPath")
        if (usableSpace < 230 * 1024 * 1024L) { // ~230MB model extraction space
          return@withContext -6 // Storage Space Error
        }
        return@withContext -4 // General Asset Copy Error
      }

      Log.i("GameFragment", "Starting KataGo Engine Init with model $effectiveModelName...")
      var result = bridge.init(configPath, modelPath)
      Log.i("GameFragment", "Engine Init Result: $result")
      if (result != 0 && bridge is KataGoBridgeTPU) {
        Log.w("GameFragment", "TPU initialization failed ($result). Falling back to OpenCL GPU backend...")
        try {
          bridge.shutdown()
        } catch (e: Exception) {
          Log.e("GameFragment", "Failed to shutdown TPU bridge", e)
        }
        bridge = KataGoBridge()
        val gpuModelPath = copyAssetToFile("model.bin.gz") ?: modelPath
        result = bridge.init(configPath, gpuModelPath)
        Log.i("GameFragment", "GPU Engine Init Result: $result")
      }
      if (result in -18..-10) {
        Log.w("GameFragment", "GPU initialization failed ($result). Falling back to CPU/Eigen backend...")
        try {
          bridge.shutdown()
        } catch (e: Exception) {
          Log.e("GameFragment", "Failed to shutdown GPU bridge", e)
        }
        bridge = KataGoBridgeEigen()
        val cpuModelPath = copyAssetToFile("model.bin.gz") ?: modelPath
        result = bridge.init(configPath, cpuModelPath)
        Log.i("GameFragment", "CPU/Eigen Engine Init Result: $result")
      }

      val verifiedBackend = when (bridge) {
        is KataGoBridgeTPU -> if (WeiqiApplication.isPixel11Family()) "TPU_PIXEL_11 (Tensor G6 SantaFe)" else "TPU_PIXEL_9 (Tensor G4 Zuma)"
        is KataGoBridge -> "GPU_OPENCL"
        is KataGoBridgeEigen -> "CPU_EIGEN"
        else -> "UNKNOWN"
      }
      Log.i("KataGoBackend", "=============================================")
      Log.i("KataGoBackend", "VERIFIED RUNTIME BACKEND: $verifiedBackend")
      Log.i("KataGoBackend", "ACTIVE BRIDGE CLASS: ${bridge.javaClass.simpleName}")
      Log.i("KataGoBackend", "LOADED MODEL FILE: $effectiveModelName")
      Log.i("KataGoBackend", "INITIALIZATION STATUS: ${if (result == 0) "SUCCESS" else "FAILED ($result)"}")
      Log.i("KataGoBackend", "=============================================")

      result
    } catch (e: Exception) {
      Log.e("GameFragment", "Engine Init Exception", e)
      -5
    }
  }

  private fun hasAsset(assetName: String): Boolean {
    return try {
      requireContext().assets.open(assetName).close()
      true
    } catch (e: Exception) {
      false
    }
  }


  private fun copyAssetToFile(assetName: String): String? {
    val destFile = File(requireContext().filesDir, assetName)

    // If file already exists and has substantial size, skip copying
    // g170 model is ~200MB, gtp.cfg is ~30KB
    if (destFile.exists() && destFile.length() > 0) {
      Log.i("GameFragment", "Asset $assetName already exists, skipping copy.")
      return destFile.absolutePath
    }

    // Defensive Check: Verify Usable Space before copying large assets
    val requiredBytes = try {
      requireContext().assets.openFd(assetName).use { it.length }
    } catch (e: Exception) {
      // For compressed files or assets where openFd isn't supported, fallback to estimated thresholds
      if (assetName.endsWith(".gz")) 180 * 1024 * 1024L else 1024 * 1024L
    }

    val usableSpace = requireContext().filesDir.usableSpace
    if (usableSpace < requiredBytes + (15 * 1024 * 1024L)) { // require 15MB safety buffer
      Log.e("GameFragment", "Insufficient space to extract $assetName. Required: $requiredBytes, Usable: $usableSpace")
      return null
    }

    val tempFile = File(requireContext().filesDir, "$assetName.tmp")
    try {
      Log.i("GameFragment", "Extracting asset $assetName to internal storage...")
      requireContext().assets.open(assetName).use { inputStream ->
        FileOutputStream(tempFile).use { outputStream ->
          inputStream.copyTo(outputStream)
        }
      }
      
      // Validate copy integrity by verifying size > 0
      if (tempFile.length() == 0L) {
        Log.e("GameFragment", "Integrity Check Failed: Extracted file $assetName size is 0 bytes.")
        return null
      }

      if (tempFile.renameTo(destFile)) {
        Log.i("GameFragment", "Successfully extracted $assetName")
        return destFile.absolutePath
      } else {
        Log.e("GameFragment", "Failed to rename temp file for $assetName")
        return null
      }
    } catch (e: Exception) {
      Log.e("GameFragment", "Error copying asset $assetName", e)
      return null
    } finally {
      if (tempFile.exists()) tempFile.delete()
    }
  }

  private fun formatLatency(ms: Long?): String {
    if (ms == null) return ""
    return String.format(java.util.Locale.US, "%.2fs", ms / 1000.0)
  }

  private fun toGtpCoords(x: Int, y: Int): String {
    val col = if (x >= 8) ('A' + x + 1).toChar() else ('A' + x).toChar()
    val row = 19 - y
    return "$col$row"
  }

  private fun fromGtpCoords(gtp: String): Pair<Int, Int> {
    val response = if (gtp.startsWith("=")) gtp.substring(1).trim() else gtp.trim()
    if (response.isEmpty() || response.uppercase() == "PASS") return -1 to -1

    val coord = response.split(" ")[0].uppercase()
    val colChar = coord[0]
    val x = if (colChar > 'I') colChar - 'A' - 1 else colChar - 'A'
    val row = coord.substring(1).toInt()
    val y = 19 - row
    return x to y
  }

  private suspend fun playMove(x: Int, y: Int, stone: Stone, bridge: IKataGoBridge, onResult: (Boolean, String) -> Unit) {
    val color = if (stone == Stone.BLACK) "black" else "white"
    val moveStr = toGtpCoords(x, y)
    val response = withContext(Dispatchers.IO) {
      bridge.sendGtpCommand("play $color $moveStr")
    }
    onResult(response.startsWith("="), moveStr)
  }

  private suspend fun genAiMove(stone: Stone, bridge: IKataGoBridge, onResult: (Int, Int, String, Long) -> Unit) {
    val color = if (stone == Stone.BLACK) "black" else "white"
    val startTime = System.currentTimeMillis()
    val response = withContext(Dispatchers.IO) {
      bridge.sendGtpCommand("genmove $color")
    }
    val elapsed = System.currentTimeMillis() - startTime
    Log.i("KataGoLatency", "genmove $color finished in ${elapsed}ms")
    if (response.startsWith("=")) {
      val parts = response.split(" ")
      if (parts.size >= 2) {
        val moveStr = parts[1]
        if (moveStr.uppercase() == "PASS") {
          onResult(-1, -1, "PASS", elapsed)
        } else {
          val (x, y) = fromGtpCoords(response)
          onResult(x, y, moveStr, elapsed)
        }
      }
    } else {
      onResult(-1, -1, "ERROR", elapsed)
    }
  }

  private fun syncBoardState(bridge: IKataGoBridge): Array<Array<Stone>> {
    val rawBoard = bridge.boardState ?: return Array(boardSize) { Array(boardSize) { Stone.EMPTY } }
    val newBoard = Array(boardSize) { Array(boardSize) { Stone.EMPTY } }
    for (y in 0 until boardSize) {
      for (x in 0 until boardSize) {
        val stoneInt = rawBoard[y * boardSize + x]
        newBoard[y][x] = when (stoneInt) {
          1 -> Stone.BLACK
          2 -> Stone.WHITE
          else -> Stone.EMPTY
          }
      }
    }
    return newBoard
  }

  private suspend fun getAnalysis(bridge: IKataGoBridge, perspective: Stone): AnalysisResult = withContext(Dispatchers.IO) {
    // Query analysis from specified perspective
    val colorStr = if (perspective == Stone.WHITE) "white" else "black"
    Log.i("GameFragment", "Requesting analysis for $colorStr...")
    val response = bridge.sendGtpCommand("kata-get-analysis $colorStr")
    if (response.startsWith("=")) {
      try {
        val jsonStr = response.substring(1).trim()
        val json = JSONObject(jsonStr)
        val rootInfo = json.getJSONObject("rootInfo")
        Log.d("GameFragment", "RootInfo: $rootInfo")
        val winrate = rootInfo.getDouble("winrate")
        val scoreLead = rootInfo.getDouble("scoreLead")
        val visits = if (rootInfo.has("visits")) rootInfo.getLong("visits") else 0L

        val ownershipArray = json.getJSONArray("ownership")
        val ownership = DoubleArray(ownershipArray.length())
        for (i in 0 until ownershipArray.length()) {
          ownership[i] = ownershipArray.getDouble(i)
        }

        val moveInfos = json.getJSONArray("moveInfos")
        val candidates = mutableListOf<CandidateMove>()
        for (i in 0 until moveInfos.length()) {
          val moveInfo = moveInfos.getJSONObject(i)
          val moveStr = moveInfo.getString("move")
          if (moveStr.uppercase() == "PASS") continue

          val (x, y) = fromGtpCoords(moveStr)
          if (x != -1 && y != -1) {
            candidates.add(CandidateMove(
                             x = x,
                             y = y,
                            winrate = moveInfo.getDouble("winrate"),
                            visits = moveInfo.getLong("visits")
            ))
          }
        }

        val topCandidates = candidates.sortedByDescending { it.visits }.take(5)
        Log.i("GameFragment", "Analysis received: winrate=$winrate, scoreLead=$scoreLead, visits=$visits, candidates=${topCandidates.size}")
        AnalysisResult(winrate, scoreLead, visits, ownership, topCandidates)
      } catch (e: Exception) {
        Log.e("GameFragment", "Error parsing analysis: ${e.message}", e)
        AnalysisResult()
      }
    } else {
      Log.w("GameFragment", "Analysis command failed or returned empty: $response")
      AnalysisResult()
    }
  }
}
