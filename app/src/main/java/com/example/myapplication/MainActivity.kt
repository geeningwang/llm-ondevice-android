package com.example.myapplication

import android.os.Bundle
import android.os.Debug
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                ChatApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatApp(viewModel: ChatViewModel = viewModel()) {
    val messages by viewModel.messages.collectAsState()
    val modelState by viewModel.modelState.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val logs by viewModel.logs.collectAsState()
    
    var inputText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Gemma 3 1B-IT On-Device")
                        Text(
                            "Powered by MediaPipe v0.10.35",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                },
                navigationIcon = {
                    if (modelState is ModelState.Ready) {
                        IconButton(onClick = { viewModel.exitChat() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to main page"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (val state = modelState) {
                    is ModelState.Idle -> {
                        ModelSetupScreen(
                            onStart = { viewModel.downloadAndInit() },
                            onClearStorage = { viewModel.clearStorage() }
                        )
                    }
                    is ModelState.Downloading -> {
                        ProgressScreen("Downloading Model...", state.progress)
                    }
                    is ModelState.Initializing -> {
                        ProgressScreen("Initializing Model...", null)
                    }
                    is ModelState.Ready -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            SystemStatusPane(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            ChatScreen(
                                modifier = Modifier
                                    .weight(2f)
                                    .fillMaxWidth(),
                                messages = messages,
                                inputText = inputText,
                                onInputChange = { inputText = it },
                                onSend = {
                                    viewModel.sendMessage(inputText)
                                    inputText = ""
                                },
                                isGenerating = isGenerating
                            )
                        }
                    }
                    is ModelState.Error -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Error: ${state.message}",
                                color = Color.Red,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            if (state.message.contains("zip archive", ignoreCase = true) || state.message.contains("corrupted", ignoreCase = true) || state.message.contains("not a valid", ignoreCase = true)) {
                                Text(
                                    "The model file seems corrupted or incompatible with MediaPipe Android. Retrying will start a fresh download.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(bottom = 8.dp).padding(horizontal = 16.dp)
                                )
                            }
                            Button(onClick = { viewModel.downloadAndInit(forceRedownload = true) }) {
                                Text("Retry Download")
                            }
                            TextButton(
                                onClick = { viewModel.clearStorage() },
                                modifier = Modifier.padding(top = 8.dp),
                                colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                            ) {
                                Text("Clear Storage & Reset")
                            }
                        }
                }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            LogPanel(logs = logs)
        }
    }
}

/**
 * Always-visible log panel shown at the bottom of the screen so the user can see exactly what
 * the app is doing (checking for a cached model, downloading, verifying, initializing, errors,
 * chat generation, etc.) instead of just a spinner with no explanation.
 */
@Composable
fun LogPanel(logs: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp),
        color = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(8.dp)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .simpleVerticalScrollbar(listState)
        ) {
            items(logs) { line ->
                Text(
                    text = line,
                    color = Color(0xFF33FF66),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
fun ProgressScreen(label: String, progress: Float?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                Text("$label ${(progress * 100).toInt()}%")
            } else {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(label)
            }
        }
    }
}

