package ru.nikitarovno.setka

import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun HomeScreen(vm: LauncherViewModel) {
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val s = vm.settings
    val drag = remember { DragState() }
    val homeSnap = remember { GridSnap() }
    val folderSnap = remember { GridSnap() }
    val scope = rememberCoroutineScope()
    var flingJob by remember { mutableStateOf<Job?>(null) }

    val folder = vm.items.firstOrNull { it is FolderItem && it.id == vm.openFolderId } as? FolderItem
    val overlay = folder != null || vm.searchOpen || vm.settingsOpen
    val blur by animateDpAsState(if (overlay) 26.dp else 0.dp, tween(260), label = "blur")
    val dim by animateFloatAsState(if (overlay) 1f else 0f, tween(260), label = "dim")

    // «Назад»: закрывает открытое. Если «Сетка» — рабочий стол, назад никуда не выходит;
    // если запущена как обычное приложение — выходит.
    val ctx = LocalContext.current
    val isHome = remember(vm.items) {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == ctx.packageName
    }
    BackHandler(enabled = isHome || drag.active || overlay || vm.modalOpen) {
        if (drag.active) drag.reset() else vm.closeOverlays()
    }

    Box(Modifier.fillMaxSize()) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bars = WindowInsets.systemBars.asPaddingValues()
        val gridW = maxWidth - 8.dp
        val gridH = maxHeight - bars.calculateTopPadding() - bars.calculateBottomPadding() - 12.dp
        val cellW = gridW / s.columns
        val cellH = gridH / s.rows
        val cellWpx = with(density) { cellW.toPx() }
        val cellHpx = with(density) { cellH.toPx() }
        val screenH = maxHeight

        // ---------- Что показываем на главном ----------
        // Во время перетаскивания раскладка НЕ меняется: взятые иконки остаются «призраками» на месте.
        val homeDragging = drag.active && drag.inFolder == null
        val homeList = if (homeDragging) vm.homeWithout(drag.stack, drag.fromFolder) else vm.items
        val homeMap = homeList.associateBy { it.id }
        // Тащим разделитель — вместе с ним «едет» весь его блок
        val ghosts = if (drag.active && drag.fromFolder == null) {
            val one = drag.stack.singleOrNull()
            if (one != null && vm.isRowSpacer(one)) vm.blockIds(one).toSet() else drag.stack.toSet()
        } else emptySet()
        val baseKeys = homeList.map { it.id }
        val baseKinds = homeList.map { if (it is SpacerItem && it.row) rowKind(it.size) else Kind.CELL }
        // Пока тащим по главному — показываем все пустые клетки, чтобы бросить можно было куда угодно
        val (homeKeys, homeKinds) =
            withVirtualHoles(baseKeys, baseKinds, s.columns, full = homeDragging, growBlock = drag.growBlock, growRows = drag.growRows)

        // ---------- Жесты на весь экран ----------
        val gestures = Modifier.pointerInput(Unit) {
            val slop = viewConfiguration.touchSlop
            val swipeDist = 56.dp.toPx()
            val edge = 28.dp.toPx()

            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                flingJob?.cancel()
                if (vm.modalOpen) return@awaitEachGesture
                val folderId = vm.openFolderId
                val snap = if (folderId != null) folderSnap else homeSnap
                val outsidePanel = folderId != null && folderSnap.panel?.contains(down.position) != true
                val startIdx = if (outsidePanel) -1 else snap.hit(down.position)

                var tap = false
                var vertical = false
                var swipeSearch = false
                val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (c.changedToUp()) {
                            tap = !c.isConsumed
                            break
                        }
                        if (c.isConsumed) break
                        val d = c.position - down.position
                        if (d.getDistance() > slop) {
                            if (abs(d.y) > abs(d.x)) {
                                vertical = true
                                if (folderId == null && d.y > 0f && homeSnap.scroll.floatValue <= 0f) swipeSearch = true
                            }
                            break
                        }
                    }
                    true
                }

                // ---------- Короткие жесты ----------
                if (early != null) {
                    when {
                        tap -> {
                            if (outsidePanel) {
                                vm.openFolderId = null
                            } else if (startIdx >= 0) {
                                val k = snap.keys[startIdx]
                                if (folderId != null) {
                                    vm.launch(k, view, snap.screenRect(startIdx))
                                } else when (val it = vm.items.firstOrNull { x -> x.id == k }) {
                                    is AppItem -> vm.launch(it.key, view, snap.screenRect(startIdx))
                                    is FolderItem -> vm.openFolderId = it.id
                                    is SpacerItem -> {
                                        // Тап по середине разделителя — задать/сменить название
                                        val pl = snap.places[startIdx]
                                        val lx = down.position.x - snap.origin.x
                                        if (it.row && abs(lx - (pl.x + pl.w / 2f)) < pl.w * 0.25f) vm.renameSpacerId = it.id
                                    }
                                    else -> {}
                                }
                            }
                        }
                        swipeSearch -> {
                            var fired = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                c.consume()
                                if (c.changedToUp()) break
                                if (!fired && c.position.y - down.position.y > swipeDist) {
                                    fired = true
                                    vm.searchOpen = true
                                }
                            }
                        }
                        vertical && !outsidePanel -> {
                            val vt = VelocityTracker()
                            vt.addPosition(down.uptimeMillis, down.position)
                            var lastY = down.position.y
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                vt.addPosition(c.uptimeMillis, c.position)
                                if (c.changedToUp()) break
                                snap.scrollBy(lastY - c.position.y)
                                lastY = c.position.y
                                c.consume()
                            }
                            val vy = vt.calculateVelocity().y
                            if (abs(vy) > 300f) {
                                flingJob = scope.launch {
                                    var prev = 0f
                                    animateDecay(0f, -vy, FloatExponentialDecaySpec(frictionMultiplier = 1.4f)) { value, _ ->
                                        snap.scrollBy(value - prev)
                                        prev = value
                                    }
                                }
                            }
                        }
                    }
                    return@awaitEachGesture
                }

                // ---------- Долгий тап по пустому месту — настройки ----------
                if (outsidePanel || startIdx < 0 || snap.keys[startIdx] == PLACEHOLDER || isHoleId(snap.keys[startIdx])) {
                    if (!outsidePanel && folderId == null) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.menu = MenuTarget.Empty
                    }
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        c.consume()
                        if (c.changedToUp()) break
                    }
                    return@awaitEachGesture
                }

                // ---------- Взяли элемент ----------
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                val pl0 = snap.places[startIdx]
                drag.fromFolder = folderId
                drag.inFolder = folderId
                drag.stack = listOf(snap.keys[startIdx])
                drag.merge = null
                drag.target = if (folderId != null) startIdx else -1
                drag.grab = down.position - Offset(snap.origin.x + pl0.x, snap.origin.y + pl0.y - snap.scroll.floatValue)
                drag.pos = down.position

                var moved = false
                var edgeSince = 0L
                var pendingKey = -1
                var pendingSince = 0L
                var mergeCand: String? = null
                var mergeSince = 0L
                var growKey = -1
                var growSince = 0L
                val others = HashMap<PointerId, Offset>() // другие пальцы: где коснулись
                val scrolling = HashSet<PointerId>() // … и какие из них уже листают

                fun secondTap(p: Offset) {
                    val inF = drag.inFolder
                    if (inF != null && folderSnap.panel?.contains(p) != true) {
                        // Тап мимо папки — выходим на главный вместе с пачкой
                        vm.openFolderId = null
                        drag.inFolder = null
                        drag.merge = null
                        drag.target = -1
                        pendingKey = -1
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        return
                    }
                    // Пачку из папки нельзя смешивать с элементами главного экрана
                    if (inF == null && drag.fromFolder != null) return
                    val sn = if (inF != null) folderSnap else homeSnap
                    val i = sn.hit(p)
                    if (i < 0) return
                    val k = sn.keys[i]
                    if (k == PLACEHOLDER || isHoleId(k) || k in drag.stack) return
                    drag.stack = drag.stack + k
                    if (inF != null) {
                        val r = if (i < drag.target) i else i - 1
                        if (r < drag.target) drag.target = drag.target - 1
                    }
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }

                fun setTarget(key: Int, nt: Int, now: Long) {
                    if (key != pendingKey) {
                        pendingKey = key
                        pendingSince = now
                    } else if (now - pendingSince > 110 && nt != drag.target) {
                        drag.target = nt
                    }
                }

                fun evaluate() {
                    val inF = drag.inFolder
                    val sn = if (inF != null) folderSnap else homeSnap
                    val now = SystemClock.uptimeMillis()

                    // Автопрокрутка: только если САМ ПАЛЕЦ упёрся в край и держится там ~0.5 с
                    val fy = drag.pos.y
                    val dir = when {
                        fy > sn.origin.y + sn.viewH - edge -> 1
                        fy < sn.origin.y + edge -> -1
                        else -> 0
                    }
                    if (dir == 0) edgeSince = 0L
                    else {
                        if (edgeSince == 0L) edgeSince = now
                        if (now - edgeSince > 300) sn.scrollBy(dir * 16f)
                    }

                    // Целимся центром перетаскиваемой иконки, а не пальцем
                    val p = drag.pos - drag.grab + Offset(homeSnap.cellW / 2f, homeSnap.cellH * 0.4f)
                    if (inF != null && folderSnap.panel?.contains(p) != true) {
                        drag.merge = null
                        return
                    }
                    val lp = sn.local(p)
                    val i = sn.hit(p)

                    // ----- Папка: старая логика с placeholder -----
                    if (inF != null) {
                        drag.merge = null
                        if (i < 0) {
                            if (sn.inView(p) && lp.y >= sn.contentH - 1f) setTarget(-2, sn.keys.count { it != PLACEHOLDER }, now)
                            return
                        }
                        val k = sn.keys[i]
                        if (k == PLACEHOLDER) return
                        val pl = sn.places[i]
                        val fx = (lp.x - pl.x) / pl.w
                        val t0 = drag.target
                        val b = if (t0 >= 0 && i > t0) i - 1 else i
                        setTarget(i * 2 + if (fx < 0.5f) 0 else 1, if (fx < 0.5f) b else b + 1, now)
                        return
                    }

                    // ----- Главный экран -----
                    if (i < 0) {
                        drag.merge = null
                        if (!sn.inView(p)) return
                        // Пустое место справа в ряду — в конец этого ряда; ниже всех — в самый конец
                        var last = -1
                        for ((j, pl) in sn.places.withIndex()) {
                            if (pl.w <= sn.cellW + 1f && lp.y >= pl.y && lp.y < pl.y + pl.h) last = j
                        }
                        when {
                            last >= 0 -> setTarget(1_000_000 + last, last + 1, now)
                            lp.y >= sn.contentH - 1f -> setTarget(-2, sn.keys.size, now)
                        }
                        return
                    }
                    val k = sn.keys[i]
                    val pl = sn.places[i]
                    // Навёл на низ последнего ряда блока — под ним появляется новый пустой ряд
                    if (pl.w <= sn.cellW + 1f && lp.y > pl.y + pl.h * 0.72f && inLastRowOfBlock(sn.places, sn.cellW, i)) {
                        val b = blockIndexOf(sn.places, sn.cellW, i)
                        val gk = b * 10_000 + pl.y.toInt()
                        if (growKey != gk) {
                            growKey = gk
                            growSince = now
                        } else if (now - growSince > 450) {
                            drag.growRows = if (drag.growBlock == b) drag.growRows + 1 else 1
                            drag.growBlock = b
                            growKey = -1
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    } else {
                        growKey = -1
                        // Ушёл в другой блок — добавленные ряды убираем, остальное не двигается
                        if (drag.growBlock >= 0 && pl.w <= sn.cellW + 1f &&
                            blockIndexOf(sn.places, sn.cellW, i) != drag.growBlock) {
                            drag.growBlock = -1
                            drag.growRows = 0
                        }
                    }
                    if (isHoleId(k)) {
                        // Пустая клетка — встаём ровно в неё
                        drag.merge = null
                        setTarget(i * 2, i, now)
                        return
                    }
                    val isCell = pl.w <= sn.cellW + 1f
                    if (!isCell) {
                        // Разделитель: верхняя половина — перед ним, нижняя — после
                        drag.merge = null
                        val top = lp.y < pl.y + pl.h / 2f
                        setTarget(i * 2 + if (top) 0 else 1, if (top) i else i + 1, now)
                        return
                    }
                    val fx = (lp.x - pl.x) / pl.w
                    val dist = (lp - pl.iconCenter).getDistance()
                    val radius = if (drag.merge == k) sn.cellW * 0.45f else sn.cellW * 0.22f
                    // Папка — только если подержать иконку точно над другой ~0.45 с.
                    // Просто провёл мимо — это перестановка, а не папка.
                    if (k !in drag.stack && dist < radius && vm.canMergeInto(drag.stack, drag.fromFolder, k)) {
                        if (drag.merge == k) return
                        if (mergeCand != k) {
                            mergeCand = k
                            mergeSince = now
                        } else if (now - mergeSince > 450) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            drag.merge = k
                            drag.target = -1
                            pendingKey = -1
                            return
                        }
                    } else {
                        mergeCand = null
                    }
                    drag.merge = null
                    // Левая половина — перед иконкой, правая — после
                    setTarget(i * 2 + if (fx < 0.5f) 0 else 1, if (fx < 0.5f) i else i + 1, now)
                }

                while (true) {
                    val ev = withTimeoutOrNull(50L) { awaitPointerEvent() }
                    var ended = false
                    if (ev != null) {
                        for (c in ev.changes) {
                            if (c.id == down.id) {
                                if (c.changedToUp()) ended = true
                                else {
                                    drag.pos = c.position
                                    if ((c.position - down.position).getDistance() > slop) moved = true
                                }
                                c.consume()
                            } else if (c.changedToDown()) {
                                // Второй палец: свайп — листаем, просто тап — добавить в пачку
                                c.consume()
                                moved = true
                                others[c.id] = c.position
                            } else if (c.changedToUp()) {
                                val start = others.remove(c.id)
                                if (start != null && scrolling.remove(c.id).not()) secondTap(start)
                                c.consume()
                            } else {
                                val start = others[c.id]
                                if (start != null) {
                                    if (c.id !in scrolling && (c.position - start).getDistance() > slop) scrolling += c.id
                                    if (c.id in scrolling) {
                                        val sn = if (drag.inFolder != null) folderSnap else homeSnap
                                        sn.scrollBy(c.previousPosition.y - c.position.y)
                                    }
                                }
                                c.consume()
                            }
                        }
                    }
                    if (ended) break
                    if (moved) evaluate()
                }

                // ---------- Отпустили ----------
                val stack = drag.stack
                val from = drag.fromFolder
                val inF = drag.inFolder
                val target = drag.target
                val merge = drag.merge
                when {
                    !moved -> {
                        val k = stack.first()
                        vm.menu = if (from != null) MenuTarget.FolderApp(from, k) else when (val it = vm.items.firstOrNull { x -> x.id == k }) {
                            is AppItem -> MenuTarget.App(it.key)
                            is FolderItem -> MenuTarget.Folder(it.id)
                            is SpacerItem -> MenuTarget.Spacer(it.id)
                            null -> null
                        }
                    }
                    inF != null -> vm.dropInFolder(inF, stack, target)
                    else -> vm.dropOnHome(stack, from, if (merge != null) -1 else target, merge, homeSnap.keys)
                }
                drag.reset()
            }
        }

        Box(Modifier.fillMaxSize().then(gestures)) {
            // ---------- Главный экран ----------
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (blur > 0.dp) Modifier.blur(blur) else Modifier)
                    .systemBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 6.dp)
            ) {
                val nudgePx = cellWpx * 0.16f
                GridLayer(
                    homeSnap, homeKeys, homeKinds, s.columns, cellWpx, cellHpx, Modifier.fillMaxSize(),
                    nudge = { i ->
                        val t = drag.target
                        if (!drag.active || drag.inFolder != null || t < 0) 0f
                        else if (homeKeys.getOrNull(t)?.let(::isHoleId) == true) 0f // в пустую клетку — соседей не двигаем
                        else {
                            val pl = homeSnap.places
                            val rowAfter = pl.getOrNull(t)?.takeIf { it.w <= homeSnap.cellW + 1f }?.y
                            val rowBefore = pl.getOrNull(t - 1)?.takeIf { it.w <= homeSnap.cellW + 1f }?.y
                            val y = pl.getOrNull(i)?.y
                            when {
                                i >= t && y != null && y == rowAfter -> nudgePx
                                i < t && y != null && y == rowBefore -> -nudgePx
                                else -> 0f
                            }
                        }
                    },
                ) { i ->
                    val k = homeKeys[i]
                    if (isHoleId(k)) {
                        if (homeDragging) PlaceholderCell(active = drag.target == i)
                        return@GridLayer
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = if (k in ghosts) 0.25f else 1f }
                    ) {
                        when (val it = homeMap[k]) {
                            is AppItem -> AppCell(
                                vm.apps[it.key], vm.labelOf(it.key), s,
                                CellState(mergeTarget = drag.merge == k), vm.isNew(it.key)
                            )
                            is FolderItem -> FolderCell(it, vm.apps, s, CellState(mergeTarget = drag.merge == k))
                            is SpacerItem -> SpacerCell(it, s, CellState(editing = drag.active))
                            null -> {}
                        }
                    }
                }
            }

            if (dim > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.38f * dim))
                )
            }

            // ---------- Папка-панель ----------
            FolderPanel(vm, folder, drag, folderSnap, s, cellW, cellH, cellWpx, cellHpx, screenH)

            // ---------- Редактор рядов ----------
            EditorOverlay(vm, s, cellH)

            // ---------- Пачка на пальце ----------
            if (drag.active) {
                Box(
                    Modifier
                        .offset { IntOffset((drag.pos.x - drag.grab.x).roundToInt(), (drag.pos.y - drag.grab.y).roundToInt()) }
                        .size(cellW, cellH)
                        .graphicsLayer {
                            scaleX = 1.1f
                            scaleY = 1.1f
                        }
                ) {
                    val shown = drag.stack.take(3)
                    for (j in shown.indices.reversed()) {
                        Box(
                            Modifier
                                .offset(x = (j * 5).dp, y = (j * 5).dp)
                                .graphicsLayer { alpha = if (j == 0) 1f else 0.75f }
                        ) {
                            StackCell(vm, shown[j], drag.fromFolder, s)
                        }
                    }
                    if (drag.stack.size > 1) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 6.dp, y = (-4).dp)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF3B30)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                drag.stack.size.toString(),
                                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Inter,
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- Оверлеи поверх ----------
    if (vm.searchOpen || vm.settingsOpen) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { vm.closeOverlays() } }
        )
    }
    SearchOverlay(vm)
    SettingsSheet(vm)
    MenuSheet(vm)
    FolderPicker(vm)
    RenameDialogs(vm)
    }
}

