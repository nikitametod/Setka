package ru.nikitarovno.setka

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.Font as ResFont

val Inter = FontFamily(
    ResFont(R.font.inter_regular, FontWeight.Normal),
    ResFont(R.font.inter_medium, FontWeight.Medium),
    ResFont(R.font.inter_semibold, FontWeight.SemiBold),
    ResFont(R.font.inter_bold, FontWeight.Bold),
)

val LabelShadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1.5f), 6f)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppLabel(text: String, s: LauncherSettings) {
    val style = TextStyle(
        color = Color.White,
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = s.labelSize.sp,
        lineHeight = (s.labelSize * 1.18f).sp,
        letterSpacing = (-0.1).sp,
        textAlign = TextAlign.Center,
        shadow = LabelShadow,
        hyphens = Hyphens.Auto,
        lineBreak = LineBreak.Paragraph,
    )
    when (s.labelMode) {
        LabelMode.TWO_LINES -> Text(
            text = text,
            style = style,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 1.dp),
        )
        LabelMode.MARQUEE -> Text(
            text = text,
            style = style,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .basicMarquee(
                    iterations = Int.MAX_VALUE,
                    initialDelayMillis = 1500,
                    repeatDelayMillis = 2500,
                    velocity = 28.dp,
                ),
        )
    }
}

/** Размер иконки: сначала резервируем место под подпись, остальное — иконке. */
@Composable
private fun iconSizeFor(w: Dp, h: Dp, s: LauncherSettings): Dp {
    val byW = w * 0.72f
    if (!s.showLabels) return minOf(byW, h * 0.78f)
    val lines = if (s.labelMode == LabelMode.TWO_LINES) 2 else 1
    val labelH = with(LocalDensity.current) { (s.labelSize * 1.18f * lines).sp.toDp() }
    return minOf(byW, h - labelH - 12.dp)
}

@Composable
fun AppCell(app: AppInfo?, label: String, s: LauncherSettings, st: CellState, isNew: Boolean = false) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val icon = iconSizeFor(maxWidth, maxHeight, s)
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (st.mergeTarget) {
                    Box(
                        Modifier
                            .size(icon + 10.dp)
                            .clip(RoundedCornerShape(icon * 0.3f))
                            .background(Color.White.copy(alpha = 0.28f))
                    )
                }
                if (app != null) {
                    Image(
                        bitmap = app.icon,
                        contentDescription = label,
                        modifier = Modifier
                            .size(icon)
                            .clip(RoundedCornerShape(icon * 0.27f))
                    )
                } else {
                    Spacer(Modifier.size(icon))
                }
            }
            if (s.showLabels && !st.dragging) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isNew) {
                        Box(
                            Modifier
                                .padding(end = 3.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4DA3FF))
                        )
                    }
                    AppLabel(label, s)
                }
            }
        }
    }
}

/** Разделитель-строка. */
@Composable
fun SpacerCell(sp: SpacerItem, s: LauncherSettings, st: CellState) {
    val outline = st.editing || st.dragging
    val titled = sp.title.isNotEmpty()
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
            .then(
                if (outline) Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp)
                else Modifier
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!titled) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { SpacerLine(sp.style, LineFade.BOTH) }
        } else {
            // Название — ровно посередине, полоска расходится от него в обе стороны
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { SpacerLine(sp.style, LineFade.LEFT) }
            Text(
                sp.title.uppercase(),
                color = Color.White.copy(alpha = 0.9f),
                fontFamily = Inter,
                fontWeight = FontWeight.SemiBold,
                fontSize = (s.labelSize + 1f).sp,
                letterSpacing = 1.2.sp,
                style = TextStyle(shadow = LabelShadow),
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { SpacerLine(sp.style, LineFade.RIGHT) }
        }
    }
}

/** С какой стороны полоска гаснет: BOTH — к обоим краям, LEFT — к левому (у текста яркая), RIGHT — к правому. */
enum class LineFade { BOTH, LEFT, RIGHT }

private fun fadeColors(fade: LineFade, mid: List<Color>, clear: Color): List<Color> = when (fade) {
    LineFade.BOTH -> listOf(clear) + mid + listOf(clear)
    LineFade.LEFT -> listOf(clear) + mid
    LineFade.RIGHT -> mid + listOf(clear)
}

@Composable
private fun SpacerLine(style: Int, fade: LineFade) {
    when (style) {
        1 -> Box(
            Modifier
                .fillMaxWidth()
                .height(0.8.dp)
                .background(Color.White.copy(alpha = 0.35f))
        )
        2 -> Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    Brush.horizontalGradient(
                        fadeColors(fade, listOf(Color.White.copy(alpha = 0.6f)), Color.White.copy(alpha = 0f))
                    )
                )
        )
        3 -> Canvas(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
        ) {
            val step = 9.dp.toPx()
            var x = step / 2
            while (x < size.width) {
                drawCircle(Color.White.copy(alpha = 0.45f), radius = 1.6.dp.toPx(), center = Offset(x, size.height / 2))
                x += step
            }
        }
        4 -> Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .blur(6.dp)
                .background(
                    Brush.horizontalGradient(
                        fadeColors(
                            fade,
                            listOf(Color(0xAAFF8AD8), Color(0xAA8AB4FF), Color(0xAA80FFD4)),
                            Color(0x0080FFD4),
                        )
                    ),
                    RoundedCornerShape(5.dp)
                )
        )
        else -> {}
    }
}

@Composable
fun FolderIcon(folder: FolderItem, apps: Map<String, AppInfo>, size: Dp, highlighted: Boolean) {
    val shape = RoundedCornerShape(size * 0.27f)
    val pad = size * 0.11f
    val gap = size * 0.045f
    val mini = (size - pad * 2 - gap * 2) / 3
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(Color.White.copy(alpha = if (highlighted) 0.38f else 0.2f))
            .border(0.6.dp, Color.White.copy(alpha = 0.28f), shape)
            .padding(pad)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            for (r in 0 until 3) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (c in 0 until 3) {
                        val key = folder.apps.getOrNull(r * 3 + c)
                        val a = key?.let { apps[it] }
                        if (a != null) {
                            Image(
                                bitmap = a.icon,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(mini)
                                    .clip(RoundedCornerShape(mini * 0.27f))
                            )
                        } else {
                            Spacer(Modifier.size(mini))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FolderCell(folder: FolderItem, apps: Map<String, AppInfo>, s: LauncherSettings, st: CellState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val icon = iconSizeFor(maxWidth, maxHeight, s)
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FolderIcon(folder, apps, icon, st.mergeTarget)
            if (s.showLabels && !st.dragging) {
                Spacer(Modifier.height(4.dp))
                AppLabel(folder.name, s)
            }
        }
    }
}
