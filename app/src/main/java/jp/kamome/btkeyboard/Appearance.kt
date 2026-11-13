package jp.kamome.btkeyboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import jp.kamome.btkeyboard.video.LocalVideo
import jp.kamome.btkeyboard.video.VideoLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 各画面のキーが参照する、レイアウトごとの見た目。AppearanceBackground が配下へ渡す */
data class KeyStyle(
    val keyAlpha: Float = 1f,       // キーの不透明度
    val textColor: Color? = null,   // 指定された文字色
    val onImage: Boolean = false,   // 背景画像の上に表示しているか
)

val LocalKeyStyle = compositionLocalOf { KeyStyle() }

/**
 * レイアウトの背景画像（あれば）と、画像を暗くする膜を、いちばん奥に敷いて、その上に content を置く。
 * bgVersion は、画像を差し替えたときに読み直すための番号
 */
@Composable
fun AppearanceBackground(layoutId: String, style: LayoutStyle, bgVersion: Int, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val image by produceState<ImageBitmap?>(null, layoutId, bgVersion) {
        value = withContext(Dispatchers.IO) { BackgroundStore.load(ctx, layoutId) }
    }
    // 映像を受信中なら、背景画像の代わりに映像を敷く（映像がなければ、従来どおり）
    val video = LocalVideo.current?.takeIf { it.active }
    val keyStyle = KeyStyle(style.keyAlpha / 100f, style.textColor?.let { Color(it) }, image != null || video != null)
    Box(Modifier.fillMaxSize()) {
        if (video != null) {
            Box(Modifier.fillMaxSize().background(Color.Black))
            VideoLayer(video)
            if (style.dim > 0) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = style.dim / 100f)))
        } else image?.let {
            Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (style.dim > 0) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = style.dim / 100f)))
        }
        CompositionLocalProvider(LocalKeyStyle provides keyStyle) { content() }
    }
}

/** 文字色: 指定があればそれ、背景画像があれば白、なければ fallback */
@Composable
fun keyTextColor(fallback: Color): Color {
    val s = LocalKeyStyle.current
    return s.textColor ?: if (s.onImage) Color.White else fallback
}

/** キー（や面）の背景色に、キーの不透明度を掛ける */
@Composable
fun Color.withKeyAlpha(): Color = copy(alpha = alpha * LocalKeyStyle.current.keyAlpha)

/** 背景画像の上や透けたキーの上でも読めるよう、文字色と明るさが逆の影を付ける。画像も透過もなければ付けない */
@Composable
fun keyTextStyle(textColor: Color): TextStyle {
    val s = LocalKeyStyle.current
    if (!s.onImage && s.keyAlpha >= 1f) return TextStyle.Default
    val shadow = if (textColor.luminance() > 0.5f) Color.Black.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.85f)
    return TextStyle(shadow = Shadow(color = shadow, offset = Offset(2f, 2f), blurRadius = 6f))
}
