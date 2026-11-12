package jp.kamome.btkeyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlin.math.max

/**
 * レイアウトごとの背景画像の保存先。選んだ画像は、画面サイズに合わせて縮小した JPEG として
 * アプリ専用の領域に置く（端末の外へは出ない。元の画像を消しても、背景は残る）
 */
object BackgroundStore {
    private const val MAX_EDGE = 2400
    private const val TAG = "BTKB"

    private fun dir(c: Context) = File(c.filesDir, "bg").apply { mkdirs() }

    // レイアウト ID にファイル名に向かない文字が入っていても安全なように、英数字以外は置き換える
    private fun file(c: Context, id: String) = File(dir(c), id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg")

    fun exists(c: Context, id: String) = file(c, id).exists()

    fun load(c: Context, id: String): ImageBitmap? =
        file(c, id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }

    fun delete(c: Context, id: String) { file(c, id).delete() }

    fun deleteAll(c: Context) { dir(c).listFiles()?.forEach { it.delete() } }

    fun copy(c: Context, from: String, to: String) {
        val src = file(c, from)
        if (src.exists()) src.copyTo(file(c, to), overwrite = true)
    }

    /** 画像を読み込んで縮小し、保存する。読めない画像なら false（時間がかかるので、メインスレッド以外で呼ぶ） */
    fun save(c: Context, id: String, uri: Uri): Boolean = runCatching {
        val cr = c.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = cr.openInputStream(uri)
        if (stream == null) { Log.w(TAG, "画像を開けません: $uri"); return false }
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { Log.w(TAG, "画像の大きさを読めません: $uri"); return false }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
        val decoded = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        if (decoded == null) { Log.w(TAG, "画像を読み込めません: $uri"); return false }
        val rotation = cr.openInputStream(uri)?.use { rotationOf(it) } ?: 0
        val bitmap = if (rotation == 0) decoded else Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        file(c, id).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        true
    }.onFailure { Log.w(TAG, "背景画像の保存に失敗", it) }.getOrDefault(false)

    /** 写真の向き（EXIF）を回転角にする。撮影時の向きのまま横になった写真を、正しい向きで敷くため */
    private fun rotationOf(input: java.io.InputStream): Int =
        when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
}
