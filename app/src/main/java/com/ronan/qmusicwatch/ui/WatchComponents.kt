package com.ronan.qmusicwatch.ui

import androidx.compose.foundation.BasicTooltipBox
import androidx.compose.foundation.BasicTooltipState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WatchIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = WatchTextPrimary,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val dimensions = LocalWatchDimensions.current
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .size(dimensions.touchTarget)
            .clip(RoundedCornerShape(dimensions.cornerRadius))
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick?.invoke()
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
    onSearch: () -> Unit = {},
) {
    val dimensions = LocalWatchDimensions.current
    Surface(
        modifier = modifier.height(dimensions.searchHeight),
        shape = RoundedCornerShape(dimensions.cornerRadius),
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
                        WatchIconButton(it, "搜索", onClick = onSearch)
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
        modifier.fillMaxWidth().height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f),
            color = WatchTextPrimary,
            fontSize = dimensions.titleSp.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        action?.let {
            Surface(
                onClick = onAction,
                shape = RoundedCornerShape(9.dp),
                color = Color.Transparent,
            ) {
                Text(
                    it,
                    Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                    color = WatchAccent,
                    fontSize = (dimensions.secondarySp + 1f).sp,
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
        .clip(RoundedCornerShape(dimensions.cornerRadius))
        .then(if (onClick != null) Modifier.combinedClickable(onClick = onClick) else Modifier)
        .padding(horizontal = 7.dp)
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
    onClick: () -> Unit,
) {
    val dimensions = LocalWatchDimensions.current
    Surface(
        onClick = onClick,
        modifier = modifier.height(dimensions.touchTarget),
        shape = RoundedCornerShape(dimensions.cornerRadius),
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
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(dimensions.screenPadding),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = modifier.fillMaxWidth().heightIn(max = maxHeight * .78f)
                    .navigationBarsPadding().imePadding(),
                shape = RoundedCornerShape(dimensions.cornerRadius + 2.dp),
                color = WatchSurfaceRaised,
                tonalElevation = 0.dp,
            ) {
                Column(
                    Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    icon?.invoke()
                    title?.let { Box(Modifier.fillMaxWidth()) { it() } }
                    text?.let { Box(Modifier.fillMaxWidth().weight(1f, fill = false)) { it() } }
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
