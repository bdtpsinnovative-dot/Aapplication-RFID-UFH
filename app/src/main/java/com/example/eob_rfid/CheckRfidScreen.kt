package com.example.eob_rfid

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.DecimalFormat
import kotlin.math.floor

// --- Constants & Config ---
private const val BURST_GUARD_MS = 180L
private const val IDLE_FINALIZE_MS = 90L
private const val MAX_TAIL_LEN = 256
private const val MIN_TOKEN_LEN = 6

// --- Custom Colors ---
private val ColorPrimary = Color(0xFF2563EB)
private val ColorPrimarySoft = Color(0xFFEFF6FF)
private val ColorSuccess = Color(0xFF10B981)
private val ColorSuccessSoft = Color(0xFFECFDF5)
private val ColorWarning = Color(0xFFF59E0B)
private val ColorWarningSoft = Color(0xFFFFFBEB)
private val ColorTextMain = Color(0xFF1E293B)
private val ColorTextSec = Color(0xFF64748B)
private val ColorBg = Color(0xFFF8FAFC)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckRfidScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val qtyFmt = remember { DecimalFormat("#,##0.###") }

    val currentBranchId = remember { SessionStore.getBranchId(ctx) }
    val currentBranchName = remember { SessionStore.getBranchName(ctx) }
    val currentUserId = remember { SessionStore.getUserId(ctx) }
    val currentUserName = remember { SessionStore.getDisplayName(ctx) }

    var loading by remember { mutableStateOf(false) }
    var savingAll by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<StockReceivingItem>>(emptyList()) }

    val draftTags = remember { mutableStateMapOf<Long, List<String>>() }
    var picked by remember { mutableStateOf<StockReceivingItem?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    fun reqSlots(qty: Double): Int = floor(qty).toInt().coerceAtLeast(0)
    fun norm(s: String) = s.trim()

    fun load() {
        scope.launch {
            loading = true
            msg = null
            try {
                items = StockReceivingBrowseApi.fetchAll(SessionStore.getAccessToken(ctx))
            } catch (e: Exception) {
                msg = e.message ?: "โหลดข้อมูลไม่สำเร็จ"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    val needList = remember(items) { items.filter { reqSlots(it.qty) > 0 } }
    val totalNeed = needList.sumOf { reqSlots(it.qty) }
    val totalHave = needList.sumOf { draftTags[it.productId]?.size ?: 0 }
    val allFilled = needList.isNotEmpty() && needList.all { itx ->
        (draftTags[itx.productId]?.size ?: 0) == reqSlots(itx.qty)
    }

    val hasDuplicateInDraft = run {
        val all = draftTags.values.flatten().map(::norm).filter { it.isNotBlank() }
        all.size != all.toSet().size
    }

    Scaffold(
        containerColor = ColorBg,
        topBar = {
            ModernTopBar(
                title = "RFID Tagging",
                subtitle = "สาขา: $currentBranchName",
                onBack = onBack,
                onRefresh = { load() },
                loading = loading || savingAll
            )
        },
        bottomBar = {
            if (needList.isNotEmpty()) {
                ModernBottomBar(
                    totalHave = totalHave,
                    totalNeed = totalNeed,
                    allFilled = allFilled,
                    hasDuplicate = hasDuplicateInDraft,
                    savingAll = savingAll,
                    onSave = {
                        if (!allFilled || hasDuplicateInDraft || savingAll) return@ModernBottomBar
                        savingAll = true
                        msg = null
                        scope.launch {
                            try {
                                val payload = needList.associate { it.productId to (draftTags[it.productId] ?: emptyList()) }
                                val token = SessionStore.getAccessToken(ctx)

                                SupabaseBatchCommit.commitAll(
                                    data = payload,
                                    accessToken = token,
                                    branchId = currentBranchId,
                                    branchName = currentBranchName,
                                    userId = currentUserId,
                                    userName = currentUserName
                                )
                                SupabaseBatchCommit.clearStockReceiving(token, currentBranchId)

                                draftTags.clear()
                                load()
                                msg = "บันทึกข้อมูลลงสาขา $currentBranchId เรียบร้อยแล้ว"
                            } catch (e: Exception) {
                                msg = e.message ?: "บันทึกไม่สำเร็จ"
                            } finally {
                                savingAll = false
                            }
                        }
                    }
                )
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ModernSummaryCard(
                    totalHave = totalHave,
                    totalNeed = totalNeed,
                    hasDuplicate = hasDuplicateInDraft
                )

                AnimatedVisibility(
                    visible = !msg.isNullOrBlank(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    ModernAlertCard(msg.orEmpty(), isError = true)
                }

                if (loading && needList.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ColorPrimary)
                    }
                } else if (needList.isEmpty()) {
                    EmptyStateView(onRefresh = { load() })
                } else {
                    Text(
                        "รายการสินค้า (${needList.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = ColorTextMain,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp)
                    )

                    needList.forEach { item ->
                        val need = reqSlots(item.qty)
                        val have = draftTags[item.productId]?.size ?: 0
                        ModernItemCard(
                            item = item,
                            code = item.barcode ?: item.sku,
                            qtyText = qtyFmt.format(item.qty),
                            have = have,
                            need = need,
                            enabled = !savingAll,
                            onClick = {
                                picked = item
                                showDialog = true
                            }
                        )
                    }
                    Spacer(Modifier.height(80.dp))
                }
            }
        }
    }

    if (showDialog && picked != null) {
        val pid = picked!!.productId
        val slots = reqSlots(picked!!.qty)
        val initial = draftTags[pid].orEmpty()

        val usedOther = draftTags.entries
            .filter { it.key != pid }
            .flatMap { it.value }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        ModernRfidDialog(
            title = picked!!.name ?: "Product #$pid",
            subtitle = picked!!.barcode ?: picked!!.sku ?: "-",
            slots = slots,
            initial = initial,
            usedOther = usedOther,
            onDismiss = { showDialog = false },
            onSaved = { rfids ->
                draftTags[pid] = rfids
                showDialog = false
            }
        )
    }
}

// ---------------- UI Components (Same as before) ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernTopBar(title: String, subtitle: String, onBack: () -> Unit, onRefresh: () -> Unit, loading: Boolean) {
    CenterAlignedTopAppBar(
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = ColorTextMain))
                Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(color = ColorTextSec))
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBackIosNew, contentDescription = "Back", tint = ColorTextMain, modifier = Modifier.size(20.dp))
            }
        },
        actions = {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp).padding(2.dp), strokeWidth = 2.dp, color = ColorPrimary)
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = ColorTextMain)
                }
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = ColorBg)
    )
}

