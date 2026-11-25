package ru.nikitarovno.setka

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

data class CellState(
    val dragging: Boolean = false,
    val mergeTarget: Boolean = false,
    val editing: Boolean = false,
)

/** Место элемента в сетке (в пикселях, относительно начала содержимого). */
data class Place(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun contains(p: Offset) = p.x >= x && p.x < x + w && p.y >= y && p.y < y + h
    val center get() = Offset(x + w / 2f, y + h / 2f)
    /** Центр иконки — она в верхней части ячейки. */
    val iconCenter get() = Offset(x + w / 2f, y + h * 0.4f)
}

enum class Kind { CELL, ROW_S, ROW_M, ROW_L }

fun Kind.isRow() = this != Kind.CELL

fun rowKind(size: Int) = when (size) {
    0 -> Kind.ROW_S
    2 -> Kind.ROW_L
    else -> Kind.ROW_M
}

const val PLACEHOLDER = "__placeholder__"

/** Пустая клетка, которой нет в сохранённой раскладке (показывается только при перетаскивании). */
const val VHOLE = "vhole-"

/** Пустая клетка: сохранённая (HOLE) или временная (VHOLE). */
fun isHoleId(id: String) = id.startsWith(HOLE) || id.startsWith(VHOLE)

/**
 * Пустые клетки для сетки.
 * Под каждым разделителем — блок. Пустой блок всегда показывает ряд пустых клеток («дорожку»).
 * full = true (идёт перетаскивание): добиваем неполные ряды пустыми клетками.
 * Новые ряды НЕ появляются сами: только у блока growBlock, по одному на каждое
 * наведение на низ последнего ряда (growRows штук) — рядов сколько угодно.
 */
fun withVirtualHoles(
    keys: List<String>, kinds: List<Kind>, columns: Int,
    full: Boolean = true, growBlock: Int = -1, growRows: Int = 0,
): Pair<List<String>, List<Kind>> {
    val k = ArrayList<String>(keys.size + columns * 3)
    val t = ArrayList<Kind>(keys.size + columns * 3)
    var col = 0
    var n = 0
    var cellsInBlock = 0
    var inBlock = false // уже были под разделителем
    var block = 0
    fun holes(count: Int) = repeat(count) {
        k += VHOLE + n++
        t += Kind.CELL
    }
    fun closeRow() {
        if (col != 0) {
            if (full) holes(columns - col)
            col = 0
        }
    }
    fun endBlock() {
        if (inBlock && cellsInBlock == 0) holes(columns) else closeRow()
        if (full && block == growBlock) holes(columns * growRows)
    }
    for (i in keys.indices) {
        if (kinds[i].isRow()) {
            endBlock()
            cellsInBlock = 0
            inBlock = true
            block++
        } else {
            cellsInBlock++
            col = (col + 1) % columns
        }
        k += keys[i]
        t += kinds[i]
    }
    endBlock()
    return k to t
}

/** Номер блока, в котором стоит элемент i (0 — над первым разделителем). */
fun blockIndexOf(places: List<Place>, cellW: Float, i: Int): Int =
    (0 until i).count { places[it].w > cellW + 1f }

/** Элемент i — в последнем ряду своего блока (ниже — разделитель или конец). */
fun inLastRowOfBlock(places: List<Place>, cellW: Float, i: Int): Boolean {
    val y = places[i].y
    for (j in i + 1 until places.size) {
        if (places[j].y > y + 1f) return places[j].w > cellW + 1f
    }
    return true
}

/** Раскладка: обычные элементы — по клеткам, разделитель-строка — с новой строки на всю ширину. */
fun layoutGrid(kinds: List<Kind>, columns: Int, cellW: Float, cellH: Float): Pair<List<Place>, Float> {
    var col = 0
    var y = 0f
    val out = ArrayList<Place>(kinds.size)
    for (k in kinds) {
        if (k.isRow()) {
            if (col != 0) {
                y += cellH
                col = 0
            }
            val h = cellH * when (k) {
                Kind.ROW_S -> 0.3f
                Kind.ROW_L -> 0.85f
                else -> 0.5f
            }
            out += Place(0f, y, cellW * columns, h)
            y += h
        } else {
            out += Place(col * cellW, y, cellW, cellH)
            col++
            if (col == columns) {
                col = 0
                y += cellH
            }
        }
    }
    val total = y + if (col != 0) cellH else 0f
    return out to total
}

