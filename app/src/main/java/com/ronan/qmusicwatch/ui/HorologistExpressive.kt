/*
 * Adapted from Google Horologist media-ui-material3.
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified for a full-Android, square-watch client by Ronan, 2026.
 */
package com.ronan.qmusicwatch.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.TransformResult
import androidx.graphics.shapes.pillStar
import androidx.graphics.shapes.toPath
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun ExpressivePlayerControls(
    playing: Boolean,
    progress: Float,
    accent: Color,
    animateShape: Boolean = true,
    modifier: Modifier = Modifier,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    val previousInteraction = remember { MutableInteractionSource() }
    val playInteraction = remember { MutableInteractionSource() }
    val nextInteraction = remember { MutableInteractionSource() }
    val previousPressed by previousInteraction.collectIsPressedAsState()
    val playPressed by playInteraction.collectIsPressedAsState()
    val nextPressed by nextInteraction.collectIsPressedAsState()
    val sideBase = 42.dp
    val centerBase = 58.dp
    val previousSize by animateDpAsState(
        if (previousPressed) 46.dp else if (playPressed) 40.dp else sideBase,
        tween(180, easing = FastOutSlowInEasing),
        label = "previousButtonSize",
    )
    val centerSize by animateDpAsState(
        if (playPressed) 62.dp else if (previousPressed || nextPressed) 56.dp else centerBase,
        tween(180, easing = FastOutSlowInEasing),
        label = "playButtonSize",
    )
    val nextSize by animateDpAsState(
        if (nextPressed) 46.dp else if (playPressed) 40.dp else sideBase,
        tween(180, easing = FastOutSlowInEasing),
        label = "nextButtonSize",
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExpressiveMediaButton(
            icon = Icons.Default.SkipPrevious,
            contentDescription = "上一首",
            size = previousSize,
            interactionSource = previousInteraction,
            onClick = onPrevious,
        )
        ExpressivePlayPauseButton(
            playing = playing,
            progress = progress,
            accent = accent,
            animateShape = animateShape,
            size = centerSize,
            interactionSource = playInteraction,
            onClick = onPlayPause,
        )
        ExpressiveMediaButton(
            icon = Icons.Default.SkipNext,
            contentDescription = "下一首",
            size = nextSize,
            interactionSource = nextInteraction,
            onClick = onNext,
        )
    }
}

@Composable
private fun ExpressiveMediaButton(
    icon: ImageVector,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(23.dp), tint = WatchTextPrimary)
    }
}

@Composable
private fun ExpressivePlayPauseButton(
    playing: Boolean,
    progress: Float,
    accent: Color,
    animateShape: Boolean,
    size: androidx.compose.ui.unit.Dp,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val morphProgress = animateFloatAsState(
        targetValue = if (pressed) 0f else 1f,
        animationSpec = tween(if (pressed) 100 else 320, easing = FastOutSlowInEasing),
        label = "scallopMorph",
    )
    val rotation = rememberInfiniteTransition(label = "scallopRotation").animateFloat(
        initialValue = 0f,
        targetValue = if (playing && animateShape) -.1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(3_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scallopRotationProgress",
    )
    val shape = remember(playing) { ExpressiveScallopShape(playing, morphProgress, rotation) }
    val haptics = LocalHapticFeedback.current
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        ExpressiveWavyProgress(
            progress = progress,
            playing = playing,
            morphProgress = morphProgress,
            rotationProgress = rotation,
            modifier = Modifier.fillMaxSize(),
            indicatorColor = WatchTextPrimary,
            trackColor = accent.copy(alpha = .2f),
        )
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = .86f; scaleY = .86f }
                .clip(shape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            shape = shape,
            color = accent,
            contentColor = Color(0xFF24143C),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (playing) "暂停" else "播放",
                    Modifier.size(27.dp),
                )
            }
        }
    }
}

