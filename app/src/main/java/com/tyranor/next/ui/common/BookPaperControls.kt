package com.tyranor.next.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.AbsoluteCutCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.BookPaperPalette
import com.tyranor.next.theme.BookSerif
import com.tyranor.next.theme.BookSerifBold
import com.tyranor.next.theme.CurrentBookPaperPalette
import com.tyranor.next.theme.DialogItemSurface
import com.tyranor.next.theme.LocalAppDialogContent
import com.tyranor.next.theme.LocalShelfBookLeaf
import com.tyranor.next.theme.NavWhite
import com.tyranor.next.theme.SecondaryTextColor
import com.tyranor.next.theme.ShelfBookClothWeaveDark
import com.tyranor.next.theme.ShelfBookClothWeaveLight
import com.tyranor.next.theme.ShelfBookFooterStyle
import com.tyranor.next.theme.ShelfBookShade
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.ui.common.motion.PressSquishDefaults
import com.tyranor.next.ui.common.motion.pressSquish
import com.tyranor.next.ui.common.motion.shelfBookMarker
import kotlin.math.PI
import kotlin.math.sin
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults

private const val BOOK_CLOTH_GRAIN_SIZE = 64
private const val BOOK_CLOTH_SEED = 20261122L

/** 精装布纹硬壳纹理（64×64 周期无缝）：正交经纬织线叠加微纤维颗粒，供书封与书卷按钮复用。 */
internal val shelfBookClothGrainImage: ImageBitmap by lazy { buildShelfBookClothGrain() }
internal val shelfBookClothGrainBrush: ShaderBrush by lazy {
    ShaderBrush(ImageShader(shelfBookClothGrainImage, TileMode.Repeated, TileMode.Repeated))
}

private fun buildShelfBookClothGrain(): ImageBitmap {
    val size = BOOK_CLOTH_GRAIN_SIZE
    val random = java.util.Random(BOOK_CLOTH_SEED)
    val lightBase = ShelfBookClothWeaveLight.toArgb()
    val darkBase = ShelfBookClothWeaveDark.toArgb()
    val lightR = (lightBase shr 16) and 0xFF
    val lightG = (lightBase shr 8) and 0xFF
    val lightB = lightBase and 0xFF
    val darkR = (darkBase shr 16) and 0xFF
    val darkG = (darkBase shr 8) and 0xFF
    val darkB = darkBase and 0xFF
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val wy = sin(y * 2.0 * PI / 4.0).toFloat()
        for (x in 0 until size) {
            val wx = sin(x * 2.0 * PI / 4.0).toFloat()
            val weave = (wx * 0.55f + wy * 0.45f) * 0.5f + ((x xor y) and 1) * 0.14f - 0.07f
            val grit = (random.nextFloat() - 0.5f) * 0.36f
            val v = (weave + grit).coerceIn(-1f, 1f)
            if (v >= 0f) {
                val a = (v * 20f).toInt().coerceIn(0, 255)
                pixels[y * size + x] = (a shl 24) or (lightR shl 16) or (lightG shl 8) or lightB
            } else {
                val a = ((-v) * 28f).toInt().coerceIn(0, 255)
                pixels[y * size + x] = (a shl 24) or (darkR shl 16) or (darkG shl 8) or darkB
            }
        }
    }
    return android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** 生成以 [inset] 内缩、外侧倒角为 [cut] 的对称 45° 斜切八边形路径。 */
internal fun shelfBookChamferRectPath(width: Float, height: Float, inset: Float, cut: Float): Path {
    val l = inset
    val t = inset
    val r = (width - inset).coerceAtLeast(l + 2f)
    val b = (height - inset).coerceAtLeast(t + 2f)
    val c = cut.coerceAtMost(minOf(r - l, b - t) * 0.35f)
    return Path().apply {
        moveTo(l + c, t)
        lineTo(r - c, t)
        lineTo(r, t + c)
        lineTo(r, b - c)
        lineTo(r - c, b)
        lineTo(l + c, b)
        lineTo(l, b - c)
        lineTo(l, t + c)
        close()
    }
}

/** 书页内淡入次级按钮（羊皮纸烫金细框样式，对应全局 tonal 按钮）。 */
internal fun bookParchmentButtonStyle(palette: BookPaperPalette): ShelfBookFooterStyle = ShelfBookFooterStyle(
    fillTop = palette.washSubtle.copy(alpha = (palette.washSubtle.alpha * 1.45f).coerceAtMost(0.24f)),
    fillBottom = palette.washSubtle,
    outerBorder = palette.popupBorder,
    topSpecular = if (palette.isDarkPaper) Color(0x22F7ECDA) else Color(0x45FFFBF2),
    radialSheen = Color.Transparent,
    ambientGlow = Color.Transparent,
    content = palette.ink,
    shadowAlpha = 0f,
    clothAlpha = 0f,
)

