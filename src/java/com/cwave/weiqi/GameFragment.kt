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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
import androidx.fragment.app.Fragment
import com.cwave.weiqi.katago.KataGoBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

class GameFragment : Fragment() {
  private val bridge = KataGoBridge()
  private val boardSize = 19

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
          Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colors.background
          ) {
            GameScreen(bridge)
          }
        }
      }
    }
  }

  @Composable
  fun GameScreen(bridge: KataGoBridge) {
    val scope = rememberCoroutineScope()
    var boardState by remember { mutableStateOf(Array(boardSize) { Array(boardSize) { Stone.EMPTY } }) }
    var previewMove by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var lastMove by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var analysis by remember { mutableStateOf(AnalysisResult()) }
    var showAnalysis by remember { mutableStateOf(false) }
    var isEngineInitialized by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Initializing engine...") }
    var lastMoveText by remember { mutableStateOf("No moves yet") }
    var isThinking by remember { mutableStateOf(false) }
    var currentMode by remember { mutableStateOf(GameMode.USER_BLACK) }
    var currentTurn by remember { mutableStateOf(Stone.BLACK) }
    var aiAutoPlay by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var handicap by remember { mutableStateOf(0) }
    var currentModelName by remember { mutableStateOf("model.bin.gz") }
    var currentVisits by remember { mutableStateOf(1000) }

    var moveHistory by remember { mutableStateOf(listOf<String>()) }
    var historyIndex by remember { mutableStateOf(-1) }
    var consecutivePasses by remember { mutableStateOf(0) }
    var finalScoreText by remember { mutableStateOf<String?>(null) }

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
                // We use a local busy check if needed, but since we are in a LaunchedEffect 
                // keyed by currentTurn, it won't run multiple times for the same turn.
                statusText = "Analyzing position..."
                // Use a fraction of currentVisits for analysis to keep it fast
                val analysisVisits = (currentVisits * 0.4).toInt().coerceIn(100, 1000)
                bridge.sendGtpCommand("think $colorStr $analysisVisits")
                statusText = "Turn."
            }
        }
        
        if (!isThinking) {
            analysis = getAnalysis(bridge, currentTurn)
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
      soundId.value = soundPool.load(context, R.raw.place_stone, 1)
    }

    fun playMoveSound() {
      soundId.value?.let { id ->
        soundPool.play(id, 1f, 1f, 0, 0, 1f)
      }
    }

    suspend fun checkGameEnd() {
      if (consecutivePasses >= 2) {
        statusText = "Game ended by two passes. Scoring..."
        val score = bridge.sendGtpCommand("final_score")
        if (score.startsWith("=")) {
          finalScoreText = score.substring(1).trim()
          statusText = "Game Over. Result: $finalScoreText"
        }
      }
    }

    suspend fun handleAiMove(color: Stone) {
      isThinking = true
      statusText = "AI is thinking..."
      genAiMove(color, bridge) { aiX, aiY, aiMoveStr ->
        isThinking = false
        boardState = syncBoardState(bridge)
        if (aiX != -1 && aiY != -1) {
          lastMove = aiX to aiY
          playMoveSound()
          val colorStr = if (color == Stone.BLACK) "Black" else "White"
          lastMoveText = "$colorStr (AI) played $aiMoveStr"
          
          // Update history
          moveHistory = moveHistory.take(historyIndex + 1) + aiMoveStr
          historyIndex++
          consecutivePasses = 0
          
          currentTurn = if (color == Stone.BLACK) Stone.WHITE else Stone.BLACK
          statusText = "Turn."
        } else if (aiMoveStr == "PASS") {
          lastMoveText = "AI passed."
          consecutivePasses++
          currentTurn = if (color == Stone.BLACK) Stone.WHITE else Stone.BLACK
          statusText = "Turn."
          scope.launch { checkGameEnd() }
        } else if (aiMoveStr.lowercase() == "resign") {
            val winner = if (color == Stone.BLACK) "White" else "Black"
            statusText = "AI Resigned. $winner wins!"
            lastMoveText = "AI Resigned."
            finalScoreText = "$winner wins by resignation"
        } else {
          statusText = "AI error."
        }
      }
    }

    suspend fun startNewGame(mode: GameMode, h: Int, m: String, v: Int) {
      isThinking = true
      
      if (m != currentModelName) {
        statusText = "Re-initializing engine with $m..."
        bridge.shutdown()
        val res = initEngine(m)
        if (res != 0) {
          statusText = "Engine Init Failed: $res"
          isThinking = false
          return
        }
        currentModelName = m
      }

      statusText = "Starting new game..."
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
      aiAutoPlay = false
      isThinking = false
      
      // KataGo sets turn to White after handicap
      currentTurn = if (h > 0) Stone.WHITE else Stone.BLACK
      statusText = "Turn."
      lastMoveText = "No moves yet"

      if (currentMode == GameMode.USER_WHITE || (currentMode == GameMode.AI_BOTH) || (h > 0 && currentMode == GameMode.USER_BLACK)) {
        handleAiMove(currentTurn)
      }
    }

    LaunchedEffect(Unit) {
      statusText = "Tuning GPU (One-time setup, 1-2 mins)..."
      val result = initEngine(currentModelName)
      if (result == 0) {
        isEngineInitialized = true
        statusText = "Engine ready."
        showSettings = true
      } else {
        statusText = "Engine Init Failed: $result"
      }
    }

    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text("Go Game") },
          backgroundColor = MaterialTheme.colors.primary,
          contentColor = MaterialTheme.colors.onPrimary,
          elevation = 4.dp,
          actions = {
            IconButton(onClick = { showAnalysis = !showAnalysis }) {
              Icon(
                imageVector = if (showAnalysis) Icons.Default.Visibility
                else Icons.Default.VisibilityOff,
                contentDescription = "Toggle AI Analysis"
              )
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
                  verticalArrangement = Arrangement.Center
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

                    if (showAnalysis && finalScoreText == null) {
                      val winratePercent = (analysis.winrate * 100).toInt()
                      val scoreLeadFormatted = String.format("%.1f", abs(analysis.scoreLead))
                      // scoreLead in JSON is now always relative to Black (positive = Black leads)
                      val leadColor = if (analysis.scoreLead >= 0) "B" else "W"
                      val leadText = "$leadColor+$scoreLeadFormatted"

                      Spacer(Modifier.width(12.dp))
                      Divider(modifier = Modifier.height(20.dp).width(1.dp), color = Color.Gray.copy(alpha = 0.5f))
                      Spacer(Modifier.width(12.dp))

                      Text(
                        text = "$winratePercent% | $leadText pts",
                        style = MaterialTheme.typography.h5,
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

                  if (showAnalysis) {
                    // Draw Ownership Dots (Territory Analysis)
                    for (row in 0 until boardSize) {
                      for (col in 0 until boardSize) {
                        val score = analysis.ownership[row * boardSize + col]
                        if (abs(score) > 0.1) {
                          val centerX = marginPx + col * stepPx
                          val centerY = marginPx + row * stepPx
                          drawCircle(
                            color = if (score > 0) Color.Black.copy(alpha = (score * 0.4).toFloat()) 
                            else Color.White.copy(alpha = (-score * 0.4).toFloat()),
                              radius = 3.dp.toPx(),
                            center = Offset(centerX, centerY)
                          )
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
                            center = Offset(centerX, centerY),
                            style = Stroke(width = 1.5.dp.toPx())
                          )
                        }
                      }
                    }
                  }

                  // Draw Preview Stone (Ghost Stone)
                  previewMove?.let { (px, py) ->
                    val centerX = marginPx + px * stepPx
                    val centerY = marginPx + py * stepPx
                    val previewColor = if (currentTurn == Stone.BLACK) Color.Black else Color.White
                    val previewAlpha = if (currentTurn == Stone.BLACK) 0.45f else 0.75f
                    
                    drawCircle(
                      color = previewColor.copy(alpha = previewAlpha),
                      radius = stoneRadius,
                      center = Offset(centerX, centerY)
                    )

                    // Add a distinct border for the preview stone
                    drawCircle(
                      color = if (currentTurn == Stone.BLACK) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.8f),
                      radius = stoneRadius,
                      center = Offset(centerX, centerY),
                      style = Stroke(width = 1.5.dp.toPx())
                    )
                    
                    // Highlight
                    drawCircle(
                      color = (if (currentTurn == Stone.BLACK) Color.White else Color.Black).copy(alpha = 0.3f),
                      radius = stoneRadius * 0.4f,
                      center = Offset(centerX - stoneRadius * 0.3f, centerY - stoneRadius * 0.3f)
                    )
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
                // Autoplay Toggle for Mode 4
                Button(
                  onClick = { aiAutoPlay = !aiAutoPlay },
                  modifier = Modifier.size(80.dp),
                  shape = CircleShape,
                  colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (aiAutoPlay) Color.Red else Color.Green
                  ),
                  elevation = ButtonDefaults.elevation(6.dp)
                ) {
                  Text(if (aiAutoPlay) "STOP" else "AUTO", fontWeight = FontWeight.Bold)
                }

                LaunchedEffect(aiAutoPlay, currentTurn) {
                  if (aiAutoPlay && currentMode == GameMode.AI_BOTH && !isThinking) {
                    kotlinx.coroutines.delay(1000)
                    handleAiMove(currentTurn)
                  }
                }
              } else {
                // Pass Button
                Button(
                  onClick = {
                    scope.launch {
                        val color = if (currentTurn == Stone.BLACK) "black" else "white"
                        bridge.sendGtpCommand("play $color pass")
                        consecutivePasses++
                        val colorStr = if (currentTurn == Stone.BLACK) "Black" else "White"
                        lastMoveText = "$colorStr passed."
                        currentTurn = if (currentTurn == Stone.BLACK) Stone.WHITE else Stone.BLACK
                        
                        checkGameEnd()

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
                  Text("PASS", fontWeight = FontWeight.Bold)
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
                          moveHistory = moveHistory.take(historyIndex + 1) + moveStr
                          historyIndex++
                          consecutivePasses = 0
                          previewMove = null
                          lastMove = x to y
                          playMoveSound()
                          currentTurn = if (turnColor == Stone.BLACK) Stone.WHITE else Stone.BLACK
                          if (currentMode == GameMode.USER_BLACK && currentTurn == Stone.WHITE) {
                            scope.launch { handleAiMove(Stone.WHITE) }
                          } else if (currentMode == GameMode.USER_WHITE && currentTurn == Stone.BLACK) {
                            scope.launch { handleAiMove(Stone.BLACK) }
                          } else {
                            statusText = "Turn."
                          }
                        } else {
                          statusText = "Illegal move at ${toGtpCoords(x, y)}"
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
                  Text("PLACE", fontWeight = FontWeight.Bold)
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
                      historyIndex--
                      boardState = syncBoardState(bridge)
                      currentTurn = if (currentTurn == Stone.BLACK) Stone.WHITE else Stone.BLACK
                      lastMove = if (historyIndex >= 0) fromGtpCoords(moveHistory[historyIndex]) else null
                      lastMoveText = if (historyIndex >= 0) "Undone. Last move: ${moveHistory[historyIndex]}" else "Undone to start"
                      previewMove = null
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
                Text("NEW GAME", fontWeight = FontWeight.Bold)
              }

              // Redo Button
              IconButton(
                onClick = {
                  if (historyIndex < moveHistory.size - 1) {
                    scope.launch {
                      historyIndex++
                      val moveStr = moveHistory[historyIndex]
                      val color = if (currentTurn == Stone.BLACK) "black" else "white"
                      bridge.sendGtpCommand("play $color $moveStr")
                      boardState = syncBoardState(bridge)
                      lastMove = fromGtpCoords(moveStr)
                      lastMoveText = "Redone: $moveStr"
                      currentTurn = if (currentTurn == Stone.BLACK) Stone.WHITE else Stone.BLACK
                      previewMove = null
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
            AlertDialog(
              onDismissRequest = { if (isEngineInitialized) showSettings = false },
              shape = RoundedCornerShape(24.dp),
              title = {
                Text(
                  "New Game",
                  style = MaterialTheme.typography.h5,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.padding(bottom = 8.dp)
                )
              },
              text = {
                Column(
                  modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                ) {
                  // --- Model Selection ---
                  Text(
                    "AI ENGINE",
                    style = MaterialTheme.typography.overline,
                    color = MaterialTheme.colors.primary,
                    fontWeight = FontWeight.Bold
                  )
                  Spacer(Modifier.height(8.dp))
                  
                  Surface(
                    modifier = Modifier
                      .fillMaxWidth()
                      .clip(RoundedCornerShape(12.dp))
                      .pointerInput(Unit) {
                        detectTapGestures { currentModelName = "model.bin.gz" }
                      },
                    color = if (currentModelName == "model.bin.gz") MaterialTheme.colors.primary.copy(alpha = 0.08f) else Color.Transparent,
                    border = BorderStroke(
                      width = if (currentModelName == "model.bin.gz") 2.dp else 1.dp,
                      color = if (currentModelName == "model.bin.gz") MaterialTheme.colors.primary else Color.LightGray.copy(alpha = 0.5f)
                    )
                  ) {
                    Row(
                      modifier = Modifier.padding(16.dp),
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      Icon(
                        Icons.Default.Memory,
                        contentDescription = null,
                        tint = if (currentModelName == "model.bin.gz") MaterialTheme.colors.primary else Color.Gray,
                        modifier = Modifier.size(32.dp)
                      )
                      Column(modifier = Modifier.padding(start = 16.dp)) {
                        Text(
                          "Mobile (10b)",
                          style = MaterialTheme.typography.subtitle1,
                          fontWeight = FontWeight.Bold,
                          color = if (currentModelName == "model.bin.gz") MaterialTheme.colors.primary else MaterialTheme.colors.onSurface
                        )
                        Text("Fast, balanced performance", style = MaterialTheme.typography.caption)
                      }
                      Spacer(Modifier.weight(1f))
                      RadioButton(
                        selected = currentModelName == "model.bin.gz",
                        onClick = { currentModelName = "model.bin.gz" },
                        colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colors.primary)
                      )
                    }
                  }

                  Spacer(modifier = Modifier.height(24.dp))

                  // --- Handicap Selection ---
                  Text(
                    "HANDICAP",
                    style = MaterialTheme.typography.overline,
                    color = MaterialTheme.colors.primary,
                    fontWeight = FontWeight.Bold
                  )
                  Spacer(Modifier.height(8.dp))
                  
                  androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
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
                            text = if (h == 0) "None" else h.toString(),
                            color = if (isSelected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
                            style = MaterialTheme.typography.button,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                          )
                        }
                      }
                    }
                  }

                  Spacer(modifier = Modifier.height(24.dp))

                  // --- AI Strength Selection ---
                  Text(
                    "AI STRENGTH",
                    style = MaterialTheme.typography.overline,
                    color = MaterialTheme.colors.primary,
                    fontWeight = FontWeight.Bold
                  )
                  Spacer(Modifier.height(8.dp))
                  
                  val levels = listOf(
                    "Easy" to 100,
                    "Amateur" to 500,
                    "Advanced" to 1000,
                    "Pro" to 2500
                  )
                  
                  androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                  ) {
                    items(levels.size) { index ->
                      val (label, v) = levels[index]
                      val isSelected = currentVisits == v
                      Surface(
                        modifier = Modifier
                          .size(width = 100.dp, height = 56.dp)
                          .clip(RoundedCornerShape(16.dp))
                          .pointerInput(v) {
                            detectTapGestures { currentVisits = v }
                          },
                        color = if (isSelected) MaterialTheme.colors.primary else MaterialTheme.colors.surface,
                        elevation = if (isSelected) 6.dp else 0.dp,
                        border = if (isSelected) null else BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.6f))
                      ) {
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
                            text = "$v visits",
                            color = if (isSelected) MaterialTheme.colors.onPrimary.copy(alpha = 0.8f) else Color.Gray,
                            style = MaterialTheme.typography.caption
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
                          GameMode.USER_BLACK -> Triple("You are Black", "Traditional game", Icons.Default.Person)
                          GameMode.USER_WHITE -> Triple("You are White", "Play with Handicap", Icons.Default.Person)
                          GameMode.USER_BOTH -> Triple("Two Players", "Local multiplayer", Icons.Default.Groups)
                          GameMode.AI_BOTH -> Triple("AI vs AI", "Engine self-play", Icons.Default.SmartToy)
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
                }
              },
              confirmButton = {
                Button(
                  onClick = {
                    showSettings = false
                    scope.launch {
                      startNewGame(currentMode, handicap, currentModelName, currentVisits)
                    }
                  },
                  shape = RoundedCornerShape(24.dp),
                  modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth()
                    .height(48.dp)
                ) {
                  Text("START GAME", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
              },
              dismissButton = {
                if (isEngineInitialized) {
                  TextButton(
                    onClick = { showSettings = false },
                    modifier = Modifier.padding(8.dp)
                  ) {
                    Text("CANCEL", color = Color.Gray)
                  }
                }
              }
            )
          }

          if (finalScoreText != null) {
            AlertDialog(
                onDismissRequest = { /* Don't dismiss by clicking outside */ },
                title = { Text("Game Over", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text("The game has ended.", style = MaterialTheme.typography.body1)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Result: ${finalScoreText}",
                            style = MaterialTheme.typography.h6,
                            color = MaterialTheme.colors.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            finalScoreText = null
                            showSettings = true
                        }
                    ) {
                        Text("NEW GAME")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { finalScoreText = null }) {
                        Text("CLOSE")
                    }
                }
            )
          }
    }
  }

  private suspend fun initEngine(modelName: String): Int = withContext(Dispatchers.IO) {
    try {
      val configPath = copyAssetToFile("gtp.cfg")
      val modelPath = copyAssetToFile(modelName)
      if (configPath == null || modelPath == null) {
        Log.e("GameFragment", "Failed to extract assets: cfg=$configPath, model=$modelPath")
        return@withContext -4
      }

      Log.i("GameFragment", "Starting KataGo Engine Init with model $modelName...")
      val result = bridge.init(configPath, modelPath)
      Log.i("GameFragment", "Engine Init Result: $result")
      result
    } catch (e: Exception) {
      Log.e("GameFragment", "Engine Init Exception", e)
      -5
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

    val tempFile = File(requireContext().filesDir, "$assetName.tmp")
    try {
      Log.i("GameFragment", "Extracting asset $assetName to internal storage...")
      requireContext().assets.open(assetName).use { inputStream ->
        FileOutputStream(tempFile).use { outputStream ->
          inputStream.copyTo(outputStream)
        }
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

  private suspend fun playMove(x: Int, y: Int, stone: Stone, bridge: KataGoBridge, onResult: (Boolean, String) -> Unit) {
    val color = if (stone == Stone.BLACK) "black" else "white"
    val moveStr = toGtpCoords(x, y)
    val response = withContext(Dispatchers.IO) {
      bridge.sendGtpCommand("play $color $moveStr")
    }
    onResult(response.startsWith("="), moveStr)
  }

  private suspend fun genAiMove(stone: Stone, bridge: KataGoBridge, onResult: (Int, Int, String) -> Unit) {
    val color = if (stone == Stone.BLACK) "black" else "white"
    val response = withContext(Dispatchers.IO) {
      bridge.sendGtpCommand("genmove $color")
    }
    if (response.startsWith("=")) {
      val parts = response.split(" ")
      if (parts.size >= 2) {
        val moveStr = parts[1]
        if (moveStr.uppercase() == "PASS") {
          onResult(-1, -1, "PASS")
        } else {
          val (x, y) = fromGtpCoords(response)
          onResult(x, y, moveStr)
        }
      }
    } else {
      onResult(-1, -1, "ERROR")
    }
  }

  private fun syncBoardState(bridge: KataGoBridge): Array<Array<Stone>> {
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

  private suspend fun getAnalysis(bridge: KataGoBridge, perspective: Stone): AnalysisResult = withContext(Dispatchers.IO) {
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
