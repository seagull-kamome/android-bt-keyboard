package jp.kamome.btkeyboard

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 画面は「接続設定」と「入力（キーボード / メディア / トラックパッド）」の 2 系統 */
@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {
    private var controller: HidController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun App() {
        var state by remember { mutableStateOf("未開始") }
        var connected by remember { mutableStateOf(false) }
        var inputScreen by remember { mutableStateOf(false) }
        var target by remember { mutableStateOf("jis") }
        var lastLayoutId by remember { mutableStateOf("jis") }   // トラックパッドの直前に使っていたレイアウト
        var input by remember { mutableStateOf<InputState?>(null) }
        var devices by remember { mutableStateOf(emptyList<BluetoothDevice>()) }
        val log = remember { mutableStateListOf<String>() }
        var layouts by remember { mutableStateOf(LayoutStore.load(this)) }
        val saveLayouts: (List<KeyboardLayout>) -> Unit = { layouts = it; LayoutStore.save(this, it) }
        var editor by remember { mutableStateOf(false) }
        val prefs = remember { getSharedPreferences("settings", Context.MODE_PRIVATE) }
        var forceLandscape by remember { mutableStateOf(prefs.getBoolean("force_landscape", true)) }
        // トラックパッドのクリックボタン列を左に置くか（左手でキーを押し、右手でパッドを使う人向けなど）
        var padLeft by remember { mutableStateOf(prefs.getBoolean("pad_buttons_left", false)) }
        val setPadLeft: (Boolean) -> Unit = { padLeft = it; prefs.edit().putBoolean("pad_buttons_left", it).apply() }

        // HID の登録は 1 アプリにつき 1 回だけ。2 回目以降は registerApp が失敗する
        val start = start@{
            Log.i("BTKB", "start() called")
            // 登録済み・登録中なら何もしない。登録が外れていたら（未登録）作り直して登録し直す
            controller?.let { if (it.registered || it.registering) return@start else it.stop() }
            val c = HidController(this,
                onLog = { Log.i("BTKB", it); log.add(0, it); if (log.size > 60) log.removeAt(60) }) {
                state = it
                connected = controller?.host != null
            }
            controller = c
            input = InputState(c)
            // 前面サービスで、アプリが奥に回っても HID の登録が外れないようにする
            startForegroundService(Intent(this, KeepAliveService::class.java))
            c.start()
            devices = c.bondedDevices()
        }
        // 強制終了などで Bluetooth 側に前の登録が固まったときの回復: Bluetooth を入れ直して登録し直す
        val resetBluetooth = {
            controller?.stop(); controller = null; input = null; connected = false
            state = "Bluetooth を入れ直しています…"
            val addLog: (String) -> Unit = { Log.i("BTKB", it); log.add(0, it) }
            val started = restartBluetooth(addLog) { start() }
            if (!started) {
                state = "Android 13 以降は、設定で Bluetooth をオフ→オンしてから「HID デバイスとして開始」を押してください"
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            Log.i("BTKB", "permission result: $granted")
            if (granted.values.all { it }) start() else state = "Bluetooth の権限が必要です"
        }
        val begin = {
            // Android 11 以前は BLUETOOTH/BLUETOOTH_ADMIN（インストール時に自動付与）のみで、実行時の要求は不要
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                launcher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
            } else start()
        }

        val inState = input
        // 入力画面（キーボード / メディア / トラックパッド）のときだけ横向きに固定する（接続設定は端末の向きに従う）
        val wantLandscape = forceLandscape && ((inputScreen && inState != null) || editor)
        SideEffect {
            requestedOrientation = if (wantLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        BackHandler(enabled = inputScreen || editor) { if (editor) editor = false else inputScreen = false }
        if (editor) {
            LayoutEditorScreen(layouts, saveLayouts, onClose = { editor = false })
        } else if (inputScreen && inState != null) {
            // 削除されたレイアウトへの切替キーは何もしない。トラックパッドへ移るときは、直前のレイアウトを覚えておく
            val onSwitch: (String) -> Unit = { t ->
                if (t == "setup") inputScreen = false
                else if (t == "trackpad" || layouts.any { it.id == t }) {
                    if (t == "trackpad" && target != "trackpad") lastLayoutId = target
                    target = t
                }
            }
            // 見た目（背景画像・キーの透明度・文字色）は、表示中のレイアウトのもの。
            // トラックパッドはレイアウトではないので、直前に使っていたレイアウトの見た目を引き継ぐ
            val styleId = if (target == "trackpad") lastLayoutId else target
            val styled = layouts.firstOrNull { it.id == styleId } ?: layouts.first()
            AppearanceBackground(styled.id, styled.style, bgVersion = 0) {
                if (target == "trackpad") TrackpadScreen(controller!!, onSwitch, padLeft, { setPadLeft(!padLeft) })
                else KeyboardScreen(layouts.firstOrNull { it.id == target } ?: layouts.first(), inState, onSwitch, padLeft)
            }
        } else {
            SetupScreen(
                state, connected, devices, log,
                onStart = begin,
                onResetBluetooth = resetBluetooth,
                onRefresh = { devices = controller?.bondedDevices().orEmpty() },
                onConnect = { controller?.connect(it) },
                onOpenInput = { inputScreen = true },
                onOpenEditor = { editor = true },
                forceLandscape = forceLandscape,
                padLeft = padLeft, onPadLeft = setPadLeft,
                onForceLandscape = { forceLandscape = it; prefs.edit().putBoolean("force_landscape", it).apply() },
            )
        }
    }

    @androidx.compose.runtime.Composable
    private fun SetupScreen(
        state: String, connected: Boolean, devices: List<BluetoothDevice>, log: List<String>,
        onStart: () -> Unit, onResetBluetooth: () -> Unit, onRefresh: () -> Unit, onConnect: (BluetoothDevice) -> Unit, onOpenInput: () -> Unit,
        onOpenEditor: () -> Unit, forceLandscape: Boolean, onForceLandscape: (Boolean) -> Unit,
        padLeft: Boolean, onPadLeft: (Boolean) -> Unit,
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("状態: $state")
            Text("端末名: ${BluetoothAdapter.getDefaultAdapter()?.name}")
            // いちばんよく使うボタンなので、横向きで縦に収まらなくても見える位置（先頭）に置く
            Button(onClick = onOpenInput) {
                Text(if (connected) "入力画面へ" else "入力画面へ（未接続）")
            }
            Button(onClick = onStart) { Text("HID デバイスとして開始") }
            Button(onClick = onResetBluetooth) { Text("Bluetooth を入れ直して登録し直す（登録に失敗するとき）") }
            Button(onClick = { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                Text("Bluetooth 設定を開く（PC から本機を探してペアリング）")
            }
            Button(onClick = onRefresh) { Text("ペアリング済み機器を更新") }
            devices.forEach { d -> Button(onClick = { onConnect(d) }) { Text("接続: ${d.name ?: d.address}") } }
            Button(onClick = onOpenEditor) { Text("レイアウトを編集") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = forceLandscape, onCheckedChange = onForceLandscape)
                Text("入力画面（キーボード・パッド）を横向きに固定")
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = padLeft, onCheckedChange = onPadLeft)
                Text("トラックパッドのクリックボタンを左側に置く")
            }
            Text("ログ（新しい順）:")
            log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    override fun onDestroy() {
        stopService(Intent(this, KeepAliveService::class.java))
        controller?.stop()
        super.onDestroy()
    }
}