@Composable
fun ModernSummaryCard(totalHave: Int, totalNeed: Int, hasDuplicate: Boolean) {
    val progress = if (totalNeed > 0) totalHave.toFloat() / totalNeed.toFloat() else 0f
    val animatedProgress by animateFloatAsState(progress, label = "progress")

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.shadow(8.dp, RoundedCornerShape(24.dp), ambientColor = Color.Black.copy(alpha = 0.05f), spotColor = Color.Black.copy(alpha = 0.05f))
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
                CircularProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxSize(), color = ColorBg, strokeWidth = 6.dp, trackColor = ColorBg)
                CircularProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxSize(), color = if (hasDuplicate) Color.Red else ColorPrimary, strokeWidth = 6.dp, trackColor = ColorPrimarySoft, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = ColorTextMain)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("ภาพรวมการสแกน", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = ColorTextMain)
                Spacer(Modifier.height(4.dp))
                if (hasDuplicate) Text("พบรายการซ้ำ! กรุณาตรวจสอบ", style = MaterialTheme.typography.bodySmall, color = Color.Red)
                else Text("คงเหลือ ${totalNeed - totalHave} รายการ", style = MaterialTheme.typography.bodySmall, color = ColorTextSec)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("$totalHave", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = ColorPrimary)
                Text("/ $totalNeed", style = MaterialTheme.typography.bodySmall, color = ColorTextSec)
            }
        }
    }
}

