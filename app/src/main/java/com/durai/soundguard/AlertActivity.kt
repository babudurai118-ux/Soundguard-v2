package com.durai.soundguard

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class AlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            AlertScreen(
                onAnswer = {
                    startService(Intent(this, GuardService::class.java).setAction("ACK"))
                    finish()
                },
                onDone = { finish() }
            )
        }
    }
}

@Composable
fun AlertScreen(onAnswer: () -> Unit, onDone: () -> Unit) {
    val kind by Guard.callKind.collectAsState()
    LaunchedEffect(kind) { if (kind == 0) onDone() }

    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    val bg = lerp(Color(0xFFB91C1C), Color(0xFF7F1D1D), a)

    Column(
        Modifier.fillMaxSize().background(bg).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(Modifier.height(48.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SoundGuard", color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp)
            Text(
                Guard.kindName(kind), color = Color.White, fontSize = 38.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)
            )
            Text("Incoming alert", color = Color.White.copy(alpha = 0.8f), fontSize = 18.sp,
                modifier = Modifier.padding(top = 8.dp))
        }
        Button(
            onClick = onAnswer,
            shape = CircleShape,
            modifier = Modifier.size(132.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
        ) { Text("Answer", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(48.dp))
    }
}
