package ui

import android.Manifest
import android.util.Log
import android.view.KeyEvent as AndroidKeyEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import coil.compose.AsyncImage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import data.AuthManager
import data.DraftDatabase
import data.SessionManager
import data.SessionStore
import data.StockReceivingBrowseApi
import data.StockReceivingItem
import data.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import androidx.compose.runtime.snapshotFlow
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

// 👵🏼 Import Hardware SDK ของทั้ง 2 รุ่น
import com.xlzn.hcpda.uhf.UHFReader
import android.hardware.UHFDevice
import com.magicrf.uhfreaderlib.reader.UhfReader as MagicUhfReader
import com.magicrf.uhfreaderlib.reader.Tools

// --- Constants & Config ---
private const val BURST_GUARD_MS = 180L
private const val IDLE_FINALIZE_MS = 90L
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val qtyFmt = remember { DecimalFormat("#,##0.###") }

    val currentBranchId = remember { SessionStore.getBranchId(ctx) }
    val currentBranchName = remember { SessionStore.getBranchName(ctx) }
    val currentUserId = remember { SessionManager.userId ?: SessionStore.getUserId(ctx) }
    val currentUserName = remember { SessionStore.getDisplayName(ctx) }

    val db = remember { DraftDatabase(ctx) }

    var loading by remember { mutableStateOf(false) }
    var savingAll by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<StockReceivingItem>>(emptyList()) }

    val draftTags = remember { mutableStateMapOf<Long, List<String>>() }
    var rfidDraftLoaded by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<StockReceivingItem?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    fun reqSlots(qty: Double): Int = floor(qty).toInt().coerceAtLeast(0)
    fun norm(s: String) = s.trim()

    fun load() {
        scope.launch {
            loading = true
            msg = null
            try {
                val token = AuthManager.getValidAccessToken(ctx)
                if (token.isNullOrBlank()) throw Exception("กรุณาล็อกอินใหม่")
                items = StockReceivingBrowseApi.fetchAll(token)
            } catch (e: Exception) {
                msg = e.message ?: "โหลดข้อมูลไม่สำเร็จ"
            } finally {
                loading = false
            }
        }
    }

    // -------------------------------------------------------------------------
    // 👵🏼 HARDWARE WRAPPER LOGIC (ลอจิกเชื่อมต่อเครื่องยิง 2 รุ่น)
    // -------------------------------------------------------------------------
    var isReaderConnected by remember { mutableStateOf(false) }
    var hcReader by remember { mutableStateOf<UHFReader?>(null) }
    var p8Device by remember { mutableStateOf<UHFDevice?>(null) }
    var p8Reader by remember { mutableStateOf<MagicUhfReader?>(null) }
    var currentDeviceType by remember { mutableStateOf(DeviceType.UNKNOWN) }
    val deviceModel = remember { android.os.Build.MODEL }

    var permissionGranted by remember { mutableStateOf(false) }
    var scanningOn by remember { mutableStateOf(false) }
    var hwMsg by remember { mutableStateOf<String?>("Model: ${android.os.Build.MODEL} | Mfr: ${android.os.Build.MANUFACTURER}") }
    var hwError by remember { mutableStateOf(false) }
    val scanCh = remember { Channel<String>(capacity = 4096) }

    // Callback สำหรับส่งโค้ดที่สแกนได้เข้าไปใน Dialog
    var onRfidScanned: ((String) -> Unit)? by remember { mutableStateOf(null) }

    fun normalizeToken(raw: String): String = raw.trim().replace(Regex("[^A-Za-z0-9]"), "").uppercase()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        permissionGranted = perms.values.all { it }
        if (!permissionGranted) { hwMsg = "ต้องการ Permission เพื่อเชื่อมต่อ Hardware"; hwError = true }
        else { hwMsg = "กำลังตรวจสอบรุ่นเครื่อง..."; hwError = false }
    }

    LaunchedEffect(Unit) {
        // โหลด RFID draft ก่อน แล้วค่อย set flag
        val savedDraft = withContext(Dispatchers.IO) { db.loadRfidDraft(currentBranchId) }
        if (savedDraft.isNotEmpty()) draftTags.putAll(savedDraft)
        rfidDraftLoaded = true  // สัญญาณให้ auto-save เริ่มได้

        load()
        permissionLauncher.launch(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE))
    }

    // Auto-save RFID draft — เริ่มหลัง rfidDraftLoaded=true เท่านั้น
    LaunchedEffect(rfidDraftLoaded) {
        if (!rfidDraftLoaded) return@LaunchedEffect
        snapshotFlow { draftTags.toMap() }
            .collect { tags ->
                withContext(Dispatchers.IO) { db.saveRfidDraft(tags, currentBranchId) }
            }
    }

    // Init Hardware — ลอง HC ก่อน (retry 3 ครั้ง) ถ้าไม่ได้ค่อยลอง MagicRF
    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) return@LaunchedEffect
        withContext(Dispatchers.IO) {

            currentDeviceType = when {
                deviceModel.contains("HC", ignoreCase = true) -> DeviceType.HC
                deviceModel.contains("p8", ignoreCase = true) ||
                deviceModel.contains("uhf", ignoreCase = true) ||
                deviceModel.contains("magic", ignoreCase = true) -> DeviceType.P8_MAGICRF
                else -> DeviceType.HC // fallback ลอง HC ก่อน
            }

            withContext(Dispatchers.Main) {
                hwMsg = "Model: $deviceModel → ลอง ${currentDeviceType.name}..."; hwError = false
            }

            // ลอง HC (retry 3 ครั้ง เหมือน StockCountScreen)
            var hcOk = false
            if (currentDeviceType == DeviceType.HC) {
                var retry = 0
                while (retry < 3 && !hcOk) {
                    try {
                        val reader = UHFReader.getInstance()
                        if (reader == null) {
                            withContext(Dispatchers.Main) { hwMsg = "ไม่พบ Hardware HC"; hwError = true }
                            break
                        }
                        hcReader = reader
                        delay(1000)
                        if (reader.connect(ctx)?.data == true) {
                            delay(500)
                            reader.setPower(30)
                            reader.setOnInventoryDataListener { tagsList ->
                                if (!tagsList.isNullOrEmpty()) {
                                    for (tag in tagsList) {
                                        val rfid = tag.ecpHex
                                        if (!rfid.isNullOrEmpty()) scanCh.trySend(normalizeToken(rfid))
                                    }
                                }
                            }
                            isReaderConnected = true
                            hcOk = true
                            withContext(Dispatchers.Main) { hwMsg = "พร้อมใช้งาน (HC | $deviceModel)"; hwError = false }
                        } else {
                            retry++
                            withContext(Dispatchers.Main) { hwMsg = "เชื่อมต่อ HC ไม่สำเร็จ (Retry $retry)..."; hwError = true }
                            delay(1500)
                        }
                    } catch (t: Throwable) {
                        Log.e("UHF", "HC Init Error: ${t.message}", t)
                        retry++
                        withContext(Dispatchers.Main) { hwMsg = "HC Error: ${t.localizedMessage}"; hwError = true }
                        delay(1500)
                    }
                }
            }

            // ลอง MagicRF เฉพาะตอนที่ระบุ device เป็น P8 ตั้งแต่แรก หรือ HC ไม่มี hardware เลย
            if (!hcOk && currentDeviceType == DeviceType.P8_MAGICRF) {
                try {
                    withContext(Dispatchers.Main) { hwMsg = "กำลังเชื่อมต่อ MagicRF..."; hwError = false }
                    val device = UHFDevice(ctx)
                    device.UhfOpen()
                    MagicUhfReader.setPortPath(device.SerialDev())
                    val reader = MagicUhfReader.getInstance()
                    if (reader != null) {
                        p8Device = device
                        p8Reader = reader
                        isReaderConnected = true
                        withContext(Dispatchers.Main) { hwMsg = "พร้อมใช้งาน (MagicRF | $deviceModel)"; hwError = false }
                    } else {
                        withContext(Dispatchers.Main) { hwMsg = "SerialPort Init ไม่สำเร็จ"; hwError = true }
                    }
                } catch (t: Throwable) {
                    Log.e("UHF", "MagicRF Init Error: ${t.message}", t)
                    currentDeviceType = DeviceType.UNKNOWN
                    withContext(Dispatchers.Main) {
                        hwMsg = "ไม่รองรับ Hardware: $deviceModel"; hwError = true
                    }
                }
            }

            // HC ถูก fallback มาแต่ connect ไม่ได้ (ไม่ใช่ crash แค่ไม่มี hardware HC)
            if (!hcOk && !isReaderConnected && currentDeviceType == DeviceType.HC) {
                withContext(Dispatchers.Main) {
                    hwMsg = "ไม่พบ Hardware RFID บนเครื่อง: $deviceModel"; hwError = true
                }
            }
        }
    }

    // Hardware Cleanup
    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                scanningOn = false
                if (currentDeviceType == DeviceType.HC) {
                    hcReader?.stopInventory()
                    hcReader?.disConnect()
                } else if (currentDeviceType == DeviceType.P8_MAGICRF) {
                    p8Reader?.close()
                    p8Device?.UhfStop()
                }
            } catch (e: Exception) { }
        }
    }

    // Hardware Scan Loop (เฉพาะเครื่องที่ต้อง Polling อย่าง P8)
    LaunchedEffect(scanningOn) {
        if (!isReaderConnected || !scanningOn) {
            if (currentDeviceType == DeviceType.HC && !scanningOn) hcReader?.stopInventory()
            return@LaunchedEffect
        }

        try {
            if (currentDeviceType == DeviceType.HC) {
                hcReader?.startInventory()
            } else if (currentDeviceType == DeviceType.P8_MAGICRF) {
                while (scanningOn) {
                    withContext(Dispatchers.IO) {
                        val epcList = p8Reader?.inventoryRealTime()
                        if (epcList != null && epcList.isNotEmpty()) {
                            for (epc in epcList) {
                                if (epc != null) {
                                    val epcStr = Tools.Bytes2HexString(epc, epc.size)
                                    scanCh.trySend(normalizeToken(epcStr))
                                }
                            }
                        }
                        delay(80)
                    }
                }
            }
        } catch (e: Exception) { scanningOn = false }
    }

    // รับข้อมูลจาก Channel แล้วส่งให้ Callback ของ Dialog
    LaunchedEffect(Unit) {
        for (tag in scanCh) {
            onRfidScanned?.invoke(tag)
        }
    }
    // -------------------------------------------------------------------------

    // productId ที่ user ลบออกจาก session นี้ (ไม่ส่ง server ไม่ save SQLite)
    val excludedIds = remember { mutableStateSetOf<Long>() }

    val needList = remember(items, excludedIds.size) {
        items.filter { reqSlots(it.qty) > 0 && it.productId !in excludedIds }
    }
    val totalNeed = needList.sumOf { reqSlots(it.qty) }
    val totalHave = needList.sumOf { draftTags[it.productId]?.size ?: 0 }
    val allFilled = needList.isNotEmpty() && needList.all { itx ->
        (draftTags[itx.productId]?.size ?: 0) == reqSlots(itx.qty)
    }

    val hasDuplicateInDraft = run {
        val all = draftTags.values.flatten().map(::norm).filter { it.isNotBlank() }
        all.size != all.toSet().size
    }

    // ── Barcode search (ปืนยิงบาร์โค้ด) ─────────────────────────────────────
    var searchQuery by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val filteredList = remember(needList, searchQuery) {
        if (searchQuery.isBlank()) needList
        else needList.filter { item ->
            item.barcode?.contains(searchQuery, ignoreCase = true) == true ||
            item.sku?.contains(searchQuery, ignoreCase = true) == true ||
            item.name?.contains(searchQuery, ignoreCase = true) == true
        }
    }

    // focus ครั้งแรกตอนเปิดหน้า
    LaunchedEffect(searchFocusRequester) {
        delay(600)
        try { searchFocusRequester.requestFocus() } catch (_: Exception) {}
        keyboardController?.hide()
    }

    // คืน focus ให้ main screen ทันทีที่ปิด dialog
    LaunchedEffect(showDialog) {
        if (!showDialog) {
            delay(200)
            try { searchFocusRequester.requestFocus() } catch (_: Exception) {}
            keyboardController?.hide()
        }
    }

    fun doSave() {
        if (!allFilled || hasDuplicateInDraft || savingAll) return
        savingAll = true
        msg = null
        scope.launch {
            try {
                val token = AuthManager.getValidAccessToken(ctx)
                if (token.isNullOrBlank()) throw Exception("กรุณาล็อกอินใหม่")
                val payload = needList.associate { it.productId to (draftTags[it.productId] ?: emptyList()) }
                SupabaseBatchCommit.commitAll(payload, token, currentBranchId, currentBranchName, currentUserId, currentUserName)
                SupabaseBatchCommit.clearStockReceiving(token, currentBranchId)
                draftTags.clear()
                // ล้าง RFID draft เพราะบันทึกเข้าระบบแล้ว
                withContext(Dispatchers.IO) { db.clearRfidDraft(currentBranchId) }
                load()
                msg = "บันทึกข้อมูลลงสาขา $currentBranchId เรียบร้อยแล้ว"
            } catch (e: Exception) {
                msg = e.message ?: "บันทึกไม่สำเร็จ"
            } finally {
                savingAll = false
            }
        }
    }

    Scaffold(
        containerColor = ColorBg,
        topBar = {
            ModernTopBar(
                title = "RFID Tagging",
                subtitle = "สาขา: $currentBranchName",
                onBack = onBack,
                onRefresh = { load() },
                loading = loading || savingAll,
                onClearAll = if (draftTags.isNotEmpty() && !savingAll) {
                    {
                        draftTags.clear()
                        scope.launch(Dispatchers.IO) { db.clearRfidDraft(currentBranchId) }
                    }
                } else null
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
                    onSave = { doSave() }
                )
            }
        }
    ) { pad ->
        Box(modifier = Modifier.padding(pad).fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                ModernSummaryCard(totalHave = totalHave, totalNeed = totalNeed, hasDuplicate = hasDuplicateInDraft)
            }

            item {
                BarcodeSearchBar(
                    query = searchQuery,
                    focusRequester = searchFocusRequester,
                    onQueryChange = { searchQuery = it; keyboardController?.hide() },
                    onClear = { searchQuery = "" }
                )
            }

            // Hardware status chip
            if (!hwMsg.isNullOrBlank()) {
                item {
                    HardwareStatusChip(message = hwMsg.orEmpty(), isError = hwError)
                }
            }

            // Operation message banner
            if (!msg.isNullOrBlank()) {
                item {
                    ModernAlertCard(msg.orEmpty(), isError = msg?.contains("ไม่สำเร็จ") == true)
                }
            }

            if (loading && needList.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ColorPrimary)
                    }
                }
            } else if (needList.isEmpty()) {
                item { EmptyStateView(onRefresh = { load() }) }
            } else {
                item {
                    Text(
                        if (searchQuery.isBlank()) "รายการสินค้า (${needList.size})"
                        else "ผลค้นหา (${filteredList.size}/${needList.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = ColorTextMain,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                }
                if (filteredList.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                            Text("ไม่พบสินค้า \"$searchQuery\"", style = MaterialTheme.typography.bodyMedium, color = ColorTextSec)
                        }
                    }
                }
                itemsIndexed(filteredList) { _, item ->
                    val need = reqSlots(item.qty)
                    val have = draftTags[item.productId]?.size ?: 0
                    ModernItemCard(
                        item = item,
                        code = item.barcode ?: item.sku,
                        qtyText = qtyFmt.format(item.qty),
                        have = have,
                        need = need,
                        enabled = !savingAll,
                        imageUrl = item.imageUrl,
                        onClick = { picked = item; showDialog = true },
                        onDelete = {
                            scope.launch {
                                try {
                                    val token = AuthManager.getValidAccessToken(ctx)
                                    if (token.isNullOrBlank()) throw Exception("กรุณาล็อกอินใหม่")
                                    withContext(Dispatchers.IO) {
                                        SupabaseBatchCommit.deleteProduct(item.productId, currentBranchId, token)
                                    }
                                    // ลบสำเร็จ — ซ่อนออกจาก list + ล้าง tag draft
                                    excludedIds.add(item.productId)
                                    draftTags.remove(item.productId)
                                    withContext(Dispatchers.IO) {
                                        db.saveRfidDraft(draftTags.toMap(), currentBranchId)
                                    }
                                    msg = "ลบสินค้าออกจากรายการรับเข้าแล้ว"
                                } catch (e: Exception) {
                                    msg = "ลบไม่สำเร็จ: ${e.message}"
                                }
                            }
                        }
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
        } // end outer Box
    }

    if (showDialog && picked != null) {
        val pid = picked!!.productId
        val slots = reqSlots(picked!!.qty)
        val initial = draftTags[pid].orEmpty()

        // Map: tag → ชื่อสินค้าที่ครอบครอง tag นั้นอยู่
        val usedOther: Map<String, String> = draftTags.entries
            .filter { it.key != pid }
            .flatMap { (prodId, tags) ->
                val name = needList.find { it.productId == prodId }?.name ?: "สินค้า #$prodId"
                tags.map { tag -> tag.trim().uppercase() to name }
            }
            .filter { it.first.isNotBlank() }
            .toMap()

        ModernRfidDialog(
            title = picked!!.name ?: "Product #$pid",
            subtitle = picked!!.barcode ?: picked!!.sku ?: "-",
            slots = slots,
            initial = initial,
            usedOther = usedOther,
            isScanning = scanningOn,
            isReaderConnected = isReaderConnected,
            onToggleScan = {
                if (isReaderConnected) scanningOn = it
                else Toast.makeText(ctx, "เครื่องสแกนยังไม่พร้อมใช้งาน", Toast.LENGTH_SHORT).show()
            },
            setScanCallback = { cb -> onRfidScanned = cb },
            onRemoveFromOther = { tag ->
                for ((prodId, tags) in draftTags) {
                    if (tag in tags) {
                        draftTags[prodId] = tags.filter { it != tag }
                        break
                    }
                }
                scope.launch(Dispatchers.IO) { db.saveRfidDraft(draftTags.toMap(), currentBranchId) }
            },
            onDismiss = {
                scanningOn = false
                showDialog = false
            },
            onSaved = { rfids ->
                scanningOn = false
                draftTags[pid] = rfids
                showDialog = false
                // save ทันทีไม่รอ snapshotFlow — ป้องกันกดออกก่อน coroutine ทำงาน
                scope.launch(Dispatchers.IO) {
                    db.saveRfidDraft(draftTags.toMap(), currentBranchId)
                }
            }
        )
    }
}

