package jp.kamome.btkeyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

private val SMALL_PADDING = PaddingValues(horizontal = 10.dp, vertical = 4.dp)

private fun newId() = "custom${System.currentTimeMillis()}"

private fun KeyboardLayout.replaceKey(r: Int, c: Int, key: KeyDef) =
    copy(rows = rows.mapIndexed { ri, row -> if (ri == r) row.mapIndexed { ci, k -> if (ci == c) key else k } else row })

private fun KeyboardLayout.removeKey(r: Int, c: Int) =
    copy(rows = rows.mapIndexed { ri, row -> if (ri == r) row.filterIndexed { ci, _ -> ci != c } else row }
        .filter { it.isNotEmpty() })

private fun KeyboardLayout.moveKey(r: Int, c: Int, delta: Int): KeyboardLayout {
    val row = rows[r].toMutableList()
    val to = c + delta
    if (to !in row.indices) return this
    row[c] = row[to].also { row[to] = row[c] }
    return copy(rows = rows.mapIndexed { ri, old -> if (ri == r) row else old })
}

private fun KeyboardLayout.addKey(r: Int, key: KeyDef) =
    copy(rows = rows.mapIndexed { ri, row -> if (ri == r) row + key else row })

/** レイアウト編集画面。変更はそのつど onChange で保存側へ渡す */
@Composable
fun LayoutEditorScreen(layouts: List<KeyboardLayout>, onChange: (List<KeyboardLayout>) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var currentId by remember { mutableStateOf(layouts.first().id) }
    val layout = layouts.firstOrNull { it.id == currentId } ?: layouts.first()
    var sel by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var editing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var appearance by remember { mutableStateOf(false) }
    var bgVersion by remember { mutableIntStateOf(0) }   // 画像を差し替えたとき、背景を読み直すための番号
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val id = layout.id
            scope.launch {
                val ok = withContext(Dispatchers.IO) { BackgroundStore.save(ctx, id, uri) }
                if (ok) bgVersion++ else message = "画像を読み込めませんでした"
            }
        }
    }

    fun setLayout(l: KeyboardLayout) = onChange(layouts.map { if (it.id == l.id) l else it })
    fun addLayout(l: KeyboardLayout) { onChange(layouts + l); currentId = l.id; sel = null }
    val defaultKey = KeyDef("a", KeyKind.KEY, Hid.ch('a'))

    AppearanceBackground(layout.id, layout.style, bgVersion) {
    Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // 1 段目: レイアウトの選択と、レイアウト単位の操作
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), RoundedCornerShape(12.dp)).horizontalScroll(rememberScrollState()).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallButton("閉じる", onClick = onClose)
            Picker(layout.name, layouts.map { it.name to it.id }) { currentId = it; sel = null }
            SmallButton("新規") {
                addLayout(KeyboardLayout(newId(), "新しいレイアウト",
                    listOf(listOf(defaultKey, KeyDef("接続", KeyKind.SWITCH, 0, 1f, "setup")))))
            }
            SmallButton("複製") {
                val id = newId()
                addLayout(layout.copy(id = id, name = layout.name + " コピー"))
                BackgroundStore.copy(ctx, layout.id, id)
            }
            SmallButton("名前") { renaming = true }
            SmallButton("見た目") { appearance = true }
            SmallButton("削除", enabled = layouts.size > 1) {
                val rest = layouts.filter { it.id != layout.id }
                onChange(rest); currentId = rest.first().id; sel = null
                BackgroundStore.delete(ctx, layout.id)
            }
            SmallButton("初期化") {
                onChange(Presets.all); currentId = Presets.all.first().id; sel = null
                BackgroundStore.deleteAll(ctx); bgVersion++
                message = "初期のレイアウトに戻しました"
            }
            SmallButton("JSON コピー") {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("layout", layout.toJson().toString(2)))
                message = "「${layout.name}」の JSON をクリップボードにコピーしました"
            }
            SmallButton("貼り付け") {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
                runCatching {
                    val parsed = if (text.trimStart().startsWith("[")) JSONArray(text).let { a -> List(a.length()) { a.getJSONObject(it).toLayout() } }
                    else listOf(JSONObject(text).toLayout())
                    val merged = parsed.fold(layouts) { acc, l ->
                        if (acc.any { it.id == l.id }) acc.map { if (it.id == l.id) l else it } else acc + l
                    }
                    onChange(merged); currentId = parsed.first().id; sel = null
                    message = "${parsed.size} 件のレイアウトを読み込みました"
                }.onFailure { message = "クリップボードの内容を読み込めませんでした: ${it.message}" }
            }
        }

        // プレビュー: 実際のキーボードと同じ割り付けで、タップで選択する
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            layout.rows.forEachIndexed { r, row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEachIndexed { c, k ->
                        EditorKey(k, sel == r to c, Modifier.weight(k.weight).fillMaxHeight()) { sel = r to c }
                    }
                }
            }
        }

        // 2 段目: 選択中のキーに対する操作
        val s = sel?.takeIf { (r, c) -> r in layout.rows.indices && c in layout.rows[r].indices }
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), RoundedCornerShape(12.dp)).horizontalScroll(rememberScrollState()).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (s != null) "選択: ${layout.rows[s.first][s.second].label}" else "キーを選んでください",
                fontSize = 12.sp, modifier = Modifier.padding(end = 4.dp))
            SmallButton("編集", enabled = s != null) { editing = true }
            SmallButton("◀", enabled = s != null) { s?.let { (r, c) -> setLayout(layout.moveKey(r, c, -1)); if (c > 0) sel = r to c - 1 } }
            SmallButton("▶", enabled = s != null) { s?.let { (r, c) -> setLayout(layout.moveKey(r, c, 1)); if (c < layout.rows[r].size - 1) sel = r to c + 1 } }
            SmallButton("削除", enabled = s != null) { s?.let { (r, c) -> setLayout(layout.removeKey(r, c)); sel = null } }
            SmallButton("この行にキー追加", enabled = layout.rows.isNotEmpty()) {
                val r = s?.first ?: layout.rows.lastIndex
                setLayout(layout.addKey(r, defaultKey)); sel = r to layout.rows[r].size; editing = true
            }
            SmallButton("行を追加") { setLayout(layout.copy(rows = layout.rows + listOf(listOf(defaultKey)))); sel = layout.rows.size to 0; editing = true }
            if (message.isNotEmpty()) Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
    }

    if (appearance) {
        val hasBackground = remember(layout.id, bgVersion) { BackgroundStore.exists(ctx, layout.id) }
        AppearanceDialog(
            layout.style, hasBackground = hasBackground,
            onStyle = { setLayout(layout.copy(style = it)) },
            onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onClear = { BackgroundStore.delete(ctx, layout.id); bgVersion++ },
            onDismiss = { appearance = false },
        )
    }
    if (editing) {
        val s = sel
        val key = s?.let { (r, c) -> layout.rows.getOrNull(r)?.getOrNull(c) }
        if (s != null && key != null) {
            KeyEditDialog(key, layouts, onDismiss = { editing = false }) { edited ->
                setLayout(layout.replaceKey(s.first, s.second, edited)); editing = false
            }
        } else editing = false
    }
    if (renaming) RenameDialog(layout.name, onDismiss = { renaming = false }) { setLayout(layout.copy(name = it)); renaming = false }
}

