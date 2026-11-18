package com.durai.soundguard

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

private val Bg = Color(0xFF0F1720)
private val CardC = Color(0xFF182430)
private val Green = Color(0xFF4ADE80)
private val Red = Color(0xFFF87171)
private val Amber = Color(0xFFFBBF24)
private val Blue = Color(0xFF2563EB)
private val Dim = Color(0xFF9FB0C0)

class MainActivity : ComponentActivity() {
    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startGuard() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else startGuard()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Bg, surface = CardC)) {
                Surface(color = Bg, contentColor = Color.White, modifier = Modifier.fillMaxSize()) { App(this) }
            }
        }
    }

    private fun startGuard() {
        ContextCompat.startForegroundService(this, Intent(this, GuardService::class.java))
    }
}

@Composable
fun App(ctx: Context) {
    val s by Guard.status.collectAsState()
    val conn by Guard.connected.collectAsState()
    val on by Guard.monitoring.collectAsState()
    val nm = ctx.getSystemService(NotificationManager::class.java)
    val needFs = Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()
    fun open(i: Intent) = ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("SoundGuard", fontSize = 26.sp, fontWeight = FontWeight.Bold)

        if (!on) Panel {
            Text("Monitoring is off", fontWeight = FontWeight.SemiBold)
            Button(
                onClick = { ContextCompat.startForegroundService(ctx, Intent(ctx, GuardService::class.java)) },
                modifier = Modifier.padding(top = 8.dp)
            ) { Text("Start monitoring") }
        } else ConnCard(conn) { open(Intent(Settings.ACTION_WIFI_SETTINGS)) }

        if (needFs) Panel {
            Text("Allow full-screen alerts so the call screen can appear over the lock screen.", color = Dim)
            Button(
                onClick = {
                    open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
                },
                modifier = Modifier.padding(top = 8.dp)
            ) { Text("Allow") }
        }

        val st = s
        if (on && conn && st != null) {
            StatusCard(st)
            Controls(st)
            Events(st)
        }
        if (on) {
            Button(
                onClick = { open(Intent(Intent.ACTION_VIEW, Uri.parse("${Guard.BASE}/update"))) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
            ) { Text("Firmware update (opens browser)") }
            Button(
                onClick = { ctx.startService(Intent(ctx, GuardService::class.java).setAction("STOP")) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D))
            ) { Text("Stop monitoring") }
        }
    }
}

@Composable
fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardC).padding(16.dp),
        content = content
    )
}

@Composable
fun ConnCard(conn: Boolean, onWifi: () -> Unit) {
    val c by animateColorAsState(if (conn) Green else Amber, label = "conn")
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(c))
            Spacer(Modifier.width(10.dp))
            Text(if (conn) "Connected to SoundGuard" else "Searching for SoundGuard...", fontWeight = FontWeight.SemiBold)
        }
        if (!conn) {
            Text(
                "Join the Wi-Fi \"SoundGuard\" (password guard1234). If Android asks, choose \"Stay connected\".",
                color = Dim, modifier = Modifier.padding(top = 8.dp)
            )
            Button(onClick = onWifi, modifier = Modifier.padding(top = 8.dp)) { Text("Open Wi-Fi settings") }
        }
    }
}

@Composable
fun StatusCard(s: Status) {
    val (title, color, sub) = when {
        s.err.isNotEmpty() -> Triple("Error: ${s.err}", Red, "Fix it, then press Calibrate")
        s.st == 1 -> Triple("Calibrating ${s.cal}%", Amber, "Keep the room quiet for ~5 seconds")
        s.al != 0 -> Triple(Guard.kindName(s.al), Red, if (s.ring) "Ringing" else "Silenced (rings again if it continues)")
        else -> Triple("Listening", Green, s.warn.ifEmpty { "No abnormal sound" })
    }
    val c by animateColorAsState(color, label = "status")
    val lvl = if (s.abs) (s.spl - 30) / 80f else (s.db / 60).toFloat()
    val p by animateFloatAsState(lvl.coerceIn(0f, 1f), label = "level")
    Panel {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c)
        Text(sub, color = Dim, modifier = Modifier.padding(top = 4.dp))
        LinearProgressIndicator(
            progress = { p }, color = c, trackColor = Bg,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(10.dp).clip(RoundedCornerShape(5.dp))
        )
        Text(
            (if (s.abs) "~${s.spl} dB" else "+${s.db.roundToInt()} dB") +
                " | floor ${s.floor} | clients ${s.clients} | up ${s.up}s",
            color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
fun Controls(s: Status) {
    var loud by remember { mutableFloatStateOf(s.loud.toFloat()) }
    var voice by remember { mutableFloatStateOf(s.voice.toFloat()) }
    var adb by remember { mutableFloatStateOf(s.adb.toFloat()) }
    var off by remember { mutableFloatStateOf(s.off.toFloat()) }
    var absMode by remember { mutableStateOf(s.abs) }
    val push = { Guard.setCfg(loud.roundToInt(), voice.roundToInt(), absMode, adb.roundToInt(), off.roundToInt()) }

    Panel {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Guard.send("/calibrate") }, modifier = Modifier.weight(1f)) { Text("Calibrate") }
            Button(
                onClick = { Guard.send("/test") }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
            ) { Text("Test alarm") }
        }
        Text("Sensitivity", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeBtn("Relative", !absMode) { absMode = false; push() }
            ModeBtn("Custom dB", absMode) { absMode = true; push() }
        }
        AnimatedVisibility(!absMode) {
            Column {
                Sl("Loud noise", loud, 25f..65f, "dB over floor", { loud = it }, { push() })
                Sl("Voice (cry / help)", voice, 12f..45f, "dB over floor", { voice = it }, { push() })
                Text("Lower = more sensitive. Calibrate again if you move the device.", color = Dim, fontSize = 12.sp)
            }
        }
        AnimatedVisibility(absMode) {
            Column {
                Sl("Alert above", adb, 40f..110f, "dB", { adb = it }, { push() })
                Text("Now ~${s.spl} dB", color = Dim)
                Sl("Mic offset", off, -20f..20f, "dB", { off = it }, { push() })
                Text("Move the offset until \"Now\" matches a sound-meter app.", color = Dim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun RowScope.ModeBtn(t: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = Modifier.weight(1f),
        colors = ButtonDefaults.buttonColors(containerColor = if (selected) Blue else Color(0xFF334155))
    ) { Text(t) }
}

@Composable
fun Sl(label: String, v: Float, range: ClosedFloatingPointRange<Float>, unit: String,
       onChange: (Float) -> Unit, onDone: () -> Unit) {
    Text("$label: ${v.roundToInt()} $unit", modifier = Modifier.padding(top = 10.dp))
    Slider(value = v, onValueChange = onChange, onValueChangeFinished = onDone, valueRange = range)
}

@Composable
fun Events(s: Status) {
    Panel {
        Text("Recent events", fontWeight = FontWeight.Bold)
        if (s.hist.isEmpty()) Text("None", color = Dim, modifier = Modifier.padding(top = 4.dp))
        s.hist.forEach {
            Text("${Guard.kindName(it.k)} - ${ago(s.up - it.t)}", color = Dim, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

private fun ago(sec: Long) = if (sec < 60) "${sec}s ago" else "${sec / 60} min ago"
