package jp.kamome.btkeyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** キーの押下・解放を HID レポートに変換する。修飾キーはタップでラッチし、次のキーを離すと解除する */
class InputState(val hid: HidController) {
    var mods by mutableIntStateOf(0)
        private set
    private val keys = mutableListOf<Int>()

    fun down(k: KeyDef) {
        when (k.kind) {
            KeyKind.KEY -> { keys.add(k.value); hid.sendKeyboard(mods, keys) }
            KeyKind.MOD -> { mods = mods xor k.value; hid.sendKeyboard(mods, keys) }
            KeyKind.CONSUMER -> hid.sendConsumer(k.value)
            KeyKind.SWITCH, KeyKind.HOLD -> {}
        }
    }

    fun up(k: KeyDef) {
        when (k.kind) {
            KeyKind.KEY -> { keys.removeAll { it == k.value }; mods = 0; hid.sendKeyboard(0, keys) }
            KeyKind.CONSUMER -> hid.sendConsumer(0)
            else -> {}
        }
    }
}

@Composable
fun KeyboardScreen(layout: KeyboardLayout, input: InputState, onSwitch: (String) -> Unit, padButtonsLeft: Boolean) {
    // 「押す間切替」キーが押されている数。1 以上の間、トラックパッドを重ねて表示する
    var holds by remember { mutableIntStateOf(0) }
    val onHold: (Boolean) -> Unit = { down -> holds = (holds + if (down) 1 else -1).coerceAtLeast(0) }
    Box(Modifier.fillMaxSize()) {
        Column(
            // 押す間切替の間は見えなくする（描画の透明度を 0 にしても、押している指のタッチはキーに届き続ける）
            Modifier.fillMaxSize().alpha(if (holds > 0) 0f else 1f).padding(horizontal = 3.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            layout.rows.forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEach { k -> KeyButton(k, input, onSwitch, onHold, Modifier.weight(k.weight).fillMaxHeight()) }
                }
            }
        }
        // キーを押している指のタッチは、キーが画面に残っている限りそのキーに届き続ける。
        // 別の指の新しいタッチは、この上に重ねたトラックパッドが受け取る。
        // 外側の Box は、パッドやボタンの外側のタッチが、見えなくなったキーに届かないように受け止める
        if (holds > 0) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
                TrackpadScreen(input.hid, {}, padButtonsLeft, {}, overlay = true)
            }
        }
    }
}

