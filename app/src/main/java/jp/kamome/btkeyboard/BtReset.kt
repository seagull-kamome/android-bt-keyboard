package jp.kamome.btkeyboard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Bluetooth をオフ→オンして、Bluetooth 側に固まった HID の登録を消す。
 * アプリが強制終了されたあと、前の登録の解除が「進行中」のまま終わらなくなることがあり、
 * アプリからは解除できないので、これが唯一の回復手段になる。
 *
 * Android 13 以降は一般のアプリが Bluetooth を切り替えられないため false を返す（設定画面へ案内する）。
 * 完了すると onReady をメインスレッドで呼ぶ。
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
fun restartBluetooth(onLog: (String) -> Unit, onReady: () -> Unit): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return false
    val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
    val handler = Handler(Looper.getMainLooper())
    var waitingForOn = false
    var polls = 0
    val poll = object : Runnable {
        override fun run() {
            polls++
            when {
                polls > 60 -> { onLog("Bluetooth の入れ直しがタイムアウトしました"); return }
                !waitingForOn && adapter.state == BluetoothAdapter.STATE_OFF -> {
                    onLog("Bluetooth をオフにしました。オンに戻します")
                    adapter.enable(); waitingForOn = true
                }
                waitingForOn && adapter.state == BluetoothAdapter.STATE_ON -> {
                    onLog("Bluetooth がオンになりました")
                    onReady(); return
                }
            }
            handler.postDelayed(this, 300)
        }
    }
    onLog("Bluetooth をオフにします")
    if (adapter.state != BluetoothAdapter.STATE_OFF) adapter.disable()
    handler.postDelayed(poll, 300)
    return true
}