/**
 * 书页书扣主按钮共用外壳：45° 斜切八边形 + 软影微晕 + 亚麻布纹与径向微光 + 克制外细框。
 * 同时用于书册主/管理页脚（「启动游戏」「删除游戏」）与书内三级页的 [NoRippleButton]。
 */
@Composable
internal fun BookScrollClaspButton(
    style: ShelfBookFooterStyle,
    onClick: () -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
    cloth: ImageBitmap? = shelfBookClothGrainImage,
    enabled: Boolean = true,
    fillMaxWidth: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    contentDescriptionLabel: String? = null,
    leadingIcon: (@Composable BoxScope.() -> Unit)? = null,
    labelContent: @Composable RowScope.() -> Unit,
) {
    val cut = minOf(8.dp, height * 0.18f)
    val buttonShape = remember(cut) { AbsoluteCutCornerShape(cut) }
    val widthModifier = if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier
    val semanticsModifier = if (contentDescriptionLabel != null) {
        Modifier.semantics(mergeDescendants = true) { contentDescription = contentDescriptionLabel }
    } else {
        Modifier
    }
    Box(
        modifier
            .then(widthModifier)
            .height(height)
            .alpha(if (enabled) 1f else 0.45f)
            .then(if (enabled) Modifier.pressSquish() else Modifier)
            .drawBehind {
                val cutPx = cut.toPx().coerceAtMost(minOf(size.width, size.height) * 0.35f)
                val shadowDy1 = 1.dp.toPx()
                val shadowDy2 = 2.2.dp.toPx()
                if (enabled && style.ambientGlow.alpha > 0.005f && size.width > cutPx * 2f + 1f) {
                    val glowY = size.height + 0.8.dp.toPx()
                    drawLine(
                        brush = Brush.horizontalGradient(
                            0f to Color.Transparent,
                            0.25f to style.ambientGlow,
                            0.75f to style.ambientGlow,
                            1f to Color.Transparent,
                            startX = cutPx,
                            endX = size.width - cutPx,
                        ),
                        start = Offset(cutPx, glowY),
                        end = Offset(size.width - cutPx, glowY),
                        strokeWidth = 3.2.dp.toPx(),
                    )
                }
                if (enabled && style.shadowAlpha > 0.005f) {
                    val shadowPath = shelfBookChamferRectPath(size.width, size.height, inset = 0f, cut = cutPx)
                    withTransform({ translate(top = shadowDy2) }) {
                        drawPath(shadowPath, ShelfBookShade.copy(alpha = style.shadowAlpha * 0.45f))
                    }
                    withTransform({ translate(top = shadowDy1) }) {
                        drawPath(shadowPath, ShelfBookShade.copy(alpha = style.shadowAlpha * 0.72f))
                    }
                }
            }
            .clip(buttonShape)
            .drawWithCache {
                val cutPx = cut.toPx().coerceAtMost(minOf(size.width, size.height) * 0.35f)
                val fillBrush = Brush.verticalGradient(0f to style.fillTop, 1f to style.fillBottom)
                val clothBrush = if (cloth != null && style.clothAlpha > 0.005f) {
                    if (cloth === shelfBookClothGrainImage) shelfBookClothGrainBrush
                    else ShaderBrush(ImageShader(cloth, TileMode.Repeated, TileMode.Repeated))
                } else {
                    null
                }
                val sheenCenter = Offset(size.width * 0.5f, size.height * 0.08f)
                val sheenRadius = maxOf(size.width * 0.60f, size.height * 2.2f, 1f)
                val sheenBrush = Brush.radialGradient(
                    colors = listOf(style.radialSheen, Color.Transparent),
                    center = sheenCenter,
                    radius = sheenRadius,
                )
                val outerPath = shelfBookChamferRectPath(size.width, size.height, inset = 0.42.dp.toPx(), cut = cutPx)
                val outerStroke = Stroke(width = 0.85.dp.toPx())
                val specY = 0.75.dp.toPx()
                val specStroke = 0.8.dp.toPx()
                val hasSpan = size.width > cutPx * 2f + 1f
                val specBrush = if (hasSpan) {
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.16f to style.topSpecular,
                        0.84f to style.topSpecular,
                        1f to Color.Transparent,
                        startX = cutPx,
                        endX = size.width - cutPx,
                    )
                } else {
                    null
                }
                onDrawBehind {
                    drawRect(fillBrush)
                    if (clothBrush != null) drawRect(clothBrush, alpha = style.clothAlpha)
                    drawRect(sheenBrush)
                    if (specBrush != null) {
                        drawLine(
                            brush = specBrush,
                            start = Offset(cutPx, specY),
                            end = Offset(size.width - cutPx, specY),
                            strokeWidth = specStroke,
                        )
                    }
                    drawPath(outerPath, color = style.outerBorder, style = outerStroke)
                }
            }
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .then(semanticsModifier),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (leadingIcon != null) {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center, content = leadingIcon)
            }
            labelContent()
        }
    }
}

