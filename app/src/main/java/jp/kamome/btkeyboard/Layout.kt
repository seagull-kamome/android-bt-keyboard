package jp.kamome.btkeyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** キー 1 つの動作の種類 */
enum class KeyKind {
    KEY,       // 通常キー（value = HID keycode）
    MOD,       // 修飾キー（value = ビット、タップでラッチ）
    CONSUMER,  // メディアキー（value = Consumer Usage ID）
    SWITCH,    // 画面切替（target = レイアウト ID / "trackpad" / "setup"）
    HOLD,      // 押している間だけトラックパッドを重ねて表示する（指を離すと戻る）
}

data class KeyDef(
    val label: String,
    val kind: KeyKind,
    val value: Int = 0,
    val weight: Float = 1f,
    val target: String = "",
)

/**
 * レイアウトごとの見た目。背景画像そのものは JSON に入れられないので、アプリ内のファイル（BackgroundStore）に置き、
 * ここには、暗さ・キーの不透明度・文字色の数値だけを持つ（JSON のコピーと貼り付けで一緒に運ばれる）
 */
data class LayoutStyle(
    val dim: Int = 35,           // 背景画像を暗くする割合（0〜70 %）
    val keyAlpha: Int = 100,     // キーの不透明度（10〜100 %）
    val textColor: Int? = null,  // 文字色（ARGB）。null なら既定（背景画像があれば白）
)

data class KeyboardLayout(
    val id: String, val name: String, val rows: List<List<KeyDef>>,
    val style: LayoutStyle = LayoutStyle(),
)

fun LayoutStyle.toJson(): JSONObject = JSONObject().put("dim", dim).put("keyAlpha", keyAlpha)
    .apply { textColor?.let { put("textColor", it) } }

fun JSONObject?.toStyle(): LayoutStyle {
    if (this == null) return LayoutStyle()
    val d = LayoutStyle()
    return LayoutStyle(
        optInt("dim", d.dim).coerceIn(0, 70),
        optInt("keyAlpha", d.keyAlpha).coerceIn(10, 100),
        if (has("textColor")) getInt("textColor") else null,
    )
}

fun KeyDef.toJson(): JSONObject = JSONObject()
    .put("label", label).put("kind", kind.name).put("value", value)
    .put("weight", weight.toDouble()).put("target", target)

fun JSONObject.toKeyDef() = KeyDef(
    getString("label"), KeyKind.valueOf(getString("kind")), optInt("value"),
    optDouble("weight", 1.0).toFloat(), optString("target"),
)

fun KeyboardLayout.toJson(): JSONObject = JSONObject()
    .put("id", id).put("name", name)
    .put("rows", JSONArray(rows.map { row -> JSONArray(row.map { it.toJson() }) }))
    .put("style", style.toJson())

fun JSONObject.toLayout(): KeyboardLayout {
    val rows = getJSONArray("rows")
    return KeyboardLayout(getString("id"), getString("name"), List(rows.length()) { r ->
        val row = rows.getJSONArray(r)
        List(row.length()) { row.getJSONObject(it).toKeyDef() }
    }, optJSONObject("style").toStyle())
}

/** レイアウトの保存先。ファイルが無い、または読めないときは既定のプリセットを返す */
object LayoutStore {
    private fun file(c: Context) = File(c.filesDir, "layouts.json")

    fun load(c: Context): List<KeyboardLayout> = runCatching {
        val a = JSONArray(file(c).readText())
        List(a.length()) { a.getJSONObject(it).toLayout() }.ifEmpty { null }
    }.getOrNull() ?: Presets.all

    fun save(c: Context, layouts: List<KeyboardLayout>) {
        file(c).writeText(JSONArray(layouts.map { it.toJson() }).toString())
    }
}