@Composable
fun ModernItemCard(item: StockReceivingItem, code: String?, qtyText: String, have: Int, need: Int, enabled: Boolean, onClick: () -> Unit) {
    val isComplete = have >= need && need > 0
    val bg = Color.White
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
        border = if (isComplete) BorderStroke(1.dp, ColorSuccess.copy(alpha = 0.5f)) else null,
        modifier = Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(alpha = 0.03f), spotColor = Color.Black.copy(alpha = 0.03f)).clip(RoundedCornerShape(16.dp)).clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(if (isComplete) ColorSuccessSoft else ColorPrimarySoft), contentAlignment = Alignment.Center) {
                Icon(imageVector = if (isComplete) Icons.Outlined.Check else Icons.Outlined.Inventory2, contentDescription = null, tint = if (isComplete) ColorSuccess else ColorPrimary, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name ?: "Unknown Product", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = ColorTextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(code ?: "-", style = MaterialTheme.typography.bodySmall, color = ColorTextSec, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Surface(shape = RoundedCornerShape(8.dp), color = if (isComplete) ColorSuccess else ColorBg) {
                    Text("$have / $need", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (isComplete) Color.White else ColorTextMain)
                }
                Spacer(Modifier.height(4.dp))
                Text("Qty: $qtyText", style = MaterialTheme.typography.labelSmall, color = ColorTextSec)
            }
        }
    }
}

@Composable
fun ModernBottomBar(totalHave: Int, totalNeed: Int, allFilled: Boolean, hasDuplicate: Boolean, savingAll: Boolean, onSave: () -> Unit) {
    val enabled = allFilled && !hasDuplicate && !savingAll
    Surface(color = Color.White, shadowElevation = 16.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("ความคืบหน้าทั้งหมด", style = MaterialTheme.typography.labelSmall, color = ColorTextSec)
                    Text(if (allFilled) "ครบถ้วนพร้อมบันทึก" else "กำลังดำเนินการ...", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = if (allFilled) ColorSuccess else ColorTextMain)
                }
                Text("$totalHave/$totalNeed", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = if (allFilled) ColorSuccess else ColorTextSec.copy(alpha = 0.5f))
            }
            Button(onClick = onSave, enabled = enabled, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = ColorPrimary, disabledContainerColor = Color.Gray.copy(alpha = 0.2f)), elevation = ButtonDefaults.buttonElevation(defaultElevation = if (enabled) 4.dp else 0.dp, pressedElevation = 2.dp)) {
                if (savingAll) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.5.dp)
                else Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Save, null); Spacer(Modifier.width(8.dp)); Text("บันทึกเข้าระบบ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
fun ModernAlertCard(text: String, isError: Boolean) {
    val bg = if (isError) Color(0xFFFEF2F2) else Color(0xFFF0FDF4)
    val contentColor = if (isError) Color(0xFFEF4444) else Color(0xFF15803D)
    val icon = if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle
    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = contentColor)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = contentColor, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun EmptyStateView(onRefresh: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(modifier = Modifier.size(120.dp).clip(CircleShape).background(ColorPrimarySoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Inventory2, null, modifier = Modifier.size(60.dp), tint = ColorPrimary.copy(alpha = 0.5f))
        }
        Spacer(Modifier.height(24.dp))
        Text("ไม่มีรายการสินค้า", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ColorTextMain)
        Text("ดูเหมือนจะไม่มีรายการที่ต้องทำในขณะนี้", style = MaterialTheme.typography.bodyMedium, color = ColorTextSec)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onRefresh, shape = RoundedCornerShape(12.dp)) { Text("รีเฟรชข้อมูล") }
    }
}