/**
 * Снимок сетки для обработчика жестов: где что лежит на экране.
 * Обновляется при каждой отрисовке, жесты читают его в моменте.
 */
class GridSnap {
    var keys: List<String> = emptyList()
    var places: List<Place> = emptyList()
    var origin: Offset = Offset.Zero // левый верх области сетки в координатах корня
    var viewW = 0f
    var viewH = 0f
    var contentH = 0f
    var cellW = 0f
    var cellH = 0f
    var panel: Rect? = null // рамка панели папки (для «тапа мимо»)
    val scroll = mutableFloatStateOf(0f)

    fun local(p: Offset) = Offset(p.x - origin.x, p.y - origin.y + scroll.floatValue)
    fun inView(p: Offset) = p.x >= origin.x && p.x < origin.x + viewW && p.y >= origin.y && p.y < origin.y + viewH
    fun hit(p: Offset): Int {
        if (!inView(p)) return -1
        val l = local(p)
        return places.indexOfFirst { it.contains(l) }
    }

    fun maxScroll() = (contentH - viewH).coerceAtLeast(0f)
    fun scrollBy(d: Float) {
        scroll.floatValue = (scroll.floatValue + d).coerceIn(0f, maxScroll())
    }

    /** Экранный прямоугольник элемента (для анимации запуска). */
    fun screenRect(i: Int): android.graphics.Rect? {
        val pl = places.getOrNull(i) ?: return null
        val x = origin.x + pl.x
        val y = origin.y + pl.y - scroll.floatValue
        return android.graphics.Rect(x.roundToInt(), y.roundToInt(), (x + pl.w).roundToInt(), (y + pl.h).roundToInt())
    }
}

/** Состояние перетаскивания пачки. */
class DragState {
    var stack by mutableStateOf<List<String>>(emptyList()) // id (на главном) или ключи (из папки)
    var fromFolder by mutableStateOf<String?>(null)
    var inFolder by mutableStateOf<String?>(null) // где сейчас «висит» пачка: null — главный экран
    var pos by mutableStateOf(Offset.Zero)
    var grab by mutableStateOf(Offset.Zero)
    var target by mutableIntStateOf(-1)
    var merge by mutableStateOf<String?>(null)
    var growBlock by mutableIntStateOf(-1) // блок, которому добавили пустые ряды
    var growRows by mutableIntStateOf(0)
    val active get() = stack.isNotEmpty()

    fun reset() {
        stack = emptyList()
        fromFolder = null
        inFolder = null
        merge = null
        target = -1
        growBlock = -1
        growRows = 0
    }
}

/** Отрисовка сетки с плавными перемещениями элементов. */
@Composable
fun GridLayer(
    snap: GridSnap,
    keys: List<String>,
    kinds: List<Kind>,
    columns: Int,
    cellWpx: Float,
    cellHpx: Float,
    modifier: Modifier,
    nudge: ((Int) -> Float)? = null,
    content: @Composable (index: Int) -> Unit,
) {
    val density = LocalDensity.current
    val (places, total) = remember(kinds, columns, cellWpx, cellHpx) {
        layoutGrid(kinds, columns, cellWpx, cellHpx)
    }
    snap.keys = keys
    snap.places = places
    snap.contentH = total
    snap.cellW = cellWpx
    snap.cellH = cellHpx

    Box(
        modifier
            .clipToBounds()
            .onGloballyPositioned {
                snap.origin = it.positionInRoot()
                snap.viewW = it.size.width.toFloat()
                snap.viewH = it.size.height.toFloat()
                if (snap.scroll.floatValue > snap.maxScroll()) snap.scroll.floatValue = snap.maxScroll()
            }
    ) {
        for (i in keys.indices) {
            val pl = places.getOrNull(i) ?: continue
            key(keys[i]) {
                val tgt = IntOffset(pl.x.roundToInt(), pl.y.roundToInt())
                val anim = remember { Animatable(tgt, IntOffset.VectorConverter) }
                LaunchedEffect(tgt) {
                    anim.animateTo(tgt, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
                }
                val wDp = with(density) { pl.w.toDp() }
                val hDp = with(density) { pl.h.toDp() }
                Box(
                    Modifier
                        .offset {
                            val dx = nudge?.invoke(i)?.roundToInt() ?: 0
                            IntOffset(anim.value.x + dx, anim.value.y - snap.scroll.floatValue.roundToInt())
                        }
                        .size(wDp, hDp)
                ) {
                    content(i)
                }
            }
        }
    }
}

