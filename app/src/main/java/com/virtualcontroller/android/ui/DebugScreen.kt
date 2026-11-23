package com.virtualcontroller.android.ui

import android.content.Context
import android.view.InputDevice
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.virtualcontroller.android.R
import com.virtualcontroller.android.uinput.GamepadProfile
import com.virtualcontroller.android.uinput.UInputGamepad
import com.virtualcontroller.android.parser.Buttons
import com.virtualcontroller.android.parser.SteamControllerState
import com.virtualcontroller.android.service.ControllerService
import com.virtualcontroller.android.ui.component.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import rikka.shizuku.Shizuku

/** Live controller state — for reference only, not interactive. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val capture = remember(context) { RawEvdevCapture(context) }
    val captureStatus by capture.status.collectAsState()
    val captureEvents by capture.events.collectAsState()
    val clipboard = LocalClipboardManager.current
    var availablePads by remember { mutableStateOf(listInputGamepads()) }
    var selectedPad by remember { mutableStateOf(availablePads.firstOrNull()?.name.orEmpty()) }
    DisposableEffect(capture) { onDispose { capture.stop() } }

    val s by ControllerService.stateFlow.collectAsState()
    val raw by ControllerService.rawReportFlow.collectAsState()

    // Report rate, averaged over 30 frames so the number is readable.
    var hz by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var last = 0L; var count = 0; var accum = 0L
        ControllerService.stateFlow.filterNotNull().collect {
            val now = System.currentTimeMillis()
            if (last != 0L) {
                accum += now - last
                if (++count >= 30) {
                    hz = (1000 / (accum / count).coerceAtLeast(1)).toInt()
                    count = 0; accum = 0
                }
            }
            last = now
        }
    }

    val state = s ?: SteamControllerState()

    // Nothing here is clickable, so without this the D-pad has nothing to move to and a TV
    // remote could never scroll past the first card.
    val readOnlyFocus = Modifier.focusRing(SectionShape).focusable()

    @Composable
    fun Chips(vararg chips: Pair<String, Int>) =
        FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { (label, mask) -> Chip(label, state.isButtonPressed(mask)) }
        }

    Column(
        Modifier.centeredContent().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenHeader(stringResource(R.string.debug_title), subtitle = stringResource(R.string.debug_readonly_notice), onBack = onBack) {
            Pill("$hz Hz")
        }

        SectionTitle("USB input capture")
        SectionCard(readOnlyFocus) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Choose the connected Ucom device. Capture briefly grabs only that device and lists the raw button and stick events Android reports.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { availablePads = listInputGamepads(); if (selectedPad !in availablePads.map { it.name }) selectedPad = availablePads.firstOrNull()?.name.orEmpty() }) {
                        Text("Refresh")
                    }
                }
                if (availablePads.isEmpty()) {
                    Text("No gamepad input device found. Keep the Ucom connected and refresh.", color = MaterialTheme.colorScheme.error)
                } else {
                    availablePads.forEach { pad ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedPad == pad.name, onClick = { selectedPad = pad.name })
                            Column {
                                Text(pad.name, style = MaterialTheme.typography.titleSmall)
                                Text("USB ${pad.vendorId.toString(16).uppercase()}:${pad.productId.toString(16).uppercase()}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                val isCapturing = captureStatus.startsWith("Capturing") || captureStatus.startsWith("Starting")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = selectedPad.isNotBlank() && !isCapturing, onClick = { capture.start(selectedPad) }) { Text("Start capture") }
                    OutlinedButton(enabled = isCapturing, onClick = { capture.stop() }) { Text("Stop") }
                    OutlinedButton(enabled = captureEvents.isNotEmpty(), onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(captureEvents.joinToString("\n")))
                    }) { Text("Copy") }
                }
                Text(captureStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (captureEvents.isNotEmpty()) {
                    HorizontalDivider()
                    captureEvents.asReversed().take(80).forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                } else if (isCapturing) {
                    Text("No raw events yet. Press a button or move a stick.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        SectionTitle("Buttons", painterResource(R.drawable.ic_gamepad))
        SectionCard(readOnlyFocus) {
            Chips("A" to Buttons.A, "B" to Buttons.B, "X" to Buttons.X, "Y" to Buttons.Y,
                "▲" to Buttons.DPAD_UP, "▼" to Buttons.DPAD_DOWN, "◀" to Buttons.DPAD_LEFT, "▶" to Buttons.DPAD_RIGHT)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Chips("LB" to Buttons.LB, "RB" to Buttons.RB, "SEL" to Buttons.VIEW, "◆" to Buttons.STEAM,
                "START" to Buttons.MENU, "QA" to Buttons.QUICK_ACCESS, "L3" to Buttons.LS, "R3" to Buttons.RS)
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Chips("LGrip" to Buttons.GRIP_LT, "RGrip" to Buttons.GRIP_RT,
                "L4" to Buttons.L4, "L5" to Buttons.L5, "R4" to Buttons.R4, "R5" to Buttons.R5)
        }

        SectionTitle("Triggers (0–32767)")
        SectionCard(readOnlyFocus) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AxisBar("LT", state.leftTrigger.toInt(), signed = false)
                AxisBar("RT", state.rightTrigger.toInt(), signed = false)
            }
        }

        SectionTitle("Sticks (±32767)")
        SectionCard(readOnlyFocus) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AxisBar(stringResource(R.string.debug_left_x), state.leftJoyX.toInt())
                AxisBar(stringResource(R.string.debug_left_y), state.leftJoyY.toInt())
                AxisBar(stringResource(R.string.debug_right_x), state.rightJoyX.toInt())
                AxisBar(stringResource(R.string.debug_right_y), state.rightJoyY.toInt())
            }
        }

        SectionTitle("Trackpads (±32767)")
        SectionCard(readOnlyFocus) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AxisBar(stringResource(R.string.debug_left_x), state.leftPadX.toInt())
                AxisBar(stringResource(R.string.debug_left_y), state.leftPadY.toInt())
                AxisBar(stringResource(R.string.debug_right_x), state.rightPadX.toInt())
                AxisBar(stringResource(R.string.debug_right_y), state.rightPadY.toInt())
            }
        }

        // The IMU block is only present once raw accel/gyro output is on; until then these stay at zero.
        SectionTitle("IMU")
        SectionCard(readOnlyFocus) {
            Row {
                InfoRow("Accel X", state.accelX.toString(), Modifier.weight(1f))
                InfoRow("Accel Y", state.accelY.toString(), Modifier.weight(1f))
                InfoRow("Accel Z", state.accelZ.toString(), Modifier.weight(1f))
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row {
                InfoRow("Gyro X", state.gyroX.toString(), Modifier.weight(1f))
                InfoRow("Gyro Y", state.gyroY.toString(), Modifier.weight(1f))
                InfoRow("Gyro Z", state.gyroZ.toString(), Modifier.weight(1f))
            }
        }

        SectionTitle("Raw report (hex)")
        SectionCard(readOnlyFocus) {
            Text(
                raw?.let(::formatHex) ?: "—",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

private data class InputPadInfo(val id: Int, val name: String, val vendorId: Int, val productId: Int)

private fun listInputGamepads(): List<InputPadInfo> = InputDevice.getDeviceIds().mapNotNull { id ->
    val device = InputDevice.getDevice(id) ?: return@mapNotNull null
    if (device.isVirtual) return@mapNotNull null
    val gamepad = device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
    val joystick = device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    if (!gamepad && !joystick) return@mapNotNull null
    InputPadInfo(id, device.name, device.vendorId, device.productId)
}.distinctBy { pad -> pad.name }

/** Temporary raw-event probe; the physical pad is released as soon as capture stops. */
private class RawEvdevCapture(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _status = MutableStateFlow("Idle. Shizuku must be running and authorized.")
    val status = _status.asStateFlow()
    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events = _events.asStateFlow()
    private var bridge: UInputGamepad? = null

    private val listener = object : com.virtualcontroller.android.ipc.IInputEventListener.Stub() {
        override fun onInputFrame(report: ByteArray?) = Unit
        override fun onRawInputEvent(deviceName: String?, type: Int, code: Int, value: Int) {
            val kind = when (type) { 1 -> "KEY"; 3 -> "AXIS"; else -> "EV$type" }
            val label = when (type to code) {
                1 to 304 -> "BTN_SOUTH"; 1 to 305 -> "BTN_EAST"; 1 to 306 -> "BTN_C"
                1 to 307 -> "BTN_NORTH"; 1 to 308 -> "BTN_WEST"; 1 to 310 -> "BTN_TL"
                1 to 311 -> "BTN_TR"; 1 to 312 -> "BTN_TL2"; 1 to 313 -> "BTN_TR2"
                1 to 314 -> "BTN_SELECT"; 1 to 315 -> "BTN_START"; 1 to 316 -> "BTN_MODE"
                1 to 317 -> "BTN_THUMBL"; 1 to 318 -> "BTN_THUMBR"
                1 to 544 -> "DPAD_UP"; 1 to 545 -> "DPAD_DOWN"; 1 to 546 -> "DPAD_LEFT"; 1 to 547 -> "DPAD_RIGHT"
                else -> null
            }
            val row = "${deviceName ?: "?"}  $kind ${label ?: "code=$code"} value=$value"
            scope.launch {
                _events.value = (_events.value + row).takeLast(120)
            }
        }
    }

    fun start(deviceName: String) {
        if (_status.value.startsWith("Capturing") || _status.value.startsWith("Starting")) return
        _events.value = emptyList()
        if (!Shizuku.pingBinder()) {
            _status.value = "Shizuku is not running. Start Shizuku, then try again."
            return
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            _status.value = "Grant Shizuku access, then tap Start capture again."
            Shizuku.requestPermission(6420)
            return
        }
        _status.value = "Starting capture…"
        scope.launch {
            try {
                val pad = UInputGamepad(appContext, GamepadProfile.XBOX_360)
                bridge = pad
                pad.bind()
                var attempts = 0
                while (!pad.isReady && attempts++ < 30) delay(150)
                if (!pad.isReady) {
                    _status.value = "Could not start the privileged input reader. Check Shizuku access."
                    pad.unbind(); bridge = null
                    return@launch
                }
                pad.startEvdevReader(listener, deviceName, includeRawEvents = true)
                _status.value = "Capturing $deviceName. Press each button once, then stop capture."
            } catch (t: Throwable) {
                _status.value = "Capture could not start: ${t.message ?: t.javaClass.simpleName}"
                try { bridge?.unbind() } catch (_: Throwable) {}
                bridge = null
            }
        }
    }

    fun stop() {
        val active = bridge ?: run {
            if (_status.value == "Capturing") _status.value = "Stopped"
            return
        }
        _status.value = "Stopping…"
        scope.launch {
            try { active.stopEvdevReader() } catch (_: Throwable) {}
            try { active.unbind() } catch (_: Throwable) {}
            bridge = null
            _status.value = "Stopped. The controller is released."
        }
    }
}

@Composable
private fun Chip(label: String, active: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (active) colors.primary else colors.surfaceContainerHighest,
        contentColor = if (active) colors.onPrimary else colors.onSurfaceVariant,
    ) {
        Text(
            label, Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
        )
    }
}

/** Label, value and a bar; [signed] axes are centred so zero sits in the middle. */
@Composable
private fun AxisBar(label: String, value: Int, signed: Boolean = true) {
    val fraction = if (signed) (value + 32768) / 65535f else value / 32767f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value.toString(), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
        }
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            drawStopIndicator = {},
        )
    }
}

private fun formatHex(buf: ByteArray): String = buildString {
    buf.forEachIndexed { i, b ->
        append("%02X ".format(b.toInt() and 0xFF))
        if (i % 16 == 15) append("\n")
    }
}.trimEnd()