@Composable
private fun EditorKey(k: KeyDef, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.clip(RoundedCornerShape(6.dp))
            .background((if (k.kind == KeyKind.KEY) colors.surfaceVariant else colors.secondaryContainer).withKeyAlpha())
            .then(if (selected) Modifier.border(3.dp, colors.primary, RoundedCornerShape(6.dp)) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val c = keyTextColor(colors.onSurface)
        Text(k.label, fontSize = when (k.label.length) { 1 -> 14.sp; 2 -> 12.sp; 3 -> 10.sp; else -> 8.sp },
            maxLines = 1, softWrap = false, color = c, style = keyTextStyle(c))
    }
}

@Composable
private fun SmallButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, contentPadding = SMALL_PADDING) { Text(label, fontSize = 12.sp, maxLines = 1) }
}

/** 一覧から 1 つ選ぶドロップダウン */
@Composable
private fun <T> Picker(current: String, options: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, contentPadding = SMALL_PADDING) { Text("$current ▼", fontSize = 12.sp, maxLines = 1) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (name, value) -> DropdownMenuItem(text = { Text(name) }, onClick = { onPick(value); open = false }) }
        }
    }
}

/** 一覧を格子状に並べて 1 つ選ぶ。項目が多いとき、縦のドロップダウンより探しやすい */
@Composable
private fun <T> GridPicker(current: String, options: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, contentPadding = SMALL_PADDING) { Text("$current ▼", fontSize = 12.sp, maxLines = 1) }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            modifier = Modifier.fillMaxWidth(0.94f),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            text = {
                LazyVerticalGrid(
                    GridCells.Adaptive(76.dp), Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(options) { (name, value) ->
                        OutlinedButton(onClick = { onPick(value); open = false }, contentPadding = SMALL_PADDING) {
                            Text(name, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text("閉じる") } },
        )
    }
}

/** 文字色の候補（null は既定: 背景画像があれば白、なければテーマの色） */
private val TEXT_COLORS: List<Pair<String, Int?>> = listOf(
    "既定" to null, "白" to 0xFFFFFFFF.toInt(), "黒" to 0xFF000000.toInt(), "黄" to 0xFFFFEB3B.toInt(),
    "水色" to 0xFF80DEEA.toInt(), "桃" to 0xFFF48FB1.toInt(), "緑" to 0xFFA5D6A7.toInt(), "橙" to 0xFFFFB74D.toInt(),
)

