package ui

import android.Manifest
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
import androidx.compose.runtime.saveable.listSaver
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
import data.SessionManager

// --- ✅ Import ให้ตรงกับ Demo จีน ---
import com.xlzn.hcpda.uhf.UHFReader
import data.SupabaseConfig
// ---------------------------------

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

private const val BURST_GUARD_MS = 100L
private const val MIN_TOKEN_LEN = 4

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
    var uhfReader by remember { mutableStateOf<UHFReader?>(null) }
    var permissionGranted by remember { mutableStateOf(false) }

    var scanningOn by rememberSaveable { mutableStateOf(false) }
    var bannerText by remember { mutableStateOf<String?>("กำลังตรวจสอบ Permission...") }
    var bannerStatus by remember { mutableStateOf(BannerStatus.WARN) }

    var scanningBusy by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var showCheckDialog by remember { mutableStateOf(false) }

    // --- DATA STATE ---
    val scannedList = rememberSaveable(
        saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })
    ) { mutableStateListOf<String>() }

    val scannedSet = remember { HashSet<String>() }
    val queuedSet = remember { HashSet<String>() }
    val lastBurstMs = remember { HashMap<String, Long>() }
    var dupIgnored by rememberSaveable { mutableStateOf(0) }

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

    // -------------------------------------------------------------------------
    // 1. Permission Request (จำเป็นสำหรับเครื่องจริง)
    // -------------------------------------------------------------------------
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            permissionGranted = true
            bannerText = "กำลังเชื่อมต่อ Hardware..."
        } else {
            bannerStatus = BannerStatus.ERROR
            bannerText = "ต้องการ Permission เพื่อเชื่อมต่อ Hardware"
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        )
    }

    // -------------------------------------------------------------------------
    // 2. SDK INIT
    // -------------------------------------------------------------------------
    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) return@LaunchedEffect

        withContext(Dispatchers.IO) {
            var retryCount = 0
            val maxRetries = 3 // ลดจำนวน Retry ลงหน่อยสำหรับ Emulator จะได้ไม่รอนาน
            var connected = false

            while (retryCount < maxRetries && !connected) {
                try {
                    // ⚠️ ใน Emulator บรรทัดนี้อาจจะ return null หรือ throw error เพราะไม่มีไฟล์ .so
                    val reader = UHFReader.getInstance()

                    if (reader == null) {
                        withContext(Dispatchers.Main) {
                            bannerStatus = BannerStatus.ERROR
                            bannerText = "ไม่พบ Hardware (Emulator Mode)"
                        }
                        return@withContext
                    }

                    uhfReader = reader
                    delay(1000)

                    val result = reader.connect(context)

                    if (result?.data == true) {
                        // ✅ Connect สำเร็จ (เฉพาะเครื่องจริง)
                        withContext(Dispatchers.Main) {
                            delay(500)
                            reader.setPower(30)

                            // 👇 [จุดที่แก้ไข Final] ใช้ ecpHex ตามรูปที่แคปมา
                            reader.setOnInventoryDataListener { tagsList ->
                                if (!tagsList.isNullOrEmpty()) {
                                    for (tag in tagsList) {
                                        // ✅ ใช้ ecpHex ตรงๆ (ชัวร์ที่สุดจากรูป Autocomplete)
                                        val rfid = tag.ecpHex

                                        if (!rfid.isNullOrEmpty()) {
                                            scanCh.trySend(normalizeToken(rfid))
                                        }
                                    }
                                }
                            }

                            isReaderConnected = true
                            bannerStatus = BannerStatus.OK
                            bannerText = "พร้อมใช้งาน (Connected)"
                        }
                        connected = true
                    } else {
                        retryCount++
                        withContext(Dispatchers.Main) {
                            bannerText = "เชื่อมต่อไม่สำเร็จ (Retry $retryCount)..."
                        }
                        delay(1500)
                    }
                } catch (t: Throwable) {
                    // ⚠️ Emulator มักจะตกมาที่ catch นี้ เพราะสถาปัตยกรรม CPU ไม่ตรง (x86 vs arm)
                    Log.e("UHF_INIT", "Error: ${t.message}", t)
                    retryCount++
                    withContext(Dispatchers.Main) {
                        bannerStatus = BannerStatus.ERROR
                        // แสดงข้อความให้รู้ว่า Code ทำงานแล้ว แต่ Hardware ไม่มี
                        bannerText = "Emulator/Error: ${t.localizedMessage}"
                    }
                    delay(2000)
                }
            }
        }
    }

    // --- LIFECYCLE CLEANUP ---
    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                scanningOn = false
                uhfReader?.stopInventory()
                uhfReader?.disConnect()
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
                uhfReader?.startInventory()
            } else {
                uhfReader?.stopInventory()
                bannerStatus = BannerStatus.NONE
                bannerText = null
            }
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

            if (!queuedSet.contains(tag)) {
                queuedSet.add(tag)
                if (!scannedSet.contains(tag)) {
                    scannedSet.add(tag)
                    scannedList.add(0, tag)
                } else {
                    dupIgnored += 1
                }
            }
            scanningBusy = false
        }
    }

    fun clearAll() {
        scannedList.clear(); scannedSet.clear(); queuedSet.clear(); lastBurstMs.clear()
        dupIgnored = 0; checked = false; found = emptyList(); missing = emptyList()
        resultTab = "GROUP"; bannerStatus = BannerStatus.NONE; bannerText = null
    }

    // ... (ส่วน UI และ Logic Supabase ด้านล่างเหมือนเดิม) ...

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
            val results = SupabaseBatchCheckApi.lookupMany(scannedList.toList(), SessionManager.accessToken)
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
            SupabaseReaderStockApi.upsertFromCounts(groups, SessionManager.accessToken)
            bannerStatus = BannerStatus.OK; bannerText = "บันทึกสต๊อกเรียบร้อย (${groups.size} รายการ)"
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
                Column(Modifier.padding(16.dp).navigationBarsPadding()) {
                    // Banner
                    AnimatedVisibility(visible = bannerText != null && bannerStatus != BannerStatus.NONE) {
                        val (bg, txt, icon) = when (bannerStatus) {
                            BannerStatus.OK -> Triple(ColorSuccess.copy(0.1f), ColorSuccess, Icons.Rounded.CheckCircle)
                            BannerStatus.WARN -> Triple(ColorWarning.copy(0.1f), ColorWarning, Icons.Rounded.Warning)
                            BannerStatus.ERROR -> Triple(ColorError.copy(0.1f), ColorError, Icons.Rounded.Error)
                            else -> Triple(Color.Gray.copy(0.1f), Color.Gray, Icons.Rounded.Info)
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 12.dp)
                                .background(bg, RoundedCornerShape(12.dp)).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(icon, null, tint = txt)
                            Spacer(Modifier.width(8.dp))
                            Text(bannerText ?: "", color = txt, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        }
                    }

                    // Buttons
                    Row(Modifier.height(56.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val scanColor by animateColorAsState(if (scanningOn) ColorError else ColorPrimary)
                        Button(
                            onClick = {
                                if (!isReaderConnected) {
                                    // ⚠️ ใน Emulator จะเข้าเคสนี้เสมอ เพราะ isReaderConnected = false
                                    Toast.makeText(context, "ไม่พบเครื่องสแกน (Emulator Mode)", Toast.LENGTH_SHORT).show()
                                } else {
                                    scanningOn = !scanningOn
                                }
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = scanColor),
                            enabled = !checking && !confirming
                        ) {
                            Icon(if (scanningOn) Icons.Rounded.Stop else Icons.Rounded.QrCodeScanner, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (scanningOn) "หยุดนับ" else "เริ่มนับ", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }

                        if (checked) {
                            Button(
                                onClick = { scope.launch { confirmToReaderStock() } },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ColorSuccess),
                                enabled = found.isNotEmpty() && !confirming
                            ) {
                                if (confirming) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                                else {
                                    Icon(Icons.Rounded.Save, null); Spacer(Modifier.width(8.dp))
                                    Text("บันทึก", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            FilledTonalButton(
                                onClick = { showCheckDialog = true },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                shape = RoundedCornerShape(16.dp),
                                enabled = scannedList.isNotEmpty() && !checking
                            ) {
                                if (checking) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                else {
                                    Icon(Icons.Rounded.FactCheck, null); Spacer(Modifier.width(8.dp))
                                    Text("ตรวจสอบ", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Dashboard
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                    .shadow(12.dp, RoundedCornerShape(24.dp), spotColor = ColorPrimary.copy(0.4f))
                    .clip(RoundedCornerShape(24.dp))
                    .background(GradientHeader)
            ) {
                Box(Modifier.offset((-30).dp, (-30).dp).size(150.dp).alpha(0.1f).background(Color.White, CircleShape))
                Box(Modifier.align(Alignment.BottomEnd).offset(50.dp, 50.dp).size(200.dp).alpha(0.1f).background(Color.White, CircleShape))

                Row(
                    Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("จำนวนที่อ่านได้ (Total)", color = Color.White.copy(0.8f), fontSize = 14.sp)
                        Text("${scannedList.size}", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Box(contentAlignment = Alignment.Center) {
                        if (scanningOn) {
                            val infiniteTransition = rememberInfiniteTransition()
                            val scale by infiniteTransition.animateFloat(
                                initialValue = 1f, targetValue = 1.5f,
                                animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Restart)
                            )
                            val alpha by infiniteTransition.animateFloat(
                                initialValue = 0.5f, targetValue = 0f,
                                animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Restart)
                            )
                            Box(Modifier.size(50.dp).scale(scale).alpha(alpha).background(Color.White, CircleShape))
                        }
                        Box(
                            Modifier.size(56.dp)
                                .background(Color.White.copy(0.2f), CircleShape)
                                .border(1.dp, Color.White.copy(0.3f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(if (scanningBusy) Icons.Rounded.Downloading else Icons.Rounded.Inventory2, null, tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }

            // List Content
            LazyColumn(
                contentPadding = PaddingValues(bottom = 20.dp, start = 10.dp, end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!checked) {
                    itemsIndexed(scannedList) { _, tag ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = ColorSurface),
                            shape = RoundedCornerShape(8.dp),
                            elevation = CardDefaults.cardElevation(1.dp)
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.QrCode, null, tint = ColorPrimary, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(tag, fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
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

// ... (API Objects: SupabaseBatchCheckApi, SupabaseReaderStockApi ใช้ของเดิมได้เลยครับ ไม่ต้องแก้) ...
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