@Composable
fun ModernRfidDialog(title: String, subtitle: String?, slots: Int, initial: List<String>, usedOther: Set<String>, onDismiss: () -> Unit, onSaved: (List<String>) -> Unit) {
    val list = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    val localSet by remember { derivedStateOf { list.toSet() } }
    val usedOtherNorm = remember(usedOther) { usedOther.map { it.uppercase().trim() }.toSet() }
    var scanning by rememberSaveable { mutableStateOf(true) }
    var scanBuffer by remember { mutableStateOf("") }
    var lastResult by remember { mutableStateOf<ScanResult?>(null) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var idleJob by remember { mutableStateOf<Job?>(null) }
    val lastSeenMs = remember { mutableStateMapOf<String, Long>() }

    fun normalize(raw: String): String = raw.trim().replace(Regex("[^A-Za-z0-9]"), "").uppercase()

    fun submitCode(raw: String) {
        val code = normalize(raw)
        if (code.length < MIN_TOKEN_LEN) return
        val now = System.currentTimeMillis()
        if (now - (lastSeenMs[code] ?: 0L) < BURST_GUARD_MS) return
        lastSeenMs[code] = now
        when {
            list.size >= slots -> { lastResult = ScanResult.Error("ครบจำนวนแล้ว", code); haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
            localSet.contains(code) -> { lastResult = ScanResult.Error("สแกนซ้ำ (รายการนี้)", code); haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
            usedOtherNorm.contains(code) -> { lastResult = ScanResult.Error("สแกนซ้ำ (สินค้าอื่น)", code); haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
            else -> { list.add(0, code); lastResult = ScanResult.Success("บันทึกสำเร็จ", code); if (list.size >= slots) { scanning = false; focusRequester.freeFocus() } }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Card(modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.85f).clip(RoundedCornerShape(28.dp)).focusRequester(focusRequester).focusable().onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown) {
                    val char = ev.nativeKeyEvent.unicodeChar.toChar()
                    val isEnter = ev.nativeKeyEvent.keyCode in listOf(AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER)
                    if (scanning) {
                        if (isEnter) { if (scanBuffer.isNotBlank()) { submitCode(scanBuffer); scanBuffer = "" }; return@onPreviewKeyEvent true }
                        else if (char.isLetterOrDigit()) {
                            scanBuffer += char
                            idleJob?.cancel()
                            idleJob = scope.launch { delay(IDLE_FINALIZE_MS); if (scanBuffer.isNotBlank()) { submitCode(scanBuffer); scanBuffer = "" } }
                            return@onPreviewKeyEvent true
                        }
                    }
                }
                false
            }, colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(8.dp)) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("SKU: ${subtitle ?: "-"}", style = MaterialTheme.typography.bodySmall, color = ColorTextSec) }
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null, tint = ColorTextSec) }
                    }
                    Divider(color = ColorBg)
                    Box(Modifier.fillMaxWidth().weight(0.4f).background(ColorBg), contentAlignment = Alignment.Center) {
                        ScannerPulseAnimation(isScanning = scanning)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Spacer(Modifier.height(130.dp)); Text(if (scanning) "พร้อมรับสแกน..." else "หยุดการสแกน", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if(scanning) ColorPrimary else ColorTextSec); Text(if (scanning) "ยิง RFID Tag ได้ทันที" else "กด 'เริ่มยิง' เพื่อดำเนินการต่อ", style = MaterialTheme.typography.bodySmall, color = ColorTextSec) }
                    }
                    AnimatedVisibility(visible = lastResult != null) {
                        lastResult?.let {
                            val isErr = it is ScanResult.Error
                            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (isErr) Color(0xFFFEF2F2) else Color(0xFFF0FDF4)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (isErr) Icons.Default.Warning else Icons.Default.CheckCircle, null, tint = if (isErr) Color.Red else ColorSuccess)
                                    Spacer(Modifier.width(8.dp))
                                    Column { Text(it.message, fontWeight = FontWeight.Bold, color = if (isErr) Color.Red else ColorSuccess); Text(it.code, style = MaterialTheme.typography.bodySmall, color = ColorTextMain) }
                                }
                            }
                        }
                    }
                    Column(Modifier.weight(0.6f).fillMaxWidth().background(Color.White)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("รายการที่ยิงแล้ว", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Surface(color = if (list.size == slots) ColorSuccess else ColorPrimarySoft, shape = RoundedCornerShape(12.dp)) { Text("${list.size} / $slots", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (list.size == slots) Color.White else ColorPrimary) }
                        }
                        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(list) { index, code -> ScannedItemRow(index = list.size - index, code = code, onDelete = { list.removeAt(index) }) }
                        }
                    }
                    Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { scanning = !scanning; if (scanning) { scope.launch { focusRequester.requestFocus(); keyboardController?.hide() } } }, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(12.dp)) { Text(if (scanning) "หยุดยิง" else "เริ่มยิง") }
                        Button(onClick = { onSaved(list.toList()) }, enabled = !scanning, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = ColorPrimary)) { Text("ยืนยัน") }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus(); keyboardController?.hide() }
}