@Composable
private fun KeyButton(k: KeyDef, input: InputState, onSwitch: (String) -> Unit, onHold: (Boolean) -> Unit, modifier: Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val latched = k.kind == KeyKind.MOD && (input.mods and k.value) != 0
    val switch by rememberUpdatedState(onSwitch)
    val colors = MaterialTheme.colorScheme
    val bg = when {
        pressed || latched -> colors.primary
        k.kind == KeyKind.KEY -> colors.surfaceVariant.withKeyAlpha()
        else -> colors.secondaryContainer.withKeyAlpha()
    }
    val textColor = labelColor(pressed || latched, colors.onSurface, colors.onPrimary)
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .pointerInput(k) {
                detectTapGestures(onPress = {
                    pressed = true
                    var completed = false
                    try {
                        if (k.kind == KeyKind.HOLD) onHold(true) else input.down(k)
                        completed = tryAwaitRelease()
                    } finally {
                        pressed = false
                        if (k.kind == KeyKind.HOLD) onHold(false) else input.up(k)
                    }
                    if (completed && k.kind == KeyKind.SWITCH) switch(k.target)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            k.label, fontSize = when (k.label.length) { 1 -> 15.sp; 2 -> 13.sp; 3 -> 11.sp; else -> 9.sp },
            color = textColor, style = keyTextStyle(textColor),
            maxLines = 1, softWrap = false,
        )
    }
}

/**
 * トラックパッド: 1 本指=移動、タップ=左クリック、2 本指ドラッグ=スクロール、2 本指タップ=右クリック。
 * overlay=true は「押す間切替」で重ねる表示で、画面切替ボタンは出さない。
 * 横向きではクリックボタンの列を左右どちらにも置ける（buttonsOnLeft）
 */
@Composable
fun TrackpadScreen(
    hid: HidController, onSwitch: (String) -> Unit,
    buttonsOnLeft: Boolean, onToggleSide: () -> Unit, overlay: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val pad: @Composable (Modifier) -> Unit = { m ->
        Box(
            m.clip(RoundedCornerShape(12.dp)).background(colors.surfaceVariant.withKeyAlpha())
                .pointerInput(Unit) { trackpadGestures(hid) },
            contentAlignment = Alignment.Center,
        ) {
            val c = keyTextColor(colors.onSurfaceVariant)
            Text(if (overlay) "トラックパッド（キーを離すと戻ります）" else "トラックパッド", color = c, style = keyTextStyle(c))
        }
    }
    if (landscape) {
        // 横向き: パッドと、クリックボタン・切替ボタンの列を横に並べる
        val panel: @Composable () -> Unit = {
            Column(Modifier.width(if (overlay) 160.dp else 200.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MouseButton("左", 1, hid, Modifier.weight(2f).fillMaxWidth())
                MouseButton("右", 2, hid, Modifier.weight(2f).fillMaxWidth())
                if (!overlay) {
                    SwitchButton("キーボード", "jis", onSwitch, Modifier.weight(1f).fillMaxWidth())
                    SwitchButton("メディア", "media", onSwitch, Modifier.weight(1f).fillMaxWidth())
                    SwitchButton("接続", "setup", onSwitch, Modifier.weight(1f).fillMaxWidth())
                    ActionButton(if (buttonsOnLeft) "ボタンを右へ ▶" else "◀ ボタンを左へ", onToggleSide, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        Row(Modifier.fillMaxSize().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (buttonsOnLeft) panel()
            pad(Modifier.weight(1f).fillMaxHeight())
            if (!buttonsOnLeft) panel()
        }
    } else {
        Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            pad(Modifier.weight(1f).fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MouseButton("左", 1, hid, Modifier.weight(1f).height(64.dp))
                MouseButton("右", 2, hid, Modifier.weight(1f).height(64.dp))
            }
            if (!overlay) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SwitchButton("キーボード", "jis", onSwitch, Modifier.weight(1f).height(48.dp))
                SwitchButton("メディア", "media", onSwitch, Modifier.weight(1f).height(48.dp))
                SwitchButton("接続", "setup", onSwitch, Modifier.weight(1f).height(48.dp))
            }
        }
    }
}

private const val SENSITIVITY = 1.6f
private const val SCROLL_PX_PER_NOTCH = 14f

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.trackpadGestures(hid: HidController) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startTime = down.uptimeMillis
        var lastTime = startTime
        var maxPointers = 1
        var travel = 0f
        var remX = 0f
        var remY = 0f
        var scrollRem = 0f
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size > maxPointers) maxPointers = pressed.size
            lastTime = event.changes.maxOf { it.uptimeMillis }
            if (pressed.size == 1 && maxPointers == 1) {
                val d = pressed[0].positionChange()
                travel += abs(d.x) + abs(d.y)
                remX += d.x * SENSITIVITY
                remY += d.y * SENSITIVITY
                val ix = remX.toInt()
                val iy = remY.toInt()
                if (ix != 0 || iy != 0) { hid.sendMouse(0, ix, iy); remX -= ix; remY -= iy }
            } else if (pressed.size >= 2) {
                val dy = pressed.map { it.positionChange().y }.average().toFloat()
                travel += abs(dy)
                scrollRem += dy / SCROLL_PX_PER_NOTCH
                val notches = scrollRem.toInt()
                if (notches != 0) { hid.sendMouse(0, 0, 0, notches); scrollRem -= notches }
            }
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
        if (travel < viewConfiguration.touchSlop * 2 && lastTime - startTime < 300) {
            val button = if (maxPointers >= 2) 2 else 1
            hid.sendMouse(button, 0, 0)
            hid.sendMouse(0, 0, 0)
        }
    }
}

@Composable
private fun MouseButton(label: String, mask: Int, hid: HidController, modifier: Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (pressed) colors.primary else colors.secondaryContainer.withKeyAlpha())
            .pointerInput(mask) {
                detectTapGestures(onPress = {
                    pressed = true
                    hid.sendMouse(mask, 0, 0)
                    tryAwaitRelease()
                    pressed = false
                    hid.sendMouse(0, 0, 0)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        val c = labelColor(pressed, colors.onSurface, colors.onPrimary)
        Text(label, color = c, style = keyTextStyle(c))
    }
}

@Composable
private fun SwitchButton(label: String, target: String, onSwitch: (String) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(colors.tertiaryContainer.withKeyAlpha())
            .pointerInput(target) { detectTapGestures(onTap = { onSwitch(target) }) },
        contentAlignment = Alignment.Center,
    ) {
        val c = keyTextColor(colors.onTertiaryContainer)
        Text(label, color = c, style = keyTextStyle(c), fontSize = 13.sp)
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(colors.secondaryContainer.withKeyAlpha())
            .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        val c = keyTextColor(colors.onSecondaryContainer)
        Text(label, color = c, style = keyTextStyle(c), fontSize = 12.sp)
    }
}

/** 押している間は、指定された文字色（なければ onPressed）。そうでなければ、見た目の設定に従った文字色 */
@Composable
private fun labelColor(pressed: Boolean, normal: Color, onPressed: Color): Color =
    if (pressed) (LocalKeyStyle.current.textColor ?: onPressed) else keyTextColor(normal)