object Presets {
    private fun k(label: String, code: Int, w: Float = 1f) = KeyDef(label, KeyKind.KEY, code, w)
    private fun m(label: String, mask: Int, w: Float = 1f) = KeyDef(label, KeyKind.MOD, mask, w)
    private fun c(label: String, usage: Int, w: Float = 1f) = KeyDef(label, KeyKind.CONSUMER, usage, w)
    private fun sw(label: String, target: String, w: Float = 1f) = KeyDef(label, KeyKind.SWITCH, 0, w, target)
    private fun hold(label: String, w: Float = 1f) = KeyDef(label, KeyKind.HOLD, 0, w, "trackpad")
    private fun chars(s: String) = s.map { k(it.toString(), Hid.ch(it)) }

    val jis = KeyboardLayout("jis", "日本語キーボード", listOf(
        (0 until 12).map { k("F${it + 1}", Hid.F1 + it) },
        listOf(k("半全", Hid.HANKAKU)) + chars("1234567890") +
            listOf(k("-", Hid.MINUS), k("^", Hid.CARET), k("¥", Hid.INT_YEN), k("BS", Hid.BACKSPACE, 1.5f)),
        listOf(k("Tab", Hid.TAB, 1.5f)) + chars("qwertyuiop") +
            listOf(k("@", Hid.AT), k("[", Hid.LBRACKET), k("Enter", Hid.ENTER, 1.5f)),
        listOf(m("Ctrl", Hid.MOD_CTRL, 1.8f)) + chars("asdfghjkl") +
            listOf(k(";", Hid.SEMICOLON), k(":", Hid.COLON), k("]", Hid.RBRACKET)),
        listOf(m("Shift", Hid.MOD_SHIFT, 2.2f)) + chars("zxcvbnm") +
            listOf(k(",", Hid.COMMA), k(".", Hid.PERIOD), k("/", Hid.SLASH), k("\\", Hid.INT_BACKSLASH),
                m("Shift", Hid.MOD_SHIFT, 1.5f)),
        listOf(
            k("Esc", Hid.ESC), m("Win", Hid.MOD_GUI), m("Alt", Hid.MOD_ALT),
            k("無変換", Hid.INT_MUHENKAN, 1.6f), k("Space", Hid.SPACE, 3.5f), k("変換", Hid.INT_HENKAN, 1.3f),
            k("かな", Hid.INT_KANA, 1.4f), k("Del", Hid.DELETE),
        ),
        listOf(
            hold("パッド(押)", 1.6f), k("←", Hid.LEFT), k("↓", Hid.DOWN), k("↑", Hid.UP), k("→", Hid.RIGHT),
            k("Home", Hid.HOME, 1.3f), k("End", Hid.END, 1.3f), k("PgUp", Hid.PAGE_UP, 1.3f), k("PgDn", Hid.PAGE_DOWN, 1.3f),
            sw("パッド", "trackpad", 1.6f), sw("メディア", "media", 1.6f), sw("接続", "setup", 1.3f),
        ),
    ))

    val media = KeyboardLayout("media", "メディアリモコン", listOf(
        listOf(c("⏮ 前", Consumer.PREV), c("⏯ 再生/停止", Consumer.PLAY_PAUSE, 2f), c("⏭ 次", Consumer.NEXT)),
        listOf(c("⏪ 巻戻し", Consumer.REWIND), c("⏹ 停止", Consumer.STOP), c("⏩ 早送り", Consumer.FAST_FORWARD)),
        listOf(c("🔉 音量−", Consumer.VOL_DOWN), c("🔇 ミュート", Consumer.MUTE), c("🔊 音量＋", Consumer.VOL_UP)),
        listOf(c("ブラウザ", Consumer.BROWSER_HOME), c("メール", Consumer.MAIL), c("電卓", Consumer.CALCULATOR)),
        listOf(k("←", Hid.LEFT), k("↑", Hid.UP), k("↓", Hid.DOWN), k("→", Hid.RIGHT),
            k("Space", Hid.SPACE, 2f), k("Enter", Hid.ENTER, 1.5f), k("Esc", Hid.ESC)),
        listOf(sw("キーボード", "jis", 1.5f), sw("パッド", "trackpad", 1.5f), sw("接続", "setup")),
    ))

    val all = listOf(jis, media)
}
