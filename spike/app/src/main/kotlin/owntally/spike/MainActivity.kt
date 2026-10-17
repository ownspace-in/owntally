package owntally.spike

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import owntally.sync.GroupRoom
import owntally.sync.PublicKey
import owntally.sync.Probe
import owntally.sync.RelayStatus
import owntally.sync.WebSocketTransport

enum class Role(val label: String, val key: PublicKey) {
    A("Phone A: starts the test", PublicKey(ByteArray(32) { 0xA1.toByte() })),
    B("Phone B: answers", PublicKey(ByteArray(32) { 0xB2.toByte() })),
}

enum class Mode(val label: String) { RELAY("Relay (WebSocket)"), NEARBY("Nearby") }

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val log = mutableStateListOf<String>()

    private var role by mutableStateOf(Role.A)
    private var mode by mutableStateOf(Mode.RELAY)
    private var relayUrl by mutableStateOf("wss://relay.ownspace.in/ws")
    private var roomName by mutableStateOf("spike-1")
    private var running by mutableStateOf(false)

    private var relay: WebSocketTransport? = null
    private var nearby: NearbyTransport? = null
    private var jobs = mutableListOf<Job>()

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private fun note(text: String) {
        log.add("${clock.format(Date())}  $text")
    }

    private val room get() = GroupRoom.fromGroupId(roomName.trim().toByteArray())
    private val other get() = if (role == Role.A) Role.B else Role.A

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
        applyLaunchOptions()
    }

    /** For scripted runs: adb shell am start -n owntally.spike/.MainActivity -e relay ws://... --ez autostart true */
    private fun applyLaunchOptions() {
        intent.getStringExtra("relay")?.let { relayUrl = it }
        intent.getStringExtra("room")?.let { roomName = it }
        intent.getStringExtra("role")?.let { name -> Role.entries.firstOrNull { it.name == name }?.let { role = it } }
        intent.getStringExtra("mode")?.let { name -> Mode.entries.firstOrNull { it.name == name }?.let { mode = it } }
        if (intent.getBooleanExtra("autostart", false)) {
            start()
            if (intent.getBooleanExtra("autotest", false)) runTest()
        }
    }

    override fun onDestroy() {
        stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun nearbyPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    private fun network(): String {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    }

    private fun context() = "${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), network=${network()}"

    private fun start() {
        if (running) return
        running = true
        note("start: ${mode.label}, ${role.name}, room '${roomName.trim()}'. ${context()}")
        when (mode) {
            Mode.RELAY -> startRelay()
            Mode.NEARBY -> startNearby()
        }
    }

    private fun startRelay() {
        val transport = WebSocketTransport(role.key, relayUrl.trim(), scope).also { relay = it }
        val r = room
        jobs += scope.launch {
            var last: RelayStatus? = null
            transport.status(r).collect { status ->
                if (status != last) note("relay: $status${transport.lastError(r)?.let { " (last error: $it)" } ?: ""}")
                last = status
            }
        }
        jobs += scope.launch {
            transport.incoming(r).collect { link ->
                note("relay: ${link.peer.toString().take(6)} started talking to us")
                launch { Probe.serve(link) { note("  $it") } }
            }
        }
    }

    private fun startNearby() {
        val transport = NearbyTransport(this, role.key).also { nearby = it }
        if (role == Role.B) {
            note("nearby: advertising, waiting for phone A")
            jobs += scope.launch {
                try {
                    transport.incoming(room).collect { link ->
                        note("nearby: connected to ${link.peer.toString().take(6)}")
                        launch { Probe.serve(link) { note("  $it") } }
                    }
                } catch (e: Exception) {
                    note("nearby: advertising failed: ${e.message}")
                }
            }
        } else {
            note("nearby: ready. Tap 'Run test' when phone B is waiting")
        }
    }

    private fun runTest() {
        if (!running) {
            note("tap Start first")
            return
        }
        jobs += scope.launch {
            val started = System.nanoTime()
            try {
                val link = when (mode) {
                    Mode.RELAY -> {
                        val t = relay!!
                        t.status(room).first { it == RelayStatus.OPEN }
                        t.connect(room, other.key)
                    }
                    Mode.NEARBY -> nearby!!.connectAny(room)
                }
                val connectMs = (System.nanoTime() - started) / 1_000_000
                note("connected in ${connectMs}ms, link=${link.kind}. Sending 10 each of 1, 1000 and 50000 bytes")
                val result = Probe.run(link, repeats = 10, timeoutMillis = 10_000) { note("  $it") }
                note("RESULT ${mode.name} link=${link.kind} connect=${connectMs}ms ${result.summary()} | ${context()}")
            } catch (e: Exception) {
                note("test failed after ${(System.nanoTime() - started) / 1_000_000}ms: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        relay?.close()
        relay = null
        nearby?.stop()
        nearby = null
        if (running) note("stopped")
        running = false
    }

    @Composable
    private fun Screen() {
        val clipboard = LocalClipboardManager.current
        val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys
            if (denied.isEmpty()) start() else note("permissions denied: ${denied.joinToString { it.substringAfterLast('.') }}")
        }

        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("OwnTally transport spike", style = MaterialTheme.typography.titleLarge)

            Role.entries.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = role == r, onClick = { if (!running) role = r }, enabled = !running)
                    Text(r.label)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Mode.entries.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == m, onClick = { if (!running) mode = m }, enabled = !running)
                        Text(m.label)
                    }
                }
            }
            if (mode == Mode.RELAY) {
                OutlinedTextField(
                    value = relayUrl, onValueChange = { relayUrl = it }, label = { Text("Relay address") },
                    singleLine = true, enabled = !running, modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = roomName, onValueChange = { roomName = it }, label = { Text("Room name (same on both phones)") },
                singleLine = true, enabled = !running, modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !running, onClick = {
                    if (mode == Mode.NEARBY) askPermissions.launch(nearbyPermissions().toTypedArray()) else start()
                }) { Text("Start") }
                Button(enabled = running && role == Role.A, onClick = { runTest() }) { Text("Run test") }
                OutlinedButton(enabled = running, onClick = { stop() }) { Text("Stop") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(log.joinToString("\n"))) }) { Text("Copy log") }
                OutlinedButton(onClick = { log.clear() }) { Text("Clear") }
            }

            LazyColumn(Modifier.weight(1f)) {
                items(log.asReversed()) { Text(it, fontSize = 12.sp, lineHeight = 15.sp) }
            }
        }
    }
}
