package ru.nikitarovno.setka

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val Glass = Color(0xFF1C1C1E).copy(alpha = 0.94f)
private val Dim = Color.White.copy(alpha = 0.55f)

@Composable
private fun T(
    text: String,
    size: Float = 16f,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Color.White,
    modifier: Modifier = Modifier,
) = Text(text, modifier = modifier, color = color, fontSize = size.sp, fontWeight = weight, fontFamily = Inter)

// =====================================================================
// Поиск (свайп вниз)
// =====================================================================

private const val EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`"
private const val RU = "йцукенгшщзхъфывапролджэячсмитьбюё"

private fun swapLayout(q: String): String = buildString {
    for (ch in q) {
        val i = EN.indexOf(ch)
        val j = RU.indexOf(ch)
        append(
            when {
                i >= 0 -> RU[i]
                j >= 0 -> EN[j]
                else -> ch
            }
        )
    }
}

private val TRANSLIT = mapOf(
    'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
    'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
    'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
    'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "",
    'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
)

private fun translit(s: String) = buildString { for (c in s) append(TRANSLIT[c] ?: c.toString()) }

/** 0 — начинается с запроса, 1 — содержит, -1 — не подходит. */
private fun score(label: String, q: String): Int {
    val l = label.lowercase()
    val variants = listOf(q, swapLayout(q), translit(q))
    val targets = listOf(l, translit(l))
    var best = -1
    for (t in targets) for (v in variants) {
        if (v.isEmpty()) continue
        if (t.startsWith(v)) return 0
        if (t.contains(v)) best = 1
    }
    return best
}

@Composable
fun SearchOverlay(vm: LauncherViewModel) {
    val s = vm.settings
    AnimatedVisibility(
        visible = vm.searchOpen,
        enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { -it / 8 },
        exit = fadeOut(tween(120)),
    ) {
        var q by remember { mutableStateOf("") }
        val fr = remember { FocusRequester() }
        val kb = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            delay(60)
            fr.requestFocus()
            kb?.show()
        }
        DisposableEffect(Unit) { onDispose { kb?.hide() } }

        val query = q.trim().lowercase()
        val results = remember(query, vm.apps, vm.labels) {
            if (query.isEmpty()) emptyList()
            else vm.apps.values
                .map { it to score(vm.labelOf(it.key), query) }
                .filter { it.second >= 0 }
                .sortedWith(compareBy({ it.second }, { vm.labelOf(it.first.key).lowercase() }))
                .map { it.first }
        }

        fun open(a: AppInfo) {
            vm.launch(a.key)
            vm.searchOpen = false
        }

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp)
                .pointerInput(Unit) { detectTapGestures { vm.searchOpen = false } }
        ) {
            Spacer(Modifier.height(12.dp))
            BasicTextField(
                value = q,
                onValueChange = { q = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 20.sp, fontFamily = Inter),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { results.firstOrNull()?.let { open(it) } }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fr),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White.copy(alpha = 0.14f))
                            .border(0.6.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 18.dp, vertical = 14.dp)
                    ) {
                        if (q.isEmpty()) T("Поиск", size = 20f, color = Dim)
                        inner()
                    }
                },
            )
            Spacer(Modifier.height(14.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(s.columns),
                modifier = Modifier.weight(1f),
            ) {
                items(results, key = { it.key }) { a ->
                    Box(
                        Modifier
                            .height(78.dp)
                            .clickable { open(a) }
                    ) {
                        val hiddenMark = if (a.key in vm.hidden) " ·скр." else ""
                        AppCell(a, vm.labelOf(a.key) + hiddenMark, s, CellState())
                    }
                }
            }
        }
    }
}

// =====================================================================
// Настройки (долгий тап по пустому месту)
// =====================================================================

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T(title, size = 15f)
            T(format(value), size = 15f, weight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(text, size = 14f, weight = FontWeight.Medium, color = if (selected) Color.Black else Color.White)
    }
}

@Composable
fun SettingsSheet(vm: LauncherViewModel) {
    val ctx = LocalContext.current
    AnimatedVisibility(
        visible = vm.settingsOpen,
        enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
        exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
    ) {
        val s = vm.settings
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            val maxSheetH = maxHeight * 0.85f
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                    .background(Glass)
                    .pointerInput(Unit) { detectTapGestures { } } // не пропускать тапы вниз
                    .heightIn(max = maxSheetH)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 22.dp, vertical = 18.dp)
            ) {
                T("Сетка", size = 24f, weight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Chip("+ Разделитель", false, Modifier.fillMaxWidth()) {
                    vm.addSeparator()
                    vm.settingsOpen = false
                }
                Spacer(Modifier.height(8.dp))
                Chip("Обновить приложения", false, Modifier.fillMaxWidth()) { vm.reload() }
                Spacer(Modifier.height(10.dp))
                SliderRow("Колонки", s.columns.toFloat(), 4f..8f, 3, { it.roundToInt().toString() }) {
                    vm.updateSettings(s.copy(columns = it.roundToInt()))
                }
                SliderRow("Ряды", s.rows.toFloat(), 6f..14f, 7, { it.roundToInt().toString() }) {
                    vm.updateSettings(s.copy(rows = it.roundToInt()))
                }
                SliderRow("Размер подписи", s.labelSize, 7f..14f, 13, { "%.1f".format(it) }) {
                    vm.updateSettings(s.copy(labelSize = (it * 2).roundToInt() / 2f))
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    T("Подписи под иконками", size = 15f)
                    Switch(
                        checked = s.showLabels,
                        onCheckedChange = { vm.updateSettings(s.copy(showLabels = it)) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = Color.White,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color.White.copy(alpha = 0.15f),
                            uncheckedBorderColor = Color.Transparent,
                        ),
                    )
                }
                if (s.showLabels) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("2 строки", s.labelMode == LabelMode.TWO_LINES, Modifier.weight(1f)) {
                            vm.updateSettings(s.copy(labelMode = LabelMode.TWO_LINES))
                        }
                        Chip("Бегущая строка", s.labelMode == LabelMode.MARQUEE, Modifier.weight(1f)) {
                            vm.updateSettings(s.copy(labelMode = LabelMode.MARQUEE))
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Обои", false, Modifier.weight(1f)) {
                        runCatching {
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Обои"))
                        }
                    }
                    Chip("Лаунчер по умолч.", false, Modifier.weight(1f)) {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                    }

                }
                if (vm.hidden.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    T("Скрытые (тап — вернуть)", size = 13f, color = Dim)
                    Spacer(Modifier.height(6.dp))
                    for (key in vm.hidden.toList()) {
                        T(
                            vm.labelOf(key).ifEmpty { key.substringBefore('/') },
                            size = 15f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.unhide(key) }
                                .padding(vertical = 8.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                T("Свайп вниз — поиск · удержание пустого места — эти настройки", size = 12f, color = Dim)
            }
        }
    }
}

// =====================================================================
// Контекстное меню (удержание без перетаскивания)
// =====================================================================

@Composable
fun MenuSheet(vm: LauncherViewModel) {
    val m = vm.menu ?: return
    val title: String
    val actions = mutableListOf<Pair<String, () -> Unit>>()
    when (m) {
        is MenuTarget.App -> {
            title = vm.labelOf(m.key)
            actions += "В папку…" to { vm.pickFolderFor = m.key }
            actions += "Переименовать" to { vm.renameAppKey = m.key }
            actions += "Скрыть" to { vm.hide(m.key) }
            actions += "О приложении" to { vm.appDetails(m.key) }
            actions += "Удалить" to { vm.uninstall(m.key) }
        }
        is MenuTarget.Folder -> {
            title = (vm.items.firstOrNull { it.id == m.id } as? FolderItem)?.name ?: "Папка"
            actions += "Переименовать" to { vm.renameFolderId = m.id }
            actions += "Расформировать" to { vm.disbandFolder(m.id) }
        }
        is MenuTarget.Spacer -> {
            val sp = vm.items.firstOrNull { it.id == m.id } as? SpacerItem
            title = "Разделитель"
            if (sp != null) {
                actions += "Название…" to { vm.renameSpacerId = m.id }
            }
            actions += "Удалить" to { vm.removeItem(m.id) }
        }
        is MenuTarget.FolderApp -> {
            title = vm.labelOf(m.key)
            actions += "Вынести на главный экран" to { vm.removeFromFolder(m.folderId, m.key) }
            actions += "Переименовать" to { vm.renameAppKey = m.key }
            actions += "Скрыть" to { vm.hide(m.key) }
            actions += "О приложении" to { vm.appDetails(m.key) }
            actions += "Удалить" to { vm.uninstall(m.key) }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .pointerInput(Unit) { detectTapGestures { vm.menu = null } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(10.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Glass)
                .padding(vertical = 8.dp)
        ) {
            T(
                title, size = 14f, color = Dim,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )
            val spRow = (m as? MenuTarget.Spacer)?.let { t -> vm.items.firstOrNull { it.id == t.id } as? SpacerItem }
            if (spRow != null && spRow.row) {
                T(
                    "Стиль: ${SPACER_STYLE_NAMES[spRow.style]}", size = 15f, weight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (st in 0 until SPACER_STYLES) {
                        Chip(SPACER_STYLE_NAMES[st], spRow.style == st, Modifier.weight(1f)) {
                            vm.setSpacerStyle(spRow.id, st)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                T(
                    "Толщина: ${SPACER_SIZE_NAMES[spRow.size.coerceIn(0, 2)]}", size = 15f, weight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (sz in 0..2) {
                        Chip(SPACER_SIZE_NAMES[sz], spRow.size == sz, Modifier.weight(1f)) {
                            vm.setSpacerSize(spRow.id, sz)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            for ((label, action) in actions) {
                T(
                    label, size = 17f, weight = FontWeight.Medium,
                    color = if (label == "Удалить") Color(0xFFFF6B6B) else Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.menu = null
                            action()
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                )
            }
        }
    }
}

// =====================================================================
// Переименование папок и приложений
// =====================================================================

@Composable
fun RenameDialogs(vm: LauncherViewModel) {
    val folderId = vm.renameFolderId
    val appKey = vm.renameAppKey
    val spacerId = vm.renameSpacerId
    if (folderId == null && appKey == null && spacerId == null) return

    val initial = when {
        folderId != null -> (vm.items.firstOrNull { it.id == folderId } as? FolderItem)?.name ?: ""
        spacerId != null -> (vm.items.firstOrNull { it.id == spacerId } as? SpacerItem)?.title ?: ""
        else -> vm.labelOf(appKey!!)
    }

    var text by remember(folderId, appKey, spacerId) { mutableStateOf(initial) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(folderId, appKey, spacerId) {
        delay(80)
        fr.requestFocus()
    }

    fun close() {
        vm.renameFolderId = null
        vm.renameAppKey = null
        vm.renameSpacerId = null
    }

    AlertDialog(
        onDismissRequest = { close() },
        containerColor = Color(0xFF232325),
        title = {
            T(
                when {
                    folderId != null -> "Название папки"
                    spacerId != null -> "Название разделителя"
                    else -> "Подпись иконки"
                },
                size = 20f, weight = FontWeight.SemiBold,
            )
        },
        text = {
            Column {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 18.sp, fontFamily = Inter),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fr)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.1f))
                        .padding(14.dp),
                )
                if (appKey != null || spacerId != null) {
                    Spacer(Modifier.height(8.dp))
                    T(if (spacerId != null) "Пусто — без названия, только линия" else "Пусто — вернуть оригинальное название", size = 12f, color = Dim)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    folderId != null -> vm.renameFolder(folderId, text)
                    spacerId != null -> vm.renameSpacer(spacerId, text)
                    appKey != null -> vm.renameApp(appKey, text)
                }
                close()
            }) { T("Готово", size = 16f, weight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = { close() }) { T("Отмена", size = 16f, color = Dim) }
        },
    )
}


// =====================================================================
// Выбор папки для приложения
// =====================================================================

@Composable
fun FolderPicker(vm: LauncherViewModel) {
    val key = vm.pickFolderFor ?: return
    val folders = vm.items.filterIsInstance<FolderItem>()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .pointerInput(Unit) { detectTapGestures { vm.pickFolderFor = null } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(10.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Glass)
                .padding(vertical = 8.dp)
        ) {
            T(
                "Куда положить «${vm.labelOf(key)}»", size = 14f, color = Dim,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )
            LazyColumn(Modifier.height((folders.size.coerceAtMost(6) * 50 + 50).dp)) {
                items(folders, key = { it.id }) { f ->
                    T(
                        "${f.name}  ·  ${f.apps.size}", size = 17f, weight = FontWeight.Medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.pickFolderFor = null
                                vm.moveToFolder(key, f.id)
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                    )
                }
                item {
                    T(
                        "+ Новая папка", size = 17f, weight = FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.pickFolderFor = null
                                vm.moveToFolder(key, null)
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                    )
                }
            }
        }
    }
}
