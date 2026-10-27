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

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    billingManager = BillingManager(requireContext(), lifecycleScope)
    bridge = createEngineBridge()
  }

  private fun createEngineBridge(): IKataGoBridge {
    val filesDir = try { requireContext().filesDir } catch (e: Exception) { null }
    return if (WeiqiApplication.hasSystemOpenCL(filesDir)) {
      Log.i("GameFragment", "OpenCL GPU detected. Initializing KataGoBridge (OpenCL GPU).")
      KataGoBridge()
    } else {
      Log.i("GameFragment", "No OpenCL GPU detected. Falling back to KataGoBridgeEigen (CPU).")
      KataGoBridgeEigen()
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
                  .padding(16.dp),
                contentAlignment = Alignment.BottomCenter
              ) {
                Card(
                  shape = RoundedCornerShape(16.dp),
                  elevation = 6.dp,
                  backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.95f)
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                  ) {
                    CircularProgressIndicator(
                      color = MaterialTheme.colors.primary,
                      strokeWidth = 3.dp,
                      modifier = Modifier.size(22.dp)
                    )
                    Column {
                      Text(
                        text = statusText,
                        style = MaterialTheme.typography.subtitle2,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.onSurface
                      )
                      Text(
                        text = context.getString(R.string.preparing_engine),
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

    val initialShowLatency = remember { sharedPrefs.getBoolean("show_engine_latency", false) }
    var showEngineLatency by remember { mutableStateOf(initialShowLatency) }
    var lastEngineLatencyMs by remember { mutableStateOf<Long?>(null) }

    val backendBadgeText = when (bridge) {
      is KataGoBridge -> "GPU (OpenCL)"
      is KataGoBridgeEigen -> "CPU (Eigen)"
      else -> "KataGo"
    }

    val backendDetailText = when (bridge) {
      is KataGoBridge -> "OpenCL Mobile GPU (Mali/Adreno)"
      is KataGoBridgeEigen -> "CPU Eigen Multi-threaded Fallback"
      else -> "KataGo Engine"
    }

    var lastMoveText by remember { mutableStateOf(requireContext().getString(R.string.last_move_none)) }
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
    // The result dialog is shown once when a game ends. BACK TO BOARD closes it but keeps the finished
    // game (result, territory, locked board) until Undo or a new game.
    var showGameOverDialog by remember { mutableStateOf(false) }
    // True while the final score is being computed (it takes a few seconds).
    var isScoring by remember { mutableStateOf(false) }

    fun saveGameState(
      history: List<String> = moveHistory,
      idx: Int = historyIndex,
      turn: Stone = currentTurn,
      score: String? = finalScoreText,
      passes: Int = consecutivePasses
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
        .apply()
    }

    // --- Game-rule helpers: handicap setup, turn order and which side the AI plays ---
    // Handicap 1-9 places that many Black stones on the star points (1 stone = Q16, as in the iOS
    // app), komi is 0.5 and White moves first. Without handicap, komi is 7.5 and Black moves first.
    fun handicapStoneCount(h: Int): Int = h.coerceIn(0, 9)
    fun komiForHandicap(h: Int): Double = if (h >= 1) 0.5 else 7.5
    fun firstPlayer(h: Int): Stone = if (handicapStoneCount(h) > 0) Stone.WHITE else Stone.BLACK
    fun opponent(s: Stone): Stone = if (s == Stone.BLACK) Stone.WHITE else Stone.BLACK
    fun gtpColor(s: Stone): String = if (s == Stone.BLACK) "black" else "white"
    fun isAiTurn(mode: GameMode, turn: Stone): Boolean = when (mode) {
      GameMode.USER_BLACK -> turn == Stone.WHITE
      GameMode.USER_WHITE -> turn == Stone.BLACK
      GameMode.AI_BOTH -> true
      GameMode.USER_BOTH -> false
    }
    fun isHumanColor(mode: GameMode, color: Stone): Boolean = !isAiTurn(mode, color)
    fun isUserVsAi(mode: GameMode): Boolean = mode == GameMode.USER_BLACK || mode == GameMode.USER_WHITE
    fun isPassMove(move: String): Boolean = move.equals("pass", ignoreCase = true)

    // moveHistory starts with the handicap stones (setup entries that are never undone), followed by
    // every move and pass in order, so colors strictly alternate after the setup.
    fun moveColor(i: Int, h: Int): Stone =
      if ((i - handicapStoneCount(h)) % 2 == 0) firstPlayer(h) else opponent(firstPlayer(h))

    // Side to move after the entries up to idx have been played.
    fun sideToMove(idx: Int, h: Int): Stone =
      if (idx < handicapStoneCount(h)) firstPlayer(h) else opponent(moveColor(idx, h))

    // Moves taken back by Undo. Against the AI, Undo returns to your last decision (your move and the
    // AI's reply), so it is your turn again; it is unavailable until you have made a move. Two Players:
    // one move. AI vs AI: Undo is off.
    fun movesToUndo(mode: GameMode, idx: Int, h: Int): Int {
      val setup = handicapStoneCount(h)
      return when (mode) {
        GameMode.AI_BOTH -> 0
        GameMode.USER_BOTH -> if (idx >= setup) 1 else 0
        GameMode.USER_BLACK, GameMode.USER_WHITE -> {
          var i = idx
          while (i >= setup && !isHumanColor(mode, moveColor(i, h))) i--
          if (i >= setup) idx - i + 1 else 0
        }
      }
    }

    fun colorLabel(s: Stone): String =
      requireContext().getString(if (s == Stone.BLACK) R.string.color_black else R.string.color_white)

    fun trailingPasses(history: List<String>, idx: Int, h: Int): Int {
      var count = 0
      var i = idx
      while (i >= handicapStoneCount(h) && i < history.size && isPassMove(history[i])) {
        count++
        i--
      }
      return count
    }

    fun lastPlayedPoint(history: List<String>, idx: Int, h: Int): Pair<Int, Int>? =
      if (idx >= handicapStoneCount(h) && idx < history.size && !isPassMove(history[idx])) fromGtpCoords(history[idx]) else null

    // The handicap stones on the board in row-major order: the setup entries saved at the start of a game.
    fun handicapSetup(board: Array<Array<Stone>>): List<String> {
      val stones = mutableListOf<String>()
      for (r in 0 until boardSize) {
        for (c in 0 until boardSize) {
          if (board[r][c] == Stone.BLACK) stones.add(toGtpCoords(c, r))
        }
      }
      return stones
    }

    // Automatically update analysis when the position changes (also Undo/Redo of two moves, which keeps
    // the same side to move) or analysis is toggled ON
    LaunchedEffect(currentTurn, showAnalysis, historyIndex) {
      if (showAnalysis && isEngineInitialized && !isThinking) {
        val startTime = System.currentTimeMillis()
        analysis = getAnalysis(bridge, currentTurn)
        val elapsed = System.currentTimeMillis() - startTime
        lastEngineLatencyMs = elapsed
        Log.i("KataGoLatency", "[$backendBadgeText] Analysis updated in ${elapsed}ms")
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
        val path = copyAssetToFile(context.applicationContext, "place_stone.mp3")
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

    // Scores the game after two passes in a row. presentDialog is false when re-scoring a finished game
    // that was restored without its result.
    suspend fun checkGameEnd(presentDialog: Boolean = true) {
      if (consecutivePasses >= 2) {
        isScoring = true
        previewMove = null
        onThinkingChange(true)
        var scoreText: String? = null
        withContext(Dispatchers.IO) {
          val score = bridge.sendGtpCommand("final_score")
          if (score.startsWith("=")) {
            scoreText = score.substring(1).trim()
          } else {
            Log.w("GameFragment", "final_score failed: $score")
          }
        }
        val result = scoreText ?: context.getString(R.string.game_over)
        Log.i("GameFragment", "Game over, result: $result")
        finalScoreText = result
        analysis = getAnalysis(bridge, currentTurn)
        saveGameState(score = result)
        isScoring = false
        onThinkingChange(false)
        showGameOverDialog = presentDialog
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
          val latencySuffix = if (showEngineLatency) " (${formatLatency(elapsedMs)})" else ""
          lastMoveText = context.getString(R.string.last_move_ai_played, colorLabel(color), aiMoveStr) + latencySuffix
          
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
          lastMoveText = context.getString(R.string.last_move_ai_passed, colorLabel(color)) + latencySuffix
          android.widget.Toast.makeText(context, R.string.msg_ai_passed, android.widget.Toast.LENGTH_SHORT).show()
          playPassSound()
          // Record the pass so undo/redo and saved-game restore keep colors in sync with the engine.
          val newHistory = moveHistory.take(historyIndex + 1) + "pass"
          val newIndex = historyIndex + 1
          moveHistory = newHistory
          historyIndex = newIndex
          lastMove = null
          val newPasses = consecutivePasses + 1
          consecutivePasses = newPasses
          val nextTurn = if (color == Stone.BLACK) Stone.WHITE else Stone.BLACK
          currentTurn = nextTurn
          onStatusTextChange("Turn.")
          scope.launch { checkGameEnd() }
          saveGameState(history = newHistory, idx = newIndex, turn = nextTurn, passes = newPasses)

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
            val scoreText = context.getString(R.string.resign_result, colorLabel(opponent(color)))
            onStatusTextChange(scoreText)
            lastMoveText = scoreText
            finalScoreText = scoreText
            showGameOverDialog = true
            saveGameState(score = scoreText)
        } else {
          aiAutoPlay = false
          onStatusTextChange("AI error.")
        }
      }
    }



    suspend fun startNewGame(mode: GameMode, h: Int, m: String, v: Int) {
      onThinkingChange(true)
      isGameInProgress = true

      if (m != currentModelName) {
        onStatusTextChange("Re-initializing engine...")
        try { bridge.shutdown() } catch (e: Exception) { Log.e("GameFragment", "Failed to shutdown previous bridge", e) }
        val newBridge = createEngineBridge()
        this@GameFragment.bridge = newBridge
        onBridgeChange(newBridge)
        val res = initEngine(m)
        onBridgeChange(this@GameFragment.bridge)
        if (res != 0) {
          onStatusTextChange("Engine Init Failed: $res")
          onEngineErrorChange(res)
          onThinkingChange(false)
          return
        }
        currentModelName = m
      }

      onStatusTextChange("Starting new game...")
      val stones = handicapStoneCount(h)
      withContext(Dispatchers.IO) {
        bridge.sendGtpCommand("clear_board")
        bridge.sendGtpCommand("set_max_visits $v")
        bridge.sendGtpCommand("komi ${komiForHandicap(h)}")

        if (stones > 0) {
          val resp = bridge.sendGtpCommand("fixed_handicap $stones")
          if (!resp.startsWith("=")) Log.e("GameFragment", "fixed_handicap $stones failed: $resp")
        }
      }

      boardState = syncBoardState(bridge)

      // Handicap stones are kept at the start of moveHistory as setup entries (never undone).
      val newMoveHistory = if (stones > 0) handicapSetup(boardState) else emptyList()

      previewMove = null
      lastMove = null
      moveHistory = newMoveHistory
      historyIndex = newMoveHistory.size - 1
      consecutivePasses = 0
      finalScoreText = null
      showGameOverDialog = false
      isScoring = false
      analysis = AnalysisResult()
      currentMode = mode
      handicap = h
      currentVisits = v
      aiAutoPlay = (mode == GameMode.AI_BOTH)
      onThinkingChange(false)

      // With handicap stones on the board White moves first; otherwise Black does.
      val firstTurn = firstPlayer(h)
      currentTurn = firstTurn
      onStatusTextChange("Turn.")
      lastMoveText = context.getString(R.string.last_move_none)

      saveGameState(
        history = newMoveHistory,
        idx = newMoveHistory.size - 1,
        turn = firstTurn,
        score = null,
        passes = 0
      )

      // The AI opens only when the first move belongs to its color (e.g. you play White without
      // handicap, or Black with handicap). Playing White with handicap means you move first.
      if (isAiTurn(mode, firstTurn)) {
        handleAiMove(firstTurn)
      }
    }


    suspend fun restoreSavedGame(
      movesJsonStr: String,
      mode: GameMode,
      h: Int,
      v: Int,
      scoreText: String?
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

      // Set up the handicap, then replay the moves (and passes) with strictly alternating colors,
      // starting with the player who moves first. The replay stops at the first move the engine
      // refuses, so the move list always matches the engine's game record, and the side to move is
      // derived from the replay.
      val stones = handicapStoneCount(h)
      var turn = firstPlayer(h)
      var setup = emptyList<String>()
      var gameMoves: List<String> = moves
      val replayed = mutableListOf<String>()
      withContext(Dispatchers.IO) {
        bridge.sendGtpCommand("clear_board")
        bridge.sendGtpCommand("set_max_visits $v")
        bridge.sendGtpCommand("komi ${komiForHandicap(h)}")

        if (stones > 0) {
          val resp = bridge.sendGtpCommand("fixed_handicap $stones")
          if (!resp.startsWith("=")) Log.e("GameFragment", "Restore: fixed_handicap $stones failed: $resp")
          setup = handicapSetup(syncBoardState(bridge))
          // Saved games start with the handicap stones. Versions before 3.6 saved 1-stone games
          // without any (they placed no stone), so only skip entries that are the handicap stones.
          if (moves.size >= setup.size && moves.take(setup.size).toSet() == setup.toSet()) {
            gameMoves = moves.drop(setup.size)
          }
        }

        for (mv in gameMoves) {
          val resp = bridge.sendGtpCommand("play ${gtpColor(turn)} $mv")
          if (!resp.startsWith("=")) {
            Log.w("GameFragment", "Restore: play ${gtpColor(turn)} $mv refused ($resp); the saved game ends here")
            break
          }
          replayed.add(mv)
          turn = opponent(turn)
        }
      }
      val history = setup + replayed
      val complete = replayed.size == gameMoves.size

      boardState = syncBoardState(bridge)
      previewMove = null
      lastMove = lastPlayedPoint(history, history.size - 1, h)

      moveHistory = history
      historyIndex = history.size - 1
      consecutivePasses = trailingPasses(history, history.size - 1, h)
      // A finished game comes back finished (result and territory shown, board locked) without
      // reopening the result dialog.
      finalScoreText = if (complete) scoreText else null
      showGameOverDialog = false
      isScoring = false
      analysis = AnalysisResult()
      currentMode = mode
      handicap = h
      currentVisits = v
      aiAutoPlay = false
      currentTurn = turn
      if (history != moves || !complete) {
        saveGameState(history = history, idx = history.size - 1, turn = turn, score = finalScoreText, passes = consecutivePasses)
      }

      lastMoveText = if (history.size > stones) {
        val lastMoveStr = history.last()
        val lastColor = colorLabel(opponent(turn))
        if (isPassMove(lastMoveStr)) context.getString(R.string.last_move_passed, lastColor)
        else context.getString(R.string.last_move_played, lastColor, lastMoveStr)
      } else context.getString(R.string.last_move_none)

      if (finalScoreText != null) {
        // Territory marks for the finished game.
        analysis = getAnalysis(bridge, turn)
        onThinkingChange(false)
        onStatusTextChange("Turn.")
      } else if (consecutivePasses >= 2) {
        // The game had ended but its result was not saved (e.g. the app closed while counting).
        onThinkingChange(false)
        checkGameEnd(presentDialog = false)
      } else {
        onThinkingChange(false)
        onStatusTextChange("Turn.")
        // If the app was closed while the AI was about to move, let it move now instead of stalling.
        if (isUserVsAi(mode) && isAiTurn(mode, turn)) {
          handleAiMove(turn)
        }
      }
    }

    LaunchedEffect(isThinking, engineError) {
      if (isThinking && !isEngineInitialized && engineError == null) {
        val tuningMsg = when {
          bridge is KataGoBridge -> requireContext().getString(R.string.tuning_gpu)
          else -> requireContext().getString(R.string.initializing_engine)
        }
        onStatusTextChange(tuningMsg)

        val result = initEngine(currentModelName)
        onBridgeChange(this@GameFragment.bridge)
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
              val savedScoreText = sharedPrefs.getString("final_score_text", null)
              restoreSavedGame(savedMoves, savedMode, savedHandicap, savedVisits, savedScoreText)
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
                    if (isScoring) {
                      // Scoring takes a few seconds; the board stays locked meanwhile.
                      Text(
                        text = context.getString(R.string.counting_score),
                        style = MaterialTheme.typography.h6,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colors.onSurface
                      )
                    } else if (finalScoreText != null) {
                      Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                          text = context.getString(R.string.game_over),
                          style = MaterialTheme.typography.h5,
                          fontWeight = FontWeight.ExtraBold,
                          color = MaterialTheme.colors.onSurface
                        )
                        Text(
                          text = context.getString(R.string.result_format, finalScoreText ?: ""),
                          style = MaterialTheme.typography.subtitle1,
                          fontWeight = FontWeight.Bold,
                          color = MaterialTheme.colors.primary
                        )
                      }
                    } else {
                      if (isEngineInitialized) {
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

                      if (!showAnalysis && isEngineInitialized) {
                        Spacer(Modifier.width(16.dp))
                        Text(
                          text = lastMoveText,
                          style = MaterialTheme.typography.subtitle1,
                          fontWeight = FontWeight.Bold,
                          color = MaterialTheme.colors.onSurface
                        )
                      }

                      if (showAnalysis) {
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
                          text = context.getString(R.string.analysis_summary, winratePercent, "$leadSign$scoreLeadFormatted") + latencyText,
                          style = MaterialTheme.typography.h6,
                          fontWeight = FontWeight.ExtraBold,
                          color = MaterialTheme.colors.primary
                        )
                      }
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

                        // Only the player to move can pick a point, and not after the game ended.
                        if (finalScoreText == null && isHumanColor(currentMode, currentTurn) && boardState[y][x] == Stone.EMPTY) {
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
                  }

                  if (showAnalysis && finalScoreText == null) {
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

                      // Display candidate move winrate from the perspective of the player whose turn it is
                      val displayedWinrate = if (currentTurn == Stone.WHITE) 1.0 - candidate.winrate else candidate.winrate
                      val winratePercent = (displayedWinrate * 100).toInt().coerceIn(0, 100)
                      val winrateText = "$winratePercent%"
                      val textPaint = android.graphics.Paint().apply {
                        color = if (candidate == bestMove) android.graphics.Color.parseColor("#B71C1C")
                        else android.graphics.Color.WHITE
                        textSize = 9.dp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                        isAntiAlias = true
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                      }
                      drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawText(
                          winrateText,
                          centerX,
                          centerY + 3.5f.dp.toPx(),
                          textPaint
                        )
                      }
                    }
                  }                       // Draw Preview Stone (Ghost Stone)
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
                        val turnColor = currentTurn
                        val resp = withContext(Dispatchers.IO) {
                          bridge.sendGtpCommand("play ${gtpColor(turnColor)} pass")
                        }
                        if (!resp.startsWith("=")) {
                          onStatusTextChange("Pass failed: $resp")
                          return@launch
                        }
                        playPassSound()
                        // Record the pass (and drop any redo moves) so undo/redo and saved-game
                        // restore keep colors in sync with the engine.
                        val newHistory = moveHistory.take(historyIndex + 1) + "pass"
                        val newIndex = historyIndex + 1
                        moveHistory = newHistory
                        historyIndex = newIndex
                        lastMove = null
                        previewMove = null
                        val newPasses = consecutivePasses + 1
                        consecutivePasses = newPasses
                        lastMoveText = context.getString(R.string.last_move_passed, colorLabel(turnColor))
                        val nextTurn = opponent(turnColor)
                        currentTurn = nextTurn
                        
                        checkGameEnd()
                        saveGameState(history = newHistory, idx = newIndex, turn = nextTurn, passes = newPasses)
 
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
                          lastMoveText = context.getString(R.string.last_move_played, colorLabel(turnColor), moveStr)
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
                          // Ko, suicide or an occupied point: the engine refused the move.
                          Log.i("GameFragment", "Illegal move refused: ${gtpColor(turnColor)} $moveStr")
                          android.widget.Toast.makeText(context, R.string.msg_illegal_move, android.widget.Toast.LENGTH_SHORT).show()
                          boardState = syncBoardState(bridge)
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
                  // The default 16dp side padding leaves only 48dp for the label in an 80dp circle,
                  // which wrapped "PLACE" mid-word. Use nearly the full width and keep it on one line.
                  contentPadding = PaddingValues(horizontal = 4.dp),
                  elevation = ButtonDefaults.elevation(defaultElevation = 6.dp, pressedElevation = 12.dp)
                ) {
                  Text(context.getString(R.string.btn_place), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
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
              val undoCount = movesToUndo(currentMode, historyIndex, handicap)
              IconButton(
                onClick = {
                  val count = movesToUndo(currentMode, historyIndex, handicap)
                  if (count > 0 && !isThinking) {
                    scope.launch {
                      onThinkingChange(true)
                      // movesToUndo never reaches into the handicap setup. Against the AI it also takes
                      // back the AI's reply, so it is your turn again.
                      var undone = 0
                      while (undone < count) {
                        val resp = withContext(Dispatchers.IO) { bridge.sendGtpCommand("undo") }
                        if (!resp.startsWith("=")) {
                          Log.w("GameFragment", "undo failed: $resp")
                          break
                        }
                        undone++
                      }
                      val newIndex = historyIndex - undone
                      historyIndex = newIndex
                      currentTurn = sideToMove(newIndex, handicap)
                      boardState = syncBoardState(bridge)
                      lastMove = lastPlayedPoint(moveHistory, newIndex, handicap)
                      lastMoveText = if (newIndex >= handicapStoneCount(handicap)) {
                        context.getString(R.string.last_move_undone, moveHistory[newIndex])
                      } else {
                        context.getString(R.string.last_move_undone_start)
                      }
                      previewMove = null
                      consecutivePasses = trailingPasses(moveHistory, newIndex, handicap)
                      // Taking back a move after the game ended resumes play.
                      finalScoreText = null
                      showGameOverDialog = false
                      saveGameState(idx = newIndex, turn = currentTurn, score = null, passes = consecutivePasses)
                      onThinkingChange(false)
                    }
                  }
                },
                enabled = undoCount > 0 && !isThinking,
                modifier = Modifier.background(MaterialTheme.colors.surface, CircleShape).size(48.dp)
              ) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = MaterialTheme.colors.primary)
              }
 
              // New Game Button
              Button(
                onClick = {
                  // Pause AI-vs-AI autoplay so no engine move races with setting up a new game.
                  aiAutoPlay = false
                  showSettings = true
                },
                // Starting a new game while the AI is still searching would mix the old game's AI
                // move into the new game.
                enabled = !isThinking,
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

              // Redo Button (off in AI vs AI, like Undo)
              IconButton(
                onClick = {
                  if (historyIndex < moveHistory.size - 1 && !isThinking && currentMode != GameMode.AI_BOTH) {
                    scope.launch {
                      onThinkingChange(true)
                      // Against the AI, redo the player's move together with the AI's reply.
                      while (historyIndex < moveHistory.size - 1) {
                        val moveStr = moveHistory[historyIndex + 1]
                        val resp = withContext(Dispatchers.IO) {
                          bridge.sendGtpCommand("play ${gtpColor(currentTurn)} $moveStr")
                        }
                        if (!resp.startsWith("=")) {
                          Log.w("GameFragment", "redo of $moveStr failed: $resp")
                          break
                        }
                        historyIndex += 1
                        currentTurn = opponent(currentTurn)
                        if (!isUserVsAi(currentMode) || !isAiTurn(currentMode, currentTurn)) break
                      }
                      boardState = syncBoardState(bridge)
                      val newIndex = historyIndex
                      lastMove = lastPlayedPoint(moveHistory, newIndex, handicap)
                      lastMoveText = context.getString(R.string.last_move_redone, moveHistory.getOrNull(newIndex) ?: "")
                      previewMove = null
                      consecutivePasses = trailingPasses(moveHistory, newIndex, handicap)
                      saveGameState(idx = newIndex, turn = currentTurn, passes = consecutivePasses)
                      onThinkingChange(false)
                      if (consecutivePasses >= 2) {
                        checkGameEnd()
                      } else if (isUserVsAi(currentMode) && isAiTurn(currentMode, currentTurn)) {
                        // The redo history ended on the AI's turn: let it move.
                        handleAiMove(currentTurn)
                      }
                    }
                  }
                },
                enabled = historyIndex < moveHistory.size - 1 && !isThinking && currentMode != GameMode.AI_BOTH,
                modifier = Modifier.background(MaterialTheme.colors.surface, CircleShape).size(48.dp)
              ) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", tint = MaterialTheme.colors.primary)
              }
            }

            Spacer(modifier = Modifier.height(32.dp))
          }

          if (showSettings) {
            // Choices in this dialog only take effect when START is pressed; Cancel (or dismissing)
            // leaves the current game's difficulty, handicap and side untouched. These are re-read
            // from the current game every time the dialog opens.
            var pendingVisits by remember { mutableStateOf(currentVisits) }
            var pendingHandicap by remember { mutableStateOf(handicap) }
            var pendingMode by remember { mutableStateOf(currentMode) }
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
                          val isSelected = pendingVisits == v
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
                                    pendingVisits = v
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
                          val isSelected = pendingHandicap == h
                          Surface(
                            modifier = Modifier
                              .size(width = 72.dp, height = 56.dp)
                              .clip(RoundedCornerShape(16.dp))
                              .pointerInput(h) {
                                detectTapGestures { pendingHandicap = h }
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

                      // Handicap stones always go to Black, so spell out who that is for the chosen side.
                      if (pendingHandicap > 0) {
                        Text(
                          text = context.getString(
                            when (pendingMode) {
                              GameMode.USER_BLACK -> R.string.handicap_footer_user_black
                              GameMode.USER_WHITE -> R.string.handicap_footer_user_white
                              GameMode.USER_BOTH, GameMode.AI_BOTH -> R.string.handicap_footer_black
                            }
                          ),
                          style = MaterialTheme.typography.caption,
                          color = Color.Gray
                        )
                      }

                      Spacer(modifier = Modifier.height(24.dp))

                      // --- Mode Selection ---
                      Text(
                        context.getString(R.string.section_play_as),
                        style = MaterialTheme.typography.overline,
                        color = MaterialTheme.colors.primary,
                        fontWeight = FontWeight.Bold
                      )
                      Spacer(Modifier.height(8.dp))
                      
                      GameMode.values().forEach { mode ->
                        val isSelected = pendingMode == mode
                        Surface(
                          modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .pointerInput(Unit) {
                              detectTapGestures { pendingMode = mode }
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
                              onClick = { pendingMode = mode },
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
                              text = context.getString(R.string.label_active_backend),
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
                              text = context.getString(R.string.label_latest_latency, formatLatency(lastEngineLatencyMs)),
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
                            .putString("current_mode", pendingMode.name)
                            .putInt("handicap", pendingHandicap)
                            .putString("current_model_name", currentModelName)
                            .putInt("current_visits", pendingVisits)
                            .apply()
                          val startMode = pendingMode
                          val startHandicap = pendingHandicap
                          val startVisits = pendingVisits
                          scope.launch {
                            startNewGame(startMode, startHandicap, currentModelName, startVisits)
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

          // Shown once when a game ends. Closing it keeps the finished game on the board.
          if (showGameOverDialog && finalScoreText != null) {
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
                      text = context.getString(R.string.game_over),
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
                          text = context.getString(R.string.game_concluded),
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
                            showGameOverDialog = false
                            showSettings = true
                        },
                        shape = RoundedCornerShape(24.dp),
                        elevation = ButtonDefaults.elevation(0.dp, 0.dp),
                        modifier = Modifier.padding(8.dp).fillMaxWidth().height(48.dp)
                    ) {
                        Text(context.getString(R.string.btn_new_game), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                },
                dismissButton = {
                    TextButton(
                      onClick = { showGameOverDialog = false },
                      modifier = Modifier.padding(8.dp).fillMaxWidth().height(48.dp)
                    ) {
                      Text(context.getString(R.string.btn_back_to_board), fontWeight = FontWeight.Bold, color = Color.Gray)
                    }
                }
            )
          }
    }
  }

  private suspend fun initEngine(modelName: String): Int = withContext(Dispatchers.IO) {
    try {
      val appContext = context?.applicationContext ?: return@withContext -5
      // Check disk space for safety first
      val usableSpace = appContext.filesDir.usableSpace
      if (usableSpace < 50 * 1024 * 1024L) { // absolute minimum 50MB
        Log.e("GameFragment", "Aborting engine init: critical storage low ($usableSpace bytes).")
        return@withContext -6
      }

      val configPath = copyAssetToFile(appContext, "gtp.cfg")
      val modelPath = copyAssetToFile(appContext, modelName)
      if (configPath == null || modelPath == null) {
        Log.e("GameFragment", "Failed to extract assets: cfg=$configPath, model=$modelPath")
        if (usableSpace < 230 * 1024 * 1024L) { // ~230MB model extraction space
          return@withContext -6 // Storage Space Error
        }
        return@withContext -4 // General Asset Copy Error
      }

      Log.i("GameFragment", "Starting KataGo Engine Init with model $modelName...")
      var result = bridge.init(configPath, modelPath)
      Log.i("GameFragment", "Engine Init Result: $result")

      if (result != 0 && bridge is KataGoBridge) {
        Log.w("GameFragment", "GPU initialization failed ($result). Falling back to CPU/Eigen backend...")
        try {
          bridge.shutdown()
        } catch (e: Exception) {
          Log.e("GameFragment", "Failed to shutdown GPU bridge", e)
        }
        bridge = KataGoBridgeEigen()
        result = bridge.init(configPath, modelPath)
        Log.i("GameFragment", "CPU/Eigen Engine Init Result: $result")
      }

      val verifiedBackend = when (bridge) {
        is KataGoBridge -> "GPU_OPENCL"
        is KataGoBridgeEigen -> "CPU_EIGEN"
        else -> "UNKNOWN"
      }
      Log.i("KataGoBackend", "=============================================")
      Log.i("KataGoBackend", "VERIFIED RUNTIME BACKEND: $verifiedBackend")
      Log.i("KataGoBackend", "ACTIVE BRIDGE CLASS: ${bridge.javaClass.simpleName}")
      Log.i("KataGoBackend", "LOADED MODEL FILE: $modelName")
      Log.i("KataGoBackend", "INITIALIZATION STATUS: ${if (result == 0) "SUCCESS" else "FAILED ($result)"}")
      Log.i("KataGoBackend", "=============================================")

      result
    } catch (e: Exception) {
      Log.e("GameFragment", "Engine Init Exception", e)
      -5
    }
  }


  private fun copyAssetToFile(appContext: android.content.Context, assetName: String): String? {
    val destFile = File(appContext.filesDir, assetName)

    // Reuse an extracted copy, unless the app was updated after it was extracted: an update can
    // change the bundled file (3.6 switched gtp.cfg to Chinese rules), and keeping the old copy
    // would silently keep the old behavior.
    val updatedSinceExtraction = try {
      appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime > destFile.lastModified()
    } catch (e: Exception) {
      true
    }
    if (destFile.exists() && destFile.length() > 0 && !updatedSinceExtraction) {
      Log.i("GameFragment", "Asset $assetName already exists, skipping copy.")
      return destFile.absolutePath
    }

    // Defensive Check: Verify Usable Space before copying large assets
    val requiredBytes = try {
      appContext.assets.openFd(assetName).use { it.length }
    } catch (e: Exception) {
      // For compressed files or assets where openFd isn't supported, fallback to estimated thresholds
      if (assetName.endsWith(".gz")) 180 * 1024 * 1024L else 1024 * 1024L
    }

    val usableSpace = appContext.filesDir.usableSpace
    if (usableSpace < requiredBytes + (15 * 1024 * 1024L)) { // require 15MB safety buffer
      Log.e("GameFragment", "Insufficient space to extract $assetName. Required: $requiredBytes, Usable: $usableSpace")
      return null
    }

    val tempFile = File(appContext.filesDir, "$assetName.tmp")
    try {
      Log.i("GameFragment", "Extracting asset $assetName to internal storage...")
      appContext.assets.open(assetName).use { inputStream ->
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

  private suspend fun getAnalysis(bridge: IKataGoBridge, perspective: Stone = Stone.BLACK, visits: Int = 100): AnalysisResult = withContext(Dispatchers.IO) {
    // Always query analysis from Black's perspective so UI consistently displays Black winrate / scoreLead
    val response = bridge.sendGtpCommand("kata-get-analysis black $visits")
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

        // If no MCTS search visits have occurred yet for this position, extract top recommendations from raw neural network policy
        if (candidates.isEmpty() && json.has("policy")) {
          val rawBoard = bridge.boardState
          val policyArray = json.getJSONArray("policy")
          val policyCandidates = mutableListOf<CandidateMove>()
          val boardArea = boardSize * boardSize
          val limit = minOf(boardArea, policyArray.length())
          for (i in 0 until limit) {
            val prob = policyArray.getDouble(i)
            if (prob > 0.005) { // 0.5% threshold
              val x = i % boardSize
              val y = i / boardSize
              val isEmpty = rawBoard == null || rawBoard[y * boardSize + x] == 0
              if (isEmpty) {
                policyCandidates.add(CandidateMove(
                  x = x,
                  y = y,
                  winrate = winrate,
                  visits = (prob * 1000).toLong()
                ))
              }
            }
          }
          candidates.addAll(policyCandidates.sortedByDescending { it.visits }.take(5))
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