@Composable
fun BarcodeSearchBar(
    query: String,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var scanBuffer by remember { mutableStateOf("") }
    var idleJob by remember { mutableStateOf<Job?>(null) }

    Box(modifier = Modifier.fillMaxWidth()) {
        // Hidden BasicTextField — value="" เสมอ ปืนส่งทีละตัว เราสะสมเองใน scanBuffer
        BasicTextField(
            value = "",
            onValueChange = { v ->
                val newChars = v.filter { it.isLetterOrDigit() || it == '-' }
                if (newChars.isEmpty()) return@BasicTextField
                scanBuffer += newChars
                onQueryChange(scanBuffer)
                keyboardController?.hide()
                // รีเซ็ต buffer หลังหยุดยิง 400ms → พร้อมรับบาร์โค้ดถัดไป
                idleJob?.cancel()
                idleJob = scope.launch {
                    delay(400)
                    scanBuffer = ""
                }
            },
            modifier = Modifier
                .focusRequester(focusRequester)
                .size(1.dp)
                .alpha(0.01f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search
            ),
            keyboardActions = KeyboardActions(
                onSearch = { keyboardController?.hide() }
            )
        )
        // Visual bar ที่แสดงผล
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.5.dp, if (query.isNotBlank()) ColorPrimary.copy(0.5f) else Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.QrCodeScanner, null, tint = if (query.isNotBlank()) ColorPrimary else ColorTextSec, modifier = Modifier.size(20.dp))
            Text(
                text = if (query.isBlank()) "ยิงบาร์โค้ด / SKU เพื่อค้นหา" else query,
                style = MaterialTheme.typography.bodyMedium,
                color = if (query.isBlank()) ColorTextSec else ColorTextMain,
                fontFamily = if (query.isBlank()) null else FontFamily.Monospace,
                fontWeight = if (query.isBlank()) FontWeight.Normal else FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (query.isNotBlank()) {
                IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, null, tint = ColorTextSec, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    loading: Boolean,
    onClearAll: (() -> Unit)? = null
) {
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
            if (onClearAll != null) {
                IconButton(onClick = onClearAll) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "ล้าง tag ทั้งหมด", tint = Color(0xFFEF4444))
                }
            }
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp).padding(2.dp), strokeWidth = 2.dp, color = ColorPrimary)
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh", tint = ColorTextMain)
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
    val progressColor = if (hasDuplicate) Color.Red else ColorPrimary

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.shadow(6.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(0.05f), spotColor = Color.Black.copy(0.05f))
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // circular progress เล็กลง
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp)) {
                    CircularProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxSize(), color = progressColor, strokeWidth = 4.dp, trackColor = ColorPrimarySoft, strokeCap = StrokeCap.Round)
                    Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = ColorTextMain)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("ภาพรวมการสแกน", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = ColorTextMain)
                    if (hasDuplicate)
                        Text("พบ Tag ซ้ำ! กรุณาตรวจสอบ", style = MaterialTheme.typography.labelSmall, color = Color.Red)
                    else
                        Text("เสร็จแล้ว $totalHave / ทั้งหมด $totalNeed", style = MaterialTheme.typography.labelSmall, color = ColorTextSec)
                }
                // ตัวเลขขวา
                Text(
                    "$totalHave/$totalNeed",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (hasDuplicate) Color.Red else ColorPrimary
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)),
                color = progressColor,
                trackColor = ColorPrimarySoft
            )
        }
    }
}