/** 书页内两端渐隐的水平墨线分隔。 */
@Composable
internal fun BookInkFadedDivider(modifier: Modifier = Modifier, alpha: Float = 0.22f) {
    val ink = CurrentBookPaperPalette.ink
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.15f to ink.copy(alpha = alpha),
                0.85f to ink.copy(alpha = alpha),
                1f to Color.Transparent,
            ),
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 0.65.dp.toPx(),
        )
    }
}

/** 书页古典双细墨线。 */
@Composable
internal fun BookInkDoubleRule(modifier: Modifier = Modifier) {
    val ink = CurrentBookPaperPalette.ink
    Canvas(modifier.fillMaxWidth().height(4.dp)) {
        drawLine(ink.copy(alpha = 0.50f), Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), 1.15.dp.toPx())
        drawLine(ink.copy(alpha = 0.24f), Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 0.6.dp.toPx())
    }
}

/** 书页细线小箭头（与目录行一致）。 */
@Composable
internal fun BookInkChevron(color: Color, forward: Boolean = true, modifier: Modifier = Modifier) {
    Canvas(modifier.size(6.dp, 11.dp)) {
        val w = 1.4.dp.toPx()
        val tipX = if (forward) size.width else 0f
        val tailX = size.width - tipX
        drawLine(color, Offset(tailX, 0f), Offset(tipX, size.height / 2f), w, cap = StrokeCap.Round)
        drawLine(color, Offset(tipX, size.height / 2f), Offset(tailX, size.height), w, cap = StrokeCap.Round)
    }
}

/** 分组内容水平外边距：普通页面保持 16dp，书页内已有书页留白故收窄为 0dp。 */
@Composable
fun appSectionHorizontalPadding(): Dp = if (LocalShelfBookLeaf.current) 0.dp else 16.dp

/** 分组内行文本水平边距：普通页面为 16dp，书页内与纸墨条目对齐为 4dp。 */
@Composable
fun appRowHorizontalPadding(): Dp = if (LocalShelfBookLeaf.current) 4.dp else 16.dp

/** 弹窗/书页内占位底色：书外使用 [DialogItemSurface]，书页内自动切为轻淡纸墨渲染底色。 */
@Composable
fun appDialogSurfaceColor(): Color = if (LocalShelfBookLeaf.current) CurrentBookPaperPalette.washSubtle else DialogItemSurface

/**
 * 分组容器：
 * - 普通页面 / 窗口弹窗：`MiuixCard` + 玻璃发丝描边（弹窗内使用 [DialogItemSurface]）。
 * - 书页内（[LocalShelfBookLeaf]）：无卡片块，改以烫金/墨色小标题、留白与底部渐隐细墨线组织分组，与目录页一脉相承。
 */
@Composable
fun AppSectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    insidePadding: PaddingValues = PaddingValues(0.dp),
    glassBorderOutside: Boolean = true,
    useDialogSurfaceOutside: Boolean = true,
    bookDividerAfter: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalShelfBookLeaf.current) {
        val palette = CurrentBookPaperPalette
        Column(modifier = modifier.fillMaxWidth()) {
            if (title != null) {
                Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = BookSerifBold,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = palette.foilAccent,
                    )
                    BookInkDoubleRule(Modifier.padding(top = 4.dp))
                }
            }
            Column(Modifier.fillMaxWidth().padding(insidePadding), content = content)
            if (bookDividerAfter) {
                BookInkFadedDivider(Modifier.padding(top = 6.dp))
            }
        }
        return
    }
    val surfaceColor = if (useDialogSurfaceOutside && LocalAppDialogContent.current) {
        DialogItemSurface
    } else {
        NavWhite
    }
    MiuixCard(
        modifier = modifier.fillMaxWidth().then(if (glassBorderOutside) Modifier.glassBorder() else Modifier),
        cornerRadius = AppComponentCornerRadius,
        colors = MiuixCardDefaults.defaultColors(color = surfaceColor),
        insideMargin = if (title == null) insidePadding else PaddingValues(0.dp),
    ) {
        if (title != null) {
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
                Column(Modifier.fillMaxWidth().padding(insidePadding), content = content)
            }
        } else {
            content()
        }
    }
}

