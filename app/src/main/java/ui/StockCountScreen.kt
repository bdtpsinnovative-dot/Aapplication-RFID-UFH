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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.snapshotFlow
import data.AuthManager
import data.DraftDatabase
import data.SessionManager
import data.SupabaseConfig

// 👵🏼 Import ของเครื่องแรก (HC)
import com.xlzn.hcpda.uhf.UHFReader

// 👵🏼 Import ของเครื่องที่สอง (p8 - MagicRF)
import android.hardware.UHFDevice
import com.magicrf.uhfreaderlib.reader.UhfReader as MagicUhfReader
import com.magicrf.uhfreaderlib.reader.Tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant

// --- THEME & COLORS ---
private val ColorPrimary = Color(0xFF4F46E5)
private val ColorBg = Color(0xFFF1F5F9)
private val ColorSurface = Color(0xFFFFFFFF)
private val ColorSuccess = Color(0xFF10B981)
private val ColorError = Color(0xFFEF4444)
private val ColorWarning = Color(0xFFF59E0B)
private val ColorTextMain = Color(0xFF1E293B)
private val ColorTextSub = Color(0xFF64748B)

private val GradientHeader = Brush.linearGradient(
    listOf(Color(0xFF6366F1), Color(0xFF4338CA))
)

private enum class BannerStatus { NONE, OK, WARN, ERROR }


