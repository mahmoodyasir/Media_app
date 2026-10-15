package com.mediaapp.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediaapp.editor.CropSliderRange
import com.mediaapp.editor.FilterPreset
import com.mediaapp.editor.StrokeSliderRange
import com.mediaapp.editor.TextSliderRange
import com.mediaapp.editor.cropSliderValue
import com.mediaapp.editor.image.CollageTemplate
import com.mediaapp.editor.strokeSliderValue
import com.mediaapp.editor.textSizeSliderValue
import com.mediaapp.editor.image.CropAspect
import com.mediaapp.editor.image.ShapeKind

private val inkColors = listOf(
    0xFFFFFFFF.toInt(),
    0xFF16171A.toInt(),
    0xFFD6F25C.toInt(),
    0xFFFF6B6B.toInt(),
    0xFF7EB6FF.toInt(),
    0xFFFFC56E.toInt(),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageEditorRoute(snackbar: SnackbarHostState, onBack: () -> Unit) {
    val vm: ImageEditorViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val base by vm.base.collectAsStateWithLifecycle()
    val collage by vm.collagePreview.collectAsStateWithLifecycle()
    val consent by vm.consent.collectAsStateWithLifecycle()
    val showSave = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    CollectMessages(vm.messages, snackbar)
    ConsentEffect(consent, vm::onConsent)
    DisposableEffect(ui.vault) {
        if (ui.vault) WindowSecurity.acquire()
        onDispose { if (ui.vault) WindowSecurity.release() }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        if (uris.isNotEmpty()) vm.loadCollage(uris)
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            Text("Edit photo", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = vm::undo, enabled = ui.canUndo) { Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo") }
            IconButton(onClick = vm::redo, enabled = ui.canRedo) { Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "Redo") }
            TextButton(onClick = { showSave.value = true }, enabled = ui.ready && !ui.saving) { Text("Save") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            when {
                ui.error != null && !ui.ready -> Text(ui.error ?: "", textAlign = TextAlign.Center)
                !ui.ready || ui.saving -> CircularProgressIndicator()
                ui.collage -> CollagePreview(collage)
                base != null -> PhotoCanvas(base!!, ui, vm)
            }
        }
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImageTool.entries.forEach { tool ->
                FilterChip(selected = ui.tool == tool, onClick = { vm.tool(tool) }, label = { Text(tool.name) })
            }
        }
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            ScrollingOptions {
                when (ui.tool) {
                    ImageTool.Filters -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = ui.document.filter == preset,
                                onClick = { vm.filter(preset) },
                                label = { Text(preset.label) },
                            )
                        }
                    }
                    ImageTool.Crop -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CropAspect.entries.forEach { aspect ->
                                FilterChip(selected = ui.document.aspect == aspect, onClick = { vm.aspect(aspect) }, label = { Text(aspect.label) })
                            }
                        }
                        Row {
                            TextButton(onClick = vm::rotate) { Text("Rotate") }
                            TextButton(onClick = vm::flipHorizontal) { Text("Flip horizontal") }
                            TextButton(onClick = vm::flipVertical) { Text("Flip vertical") }
                        }
                        Text("Drag the photo to move the crop. The slider tightens it.", style = MaterialTheme.typography.bodyMedium)
                    }
                    ImageTool.Text -> {
                        OutlinedTextField(ui.draftText, vm::draft, label = { Text("Caption") }, modifier = Modifier.fillMaxWidth())
                        ColorRow(ui.color, vm::color)
                        TextButton(onClick = vm::addText) { Text("Add text") }
                        TextButton(onClick = vm::recolorText) { Text("Update selected") }
                    }
                    ImageTool.Shapes -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ShapeKind.entries.forEach { kind ->
                                FilterChip(selected = ui.shapeKind == kind, onClick = { vm.shapeKind(kind) }, label = { Text(kind.name) })
                            }
                        }
                        ColorRow(ui.color, vm::color)
                        TextButton(onClick = vm::addShape) { Text("Add shape") }
                    }
                    ImageTool.Collage -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CollageTemplate.entries.forEach { template ->
                                FilterChip(selected = ui.template == template, onClick = { vm.template(template) }, label = { Text(template.label) })
                            }
                        }
                        TextButton(onClick = {
                            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text("Pick photos") }
                        Text("Two, three, or four panels. Empty slots stay dark. The current filter is applied on export.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            when (ui.tool) {
                ImageTool.Crop -> Slider(
                    value = cropSliderValue(ui.document.crop.width),
                    onValueChange = {
                        vm.beginGesture()
                        vm.tightness(it / ui.document.crop.width.coerceAtLeast(CropSliderRange.start))
                    },
                    onValueChangeFinished = { vm.endGesture(render = true) },
                    valueRange = CropSliderRange,
                )
                ImageTool.Text -> {
                    Text("Size", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = textSizeSliderValue(ui.document.texts, ui.selectedText, ui.textSize),
                        onValueChange = vm::resizeText,
                        onValueChangeFinished = { vm.endGesture(render = false) },
                        valueRange = TextSliderRange,
                    )
                }
                ImageTool.Shapes -> {
                    Text("Stroke", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = strokeSliderValue(ui.document.shapes, ui.stroke),
                        onValueChange = vm::stroke,
                        onValueChangeFinished = { vm.endGesture(render = false) },
                        valueRange = StrokeSliderRange,
                    )
                }
                else -> Unit
            }
        }
    }
    if (showSave.value) {
        AlertDialog(
            onDismissRequest = { showSave.value = false },
            title = { Text("Save photo") },
            text = {
                Text(
                    if (ui.vault) {
                        "A copy goes to Pictures/Media App. You can also replace this locked file, or save another copy into Locked."
                    } else {
                        "A new file is written to Pictures/Media App. Replacing the original needs Android's permission. Save to vault encrypts a copy and hides it."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { showSave.value = false; vm.save(SaveTarget.Copy) }) { Text("Save copy") }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = { showSave.value = false; vm.save(SaveTarget.Overwrite) }) {
                        Text(if (ui.vault) "Replace locked original" else "Overwrite original")
                    }
                    TextButton(onClick = { showSave.value = false; vm.save(SaveTarget.Vault) }) { Text("Save to vault") }
                }
            },
        )
    }
}

