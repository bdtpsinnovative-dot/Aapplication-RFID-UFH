package ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val BURST_GUARD_MS = 180L
private const val IDLE_FINALIZE_MS = 90L
private const val MIN_TOKEN_LEN = 6

internal sealed class ScanResult(val message: String, val code: String) {
    class Success(message: String, code: String) : ScanResult(message, code)
    class Error(message: String, code: String) : ScanResult(message, code)
}

@Composable
fun ModernRfidDialog(
    title: String,
    subtitle: String?,
    slots: Int,
    initial: List<String>,
    usedOther: Map<String, Pair<String, String?>>,
    isScanning: Boolean,
    isReaderConnected: Boolean,
    onToggleScan: (Boolean) -> Unit,
    setScanCallback: (((String) -> Unit)?) -> Unit,
    onRemoveFromOther: (tag: String) -> Unit,
    onDismiss: () -> Unit,
    onSaved: (List<String>) -> Unit
) {
    val list = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    val localSet by remember { derivedStateOf { list.toSet() } }
    val usedOtherNorm = remember(usedOther) { usedOther.mapKeys { it.key.uppercase().trim() } }
    var conflictInfo by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var conflictTag by remember { mutableStateOf<String?>(null) }
    var editableSlots by rememberSaveable { mutableStateOf(slots.coerceAtLeast(1)) }
    var scanBuffer by remember { mutableStateOf("") }
    var lastResult by remember { mutableStateOf<ScanResult?>(null) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var idleJob by remember { mutableStateOf<Job?>(null) }
    val lastSeenMs = remember { mutableStateMapOf<String, Long>() }

    fun normalize(raw: String): String = raw.trim().replace(Regex("[^A-Za-z0-9]"), "").uppercase()

    fun refocus() {
        scope.launch {
            delay(60)
            try { focusRequester.requestFocus() } catch (_: Exception) {}
            keyboardController?.hide()
        }
    }

    fun submitCode(raw: String) {
        val code = normalize(raw)
        if (code.length < MIN_TOKEN_LEN) {
            if (code.isNotBlank()) lastResult = ScanResult.Error("รหัสสั้นเกินไป", code)
            return
        }
        val now = System.currentTimeMillis()
        if (now - (lastSeenMs[code] ?: 0L) < BURST_GUARD_MS) return
        lastSeenMs[code] = now
        when {
            list.size >= editableSlots -> {
                lastResult = ScanResult.Error("ครบจำนวนแล้ว", code)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggleScan(false)
            }
            localSet.contains(code) -> {
                lastResult = ScanResult.Error("ซ้ำในรายการนี้", code)
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            usedOtherNorm.containsKey(code) -> {
                val info = usedOtherNorm[code]
                val owner = info?.first ?: "สินค้าอื่น"
                lastResult = ScanResult.Error("ซ้ำกับ: $owner", code)
                conflictTag = code
                conflictInfo = info
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            else -> {
                list.add(0, code)
                lastResult = ScanResult.Success("บันทึกสำเร็จ", code)
                if (list.size >= editableSlots) {
                    onToggleScan(false)
                    focusRequester.freeFocus()
                }
            }
        }
    }

    DisposableEffect(isScanning) {
        if (isScanning) setScanCallback { tag -> submitCode(tag) }
        onDispose { setScanCallback(null) }
    }

    val keyEventModifier = Modifier
        .focusRequester(focusRequester)
        .focusable()
        .onPreviewKeyEvent { ev ->
            if (ev.type == KeyEventType.KeyDown && isScanning) {
                val char = ev.nativeKeyEvent.unicodeChar.toChar()
                val isEnter = ev.nativeKeyEvent.keyCode in listOf(
                    AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                )
                when {
                    isEnter -> {
                        if (scanBuffer.isNotBlank()) { submitCode(scanBuffer); scanBuffer = "" }
                        true
                    }
                    char.isLetterOrDigit() -> {
                        scanBuffer += char
                        idleJob?.cancel()
                        idleJob = scope.launch {
                            delay(IDLE_FINALIZE_MS)
                            if (scanBuffer.isNotBlank()) { submitCode(scanBuffer); scanBuffer = "" }
                        }
                        true
                    }
                    else -> false
                }
            } else false
        }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.93f)
                    .fillMaxHeight(0.88f)
                    .clip(RoundedCornerShape(28.dp))
                    .then(keyEventModifier),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(Modifier.fillMaxSize()) {

                    // Header
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("SKU: ${subtitle ?: "-"}", style = MaterialTheme.typography.bodySmall, color = ColorTextSec)
                        }
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null, tint = ColorTextSec) }
                    }

                    // Scan zone
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isScanning) ColorPrimarySoft else ColorBg)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ScannerPulseAnimation(isScanning = isScanning)
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (isScanning) "กำลังรับสแกน..."
                                else if (!isReaderConnected) "เครื่องไม่พร้อม"
                                else "พร้อมยิง RFID",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isScanning) ColorPrimary else if (!isReaderConnected) Color(0xFFEF4444) else ColorTextMain
                            )
                            Text(
                                if (isScanning) "ยิง Tag ได้เลย ไม่ต้องแตะจอ" else "กดปุ่ม 'เริ่มยิง' ด้านล่าง",
                                style = MaterialTheme.typography.bodySmall,
                                color = ColorTextSec
                            )
                        }
                        // Qty adjuster
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    if (editableSlots > 1) {
                                        editableSlots--
                                        while (list.size > editableSlots) list.removeAt(list.size - 1)
                                        lastResult = null
                                    }
                                    if (isScanning) refocus()
                                },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Default.Remove, null, tint = if (editableSlots > 1) ColorPrimary else Color.Gray, modifier = Modifier.size(16.dp))
                            }
                            Text(
                                "$editableSlots",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = ColorTextMain,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(min = 32.dp)
                            )
                            IconButton(
                                onClick = { editableSlots++; if (list.size < editableSlots) onToggleScan(true); refocus() },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Default.Add, null, tint = ColorPrimary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    // Scan result banner
                    AnimatedVisibility(
                        visible = lastResult != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        lastResult?.let { res ->
                            val isErr = res is ScanResult.Error
                            val isConflict = isErr && conflictTag != null
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 6.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isErr) Color(0xFFFEF2F2) else Color(0xFFF0FDF4))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        if (isErr) Icons.Default.Warning else Icons.Default.CheckCircle, null,
                                        tint = if (isErr) Color(0xFFEF4444) else ColorSuccess,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(res.message, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (isErr) Color(0xFFEF4444) else ColorSuccess)
                                        Text(res.code, style = MaterialTheme.typography.bodySmall, color = ColorTextSec)
                                    }
                                    if (isConflict) {
                                        TextButton(
                                            onClick = {
                                                conflictTag?.let { tag ->
                                                    onRemoveFromOther(tag)
                                                    if (!localSet.contains(tag) && list.size < editableSlots) {
                                                        list.add(0, tag)
                                                        lastResult = ScanResult.Success("ย้ายมาแล้ว", tag)
                                                    } else {
                                                        lastResult = null
                                                    }
                                                    conflictTag = null
                                                    conflictInfo = null
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
                                        ) {
                                            Text("ลบออก", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                if (isConflict && conflictInfo != null) {
                                    ConflictProductCard(
                                        conflictInfo = conflictInfo!!,
                                        onDismiss = { }
                                    )
                                }
                            }
                        }
                    }

                    // Scanned list header
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("แท็กที่ยิงแล้ว", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ColorTextMain)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (list.isNotEmpty()) {
                                TextButton(
                                    onClick = { list.clear(); lastResult = null; onToggleScan(true); scanBuffer = ""; refocus() },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444)),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) { Text("ล้างทั้งหมด", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                            }
                            Surface(
                                color = if (list.size >= editableSlots) ColorSuccess else if (list.isNotEmpty()) ColorWarning else ColorBg,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    "${list.size} / $editableSlots",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (list.isNotEmpty()) Color.White else ColorTextSec
                                )
                            }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (list.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                                    Text("ยังไม่มีแท็ก — กด 'เริ่มยิง' แล้วยิง RFID", style = MaterialTheme.typography.bodySmall, color = ColorTextSec)
                                }
                            }
                        } else {
                            itemsIndexed(list) { index, code ->
                                ScannedItemRow(index = list.size - index, code = code, onDelete = { list.removeAt(index) })
                            }
                        }
                    }

                    // Action buttons
                    HorizontalDivider(color = ColorBg)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onToggleScan(!isScanning); if (!isScanning) refocus() },
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (isScanning) Color(0xFFEF4444) else ColorPrimary)
                        ) {
                            Icon(if (isScanning) Icons.Filled.Close else Icons.Default.QrCodeScanner, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isScanning) "หยุดยิง" else "เริ่มยิง", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { onSaved(list.toList()) },
                            enabled = !isScanning && list.isNotEmpty(),
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ColorPrimary)
                        ) {
                            Icon(Icons.Outlined.Save, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (list.size >= editableSlots) "ยืนยัน ✓" else "ยืนยัน (${list.size})",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(isScanning) {
        if (isScanning) {
            delay(80)
            try { focusRequester.requestFocus() } catch (_: Exception) {}
            keyboardController?.hide()
        }
    }

    LaunchedEffect(lastResult) {
        if (lastResult != null) {
            delay(2500)
            lastResult = null
            conflictTag = null
            if (isScanning) try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }
}

@Composable
private fun ConflictProductCard(
    conflictInfo: Pair<String, String?>,
    onDismiss: () -> Unit
) {
    var showFullImg by remember { mutableStateOf(false) }
    val imgUrl = getProductImageUrl(conflictInfo.second)

    if (showFullImg && imgUrl != null) {
        Dialog(
            onDismissRequest = { showFullImg = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .clickable { showFullImg = false },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = imgUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                IconButton(
                    onClick = { showFullImg = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                ) {
                    Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Text(
                    conflictInfo.first,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFFFE4E4))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AsyncImage(
            model = imgUrl,
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .clickable { if (imgUrl != null) showFullImg = true },
            contentScale = ContentScale.Crop,
            error = rememberVectorPainter(Icons.Outlined.Inventory2)
        )
        Column(Modifier.weight(1f)) {
            Text("Tag นี้ถูกใช้โดย:", style = MaterialTheme.typography.labelSmall, color = Color(0xFFEF4444))
            Text(
                conflictInfo.first,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFC62828),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text("กดรูปเพื่อดูเต็มจอ", style = MaterialTheme.typography.labelSmall, color = Color(0xFFEF9A9A))
        }
    }
}

@Composable
fun ScannerPulseAnimation(isScanning: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        1f, if (isScanning) 1.35f else 1f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "scale"
    )
    val alpha by infiniteTransition.animateFloat(
        0.4f, if (isScanning) 0.05f else 0.4f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha"
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(52.dp)) {
        if (isScanning) {
            Box(modifier = Modifier.size(52.dp).scale(scale).clip(CircleShape).background(ColorPrimary.copy(alpha = alpha)))
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .shadow(6.dp, CircleShape, spotColor = ColorPrimary.copy(0.15f))
                .clip(CircleShape)
                .background(Color.White)
                .border(2.5.dp, if (isScanning) ColorPrimary else Color.Gray.copy(0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(22.dp), tint = if (isScanning) ColorPrimary else Color.Gray)
        }
    }
}

@Composable
fun ScannedItemRow(index: Int, code: String, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ColorBg)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$index.", style = MaterialTheme.typography.bodySmall, color = ColorTextSec, modifier = Modifier.width(24.dp))
        Text(code, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = ColorTextMain, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.Delete, null, tint = ColorTextSec, modifier = Modifier.size(18.dp))
        }
    }
}
