package com.mediaapp.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backspace
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediaapp.data.model.GalleryEntry
import com.mediaapp.data.model.MediaFilter
import com.mediaapp.data.vault.LockKind
import com.mediaapp.data.vault.VaultSession

@Composable
fun VaultRoute(
    snackbar: SnackbarHostState,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedContentScope,
    onOpenEntry: (VaultUi, GalleryEntry) -> Unit,
) {
    val vm: VaultViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val consent by vm.consent.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        WindowSecurity.acquire()
        onDispose { WindowSecurity.release() }
    }
    CollectMessages(vm.messages, snackbar)
    ConsentEffect(consent, vm::onConsent)
    LaunchedEffect(ui.gate) {
        if (ui.gate == VaultGate.Open) vm.finishPending()
    }
    Column(Modifier.fillMaxSize()) {
        when (ui.gate) {
            VaultGate.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            VaultGate.Choose -> VaultIntro(onPin = vm::choosePin, onPattern = vm::choosePattern)
            VaultGate.EnterPin, VaultGate.ConfirmPin, VaultGate.Unlock -> {
                if (ui.kind == LockKind.Pattern && ui.gate == VaultGate.Unlock) {
                    PatternLock(
                        title = "Draw your pattern",
                        error = ui.error,
                        cooldownMs = ui.cooldownMs,
                        biometric = ui.biometric,
                        session = vm.session,
                        onBiometric = vm::unlockBiometric,
                        onComplete = vm::submitPattern,
                    )
                } else {
                    PinLock(
                        title = when (ui.gate) {
                            VaultGate.ConfirmPin -> "Confirm the PIN"
                            VaultGate.Unlock -> "Enter your PIN"
                            else -> "Choose a PIN"
                        },
                        draft = ui.draft,
                        error = ui.error,
                        cooldownMs = ui.cooldownMs,
                        biometric = ui.biometric && ui.gate == VaultGate.Unlock,
                        session = vm.session,
                        onDigit = vm::appendDigit,
                        onBackspace = vm::backspace,
                        onSubmit = vm::submitPin,
                        onBiometric = vm::unlockBiometric,
                    )
                }
            }
            VaultGate.EnterPattern, VaultGate.ConfirmPattern -> PatternLock(
                title = if (ui.gate == VaultGate.ConfirmPattern) "Draw the pattern again" else "Draw a pattern",
                error = ui.error,
                cooldownMs = 0L,
                biometric = false,
                session = vm.session,
                onBiometric = {},
                onComplete = vm::submitPattern,
            )
            VaultGate.Open -> VaultBrowser(
                ui = ui,
                progress = progress,
                sharedScope = sharedScope,
                animatedScope = animatedScope,
                onFilter = vm::setFilter,
                onColumns = vm::setColumns,
                onOpen = { onOpenEntry(ui, it) },
                onToggle = vm::toggle,
                onStartSelect = vm::startSelect,
                onClear = vm::clearSelection,
                onSelectAll = vm::selectAll,
                onRestore = { vm.restore(vm.selectedEntries()) },
                onDestroy = { vm.destroy(vm.selectedEntries()) },
                onBiometric = vm::setBiometric,
                onLockNow = vm::lockNow,
                thumb = { entry -> VaultThumb(entry, vm::thumb) },
            )
        }
    }
}

@Composable
private fun VaultIntro(onPin: () -> Unit, onPattern: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(16.dp))
        Text("Lock files on this phone", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "A PIN or pattern keeps the vault closed. You can add fingerprint or face unlock after that. Locked files are encrypted and removed from the gallery.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        DestructionWarning()
        Spacer(Modifier.height(20.dp))
        Button(onClick = onPin, modifier = Modifier.fillMaxWidth()) { Text("Use a PIN") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onPattern, modifier = Modifier.fillMaxWidth()) { Text("Use a pattern") }
    }
}

