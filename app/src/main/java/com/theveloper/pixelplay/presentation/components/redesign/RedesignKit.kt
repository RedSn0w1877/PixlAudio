@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.theveloper.pixelplay.presentation.components.redesign

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded

/*
 * Redesign batch 2 kit (Library, Search, Queue, Ask Taizo, Settings).
 *
 * Every number here is copied from the Android mockup frames (project/Android*.dc.html, 390 x 844
 * at 1 px = 1 dp) so the iOS port can read the same values off this file. Colours are roles, not
 * hex: the mockup's #4F378B is primaryContainer, #4A4458 secondaryContainer, #2B2930
 * surfaceContainerHigh, #211F26 surfaceContainer, #36343B surfaceContainerHighest, #938F99
 * outline, #CAC4D0 onSurfaceVariant, #D0BCFF primary.
 *
 * Nothing here allocates per frame: shapes are constants or remembered by Compose
 * (MaterialShapes.toShape()), colour animations are the only animated values and they are read
 * by the background modifier.
 */
object Redesign {
    /** Screen side gutter. */
    val Gutter = 16.dp

    /** Big screen title ("Library", "Search", "Settings"): 36/44 sp, bold, -0.5 sp tracking. */
    val ScreenTitleSize = 36.sp
    val ScreenTitleLine = 44.sp

    /** Compact screen title (Search with results, Queue, Taizo): 28/34-36 sp bold. */
    val CompactTitleSize = 28.sp

    /** Header square buttons: 48 dp, 16 dp corners. */
    val HeaderButtonSize = 48.dp
    val HeaderButtonCorner = 16.dp

    /** Filter chips: 36 dp tall, 10 dp corners, 14 dp side padding (10 before a check). */
    val ChipHeight = 36.dp
    val ChipCorner = 10.dp

    /** Connected button groups: 2 dp gaps, outer corner = half the height, inner 6 dp (8 on 44 dp). */
    val GroupGap = 2.dp

    /** Grouped list rows (settings, queue): outer corner 20 dp, inner 4 dp, 2 dp gaps. */
    val RowOuterCorner = 20.dp
    val RowInnerCorner = 4.dp

    /** Section label above a group: 14/20 sp medium in primary, 16 dp start padding. */
    val SectionLabelSize = 14.sp
}

/** Title + text sizes of the redesign in one place, built once. */
object RedesignType {
    val ScreenTitle = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Bold,
        fontSize = Redesign.ScreenTitleSize,
        lineHeight = Redesign.ScreenTitleLine,
        letterSpacing = (-0.5).sp,
    )
    val CompactTitle = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Bold,
        fontSize = Redesign.CompactTitleSize,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp,
    )
    val RowTitle = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    )
    val RowSubtitle = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )
    val Label = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
    val SectionTitle = TextStyle(
        fontFamily = GoogleSansRounded,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 22.sp,
    )
}

/** Big screen title as a heading. */
@Composable
fun RedesignScreenTitle(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = RedesignType.ScreenTitle,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { heading() },
    )
}

private val HeaderButtonShape = RoundedCornerShape(Redesign.HeaderButtonCorner)

/**
 * The mockup's header square button (New playlist, New chat): 48 dp, 16 dp corners, icon 24 dp.
 * Default colours are primaryContainer (the accent action); pass surfaceContainerHigh for the
 * neutral ones (settings, new chat).
 */
@Composable
fun RedesignHeaderButton(
    icon: Painter,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    iconSize: Dp = 24.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(Redesign.HeaderButtonSize)
            .clip(HeaderButtonShape)
            .background(containerColor)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter = icon, contentDescription = contentDescription, tint = contentColor, modifier = Modifier.size(iconSize))
    }
}

private val ChipShape = RoundedCornerShape(Redesign.ChipCorner)

/**
 * Filter chip from the Library mockup: 36 dp, 10 dp corners. Selected = secondaryContainer with a
 * leading 18 dp check; unselected = 1 dp outline border, onSurfaceVariant label. The fill fades.
 */
@Composable
fun RedesignFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    contentDescription: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (selected) scheme.secondaryContainer else Color.Transparent,
        label = "chipContainer",
    )
    val content = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
    val showCheck = selected && leadingIcon == null
    Row(
        modifier = modifier
            .height(Redesign.ChipHeight)
            .clip(ChipShape)
            .background(container)
            .then(if (selected) Modifier else Modifier.border(BorderStroke(1.dp, scheme.outline), ChipShape))
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(
                start = if (showCheck || leadingIcon != null) 10.dp else 14.dp,
                end = if (label.isEmpty()) 10.dp else 14.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showCheck) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        } else if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = contentDescription, tint = content, modifier = Modifier.size(18.dp))
        }
        if (label.isNotEmpty()) {
            Text(text = label, style = RedesignType.Label, color = content, maxLines = 1)
        }
    }
}

/** Where a button sits in a connected group; decides which corners are outer (round) and inner. */
enum class GroupPosition { Single, First, Middle, Last }

/** Corner shape for [position] in a connected group of [height]: outer = height / 2, inner [inner]. */
fun groupShape(position: GroupPosition, height: Dp, inner: Dp = 6.dp): Shape {
    val outer = height / 2
    return when (position) {
        GroupPosition.Single -> RoundedCornerShape(outer)
        GroupPosition.First -> RoundedCornerShape(topStart = outer, bottomStart = outer, topEnd = inner, bottomEnd = inner)
        GroupPosition.Middle -> RoundedCornerShape(inner)
        GroupPosition.Last -> RoundedCornerShape(topStart = inner, bottomStart = inner, topEnd = outer, bottomEnd = outer)
    }
}