@Composable
private fun PlaceholderCell(active: Boolean = true) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(7.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = if (active) 0.22f else 0.06f))
            .border(1.dp, Color.White.copy(alpha = if (active) 0.6f else 0.22f), RoundedCornerShape(16.dp))
    )
}

@Composable
private fun StackCell(vm: LauncherViewModel, id: String, fromFolder: String?, s: LauncherSettings) {
    if (fromFolder != null) {
        AppCell(vm.apps[id], vm.labelOf(id), s, CellState(dragging = true))
        return
    }
    when (val it = vm.items.firstOrNull { x -> x.id == id }) {
        is AppItem -> AppCell(vm.apps[it.key], vm.labelOf(it.key), s, CellState(dragging = true))
        is FolderItem -> FolderCell(it, vm.apps, s, CellState(dragging = true))
        is SpacerItem -> SpacerCell(it, s, CellState(editing = true))
        null -> {}
    }
}

@Composable
private fun FolderPanel(
    vm: LauncherViewModel,
    folder: FolderItem?,
    drag: DragState,
    snap: GridSnap,
    s: LauncherSettings,
    cellW: androidx.compose.ui.unit.Dp,
    cellH: androidx.compose.ui.unit.Dp,
    cellWpx: Float,
    cellHpx: Float,
    screenH: androidx.compose.ui.unit.Dp,
) {
    var last by remember { mutableStateOf<FolderItem?>(null) }
    if (folder != null) last = folder

    AnimatedVisibility(
        visible = folder != null,
        enter = fadeIn(tween(150)) + scaleIn(spring(dampingRatio = 0.82f, stiffness = 420f), initialScale = 0.88f),
        exit = fadeOut(tween(130)) + scaleOut(tween(130), targetScale = 0.92f),
    ) {
        val f = folder ?: last ?: return@AnimatedVisibility
        val cols = max(3, s.columns - 2)
        val dragHere = drag.active && drag.inFolder == f.id
        val apps = if (dragHere) f.apps.filter { it !in drag.stack } else f.apps
        val keys = ArrayList(apps)
        if (dragHere) keys.add(drag.target.coerceIn(0, apps.size), PLACEHOLDER)
        val kinds = List(keys.size) { Kind.CELL }

        val headerH = 64.dp
        val maxPanelH = screenH * 0.7f
        val rowsNeeded = max(1, ceil(keys.size / cols.toFloat()).toInt())
        val gridH = min((cellH * rowsNeeded).value, (maxPanelH - headerH - 20.dp).value).dp

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(cellW * cols + 24.dp)
                    .onGloballyPositioned { snap.panel = it.boundsInRoot() }
                    .clip(RoundedCornerShape(30.dp))
                    .background(Color.White.copy(alpha = 0.14f))
                    .border(0.8.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(30.dp))
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 14.dp)
            ) {
                Box(
                    Modifier
                        .height(headerH)
                        .padding(start = 8.dp, top = 14.dp)
                ) {
                    Text(
                        f.name,
                        color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = Inter,
                        maxLines = 1,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { vm.renameFolderId = f.id }
                    )
                }
                GridLayer(
                    snap, keys, kinds, cols, cellWpx, cellHpx,
                    Modifier
                        .width(cellW * cols)
                        .height(gridH)
                ) { i ->
                    val k = keys[i]
                    if (k == PLACEHOLDER) PlaceholderCell()
                    else AppCell(vm.apps[k], vm.labelOf(k), s, CellState(), vm.isNew(k))
                }
            }
        }
    }
}
