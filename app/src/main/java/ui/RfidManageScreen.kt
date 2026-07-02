package ui

import android.Manifest
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import data.AppError
import data.SessionManager
import data.SupabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

// Hardware SDK
import com.xlzn.hcpda.uhf.UHFReader
import android.hardware.UHFDevice
import com.magicrf.uhfreaderlib.reader.UhfReader as MagicUhfReader
import com.magicrf.uhfreaderlib.reader.Tools

private enum class ScanTarget {
    OLD_TAG, NEW_TAG, DELETE_TAG
}

private data class ScannedTag(
    val rfid: String,
    val productName: String,
    val sku: String?,
    val imageUrl: String?,
    val productId: Long,
    val tagId: Long? = null,
    val branchId: Long? = null,
    val isVerified: Boolean = false,
    val isNotFound: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RfidManageScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var selectedTab by remember { mutableStateOf(0) } // 0: เปลี่ยนแท็ก, 1: ลบแท็ก

    // ── State สำหรับ "เปลี่ยนแท็ก" ──
    var oldTagRfid by remember { mutableStateOf("") }
    var oldTagProduct by remember { mutableStateOf<ScannedTag?>(null) }
    var newTagRfid by remember { mutableStateOf("") }

    // ── State สำหรับ "ลบแท็ก" (สแกนเก็บเข้าลิสต์ท้องถิ่นก่อนเพื่อความลื่นไหล) ──
    var deleteList by remember { mutableStateOf<List<ScannedTag>>(emptyList()) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    var activeScanTarget by remember { mutableStateOf<ScanTarget?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var successMsg by remember { mutableStateOf<String?>(null) }

    // ── Hardware RFID state ──────────────────────────
    var isReaderConnected by remember { mutableStateOf(false) }
    var hcReader by remember { mutableStateOf<UHFReader?>(null) }
    var p8Device by remember { mutableStateOf<UHFDevice?>(null) }
    var p8Reader by remember { mutableStateOf<MagicUhfReader?>(null) }
    var currentDeviceType by remember { mutableStateOf(DeviceType.UNKNOWN) }
    val deviceModel = remember { android.os.Build.MODEL }

    var permissionGranted by remember { mutableStateOf(false) }
    var scanningOn by remember { mutableStateOf(false) }
    var hwMsg by remember { mutableStateOf<String?>(null) }
    var hwError by remember { mutableStateOf(false) }
    val scanCh = remember { Channel<String>(capacity = 256) }

    fun normalizeToken(raw: String) = raw.trim().replace(Regex("[^A-Za-z0-9]"), "").uppercase()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        permissionGranted = perms.values.all { it }
        if (!permissionGranted) { hwMsg = "ต้องการ Permission เพื่อเชื่อมต่อ Hardware"; hwError = true }
    }

    // ── Hardware init ─────────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        )
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

            var hcOk = false
            if (currentDeviceType == DeviceType.HC) {
                var retry = 0
                while (retry < 3 && !hcOk) {
                    try {
                        val reader = UHFReader.getInstance() ?: run {
                            withContext(Dispatchers.Main) { hwMsg = "ไม่พบ Hardware HC"; hwError = true }
                            break
                        }
                        hcReader = reader
                        delay(1000)
                        if (reader.connect(ctx)?.data == true) {
                            delay(500)
                            reader.setPower(30)
                            reader.setOnInventoryDataListener { tagsList ->
                                tagsList?.forEach { tag ->
                                    val rfid = tag.ecpHex
                                    if (!rfid.isNullOrEmpty()) scanCh.trySend(normalizeToken(rfid))
                                }
                            }
                            isReaderConnected = true
                            hcOk = true
                            withContext(Dispatchers.Main) { hwMsg = "พร้อมใช้งาน (HC)"; hwError = false }
                        } else {
                            retry++
                            withContext(Dispatchers.Main) { hwMsg = "เชื่อมต่อ HC ไม่สำเร็จ (Retry $retry)"; hwError = true }
                            delay(1500)
                        }
                    } catch (t: Throwable) {
                        Log.e("RfidManage", "HC Init: ${t.message}")
                        retry++
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
                        withContext(Dispatchers.Main) { hwMsg = "พร้อมใช้งาน (MagicRF)"; hwError = false }
                    } else {
                        withContext(Dispatchers.Main) { hwMsg = "MagicRF Init ไม่สำเร็จ"; hwError = true }
                    }
                } catch (t: Throwable) {
                    Log.e("RfidManage", "MagicRF Init: ${t.message}")
                    withContext(Dispatchers.Main) { hwMsg = "ไม่รองรับ Hardware: $deviceModel"; hwError = true }
                }
            }

            if (!isReaderConnected) {
                withContext(Dispatchers.Main) { hwMsg = "ไม่พบ Hardware RFID บนเครื่องนี้"; hwError = true }
            }
        }
    }

    // Scan loop
    LaunchedEffect(scanningOn) {
        if (!isReaderConnected || !scanningOn) return@LaunchedEffect
        try {
            if (currentDeviceType == DeviceType.HC) {
                hcReader?.startInventory()
                try { while (isActive && scanningOn) delay(100) }
                finally { hcReader?.stopInventory() }
            } else if (currentDeviceType == DeviceType.P8_MAGICRF) {
                while (isActive && scanningOn) {
                    val epcList = try { withContext(Dispatchers.IO) { p8Reader?.inventoryRealTime() } } catch (_: Exception) { null }
                    if (!isActive || !scanningOn) break
                    epcList?.forEach { epc ->
                        if (epc != null) scanCh.trySend(normalizeToken(Tools.Bytes2HexString(epc, epc.size)))
                    }
                    delay(80)
                }
            }
        } catch (_: Exception) {
            withContext(Dispatchers.Main) { scanningOn = false }
        }
    }

    // รับ tag จาก channel → ยิงเสร็จเอาลงลิสต์ทันที (ไม่มีการดึงเน็ตขณะสแกน เพื่อความลื่นไหลระดับ 100%)
    LaunchedEffect(Unit) {
        for (tag in scanCh) {
            if (loading) continue
            
            // กรณีเปลี่ยนแท็กทีละชิ้น
            if (selectedTab == 0) {
                scanningOn = false
                if (activeScanTarget == ScanTarget.OLD_TAG) {
                    oldTagRfid = tag
                    oldTagProduct = ScannedTag(
                        rfid = tag,
                        productName = "รอตรวจสอบข้อมูล...",
                        sku = null,
                        imageUrl = null,
                        productId = 0,
                        isVerified = false
                    )
                } else if (activeScanTarget == ScanTarget.NEW_TAG) {
                    newTagRfid = tag
                }
            } 
            // กรณีลบแท็กสะสม: ยิงรัวๆ เก็บใส่ลิสต์ทันที ไม่มีสะดุดเน็ต!
            else if (selectedTab == 1 && activeScanTarget == ScanTarget.DELETE_TAG) {
                if (deleteList.any { it.rfid == tag }) continue
                
                deleteList = deleteList + ScannedTag(
                    rfid = tag,
                    productName = "รอตรวจสอบ...",
                    sku = null,
                    imageUrl = null,
                    productId = 0,
                    isVerified = false
                )
            }
        }
    }

    // Cleanup
    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                scanningOn = false
                if (currentDeviceType == DeviceType.HC) { hcReader?.stopInventory(); hcReader?.disConnect() }
                else if (currentDeviceType == DeviceType.P8_MAGICRF) { p8Reader?.close(); p8Device?.UhfStop() }
            } catch (_: Exception) {}
        }
    }

    // ── API Operations ────────────────────────────────────────────────────────
    
    // ตรวจสอบข้อมูลสินค้าของแท็กเดิมที่สแกนไว้
    fun verifyOldTagInfo() {
        if (oldTagRfid.isBlank()) return
        scope.launch {
            loading = true
            errorMsg = null
            try {
                val token = SessionManager.accessToken
                val info = RfidApi.fetchRfidProductInfo(oldTagRfid, token)
                if (info == null) {
                    errorMsg = "ไม่พบรหัสแท็กเดิม: $oldTagRfid ในระบบ"
                    oldTagProduct = ScannedTag(
                        rfid = oldTagRfid,
                        productName = "ไม่พบสินค้าในระบบ",
                        sku = null,
                        imageUrl = null,
                        productId = 0,
                        isVerified = true,
                        isNotFound = true
                    )
                } else {
                    oldTagProduct = info.copy(isVerified = true)
                }
            } catch (e: Exception) {
                errorMsg = AppError.resolve(e)
            } finally {
                loading = false
            }
        }
    }

    // ดึงเน็ตรวบยอดเพื่อดึงข้อมูลสินค้าทั้งหมดที่เตรียมลบ
    fun verifyDeleteListInfo() {
        if (deleteList.isEmpty()) return
        scope.launch {
            loading = true
            errorMsg = null
            try {
                val token = SessionManager.accessToken
                val updatedList = deleteList.map { item ->
                    if (item.isVerified) {
                        item
                    } else {
                        val info = RfidApi.fetchRfidProductInfo(item.rfid, token)
                        info?.copy(isVerified = true) ?: item.copy(
                            productName = "ไม่พบสินค้าในระบบ",
                            isVerified = true,
                            isNotFound = true
                        )
                    }
                }
                deleteList = updatedList
            } catch (e: Exception) {
                errorMsg = AppError.resolve(e)
            } finally {
                loading = false
            }
        }
    }

    fun executeChangeRfid() {
        if (oldTagRfid.isBlank() || newTagRfid.isBlank()) {
            errorMsg = "ข้อมูลแท็กสแกนไม่ครบถ้วน"
            return
        }
        scope.launch {
            loading = true
            errorMsg = null
            successMsg = null
            try {
                val token = SessionManager.accessToken
                val ok = RfidApi.changeRfidTag(oldTagRfid, newTagRfid, token)
                if (ok) {
                    successMsg = "เปลี่ยนแท็ก RFID สำเร็จแล้ว!"
                    oldTagRfid = ""
                    newTagRfid = ""
                    oldTagProduct = null
                } else {
                    errorMsg = "บันทึกข้อมูลล้มเหลว"
                }
            } catch (e: Exception) {
                errorMsg = AppError.resolve(e)
            } finally {
                loading = false
            }
        }
    }

    fun executeDeleteRfidBatch() {
        if (deleteList.isEmpty()) return
        scope.launch {
            loading = true
            errorMsg = null
            successMsg = null
            try {
                val token = SessionManager.accessToken
                var successCount = 0
                deleteList.forEach { item ->
                    if (item.tagId != null && item.tagId > 0) {
                        RfidApi.insertDeletedHistory(item, "ลบผ่านเครื่อง PDA", token)
                    }
                    val ok = RfidApi.deleteRfidTag(item.rfid, token)
                    if (ok) successCount++
                }
                successMsg = "ทำการลบแท็กสำเร็จทั้งหมด $successCount จาก ${deleteList.size} รายการ"
                deleteList = emptyList()
            } catch (e: Exception) {
                errorMsg = AppError.resolve(e)
            } finally {
                loading = false
                showDeleteConfirmDialog = false
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("จัดการแท็ก RFID", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "ย้อนกลับ")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.White,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        containerColor = Color(0xFFF8FAFC)
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // สถานะ Hardware
            if (hwMsg != null) {
                item {
                    AssistChip(
                        onClick = {},
                        label = { Text(hwMsg!!, style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.WifiTethering,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (hwError) MaterialTheme.colorScheme.error else Color(0xFF10B981)
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = AssistChipDefaults.assistChipColors(containerColor = Color.White)
                    )
                }
            }

            // เลือก Tab ทำงาน
            item {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = {
                            selectedTab = 0
                            errorMsg = null
                            successMsg = null
                            scanningOn = false
                        },
                        text = { Text("เปลี่ยนแท็กเดิม", fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = {
                            selectedTab = 1
                            errorMsg = null
                            successMsg = null
                            scanningOn = false
                        },
                        text = { Text("ลบแท็กสะสม", fontWeight = FontWeight.Bold) },
                        icon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null) }
                    )
                }
            }

            // ── Tab 0: เปลี่ยนแท็กเดิมเป็นแท็กใหม่ ──
            if (selectedTab == 0) {
                // ขั้นตอนที่ 1: ยิงแท็กเดิม
                item {
                    ElevatedCard(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = Color.White)
                    ) {
                        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("1. ยิงสแกนแท็กเดิม", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            
                            val isScanningOld = scanningOn && activeScanTarget == ScanTarget.OLD_TAG
                            Button(
                                onClick = {
                                    if (!isReaderConnected) return@Button
                                    activeScanTarget = ScanTarget.OLD_TAG
                                    scanningOn = !scanningOn
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isScanningOld) Color(0xFFEF4444) else MaterialTheme.colorScheme.secondary
                                ),
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Nfc, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (isScanningOld) "หยุดยิง..." else "กดเพื่อยิงสแกนแท็กเดิม")
                            }

                            OutlinedTextField(
                                value = oldTagRfid,
                                onValueChange = { 
                                    oldTagRfid = normalizeToken(it) 
                                    oldTagProduct = null // Reset if changed
                                },
                                label = { Text("รหัสแท็กเดิม (RFID)") },
                                placeholder = { Text("พิมพ์หรือยิงสแกนบาร์โค้ดที่นี่...") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )

                            if (oldTagRfid.isNotBlank() && oldTagProduct?.isVerified != true) {
                                Button(
                                    onClick = { verifyOldTagInfo() },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.align(Alignment.End),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("ตรวจสอบ", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                // แสดงข้อมูลการ์ดสินค้าของแท็กเดิมที่สแกนเจอ
                if (oldTagProduct != null) {
                    item {
                        CardProductInfo(product = oldTagProduct!!)
                    }

                    // ขั้นตอนที่ 2: ยิงแท็กใหม่
                    item {
                        ElevatedCard(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.elevatedCardColors(containerColor = Color.White)
                        ) {
                            Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("2. ยิงสแกนแท็กใหม่", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                                val isScanningNew = scanningOn && activeScanTarget == ScanTarget.NEW_TAG
                                Button(
                                    onClick = {
                                        if (!isReaderConnected) return@Button
                                        activeScanTarget = ScanTarget.NEW_TAG
                                        scanningOn = !scanningOn
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isScanningNew) Color(0xFFEF4444) else MaterialTheme.colorScheme.secondary
                                    ),
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.Nfc, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (isScanningNew) "หยุดยิง..." else "กดเพื่อยิงสแกนแท็กใหม่")
                                }

                                OutlinedTextField(
                                    value = newTagRfid,
                                    onValueChange = { newTagRfid = normalizeToken(it) },
                                    label = { Text("รหัสแท็กใหม่ (RFID)") },
                                    placeholder = { Text("พิมพ์หรือยิงสแกนบาร์โค้ดที่นี่...") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp)
                                )

                                if (newTagRfid.isNotBlank() && oldTagRfid.isNotBlank() && oldTagProduct?.isNotFound != true) {
                                    Spacer(Modifier.height(8.dp))
                                    Button(
                                        onClick = { executeChangeRfid() },
                                        modifier = Modifier.fillMaxWidth().height(48.dp),
                                        enabled = !loading,
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        if (loading) {
                                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                                        } else {
                                            Text("ยืนยันเปลี่ยนเป็นรหัสแท็กใหม่", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── Tab 1: ยิงสแกนลบแท็กรัวๆ ──
            if (selectedTab == 1) {
                item {
                    ElevatedCard(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = Color.White)
                    ) {
                        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("สแกนแท็กที่ต้องการลบ (ยิงลื่นรัวๆ 100%)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444))

                            val isScanningDelete = scanningOn && activeScanTarget == ScanTarget.DELETE_TAG
                            Button(
                                onClick = {
                                    if (!isReaderConnected) return@Button
                                    activeScanTarget = ScanTarget.DELETE_TAG
                                    scanningOn = !scanningOn
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isScanningDelete) Color(0xFFEF4444) else Color(0xFFEF4444)
                                ),
                                modifier = Modifier.fillMaxWidth().height(64.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Nfc, contentDescription = null, tint = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text(if (isScanningDelete) "หยุดยิง..." else "กดปืนยิงสแกนเก็บแท็กเข้าระบบ", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }

                // สรุปยอดเตรียมลบ
                if (deleteList.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("สแกนสะสม: ${deleteList.size} ชิ้น", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // ปุ่มตรวจสอบรวบยอด
                                if (deleteList.any { !it.isVerified }) {
                                    Button(
                                        onClick = { verifyDeleteListInfo() },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("ตรวจสอบ", fontWeight = FontWeight.Bold)
                                    }
                                }
                                // ปุ่มยืนยันการลบ
                                Button(
                                    onClick = { showDeleteConfirmDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("ลบทั้งหมด", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // แสดงลิสต์ของสินค้าที่จะลบ (สามารถกดเพื่อลบออกจากคิวได้)
                    items(deleteList) { tag ->
                        CardProductInfo(product = tag, showDeleteAction = true, onDeleteClick = {
                            deleteList = deleteList.filter { it.rfid != tag.rfid }
                        })
                    }
                }
            }

            // แสดงสถานะ Loading เมื่อกำลังค้นหาเน็ต
            if (loading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            // ── Error Message ──
            if (errorMsg != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMsg!!,
                            color = Color(0xFFDC2626),
                            modifier = Modifier.padding(16.dp),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // ── Success Message ──
            if (successMsg != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = successMsg!!,
                            color = Color(0xFF059669),
                            modifier = Modifier.padding(16.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    // ── Dialog ยืนยันการลบ ──
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("ยืนยันการลบแท็ก RFID", fontWeight = FontWeight.Bold) },
            text = { Text("คุณแน่ใจหรือไม่ว่าต้องการลบแท็ก RFID ทั้งหมด ${deleteList.size} รายการนี้ออกจากฐานข้อมูลระบบ?") },
            confirmButton = {
                Button(
                    onClick = { executeDeleteRfidBatch() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("ยืนยันการลบ", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("ยกเลิก")
                }
            }
        )
    }
}

// ── การ์ดข้อมูลสินค้า ──
@Composable
private fun CardProductInfo(
    product: ScannedTag,
    showDeleteAction: Boolean = false,
    onDeleteClick: () -> Unit = {}
) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFF1F5F9)),
                contentAlignment = Alignment.Center
            ) {
                if (product.imageUrl.isNullOrBlank()) {
                    Icon(Icons.Outlined.Image, contentDescription = null, tint = Color.Gray)
                } else {
                    AsyncImage(
                        model = product.imageUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(product.productName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = if (product.isNotFound) Color.Red else Color.Unspecified)
                if (product.isVerified) {
                    Spacer(Modifier.height(4.dp))
                    Text("SKU: ${product.sku ?: "-"}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                } else {
                    Spacer(Modifier.height(4.dp))
                    Text("สถานะ: รอตรวจสอบข้อมูลสินค้า", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                Spacer(Modifier.height(4.dp))
                Text("RFID: ${product.rfid}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            if (showDeleteAction) {
                IconButton(onClick = onDeleteClick) {
                    Icon(Icons.Default.Delete, contentDescription = "ดึงออก", tint = Color(0xFFEF4444))
                }
            }
        }
    }
}

// ── API ─────────────────────────────────────────────────────────────
private object RfidApi {
    private val client = OkHttpClient()

    private fun bearer(token: String?) = token?.takeIf { it.isNotBlank() } ?: SupabaseConfig.ANON_KEY

    private suspend fun http(req: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
    }

    private fun optStr(o: JSONObject, key: String): String? {
        val v = o.opt(key)
        return if (v == null || v.toString() == "null") null else v.toString()
    }

    // ฟังก์ชันช่วยดึงข้อมูลสินค้าที่สอดคล้องกับรหัสแท็ก RFID
    suspend fun fetchRfidProductInfo(rfid: String, token: String?): ScannedTag? {
        val q = URLEncoder.encode(rfid.trim(), "UTF-8")
        val url = "${SupabaseConfig.URL}/rest/v1/product_rfid_tags" +
                "?rfid=eq.$q&select=id,product_id,branch_id,status&limit=1"

        val req = Request.Builder().url(url).get()
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(token)}")
            .addHeader("Accept", "application/json")
            .build()

        val (code, raw) = http(req)
        if (code !in 200..299) return null

        val arr = JSONArray(raw)
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        val productId = o.getLong("product_id")
        val tagId = o.optLong("id")
        val branchId = if (o.isNull("branch_id")) null else o.getLong("branch_id")

        // ดึงต่อเข้าไปที่ products เพื่อเอาภาพและ SKU
        val pUrl = "${SupabaseConfig.URL}/rest/v1/products" +
                "?id=eq.$productId&select=id,name,sku,image_url&limit=1"

        val pReq = Request.Builder().url(pUrl).get()
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(token)}")
            .addHeader("Accept", "application/json")
            .build()

        val (pCode, pRaw) = http(pReq)
        if (pCode !in 200..299) return null

        val pArr = JSONArray(pRaw)
        if (pArr.length() == 0) return null
        val pObj = pArr.getJSONObject(0)

        // ดึง Image Public URL
        val rawImg = optStr(pObj, "image_url")
        var finalImgUrl: String? = null
        if (!rawImg.isNullOrBlank()) {
            if (rawImg.startsWith("http") || rawImg.startsWith("blob:")) {
                finalImgUrl = rawImg
            } else {
                finalImgUrl = "${SupabaseConfig.URL}/storage/v1/object/public/product-images/$rawImg"
            }
        }

        return ScannedTag(
            rfid = rfid,
            productName = pObj.optString("name", "สินค้า"),
            sku = optStr(pObj, "sku"),
            imageUrl = finalImgUrl,
            productId = productId,
            tagId = tagId,
            branchId = branchId,
            isVerified = true
        )
    }

    suspend fun changeRfidTag(oldRfid: String, newRfid: String, token: String?): Boolean {
        val q = URLEncoder.encode(oldRfid.trim(), "UTF-8")
        val url = "${SupabaseConfig.URL}/rest/v1/product_rfid_tags?rfid=eq.$q"

        val bodyJson = "{\"rfid\": \"${newRfid.trim()}\"}"
        val body = bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType())

        val req = Request.Builder().url(url).patch(body)
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(token)}")
            .addHeader("Content-Type", "application/json")
            .build()

        val (code, _) = http(req)
        return code in 200..299
    }

    suspend fun deleteRfidTag(rfid: String, token: String?): Boolean {
        val q = URLEncoder.encode(rfid.trim(), "UTF-8")
        val url = "${SupabaseConfig.URL}/rest/v1/product_rfid_tags?rfid=eq.$q"

        val req = Request.Builder().url(url).delete()
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(token)}")
            .build()

        val (code, _) = http(req)
        return code in 200..299
    }

    suspend fun insertDeletedHistory(tag: ScannedTag, reason: String, token: String?): Boolean {
        if (tag.tagId == null || tag.tagId == 0L) return false
        val url = "${SupabaseConfig.URL}/rest/v1/deleted_rfid_tags"
        
        val bodyJson = JSONObject().apply {
            put("original_tag_id", tag.tagId)
            put("product_id", tag.productId)
            put("rfid", tag.rfid)
            if (tag.branchId != null) put("branch_id", tag.branchId)
            put("reason", reason)
            put("deleted_by", "PDA User")
        }.toString()
        
        val body = bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url).post(body)
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(token)}")
            .addHeader("Content-Type", "application/json")
            .build()
            
        val (code, _) = http(req)
        return code in 200..299
    }
}