@Composable
fun ScannerPulseAnimation(isScanning: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(initialValue = 1f, targetValue = if (isScanning) 1.2f else 1f, animationSpec = infiniteRepeatable(animation = tween(1000), repeatMode = RepeatMode.Reverse), label = "scale")
    val alpha by infiniteTransition.animateFloat(initialValue = 0.5f, targetValue = if (isScanning) 0.1f else 0.5f, animationSpec = infiniteRepeatable(animation = tween(1000), repeatMode = RepeatMode.Reverse), label = "alpha")
    Box(contentAlignment = Alignment.Center) {
        if (isScanning) Box(modifier = Modifier.size(120.dp).scale(scale).clip(CircleShape).background(ColorPrimary.copy(alpha = alpha)))
        Box(modifier = Modifier.size(90.dp).shadow(10.dp, CircleShape, spotColor = ColorPrimary.copy(alpha = 0.2f)).clip(CircleShape).background(Color.White).border(4.dp, if (isScanning) ColorPrimary else Color.Gray.copy(alpha=0.3f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(40.dp), tint = if (isScanning) ColorPrimary else Color.Gray)
        }
    }
}

@Composable
fun ScannedItemRow(index: Int, code: String, onDelete: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ColorBg).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$index.", style = MaterialTheme.typography.bodySmall, color = ColorTextSec, modifier = Modifier.width(24.dp))
        Text(code, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = ColorTextMain, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.weight(1f))
        IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Delete, null, tint = ColorTextSec, modifier = Modifier.size(18.dp)) }
    }
}

private sealed class ScanResult(val message: String, val code: String) {
    class Success(message: String, code: String) : ScanResult(message, code)
    class Error(message: String, code: String) : ScanResult(message, code)
}

// ---------------- Network Logic (SupabaseBatchCommit) ----------------

private object SupabaseBatchCommit {
    private val client = OkHttpClient()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun bearer(accessToken: String?) = accessToken?.takeIf { it.isNotBlank() } ?: SupabaseConfig.ANON_KEY

    private suspend fun http(req: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { res ->
            res.code to (res.body?.string().orEmpty())
        }
    }

    suspend fun commitAll(data: Map<Long, List<String>>, accessToken: String?, branchId: Long, branchName: String, userId: String, userName: String) {
        for ((pid, rfids) in data) {
            if (rfids.isEmpty()) continue
            // ✅ 1. Insert Tags (Handle Duplicates with UPSERT)
            insertTags(pid, rfids, accessToken, branchId)
            // ✅ 2. Add Stock
            addStock(pid, rfids.size.toDouble(), accessToken, branchId)
            // ✅ 3. Movement Log
            insertMovement(productId = pid, qty = rfids.size.toDouble(), accessToken = accessToken, branchId = branchId, branchName = branchName, userId = userId, userName = userName)
        }
    }

    // ✅✅ FIX: ใช้ UPSERT เพื่อแก้ปัญหา Error 409 (Duplicate Key)
    // ถ้ารหัสซ้ำ ให้ Update แทน (ย้ายสาขา/อัปเดตสถานะ)
    private suspend fun insertTags(productId: Long, rfids: List<String>, accessToken: String?, branchId: Long) {
        val arr = JSONArray()
        rfids.forEach { r ->
            arr.put(JSONObject().put("product_id", productId).put("rfid", r).put("status", "IN_STOCK").put("branch_id", branchId))
        }

        // 🔹 เพิ่ม ?on_conflict=rfid ที่ URL
        // 🔹 เพิ่ม Prefer: resolution=merge-duplicates ที่ Header
        val req = Request.Builder()
            .url("${SupabaseConfig.URL}/rest/v1/product_rfid_tags?on_conflict=rfid")
            .post(arr.toString().toRequestBody(jsonMedia))
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "resolution=merge-duplicates,return=minimal") // <--- สำคัญ!
            .build()

