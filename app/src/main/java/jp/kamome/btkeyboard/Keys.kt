package jp.kamome.btkeyboard

/** HID Usage Page 0x07（キーボード）のキーコードと修飾キーのビット */
object Hid {
    const val MOD_CTRL = 0x01
    const val MOD_SHIFT = 0x02
    const val MOD_ALT = 0x04
    const val MOD_GUI = 0x08

    const val ENTER = 0x28
    const val ESC = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val MINUS = 0x2D
    const val CARET = 0x2E          // JIS: ^
    const val AT = 0x2F             // JIS: @
    const val LBRACKET = 0x30       // JIS: [
    const val RBRACKET = 0x32       // JIS: ]
    const val SEMICOLON = 0x33
    const val COLON = 0x34          // JIS: :
    const val HANKAKU = 0x35        // 半角/全角
    const val COMMA = 0x36
    const val PERIOD = 0x37
    const val SLASH = 0x38
    const val F1 = 0x3A
    const val HOME = 0x4A
    const val PAGE_UP = 0x4B
    const val DELETE = 0x4C
    const val END = 0x4D
    const val PAGE_DOWN = 0x4E
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
    const val INT_BACKSLASH = 0x87  // JIS: \（ろ）
    const val INT_KANA = 0x88       // ひらがな/カタカナ
    const val INT_YEN = 0x89        // JIS: ¥
    const val INT_HENKAN = 0x8A     // 変換
    const val INT_MUHENKAN = 0x8B   // 無変換

    /** 英数字 1 文字のキーコード（a-z, 0-9） */
    fun ch(c: Char): Int = when (c) {
        in 'a'..'z' -> 0x04 + (c - 'a')
        in '1'..'9' -> 0x1E + (c - '1')
        '0' -> 0x27
        else -> error("unsupported char: $c")
    }
}

/** HID Usage Page 0x0C（コンシューマコントロール）の Usage ID */
object Consumer {
    const val PLAY_PAUSE = 0xCD
    const val NEXT = 0xB5
    const val PREV = 0xB6
    const val STOP = 0xB7
    const val FAST_FORWARD = 0xB3
    const val REWIND = 0xB4
    const val MUTE = 0xE2
    const val VOL_UP = 0xE9
    const val VOL_DOWN = 0xEA
    const val BROWSER_HOME = 0x223
    const val MAIL = 0x18A
    const val CALCULATOR = 0x192
}

/** レイアウト編集で選べるキーの一覧（名前, コード） */
data class CatalogEntry(val name: String, val code: Int)

object Catalog {
    val keys: List<CatalogEntry> = buildList {
        ('a'..'z').forEach { add(CatalogEntry(it.toString(), Hid.ch(it))) }
        ('0'..'9').forEach { add(CatalogEntry(it.toString(), Hid.ch(it))) }
        (1..12).forEach { add(CatalogEntry("F$it", Hid.F1 + it - 1)) }
        addAll(listOf(
            CatalogEntry("Enter", Hid.ENTER), CatalogEntry("Esc", Hid.ESC), CatalogEntry("BS", Hid.BACKSPACE),
            CatalogEntry("Tab", Hid.TAB), CatalogEntry("Space", Hid.SPACE),
            CatalogEntry("-", Hid.MINUS), CatalogEntry("^", Hid.CARET), CatalogEntry("¥", Hid.INT_YEN),
            CatalogEntry("@", Hid.AT), CatalogEntry("[", Hid.LBRACKET), CatalogEntry("]", Hid.RBRACKET),
            CatalogEntry(";", Hid.SEMICOLON), CatalogEntry(":", Hid.COLON), CatalogEntry(",", Hid.COMMA),
            CatalogEntry(".", Hid.PERIOD), CatalogEntry("/", Hid.SLASH), CatalogEntry("\\", Hid.INT_BACKSLASH),
            CatalogEntry("半全", Hid.HANKAKU), CatalogEntry("無変換", Hid.INT_MUHENKAN),
            CatalogEntry("変換", Hid.INT_HENKAN), CatalogEntry("かな", Hid.INT_KANA),
            CatalogEntry("CapsLock", 0x39), CatalogEntry("PrtSc", 0x46), CatalogEntry("Insert", 0x49),
            CatalogEntry("Del", Hid.DELETE), CatalogEntry("Home", Hid.HOME), CatalogEntry("End", Hid.END),
            CatalogEntry("PgUp", Hid.PAGE_UP), CatalogEntry("PgDn", Hid.PAGE_DOWN),
            CatalogEntry("←", Hid.LEFT), CatalogEntry("↓", Hid.DOWN), CatalogEntry("↑", Hid.UP),
            CatalogEntry("→", Hid.RIGHT), CatalogEntry("Menu", 0x65),
        ))
    }
    val mods = listOf(
        CatalogEntry("Ctrl", Hid.MOD_CTRL), CatalogEntry("Shift", Hid.MOD_SHIFT),
        CatalogEntry("Alt", Hid.MOD_ALT), CatalogEntry("Win", Hid.MOD_GUI),
    )
    val consumer = listOf(
        CatalogEntry("再生/停止", Consumer.PLAY_PAUSE), CatalogEntry("次", Consumer.NEXT),
        CatalogEntry("前", Consumer.PREV), CatalogEntry("停止", Consumer.STOP),
        CatalogEntry("早送り", Consumer.FAST_FORWARD), CatalogEntry("巻戻し", Consumer.REWIND),
        CatalogEntry("ミュート", Consumer.MUTE), CatalogEntry("音量＋", Consumer.VOL_UP),
        CatalogEntry("音量−", Consumer.VOL_DOWN), CatalogEntry("ブラウザ", Consumer.BROWSER_HOME),
        CatalogEntry("メール", Consumer.MAIL), CatalogEntry("電卓", Consumer.CALCULATOR),
    )

    fun entries(kind: KeyKind): List<CatalogEntry> = when (kind) {
        KeyKind.KEY -> keys
        KeyKind.MOD -> mods
        KeyKind.CONSUMER -> consumer
        KeyKind.SWITCH, KeyKind.HOLD -> emptyList()
    }

    /** 既定のキー（種類を切り替えたとき最初に選ぶもの） */
    fun defaultKey(kind: KeyKind, layoutIds: List<Pair<String, String>>): KeyDef = when (kind) {
        KeyKind.SWITCH -> layoutIds.firstOrNull()?.let { (id, name) -> KeyDef(name, kind, 0, 1f, id) }
            ?: KeyDef("接続", kind, 0, 1f, "setup")
        KeyKind.HOLD -> KeyDef("パッド(押)", kind, 0, 1.6f, "trackpad")
        else -> entries(kind).first().let { KeyDef(it.name, kind, it.code) }
    }
}
