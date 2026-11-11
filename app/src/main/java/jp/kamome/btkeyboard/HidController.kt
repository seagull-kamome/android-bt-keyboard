package jp.kamome.btkeyboard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

private const val MAX_REGISTER_ATTEMPTS = 5
private const val REGISTER_RETRY_MS = 500L

/** HID ハンドシェイクのエラー応答: Invalid Report ID（Bluetooth HID 仕様の値） */
private const val ERROR_INVALID_REPORT_ID: Byte = 2

/** BluetoothHidDevice のラッパー。レポートの送信と接続状態の通知を担当する */
@SuppressLint("MissingPermission") // 権限は呼び出し側（MainActivity）で取得済みの前提
class HidController(
    context: Context,
    private val onLog: (String) -> Unit = {},
    private val onState: (String) -> Unit,
) {
    private val adapter: BluetoothAdapter? =
        context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
    private val appContext: Context = context.applicationContext
    private var hid: BluetoothHidDevice? = null
    @Volatile var host: BluetoothDevice? = null
        private set
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false
    /** 登録済み（onAppStatusChanged で確認できた状態） */
    @Volatile var registered = false
        private set
    /** 登録の試行中（再試行の待ち時間を含む）。この間に開始を重ねて押しても無視する */
    @Volatile var registering = false
        private set

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            onLog("onAppStatusChanged registered=$registered plugged=${pluggedDevice?.address}")
            this@HidController.registered = registered
            if (registered) registering = false
            onState(if (registered) "登録済み（ペアリング待ち）" else "未登録")
        }
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            onLog("onConnectionStateChanged ${device.name}/${device.address} state=$state")
            host = if (state == BluetoothProfile.STATE_CONNECTED) device else null
            onState(when (state) {
                BluetoothProfile.STATE_CONNECTED -> "接続中: ${device.name ?: device.address}"
                BluetoothProfile.STATE_CONNECTING -> "接続しています…"
                else -> "切断"
            })
        }
        // PC からの GET_REPORT に返事をしないと、PC 側が接続を切ることがある
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            onLog("onGetReport type=$type id=$id size=$bufferSize")
            val size = when (id.toInt()) {
                HidDescriptor.ID_KEYBOARD -> 8
                HidDescriptor.ID_MOUSE -> 4
                HidDescriptor.ID_CONSUMER -> 2
                else -> 0
            }
            if (size == 0) hid?.reportError(device, ERROR_INVALID_REPORT_ID)
            else hid?.replyReport(device, type, id, ByteArray(size))
        }
        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            onLog("onSetReport type=$type id=$id len=${data.size}")
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            onLog("onSetProtocol protocol=$protocol")
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            onLog("onVirtualCableUnplug ${device.address}")
        }
    }

    fun start() {
        val a = adapter ?: return onState("この端末は Bluetooth に対応していません")
        registering = true
        a.getProfileProxy(appContext, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "BT Keyboard", "Android keyboard/mouse/media", "kamome",
                    BluetoothHidDevice.SUBCLASS1_COMBO, HidDescriptor.REPORT_MAP)
                val qos = BluetoothHidDeviceAppQosSettings(
                    BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250, -1)
                register(h, sdp, qos, 1)
            }
            override fun onServiceDisconnected(profile: Int) { hid = null }
        }, BluetoothProfile.HID_DEVICE)
    }


    /**
     * HID の登録は端末全体で 1 つだけで、アプリが強制終了や再インストールされても、Bluetooth 側に
     * 前の登録が残って registerApp が false になることがある。同じ uid なら新しいプロセスからでも
     * unregisterApp で解除できるので、解除して少し待ち、登録し直す（解除は非同期なので数回まで繰り返す）
     */
    private fun register(h: BluetoothHidDevice, sdp: BluetoothHidDeviceAppSdpSettings,
                         qos: BluetoothHidDeviceAppQosSettings, attempt: Int) {
        if (stopped) return
        val ok = h.registerApp(sdp, null, qos, executor, callback)
        onLog("registerApp(試行 $attempt/$MAX_REGISTER_ATTEMPTS) -> $ok")
        if (ok) return
        if (attempt >= MAX_REGISTER_ATTEMPTS) {
            registering = false
            onState("登録に失敗しました。「Bluetooth を入れ直して登録し直す」を押してください")
            return
        }
        onState("前の登録を解除して再試行しています…")
        // 前のプロセスが残した接続があると、解除が「進行中」のまま終わらない。先に切断してから解除する
        val connected = h.connectedDevices
        onLog("接続が残っている機器: ${connected.map { it.address }}")
        connected.forEach { onLog("disconnect(${it.address}) -> ${h.disconnect(it)}") }
        onLog("unregisterApp() -> ${h.unregisterApp()}")
        mainHandler.postDelayed({ register(h, sdp, qos, attempt + 1) }, REGISTER_RETRY_MS)
    }

    fun bondedDevices(): List<BluetoothDevice> = adapter?.bondedDevices?.toList().orEmpty()

    fun connect(device: BluetoothDevice) {
        onLog("connect(${device.name}/${device.address}) -> ${hid?.connect(device)}")
    }

    fun stop() {
        stopped = true
        mainHandler.removeCallbacksAndMessages(null)
        hid?.unregisterApp()
        adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        hid = null
    }

    private fun send(id: Int, data: ByteArray): Boolean {
        val d = host
        if (d == null) {
            // 接続前に押したキーが黙って消えないよう、ログに残す（マウスは量が多いので除く）
            if (id != HidDescriptor.ID_MOUSE) onLog("未接続のため送信しません id=$id")
            return false
        }
        val ok = hid?.sendReport(d, id, data) ?: false
        if (!ok && id != HidDescriptor.ID_MOUSE) onLog("sendReport 失敗 id=$id")
        return ok
    }

    /** modifiers: ビットマスク, keys: 最大6個の HID keycode */
    fun sendKeyboard(modifiers: Int, keys: List<Int>) {
        val r = ByteArray(8)
        r[0] = modifiers.toByte()
        keys.take(6).forEachIndexed { i, k -> r[2 + i] = k.toByte() }
        send(HidDescriptor.ID_KEYBOARD, r)
    }

    fun sendMouse(buttons: Int, dx: Int, dy: Int, wheel: Int = 0) {
        send(HidDescriptor.ID_MOUSE, byteArrayOf(
            buttons.toByte(), dx.coerceIn(-127, 127).toByte(),
            dy.coerceIn(-127, 127).toByte(), wheel.coerceIn(-127, 127).toByte()))
    }

    fun sendConsumer(usage: Int) {
        send(HidDescriptor.ID_CONSUMER, byteArrayOf((usage and 0xFF).toByte(), (usage shr 8).toByte()))
    }
}