/**
 * One button of an M3 Expressive connected button group (view toggle, queue actions, search
 * filters). Selected = primary / onPrimary and fully round (its inner corners morph out to the
 * outer radius, as in the mockups); unselected = surfaceContainerHigh / onSurfaceVariant.
 */
@Composable
fun RedesignGroupButton(
    position: GroupPosition,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    inner: Dp = 6.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp),
    selectedContainer: Color = MaterialTheme.colorScheme.primary,
    selectedContent: Color = MaterialTheme.colorScheme.onPrimary,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    role: Role = Role.Button,
    content: @Composable RowScope.(Color) -> Unit,
) {
    val outer = height / 2
    val innerCorner by animateDpAsState(if (selected) outer else inner, label = "groupInner")
    val start = if (position == GroupPosition.First || position == GroupPosition.Single) outer else innerCorner
    val end = if (position == GroupPosition.Last || position == GroupPosition.Single) outer else innerCorner
    val bg by animateColorAsState(if (selected) selectedContainer else container, label = "groupBg")
    val fg = if (selected) selectedContent else contentColor
    val shape = RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end)
    Row(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(bg)
            .clickable(role = role, onClick = onClick)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        content(fg)
    }
}

/**
 * Grouped list row shape: first 20/20/4/4, middle 4, last 4/4/20/20, alone 20. The default sizes
 * return shared instances, so a list row asking for its shape on every recomposition allocates
 * nothing.
 */
fun groupedRowShape(index: Int, count: Int, outer: Dp = Redesign.RowOuterCorner, inner: Dp = Redesign.RowInnerCorner): Shape {
    if (outer == Redesign.RowOuterCorner && inner == Redesign.RowInnerCorner) {
        return when {
            count <= 1 -> GroupedSingle
            index == 0 -> GroupedFirst
            index == count - 1 -> GroupedLast
            else -> GroupedMiddle
        }
    }
    return when {
        count <= 1 -> RoundedCornerShape(outer)
        index == 0 -> RoundedCornerShape(topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner)
        index == count - 1 -> RoundedCornerShape(topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer)
        else -> RoundedCornerShape(inner)
    }
}

private val GroupedSingle = RoundedCornerShape(Redesign.RowOuterCorner)
private val GroupedFirst = RoundedCornerShape(
    topStart = Redesign.RowOuterCorner, topEnd = Redesign.RowOuterCorner,
    bottomStart = Redesign.RowInnerCorner, bottomEnd = Redesign.RowInnerCorner
)
private val GroupedLast = RoundedCornerShape(
    topStart = Redesign.RowInnerCorner, topEnd = Redesign.RowInnerCorner,
    bottomStart = Redesign.RowOuterCorner, bottomEnd = Redesign.RowOuterCorner
)
private val GroupedMiddle = RoundedCornerShape(Redesign.RowInnerCorner)

/** The expressive shapes the mockups put behind icons, in the order the Settings frame uses them. */
enum class BadgeShape { Cookie9, Sunny, Flower, Clover4, SoftBurst, Cookie6, Circle }

@Composable
fun BadgeShape.toComposeShape(): Shape = when (this) {
    BadgeShape.Cookie9 -> MaterialShapes.Cookie9Sided.toShape()
    BadgeShape.Sunny -> MaterialShapes.Sunny.toShape()
    BadgeShape.Flower -> MaterialShapes.Flower.toShape()
    BadgeShape.Clover4 -> MaterialShapes.Clover4Leaf.toShape()
    BadgeShape.SoftBurst -> MaterialShapes.SoftBurst.toShape()
    BadgeShape.Cookie6 -> MaterialShapes.Cookie6Sided.toShape()
    BadgeShape.Circle -> MaterialShapes.Circle.toShape()
}

/** Icon on an expressive shape (settings rows 40/20, Spotify card 52/26, Taizo avatar). */
@Composable
fun ShapeBadge(
    icon: Painter,
    shape: BadgeShape,
    containerColor: Color,
    iconTint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape.toComposeShape())
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(iconSize))
    }
}

/** The tonal pairs the mockups cycle through for badges (primary, tertiary, secondary, neutral). */
@Immutable
data class BadgeTone(val container: Color, val content: Color)

@Composable
fun badgeTones(): List<BadgeTone> {
    val s = MaterialTheme.colorScheme
    return listOf(
        BadgeTone(s.primaryContainer, s.onPrimaryContainer),
        BadgeTone(s.tertiaryContainer, s.onTertiaryContainer),
        BadgeTone(s.secondaryContainer, s.onSecondaryContainer),
        BadgeTone(s.surfaceContainerHighest, s.onSurfaceVariant),
    )
}

/** Section label above a group of rows: 14/20 sp medium, primary, 16 dp in, 6 dp under. */
@Composable
fun RedesignSectionLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text = text,
        style = RedesignType.Label.copy(letterSpacing = 0.1.sp),
        color = color,
        modifier = modifier
            .padding(start = 16.dp, bottom = 6.dp)
            .semantics { heading() },
    )
}

/**
 * A grouped row (Settings, Queue autoplay): 52 dp min height, surfaceContainer, badge 40 dp, 14 dp
 * gap, title 16/22 medium, optional subtitle 13/18 and trailing value 14/20 onSurfaceVariant.
 */
@Composable
fun RedesignGroupedRow(
    title: String,
    shape: Shape,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    minHeight: Dp = 52.dp,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(shape)
            .background(containerColor)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(start = 12.dp, end = if (trailing != null) 12.dp else 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = RedesignType.RowTitle, color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = RedesignType.RowSubtitle, color = scheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!value.isNullOrEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(value, style = RedesignType.Label.copy(fontWeight = FontWeight.Normal), color = scheme.onSurfaceVariant, maxLines = 1)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}