private class ExpressiveScallopShape(
    private val playing: Boolean,
    private val morphProgress: State<Float>,
    private val rotationProgress: State<Float>,
) : Shape {
    private val matrix = Matrix()
    private val androidPath = android.graphics.Path()
    private var cachedSide = -1f
    private var cachedDensity = -1f
    private var cachedMorph: Morph? = null

    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val side = min(size.width, size.height)
        val morph = if (cachedMorph == null || cachedSide != side || cachedDensity != density.density) {
            val circle = pillPolygon(side, side)
            val cornerRadius = with(density) { 8.dp.toPx() }.coerceAtMost(side / 6f)
            val scallop = RoundedPolygon.pillStar(
                numVerticesPerRadius = 10,
                width = side / 2f,
                height = side / 2f,
                innerRadiusRatio = .82f,
                rounding = CornerRounding(cornerRadius),
            ).scaleToHeight(side)
            Morph(circle, if (playing) scallop else circle).also {
                cachedSide = side
                cachedDensity = density.density
                cachedMorph = it
            }
        } else cachedMorph!!
        matrix.reset()
        androidPath.reset()
        matrix.rotateZ(rotationProgress.value * 360f)
        val path = morph.toPath(morphProgress.value, androidPath).asComposePath().apply {
            transform(matrix)
            translate(androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f))
        }
        return Outline.Generic(path)
    }
}

@Composable
private fun ExpressiveWavyProgress(
    progress: Float,
    playing: Boolean,
    morphProgress: State<Float>,
    rotationProgress: State<Float>,
    indicatorColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    val indicatorPath = remember { Path() }
    val trackPath = remember { Path() }
    val wrappedIndicatorPath = remember { Path() }
    val wrappedTrackPath = remember { Path() }
    val morphCache = remember { mutableMapOf<Pair<Int, Boolean>, Morph>() }
    Spacer(
        modifier.drawWithCache {
            val strokePx = 3.dp.toPx()
            val side = min(size.width, size.height) - strokePx
            val morph = morphCache.getOrPut(side.roundToInt() to playing) {
                val circle = pillPolygon(side, side)
                val scallop = RoundedPolygon.pillStar(
                    numVerticesPerRadius = 10,
                    width = side / 2f,
                    height = side / 2f,
                    innerRadiusRatio = .8f,
                    rounding = CornerRounding(12.dp.toPx().coerceAtMost(side / 6f)),
                ).scaleToHeight(side)
                Morph(circle, if (playing) scallop else circle)
            }
            val path = morph
                .toPath(morphProgress.value)
                .asComposePath()
                .apply { translate(Offset(size.width / 2f, size.height / 2f)) }
            val measure = PathMeasure().also { it.setPath(path, true) }
            val length = measure.length
            val value = progress.coerceIn(0f, 1f)
            val rotation = rotationProgress.value
            val gap = length * .006f + strokePx

            indicatorPath.reset()
            measure.getSegment(
                length * abs(rotation) + gap / 2f,
                max(length * (value - rotation) - gap / 2f, length * abs(rotation) + gap / 2f + .1f),
                indicatorPath,
            )
            trackPath.reset()
            measure.getSegment(
                max(length * (value - rotation) + gap / 2f, length * abs(rotation) + gap * 1.5f),
                min(length * (1f - rotation) - gap / 2f, length),
                trackPath,
            )
            wrappedIndicatorPath.reset()
            measure.getSegment(0f, length * (value - rotation - 1f) - gap / 2f, wrappedIndicatorPath)
            wrappedTrackPath.reset()
            measure.getSegment(0f, length * abs(rotation) - gap / 2f, wrappedTrackPath)

            onDrawBehind {
                rotate(360f * rotation) {
                    val stroke = Stroke(strokePx, cap = StrokeCap.Round)
                    drawPath(wrappedTrackPath, trackColor, style = stroke)
                    drawPath(trackPath, trackColor, style = stroke)
                    drawPath(indicatorPath, indicatorColor, style = stroke)
                    drawPath(wrappedIndicatorPath, indicatorColor, style = stroke)
                }
            }
        },
    )
}

private fun pillPolygon(width: Float, height: Float) = RoundedPolygon(
    vertices = floatArrayOf(
        0f, -height / 2f,
        width / 2f, -height / 2f,
        width / 2f, 0f,
        width / 2f, height / 2f,
        0f, height / 2f,
        -width / 2f, height / 2f,
        -width / 2f, 0f,
        -width / 2f, -height / 2f,
    ),
    rounding = CornerRounding(min(width / 2f, height / 2f)),
    centerX = 0f,
    centerY = 0f,
)

private fun RoundedPolygon.scaleToHeight(sizePx: Float): RoundedPolygon {
    val bounds = calculateMaxBounds()
    val scale = sizePx / (bounds[3] - bounds[1])
    return transformed { x, y -> TransformResult(x * scale, y * scale) }
}