        val (code, raw) = http(req)
        if (code !in 200..299) throw IllegalStateException("บันทึกแท็กไม่สำเร็จ ($code) $raw")
    }

    private suspend fun getStockQty(productId: Long, accessToken: String?, branchId: Long): Double? {
        val url = "${SupabaseConfig.URL}/rest/v1/stock?product_id=eq.$productId&branch_id=eq.$branchId&select=qty&limit=1"
        val req = Request.Builder().url(url).get().addHeader("apikey", SupabaseConfig.ANON_KEY).addHeader("Authorization", "Bearer ${bearer(accessToken)}").addHeader("Accept", "application/json").build()
        val (code, raw) = http(req)
        if (code !in 200..299) throw IllegalStateException("อ่าน stock ไม่สำเร็จ ($code) $raw")
        val arr = JSONArray(raw)
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        return when (val v = o.opt("qty")) { is Number -> v.toDouble(); is String -> v.toDoubleOrNull(); else -> null }
    }

    private suspend fun addStock(productId: Long, inc: Double, accessToken: String?, branchId: Long) {
        val old = getStockQty(productId, accessToken, branchId)
        val newQty = (old ?: 0.0) + inc
        val body = JSONObject().put("qty", newQty)
        val builder = if (old == null) {
            body.put("product_id", productId).put("branch_id", branchId)
            Request.Builder().url("${SupabaseConfig.URL}/rest/v1/stock").post(body.toString().toRequestBody(jsonMedia))
        } else {
            Request.Builder().url("${SupabaseConfig.URL}/rest/v1/stock?product_id=eq.$productId&branch_id=eq.$branchId").patch(body.toString().toRequestBody(jsonMedia))
        }
        val req = builder.addHeader("apikey", SupabaseConfig.ANON_KEY).addHeader("Authorization", "Bearer ${bearer(accessToken)}").addHeader("Content-Type", "application/json").addHeader("Prefer", "return=minimal").build()
        val (code, raw) = http(req)
        if (code !in 200..299) throw IllegalStateException("อัปเดต stock ไม่สำเร็จ ($code) $raw")
    }

    private suspend fun insertMovement(productId: Long, qty: Double, accessToken: String?, branchId: Long, branchName: String, userId: String, userName: String) {
        val body = JSONObject().put("product_id", productId.toString()).put("product_id_bigint", productId).put("type", "IN").put("qty", qty).put("branch_id", branchId).put("note", "รับสินค้าเข้าสาขา $branchId ($branchName) [RFID]").put("created_by", userId).put("created_by_name", userName)
        val req = Request.Builder().url("${SupabaseConfig.URL}/rest/v1/stock_movements").post(body.toString().toRequestBody(jsonMedia)).addHeader("apikey", SupabaseConfig.ANON_KEY).addHeader("Authorization", "Bearer ${bearer(accessToken)}").addHeader("Content-Type", "application/json").addHeader("Prefer", "return=minimal").build()
        val (code, raw) = http(req)
        if (code !in 200..299) throw IllegalStateException("บันทึก movement ไม่สำเร็จ ($code) $raw")
    }

    suspend fun clearStockReceiving(accessToken: String?, branchId: Long? = null) {
        var url = "${SupabaseConfig.URL}/rest/v1/stock_receiving?product_id=gt.0"
        if (branchId != null) url += "&branch_id=eq.$branchId"
        val req = Request.Builder().url(url).delete().addHeader("apikey", SupabaseConfig.ANON_KEY).addHeader("Authorization", "Bearer ${bearer(accessToken)}").addHeader("Prefer", "return=minimal").build()
        val (code, raw) = http(req)
        if (code !in 200..299) throw IllegalStateException("ล้าง stock_receiving ไม่สำเร็จ ($code) $raw")
    }
}