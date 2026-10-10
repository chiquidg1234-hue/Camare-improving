package com.lumacam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lumacam.core.prompter.Teleprompter
import com.lumacam.settings.AppSettings

/**
 * Teleprompter del modo PRESENTAR, arriba del visor (cerca de la cámara frontal, para que al leer
 * parezca que miras a quien te ve). El texto sube solo a la velocidad elegida.
 */
@Composable
fun PrompterOverlay(
    prompter: PrompterUi,
    settings: AppSettings,
    onToggle: () -> Unit,
    onRestart: () -> Unit,
    onFinished: () -> Unit,
    onWpm: (Int) -> Unit,
    onTextSize: (Int) -> Unit,
    onMirror: (Boolean) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = prompter.script?.text.orEmpty()
    val words = remember(text) { Teleprompter.wordCount(text) }
    val wpm = settings.prompterWpm
    val textSp = settings.prompterTextSp
    val scroll = rememberScrollState()
    val finished by rememberUpdatedState(onFinished)

    LaunchedEffect(prompter.resetToken, text) { scroll.scrollTo(0) }
    LaunchedEffect(prompter.running, wpm, words) {
        if (!prompter.running) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1_000_000_000f
            last = now
            val speed = Teleprompter.scrollSpeed(scroll.maxValue.toFloat(), words, wpm)
            scroll.dispatchRawDelta(speed * dt)
            if (scroll.maxValue > 0 && scroll.value >= scroll.maxValue) {
                finished()
                break
            }
        }
    }

    Column(modifier.fillMaxWidth().fillMaxHeight(0.5f)) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xB8000000)),
        ) {
            val panel = maxHeight
            val guide = panel * 0.22f
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .pointerInput(Unit) { detectTapGestures { onToggle() } },
            ) {
                Spacer(Modifier.height(guide))
                Text(
                    text.ifBlank { "Toca el lápiz para escribir o pegar tu discurso." },
                    color = Color.White,
                    fontSize = textSp.sp,
                    lineHeight = (textSp * 1.35f).sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 26.dp, end = 18.dp)
                        .graphicsLayer { scaleX = if (settings.prompterMirror) -1f else 1f },
                )
                Spacer(Modifier.height(panel - guide))
            }
            // Línea de lectura: la frase que toca leer queda a esta altura.
            val lineCenter = guide + (textSp * 0.68f).dp
            Box(
                Modifier
                    .offset(y = lineCenter)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Amber.copy(alpha = 0.35f)),
            )
            Text("▶", color = Amber, fontSize = 14.sp, modifier = Modifier.offset(x = 6.dp, y = lineCenter - 10.dp))
            // Degradado arriba para que el texto ya leído se desvanezca.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(guide * 0.8f)
                    .background(Brush.verticalGradient(listOf(Color(0xE6000000), Color.Transparent))),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xD9000000))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PromptButton(if (prompter.running) "❚❚" else "▶", onClick = onToggle)
            PromptButton("⟲", onClick = onRestart)
            PromptButton("−") { onWpm(wpm - 10) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$wpm ppm", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("≈ ${Teleprompter.formatDuration(Teleprompter.durationSeconds(words, wpm))}", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
            }
            PromptButton("+") { onWpm(wpm + 10) }
            PromptButton("A−") { onTextSize(textSp - 2) }
            PromptButton("A+") { onTextSize(textSp + 2) }
            PromptButton("⇋", selected = settings.prompterMirror) { onMirror(!settings.prompterMirror) }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "Editar guion", tint = Color.White)
            }
        }
    }
}

@Composable
private fun PromptButton(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 36.dp, height = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Amber else Color(0x26FFFFFF))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** Editor de guiones: escribir o pegar el discurso y elegir entre los guardados. */
@Composable
fun ScriptEditorDialog(
    prompter: PrompterUi,
    wpm: Int,
    onSave: (Long?, String) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var editingId by rememberSaveable { mutableStateOf(prompter.script?.id) }
    var text by rememberSaveable { mutableStateOf(prompter.script?.text.orEmpty()) }
    val words = remember(text) { Teleprompter.wordCount(text) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF15181C),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Guion", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                    Button(onClick = {
                        onSave(editingId, text)
                        onDismiss()
                    }, enabled = text.isNotBlank()) { Text("Usar") }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    items(prompter.scripts, key = { it.id }) { sc ->
                        ToggleChip(sc.title, sc.id == editingId) {
                            editingId = sc.id
                            text = sc.text
                        }
                    }
                    item {
                        ToggleChip("+ Nuevo", editingId == null) {
                            editingId = null
                            text = ""
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Escribe o pega aquí tu discurso…") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text(
                        "$words palabras · ≈ ${Teleprompter.formatDuration(Teleprompter.durationSeconds(words, wpm))} a $wpm ppm",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        clipboard.getText()?.text?.takeIf { it.isNotBlank() }?.let { pasted ->
                            text = if (text.isBlank()) pasted else text + "\n\n" + pasted
                        }
                    }) { Text("Pegar") }
                    val id = editingId
                    if (id != null) {
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = {
                            onDelete(id)
                            onDismiss()
                        }) { Text("Borrar", color = Color(0xFFFF8A80)) }
                    }
                }
            }
        }
    }
}
