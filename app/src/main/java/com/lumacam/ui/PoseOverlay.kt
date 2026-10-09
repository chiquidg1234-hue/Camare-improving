package com.lumacam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumacam.core.pose.Joint
import com.lumacam.core.pose.PoseTemplate
import com.lumacam.core.pose.Pt
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private val GoodGreen = Color(0xFF4CE07A)

/** Capa del modo poses dentro del visor. */
@Composable
fun PoseOverlay(
    pose: PoseUi,
    autoShot: Boolean,
    detectionWanted: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToggleDetection: () -> Unit,
    onToggleAutoShot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val template = pose.current?.let { if (pose.mirrored) it.mirrored() else it }
    val good = (pose.score ?: 0f) >= 0.8f
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            template?.let { drawSkeleton(it.points, Color.White.copy(alpha = 0.45f), 9.dp.toPx(), head = true) }
            if (pose.detected.isNotEmpty()) {
                drawSkeleton(pose.detected, if (good) GoodGreen else Amber, 3.5.dp.toPx(), head = false, dots = true)
            }
            val ang = pose.angle
            val roll = ang?.rollDeg
            if (ang != null && roll != null) drawLevel(roll, ang.isLevel)
        }

        // Ángulo actual, arriba a la derecha.
        val angle = pose.angle
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Scrim)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (angle != null) {
                Text(pose.category.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("${angle.elevationDeg.roundToInt()}° · ${if (angle.isLevel) "recto" else "inclinado"}", color = if (angle.isLevel) GoodGreen else Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
            } else {
                Text(if (pose.sensorAvailable) "Midiendo ángulo…" else "Sin sensor de ángulo", color = Color.White, fontSize = 12.sp)
            }
        }

        pose.countdown?.let { s ->
            Text(
                "$s",
                color = Color.White,
                fontSize = 96.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Tarjeta con la pose sugerida, abajo.
        PoseCard(
            pose = pose,
            template = pose.current,
            autoShot = autoShot,
            detectionWanted = detectionWanted,
            onPrev = onPrev,
            onNext = onNext,
            onToggleDetection = onToggleDetection,
            onToggleAutoShot = onToggleAutoShot,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun PoseCard(
    pose: PoseUi,
    template: PoseTemplate?,
    autoShot: Boolean,
    detectionWanted: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToggleDetection: () -> Unit,
    onToggleAutoShot: () -> Unit,
    modifier: Modifier,
) {
    // Los consejos van rotando cada 4 s.
    var tip by remember(template?.id) { mutableIntStateOf(0) }
    LaunchedEffect(template?.id) {
        while (true) {
            delay(4000)
            val n = template?.tips?.size ?: 1
            tip = (tip + 1) % n
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xC0101215))
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Pose anterior", tint = Color.White)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    template?.name ?: "Sin poses para este ángulo",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "${pose.index + 1} de ${pose.poses.size} · ${pose.category.label}",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Pose siguiente", tint = Color.White)
            }
        }
        val tips = template?.tips.orEmpty()
        tips.getOrNull(tip % maxOf(1, tips.size))?.let {
            Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Text(
            pose.category.effect,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        if (pose.detectionActive) {
            Spacer(Modifier.height(6.dp))
            val score = pose.score
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (score != null) "Coincidencia ${(score * 100).roundToInt()}%" else "Coincidencia —",
                    color = if ((score ?: 0f) >= 0.8f) GoodGreen else Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.width(130.dp),
                )
                LinearProgressIndicator(
                    progress = { (score ?: 0f).coerceIn(0f, 1f) },
                    color = if ((score ?: 0f) >= 0.8f) GoodGreen else Amber,
                    modifier = Modifier.weight(1f),
                )
            }
            pose.hint?.let {
                Text(it, color = Amber, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip("Detectar mi pose", detectionWanted, onToggleDetection)
            if (detectionWanted) ToggleChip("Disparo automático", autoShot, onToggleAutoShot)
        }
    }
}

private fun DrawScope.toOffset(p: Pt) = Offset(p.x * size.width, p.y * size.height)

private fun DrawScope.drawSkeleton(points: Map<Joint, Pt>, color: Color, width: Float, head: Boolean, dots: Boolean = false) {
    for ((a, b) in PoseTemplate.BONES) {
        val pa = points[a] ?: continue
        val pb = points[b] ?: continue
        drawLine(color, toOffset(pa), toOffset(pb), strokeWidth = width, cap = StrokeCap.Round)
    }
    val sl = points[Joint.SHOULDER_L]
    val sr = points[Joint.SHOULDER_R]
    val nose = points[Joint.NOSE]
    if (nose != null && sl != null && sr != null) {
        val neck = Offset((sl.x + sr.x) / 2 * size.width, (sl.y + sr.y) / 2 * size.height)
        drawLine(color, neck, toOffset(nose), strokeWidth = width, cap = StrokeCap.Round)
        if (head) {
            val shoulderW = hypot((sr.x - sl.x) * size.width, (sr.y - sl.y) * size.height)
            drawCircle(color, radius = shoulderW * 0.42f, center = toOffset(nose), style = Stroke(width))
        }
    }
    if (dots) for (p in points.values) drawCircle(color, radius = width * 1.4f, center = toOffset(p))
}

/** Línea de nivel en el centro: verde cuando el teléfono está recto. */
private fun DrawScope.drawLevel(rollDeg: Float, level: Boolean) {
    val c = Offset(size.width / 2, size.height / 2)
    val half = size.width * 0.18f
    val color = if (level) GoodGreen else Color.White.copy(alpha = 0.8f)
    rotate(-rollDeg, c) {
        drawLine(color, Offset(c.x - half, c.y), Offset(c.x - 12f, c.y), strokeWidth = 3f)
        drawLine(color, Offset(c.x + 12f, c.y), Offset(c.x + half, c.y), strokeWidth = 3f)
    }
    if (abs(rollDeg) > 0.2f) {
        // Referencia fija (horizonte real) más tenue.
        drawLine(Color.White.copy(alpha = 0.3f), Offset(c.x - half * 0.5f, c.y), Offset(c.x + half * 0.5f, c.y), strokeWidth = 1.5f)
    }
}
