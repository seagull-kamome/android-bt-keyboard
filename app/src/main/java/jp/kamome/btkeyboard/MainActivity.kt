package jp.kamome.btkeyboard

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
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
import androidx.compose.material3.Slider
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import jp.kamome.btkeyboard.video.LocalVideo
import jp.kamome.btkeyboard.video.QrCode
import jp.kamome.btkeyboard.video.VideoReceiver

/** 画面は「接続設定」と「入力（キーボード / メディア / トラックパッド）」の 2 系統 */
@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {
    private var controller: HidController? = null
    // 映像の受信（任意機能）。オフのままなら、何も起動しない
    private val video by lazy { VideoReceiver(this) }

    /** Bluetooth の接続権限があるか。Android 11 以前は、インストール時に付与されるので、常に true */
    private fun canUseBluetooth() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                // 映像を背景に出している間は、土台の面を透明にして、映像が見えるようにする（各画面が自前で面を敷く）
                CompositionLocalProvider(LocalVideo provides video) {
                    Surface(Modifier.fillMaxSize(), color = if (video.active) Color.Transparent else MaterialTheme.colorScheme.surface) { App() }
                }
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
        // 映像の受信（Chrome の画面共有を背景に出す）。既定はオフで、キーボードだけの使い方はそのまま
        var videoEnabled by remember { mutableStateOf(prefs.getBoolean("video_receive", false)) }
        var videoFailed by remember { mutableStateOf(false) }
        LaunchedEffect(videoEnabled) {
            if (videoEnabled) videoFailed = !video.start() else { video.stop(); videoFailed = false }
        }
        var keysVisible by remember { mutableStateOf(true) }   // 映像を見ている間だけ、キーを隠せる

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
        val resetBluetooth = reset@{
            // 権限がないまま Bluetooth を切り替えようとすると落ちるので、先に権限を求める（Android 12 以降）
            if (!canUseBluetooth()) { state = "先に「HID デバイスとして開始」を押して、Bluetooth の権限を許可してください"; return@reset }
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
            Surface(Modifier.fillMaxSize()) { LayoutEditorScreen(layouts, saveLayouts, onClose = { editor = false }) }
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
                val showKeys = keysVisible || !video.active
                Column(Modifier.fillMaxSize()) {
                    // 映像を見ている間は、キーに被らないよう、上に細い帯を設けて、切り替えボタンを置く
                    if (video.active) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.End) {
                            Text(if (showKeys) "⌨ 隠す" else "⌨ キーを出す", color = Color.White,
                                modifier = Modifier.background(Color.Black.copy(alpha = 0.45f)).clickable { keysVisible = !keysVisible }
                                    .padding(horizontal = 10.dp, vertical = 2.dp))
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (showKeys) {
                            if (target == "trackpad") TrackpadScreen(controller!!, onSwitch, padLeft, { setPadLeft(!padLeft) })
                            else KeyboardScreen(layouts.firstOrNull { it.id == target } ?: layouts.first(), inState, onSwitch, padLeft)
                        }
                    }
                }
            }
        } else {
            Surface(Modifier.fillMaxSize()) { SetupScreen(
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
                videoEnabled = videoEnabled, videoFailed = videoFailed,
                onVideoEnabled = { videoEnabled = it; prefs.edit().putBoolean("video_receive", it).apply() },
            ) }
        }
    }

    @androidx.compose.runtime.Composable
    private fun SetupScreen(
        state: String, connected: Boolean, devices: List<BluetoothDevice>, log: List<String>,
        onStart: () -> Unit, onResetBluetooth: () -> Unit, onRefresh: () -> Unit, onConnect: (BluetoothDevice) -> Unit, onOpenInput: () -> Unit,
        onOpenEditor: () -> Unit, forceLandscape: Boolean, onForceLandscape: (Boolean) -> Unit,
        padLeft: Boolean, onPadLeft: (Boolean) -> Unit,
        videoEnabled: Boolean, videoFailed: Boolean, onVideoEnabled: (Boolean) -> Unit,
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("状態: $state")
            // Android 12 以降は、権限を許可する前に端末名を読むと落ちる（許可を求めるボタンも、この画面にある）
            Text("端末名: " + if (canUseBluetooth()) BluetoothAdapter.getDefaultAdapter()?.name else "（Bluetooth の権限を許可すると表示されます）")
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
            VideoSection(videoEnabled, videoFailed, onVideoEnabled)
            Text("ログ（新しい順）:")
            log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    /** 映像の受信の設定。オフなら、スイッチだけ（キーボード単体の使い方には、影響しない） */
    @androidx.compose.runtime.Composable
    private fun VideoSection(enabled: Boolean, failed: Boolean, onEnabled: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = enabled, onCheckedChange = onEnabled)
            Text("映像を受信して、背景に表示（Chrome の画面共有。使わないときはオフ）")
        }
        if (!enabled) return
        if (failed) { Text("受信サーバーを開始できません（ポート 8080〜8082 が使用中）"); return }
        LaunchedEffect(Unit) { video.refreshAddress() }
        val host = video.hostPort
        if (host == null) {
            Text("Wi-Fi に接続してください（接続先を表示できません）")
            Button(onClick = { video.refreshAddress() }) { Text("接続先を更新") }
            return
        }
        Text("映像中のキーの不透明度: ${video.keyAlpha}%（小さいほど、映像が透けます）")
        Slider(value = video.keyAlpha.toFloat(), onValueChange = { video.changeKeyAlpha(it.toInt()) }, valueRange = 0f..100f)
        Text("端末: ${video.deviceName}　PIN: ${video.pin}")
        Text("Chrome のアドレス欄に http://$host/ を入れて開く（初回は、拡張機能の導入が必要）")
        Text(when {
            video.active && video.hasFrame -> "受信中（${video.streamWidth}x${video.streamHeight}）。入力画面で、背景に映像が出ます"
            video.active -> "接続済み。映像を待っています"
            else -> "送信側の接続を待っています"
        })
        val qr = remember(host) { QrCode.bitmap("http://$host/", 360).asImageBitmap() }
        Image(qr, contentDescription = "接続先の QR コード", modifier = Modifier.size(160.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { video.shareAddress(this@MainActivity) }) { Text("接続先を共有") }
            Button(onClick = { video.shareExtension(this@MainActivity) }) { Text("拡張機能を共有") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { video.refreshAddress() }) { Text("接続先を更新") }
            Button(onClick = { video.regeneratePin() }) { Text("PIN を作り直す") }
        }
    }

    override fun onDestroy() {
        video.stop()
        stopService(Intent(this, KeepAliveService::class.java))
        controller?.stop()
        super.onDestroy()
    }
}