@Composable
fun ModelSetupScreen(onStart: () -> Unit, onClearStorage: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Gemma 3 1B-IT Configuration", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Gemma 3 1B-IT is a lightweight edge model (~550MB, int4 quantized).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start")
        }
        Spacer(modifier = Modifier.height(24.dp))
        TextButton(
            onClick = onClearStorage,
            colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
        ) {
            Text("Clear Internal Storage")
        }
    }
}

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    isGenerating: Boolean,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Keep the view scrolled to the latest message as new messages arrive or stream in.
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .simpleVerticalScrollbar(listState),
            reverseLayout = false
        ) {
            items(messages) { message ->
                ChatBubble(message)
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask Gemma 3 1B-IT...") },
                enabled = !isGenerating
            )
            IconButton(
                onClick = onSend,
                enabled = !isGenerating && inputText.isNotBlank()
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

/**
 * Number of CPU clock ticks per second on Android (sysconf(_SC_CLK_TCK)). This value is fixed at
 * 100 on essentially all Android kernels/architectures, so it's safe to hard-code rather than
 * calling into native code to read it.
 */
private const val CLK_TCK = 100L

/** Reads this process's total CPU time (user + system) in clock ticks from /proc/self/stat. */
private fun readProcessCpuTicks(): Long {
    return try {
        val stat = File("/proc/self/stat").readText()
        // The 2nd field is "(comm)" and may itself contain spaces/parentheses, so find the *last*
        // ')' to reliably locate the start of the remaining, simple space-separated fields.
        val rest = stat.substring(stat.lastIndexOf(')') + 2)
        val fields = rest.split(" ")
        // Original /proc/[pid]/stat field numbering: 1=pid, 2=comm, 3=state, 4=ppid, ...,
        // 14=utime, 15=stime. `fields` starts at field 3 (index 0), so utime is index 11 and
        // stime is index 12.
        val utime = fields[11].toLong()
        val stime = fields[12].toLong()
        utime + stime
    } catch (e: Exception) {
        0L
    }
}

/**
 * Always-visible system status pane shown at the top of the chat screen (occupying roughly 1/3
 * of the available vertical space) so the user can see the live memory and CPU cost of running
 * the on-device model. Samples are taken once per second.
 */
@Composable
fun SystemStatusPane(modifier: Modifier = Modifier) {
    var totalPssMb by remember { mutableStateOf(0f) }
    var nativeHeapMb by remember { mutableStateOf(0f) }
    var cpuPercent by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        var lastTicks = readProcessCpuTicks()
        var lastWallMs = System.currentTimeMillis()
        while (isActive) {
            delay(1000)

            val memInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memInfo)
            totalPssMb = memInfo.totalPss / 1024f
            nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024f * 1024f)

            val ticks = readProcessCpuTicks()
            val wallMs = System.currentTimeMillis()
            val deltaTicks = ticks - lastTicks
            val deltaWallMs = wallMs - lastWallMs
            if (deltaWallMs > 0) {
                val deltaWallTicks = deltaWallMs * CLK_TCK / 1000.0
                cpuPercent = ((deltaTicks / deltaWallTicks) * 100)
                    .toFloat()
                    .coerceIn(0f, 100f * Runtime.getRuntime().availableProcessors())
            }
            lastTicks = ticks
            lastWallMs = wallMs
        }
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "System Status",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            StatusRow("CPU Usage", "%.1f%%".format(cpuPercent))
            StatusRow("Memory (PSS)", "%.1f MB".format(totalPssMb))
            StatusRow("Native Heap", "%.1f MB".format(nativeHeapMb))
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val alignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    val color = if (message.isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val contentColor = if (message.isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = alignment
    ) {
        Surface(
            color = color,
            contentColor = contentColor,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(8.dp),
                fontSize = 16.sp
            )
        }
    }
}

/**
 * Draws a lightweight vertical scrollbar thumb over a [LazyColumn]. Compose's [LazyColumn] has no
 * built-in visible scrollbar (unlike a traditional View-based scrolling container), so without
 * this the list is still scrollable by touch/drag, but there's no visual indicator of scroll
 * position. The thumb fades in while scrolling and fades out shortly after.
 */
private fun Modifier.simpleVerticalScrollbar(
    state: LazyListState,
    width: Dp = 4.dp
): Modifier = composed {
    val targetAlpha = if (state.isScrollInProgress) 0.8f else 0f
    val duration = if (state.isScrollInProgress) 150 else 500
    val alpha by animateFloatAsState(targetAlpha, animationSpec = tween(durationMillis = duration), label = "scrollbarAlpha")

    drawWithContent {
        drawContent()

        val layoutInfo = state.layoutInfo
        val totalItemsCount = layoutInfo.totalItemsCount
        val visibleItemsInfo = layoutInfo.visibleItemsInfo
        val firstVisibleElementIndex = visibleItemsInfo.firstOrNull()?.index

        if (alpha > 0f && firstVisibleElementIndex != null && totalItemsCount > 0) {
            val elementHeight = size.height / totalItemsCount
            val scrollbarOffsetY = firstVisibleElementIndex * elementHeight
            val scrollbarHeight = (visibleItemsInfo.size * elementHeight).coerceAtMost(size.height)

            drawRoundRect(
                color = Color.Gray,
                topLeft = Offset(size.width - width.toPx(), scrollbarOffsetY),
                size = Size(width.toPx(), scrollbarHeight),
                alpha = alpha,
                cornerRadius = CornerRadius(width.toPx() / 2, width.toPx() / 2)
            )
        }
    }
}
