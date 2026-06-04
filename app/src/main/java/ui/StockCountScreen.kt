package ui

import android.Manifest
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import data.SessionStore
import data.AppError
import data.AuthManager
import data.DraftDatabase
import data.ProductDatabase
import data.FoundTag
import data.GroupRow
import data.SupabaseBatchCheckApi
import data.SupabaseReaderStockApi

import com.xlzn.hcpda.uhf.UHFReader
import android.hardware.UHFDevice
import com.magicrf.uhfreaderlib.reader.UhfReader as MagicUhfReader
import com.magicrf.uhfreaderlib.reader.Tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val StockColorBg = Color(0xFFF1F5F9)
private val StockColorPrimary = Color(0xFF6366F1)
private val StockColorSuccess = Color(0xFF22C55E)
private val StockColorWarning = Color(0xFFF59E0B)
private val StockColorTextMain = Color(0xFF1E293B)
private val StockColorSurface = Color(0xFFFFFFFF)
private val StockColorError = Color(0xFFEF4444)
private val StockColorTextSub = Color(0xFF64748B)
private val StockGradientHeader = Brush.linearGradient(listOf(Color(0xFF6366F1), Color(0xFF4338CA)))

private enum class BannerStatus { NONE, OK, WARN, ERROR }



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockCountScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    // เรียกใช้ ProductDatabase
    val productDb = remember { ProductDatabase(context) }

    // --- SDK STATE ---
    var isReaderConnected by remember { mutableStateOf(false) }
    var hcReader by remember { mutableStateOf<UHFReader?>(null) }
    var p8Device by remember { mutableStateOf<UHFDevice?>(null) }
    var p8Reader by remember { mutableStateOf<MagicUhfReader?>(null) }

    var currentDeviceType by remember { mutableStateOf(DeviceType.UNKNOWN) }
    val deviceModel = remember { android.os.Build.MODEL }

    var permissionGranted by remember { mutableStateOf(false) }
    var scanningOn by rememberSaveable { mutableStateOf(false) }
    var bannerText by remember { mutableStateOf<String?>("Model: ${android.os.Build.MODEL} | Mfr: ${android.os.Build.MANUFACTURER}") }
    var bannerStatus by remember { mutableStateOf(BannerStatus.WARN) }

    var scanningBusy by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var showCheckDialog by remember { mutableStateOf(false) }

    val db = remember { DraftDatabase(context) }
    val toneGen = remember { ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME) }
    DisposableEffect(Unit) { onDispose { try { toneGen.release() } catch (_: Exception) {} } }
    var lastBeepMs = remember { 0L }

    fun beep() {
        val now = System.currentTimeMillis()
        if (now - lastBeepMs < 200) return
        lastBeepMs = now
        try { toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 120) } catch (_: Exception) {}
    }

    val scannedList  = remember { mutableStateListOf<String>() }
    val scanLog      = remember { mutableStateListOf<String>() }
    val scanCountMap = remember { mutableStateMapOf<String, Int>() }
    val scannedSet  = remember { HashSet<String>() }
    val queuedSet   = remember { HashSet<String>() }
    var dupIgnored by remember { mutableStateOf(0) }
    var draftLoaded by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<FoundTag>>(emptyList()) }
    var missing by remember { mutableStateOf<List<String>>(emptyList()) }
    var resultTab by remember { mutableStateOf("GROUP") }
    val scanCh = remember { Channel<String>(capacity = 4096) }

    fun normalizeToken(raw: String): String {
        return raw.trim().replace(" ", "").uppercase()
    }

    fun resetResultsBecauseNewScan() {
        checked = false; found = emptyList(); missing = emptyList(); resultTab = "GROUP"
        if (!scanningOn) { bannerStatus = BannerStatus.NONE; bannerText = null }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            permissionGranted = true
            bannerText = "กำลังตรวจสอบรุ่นเครื่อง Hardware..."
        } else {
            bannerStatus = BannerStatus.ERROR
            bannerText = "ต้องการ Permission เพื่อเชื่อมต่อ Hardware"
        }
    }

    LaunchedEffect(Unit) {
        val saved = withContext(Dispatchers.IO) { db.loadStockCountDraft() }
        if (saved.isNotEmpty()) {
            for (rfid in saved) {
                if (scannedSet.add(rfid)) {
                    queuedSet.add(rfid)
                    scannedList.add(rfid)
                }
            }
        }
        draftLoaded = true

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        )
    }

    LaunchedEffect(draftLoaded) {
        if (!draftLoaded) return@LaunchedEffect
        snapshotFlow { scannedList.toList() }
            .collect { current ->
                withContext(Dispatchers.IO) { db.saveStockCountDraft(current) }
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

            withContext(Dispatchers.Main) {
                bannerStatus = BannerStatus.WARN
                bannerText = "Model: $deviceModel → ลอง ${currentDeviceType.name}..."
            }

            var hcOk = false
            if (currentDeviceType == DeviceType.HC) {
                var retry = 0
                while (retry < 3 && !hcOk) {
                    try {
                        val reader = UHFReader.getInstance()
                        if (reader == null) {
                            withContext(Dispatchers.Main) { bannerStatus = BannerStatus.ERROR; bannerText = "ไม่พบ Hardware HC" }
                            break
                        }
                        hcReader = reader
                        delay(1000)
                        if (reader.connect(context)?.data == true) {
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
                            withContext(Dispatchers.Main) {
                                bannerStatus = BannerStatus.OK
                                bannerText = "พร้อมใช้งาน (HC | $deviceModel)"
                            }
                        } else {
                            retry++
                            withContext(Dispatchers.Main) { bannerText = "เชื่อมต่อ HC ไม่สำเร็จ (Retry $retry)..." }
                            delay(1500)
                        }
                    } catch (t: Throwable) {
                        Log.e("UHF_INIT", "HC Error: ${t.message}", t)
                        retry++
                        withContext(Dispatchers.Main) { bannerStatus = BannerStatus.ERROR; bannerText = "HC Error: ${t.localizedMessage}" }
                        delay(1500)
                    }
                }
            }

            if (!hcOk && (currentDeviceType == DeviceType.P8_MAGICRF || !isReaderConnected)) {
                try {
                    withContext(Dispatchers.Main) { bannerStatus = BannerStatus.WARN; bannerText = "กำลังเชื่อมต่อ MagicRF..." }
                    currentDeviceType = DeviceType.P8_MAGICRF
                    val device = UHFDevice(context)
                    device.UhfOpen()
                    MagicUhfReader.setPortPath(device.SerialDev())
                    val reader = MagicUhfReader.getInstance()
                    if (reader != null) {
                        p8Device = device
                        p8Reader = reader
                        isReaderConnected = true
                        withContext(Dispatchers.Main) {
                            bannerStatus = BannerStatus.OK
                            bannerText = "พร้อมใช้งาน (MagicRF | $deviceModel)"
                        }
                    } else {
                        withContext(Dispatchers.Main) { bannerStatus = BannerStatus.ERROR; bannerText = "SerialPort Init Fail" }
                    }
                } catch (t: Throwable) {
                    Log.e("UHF_INIT", "MagicRF Error: ${t.message}", t)
                    currentDeviceType = DeviceType.UNKNOWN
                    withContext(Dispatchers.Main) {
                        bannerStatus = BannerStatus.ERROR
                        bannerText = "ไม่รองรับ Hardware บนเครื่อง: $deviceModel"
                    }
                }
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
            } catch (e: Exception) { }
        }
    }

    LaunchedEffect(scanningOn) {
        if (!isReaderConnected) return@LaunchedEffect

        try {
            if (scanningOn) {
                resetResultsBecauseNewScan()
                bannerStatus = BannerStatus.OK
                bannerText = "กำลังยิงสัญญาณ RFID..."

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
            } else {
                if (currentDeviceType == DeviceType.HC) {
                    hcReader?.stopInventory()
                }
                bannerStatus = BannerStatus.NONE
                bannerText = null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            scanningOn = false
            bannerStatus = BannerStatus.ERROR
            bannerText = AppError.resolve(e)
        }
    }

    LaunchedEffect(Unit) {
        for (tag in scanCh) {
            scanningBusy = true
            if (checked) resetResultsBecauseNewScan()

            scanLog.add(tag)
            scanCountMap[tag] = (scanCountMap[tag] ?: 0) + 1

            if (!scannedSet.contains(tag)) {
                scannedSet.add(tag)
                queuedSet.add(tag)
                scannedList.add(0, tag)
                beep()
            } else {
                dupIgnored += 1
            }
            scanningBusy = false
        }
    }

    fun clearAll() {
        scannedList.clear(); scanLog.clear(); scanCountMap.clear(); scannedSet.clear(); queuedSet.clear()
        dupIgnored = 0; checked = false; found = emptyList(); missing = emptyList()
        resultTab = "GROUP"; bannerStatus = BannerStatus.NONE; bannerText = null
        scope.launch(Dispatchers.IO) { db.clearStockCountDraft() }
    }

    fun groupedFound(): List<GroupRow> {
        return found.groupBy { it.productId }.map { (pid, list) ->
            GroupRow(pid, list.first().productName ?: "สินค้า #$pid", list.size.toLong())
        }.sortedWith(compareByDescending<GroupRow> { it.qty }.thenBy { it.productId })
    }

    suspend fun doCheck() {
        if (checking || confirming) return
        if (scannedList.isEmpty()) { bannerStatus = BannerStatus.WARN; bannerText = "ยังไม่มีข้อมูล"; return }
        checking = true; bannerStatus = BannerStatus.NONE; bannerText = null
        try {
            val validToken = AuthManager.getValidAccessToken(context)
            val results = SupabaseBatchCheckApi.lookupMany(scannedList.toList(), validToken)
            found = results; missing = scannedList.filter { tag -> results.none { it.rfid == tag } }
            checked = true; resultTab = "GROUP"
            bannerStatus = BannerStatus.OK; bannerText = "ตรวจสอบสำเร็จ: ครบ ${found.size} / ขาด ${missing.size}"
        } catch (e: Exception) {
            bannerStatus = BannerStatus.ERROR; bannerText = AppError.resolve(e)
        } finally { checking = false }
    }

    suspend fun confirmToReaderStock() {
        if (confirming || checking || !checked || found.isEmpty()) return
        confirming = true; bannerStatus = BannerStatus.NONE; bannerText = null
        try {
            val groups = groupedFound()
            val validToken = AuthManager.getValidAccessToken(context)
            val storedBranchId = SessionStore.getBranchId(context)
            val finalBranchId = if (storedBranchId > 0) storedBranchId else 1L

            SupabaseReaderStockApi.upsertFromCounts(groups, finalBranchId, validToken)

            bannerStatus = BannerStatus.OK; bannerText = "บันทึกสต๊อกเรียบร้อย (${groups.size} รายการ)"
            withContext(Dispatchers.IO) { db.clearStockCountDraft() }
            clearAll()
        } catch (e: Exception) {
            bannerStatus = BannerStatus.ERROR; bannerText = AppError.resolve(e)
        } finally { confirming = false }
    }

    Scaffold(
        containerColor = StockColorBg,
        topBar = {
            TopAppBar(
                title = { Text("RFID Stock Count", fontWeight = FontWeight.Bold, color = StockColorTextMain) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBackIosNew, null, tint = StockColorTextMain)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { clearAll() },
                        enabled = !checking && !confirming && scannedList.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Filled.DeleteSweep,
                            null,
                            tint = if (scannedList.isNotEmpty()) StockColorError else Color.LightGray
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = StockColorBg)
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 24.dp,
                shadowElevation = 24.dp,
                color = StockColorSurface,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).navigationBarsPadding()) {
                    AnimatedVisibility(visible = bannerText != null && bannerStatus != BannerStatus.NONE) {
                        val (bg, txt, icon) = when (bannerStatus) {
                            BannerStatus.OK -> Triple(StockColorSuccess.copy(0.1f), StockColorSuccess, Icons.Rounded.CheckCircle)
                            BannerStatus.WARN -> Triple(StockColorWarning.copy(0.1f), StockColorWarning, Icons.Rounded.Warning)
                            BannerStatus.ERROR -> Triple(StockColorError.copy(0.1f), StockColorError, Icons.Rounded.Error)
                            else -> Triple(Color.Gray.copy(0.1f), Color.Gray, Icons.Rounded.Info)
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                .background(bg, RoundedCornerShape(10.dp)).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(icon, null, tint = txt, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(bannerText ?: "", color = txt, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                        }
                    }

                    Row(Modifier.height(42.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val scanColor by animateColorAsState(if (scanningOn) StockColorError else StockColorPrimary)
                        Button(
                            onClick = {
                                if (!isReaderConnected) {
                                    Toast.makeText(context, "ไม่พบเครื่องสแกน (Emulator Mode)", Toast.LENGTH_SHORT).show()
                                } else {
                                    scanningOn = !scanningOn
                                }
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = scanColor),
                            enabled = !checking && !confirming,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Icon(if (scanningOn) Icons.Rounded.Stop else Icons.Rounded.QrCodeScanner, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (scanningOn) "หยุดนับ" else "เริ่มนับ", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        if (checked) {
                            Button(
                                onClick = { scope.launch { confirmToReaderStock() } },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = StockColorSuccess),
                                enabled = found.isNotEmpty() && !confirming,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            ) {
                                if (confirming) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp))
                                else {
                                    Icon(Icons.Rounded.Save, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("บันทึก", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            FilledTonalButton(
                                onClick = { showCheckDialog = true },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                shape = RoundedCornerShape(12.dp),
                                enabled = scannedList.isNotEmpty() && !checking,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            ) {
                                if (checking) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                else {
                                    Icon(Icons.Rounded.FactCheck, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("ตรวจสอบ", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(StockGradientHeader)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("R", color = Color.White.copy(0.75f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("${scanLog.size}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 22.sp)
                }
                Box(Modifier.width(1.dp).height(28.dp).background(Color.White.copy(0.3f)))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("I", color = Color.White.copy(0.75f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("${scannedList.size}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 22.sp)
                }

                Spacer(Modifier.weight(1f))

                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) {
                    if (scanningOn) {
                        val infiniteTransition = rememberInfiniteTransition()
                        val scale by infiniteTransition.animateFloat(1f, 1.5f, infiniteRepeatable(tween(800), RepeatMode.Restart))
                        val alpha by infiniteTransition.animateFloat(0.5f, 0f, infiniteRepeatable(tween(800), RepeatMode.Restart))
                        Box(Modifier.size(40.dp).scale(scale).alpha(alpha).background(Color.White, CircleShape))
                    }
                    Box(
                        Modifier.size(40.dp).background(Color.White.copy(0.2f), CircleShape).border(1.dp, Color.White.copy(0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(if (scanningBusy) Icons.Rounded.Downloading else Icons.Rounded.Inventory2, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(bottom = 20.dp, start = 8.dp, end = 8.dp, top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (!checked) {
                    itemsIndexed(scannedList, key = { _, tag -> tag }) { idx, tag ->
                        val count = scanCountMap[tag] ?: 1
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = if (idx == 0) StockColorSuccess.copy(0.05f) else StockColorSurface),
                            shape = RoundedCornerShape(8.dp),
                            elevation = CardDefaults.cardElevation(0.dp)
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "#${scannedList.size - idx}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StockColorPrimary,
                                    modifier = Modifier.width(34.dp)
                                )
                                Text(
                                    tag,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    color = StockColorTextMain,
                                    modifier = Modifier.weight(1f)
                                )
                                if (count > 1) {
                                    Text(
                                        "×$count",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = StockColorWarning
                                    )
                                }
                            }
                        }
                    }
                } else {
                    when (resultTab) {
                        "GROUP" -> {
                            val groups = groupedFound()
                            itemsIndexed(groups) { _, item ->
                                // ดึงข้อมูล Product จาก DB ท้องถิ่นผ่าน productId
                                val product = remember(item.productId) { productDb.findById(item.productId) }

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = StockColorSurface),
                                    shape = RoundedCornerShape(16.dp),
                                    elevation = CardDefaults.cardElevation(2.dp)
                                ) {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        // แสดงรูปภาพ หรือ Fallback ตัวอักษร
                                        if (!product?.imageUrl.isNullOrBlank()) {
                                            AsyncImage(
                                                model = product?.imageUrl,
                                                contentDescription = product?.name,
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(StockColorBg),
                                                contentScale = ContentScale.Crop
                                            )
                                        } else {
                                            Box(
                                                Modifier
                                                    .size(48.dp)
                                                    .background(StockColorPrimary.copy(0.08f), RoundedCornerShape(8.dp)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = item.name.take(1).uppercase(),
                                                    color = StockColorPrimary,
                                                    fontWeight = FontWeight.Black,
                                                    fontSize = 18.sp
                                                )
                                            }
                                        }

                                        Spacer(Modifier.width(16.dp))

                                        // แสดงชื่อสินค้าและ SKU
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                text = product?.name ?: item.name,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp,
                                                color = StockColorTextMain,
                                                maxLines = 1
                                            )
                                            Text(
                                                text = "SKU: ${product?.sku ?: "-"}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = StockColorTextSub
                                            )
                                        }

                                        // จำนวนที่สแกนเจอ
                                        Surface(color = StockColorPrimary, shape = RoundedCornerShape(8.dp)) {
                                            Text(
                                                text = "x${item.qty}",
                                                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        "FOUND" -> itemsIndexed(found) { _, item -> ResultRow(item.rfid, item.productName ?: "Product #${item.productId}", true) }
                        "MISSING" -> itemsIndexed(missing) { _, rfid -> ResultRow(rfid, "ไม่พบข้อมูล", false) }
                    }
                }
            }
        }

        if (showCheckDialog) {
            AlertDialog(
                onDismissRequest = { showCheckDialog = false },
                icon = { Icon(Icons.Rounded.HelpOutline, null, tint = StockColorPrimary, modifier = Modifier.size(32.dp)) },
                title = { Text("ยืนยันการตรวจสอบ", fontWeight = FontWeight.Bold) },
                text = { Text("ข้อมูลถูกต้องแล้วใช่หรือไม่?", textAlign = TextAlign.Center) },
                confirmButton = {
                    Button(
                        onClick = { showCheckDialog = false; scope.launch { doCheck() } },
                        colors = ButtonDefaults.buttonColors(containerColor = StockColorPrimary)
                    ) { Text("ยืนยัน", fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { showCheckDialog = false }) { Text("ยกเลิก", color = StockColorTextSub) } },
                containerColor = StockColorSurface, shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

@Composable
fun ResultRow(title: String, subtitle: String, isSuccess: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StockColorSurface),
        shape = RoundedCornerShape(12.dp),
        border = if (!isSuccess) BorderStroke(1.dp, StockColorError.copy(0.2f)) else null,
        elevation = CardDefaults.cardElevation(if (isSuccess) 0.5.dp else 0.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isSuccess) Icons.Rounded.CheckCircle else Icons.Rounded.HighlightOff,
                null,
                tint = if (isSuccess) StockColorSuccess else StockColorError,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = StockColorTextMain, fontSize = 16.sp)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (isSuccess) StockColorTextSub else StockColorError)
            }
        }
    }
}