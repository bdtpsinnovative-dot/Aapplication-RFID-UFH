package ui

import android.Manifest
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.snapshotFlow
import data.AppError
import data.AuthManager
import data.DraftDatabase
import data.LotSummary
import data.SessionManager
import data.SessionStore
import data.StockLotApi
import data.StockReceivingBrowseApi
import data.StockReceivingItem
import data.SupabaseBatchCommit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat
import kotlin.math.floor

import com.xlzn.hcpda.uhf.UHFReader
import android.hardware.UHFDevice
import com.magicrf.uhfreaderlib.reader.UhfReader as MagicUhfReader
import com.magicrf.uhfreaderlib.reader.Tools

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

    var screenMode by remember { mutableStateOf<RfidScreenMode>(RfidScreenMode.PickMode) }
    var lotPickLoading by remember { mutableStateOf(false) }
    var availableLots by remember { mutableStateOf<List<LotSummary>>(emptyList()) }
    var lotPickError by remember { mutableStateOf<String?>(null) }

    var loading by remember { mutableStateOf(false) }
    var savingAll by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<StockReceivingItem>>(emptyList()) }

    val draftTags = remember { mutableStateMapOf<Long, List<String>>() }
    var rfidDraftLoaded by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<StockReceivingItem?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    val batches = remember { mutableStateListOf<RfidBatch>() }
    var nextBatchNum by remember { mutableIntStateOf(1) }

    val mergedTags by remember {
        derivedStateOf {
            val merged = mutableMapOf<Long, MutableList<String>>()
            batches.forEach { batch ->
                batch.tags.forEach { (pid, tags) ->
                    merged.getOrPut(pid) { mutableListOf() }.addAll(tags)
                }
            }
            draftTags.forEach { (pid, tags) ->
                merged.getOrPut(pid) { mutableListOf() }.addAll(tags)
            }
            merged as Map<Long, List<String>>
        }
    }

    fun reqSlots(qty: Double): Int = floor(qty).toInt().coerceAtLeast(0)
    fun norm(s: String) = s.trim()

    fun load() {
        scope.launch {
            loading = true
            msg = null
            try {
                val currentLotId = (screenMode as? RfidScreenMode.Tagging)?.lotId
                items = AuthManager.withValidToken(ctx) { token ->
                    StockReceivingBrowseApi.fetchAll(token, currentLotId)
                }
            } catch (e: Exception) {
                msg = AppError.resolve(e)
            } finally {
                loading = false
            }
        }
    }

    // ── Hardware state ────────────────────────────────────────────────────────
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

    var onRfidScanned: ((String) -> Unit)? by remember { mutableStateOf(null) }

    fun normalizeToken(raw: String): String = raw.trim().replace(Regex("[^A-Za-z0-9]"), "").uppercase()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        permissionGranted = perms.values.all { it }
        if (!permissionGranted) { hwMsg = "ต้องการ Permission เพื่อเชื่อมต่อ Hardware"; hwError = true }
        else { hwMsg = "กำลังตรวจสอบรุ่นเครื่อง..."; hwError = false }
    }

    LaunchedEffect(Unit) {
        val savedDraft = withContext(Dispatchers.IO) { db.loadRfidDraft(currentBranchId) }
        if (savedDraft.isNotEmpty()) draftTags.putAll(savedDraft)

        val (maxBatchNum, batchMap) = withContext(Dispatchers.IO) { db.loadRfidBatches(currentBranchId) }
        if (batchMap.isNotEmpty()) {
            batches.addAll(batchMap.entries.sortedBy { it.key }.map { (num, tags) -> RfidBatch(num, tags) })
            nextBatchNum = maxBatchNum + 1
        }

        rfidDraftLoaded = true
        permissionLauncher.launch(arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ))
    }

    LaunchedEffect(screenMode) {
        if (screenMode is RfidScreenMode.Tagging) load()
    }

    LaunchedEffect(rfidDraftLoaded) {
        if (!rfidDraftLoaded) return@LaunchedEffect
        snapshotFlow { draftTags.toMap() }.collect { tags ->
            withContext(Dispatchers.IO) { db.saveRfidDraft(tags, currentBranchId) }
        }
    }

    LaunchedEffect(rfidDraftLoaded) {
        if (!rfidDraftLoaded) return@LaunchedEffect
        snapshotFlow { batches.toList() }.collect { current ->
            withContext(Dispatchers.IO) {
                db.saveRfidBatches(current.map { it.batchNum to it.tags }, currentBranchId)
            }
        }
    }

    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            currentDeviceType = when {
                deviceModel.contains("HC", ignoreCase = true) -> DeviceType.HC
                deviceModel.contains("p8", ignoreCase = true) ||
                deviceModel.contains("uhf", ignoreCase = true) ||
                deviceModel.contains("magic", ignoreCase = true) -> DeviceType.P8_MAGICRF
                else -> DeviceType.HC
            }
            withContext(Dispatchers.Main) { hwMsg = "Model: $deviceModel → ลอง ${currentDeviceType.name}..."; hwError = false }

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
                    withContext(Dispatchers.Main) { hwMsg = "ไม่รองรับ Hardware: $deviceModel"; hwError = true }
                }
            }

            if (!hcOk && !isReaderConnected && currentDeviceType == DeviceType.HC) {
                withContext(Dispatchers.Main) { hwMsg = "ไม่พบ Hardware RFID บนเครื่อง: $deviceModel"; hwError = true }
            }
        }
    }

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
            } catch (_: Exception) { }
        }
    }

    LaunchedEffect(scanningOn) {
        if (!isReaderConnected || !scanningOn) return@LaunchedEffect
        try {
            if (currentDeviceType == DeviceType.HC) {
                hcReader?.startInventory()
                try {
                    while (isActive && scanningOn) delay(100)
                } finally {
                    hcReader?.stopInventory()
                }
            } else if (currentDeviceType == DeviceType.P8_MAGICRF) {
                while (isActive && scanningOn) {
                    val epcList = try {
                        withContext(Dispatchers.IO) { p8Reader?.inventoryRealTime() }
                    } catch (_: Exception) { null }
                    if (!isActive || !scanningOn) break
                    epcList?.forEach { epc ->
                        if (epc != null) {
                            val epcStr = Tools.Bytes2HexString(epc, epc.size)
                            scanCh.trySend(normalizeToken(epcStr))
                        }
                    }
                    delay(80)
                }
            }
        } catch (_: Exception) {
            withContext(Dispatchers.Main) { scanningOn = false }
        }
    }

    LaunchedEffect(Unit) {
        for (tag in scanCh) { onRfidScanned?.invoke(tag) }
    }

    // ── Product list & search ─────────────────────────────────────────────────
    val excludedIds = remember { mutableStateSetOf<Long>() }

    val needList = remember(items, excludedIds.size) {
        items.filter { reqSlots(it.qty) > 0 && it.productId !in excludedIds }
    }
    val totalNeed = needList.sumOf { reqSlots(it.qty) }
    val totalHave = needList.sumOf { mergedTags[it.productId]?.size ?: 0 }
    val allFilled = needList.isNotEmpty() && needList.all { itx ->
        (mergedTags[itx.productId]?.size ?: 0) == reqSlots(itx.qty)
    }
    val hasDuplicateInDraft = run {
        val all = mergedTags.values.flatten().map(::norm).filter { it.isNotBlank() }
        all.size != all.toSet().size
    }

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

    LaunchedEffect(screenMode) {
        if (screenMode is RfidScreenMode.Tagging) {
            delay(400)
            try { searchFocusRequester.requestFocus() } catch (_: Exception) {}
            keyboardController?.hide()
        }
    }

    LaunchedEffect(showDialog) {
        if (!showDialog) {
            delay(200)
            try { searchFocusRequester.requestFocus() } catch (_: Exception) {}
            keyboardController?.hide()
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────
    fun doSave() {
        if (!allFilled || hasDuplicateInDraft || savingAll) return
        savingAll = true
        msg = null
        scope.launch {
            try {
                val token = AuthManager.getValidAccessToken(ctx)
                if (token.isNullOrBlank()) throw Exception("กรุณาล็อกอินใหม่")

                val originalLotId = (screenMode as? RfidScreenMode.Tagging)?.lotId
                val prePayload = needList.associate { it.productId to (mergedTags[it.productId] ?: emptyList()) }
                val conflicts = withContext(Dispatchers.IO) {
                    SupabaseBatchCommit.findConflictingTags(prePayload, token)
                }
                if (conflicts.isNotEmpty()) {
                    val lines = conflicts.take(5).joinToString("\n") { (tag, pid) ->
                        val name = needList.find { it.productId == pid }?.name ?: "สินค้า #$pid"
                        "• $tag → $name"
                    }
                    val more = if (conflicts.size > 5) "\n...และอีก ${conflicts.size - 5} รายการ" else ""
                    throw Exception("พบ Tag ซ้ำในระบบ ${conflicts.size} รายการ!\nTag เหล่านี้ถูก assign ให้สินค้าอื่นแล้ว กรุณาตรวจสอบและเปลี่ยน Tag:\n$lines$more")
                }

                val effectiveLotId: Long? = if (originalLotId == null) {
                    val autoLotId = StockLotApi.createAutoLot(
                        branchId = currentBranchId,
                        userId = currentUserId,
                        userName = currentUserName,
                        token = token
                    )
                    val lotItemsMap = needList
                        .associate { it.productId to (mergedTags[it.productId]?.size ?: 0) }
                        .filter { it.value > 0 }
                    StockLotApi.createLotItems(autoLotId, lotItemsMap, token)
                    autoLotId
                } else originalLotId

                val payload = needList.associate { it.productId to (mergedTags[it.productId] ?: emptyList()) }
                SupabaseBatchCommit.commitAll(payload, token, currentBranchId, currentBranchName, currentUserId, currentUserName, effectiveLotId)
                SupabaseBatchCommit.clearStockReceiving(token, currentBranchId, originalLotId)

                if (originalLotId != null) {
                    StockLotApi.updateLotStatus(originalLotId, "SUCCESS", token)
                }

                draftTags.clear()
                batches.clear()
                withContext(Dispatchers.IO) {
                    db.clearRfidDraft(currentBranchId)
                    db.clearRfidBatches(currentBranchId)
                }
                load()
                msg = "บันทึกข้อมูลลงสาขา $currentBranchId เรียบร้อยแล้ว"
            } catch (e: Exception) {
                msg = AppError.resolve(e)
            } finally {
                savingAll = false
            }
        }
    }

    fun saveBatch() {
        if (draftTags.isEmpty()) return
        batches.add(RfidBatch(nextBatchNum, draftTags.toMap()))
        nextBatchNum++
        draftTags.clear()
    }

    // ── Screen routing ────────────────────────────────────────────────────────
    when (screenMode) {
        RfidScreenMode.PickMode -> RfidModePickerScreen(
            onBack = onBack,
            onSelectNoLot = { screenMode = RfidScreenMode.Tagging(null, null) },
            onSelectLot = {
                screenMode = RfidScreenMode.PickLot
                lotPickLoading = true
                lotPickError = null
                scope.launch {
                    try {
                        availableLots = AuthManager.withValidToken(ctx) { token ->
                            StockLotApi.fetchActiveLots(currentBranchId, token, listOf("COMPLETED"))
                        }
                    } catch (e: Exception) {
                        lotPickError = AppError.resolve(e)
                    } finally {
                        lotPickLoading = false
                    }
                }
            }
        )
        RfidScreenMode.PickLot -> RfidLotPickerScreen(
            onBack = { screenMode = RfidScreenMode.PickMode },
            loading = lotPickLoading,
            error = lotPickError,
            lots = availableLots,
            onSelectLot = { lot -> screenMode = RfidScreenMode.Tagging(lot.id, lot.lotCode) }
        )
        is RfidScreenMode.Tagging -> {
            Scaffold(
                containerColor = ColorBg,
                topBar = {
                    ModernTopBar(
                        title = "RFID Tagging",
                        subtitle = run {
                            val t = screenMode as? RfidScreenMode.Tagging
                            if (t?.lotId != null) "ลอต: ${t.lotCode} | สาขา: $currentBranchName"
                            else "ไม่มีลอต | สาขา: $currentBranchName"
                        },
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
                        item { ModernSummaryCard(totalHave = totalHave, totalNeed = totalNeed, hasDuplicate = hasDuplicateInDraft) }

                        item {
                            BatchControlRow(
                                batches = batches,
                                currentDraftCount = draftTags.values.sumOf { it.size },
                                onSaveBatch = { saveBatch() },
                                onDeleteBatch = { idx -> batches.removeAt(idx) },
                                saving = savingAll
                            )
                        }

                        item {
                            BarcodeSearchBar(
                                query = searchQuery,
                                focusRequester = searchFocusRequester,
                                onQueryChange = { searchQuery = it; keyboardController?.hide() },
                                onClear = { searchQuery = "" }
                            )
                        }

                        if (!hwMsg.isNullOrBlank()) {
                            item { HardwareStatusChip(message = hwMsg.orEmpty(), isError = hwError) }
                        }

                        if (!msg.isNullOrBlank()) {
                            item { ModernAlertCard(msg.orEmpty(), isError = msg?.contains("ไม่สำเร็จ") == true) }
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
                                val have = mergedTags[item.productId]?.size ?: 0
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
                                                excludedIds.add(item.productId)
                                                draftTags.remove(item.productId)
                                                withContext(Dispatchers.IO) {
                                                    db.saveRfidDraft(draftTags.toMap(), currentBranchId)
                                                }
                                                msg = "ลบสินค้าออกจากรายการรับเข้าแล้ว"
                                            } catch (e: Exception) {
                                                msg = AppError.resolve(e)
                                            }
                                        }
                                    }
                                )
                            }
                            item { Spacer(Modifier.height(80.dp)) }
                        }
                    }
                }

                if (showDialog && picked != null) {
                    val pid = picked!!.productId
                    val slots = reqSlots(picked!!.qty)
                    val initial = draftTags[pid].orEmpty()

                    val usedOther: Map<String, Pair<String, String?>> = mergedTags.entries
                        .filter { it.key != pid }
                        .flatMap { (prodId, tags) ->
                            val item = needList.find { it.productId == prodId }
                            val name = item?.name ?: "สินค้า #$prodId"
                            val img = item?.imageUrl
                            tags.map { tag -> tag.trim().uppercase() to Pair(name, img) }
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
                        onDismiss = { scanningOn = false; showDialog = false },
                        onSaved = { rfids ->
                            scanningOn = false
                            draftTags[pid] = rfids
                            showDialog = false
                            scope.launch(Dispatchers.IO) {
                                db.saveRfidDraft(draftTags.toMap(), currentBranchId)
                            }
                        }
                    )
                }
            }
        }
    }
}