@Composable
private fun PhotoCanvas(bitmap: Bitmap, ui: ImageEditUi, vm: ImageEditorViewModel) {
    val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(18.dp))
            .pointerInput(ui.tool) {
                if (ui.tool != ImageTool.Crop) return@pointerInput
                detectDragGestures(
                    onDragStart = { vm.beginGesture() },
                    onDrag = { change, drag ->
                        vm.panCrop(drag.x / size.width, drag.y / size.height)
                        change.consume()
                    },
                    onDragEnd = { vm.endGesture(render = true) },
                    onDragCancel = { vm.endGesture(render = true) },
                )
            },
    ) {
        val canvasW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val canvasH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        Image(bitmap.asImageBitmap(), contentDescription = "Edited photo", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        ui.document.shapes.forEach { shape ->
            val color = Color(shape.color)
            Box(
                Modifier
                    .offset(x = maxWidth * shape.nx, y = maxHeight * shape.ny)
                    .size(width = maxWidth * shape.nw.coerceIn(0.08f, 1f), height = maxHeight * shape.nh.coerceIn(0.06f, 1f))
                    .border(2.dp, color, if (shape.kind == ShapeKind.Circle) CircleShape else RoundedCornerShape(4.dp))
                    .pointerInput(shape.id) {
                        detectDragGestures(
                            onDragStart = { vm.beginGesture() },
                            onDrag = { _, drag -> vm.moveShape(shape.id, drag.x / canvasW, drag.y / canvasH) },
                            onDragEnd = { vm.endGesture(render = false) },
                            onDragCancel = { vm.endGesture(render = false) },
                        )
                    },
            )
        }
        ui.document.texts.forEach { stamp ->
            Text(
                stamp.text,
                color = Color(stamp.color),
                modifier = Modifier
                    .offset(x = maxWidth * stamp.nx, y = maxHeight * stamp.ny)
                    .pointerInput(stamp.id) {
                        detectDragGestures(
                            onDragStart = {
                                vm.beginGesture()
                                vm.selectText(stamp.id)
                            },
                            onDrag = { _, drag -> vm.moveText(stamp.id, drag.x / canvasW, drag.y / canvasH) },
                            onDragEnd = { vm.endGesture(render = false) },
                            onDragCancel = { vm.endGesture(render = false) },
                        )
                    },
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun CollagePreview(bitmap: Bitmap?) {
    if (bitmap == null) {
        Text("Pick photos to fill the collage.", style = MaterialTheme.typography.bodyLarge)
    } else {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = "Collage preview",
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun ColorRow(selected: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        inkColors.forEach { color ->
            Box(
                Modifier
                    .background(Color(color), CircleShape)
                    .border(if (color == selected) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    .padding(14.dp)
                    .clickable { onPick(color) },
            )
        }
    }
}