/** 分组说明脚注文案：普通页面遵循卡片外边距，书页内自动贴纸边并使用衬线墨色。 */
@Composable
fun AppSectionNote(
    text: String,
    modifier: Modifier = Modifier,
    topPadding: Dp = 6.dp,
    bottomPadding: Dp = 14.dp,
) {
    val inBook = LocalShelfBookLeaf.current
    val horizontal = if (inBook) 2.dp else 20.dp
    val color = if (inBook) CurrentBookPaperPalette.inkSecondary else SecondaryTextColor
    val style = if (inBook) {
        MaterialTheme.typography.bodyMedium.copy(fontFamily = BookSerif)
    } else {
        MaterialTheme.typography.bodyMedium
    }
    Text(
        text = text,
        style = style,
        color = color,
        modifier = modifier.padding(start = horizontal, end = horizontal, top = topPadding, bottom = bottomPadding),
    )
}

/** 复选框：普通页面复用 Material3 [Checkbox]；书页内自动切为细墨方框与烫金勾线。 */
@Composable
fun AppCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (!LocalShelfBookLeaf.current) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
        return
    }
    val palette = CurrentBookPaperPalette
    val toggleModifier = if (onCheckedChange != null) {
        val interaction = remember { MutableInteractionSource() }
        Modifier
            .minimumInteractiveComponentSize()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                interactionSource = interaction,
                indication = null,
                onValueChange = onCheckedChange,
            )
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .then(toggleModifier)
            .alpha(if (enabled) 1f else 0.42f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val stroke = 1.4.dp.toPx()
            val radius = CornerRadius(2.5.dp.toPx())
            if (checked) {
                drawRoundRect(
                    color = palette.checkboxCheckedFill,
                    cornerRadius = radius,
                )
            }
            drawRoundRect(
                color = if (checked) palette.checkboxCheckedBorder else palette.checkboxBorder,
                cornerRadius = radius,
                style = Stroke(width = stroke),
            )
            if (checked) {
                val mark = Path().apply {
                    moveTo(size.width * 0.24f, size.height * 0.52f)
                    lineTo(size.width * 0.43f, size.height * 0.72f)
                    lineTo(size.width * 0.78f, size.height * 0.30f)
                }
                drawPath(
                    path = mark,
                    color = palette.checkboxCheck,
                    style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

/** 单选行：普通弹窗沿用圆角条目卡 + [RadioButton]；书页内切为墨圈印记、细分割线与荧光笔按压。 */
@Composable
fun AppRadioRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    textStyle: TextStyle? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    if (LocalShelfBookLeaf.current) {
        val palette = CurrentBookPaperPalette
        val resolvedStyle = (textStyle ?: MaterialTheme.typography.bodyMedium).copy(
            fontFamily = if (selected) BookSerifBold else BookSerif,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        Column(modifier = modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressSquish(enabled = enabled, scale = PressSquishDefaults.RowScale)
                    .shelfBookMarker(interaction, palette.marker)
                    .selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                    .alpha(if (enabled) 1f else 0.42f)
                    .padding(horizontal = 4.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(18.dp)) {
                    val outerRadius = size.minDimension / 2f - 1.dp.toPx()
                    drawCircle(
                        color = if (selected) palette.foilAccent else palette.checkboxBorder,
                        radius = outerRadius,
                        style = Stroke(width = 1.4.dp.toPx()),
                    )
                    if (selected) {
                        drawCircle(
                            color = palette.foilAccent,
                            radius = outerRadius * 0.48f,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = label,
                    style = resolvedStyle,
                    color = if (selected) palette.ink else palette.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Canvas(Modifier.fillMaxWidth().height(1.dp)) {
                drawLine(
                    color = palette.ruleSubtle,
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = 0.55.dp.toPx(),
                )
            }
        }
        return
    }
    val bgColor = if (LocalAppDialogContent.current) DialogItemSurface else MaterialTheme.colorScheme.surfaceVariant
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .pressSquish(enabled = enabled, scale = PressSquishDefaults.RowScale)
            .clip(AppComponentShape)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        color = bgColor,
        shape = AppComponentShape,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = textStyle ?: MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
