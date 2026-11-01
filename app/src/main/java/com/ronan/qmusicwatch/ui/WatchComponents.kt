package com.ronan.qmusicwatch.ui

import androidx.compose.foundation.BasicTooltipBox
import androidx.compose.foundation.BasicTooltipState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.ronan.qmusicwatch.input.RotaryInputKind

internal fun watchLongClickAction(
    action: (() -> Unit)?,
    hapticFeedback: () -> Unit,
): (() -> Unit)? = action?.let { onLongClick ->
    {
        hapticFeedback()
        onLongClick()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WatchIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = WatchTextPrimary,
    containerColor: Color = WatchSurfaceRaised,
    onLongClick: (() -> Unit)? = null,
    buttonSize: Dp? = null,
    onClick: () -> Unit,
) {
    val dimensions = LocalWatchDimensions.current
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .size(buttonSize ?: dimensions.touchTarget)
            .clip(CircleShape)
            .background(containerColor, CircleShape)
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = watchLongClickAction(onLongClick) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(dimensions.iconSize), tint = tint)
    }
}

@Composable
fun WatchSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    heightOverride: Dp? = null,
    onSearch: () -> Unit = {},
) {
    val dimensions = LocalWatchDimensions.current
    Surface(
        modifier = modifier.height(heightOverride ?: dimensions.searchHeight),
        shape = RoundedCornerShape(dimensions.searchCornerRadius),
        color = WatchSurface,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxSize(),
            singleLine = true,
            textStyle = TextStyle(
                color = WatchTextPrimary,
                fontSize = dimensions.bodySp.sp,
            ),
            cursorBrush = SolidColor(WatchAccent),
            keyboardOptions = keyboardOptions,
            keyboardActions = KeyboardActions(onDone = { onSearch() }),
            visualTransformation = VisualTransformation.None,
            decorationBox = { innerField ->
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    leadingIcon?.let {
                        Icon(it, null, Modifier.size(dimensions.iconSize), tint = WatchTextSecondary)
                        Spacer(Modifier.width(6.dp))
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isBlank()) Text(
                            placeholder,
                            color = WatchTextSecondary,
                            fontSize = dimensions.bodySp.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        innerField()
                    }
                    trailingIcon?.let {
                        WatchIconButton(
                            it,
                            "搜索",
                            containerColor = Color.Transparent,
                            onClick = onSearch,
                        )
                    }
                }
            },
        )
    }
}

