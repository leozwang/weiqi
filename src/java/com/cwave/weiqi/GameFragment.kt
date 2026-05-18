package com.cwave.weiqi

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
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

  data class CandidateMove(
    val x: Int,
    val y: Int,
    val winrate: Double,
    val visits: Long
  )

  data class AnalysisResult(
    val winrate: Double = 0.5,
    val scoreLead: Double = 0.0,
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
    var menuExpanded by remember { mutableStateOf(false) }

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
                bridge.sendGtpCommand("think $colorStr 400")
                statusText = "Your turn."
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

    suspend fun handleAiMove(color: Stone) {
      isThinking = true
      statusText = "KataGo is thinking..."
      genAiMove(color, bridge) { aiX, aiY, aiMoveStr ->
        isThinking = false
        boardState = syncBoardState(bridge)
        if (aiX != -1 && aiY != -1) {
          lastMove = aiX to aiY
          playMoveSound()
          val colorStr = if (color == Stone.BLACK) "Black" else "White"
          lastMoveText = "$colorStr (AI) played $aiMoveStr"
          currentTurn = if (color == Stone.BLACK) Stone.WHITE else Stone.BLACK
          statusText = if (currentMode == GameMode.USER_BLACK || currentMode == GameMode.USER_WHITE) "Your turn." else "Next move..."
        } else if (aiMoveStr == "PASS") {
          lastMoveText = "AI passed."
          currentTurn = if (color == Stone.BLACK) Stone.WHITE else Stone.BLACK
          statusText = "AI passed."
        } else {
          statusText = "KataGo error."
        }
      }
    }

    LaunchedEffect(Unit) {
      statusText = "Tuning GPU (One-time setup, 1-2 mins)..."
      val result = initEngine()
      if (result == 0) {
        isEngineInitialized = true
        statusText = "Ready. Black's turn."

        if (currentMode == GameMode.USER_WHITE) {
          handleAiMove(Stone.BLACK)
        }
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
            Box {
              IconButton(onClick = { menuExpanded = true }) {
                Icon(
                  imageVector = Icons.Default.Settings, // Changed from Visibility to Settings
                  contentDescription = "Select Mode"
                )
              }
              DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
              ) {
                GameMode.values().forEach { mode ->
                  DropdownMenuItem(onClick = {
                    currentMode = mode
                    menuExpanded = false
                    aiAutoPlay = false
                    // If switching to Mode 2 (User is White) and board is empty, AI starts
                    if (mode == GameMode.USER_WHITE && boardState.all { row -> row.all { it == Stone.EMPTY } }) {
                      scope.launch { handleAiMove(Stone.BLACK) }
                    }
                  }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (currentMode == mode) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colors.primary,
                                modifier = Modifier.size(32.dp).padding(end = 8.dp)
                            )
                        } else {
                            Spacer(modifier = Modifier.size(40.dp)) // 32 + 8
                        }
                        Text(text = when(mode) {
                          GameMode.USER_BLACK -> "You are Black"
                          GameMode.USER_WHITE -> "You are White"
                          GameMode.USER_BOTH -> "You are Both"
                          GameMode.AI_BOTH -> "AI vs AI"
                        })
                    }
                  }
                }
              }
            }
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
                .height(130.dp)
                .padding(16.dp),
              contentAlignment = Alignment.Center
            ) {
              Card(
                modifier = Modifier.fillMaxSize(),
                elevation = 2.dp,
                shape = MaterialTheme.shapes.medium
              ) {
                Column(
                  modifier = Modifier.padding(8.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.Center
                ) {
                  Text(
                    text = statusText,
                    style = MaterialTheme.typography.subtitle1,
                    fontWeight = FontWeight.Bold
                  )

                  val winratePercent = (analysis.winrate * 100).toInt()
                  val scoreLeadFormatted = String.format("%.1f", abs(analysis.scoreLead))
                  val leadText = if (analysis.scoreLead >= 0) "B+$scoreLeadFormatted" else "W+$scoreLeadFormatted"

                  Text(
                    text = "Winrate: $winratePercent% | Lead: $leadText",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.primary
                  )

                  Text(
                    text = lastMoveText,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 2.dp)
                  )

                  Box(modifier = Modifier.height(8.dp).fillMaxWidth().padding(top = 4.dp)) {
                    if (isThinking) {
                      LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colors.secondary
                      )
                    }
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
                  .padding(horizontal = 4.dp)
                  .background(Color(0xFFDCB35C))
              ) {
                val boardMargin = 0.08f

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
                  val stoneRadius = (stepPx / 2) * 0.95f

                  // Draw Coordinates
                  val textPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = 10.dp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                  }
                  drawIntoCanvas { canvas ->
                    for (i in 0 until boardSize) {
                      val pos = marginPx + i * stepPx
                      val colLabel = toGtpCoords(i, 0).substring(0, 1)
                      canvas.nativeCanvas.drawText(colLabel, pos, marginPx * 0.4f, textPaint)
                      canvas.nativeCanvas.drawText(colLabel, pos, size.height - marginPx * 0.2f, textPaint)

                      val rowLabel = (19 - i).toString()
                      canvas.nativeCanvas.drawText(rowLabel, marginPx * 0.4f, pos + 4.dp.toPx(), textPaint)
                      canvas.nativeCanvas.drawText(rowLabel, size.width - marginPx * 0.4f, pos + 4.dp.toPx(), textPaint)
                    }
                  }

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
                        radius = 2.5.dp.toPx(),
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
                  drawCircle(
                    color = Color.Black.copy(alpha = 0.4f),
                    radius = stoneRadius,
                    center = Offset(centerX, centerY)
                  )
                  drawCircle(
                    color = Color.White.copy(alpha = 0.6f),
                    radius = stoneRadius * 0.4f,
                    center = Offset(centerX - stoneRadius * 0.3f, centerY - stoneRadius * 0.3f)
                  )
                  }
                }
              }
            }

            // Actions Row
            Row(
              modifier = Modifier.padding(16.dp).fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
              verticalAlignment = Alignment.CenterVertically
            ) {
              // New Game Button
              Button(
                onClick = {
                  scope.launch {
                    bridge.sendGtpCommand("clear_board")
                    boardState = syncBoardState(bridge)
                    previewMove = null
                    lastMove = null
                    analysis = AnalysisResult()
                    currentTurn = Stone.BLACK
                    statusText = "Board cleared. Black's turn."
                    lastMoveText = "No moves yet"
                    aiAutoPlay = false

                    if (currentMode == GameMode.USER_WHITE) {
                      handleAiMove(Stone.BLACK)
                    }
                  }
                },
                modifier = Modifier.height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                  backgroundColor = MaterialTheme.colors.secondary,
                  contentColor = MaterialTheme.colors.onSecondary
                )
              ) {
                Text("NEW GAME", fontWeight = FontWeight.Bold)
              }

              if (currentMode == GameMode.AI_BOTH) {
                // Autoplay Toggle for Mode 4
                Button(
                  onClick = {
                    aiAutoPlay = !aiAutoPlay
                  },
                  modifier = Modifier.size(80.dp),
                  shape = CircleShape,
                  colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (aiAutoPlay) Color.Red else Color.Green
                  )
                ) {
                  Text(if (aiAutoPlay) "STOP" else "AUTO", fontWeight = FontWeight.Bold)
                }

                LaunchedEffect(aiAutoPlay, currentTurn) {
                  if (aiAutoPlay && currentMode == GameMode.AI_BOTH && !isThinking) {
                    kotlinx.coroutines.delay(1000) // Small delay between AI moves
                    handleAiMove(currentTurn)
                  }
                }
              } else {
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
                          previewMove = null
                          lastMove = x to y
                          playMoveSound()
                          currentTurn = if (turnColor == Stone.BLACK) Stone.WHITE else Stone.BLACK

                          // Trigger AI if necessary
                          if (currentMode == GameMode.USER_BLACK && currentTurn == Stone.WHITE) {
                            scope.launch { handleAiMove(Stone.WHITE) }
                          } else if (currentMode == GameMode.USER_WHITE && currentTurn == Stone.BLACK) {
                            scope.launch { handleAiMove(Stone.BLACK) }
                          } else {
                            statusText = "Your turn."
                          }
                        } else {
                          statusText = "Illegal move at ${toGtpCoords(x, y)}"
                          previewMove = null
                        }
                      }
                    }
                  },
                  enabled = previewMove != null && !isThinking && (
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

            Spacer(modifier = Modifier.height(32.dp))
          }
    }
  }

  private suspend fun initEngine(): Int = withContext(Dispatchers.IO) {
    try {
      val configPath = copyAssetToFile("gtp.cfg")
      val modelPath = copyAssetToFile("model.bin.gz")
      if (configPath == null || modelPath == null) {
        Log.e("GameFragment", "Failed to extract assets: cfg=$configPath, model=$modelPath")
        return@withContext -4
      }

      Log.i("GameFragment", "Starting KataGo Engine Init...")
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
        val winrate = rootInfo.getDouble("winrate")
        val scoreLead = rootInfo.getDouble("scoreLead")

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
        Log.i("GameFragment", "Analysis received: winrate=$winrate, candidates=${topCandidates.size}")
        AnalysisResult(winrate, scoreLead, ownership, topCandidates)
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
