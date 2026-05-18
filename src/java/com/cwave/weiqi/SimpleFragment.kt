package com.cwave.weiqi

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.cwave.weiqi.katago.KataGoBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SimpleFragment : Fragment() {
    private val bridge = KataGoBridge()

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
                        EngineTestScreen(bridge)
                    }
                }
            }
        }
    }

    @Composable
    fun EngineTestScreen(bridge: KataGoBridge) {
        var logText by remember { mutableStateOf("Engine Log:\n") }
        val scope = rememberCoroutineScope()
        val scrollState = rememberScrollState()

        LaunchedEffect(logText) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }

        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Text(text = "KataGo Android Test", style = MaterialTheme.typography.h5)
            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        logText += "Initializing engine...\n"
                        val result = initEngine()
                        logText += when (result) {
                            0 -> "Engine initialized!\n"
                            -1 -> "Error: Config file not found.\n"
                            -2 -> "Error: Model file not found.\n"
                            -3 -> "Error: Engine internal init failed.\n"
                            -4 -> "Error: Asset copy failed.\n"
                            -5 -> "Error: Exception during init.\n"
                            else -> "Error: Unknown error ($result).\n"
                        }
                    }
                }) {
                    Text("Init")
                }

                Button(onClick = {
                    scope.launch {
                        val response = withContext(Dispatchers.IO) {
                            bridge.sendGtpCommand("clear_board")
                        }
                        logText += "> clear_board\n$response\n"
                    }
                }) {
                    Text("New Game")
                }

                Button(onClick = {
                    logText = "Engine Log:\n"
                }) {
                    Text("Clear")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        val response = withContext(Dispatchers.IO) {
                            bridge.sendGtpCommand("version")
                        }
                        logText += "> version\n$response\n"
                    }
                }) {
                    Text("Version")
                }

                Button(onClick = {
                    scope.launch {
                        val response = withContext(Dispatchers.IO) {
                            bridge.sendGtpCommand("genmove black")
                        }
                        logText += "> genmove black\n$response\n"
                    }
                }) {
                    Text("Gen Black")
                }

                Button(onClick = {
                    scope.launch {
                        val response = withContext(Dispatchers.IO) {
                            bridge.sendGtpCommand("genmove white")
                        }
                        logText += "> genmove white\n$response\n"
                    }
                }) {
                    Text("Gen White")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                color = MaterialTheme.colors.surface,
                elevation = 2.dp,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = logText,
                    modifier = Modifier
                        .padding(8.dp)
                        .verticalScroll(scrollState),
                    style = MaterialTheme.typography.body2
                )
            }
        }
    }

    private suspend fun initEngine(): Int = withContext(Dispatchers.IO) {
        try {
            val configPath = copyAssetToFile("gtp.cfg")
            val modelPath = copyAssetToFile("g170-b30c320x2-s4824661760-d1229536699.bin.gz")
            
            if (configPath == null || modelPath == null) return@withContext -4

            bridge.init(configPath, modelPath)
        } catch (e: Exception) {
            -5
        }
    }

    private fun copyAssetToFile(assetName: String): String? {
        val file = File(requireContext().filesDir, assetName)
        try {
            requireContext().assets.open(assetName).use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            return file.absolutePath
        } catch (e: Exception) {
            return null
        }
    }
}
