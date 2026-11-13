package jp.kamome.btkeyboard.video

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** 画面を描く側が、映像の受信を参照するための入り口（なければ null＝映像は使わない） */
val LocalVideo = compositionLocalOf<VideoReceiver?> { null }

/**
 * 映像の面。送信側の映像の縦横比を保って、中央に収める（余った所は、背景のまま）。
 * この部品が画面から外れると、面（Surface）が壊れ、デコーダが止まる。戻ると、作り直されて、キーフレームから再開する。
 */
@Composable
fun VideoLayer(receiver: VideoReceiver, modifier: Modifier = Modifier) {
    val w = receiver.streamWidth
    val h = receiver.streamHeight
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (w > 0 && h > 0) {
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = receiver.attachSurface(holder.surface)
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                            override fun surfaceDestroyed(holder: SurfaceHolder) = receiver.detachSurface()
                        })
                    }
                },
                modifier = Modifier.aspectRatio(w.toFloat() / h),
            )
        }
    }
}
