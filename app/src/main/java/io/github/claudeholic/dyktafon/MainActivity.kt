package io.github.claudeholic.dyktafon

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File

private val Red = Color(0xFFE53935)
private val RedDim = Color(0xFF6B2422)
private val Fg = Color(0xFFECEFF4)
private val Muted = Color(0xFF9AA3B2)

class MainActivity : ComponentActivity() {
    private var afterPermission: (() -> Unit)? = null
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) afterPermission?.invoke() else Ctl.status = "Brak uprawnienia do mikrofonu."
        afterPermission = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent { Screen(::withMic) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Parametry testowe (adb shell am start ... --ei threads N --es test_wav plik.wav --es test_mode offline|realtime). */
    private fun handleIntent(i: Intent) {
        val threads = i.getIntExtra("threads", Ctl.threads)
        Ctl.init(this, threads)
        val wav = i.getStringExtra("test_wav") ?: return
        val f = if (wav.startsWith("/")) File(wav) else File(getExternalFilesDir(null), "test/$wav")
        Ctl.runTest(f, i.getStringExtra("test_mode") ?: "offline")
    }

    private fun withMic(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) action() else {
            afterPermission = action
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun Screen(withMic: (() -> Unit) -> Unit) {
    val mode = Ctl.mode
    val activity = androidx.compose.ui.platform.LocalContext.current as MainActivity
    LaunchedEffect(mode) { activity.keepScreenOn(mode != Mode.IDLE) }

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(mode) {
        while (mode == Mode.REC_OFFLINE || mode == Mode.REC_REALTIME) {
            nowMs = System.currentTimeMillis(); delay(200)
        }
    }
    val recSec = (nowMs - Ctl.recStartMs).coerceAtLeast(0) / 1000.0

    Column(
        Modifier.fillMaxSize().background(Color(0xFF15171C)).systemBarsPadding().padding(16.dp)
    ) {
        Text("Dyktafon STT", color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(Ctl.status, color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            BigButton(
                label = "Nagraj i przerób później",
                active = mode == Mode.REC_OFFLINE,
                enabled = Ctl.offReady && (mode == Mode.IDLE || mode == Mode.REC_OFFLINE),
                caption = when (mode) {
                    Mode.REC_OFFLINE -> "STOP  ${fmt1(recSec)} s"
                    Mode.PROCESSING -> "przetwarzanie…"
                    else -> if (Ctl.offReady) "Parakeet v3" else "ładowanie…"
                },
            ) { if (mode == Mode.REC_OFFLINE) Ctl.stopOffline() else withMic { Ctl.startOffline() } }

            BigButton(
                label = "Przetwarzaj w czasie rzeczywistym",
                active = mode == Mode.REC_REALTIME,
                enabled = Ctl.rtReady && (mode == Mode.IDLE || mode == Mode.REC_REALTIME),
                caption = when (mode) {
                    Mode.REC_REALTIME -> "STOP  ${fmt1(recSec)} s"
                    Mode.FINISHING_REALTIME -> "kończenie…"
                    else -> if (Ctl.rtReady) "FastConformer" else "ładowanie…"
                },
            ) { if (mode == Mode.REC_REALTIME) Ctl.stopRealtime() else withMic { Ctl.startRealtime() } }
        }

        Spacer(Modifier.height(20.dp))
        if (Ctl.resultTitle.isNotEmpty()) {
            Text(Ctl.resultTitle, color = Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        if (Ctl.stats.isNotEmpty()) {
            Text(Ctl.stats, color = Color(0xFFFFCC80), fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
        }
        Box(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1E2128)).padding(12.dp)
        ) {
            val scroll = rememberScrollState()
            LaunchedEffect(Ctl.transcript, Ctl.partial) { scroll.animateScrollTo(scroll.maxValue) }
            Text(
                buildAnnotatedString {
                    append(Ctl.transcript)
                    if (Ctl.partial.isNotEmpty()) {
                        if (Ctl.transcript.isNotEmpty()) append(" ")
                        withStyle(SpanStyle(color = Color(0xFF80CBC4), fontStyle = FontStyle.Italic)) {
                            append(Ctl.partial)
                        }
                    }
                },
                color = Fg, fontSize = 18.sp, modifier = Modifier.verticalScroll(scroll)
            )
        }
    }
}

@Composable
private fun BigButton(label: String, active: Boolean, enabled: Boolean, caption: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(160.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(140.dp).clip(CircleShape)
                .background(if (enabled) Red else RedDim)
                .border(if (active) 6.dp else 0.dp, Color.White, CircleShape)
                .clickable(enabled = enabled, onClick = onClick)
        ) {
            if (active) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Color.White))
            } else {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = if (enabled) 1f else 0.4f)))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(caption, color = if (active) Red else Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}