/** レイアウトごとの見た目（背景画像、暗さ、キーの不透明度、文字色）。変更は、そのつど編集画面に反映される */
@Composable
private fun AppearanceDialog(
    style: LayoutStyle, hasBackground: Boolean,
    onStyle: (LayoutStyle) -> Unit, onPick: () -> Unit, onClear: () -> Unit, onDismiss: () -> Unit,
) {
    var hex by remember { mutableStateOf(style.textColor?.let { "%06X".format(it and 0xFFFFFF) } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("背景画像", fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallButton(if (hasBackground) "画像を変える" else "画像を選ぶ", onClick = onPick)
                        SmallButton("画像を消す", enabled = hasBackground, onClick = onClear)
                    }
                    Text("画像を暗くする: ${style.dim}%", fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    Slider(style.dim.toFloat(), { onStyle(style.copy(dim = it.roundToInt())) },
                        valueRange = 0f..70f, enabled = hasBackground)
                    Text("キーの不透明度: ${style.keyAlpha}%", fontSize = 12.sp)
                    Slider(style.keyAlpha.toFloat(), { onStyle(style.copy(keyAlpha = it.roundToInt())) }, valueRange = 10f..100f)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("文字の色（読みやすいよう、影が自動で付きます）", fontSize = 12.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TEXT_COLORS.forEach { (name, color) ->
                            val selected = style.textColor == color
                            Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
                                    .background(color?.let { Color(it) } ?: Color.Gray)
                                    .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.DarkGray, RoundedCornerShape(8.dp))
                                    .clickable { onStyle(style.copy(textColor = color)); hex = color?.let { "%06X".format(it and 0xFFFFFF) } ?: "" },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (color == null) Text(name, fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }
                    OutlinedTextField(hex, { v ->
                        hex = v.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                        if (hex.length == 6) onStyle(style.copy(textColor = 0xFF000000.toInt() or hex.toInt(16)))
                    }, label = { Text("色を直接指定（RRGGBB）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
                    // 見本: 暗い地の上で、いまの文字色と影を確かめる
                    val sample = style.textColor?.let { Color(it) } ?: Color.White
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF555555)).padding(8.dp)) {
                        Text("サンプル あア Aa 123", color = sample, style = TextStyle(shadow = Shadow(
                            color = if (sample.luminance() > 0.5f) Color.Black.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.85f),
                            offset = Offset(2f, 2f), blurRadius = 6f)))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

@Composable
private fun RenameDialog(name: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("レイアウト名") },
        // 横向きはキーボードが OK ボタンを隠すので、キーボードの確定キーでも決定できるようにする
        text = {
            OutlinedTextField(text, { text = it }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onOk(text.trim()) }))
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onOk(text.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

@Composable
private fun KeyEditDialog(key: KeyDef, layouts: List<KeyboardLayout>, onDismiss: () -> Unit, onOk: (KeyDef) -> Unit) {
    var draft by remember { mutableStateOf(key) }
    var weightText by remember { mutableStateOf(key.weight.toString()) }
    val layoutIds = layouts.map { it.id to it.name }
    val kindNames = listOf(KeyKind.KEY to "キー", KeyKind.MOD to "修飾キー", KeyKind.CONSUMER to "メディア",
        KeyKind.SWITCH to "画面切替", KeyKind.HOLD to "押す間切替")

    // 横向きは高さが足りないので、横幅を広く取り、左右 2 列に分けて 1 画面に収める
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(draft.label, { draft = draft.copy(label = it) }, label = { Text("表示名") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
                    Text("種類", fontSize = 12.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        kindNames.forEach { (kind, name) ->
                            FilterChip(selected = draft.kind == kind, label = { Text(name, fontSize = 12.sp) }, onClick = {
                                if (draft.kind != kind) draft = Catalog.defaultKey(kind, layoutIds).copy(weight = draft.weight)
                            })
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (draft.kind == KeyKind.SWITCH) {
                        val targets = layoutIds.map { (id, name) -> "レイアウト: $name" to id } +
                            listOf("トラックパッド" to "trackpad", "接続設定" to "setup")
                        Text("切替先", fontSize = 12.sp)
                        Picker(targets.firstOrNull { it.second == draft.target }?.first ?: draft.target, targets) { id ->
                            val name = targets.first { it.second == id }.first.removePrefix("レイアウト: ")
                            draft = draft.copy(target = id, label = if (draft.label.isBlank() || draft.label == "接続") name else draft.label)
                        }
                    } else if (draft.kind == KeyKind.HOLD) {
                        Text("押している間だけ、トラックパッドを重ねて表示します。指を離すと元の画面に戻ります。", fontSize = 12.sp)
                    } else {
                        val entries = Catalog.entries(draft.kind)
                        Text("割り当て", fontSize = 12.sp)
                        GridPicker(entries.firstOrNull { it.code == draft.value }?.name ?: "0x%X".format(draft.value),
                            entries.map { it.name to it }) { e ->
                            val old = entries.firstOrNull { it.code == draft.value }?.name
                            draft = draft.copy(value = e.code, label = if (draft.label.isBlank() || draft.label == old) e.name else draft.label)
                        }
                    }
                    OutlinedTextField(weightText, { weightText = it }, label = { Text("幅の比率（0.3〜6）") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val w = weightText.toFloatOrNull()?.coerceIn(0.3f, 6f) ?: draft.weight
                onOk(draft.copy(label = draft.label.ifBlank { "?" }, weight = w))
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