@Composable
fun WatchSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    val dimensions = LocalWatchDimensions.current
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = 34.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            Modifier.weight(1f),
            color = WatchTextPrimary,
            fontSize = dimensions.titleSp.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        action?.let {
            Surface(
                onClick = onAction,
                shape = RoundedCornerShape(50),
                color = WatchSurfaceRaised,
            ) {
                Text(
                    it,
                    Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    color = WatchTextPrimary,
                    fontSize = (dimensions.secondarySp + 1f).sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun WatchListRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    leading: (@Composable BoxScope.() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val dimensions = LocalWatchDimensions.current
    val rowModifier = modifier
        .fillMaxWidth()
        .height(dimensions.trackRowHeight)
        .clip(RoundedCornerShape(dimensions.rowCornerRadius))
        .background(WatchSurface, RoundedCornerShape(dimensions.rowCornerRadius))
        .then(if (onClick != null) Modifier.combinedClickable(onClick = onClick) else Modifier)
        .padding(horizontal = 8.dp)
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
        leading?.let {
            Box(Modifier.size(dimensions.artworkSize), content = it)
            Spacer(Modifier.width(7.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                title,
                color = WatchTextPrimary,
                fontSize = dimensions.bodySp.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) Text(
                subtitle,
                color = WatchTextSecondary,
                fontSize = dimensions.secondarySp.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

@Composable
fun WatchPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val dimensions = LocalWatchDimensions.current
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(dimensions.touchTarget),
        shape = RoundedCornerShape(50),
        color = if (outlined) WatchSurface else WatchAccent,
        contentColor = if (outlined) WatchTextPrimary else Color(0xFF06131B),
        border = if (outlined) androidx.compose.foundation.BorderStroke(1.dp, WatchDivider) else null,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, fontSize = dimensions.bodySp.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun WatchDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val dimensions = LocalWatchDimensions.current
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        WatchRotaryDialogContent {
            BoxWithConstraints(
                Modifier.fillMaxSize().padding(dimensions.screenPadding),
                contentAlignment = Alignment.Center,
            ) {
                val horizontalInset = if (dimensions.isRound) maxWidth * .08f else 0.dp
                val verticalInset = if (dimensions.isRound) maxHeight * .08f else 0.dp
                Surface(
                    modifier = modifier.fillMaxWidth()
                        .padding(horizontal = horizontalInset, vertical = verticalInset)
                        .heightIn(max = maxHeight * if (dimensions.isRound) .78f else .82f)
                        .navigationBarsPadding().imePadding(),
                    shape = RoundedCornerShape(if (dimensions.isRound) 20.dp else 18.dp),
                    color = WatchSurfaceRaised,
                    tonalElevation = 0.dp,
                ) {
                    Column(
                        Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        icon?.invoke()
                        title?.let { Box(Modifier.fillMaxWidth()) { it() } }
                        text?.let { body ->
                            val scrollState = rememberScrollState()
                            val scroll = rememberWatchRotaryScroll(scrollState)
                            val reversed = LocalWatchRotaryReversed.current
                            val volumeKeysScroll = LocalVolumeKeysScroll.current
                            WatchRotaryHandler { event ->
                                if (event.kind == RotaryInputKind.VolumeKey && !volumeKeysScroll) false
                                else { scroll(event.pixels * if (reversed) -1f else 1f); true }
                            }
                            Box(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(scrollState)) { body() }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            dismissButton?.invoke()
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WatchSelectionDialog(
    title: String,
    onDismissRequest: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val dimensions = LocalWatchDimensions.current
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        WatchRotaryDialogContent {
            BoxWithConstraints(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = .97f)),
            ) {
                val horizontalInset = if (dimensions.isRound) maxWidth * .12f else dimensions.screenPadding
                val topInset = if (dimensions.isRound) maxHeight * .12f else 6.dp
                val bottomInset = if (dimensions.isRound) maxHeight * .18f else 8.dp
                val headerWidth = if (dimensions.isRound) roundWatchContentWidth(
                    WatchScreenBounds(maxWidth, maxHeight), topInset, topInset + dimensions.touchTarget,
                ) else maxWidth - horizontalInset * 2
                Column(
                    Modifier.fillMaxSize().padding(start = horizontalInset, end = horizontalInset,
                        top = topInset, bottom = bottomInset).imePadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(Modifier.width(headerWidth).height(dimensions.touchTarget), verticalAlignment = Alignment.CenterVertically) {
                        WatchIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            containerColor = WatchSurface.copy(alpha = .82f),
                            onClick = onDismissRequest,
                        )
                        Text(title, Modifier.weight(1f).padding(start = 4.dp), color = WatchTextPrimary,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                        // On the smallest watches, a fixed action must not leave
                        // less than one full row for selecting songs.
                        val scrollFooter = footer != null && maxHeight < dimensions.touchTarget + 52.dp
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            WatchLazyColumn(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                contentPadding = PaddingValues(vertical = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                content()
                                if (scrollFooter) item { footer?.invoke() }
                            }
                            if (!scrollFooter) footer?.invoke()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WatchSelectionRow(
    title: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(40.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) WatchTextPrimary.copy(alpha = .13f) else WatchSurface,
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = WatchTextPrimary.copy(alpha = if (enabled) 1f else .38f),
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected || !enabled) {
                Icon(
                    imageVector = if (selected) Icons.Default.Check else Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = WatchTextPrimary.copy(alpha = if (enabled) 1f else .38f),
                )
            }
        }
    }
}