@Composable
private fun DestructionWarning() {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Text(
            "Uninstalling Media App or clearing its data permanently deletes everything in Locked. The encryption key lives in Android Keystore and there is no backup yet.",
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun PinLock(
    title: String,
    draft: String,
    error: String?,
    cooldownMs: Long,
    biometric: Boolean,
    session: VaultSession,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    onBiometric: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(28.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text("●".repeat(draft.length).ifEmpty { " " }, style = MaterialTheme.typography.headlineMedium)
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        if (cooldownMs > 0L) {
            Text("Try again in ${((cooldownMs + 999) / 1000)}s", modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(18.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { digit ->
                    Surface(onClick = { onDigit(digit) }, shape = CircleShape, modifier = Modifier.size(76.dp)) {
                        Box(contentAlignment = Alignment.Center) { Text(digit, style = MaterialTheme.typography.headlineMedium) }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBackspace, modifier = Modifier.size(76.dp)) {
                Icon(Icons.Outlined.Backspace, contentDescription = "Delete digit")
            }
            Surface(onClick = { onDigit("0") }, shape = CircleShape, modifier = Modifier.size(76.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("0", style = MaterialTheme.typography.headlineMedium) }
            }
            TextButton(onClick = onSubmit, modifier = Modifier.size(76.dp)) { Text("OK") }
        }
        if (biometric) {
            Spacer(Modifier.height(12.dp))
            val context = LocalContext.current
            TextButton(onClick = {
                val activity = context.findActivity() as? FragmentActivity ?: return@TextButton
                promptBiometric(activity, session, onBiometric)
            }) {
                Icon(Icons.Outlined.Fingerprint, contentDescription = null)
                Text("  Use biometrics")
            }
        }
    }
}

@Composable
private fun PatternLock(
    title: String,
    error: String?,
    cooldownMs: Long,
    biometric: Boolean,
    session: VaultSession,
    onBiometric: () -> Unit,
    onComplete: (List<Int>) -> Unit,
) {
    var selected by remember { mutableStateOf(listOf<Int>()) }
    val line = MaterialTheme.colorScheme.primary
    val dot = MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(28.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        if (cooldownMs > 0L) Text("Try again in ${((cooldownMs + 999) / 1000)}s", modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(24.dp))
        Canvas(
            Modifier
                .size(280.dp)
                .pointerInput(Unit) {
                    val side = size.width.toFloat()
                    detectDragGestures(
                        onDragStart = { offset ->
                            selected = listOfNotNull(patternDot(offset, side))
                        },
                        onDrag = { change, _ ->
                            val dotIndex = patternDot(change.position, side)
                            if (dotIndex != null && dotIndex !in selected) selected = selected + dotIndex
                        },
                        onDragEnd = {
                            val done = selected
                            selected = emptyList()
                            if (done.isNotEmpty()) onComplete(done)
                        },
                        onDragCancel = { selected = emptyList() },
                    )
                },
        ) {
            val centers = (0 until 9).map { patternCenter(it, size.width) }
            for (index in 0 until selected.lastIndex) {
                drawLine(line, centers[selected[index]], centers[selected[index + 1]], strokeWidth = 10f, cap = StrokeCap.Round)
            }
            centers.forEachIndexed { index, center ->
                val active = index in selected
                drawCircle(if (active) line else dot.copy(alpha = 0.35f), radius = if (active) 28f else 18f, center = center)
                if (active) drawCircle(Color.Transparent, radius = 40f, center = center, style = Stroke(width = 4f))
            }
        }
        if (biometric) {
            val context = LocalContext.current
            TextButton(onClick = {
                val activity = context.findActivity() as? FragmentActivity ?: return@TextButton
                promptBiometric(activity, session, onBiometric)
            }) {
                Icon(Icons.Outlined.Fingerprint, contentDescription = null)
                Text("  Use biometrics")
            }
        }
    }
}

private fun patternCenter(index: Int, size: Float): Offset {
    val cell = size / 3f
    return Offset(cell * (index % 3) + cell / 2f, cell * (index / 3) + cell / 2f)
}

private fun patternDot(offset: Offset, size: Float): Int? {
    for (index in 0 until 9) {
        if ((patternCenter(index, size) - offset).getDistance() < size / 7f) return index
    }
    return null
}

private fun promptBiometric(activity: FragmentActivity, session: VaultSession, onSuccess: () -> Unit) {
    val allowed = BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
    if (allowed != BiometricManager.BIOMETRIC_SUCCESS) return
    val executor = ContextCompat.getMainExecutor(activity)
    session.suppressAutoLock = true
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                session.suppressAutoLock = false
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                session.suppressAutoLock = false
            }
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock Locked")
        .setSubtitle("Confirm it's you")
        .setNegativeButtonText("Use passcode")
        .build()
    prompt.authenticate(info)
}

@Composable
private fun VaultBrowser(
    ui: VaultUi,
    progress: com.mediaapp.data.vault.LockProgress?,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedContentScope,
    onFilter: (MediaFilter) -> Unit,
    onColumns: (Int) -> Unit,
    onOpen: (GalleryEntry) -> Unit,
    onToggle: (GalleryEntry) -> Unit,
    onStartSelect: (GalleryEntry) -> Unit,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onRestore: () -> Unit,
    onDestroy: () -> Unit,
    onBiometric: (Boolean) -> Unit,
    onLockNow: () -> Unit,
    thumb: @Composable (GalleryEntry) -> Unit,
) {
    val selecting = ui.selected.isNotEmpty()
    val canBiometric = biometricAvailable()
    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClear) { Text("Cancel") }
                Text("${ui.selected.size} selected", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onSelectAll) { Text("All") }
                IconButton(onClick = onRestore) { Icon(Icons.Outlined.LockOpen, contentDescription = "Restore") }
                IconButton(onClick = onDestroy) { Icon(Icons.Outlined.Delete, contentDescription = "Delete from vault") }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Locked", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (ui.items.isEmpty()) "Private to this app" else "${ui.items.size} hidden",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onLockNow) { Icon(Icons.Outlined.Lock, contentDescription = "Lock the vault now") }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MediaFilter.entries.forEach { filter ->
                FilterChip(
                    selected = ui.filter == filter,
                    onClick = { onFilter(filter) },
                    label = {
                        Text(
                            when (filter) {
                                MediaFilter.All -> "All"
                                MediaFilter.Images -> "Images"
                                MediaFilter.Videos -> "Videos"
                            },
                        )
                    },
                )
            }
        }
        if (canBiometric && !selecting) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Biometric unlock", style = MaterialTheme.typography.titleMedium)
                    Text("Fingerprint or face, then the vault opens.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = ui.biometric, onCheckedChange = onBiometric)
            }
        }
        progress?.let { lock ->
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Working on ${lock.name}", style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(
                    progress = { if (lock.total == 0) 0f else lock.completed.toFloat() / lock.total },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
        Box(Modifier.weight(1f)) {
            if (ui.items.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
                    Text("Nothing locked yet", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Select photos or videos in the library, or lock a whole folder. They leave the shared gallery and stay encrypted here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                MediaGrid(
                    items = ui.items,
                    columns = ui.columns,
                    sort = ui.sort,
                    selected = ui.selected,
                    selecting = selecting,
                    onOpen = onOpen,
                    onToggle = onToggle,
                    onStartSelect = onStartSelect,
                    onColumns = onColumns,
                    thumb = thumb,
                    modifier = Modifier.fillMaxSize(),
                    sharedElement = { entry ->
                        val base = this
                        with(sharedScope) {
                            base.sharedElement(
                                rememberSharedContentState(key = "media-${entry.id}"),
                                animatedVisibilityScope = animatedScope,
                            )
                        }
                    },
                )
            }
        }
        DestructionWarning()
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun biometricAvailable(): Boolean {
    val context = LocalContext.current
    val code = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
    return code == BiometricManager.BIOMETRIC_SUCCESS
}