@Composable
fun HardwareStatusChip(message: String, isError: Boolean) {
    val bg = if (isError) Color(0xFFFEF2F2) else Color(0xFFF0FDF4)
    val tint = if (isError) Color(0xFFEF4444) else Color(0xFF15803D)
    val icon = if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Text(message, style = MaterialTheme.typography.labelMedium, color = tint, modifier = Modifier.weight(1f))
    }
}

@Composable
fun ModernItemCard(
    item: StockReceivingItem,
    code: String?,
    qtyText: String,
    have: Int,
    need: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    imageUrl: String? = null
) {
    val isComplete = have >= need && need > 0
    val progress = if (need > 0) (have.toFloat() / need.toFloat()).coerceIn(0f, 1f) else 0f
    val animProgress by animateFloatAsState(progress, label = "itemProgress")
    var showConfirmDelete by remember { mutableStateOf(false) }
    var showImageUrl by remember { mutableStateOf<String?>(null) }

    // Fullscreen image viewer dialog
    if (showImageUrl != null) {
        Dialog(
            onDismissRequest = { showImageUrl = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f))
                    .clickable { showImageUrl = null },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = showImageUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                IconButton(
                    onClick = { showImageUrl = null },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = if (isComplete) BorderStroke(1.dp, ColorSuccess.copy(alpha = 0.4f)) else null,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(12.dp), ambientColor = Color.Black.copy(0.03f), spotColor = Color.Black.copy(0.03f))
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Thumbnail รูปสินค้า 40×40dp
                val fullImageUrl = if (!imageUrl.isNullOrBlank()) {
                    if (imageUrl.startsWith("http")) imageUrl
                    else "https://zexflchjcycxrpjkuews.supabase.co/storage/v1/object/public/product-images/$imageUrl"
                } else null
                if (fullImageUrl != null) {
                    AsyncImage(
                        model = fullImageUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showImageUrl = fullImageUrl },
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFE2E8F0))
                    )
                }
                Spacer(Modifier.width(8.dp))
                // สี indicator แถบซ้าย
                Box(
                    modifier = Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(50))
                        .background(if (isComplete) ColorSuccess else if (have > 0) ColorWarning else ColorPrimary.copy(0.3f))
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.name ?: "Unknown Product",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = ColorTextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        code ?: "-",
                        style = MaterialTheme.typography.labelSmall,
                        color = ColorTextSec,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.width(6.dp))
                // badge จำนวน
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when {
                        isComplete -> ColorSuccess
                        have > 0 -> ColorWarning
                        else -> ColorBg
                    }
                ) {
                    Text(
                        "$have/$need",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (have > 0 || isComplete) Color.White else ColorTextSec
                    )
                }
                // ปุ่มลบ
                if (onDelete != null && enabled) {
                    IconButton(onClick = { showConfirmDelete = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Delete, null, tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                    }
                }
            }
            // progress bar얇얇 3dp
            LinearProgressIndicator(
                progress = { animProgress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = if (isComplete) ColorSuccess else if (have > 0) ColorWarning else ColorPrimary,
                trackColor = ColorBg
            )
        }
    }

    // Confirm dialog ก่อนลบ
    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            icon = {
                Icon(Icons.Outlined.Delete, null, tint = Color(0xFFEF4444), modifier = Modifier.size(28.dp))
            },
            title = {
                Text("ลบสินค้าออกจากรายการ?", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            },
            text = {
                Text(
                    "\"${item.name ?: "สินค้า #${item.productId}"}\" จะถูกนำออกจาก session นี้\nข้อมูล Tag ที่ยิงไว้จะถูกล้างด้วย",
                    textAlign = TextAlign.Center,
                    color = ColorTextSec
                )
            },
            confirmButton = {
                Button(
                    onClick = { showConfirmDelete = false; onDelete?.invoke() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("ลบออก", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDelete = false }) {
                    Text("ยกเลิก")
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
fun ModernBottomBar(totalHave: Int, totalNeed: Int, allFilled: Boolean, hasDuplicate: Boolean, savingAll: Boolean, onSave: () -> Unit) {
    val enabled = allFilled && !hasDuplicate && !savingAll
    Surface(color = Color.White, shadowElevation = 16.dp, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (allFilled) "ครบถ้วนพร้อมบันทึก" else "กำลังดำเนินการ...",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (allFilled) ColorSuccess else ColorTextSec
                )
                Text(
                    "$totalHave / $totalNeed Tag",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (allFilled) ColorSuccess else ColorTextMain
                )
            }
            Button(
                onClick = onSave,
                enabled = enabled,
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ColorPrimary, disabledContainerColor = Color.Gray.copy(0.2f))
            ) {
                if (savingAll) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else {
                    Icon(Icons.Outlined.Save, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("บันทึกเข้าระบบ", fontWeight = FontWeight.Bold)
                }
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
fun ModernRfidDialog(
    title: String,
    subtitle: String?,
    slots: Int,
    initial: List<String>,
    usedOther: Map<String, String>,   // tag (uppercase) → ชื่อสินค้า
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
                onToggleScan(false) // ครบปุ๊บ หยุดเครื่องยิงให้เลย
            }
            localSet.contains(code) -> { lastResult = ScanResult.Error("ซ้ำในรายการนี้", code); haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
            usedOtherNorm.containsKey(code) -> {
                val owner = usedOtherNorm[code] ?: "สินค้าอื่น"
                lastResult = ScanResult.Error("ซ้ำกับ: $owner", code)
                conflictTag = code
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

    // ผูก Callback เพื่อให้ Hardware ที่ยิงได้ วิ่งเข้าฟังก์ชัน submitCode
    // key = isScanning เพื่อให้ re-register callback ทุกครั้งที่ toggle scan
    DisposableEffect(isScanning) {
        if (isScanning) {
            setScanCallback { tag -> submitCode(tag) }
        }
        onDispose { setScanCallback(null) }
    }

    val keyEventModifier = Modifier
        .focusRequester(focusRequester)
        .focusable()
        .onPreviewKeyEvent { ev ->
            if (ev.type == KeyEventType.KeyDown && isScanning) {
                val char = ev.nativeKeyEvent.unicodeChar.toChar()
                val isEnter = ev.nativeKeyEvent.keyCode in listOf(AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER)
                when {
                    isEnter -> {
                        if (scanBuffer.isNotBlank()) { submitCode(scanBuffer); scanBuffer = "" }
                        true
                    }
                    char.isLetterOrDigit() -> {
                        scanBuffer += char
                        idleJob?.cancel()
                        idleJob = scope.launch { delay(IDLE_FINALIZE_MS); if (scanBuffer.isNotBlank()) { submitCode(scanBuffer); scanBuffer = "" } }
                        true
                    }
                    else -> false
                }
            } else false
        }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
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

                    // ── Header ──────────────────────────────────────────────
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

                    // ── Scan zone (compact) ──────────────────────────────────
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
                                if (isScanning) "กำลังรับสแกน..." else if (!isReaderConnected) "เครื่องไม่พร้อม" else "พร้อมยิง RFID",
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
                                        // ถ้า tag ที่ยิงเกิน slots ใหม่ ให้ตัดออกจากท้ายสุด
                                        while (list.size > editableSlots) list.removeAt(list.size - 1)
                                        lastResult = null
                                    }
                                    if (isScanning) refocus()
                                },
                                modifier = Modifier.size(30.dp)
                            ) { Icon(Icons.Default.Remove, null, tint = if (editableSlots > 1) ColorPrimary else Color.Gray, modifier = Modifier.size(16.dp)) }
                            Text("$editableSlots", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ColorTextMain, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 32.dp))
                            IconButton(
                                onClick = { editableSlots++; if (list.size < editableSlots) onToggleScan(true); refocus() },
                                modifier = Modifier.size(30.dp)
                            ) { Icon(Icons.Default.Add, null, tint = ColorPrimary, modifier = Modifier.size(16.dp)) }
                        }
                    }

                    // ── Scan result toast ────────────────────────────────────
                    AnimatedVisibility(
                        visible = lastResult != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        lastResult?.let { res ->
                            val isErr = res is ScanResult.Error
                            val isConflict = isErr && conflictTag != null
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 6.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isErr) Color(0xFFFEF2F2) else Color(0xFFF0FDF4))
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(if (isErr) Icons.Default.Warning else Icons.Default.CheckCircle, null, tint = if (isErr) Color(0xFFEF4444) else ColorSuccess, modifier = Modifier.size(18.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(res.message, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (isErr) Color(0xFFEF4444) else ColorSuccess)
                                    Text(res.code, style = MaterialTheme.typography.bodySmall, color = ColorTextSec)
                                }
                                if (isConflict) {
                                    TextButton(
                                        onClick = {
                                            conflictTag?.let { tag ->
                                                onRemoveFromOther(tag)
                                                // ยิงเข้า list ปัจจุบันทันทีหลังลบออกจากสินค้าอื่น
                                                if (!localSet.contains(tag) && list.size < editableSlots) {
                                                    list.add(0, tag)
                                                    lastResult = ScanResult.Success("ย้ายมาแล้ว", tag)
                                                } else {
                                                    lastResult = null
                                                }
                                                conflictTag = null
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
                                    ) {
                                        Text("ลบออก", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // ── Scanned list ─────────────────────────────────────────
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

                    // ── Action buttons ───────────────────────────────────────
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
                            Text(if (list.size >= editableSlots) "ยืนยัน ✓" else "ยืนยัน (${list.size})", fontWeight = FontWeight.Bold)
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
fun ScannerPulseAnimation(isScanning: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(1f, if (isScanning) 1.35f else 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scale")
    val alpha by infiniteTransition.animateFloat(0.4f, if (isScanning) 0.05f else 0.4f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(52.dp)) {
        if (isScanning) {
            Box(modifier = Modifier.size(52.dp).scale(scale).clip(CircleShape).background(ColorPrimary.copy(alpha = alpha)))
        }
        Box(
            modifier = Modifier.size(44.dp).shadow(6.dp, CircleShape, spotColor = ColorPrimary.copy(0.15f)).clip(CircleShape).background(Color.White).border(2.5.dp, if (isScanning) ColorPrimary else Color.Gray.copy(0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(22.dp), tint = if (isScanning) ColorPrimary else Color.Gray)
        }
    }
}

@Composable
fun ScannedItemRow(index: Int, code: String, onDelete: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ColorBg).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$index.", style = MaterialTheme.typography.bodySmall, color = ColorTextSec, modifier = Modifier.width(24.dp))
        Text(code, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = ColorTextMain, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
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
            insertTags(pid, rfids, accessToken, branchId)
            addStock(pid, rfids.size.toDouble(), accessToken, branchId)
            insertMovement(productId = pid, qty = rfids.size.toDouble(), accessToken = accessToken, branchId = branchId, branchName = branchName, userId = userId, userName = userName)
        }
    }

    private suspend fun insertTags(productId: Long, rfids: List<String>, accessToken: String?, branchId: Long) {
        val arr = JSONArray()
        rfids.forEach { r ->
            arr.put(JSONObject().put("product_id", productId).put("rfid", r).put("status", "IN_STOCK").put("branch_id", branchId))
        }

        val req = Request.Builder()
            .url("${SupabaseConfig.URL}/rest/v1/product_rfid_tags?on_conflict=rfid")
            .post(arr.toString().toRequestBody(jsonMedia))
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
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
        val rpcBody = JSONObject()
            .put("p_product_id", productId)
            .put("p_branch_id", branchId)
            .put("p_delta", inc)
        val rpcReq = Request.Builder()
            .url("${SupabaseConfig.URL}/rest/v1/rpc/increment_stock")
            .post(rpcBody.toString().toRequestBody(jsonMedia))
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
            .addHeader("Content-Type", "application/json")
            .build()
        val (rpcCode, _) = http(rpcReq)
        if (rpcCode in 200..299) return

        for (attempt in 0..2) {
            val old = getStockQty(productId, accessToken, branchId)
            val newQty = (old ?: 0.0) + inc
            val body = JSONObject().put("qty", newQty)
            val builder = if (old == null) {
                body.put("product_id", productId).put("branch_id", branchId)
                Request.Builder()
                    .url("${SupabaseConfig.URL}/rest/v1/stock")
                    .post(body.toString().toRequestBody(jsonMedia))
                    .addHeader("Prefer", "resolution=ignore-duplicates,return=minimal")
            } else {
                Request.Builder()
                    .url("${SupabaseConfig.URL}/rest/v1/stock?product_id=eq.$productId&branch_id=eq.$branchId&qty=eq.$old")
                    .patch(body.toString().toRequestBody(jsonMedia))
                    .addHeader("Prefer", "return=representation")
            }
            val req = builder
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
                .addHeader("Content-Type", "application/json")
                .build()
            val (code, raw) = http(req)
            if (code !in 200..299) throw IllegalStateException("อัปเดต stock ไม่สำเร็จ ($code) $raw")
            val updated = old == null || try { JSONArray(raw).length() > 0 } catch (_: Exception) { true }
            if (updated) return
            if (attempt < 2) kotlinx.coroutines.delay(80L * (attempt + 1))
        }
        throw IllegalStateException("อัปเดต stock ไม่สำเร็จ หลังลอง 3 ครั้ง (concurrent conflict)")
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

    suspend fun deleteProduct(productId: Long, branchId: Long, accessToken: String?) {
        val url = "${SupabaseConfig.URL}/rest/v1/stock_receiving?product_id=eq.$productId&branch_id=eq.$branchId"
        val req = Request.Builder().url(url).delete()
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
            .addHeader("Prefer", "return=minimal")
            .build()
        val (code, raw) = http(req)
        if (code !in 200..299) throw IllegalStateException("ลบสินค้าออกจากระบบไม่สำเร็จ ($code) $raw")
    }
}