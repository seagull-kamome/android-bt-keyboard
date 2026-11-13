package jp.kamome.btkeyboard.video

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Chrome の画面共有の映像を受信して、Surface に描くまでをまとめる（サーバー、デコーダ、状態）。
 * 画面（Compose）は、ここの状態を見て、映像を背景に出したり、案内を出したりする。
 * 送信側（Chrome 拡張機能など）の仕組みは、android-screen-receiver と同じ。
 */
class VideoReceiver(private val context: Context) : StreamServer.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val identity = DeviceIdentity(context)
    private val extensionId: String by lazy { ExtensionInfo.id(context) }

    // --- 画面が見る状態 ---
    /** 受信サーバーを動かしているか */
    var running by mutableStateOf(false)
        private set
    /** 送信側が接続する先（IP:ポート）。Wi-Fi につながっていなければ null */
    var hostPort by mutableStateOf<String?>(null)
        private set
    var senderConnected by mutableStateOf(false)
        private set
    var streamWidth by mutableIntStateOf(0)
        private set
    var streamHeight by mutableIntStateOf(0)
        private set
    /** 最初のフレームを、画面に出したか */
    var hasFrame by mutableStateOf(false)
        private set
    var pin by mutableStateOf(identity.pin)
        private set
    val deviceName: String get() = identity.name

    /** 背景に映像を出すか（送信側が接続して、映像の大きさが分かっているとき） */
    val active: Boolean get() = running && senderConnected && streamWidth > 0 && streamHeight > 0

    private var server: StreamServer? = null
    private var port = PORTS.first()
    private var surface: Surface? = null
    private val player = H264Player(
        onNeedKeyframe = { server?.requestKeyframe() },
        onFirstFrame = { main.post { hasFrame = true } },
        onFatalError = { main.post { restartPlayer() } },
    )

    // --- 開始と停止 ---
    fun start(): Boolean {
        if (running) return true
        for (p in PORTS) {
            val s = StreamServer(p,
                { name -> try { context.assets.open(name).use { it.readBytes() } } catch (e: IOException) { null } },
                { DeviceInfo(identity.name, identity.pin, extensionId) },
                this)
            try {
                s.start(0, false) // 読み取りの時間切れなし（映像が止まっても、接続を保つ）
                server = s
                port = p
                running = true
                refreshAddress()
                Log.i(TAG, "映像の受信サーバーを開始: ポート $p")
                return true
            } catch (e: IOException) {
                Log.w(TAG, "ポート $p を使えません", e)
            }
        }
        return false
    }

    fun stop() {
        player.stop()
        server?.stop()
        server = null
        running = false
        resetStream()
    }

    private fun resetStream() {
        senderConnected = false
        streamWidth = 0
        streamHeight = 0
        hasFrame = false
    }

    /** Wi-Fi の IP アドレスを調べ直す（接続し直すと、変わることがある） */
    fun refreshAddress() {
        hostPort = addresses().firstOrNull()?.let { "$it:$port" }
    }

    private fun addresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress ?: "" }
    } catch (e: Exception) { emptyList() }

    /** PIN を作り直す。送信側が覚えていた PIN と違うので、次の接続では、確認の画面が出る */
    fun regeneratePin() { pin = identity.regenerate() }

    // --- 共有（近距離共有など） ---
    fun shareExtension(activity: android.app.Activity) = Sharing.shareExtension(activity)

    fun shareAddress(activity: android.app.Activity) {
        val host = hostPort ?: return
        val text = buildString {
            appendLine("画面共有の接続先: $host")
            appendLine("端末: ${identity.name}　PIN: ${identity.pin}")
            appendLine("ブラウザで開く: http://$host/")
            if (extensionId.isNotEmpty()) {
                appendLine("拡張機能で開く（Chrome のアドレス欄に貼り付け）: chrome-extension://$extensionId/sender.html?target=$host&pin=${identity.pin}")
            }
        }.trim()
        Sharing.shareAddress(activity, text)
    }

    // --- 映像の面（VideoLayer の SurfaceView）との受け渡し ---
    fun attachSurface(s: Surface) {
        surface = s
        startPlayerIfReady()
    }

    fun detachSurface() {
        surface = null
        player.stop()
    }

    private fun startPlayerIfReady() {
        val s = surface ?: return
        if (streamWidth <= 0 || streamHeight <= 0) return
        try {
            player.start(s, streamWidth, streamHeight)
            server?.requestKeyframe()
        } catch (e: Exception) {
            Log.e(TAG, "デコーダを開始できません（${streamWidth}x$streamHeight）", e)
        }
    }

    private fun restartPlayer() {
        player.stop()
        startPlayerIfReady()
    }

    // --- StreamServer.Listener（サーバーのスレッドから呼ばれるので、画面の状態は、メインスレッドで更新する） ---
    override fun onSenderConnected() {
        main.post { senderConnected = true }
    }

    override fun onSenderDisconnected() {
        main.post {
            player.stop()
            resetStream()
        }
    }

    override fun onConfig(width: Int, height: Int, fps: Int) {
        Log.i(TAG, "設定を受信: ${width}x$height @${fps}fps")
        main.post {
            streamWidth = width
            streamHeight = height
            hasFrame = false
            restartPlayer()
        }
    }

    override fun onFrame(data: ByteArray, offset: Int, length: Int, ptsUs: Long, key: Boolean) {
        player.feed(data, offset, length, ptsUs, key)
    }

    private companion object {
        const val TAG = "BTKB-video"
        val PORTS = listOf(8080, 8081, 8082)
    }
}
