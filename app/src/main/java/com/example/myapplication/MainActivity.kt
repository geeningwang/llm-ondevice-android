package com.example.myapplication

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
    val tokensPerSecond by viewModel.tokensPerSecond.collectAsState()
    val isGpuAccelerated by viewModel.isGpuAccelerated.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    
    var inputText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("${selectedModel.displayName} On-Device")
                        Text(
                            if (selectedModel.backend == Backend.LITERT_LM) "Powered by LiteRT-LM v$LITERTLM_VERSION" else "Powered by MediaPipe v$MEDIAPIPE_VERSION",
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
            SystemStatusPane(
                tokensPerSecond = if (isGenerating) tokensPerSecond else 0f,
                isGpuAccelerated = isGpuAccelerated,
                isGenerating = isGenerating,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.weight(2f).fillMaxWidth()) {
                when (val state = modelState) {
                    is ModelState.Idle -> {
                        ModelSetupScreen(
                            models = viewModel.availableModels,
                            selectedModel = selectedModel,
                            onSelectModel = { viewModel.selectModel(it) },
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
                        ChatScreen(
                            modifier = Modifier.fillMaxSize(),
                            messages = messages,
                            inputText = inputText,
                            onInputChange = { inputText = it },
                            onSend = {
                                viewModel.sendMessage(inputText)
                                inputText = ""
                            },
                            isGenerating = isGenerating,
                            modelDisplayName = selectedModel.displayName
                        )
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
                            OutlinedButton(
                                onClick = { viewModel.clearStorage() },
                                modifier = Modifier.padding(top = 8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                                border = BorderStroke(1.dp, Color.Red)
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
fun ModelSetupScreen(
    models: List<ModelOption>,
    selectedModel: ModelOption,
    onSelectModel: (ModelOption) -> Unit,
    onStart: () -> Unit,
    onClearStorage: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Choose a Model", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            models.forEach { option ->
                ModelOptionRow(
                    option = option,
                    isSelected = option.id == selectedModel.id,
                    onClick = { onSelectModel(option) }
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            Text("Start")
        }
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedButton(
            onClick = onClearStorage,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
            border = BorderStroke(1.dp, Color.Red)
        ) {
            Text("Clear Internal Storage")
        }
    }
}

@Composable
fun ModelOptionRow(option: ModelOption, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = isSelected, onClick = onClick)
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    "${option.displayName} (~${option.approxSizeMb} MB)",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    option.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
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
    modelDisplayName: String = "the model",
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Keep the view scrolled to the bottom as new messages arrive or stream in. A plain
    // (non-animated) scrollToItem with a large scrollOffset is used rather than
    // animateScrollToItem: animateScrollToItem aligns the target item to the *top* of the
    // viewport, which for a long, still-growing streamed message means the newest text (at the
    // bottom of that bubble) stays scrolled out of view. scrollOffset = Int.MAX_VALUE is clamped
    // internally to the actual content size, reliably landing at the true bottom of the item.
    // Using the non-animated variant also avoids piling up overlapping animations across the many
    // rapid updates that happen while a response is streaming token by token.
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1, scrollOffset = Int.MAX_VALUE)
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
                placeholder = { Text("Ask $modelDisplayName...") },
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

data class SystemMetricSample(
    val cpuPercent: Float,
    val totalPssMb: Float,
    val nativeHeapMb: Float,
    val tokensPerSecond: Float = 0f,
    val gpuPercent: Float? = null
)

private const val MAX_METRIC_SAMPLES = 60

/** Reads sysfs GPU utilization percentage (Adreno / Mali) if accessible. */
private fun readGpuLoadPercent(): Float? {
    try {
        val file = File("/sys/class/kgsl/kgsl-3d0/gpubusy")
        if (file.exists()) {
            val parts = file.readText().trim().split("\\s+".toRegex())
            if (parts.size >= 2) {
                val busy = parts[0].toFloat()
                val total = parts[1].toFloat()
                if (total > 0) {
                    return ((busy / total) * 100f).coerceIn(0f, 100f)
                }
            }
        }
    } catch (e: Exception) {}

    try {
        val devfreqDir = File("/sys/class/devfreq")
        if (devfreqDir.exists()) {
            devfreqDir.listFiles()?.forEach { dir ->
                if (dir.name.contains("gpu", ignoreCase = true) ||
                    dir.name.contains("kgsl", ignoreCase = true) ||
                    dir.name.contains("mali", ignoreCase = true)
                ) {
                    val loadFile = File(dir, "load")
                    if (loadFile.exists()) {
                        val text = loadFile.readText().trim()
                        val percent = text.split("@")[0].toFloatOrNull()
                        if (percent != null) return percent.coerceIn(0f, 100f)
                    }
                }
            }
        }
    } catch (e: Exception) {}

    return null
}

/** Reads current system thermal status on Android 10+ (API 29+). */
private fun readThermalStatusText(context: Context): String {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
        return when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light Heat"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Throttled!"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical!"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency!"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown!"
            else -> "Normal"
        }
    }
    return "Normal"
}

/**
 * Always-visible system status pane shown at the top of the chat screen (occupying roughly 1/3
 * of the available vertical space) so the user can see live memory, CPU cost, GPU state, thermal
 * status, and token generation speed along with a historical line chart. Samples are taken once
 * per second.
 */
@Composable
fun SystemStatusPane(
    tokensPerSecond: Float = 0f,
    isGpuAccelerated: Boolean = false,
    isGenerating: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var totalPssMb by remember { mutableStateOf(0f) }
    var nativeHeapMb by remember { mutableStateOf(0f) }
    var cpuPercent by remember { mutableStateOf(0f) }
    var gpuLoadPercent by remember { mutableStateOf<Float?>(null) }
    var thermalText by remember { mutableStateOf("Normal") }
    var history by remember { mutableStateOf(listOf<SystemMetricSample>()) }

    val currentTokSec by rememberUpdatedState(tokensPerSecond)

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

            val gpuLoad = readGpuLoadPercent()
            gpuLoadPercent = gpuLoad
            thermalText = readThermalStatusText(context)

            val sample = SystemMetricSample(
                cpuPercent = cpuPercent,
                totalPssMb = totalPssMb,
                nativeHeapMb = nativeHeapMb,
                tokensPerSecond = currentTokSec,
                gpuPercent = gpuLoad
            )
            history = (history + sample).takeLast(MAX_METRIC_SAMPLES)
        }
    }

    val cpuColor = Color(0xFFFF9800)      // Orange
    val pssColor = Color(0xFF10B981)      // Green
    val nativeColor = Color(0xFF8B5CF6)   // Purple
    val tokenColor = Color(0xFF06B6D4)    // Cyan/Teal
    val gpuColor = Color(0xFFEC4899)      // Pink/Rose
    val thermalColor = Color(0xFFEAB308)  // Amber/Yellow

    val gpuValueText = when {
        !isGpuAccelerated -> "Disabled (CPU)"
        gpuLoadPercent != null -> "%.1f%%".format(gpuLoadPercent)
        isGenerating -> "GPU (Active)"
        else -> "GPU (Idle)"
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "System Status",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "60s history",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            
            // 3x2 grid layout for status indicators
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatusCell("CPU Usage", "%.1f%%".format(cpuPercent), cpuColor, Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusCell("Memory (PSS)", "%.1f MB".format(totalPssMb), pssColor, Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatusCell("Native Heap", "%.1f MB".format(nativeHeapMb), nativeColor, Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusCell("Tokens/s", "%.1f tok/s".format(tokensPerSecond), tokenColor, Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatusCell("GPU Status", gpuValueText, gpuColor, Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusCell("Thermal", thermalText, thermalColor, Modifier.weight(1f))
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            SystemStatusChart(
                history = history,
                cpuColor = cpuColor,
                pssColor = pssColor,
                nativeColor = nativeColor,
                tokenColor = tokenColor,
                gpuColor = gpuColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@Composable
private fun StatusCell(
    label: String,
    value: String,
    indicatorColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Surface(
                modifier = Modifier.size(7.dp),
                shape = CircleShape,
                color = indicatorColor
            ) {}
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
fun SystemStatusChart(
    history: List<SystemMetricSample>,
    cpuColor: Color,
    pssColor: Color,
    nativeColor: Color,
    tokenColor: Color,
    gpuColor: Color = Color(0xFFEC4899),
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        if (width <= 0f || height <= 0f) return@Canvas

        val topPadding = 4.dp.toPx()
        val bottomPadding = 4.dp.toPx()
        val chartHeight = height - topPadding - bottomPadding

        if (chartHeight <= 0f) return@Canvas

        // Draw horizontal grid lines
        val gridColor = Color.Gray.copy(alpha = 0.2f)
        val gridLineCount = 3
        for (i in 0..gridLineCount) {
            val y = topPadding + chartHeight * (i.toFloat() / gridLineCount)
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 1.dp.toPx()
            )
        }

        if (history.size < 2) return@Canvas

        val maxCpu = maxOf(100f, history.maxOfOrNull { it.cpuPercent } ?: 100f)
        val maxMem = maxOf(500f, (history.maxOfOrNull { maxOf(it.totalPssMb, it.nativeHeapMb) } ?: 500f) * 1.15f)
        val maxTokSec = maxOf(30f, (history.maxOfOrNull { it.tokensPerSecond } ?: 30f) * 1.2f)

        fun getX(index: Int): Float {
            return (index.toFloat() / (MAX_METRIC_SAMPLES - 1)) * width
        }

        fun getCpuY(cpu: Float): Float {
            val norm = (cpu / maxCpu).coerceIn(0f, 1f)
            return topPadding + chartHeight * (1f - norm)
        }

        fun getMemY(memMb: Float): Float {
            val norm = (memMb / maxMem).coerceIn(0f, 1f)
            return topPadding + chartHeight * (1f - norm)
        }

        fun getTokY(tokSec: Float): Float {
            val norm = (tokSec / maxTokSec).coerceIn(0f, 1f)
            return topPadding + chartHeight * (1f - norm)
        }

        val cpuPath = Path()
        val pssPath = Path()
        val nativePath = Path()
        val tokenPath = Path()
        val gpuPath = Path()
        val hasGpuData = history.any { it.gpuPercent != null }

        history.forEachIndexed { i, sample ->
            val x = getX(i)
            val cpuY = getCpuY(sample.cpuPercent)
            val pssY = getMemY(sample.totalPssMb)
            val nativeY = getMemY(sample.nativeHeapMb)
            val tokY = getTokY(sample.tokensPerSecond)
            val gpuY = getCpuY(sample.gpuPercent ?: 0f)

            if (i == 0) {
                cpuPath.moveTo(x, cpuY)
                pssPath.moveTo(x, pssY)
                nativePath.moveTo(x, nativeY)
                tokenPath.moveTo(x, tokY)
                if (sample.gpuPercent != null) gpuPath.moveTo(x, gpuY)
            } else {
                cpuPath.lineTo(x, cpuY)
                pssPath.lineTo(x, pssY)
                nativePath.lineTo(x, nativeY)
                tokenPath.lineTo(x, tokY)
                if (sample.gpuPercent != null) gpuPath.lineTo(x, gpuY)
            }
        }

        val strokeWidth = 2.dp.toPx()

        // Translucent gradient fill under the CPU path
        val firstX = getX(0)
        val lastX = getX(history.size - 1)
        val cpuFillPath = Path().apply {
            addPath(cpuPath)
            lineTo(lastX, topPadding + chartHeight)
            lineTo(firstX, topPadding + chartHeight)
            close()
        }
        drawPath(
            path = cpuFillPath,
            brush = Brush.verticalGradient(
                colors = listOf(cpuColor.copy(alpha = 0.2f), cpuColor.copy(alpha = 0.0f)),
                startY = topPadding,
                endY = topPadding + chartHeight
            )
        )

        // Draw CPU, PSS, Native Heap, Tokens/s, and GPU lines
        drawPath(
            path = cpuPath,
            color = cpuColor,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            path = pssPath,
            color = pssColor,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            path = nativePath,
            color = nativeColor,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            path = tokenPath,
            color = tokenColor,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        if (hasGpuData) {
            drawPath(
                path = gpuPath,
                color = gpuColor,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // Draw current sample dots at latest point
        val latestIndex = history.size - 1
        val latestSample = history[latestIndex]
        val latestX = getX(latestIndex)
        val dotRadius = 3.5.dp.toPx()

        latestSample.gpuPercent?.let { gpuLoad ->
            drawCircle(color = gpuColor, radius = dotRadius, center = Offset(latestX, getCpuY(gpuLoad)))
        }
        drawCircle(color = tokenColor, radius = dotRadius, center = Offset(latestX, getTokY(latestSample.tokensPerSecond)))
        drawCircle(color = nativeColor, radius = dotRadius, center = Offset(latestX, getMemY(latestSample.nativeHeapMb)))
        drawCircle(color = pssColor, radius = dotRadius, center = Offset(latestX, getMemY(latestSample.totalPssMb)))
        drawCircle(color = cpuColor, radius = dotRadius, center = Offset(latestX, getCpuY(latestSample.cpuPercent)))
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
