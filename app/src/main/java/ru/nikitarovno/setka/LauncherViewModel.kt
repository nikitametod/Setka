package ru.nikitarovno.setka

import android.app.ActivityOptions
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

const val HOLE = "hole-"

class LauncherViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication<Application>()
    private val launcherApps = app.getSystemService(LauncherApps::class.java)
    private val userManager = app.getSystemService(UserManager::class.java)
    private val prefs = app.getSharedPreferences("setka", Context.MODE_PRIVATE)

    var apps by mutableStateOf<Map<String, AppInfo>>(emptyMap())
        private set
    var items by mutableStateOf(LayoutStore.load(prefs))
        private set
    var settings by mutableStateOf(LauncherSettings.load(prefs))
        private set
    var labels by mutableStateOf(loadLabels())
        private set
    var hidden by mutableStateOf(prefs.getStringSet("hidden", emptySet())!!.toSet())
        private set
    var seen by mutableStateOf(prefs.getStringSet("seen", null)?.toSet())
        private set

    // Оверлеи
    var openFolderId by mutableStateOf<String?>(null)
    var searchOpen by mutableStateOf(false)
    var settingsOpen by mutableStateOf(false)
    var menu by mutableStateOf<MenuTarget?>(null)
    var renameFolderId by mutableStateOf<String?>(null)
    var renameAppKey by mutableStateOf<String?>(null)
    var renameSpacerId by mutableStateOf<String?>(null)
    var pickFolderFor by mutableStateOf<String?>(null)

    /** Открыто что-то, что перехватывает касания (меню, настройки, поиск, диалог). */
    val modalOpen: Boolean
        get() = searchOpen || settingsOpen || menu != null || pickFolderFor != null ||
            renameFolderId != null || renameAppKey != null || renameSpacerId != null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) = reload()
        override fun onPackageAdded(packageName: String?, user: UserHandle?) = reload()
        override fun onPackageChanged(packageName: String?, user: UserHandle?) = reload()
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = reload()
    }

    init {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        reload()
    }

    override fun onCleared() {
        launcherApps.unregisterCallback(callback)
    }

    // =====================================================================
    // Загрузка приложений
    // =====================================================================

    private var loadJob: Job? = null

    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val loaded = withContext(Dispatchers.Default) { loadApps() }
            if (loaded.isEmpty()) return@launch
            apps = loaded
            if (seen == null) writeSeen(loaded.keys) // первый запуск: всё, что есть, — не «новое»
            reconcile()
        }
    }

    private fun loadApps(): Map<String, AppInfo> {
        val sizePx = (ctx.resources.displayMetrics.density * 72).toInt()
        val result = LinkedHashMap<String, AppInfo>()
        for (user in launcherApps.profiles) {
            val serial = userManager.getSerialNumberForUser(user)
            val list = try {
                launcherApps.getActivityList(null, user)
            } catch (e: Exception) {
                emptyList()
            }
            for (a in dedupe(list)) {
                val cn = a.componentName
                if (cn.packageName == ctx.packageName) continue
                val key = "${cn.flattenToString()}#$serial"
                val drawable = try {
                    a.getBadgedIcon(0)
                } catch (e: Exception) {
                    a.getIcon(0)
                }
                val bmp = renderIcon(drawable, sizePx).asImageBitmap()
                result[key] = AppInfo(key, a.label.toString(), bmp, cn, user)
            }
        }
        return result
    }

    /**
     * У части системных приложений Vivo несколько входов с одинаковым названием
     * (две «Камеры», двое «Альбомов»). Оставляем основной — тот, что Android
     * отдаёт как главную точку запуска пакета.
     */
    private fun dedupe(list: List<LauncherActivityInfo>): List<LauncherActivityInfo> {
        val groups = list.groupBy { it.componentName.packageName to it.label.toString() }
        val out = ArrayList<LauncherActivityInfo>()
        for ((k, g) in groups) {
            if (g.size == 1) {
                out += g[0]
                continue
            }
            val main = try {
                ctx.packageManager.getLaunchIntentForPackage(k.first)?.component
            } catch (e: Exception) {
                null
            }
            out += g.firstOrNull { it.componentName == main } ?: g[0]
        }
        return out
    }

    /**
     * Адаптивные иконки рисуем сами слоями на всю площадь (без системной маски Vivo),
     * чтобы потом обрезать все одинаковым скруглением. Обычные — срезаем поля.
     */
    private fun renderIcon(d: Drawable, size: Int): Bitmap {
        if (d !is AdaptiveIconDrawable) return trimToFill(d.toBitmap(size, size, Bitmap.Config.ARGB_8888), size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val pad = size / 4
        for (layer in listOf(d.background, d.foreground)) {
            if (layer == null) continue
            layer.setBounds(-pad, -pad, size + pad, size + pad)
            layer.draw(c)
        }
        return bmp
    }

    private fun trimToFill(src: Bitmap, size: Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var l = w
        var t = h
        var r = -1
        var b = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((px[row + x] ushr 24) > 24) {
                    if (x < l) l = x
                    if (x > r) r = x
                    if (y < t) t = y
                    if (y > b) b = y
                }
            }
        }
        if (r < 0) return src
        val cw = r - l + 1
        val ch = b - t + 1
        if (cw >= w * 0.94f && ch >= h * 0.94f) return src
        val side = maxOf(cw, ch)
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        val sl = (cx - side / 2).coerceIn(0, maxOf(0, w - side))
        val st = (cy - side / 2).coerceIn(0, maxOf(0, h - side))
        val cropped = Bitmap.createBitmap(src, sl, st, minOf(side, w - sl), minOf(side, h - st))
        return Bitmap.createScaledBitmap(cropped, size, size, true)
    }

    /** Убираем удалённые приложения, новые добавляем в конец по алфавиту. */
    private fun reconcile() {
        val present = apps.keys - hidden
        val used = HashSet<String>()
        val out = ArrayList<HomeItem>()
        for (item in items) {
            when (item) {
                is AppItem -> if (item.key in present && used.add(item.key)) out += item
                is FolderItem -> {
                    val inner = item.apps.filter { it in present && used.add(it) }
                    if (inner.isNotEmpty()) out += item.copy(apps = inner)
                }
                // Разделители и пустые клетки (дырки) сохраняются как есть
                is SpacerItem -> if (item.row || item.id.startsWith(HOLE)) out += item
            }
        }
        apps.values
            .filter { it.key !in used && it.key !in hidden }
            .sortedBy { labelOf(it.key).lowercase() }
            .forEach { out += AppItem(it.key) }
        if (out != items) {
            items = out
            save()
        }
    }

    private fun save() = LayoutStore.save(prefs, items)

    // =====================================================================
    // «Новые» приложения
    // =====================================================================

    fun isNew(key: String): Boolean = seen?.let { key !in it } ?: false

    private fun writeSeen(s: Set<String>) {
        seen = s
        prefs.edit().putStringSet("seen", s).apply()
    }

    fun markSeen(keys: Collection<String>) {
        val cur = seen ?: return
        if (keys.all { it in cur }) return
        writeSeen(cur + keys)
    }

    // =====================================================================
    // Перетаскивание (пачки)
    // =====================================================================

    /**
     * Что показываем на главном, пока пачка «в руке». Элементы с главного остаются на месте
     * (рисуются полупрозрачными), пачка из папки вынимается из папки.
     */
    fun homeWithout(stack: List<String>, fromFolder: String?): List<HomeItem> {
        if (stack.isEmpty() || fromFolder == null) return items
        val set = stack.toSet()
        return items.mapNotNull {
            if (it is FolderItem && it.id == fromFolder) {
                val rest = it.apps.filter { k -> k !in set }
                if (rest.isEmpty()) null else it.copy(apps = rest)
            } else it
        }
    }

    private fun stackItems(stack: List<String>, fromFolder: String?): List<HomeItem> =
        if (fromFolder == null) stack.mapNotNull { id -> items.firstOrNull { it.id == id } }
        else stack.map { AppItem(it) }

    /** Можно ли бросить пачку «внутрь» элемента (сделать/пополнить папку). */
    fun canMergeInto(stack: List<String>, fromFolder: String?, targetId: String): Boolean {
        val target = items.firstOrNull { it.id == targetId } ?: return false
        if (target is SpacerItem) return false
        return stackItems(stack, fromFolder).all { it is AppItem }
    }

    private fun newHole() = SpacerItem(HOLE + UUID.randomUUID().toString(), row = false)
    private fun HomeItem.isHole() = this is SpacerItem && !row
    private fun HomeItem.isRowSpacer() = this is SpacerItem && row

    fun isRowSpacer(id: String) = items.any { it.id == id && it.isRowSpacer() }

    /** Блок: разделитель и всё под ним до следующего разделителя. */
    fun blockIds(spacerId: String): List<String> {
        val start = items.indexOfFirst { it.id == spacerId }
        if (start < 0) return emptyList()
        var end = start + 1
        while (end < items.size && !items[end].isRowSpacer()) end++
        return items.subList(start, end).map { it.id }
    }

    /**
     * Переносим блок целиком. Бросили на верхнюю половину разделителя — блок встаёт перед ним;
     * бросили внутрь чужого блока — встаёт сразу после этого блока; ниже всех — в конец.
     */
    private fun moveBlock(spacerId: String, ins: Int, displayKeys: List<String>) {
        val groupIds = blockIds(spacerId).toSet()
        if (groupIds.isEmpty()) return
        val group = items.filter { it.id in groupIds }
        val rest = items.filter { it.id !in groupIds }.toMutableList()
        val realIds = rest.map { it.id }.toSet()
        val anchor = if (ins < 0) null else displayKeys.drop(ins).firstOrNull { it in realIds }
        var at = if (anchor == null) rest.size else rest.indexOfFirst { it.id == anchor }
        if (at < 0) at = rest.size
        while (at < rest.size && !rest[at].isRowSpacer()) at++
        rest.addAll(at, group)
        items = trimHoles(rest)
        save()
    }

    /**
     * Бросили пачку на главном. Раскладка — сетка с пустыми клетками:
     * - бросили в пустую клетку — элемент встаёт ровно туда, остальные не двигаются;
     * - бросили между иконками — соседи сдвигаются только до ближайшей пустой клетки в этом блоке;
     * - место, откуда взяли, становится пустой клеткой (ничего не съезжает).
     * displayKeys — то, что было на экране в момент броска (с временными пустыми клетками).
     * mergeId — бросили на иконку/папку: папка. Иначе ins — индекс в displayKeys (-1 — никуда).
     */
    fun dropOnHome(stack: List<String>, fromFolder: String?, ins: Int, mergeId: String?, displayKeys: List<String>) {
        val display = homeWithout(stack, fromFolder)
        val byId = display.associateBy { it.id }
        val set = stack.toSet()
        val moving = stackItems(stack, fromFolder)
        if (moving.isEmpty()) return
        if (ins < 0 && mergeId == null && fromFolder == null) return
        if (fromFolder == null && mergeId == null && moving.size == 1 && moving[0].isRowSpacer()) {
            moveBlock(moving[0].id, ins, displayKeys)
            return
        }

        // Экран как список клеток: временные дырки — настоящие, взятые элементы — дырки на их месте
        val l = ArrayList<HomeItem>()
        val seenIds = HashSet<String>()
        for (id in displayKeys) {
            val it = byId[id]
            when {
                it != null -> {
                    seenIds += id
                    l += if (fromFolder == null && id in set) newHole() else it
                }
                id.startsWith(VHOLE) -> l += newHole()
            }
        }
        // На всякий случай: всё, чего не было на экране, — в конец
        for (it in display) if (it.id !in seenIds) l += if (fromFolder == null && it.id in set) newHole() else it

        markSeen(moving.filterIsInstance<AppItem>().map { it.key })
        val cells = moving.none { it.isRowSpacer() }

        when {
            mergeId != null -> {
                val i = l.indexOfFirst { it.id == mergeId }
                val keys = moving.filterIsInstance<AppItem>().map { it.key }
                if (i < 0 || keys.size != moving.size) return
                l[i] = when (val t = l[i]) {
                    is AppItem -> FolderItem(UUID.randomUUID().toString(), "Папка", listOf(t.key) + keys)
                    is FolderItem -> t.copy(apps = t.apps + keys)
                    is SpacerItem -> t
                }
            }
            ins < 0 -> l.addAll(moving)
            else -> {
                var at = ins.coerceIn(0, l.size)
                for (m in moving) {
                    if (cells && at < l.size && l[at].isHole()) {
                        l[at] = m
                    } else {
                        l.add(at, m)
                        if (cells) {
                            // Сдвигаем только до ближайшей дырки до следующего разделителя
                            var j = at + 1
                            while (j < l.size && !l[j].isRowSpacer()) {
                                if (l[j].isHole()) {
                                    l.removeAt(j)
                                    break
                                }
                                j++
                            }
                        }
                    }
                    at++
                }
            }
        }

        items = trimHoles(l)
        if (fromFolder != null && items.none { it.id == fromFolder }) openFolderId = null
        save()
    }

    /** Убираем пустые клетки в хвосте каждого блока (перед разделителем и в самом конце) — они ничего не держат. */
    private fun trimHoles(l: List<HomeItem>): List<HomeItem> {
        val out = ArrayList<HomeItem>(l.size)
        for (it in l) {
            if (it.isRowSpacer()) while (out.isNotEmpty() && out.last().isHole()) out.removeAt(out.size - 1)
            out += it
        }
        while (out.isNotEmpty() && out.last().isHole()) out.removeAt(out.size - 1)
        return out
    }

    fun stackAsItems(stack: List<String>, fromFolder: String?): List<HomeItem> = stackItems(stack, fromFolder)

    fun dropInFolder(folderId: String, stack: List<String>, insertAt: Int) {
        markSeen(stack)
        updateFolder(folderId) { f ->
            val rest = f.apps.filter { it !in stack }.toMutableList()
            rest.addAll(insertAt.coerceIn(0, rest.size), stack)
            f.copy(apps = rest)
        }
    }

    // =====================================================================
    // Папки
    // =====================================================================

    /** Перенести приложение с главного экрана в папку (или в новую, если folderId == null). */
    fun moveToFolder(key: String, folderId: String?) {
        markSeen(listOf(key))
        val idx = items.indexOfFirst { it is AppItem && it.key == key }
        if (idx < 0) return
        val l = items.toMutableList()
        if (folderId == null) {
            val f = FolderItem(UUID.randomUUID().toString(), "Папка", listOf(key))
            l[idx] = f
            items = l
            save()
            renameFolderId = f.id
            return
        }
        val fIdx = l.indexOfFirst { it is FolderItem && it.id == folderId }
        if (fIdx < 0) return
        val f = l[fIdx] as FolderItem
        l[fIdx] = f.copy(apps = f.apps + key)
        l.removeAt(idx)
        items = l
        save()
    }

    fun removeFromFolder(folderId: String, key: String) {
        val idx = items.indexOfFirst { it.id == folderId }
        if (idx < 0) return
        val f = items[idx] as FolderItem
        val l = items.toMutableList()
        val rest = f.apps - key
        if (rest.isEmpty()) {
            l[idx] = AppItem(key)
            openFolderId = null
        } else {
            l[idx] = f.copy(apps = rest)
            l.add(idx + 1, AppItem(key))
        }
        items = l
        save()
    }

    fun disbandFolder(folderId: String) {
        val idx = items.indexOfFirst { it.id == folderId }
        if (idx < 0) return
        val f = items[idx] as FolderItem
        val l = items.toMutableList()
        l.removeAt(idx)
        l.addAll(idx, f.apps.map { AppItem(it) })
        items = l
        if (openFolderId == folderId) openFolderId = null
        save()
    }

    fun renameFolder(folderId: String, name: String) {
        val clean = name.trim().ifEmpty { "Папка" }
        updateFolder(folderId) { it.copy(name = clean) }
    }

    private fun updateFolder(id: String, block: (FolderItem) -> FolderItem) {
        items = items.map { if (it is FolderItem && it.id == id) block(it) else it }
        save()
    }

    // =====================================================================
    // Разделители
    // =====================================================================

    /** Новый разделитель — в самый низ: под ним сразу пустой блок с рядом клеток. Блок потом можно утащить за разделитель. */
    fun addSeparator() {
        items = items + SpacerItem(UUID.randomUUID().toString(), row = true, style = 1)
        save()
    }

    fun setSpacerSize(id: String, size: Int) = updateSpacer(id) { it.copy(size = size) }

    fun setSpacerStyle(id: String, style: Int) = updateSpacer(id) { it.copy(style = style) }

    fun cycleSpacerStyle(id: String) = updateSpacer(id) { it.copy(style = (it.style + 1) % SPACER_STYLES) }

    fun renameSpacer(id: String, title: String) = updateSpacer(id) { it.copy(title = title.trim()) }

    fun removeItem(id: String) {
        items = items.filter { it.id != id }
        save()
    }

    private fun updateSpacer(id: String, block: (SpacerItem) -> SpacerItem) {
        items = items.map { if (it is SpacerItem && it.id == id) block(it) else it }
        save()
    }

    // =====================================================================
    // Подписи и скрытие
    // =====================================================================

    fun labelOf(key: String): String = labels[key] ?: apps[key]?.label ?: ""

    private fun loadLabels(): Map<String, String> = try {
        val o = JSONObject(prefs.getString("labels", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (e: Exception) {
        emptyMap()
    }

    fun renameApp(key: String, name: String) {
        val clean = name.trim()
        labels = if (clean.isEmpty() || clean == apps[key]?.label) labels - key else labels + (key to clean)
        prefs.edit().putString("labels", JSONObject(labels).toString()).apply()
    }

    fun hide(key: String) {
        hidden = hidden + key
        prefs.edit().putStringSet("hidden", hidden).apply()
        items = items.mapNotNull {
            when (it) {
                is AppItem -> if (it.key == key) null else it
                is FolderItem -> {
                    val rest = it.apps - key
                    if (rest.isEmpty()) null else it.copy(apps = rest)
                }
                is SpacerItem -> it
            }
        }
        save()
    }

    fun unhide(key: String) {
        hidden = hidden - key
        prefs.edit().putStringSet("hidden", hidden).apply()
        reconcile()
    }

    fun updateSettings(s: LauncherSettings) {
        settings = s
        s.save(prefs)
    }

    fun closeOverlays() {
        menu = null
        pickFolderFor = null
        renameFolderId = null
        renameAppKey = null
        renameSpacerId = null
        if (searchOpen) searchOpen = false
        else if (settingsOpen) settingsOpen = false
        else openFolderId = null
    }

    fun closeAll() {
        menu = null
        pickFolderFor = null
        renameFolderId = null
        renameAppKey = null
        renameSpacerId = null
        searchOpen = false
        settingsOpen = false
        openFolderId = null
    }

    // =====================================================================
    // Запуск и системные действия
    // =====================================================================

    /** Запуск с анимацией раскрытия из иконки (bounds — где иконка на экране). */
    fun launch(key: String, view: View? = null, bounds: Rect? = null) {
        val a = apps[key] ?: return
        markSeen(listOf(key))
        try {
            val opts = if (view != null && bounds != null) {
                ActivityOptions.makeScaleUpAnimation(
                    view, bounds.left, bounds.top, bounds.width(), bounds.height()
                ).toBundle()
            } else null
            launcherApps.startMainActivity(a.component, a.user, bounds, opts)
        } catch (e: Exception) {
            reload()
        }
    }

    fun appDetails(key: String) {
        val a = apps[key] ?: return
        try {
            launcherApps.startAppDetailsActivity(a.component, a.user, null, null)
        } catch (_: Exception) {
        }
    }

    fun uninstall(key: String) {
        val a = apps[key] ?: return
        try {
            val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.component.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (_: Exception) {
        }
    }
}
