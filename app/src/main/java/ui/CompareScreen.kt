package ui

import android.Manifest
import android.media.AudioManager
import android.media.ToneGenerator
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.xlzn.hcpda.uhf.UHFReader
import android.hardware.UHFDevice
import com.magicrf.uhfreaderlib.reader.UhfReader as MagicUhfReader
import com.magicrf.uhfreaderlib.reader.Tools
import data.StockSearchApi
import data.TargetRfidItem

private enum class CompareDeviceType { UNKNOWN, HC, P8_MAGICRF }
private enum class CompareBannerStatus { NONE, OK, WARN, ERROR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // --- State สำหรับ Hardware & Status ---
    var isReaderConnected by remember { mutableStateOf(false) }
    var hcReader by remember { mutableStateOf<UHFReader?>(null) }
    var p8Device by remember { mutableStateOf<UHFDevice?>(null) }
    var p8Reader by remember { mutableStateOf<MagicUhfReader?>(null) }

    var currentDeviceType by remember { mutableStateOf(CompareDeviceType.UNKNOWN) }
    val deviceModel = remember { android.os.Build.MODEL }
    var permissionGranted by remember { mutableStateOf(false) }
    var scanningOn by remember { mutableStateOf(false) }

    // --- State สำหรับ UX หน้างาน ---
    var totalScannedCount by remember { mutableStateOf(0) }
    var isLocked by remember { mutableStateOf(false) }

