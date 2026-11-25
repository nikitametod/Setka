package ru.nikitarovno.setka

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Ряд в редакторе: ячейки подряд (до columns штук) или один разделитель. */
private data class ERow(val ids: List<String>, val spacer: SpacerItem?)

private fun buildRows(items: List<HomeItem>, cols: Int): List<ERow> {
    val out = ArrayList<ERow>()
    var cur = ArrayList<String>()
    for (it in items) {
        if (it is SpacerItem && it.row) {
            if (cur.isNotEmpty()) { out += ERow(cur, null); cur = ArrayList() }
            out += ERow(listOf(it.id), it)
        } else {
            cur += it.id
            if (cur.size == cols) { out += ERow(cur, null); cur = ArrayList() }
        }
    }
    if (cur.isNotEmpty()) out += ERow(cur, null)
    return out
}

private enum class SelMode { NONE, ITEMS, ROWS }

private val Accent = Color(0xFFFF7A3C)

/**
 * Редактор раскладки. Открывается долгим тапом по пустому месту → «Редактор».
 * Тап по иконке — выделить её; тап по кружку слева — выделить ряд.
 * Начал с рядов — дальше выделяются только ряды, начал с иконок — только иконки.
 * Удерживай выделенное и тяни — оно переедет туда, где отпустишь.
 */
