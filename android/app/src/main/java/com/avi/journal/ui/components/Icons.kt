package com.avi.journal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app draws its own icons, matching WeightTracker's reasoning: a handful are
 * needed, and a dependency that exists only to supply a chevron is a dependency
 * that can break a build for no good reason.
 *
 * All of them are stroked with round caps and joins at roughly a tenth of their
 * box, which is what makes a set drawn by hand still look like a set.
 */

/**
 * Attaches a content description for screen readers.
 *
 * The parameter is deliberately NOT called `contentDescription`: inside a
 * `semantics { }` block the receiver's own property of that name shadows the
 * parameter, and its getter throws by design. Naming it `description` keeps the
 * right-hand side unambiguous. (Same trap WeightTracker documents.)
 */
private fun Modifier.describedAs(description: String): Modifier =
    if (description.isEmpty()) this else this.semantics { contentDescription = description }

private fun DrawScope.strokePath(
    tint: Color,
    width: Float,
    build: Path.() -> Unit,
) {
    drawPath(
        path = Path().apply(build),
        color = tint,
        style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

@Composable
private fun IconCanvas(
    size: Dp,
    contentDescription: String,
    modifier: Modifier = Modifier,
    draw: DrawScope.(Float, Float, Float) -> Unit,
) {
    Canvas(modifier = modifier.size(size).describedAs(contentDescription)) {
        val w = this.size.width
        val h = this.size.height
        draw(w, h, w * 0.105f)
    }
}

@Composable
fun ChevronIcon(
    pointingUp: Boolean,
    tint: Color,
    contentDescription: String = "",
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) = IconCanvas(size, contentDescription, modifier) { w, h, stroke ->
    strokePath(tint, w * 0.14f) {
        if (pointingUp) {
            moveTo(w * 0.24f, h * 0.62f); lineTo(w * 0.50f, h * 0.36f); lineTo(w * 0.76f, h * 0.62f)
        } else {
            moveTo(w * 0.24f, h * 0.38f); lineTo(w * 0.50f, h * 0.64f); lineTo(w * 0.76f, h * 0.38f)
        }
    }
}

@Composable
fun ArrowIcon(
    pointingLeft: Boolean,
    tint: Color,
    contentDescription: String = "",
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) = IconCanvas(size, contentDescription, modifier) { w, h, stroke ->
    strokePath(tint, stroke) {
        if (pointingLeft) {
            moveTo(w * 0.62f, h * 0.24f); lineTo(w * 0.36f, h * 0.50f); lineTo(w * 0.62f, h * 0.76f)
        } else {
            moveTo(w * 0.38f, h * 0.24f); lineTo(w * 0.64f, h * 0.50f); lineTo(w * 0.38f, h * 0.76f)
        }
    }
}

@Composable
fun CloseIcon(
    tint: Color,
    contentDescription: String = "",
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) = IconCanvas(size, contentDescription, modifier) { w, h, stroke ->
    strokePath(tint, stroke) { moveTo(w * 0.26f, h * 0.26f); lineTo(w * 0.74f, h * 0.74f) }
    strokePath(tint, stroke) { moveTo(w * 0.74f, h * 0.26f); lineTo(w * 0.26f, h * 0.74f) }
}

@Composable
fun SearchIcon(
    tint: Color,
    contentDescription: String = "",
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) = IconCanvas(size, contentDescription, modifier) { w, h, stroke ->
    val r = w * 0.26f
    drawCircle(
        color = tint,
        radius = r,
        center = Offset(w * 0.44f, h * 0.44f),
        style = Stroke(width = stroke),
    )
    strokePath(tint, stroke) { moveTo(w * 0.64f, h * 0.64f); lineTo(w * 0.82f, h * 0.82f) }
}

@Composable
fun StarIcon(
    filled: Boolean,
    tint: Color,
    contentDescription: String = "",
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) = IconCanvas(size, contentDescription, modifier) { w, h, stroke ->
    val path = Path().apply {
        // Five-pointed star by explicit points rather than trigonometry at draw
        // time: the shape never changes, so computing it every frame would be
        // work for nothing.
        moveTo(w * 0.50f, h * 0.14f)
        lineTo(w * 0.62f, h * 0.39f)
        lineTo(w * 0.89f, h * 0.43f)
        lineTo(w * 0.69f, h * 0.62f)
        lineTo(w * 0.74f, h * 0.88f)
        lineTo(w * 0.50f, h * 0.76f)
        lineTo(w * 0.26f, h * 0.88f)
        lineTo(w * 0.31f, h * 0.62f)
        lineTo(w * 0.11f, h * 0.43f)
        lineTo(w * 0.38f, h * 0.39f)
        close()
    }
    if (filled) {
        drawPath(path, tint)
    } else {
        drawPath(path, tint, style = Stroke(width = stroke, join = StrokeJoin.Round))
    }
}

@Composable
fun PinIcon(
    tint: Color,
    contentDescription: String = "",
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) = IconCanvas(size, contentDescription, modifier) { w, h, stroke ->
    strokePath(tint, stroke) {
        // Teardrop: down the left flank, around the head, back down the right.
        moveTo(w * 0.50f, h * 0.90f)
        cubicTo(w * 0.20f, h * 0.58f, w * 0.18f, h * 0.36f, w * 0.32f, h * 0.24f)
        cubicTo(w * 0.44f, h * 0.13f, w * 0.56f, h * 0.13f, w * 0.68f, h * 0.24f)
        cubicTo(w * 0.82f, h * 0.36f, w * 0.80f, h * 0.58f, w * 0.50f, h * 0.90f)
    }
    drawCircle(tint, radius = w * 0.10f, center = Offset(w * 0.50f, h * 0.38f))
}

/** Steps: two footfalls, simplified to strides so they read at 16dp. */
@Composable
fun StepsIcon(
    tint: Color,
    contentDescription: String = "",
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) = IconCanvas(size, contentDescription, modifier) { w, h, stroke ->
    strokePath(tint, stroke) {
        moveTo(w * 0.30f, h * 0.78f); lineTo(w * 0.30f, h * 0.44f); lineTo(w * 0.46f, h * 0.28f)
    }
    strokePath(tint, stroke) {
        moveTo(w * 0.70f, h * 0.72f); lineTo(w * 0.70f, h * 0.38f); lineTo(w * 0.54f, h * 0.22f)
    }
}

@Composable
fun MoonIcon(
    tint: Color,
    contentDescription: String = "",
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) = IconCanvas(size, contentDescription, modifier) { w, h, stroke ->
    strokePath(tint, stroke) {
        moveTo(w * 0.82f, h * 0.60f)
        cubicTo(w * 0.72f, h * 0.86f, w * 0.36f, h * 0.90f, w * 0.20f, h * 0.66f)
        cubicTo(w * 0.06f, h * 0.44f, w * 0.20f, h * 0.14f, w * 0.48f, h * 0.10f)
        cubicTo(w * 0.32f, h * 0.34f, w * 0.52f, h * 0.66f, w * 0.82f, h * 0.60f)
    }
}

@Composable
fun RefreshIcon(
    tint: Color,
    contentDescription: String = "",
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    Canvas(modifier = modifier.size(size).describedAs(contentDescription)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.105f
        val inset = stroke * 1.9f
        val cx = w / 2f
        val cy = h / 2f
        val radius = (w - inset * 2f) / 2f

        // Arc and arrowhead derived from the SAME angle, so the head sits
        // exactly where the ring ends rather than floating near it.
        val headAngle = 250f
        val sweep = 290f

        drawArc(
            color = tint,
            startAngle = headAngle - sweep,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(w - inset * 2f, h - inset * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )

        val radians = Math.toRadians(headAngle.toDouble())
        val radialX = kotlin.math.cos(radians).toFloat()
        val radialY = kotlin.math.sin(radians).toFloat()
        val tangentX = -radialY
        val tangentY = radialX
        val endX = cx + radialX * radius
        val endY = cy + radialY * radius
        val headLength = stroke * 3.1f
        val headHalfWidth = stroke * 1.75f

        drawPath(
            path = Path().apply {
                moveTo(endX + tangentX * headLength, endY + tangentY * headLength)
                lineTo(endX + radialX * headHalfWidth, endY + radialY * headHalfWidth)
                lineTo(endX - radialX * headHalfWidth, endY - radialY * headHalfWidth)
                close()
            },
            color = tint,
        )
    }
}