    val foundProductIds = remember { mutableStateListOf<Long>() }
    var showOverviewDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }

    var bannerText by remember { mutableStateOf<String?>("Model: $deviceModel | Mfr: ${android.os.Build.MANUFACTURER}") }
    var bannerStatus by remember { mutableStateOf(CompareBannerStatus.WARN) }

    // --- State ของข้อมูล ---
    var targetMap by remember { mutableStateOf<Map<String, TargetRfidItem>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var currentFoundItem by remember { mutableStateOf<TargetRfidItem?>(null) }

    val uniqueTargetsCount = remember(targetMap) { targetMap.values.distinctBy { it.productId }.size }
    val scanCh = remember { Channel<String>(capacity = 4096) }

    // เสียงเตือน
    val toneGen = remember { ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME) }
    DisposableEffect(Unit) { onDispose { try { toneGen.release() } catch (_: Exception) {} } }

    fun normalizeToken(raw: String): String {
        return raw.trim().replace(" ", "").uppercase()
    }

    // 1. ขอ Permission
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            permissionGranted = true
            bannerText = "กำลังตรวจสอบรุ่นเครื่อง Hardware..."
        } else {
            bannerStatus = CompareBannerStatus.ERROR
            bannerText = "ต้องการ Permission เพื่อเชื่อมต่อ Hardware"
        }
    }

    // 2. โหลดข้อมูลเป้าหมายลง RAM
    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        )
        try {
            targetMap = StockSearchApi.getPreloadedTargets(context)
        } catch (e: Exception) {
            bannerStatus = CompareBannerStatus.ERROR
            bannerText = "โหลดคิวค้นหาพัง: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    // 3. ลอจิกเชื่อมต่อ Hardware
    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            currentDeviceType = when {
                deviceModel.contains("HC", ignoreCase = true) -> CompareDeviceType.HC
                deviceModel.contains("p8", ignoreCase = true) ||
                        deviceModel.contains("uhf", ignoreCase = true) ||
                        deviceModel.contains("magic", ignoreCase = true) -> CompareDeviceType.P8_MAGICRF
                else -> CompareDeviceType.HC
            }
            var hcOk = false
            if (currentDeviceType == CompareDeviceType.HC) {
                var retry = 0
                while (retry < 3 && !hcOk) {
                    try {
                        val reader = UHFReader.getInstance()
                        if (reader != null && reader.connect(context)?.data == true) {
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
                            hcReader = reader
                            isReaderConnected = true
                            hcOk = true
                            withContext(Dispatchers.Main) {
                                bannerStatus = CompareBannerStatus.OK
                                bannerText = "พร้อมค้นหา (HC | $deviceModel)"
                            }
                        } else {
                            retry++
                            delay(1500)
                        }
                    } catch (t: Throwable) {
                        retry++
                        delay(1500)
                    }
                }
            }
            if (!hcOk && (currentDeviceType == CompareDeviceType.P8_MAGICRF || !isReaderConnected)) {
                try {
                    currentDeviceType = CompareDeviceType.P8_MAGICRF
                    val device = UHFDevice(context)
                    device.UhfOpen()
                    MagicUhfReader.setPortPath(device.SerialDev())
                    val reader = MagicUhfReader.getInstance()
                    if (reader != null) {
                        p8Device = device
                        p8Reader = reader
                        isReaderConnected = true
                        withContext(Dispatchers.Main) {
                            bannerStatus = CompareBannerStatus.OK
                            bannerText = "พร้อมค้นหา (MagicRF | $deviceModel)"
                        }
                    }
                } catch (t: Throwable) {
                    withContext(Dispatchers.Main) {
                        bannerStatus = CompareBannerStatus.ERROR
                        bannerText = "ไม่รองรับ Hardware บนเครื่อง: $deviceModel"
                    }
                }
            }
        }
    }

    // 4. คืนทรัพยากรตอนปิดหน้าจอ
    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                scanningOn = false
                if (currentDeviceType == CompareDeviceType.HC) {
                    hcReader?.stopInventory()
                    hcReader?.disConnect()
                } else if (currentDeviceType == CompareDeviceType.P8_MAGICRF) {
                    p8Reader?.close()
                    p8Device?.UhfStop()
                }
            } catch (e: Exception) {}
        }
    }

    // 5. ควบคุมการสั่งยิงคลื่น
    LaunchedEffect(scanningOn) {
        if (!isReaderConnected) return@LaunchedEffect
        try {
            if (scanningOn) {
                if (currentDeviceType == CompareDeviceType.HC) {
                    hcReader?.startInventory()
                } else if (currentDeviceType == CompareDeviceType.P8_MAGICRF) {
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
                if (currentDeviceType == CompareDeviceType.HC) hcReader?.stopInventory()
            }
        } catch (e: Exception) {
            scanningOn = false
        }
    }

    // 6. Loop ดักรอข้อมูลที่สแกนเข้ามา
    LaunchedEffect(Unit) {
        var lastBeepMs = 0L // ย้ายมาไว้ข้างใน เพื่อให้จำค่าเวลาได้ถูกต้องโดยไม่ถูกหน้าจอดึงกลับไปเป็น 0

        for (rfid in scanCh) {
            totalScannedCount++

            if (targetMap.containsKey(rfid)) {
                val found = targetMap[rfid]!!

                if (!foundProductIds.contains(found.productId)) {
                    foundProductIds.add(found.productId)
                }

                if (isLocked) {
                    // ถ้าระบบโฟกัส (Lock) อยู่ และเป็นสินค้าที่กำลังโฟกัส
                    if (currentFoundItem?.productId == found.productId) {
                        val now = System.currentTimeMillis()
                        // ให้ส่งเสียงสั้นๆ รัวๆ แบบเรดาร์ (Geiger counter)
                        if (now - lastBeepMs > 120) {
                            lastBeepMs = now
                            try { toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 50) } catch (_: Exception) {}
                        }
                    }
                } else {
                    // โหมดปกติ เปลี่ยนของไปเรื่อยๆ (ร้องเตือน 1 ครั้งยาวๆ)
                    if (currentFoundItem?.rfid != rfid) {
                        currentFoundItem = found
                        val now = System.currentTimeMillis()
                        if (now - lastBeepMs > 150) {
                            lastBeepMs = now
                            try { toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 100) } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.WifiTethering,
                            contentDescription = "Scanner",
                            modifier = Modifier.padding(end = 8.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("ค้นหาสินค้าด้วย RFID", fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBackIosNew,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showOverviewDialog = true }) {
                        Icon(Icons.Rounded.FormatListBulleted, contentDescription = "Overview", tint = Color(0xFF6366F1))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // --- Banner แจ้งสถานะ Hardware ---
            AnimatedVisibility(visible = bannerText != null && bannerStatus != CompareBannerStatus.NONE) {
                val (bg, txt, icon) = when (bannerStatus) {
                    CompareBannerStatus.OK -> Triple(Color(0xFF22C55E).copy(0.1f), Color(0xFF22C55E), Icons.Rounded.CheckCircle)
                    CompareBannerStatus.WARN -> Triple(Color(0xFFF59E0B).copy(0.1f), Color(0xFFF59E0B), Icons.Rounded.Warning)
                    CompareBannerStatus.ERROR -> Triple(Color(0xFFEF4444).copy(0.1f), Color(0xFFEF4444), Icons.Rounded.Error)
                    else -> Triple(Color.Gray.copy(0.1f), Color.Gray, Icons.Rounded.Info)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .background(bg, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(icon, null, tint = txt, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(bannerText ?: "", color = txt, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF6366F1))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("กำลังโหลดคิวค้นหา...", color = Color.Gray)
                    }
                }
                return@Scaffold
            }

            // แสดงยอดภาพรวมแบบด่วน (Clean & Modern)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.DataUsage, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("เป้าหมายค้นหาทั้งหมด", color = Color.DarkGray, fontWeight = FontWeight.Medium)
                    }
                    val isAllFound = foundProductIds.size == uniqueTargetsCount && uniqueTargetsCount > 0
                    Text(
                        text = "${foundProductIds.size} / $uniqueTargetsCount",
                        color = if (isAllFound) Color(0xFF22C55E) else Color(0xFF6366F1),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── UI แสดงผล ──
            if (currentFoundItem != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clickable { isLocked = !isLocked }, // ✅ กดล็อค/ปลดล็อค ด้วยการแตะที่การ์ดได้เลย
                    colors = CardDefaults.cardColors(containerColor = if (isLocked) Color(0xFF15803D) else Color(0xFF22C55E)),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // ปุ่มกดล็อคโฟกัส (ขยายพื้นที่กดให้ใหญ่ขึ้น)
                        IconButton(
                            onClick = { isLocked = !isLocked },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(56.dp) // ✅ เพิ่มพื้นที่กด
                        ) {
                            Icon(
                                if (isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                contentDescription = "Focus",
                                tint = if (isLocked) Color(0xFFFBBF24) else Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(32.dp) // ✅ ขยายไอคอน
                            )
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp)
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.CheckCircleOutline, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("เจอแล้ว!", fontSize = 28.sp, color = Color.White, fontWeight = FontWeight.Black)
                            }

                            if (isLocked) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier
                                        .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.GpsFixed, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("โฟกัสเป้าหมายนี้อยู่ (ยิงเจอจะดังรัวๆ)", color = Color(0xFFFBBF24), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            } else {
                                // ✅ เพิ่มข้อความแนะแนวทางว่ากดหน้าจอเพื่อล็อคได้
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier
                                        .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.TouchApp, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("แตะที่พื้นที่สีเขียวเพื่อล็อคเป้าหมาย", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            // รูปภาพ Responsive ไม่ฟิกซ์ขนาดตายตัว แต่ใช้สัดส่วน
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.55f) // กว้าง 55% ของหน้าจอ
                                    .aspectRatio(1f)     // บังคับให้เป็นสี่เหลี่ยมจัตุรัส
                                    .sizeIn(maxWidth = 200.dp, maxHeight = 200.dp) // จำกัดไม่ให้ใหญ่เกินไปบน Tablet
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.White.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (currentFoundItem!!.imageUrl != null) {
                                    AsyncImage(
                                        model = currentFoundItem!!.imageUrl,
                                        contentDescription = "Product Image",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(Icons.Rounded.ImageNotSupported, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(48.dp))
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            Text(
                                text = currentFoundItem!!.productName,
                                fontSize = 22.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "SKU: ${currentFoundItem!!.sku}",
                                fontSize = 16.sp,
                                color = Color.White.copy(0.9f),
                                fontWeight = FontWeight.Medium
                            )

                            Spacer(modifier = Modifier.height(32.dp))

                            // ปุ่มลบออกจากคิว (ดีไซน์ใหม่)
                            Button(
                                onClick = {
                                    if (isDeleting) return@Button
                                    isDeleting = true

                                    val targetIdToClear = currentFoundItem!!.targetId
                                    val productIdToClear = currentFoundItem!!.productId

                                    scope.launch {
                                        val success = StockSearchApi.removeSearchTarget(context, targetIdToClear)
                                        if (success) {
                                            targetMap = targetMap.filterValues { it.productId != productIdToClear }
                                            foundProductIds.remove(productIdToClear)
                                            currentFoundItem = null
                                            isLocked = false
                                            Toast.makeText(context, "ลบออกจากคิวเรียบร้อย", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "ลบไม่สำเร็จ ลองใหม่อีกครั้ง", Toast.LENGTH_SHORT).show()
                                        }
                                        isDeleting = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                                modifier = Modifier.fillMaxWidth(0.9f)
                            ) {
                                if (isDeleting) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color(0xFF22C55E), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Rounded.TaskAlt, null, tint = Color(0xFF22C55E))
                                    Spacer(Modifier.width(8.dp))
                                    Text("เคลียร์สินค้า (หาเจอแล้ว)", color = Color(0xFF22C55E), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
            } else {
                // 🔵 กรณี: ไม่เจอสินค้า (เรดาร์สแกน) Responsive Layout
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFFF8FAFC), RoundedCornerShape(24.dp))
                        .border(
                            width = if (scanningOn) 2.dp else 1.dp,
                            color = if (scanningOn) Color(0xFF6366F1).copy(alpha = 0.5f) else Color(0xFFE2E8F0),
                            shape = RoundedCornerShape(24.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (scanningOn) {
                        val infiniteTransition = rememberInfiniteTransition(label = "radar")
                        val scale by infiniteTransition.animateFloat(
                            initialValue = 1f,
                            targetValue = 3f,
                            animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearOutSlowInEasing), repeatMode = RepeatMode.Restart),
                            label = "scale"
                        )
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0.6f,
                            targetValue = 0f,
                            animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearOutSlowInEasing), repeatMode = RepeatMode.Restart),
                            label = "alpha"
                        )

                        // วงคลื่นกระจาย
                        Box(
                            Modifier
                                .fillMaxWidth(0.3f)
                                .aspectRatio(1f)
                                .scale(scale)
                                .alpha(alpha)
                                .background(Color(0xFF6366F1), CircleShape)
                        )
                        // วงตรงกลาง
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.3f)
                                .aspectRatio(1f)
                                .background(Color(0xFF6366F1).copy(0.15f), CircleShape)
                                .border(2.dp, Color(0xFF6366F1), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.WifiTethering, null, tint = Color(0xFF6366F1), modifier = Modifier.size(48.dp))
                        }

                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("กำลังกวาดคลื่นค้นหา...", color = Color(0xFF6366F1), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.SensorsOff, null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(72.dp))
                            Spacer(Modifier.height(16.dp))
                            Text("กดปุ่มด้านล่างเพื่อเริ่มค้นหา", color = Color(0xFF94A3B8), fontWeight = FontWeight.Medium, fontSize = 16.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // แถบ Live Status Bar แสดงจำนวน Tag รวม
            AnimatedVisibility(visible = scanningOn) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .background(Color(0xFFF1F5F9), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val infiniteTransition = rememberInfiniteTransition(label = "dot")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 0.2f,
                        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                        label = "dotAlpha"
                    )
                    Box(Modifier.size(8.dp).alpha(alpha).background(Color(0xFFEF4444), CircleShape))

                    Spacer(Modifier.width(12.dp))
                    Text("กำลังสแกน... ปะทะคลื่นไปแล้ว: ", color = Color.Gray, fontSize = 13.sp)
                    Text("$totalScannedCount Tag", color = Color.DarkGray, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            // ปุ่มเริ่ม/หยุดสแกน
            Button(
                onClick = {
                    if (!isReaderConnected) {
                        Toast.makeText(context, "Hardware ยังไม่พร้อมใช้งาน", Toast.LENGTH_SHORT).show()
                    } else {
                        scanningOn = !scanningOn
                        if (!scanningOn) {
                            // ✅ ตอนกด "หยุดค้นหา" เราจะไม่เคลียร์ค่า currentFoundItem และ isLocked แล้ว
                            // เพื่อให้หน้าจอยังคงแสดงสินค้าล่าสุด หรือสินค้าที่ล็อคไว้ค้างอยู่
                        } else {
                            totalScannedCount = 0
                            // ✅ ตอน "เริ่มกวาดสัญญาณใหม่" จะเคลียร์หน้าจอก็ต่อเมื่อ ไม่ได้กดล็อคแม่กุญแจไว้เท่านั้น
                            if (!isLocked) {
                                currentFoundItem = null
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (!isReaderConnected) Color(0xFFE2E8F0) else if (scanningOn) Color(0xFFEF4444) else Color(0xFF6366F1),
                    contentColor = if (!isReaderConnected) Color.Gray else Color.White
                ),
            ) {
                Icon(if (scanningOn) Icons.Rounded.StopCircle else Icons.Rounded.WifiTethering, null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(if (scanningOn) "หยุดค้นหา" else "เริ่มกวาดสัญญาณ (Scan)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Dialog แสดงภาพรวมเฉพาะคิวที่สแกนเจอแล้ว + โชว์รูปภาพสินค้า
        if (showOverviewDialog) {
            AlertDialog(
                onDismissRequest = { showOverviewDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FactCheck, contentDescription = null, tint = Color(0xFF6366F1))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("สินค้าที่พบแล้ว (${foundProductIds.size} / $uniqueTargetsCount)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                },
                text = {
                    val foundItems = targetMap.values.distinctBy { it.productId }.filter { foundProductIds.contains(it.productId) }

                    if (foundItems.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Rounded.SearchOff, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("ยังไม่พบสินค้าที่ตรงเป้าหมาย", color = Color.Gray)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                            items(foundItems) { item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // กล่องรูปภาพใน List
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFFF1F5F9)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (item.imageUrl != null) {
                                            AsyncImage(
                                                model = item.imageUrl,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(Icons.Rounded.Image, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(24.dp))
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(item.productName, fontWeight = FontWeight.SemiBold, color = Color.DarkGray, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text("SKU: ${item.sku}", color = Color.Gray, fontSize = 12.sp)
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Icon(
                                        Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF22C55E),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showOverviewDialog = false }) {
                        Text("ปิด", fontWeight = FontWeight.Bold, color = Color(0xFF6366F1), fontSize = 16.sp)
                    }
                }
            )
        }
    }
}