@Composable
fun EditorOverlay(vm: LauncherViewModel, s: LauncherSettings, cellH: Dp) {
    if (!vm.editorOpen) return
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val rows = buildRows(vm.items, s.columns)
    val byId = vm.items.associateBy { it.id }

    var mode by remember { mutableStateOf(SelMode.NONE) }
    var selItems by remember { mutableStateOf(setOf<String>()) }
    var selRows by remember { mutableStateOf(setOf<String>()) } // первый id ряда
    val rowTop = remember { mutableStateMapOf<Int, Float>() }
    val rowH = remember { mutableStateMapOf<Int, Float>() }
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var viewH by remember { mutableFloatStateOf(0f) }
    var targetRow by remember { mutableIntStateOf(-1) }

    fun clear() {
        mode = SelMode.NONE; selItems = emptySet(); selRows = emptySet()
    }
    fun toggleRow(r: ERow) {
        if (mode == SelMode.ITEMS) return
        val k = r.ids.first()
        selRows = if (k in selRows) selRows - k else selRows + k
        mode = if (selRows.isEmpty()) SelMode.NONE else SelMode.ROWS
    }
    fun toggleItem(r: ERow, id: String) {
        if (mode == SelMode.ROWS) { toggleRow(r); return }
        if (isHoleId(id)) return
        selItems = if (id in selItems) selItems - id else selItems + id
        mode = if (selItems.isEmpty()) SelMode.NONE else SelMode.ITEMS
    }
    fun rowAt(y: Float): Int {
        for (i in rows.indices) {
            val t = rowTop[i] ?: continue
            val h = rowH[i] ?: continue
            if (y >= t && y < t + h) return i
        }
        return if (rows.isNotEmpty() && y >= (rowTop[rows.lastIndex] ?: 0f)) rows.lastIndex else -1
    }
    val count = if (mode == SelMode.ROWS) selRows.size else selItems.size

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .pointerInput(Unit) { /* перехватываем касания, чтобы главный экран не реагировал */ }
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---------- Шапка ----------
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Редактор", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = Inter)
                    Text(
                        when (mode) {
                            SelMode.NONE -> "Тап — иконка · кружок слева — ряд · удерживай и тяни"
                            SelMode.ITEMS -> "Иконок: $count · удерживай и тяни"
                            SelMode.ROWS -> "Рядов: $count · удерживай и тяни"
                        },
                        color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = Inter,
                    )
                }
                if (mode != SelMode.NONE) {
                    Text(
                        "Сбросить", color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, fontFamily = Inter,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { clear() }.padding(10.dp)
                    )
                }
                Text(
                    "Готово", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = Inter,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { vm.editorOpen = false }.padding(10.dp)
                )
            }

            // ---------- Сетка ----------
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewH = it.height.toFloat() }
            ) {
                val gutter = 30.dp
                val cellW = (maxWidth - gutter - 8.dp) / s.columns
                val cellWpx = with(density) { cellW.toPx() }
                val gutterPx = with(density) { gutter.toPx() }

                Box(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .pointerInput(rows, mode, selItems, selRows) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { p ->
                                    val r = rowAt(p.y)
                                    if (r >= 0 && mode == SelMode.NONE) {
                                        if (p.x < gutterPx) toggleRow(rows[r])
                                        else {
                                            val c = ((p.x - gutterPx) / cellWpx).toInt()
                                            rows[r].ids.getOrNull(c)?.let { toggleItem(rows[r], it) }
                                                ?: toggleRow(rows[r])
                                        }
                                    }
                                    dragging = mode != SelMode.NONE
                                    dragPos = p
                                    targetRow = r
                                },
                                onDrag = { ch, d ->
                                    ch.consume()
                                    dragPos += d
                                    targetRow = rowAt(dragPos.y)
                                    // Автопрокрутка у краёв
                                    val vy = dragPos.y - scroll.value
                                    val edge = 80f
                                    if (vy < edge) scope.launch { scroll.scrollBy(-24f) }
                                    else if (vy > viewH - edge) scope.launch { scroll.scrollBy(24f) }
                                },
                                onDragEnd = {
                                    if (dragging) {
                                        val r = rowAt(dragPos.y)
                                        if (mode == SelMode.ROWS) {
                                            val chosen = rows.filter { it.ids.first() in selRows }.map { it.ids }
                                            // Верхняя половина ряда — перед ним, нижняя — после
                                            val t = rowTop[r] ?: 0f; val h = rowH[r] ?: 0f
                                            val idx = if (r < 0) rows.size else if (dragPos.y > t + h / 2f) r + 1 else r
                                            vm.moveRows(chosen, rows.getOrNull(idx)?.ids?.first())
                                        } else if (mode == SelMode.ITEMS && r >= 0) {
                                            val c = ((dragPos.x - gutterPx) / cellWpx).toInt().coerceAtLeast(0)
                                            val row = rows[r]
                                            val before = when {
                                                row.spacer != null -> rows.getOrNull(r + 1)?.ids?.first()
                                                c < row.ids.size -> row.ids[c]
                                                else -> rows.getOrNull(r + 1)?.ids?.first()
                                            }
                                            val ordered = vm.items.map { it.id }.filter { it in selItems }
                                            vm.moveItems(ordered, before)
                                        }
                                        clear()
                                    }
                                    dragging = false
                                    targetRow = -1
                                },
                                onDragCancel = { dragging = false; targetRow = -1 },
                            )
                        }
                ) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 120.dp)) {
                        for ((i, r) in rows.withIndex()) {
                            val rowSelected = mode == SelMode.ROWS && r.ids.first() in selRows
                            val h = if (r.spacer != null) cellH * 0.6f else cellH
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(h)
                                    .onGloballyPositioned {
                                        rowTop[i] = it.positionInParent().y
                                        rowH[i] = it.size.height.toFloat()
                                    }
                                    .then(
                                        if (rowSelected) Modifier.background(Accent.copy(alpha = 0.18f))
                                        else Modifier
                                    )
                                    .then(
                                        if (dragging && targetRow == i) Modifier.border(1.dp, Accent.copy(alpha = 0.7f))
                                        else Modifier
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Кружок выбора ряда
                                Box(
                                    Modifier.width(gutter).fillMaxHeight(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(if (rowSelected) Accent else Color.Transparent)
                                            .border(1.5.dp, if (rowSelected) Accent else Color.White.copy(alpha = if (mode == SelMode.ITEMS) 0.15f else 0.5f), CircleShape)
                                            .clickable { toggleRow(r) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (rowSelected) Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                if (r.spacer != null) {
                                    Box(Modifier.weight(1f).height(h).clickable { toggleRow(r) }) {
                                        SpacerCell(r.spacer, s, CellState())
                                    }
                                } else {
                                    for (id in r.ids) {
                                        val itemSel = mode == SelMode.ITEMS && id in selItems
                                        Box(
                                            Modifier
                                                .width(cellW)
                                                .height(h)
                                                .padding(2.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .then(
                                                    if (itemSel) Modifier
                                                        .background(Accent.copy(alpha = 0.25f))
                                                        .border(2.dp, Accent, RoundedCornerShape(14.dp))
                                                    else if (isHoleId(id)) Modifier
                                                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                                                    else Modifier
                                                )
                                                .clickable { toggleItem(r, id) }
                                        ) {
                                            when (val it = byId[id]) {
                                                is AppItem -> AppCell(vm.apps[it.key], vm.labelOf(it.key), s, CellState())
                                                is FolderItem -> FolderCell(it, vm.apps, s, CellState())
                                                else -> {}
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Что тянем — подпись у пальца
                    if (dragging) {
                        Box(
                            Modifier
                                .offset { IntOffset((dragPos.x + 24f).roundToInt(), (dragPos.y - 60f).roundToInt()) }
                                .clip(RoundedCornerShape(12.dp))
                                .background(Accent)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                if (mode == SelMode.ROWS) "Рядов: $count" else "Иконок: $count",
                                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Inter,
                            )
                        }
                    }
                }
            }
        }
    }
}