private data class FoundTag(val rfid: String, val productId: Long, val productName: String?)
private data class GroupRow(val productId: Long, val name: String, val qty: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockCountScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    // --- SDK STATE ---
    var isReaderConnected by remember { mutableStateOf(false) }

    // ตัวแปรเก็บ Hardware ของเครื่อง HC
    var hcReader by remember { mutableStateOf<UHFReader?>(null) }

    // ตัวแปรเก็บ Hardware ของเครื่อง P8 (MagicRF)
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
        if (now - lastBeepMs < 200) return   // cooldown — กัน startTone ซ้อนกัน
        lastBeepMs = now
        try { toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 120) } catch (_: Exception) {}
    }

    val scannedList  = remember { mutableStateListOf<String>() }   // unique ordered — ใช้ save/check/display
    val scanLog      = remember { mutableStateListOf<String>() }   // ทุก scan — ใช้นับ total
    val scanCountMap = remember { mutableStateMapOf<String, Int>() } // tag → จำนวนครั้ง
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

    // 1. Permission Request
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
        // โหลด draft tag ที่ค้างไว้จาก SQLite
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

    // Auto-save draft หลัง draftLoaded=true เท่านั้น ป้องกัน race condition
    LaunchedEffect(draftLoaded) {
        if (!draftLoaded) return@LaunchedEffect
        snapshotFlow { scannedList.toList() }
            .collect { current ->
                withContext(Dispatchers.IO) { db.saveStockCountDraft(current) }
            }
    }

    // 2. SDK INIT (แยกสายเชื่อมต่อ)
    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) return@LaunchedEffect

        withContext(Dispatchers.IO) {

            // ตรวจ model name — ถ้าไม่รู้จักให้ลอง HC ก่อน (ปลอดภัยกว่า MagicRF)
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

            // ลอง HC
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

            // ลอง MagicRF (ถ้า P8 หรือ HC ไม่สำเร็จ)
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

    // --- LIFECYCLE CLEANUP ---
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

    // --- SCAN CONTROL ---
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
                    // 🔵 เครื่อง P8 ต้องเขียนลูปวนถามข้อมูล (Polling)
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
                            delay(80) // ดีเลย์ตามโค้ดโรงงาน
                        }
                    }
                }

            } else {
                if (currentDeviceType == DeviceType.HC) {
                    hcReader?.stopInventory()
                }
                // เครื่อง P8 ไม่ต้องทำอะไร แค่ค่า scanningOn เป็น false ลูปข้างบนก็จะหยุดเองจ้ะ

                bannerStatus = BannerStatus.NONE
                bannerText = null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // coroutine ถูก cancel ตามปกติ (เช่น กดหยุดยิง) — ไม่ใช่ error ไม่ต้องแสดงอะไร
            throw e
        } catch (e: Exception) {
            scanningOn = false
            bannerStatus = BannerStatus.ERROR
            bannerText = "Scan Error: ${e.message}"
        }
    }

    // --- DATA PROCESSOR ---
    LaunchedEffect(Unit) {
        for (tag in scanCh) {
            scanningBusy = true
            if (checked) resetResultsBecauseNewScan()

            // นับทุก scan (รวมซ้ำ)
            scanLog.add(tag)
            scanCountMap[tag] = (scanCountMap[tag] ?: 0) + 1

            if (!scannedSet.contains(tag)) {
                scannedSet.add(tag)
                queuedSet.add(tag)
                scannedList.add(0, tag)  // ใหม่สุดขึ้นบน
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
            bannerStatus = BannerStatus.ERROR; bannerText = e.message ?: "Error"
        } finally { checking = false }
    }

    suspend fun confirmToReaderStock() {
        if (confirming || checking || !checked || found.isEmpty()) return
        confirming = true; bannerStatus = BannerStatus.NONE; bannerText = null
        try {
            val groups = groupedFound()
            val validToken = AuthManager.getValidAccessToken(context)
            SupabaseReaderStockApi.upsertFromCounts(groups, validToken)
            bannerStatus = BannerStatus.OK; bannerText = "บันทึกสต๊อกเรียบร้อย (${groups.size} รายการ)"
            withContext(Dispatchers.IO) { db.clearStockCountDraft() }
            clearAll()
        } catch (e: Exception) {
            bannerStatus = BannerStatus.ERROR; bannerText = e.message ?: "Error"
        } finally { confirming = false }
    }

    // --- UI CODE ---
    Scaffold(
        containerColor = ColorBg,
        topBar = {
            TopAppBar(
                title = { Text("RFID Stock Count", fontWeight = FontWeight.Bold, color = ColorTextMain) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBackIosNew, null, tint = ColorTextMain)
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
                            tint = if (scannedList.isNotEmpty()) ColorError else Color.LightGray
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ColorBg)
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 24.dp,
                shadowElevation = 24.dp,
                color = ColorSurface,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).navigationBarsPadding()) {
                    AnimatedVisibility(visible = bannerText != null && bannerStatus != BannerStatus.NONE) {
                        val (bg, txt, icon) = when (bannerStatus) {
                            BannerStatus.OK -> Triple(ColorSuccess.copy(0.1f), ColorSuccess, Icons.Rounded.CheckCircle)
                            BannerStatus.WARN -> Triple(ColorWarning.copy(0.1f), ColorWarning, Icons.Rounded.Warning)
                            BannerStatus.ERROR -> Triple(ColorError.copy(0.1f), ColorError, Icons.Rounded.Error)
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
                        val scanColor by animateColorAsState(if (scanningOn) ColorError else ColorPrimary)
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
                                colors = ButtonDefaults.buttonColors(containerColor = ColorSuccess),
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

            // ── Compact Stats Bar ─────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(GradientHeader)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── total reads ──
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("R", color = Color.White.copy(0.75f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("${scanLog.size}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 22.sp)
                }
                // ── divider ──
                Box(Modifier.width(1.dp).height(28.dp).background(Color.White.copy(0.3f)))
                // ── unique count ──
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("I", color = Color.White.copy(0.75f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("${scannedList.size}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 22.sp)
                }

                Spacer(Modifier.weight(1f))

                // ── pulse icon ──
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
                            colors = CardDefaults.cardColors(containerColor = if (idx == 0) ColorSuccess.copy(0.05f) else ColorSurface),
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
                                    color = ColorPrimary,
                                    modifier = Modifier.width(34.dp)
                                )
                                Text(
                                    tag,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    color = ColorTextMain,
                                    modifier = Modifier.weight(1f)
                                )
                                if (count > 1) {
                                    Text(
                                        "×$count",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = ColorWarning
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
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = ColorSurface),
                                    shape = RoundedCornerShape(16.dp),
                                    elevation = CardDefaults.cardElevation(2.dp)
                                ) {
                                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier.size(44.dp).background(ColorPrimary.copy(0.08f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(item.name.take(1).uppercase(), color = ColorPrimary, fontWeight = FontWeight.Black, fontSize = 18.sp)
                                        }
                                        Spacer(Modifier.width(16.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(item.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = ColorTextMain, maxLines = 1)
                                            Text("ID: ${item.productId}", style = MaterialTheme.typography.labelMedium, color = ColorTextSub)
                                        }
                                        Surface(color = ColorPrimary, shape = RoundedCornerShape(8.dp)) {
                                            Text("x${item.qty}", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = Color.White, fontWeight = FontWeight.Bold)
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
                icon = { Icon(Icons.Rounded.HelpOutline, null, tint = ColorPrimary, modifier = Modifier.size(32.dp)) },
                title = { Text("ยืนยันการตรวจสอบ", fontWeight = FontWeight.Bold) },
                text = { Text("ข้อมูลถูกต้องแล้วใช่หรือไม่?", textAlign = TextAlign.Center) },
                confirmButton = {
                    Button(
                        onClick = { showCheckDialog = false; scope.launch { doCheck() } },
                        colors = ButtonDefaults.buttonColors(containerColor = ColorPrimary)
                    ) { Text("ยืนยัน", fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { showCheckDialog = false }) { Text("ยกเลิก", color = ColorTextSub) } },
                containerColor = ColorSurface, shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

@Composable
fun ResultRow(title: String, subtitle: String, isSuccess: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ColorSurface),
        shape = RoundedCornerShape(12.dp),
        border = if (!isSuccess) BorderStroke(1.dp, ColorError.copy(0.2f)) else null,
        elevation = CardDefaults.cardElevation(if (isSuccess) 0.5.dp else 0.dp)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isSuccess) Icons.Rounded.CheckCircle else Icons.Rounded.HighlightOff,
                null,
                tint = if (isSuccess) ColorSuccess else ColorError,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = ColorTextMain, fontSize = 16.sp)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (isSuccess) ColorTextSub else ColorError)
            }
        }
    }
}

private object SupabaseBatchCheckApi {
    private val client = OkHttpClient()
    private fun bearer(t: String?): String = t?.takeIf { it.isNotBlank() } ?: SupabaseConfig.ANON_KEY
    private fun quoteForIn(v: String) = "\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    suspend fun lookupMany(rfids: List<String>, accessToken: String?): List<FoundTag> {
        if (rfids.isEmpty()) return emptyList()
        val out = ArrayList<FoundTag>()
        val chunks = rfids.distinct().chunked(120)
        for (chunk in chunks) {
            val inList = chunk.joinToString(",") { quoteForIn(it) }
            val filter = URLEncoder.encode("in.($inList)", "UTF-8")
            val url = "${SupabaseConfig.URL}/rest/v1/product_rfid_tags?select=rfid,product_id,products(name)&rfid=$filter"
            val req = Request.Builder().url(url).get()
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
                .build()
            val res = withContext(Dispatchers.IO) { client.newCall(req).execute() }
            res.use {
                if (!it.isSuccessful) throw IllegalStateException("Lookup failed: ${it.code}")
                val arr = JSONArray(it.body?.string().orEmpty())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val rfid = o.optString("rfid")
                    val pid = o.optLong("product_id", -1L)
                    val name = o.optJSONObject("products")?.optString("name")
                    if (rfid.isNotBlank() && pid > 0) out.add(FoundTag(rfid, pid, name))
                }
            }
        }
        return out
    }
}

private object SupabaseReaderStockApi {
    private val client = OkHttpClient()
    private val media = "application/json; charset=utf-8".toMediaType()
    private fun bearer(t: String?): String = t?.takeIf { it.isNotBlank() } ?: SupabaseConfig.ANON_KEY

    suspend fun upsertFromCounts(groups: List<GroupRow>, accessToken: String?) {
        val nowIso = Instant.now().toString()
        val arr = JSONArray()
        for (g in groups) {
            arr.put(JSONObject().put("product_id", g.productId).put("qty", g.qty).put("updated_at", nowIso))
        }
        val req = Request.Builder()
            .url("${SupabaseConfig.URL}/rest/v1/reader_stock?on_conflict=product_id")
            .post(arr.toString().toRequestBody(media))
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
            .build()
        val res = withContext(Dispatchers.IO) { client.newCall(req).execute() }
        res.use { if (!it.isSuccessful) throw IllegalStateException("Upsert failed: ${it.code}") }
    }
}