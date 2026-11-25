package ru.nikitarovno.setka

import android.content.ComponentName
import android.content.SharedPreferences
import android.os.UserHandle
import androidx.compose.ui.graphics.ImageBitmap
import org.json.JSONArray
import org.json.JSONObject

/** Установленное приложение (с учётом клонов / второго пространства). */
data class AppInfo(
    val key: String,
    val label: String,
    val icon: ImageBitmap,
    val component: ComponentName,
    val user: UserHandle,
)

/** Элемент рабочего стола: приложение или папка. */
sealed interface HomeItem {
    val id: String
}

data class AppItem(val key: String) : HomeItem {
    override val id: String get() = key
}

data class FolderItem(
    override val id: String,
    val name: String,
    val apps: List<String>,
) : HomeItem

/** Разделитель-строка на всю ширину. row=false — устаревшие «пустые клетки», удаляются при загрузке. */
data class SpacerItem(
    override val id: String,
    val row: Boolean = true,
    val style: Int = 1,
    val title: String = "",
    val size: Int = 1, // 0 — тонкий, 1 — средний, 2 — широкий
) : HomeItem

val SPACER_SIZE_NAMES = listOf("Тонкий", "Средний", "Широкий")

const val SPACER_STYLES = 5
val SPACER_STYLE_NAMES = listOf("Пусто", "Линия", "Градиент", "Точки", "Свечение")

/** Что показывает контекстное меню (долгий тап без перетаскивания). */
sealed interface MenuTarget {
    data class App(val key: String) : MenuTarget
    data class Folder(val id: String) : MenuTarget
    data class FolderApp(val folderId: String, val key: String) : MenuTarget
    data class Spacer(val id: String) : MenuTarget
    /** Долгий тап по пустому месту главного экрана. */
    object Empty : MenuTarget
}

enum class LabelMode { TWO_LINES, MARQUEE }

data class LauncherSettings(
    val columns: Int = 7,
    val rows: Int = 11,
    val labelSize: Float = 8f,
    val showLabels: Boolean = true,
    val labelMode: LabelMode = LabelMode.MARQUEE,
) {
    fun save(p: SharedPreferences) {
        p.edit()
            .putInt("cols", columns)
            .putInt("rows", rows)
            .putFloat("labelSize", labelSize)
            .putBoolean("showLabels", showLabels)
            .putString("labelMode", labelMode.name)
            .apply()
    }

    companion object {
        fun load(p: SharedPreferences) = LauncherSettings(
            columns = p.getInt("cols", 7),
            rows = p.getInt("rows", 11),
            labelSize = p.getFloat("labelSize", 8f),
            showLabels = p.getBoolean("showLabels", true),
            labelMode = runCatching {
                LabelMode.valueOf(p.getString("labelMode", null) ?: "MARQUEE")
            }.getOrDefault(LabelMode.MARQUEE),
        )
    }
}

/** Хранение раскладки в JSON. */
object LayoutStore {
    private const val KEY = "layout"

    fun load(p: SharedPreferences): List<HomeItem> {
        val raw = p.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                when (o.getString("t")) {
                    "a" -> AppItem(o.getString("k"))
                    "f" -> {
                        val a = o.getJSONArray("apps")
                        FolderItem(
                            id = o.getString("id"),
                            name = o.getString("name"),
                            apps = (0 until a.length()).map { a.getString(it) },
                        )
                    }
                    "s" -> SpacerItem(
                        id = o.getString("id"),
                        row = o.optBoolean("row", true),
                        style = o.optInt("style", 1),
                        title = o.optString("title", ""),
                        size = o.optInt("size", 1),
                    )
                    else -> null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(p: SharedPreferences, items: List<HomeItem>) {
        val arr = JSONArray()
        for (item in items) {
            val o = JSONObject()
            when (item) {
                is AppItem -> {
                    o.put("t", "a")
                    o.put("k", item.key)
                }
                is FolderItem -> {
                    o.put("t", "f")
                    o.put("id", item.id)
                    o.put("name", item.name)
                    o.put("apps", JSONArray(item.apps))
                }
                is SpacerItem -> {
                    o.put("t", "s")
                    o.put("id", item.id)
                    o.put("row", item.row)
                    o.put("style", item.style)
                    o.put("title", item.title)
                    o.put("size", item.size)
                }
            }
            arr.put(o)
        }
        p.edit().putString(KEY, arr.toString()).apply()
    }
}
