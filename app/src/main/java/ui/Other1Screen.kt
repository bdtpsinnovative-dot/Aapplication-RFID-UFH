package ui

import android.Manifest
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.DecimalFormat

// Hardware SDK
import com.xlzn.hcpda.uhf.UHFReader
import android.hardware.UHFDevice
import com.magicrf.uhfreaderlib.reader.UhfReader as MagicUhfReader
import com.magicrf.uhfreaderlib.reader.Tools

private const val IMAGE_COL = "image_url"
private const val STOCK_TABLE = "stock"
private const val STOCK_QTY_COL = "qty"

private object P1Models {
    data class Product(
        val id: Long,
        val name: String,
        val sku: String?,
        val barcode: String?,
        val price: Double?,
        val color: String?,
        val imageUrl: String?
    )

    data class RfidInfo(
        val rfid: String,
        val status: String,
        val lotId: Long?
    )
}

private data class RfidTagRow(val productId: Long, val status: String, val lotId: Long?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Other1Screen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val fm = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // ── State สำหรับระบบตรวจสอบ (Verification Flow) ──────────────────────────
    var barcodeInput by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }

    var referenceProduct by remember { mutableStateOf<P1Models.Product?>(null) }
    var referenceStockQty by remember { mutableStateOf<Double?>(null) }

    var rfidInput by remember { mutableStateOf("") }
    var scannedRfidInfo by remember { mutableStateOf<P1Models.RfidInfo?>(null) }
    var isMatch by remember { mutableStateOf<Boolean?>(null) }

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val qtyFmt = remember { DecimalFormat("#,##0.###") }
    val moneyFmt = remember { DecimalFormat("#,##0.00") }

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

    // ฟังก์ชันสำหรับสั่งให้ คลุมดำช่อง Barcode และดึง Focus กลับมา
    fun refocusBarcode() {
        barcodeInput = barcodeInput.copy(selection = TextRange(0, barcodeInput.text.length))
        try {
            focusRequester.requestFocus()
        } catch (e: Exception) {
            // ป้องกันแอปเด้งกรณี UI ยังวาดไม่เสร็จ
        }
    }

    // Auto Focus ทันทีที่เข้ามาหน้านี้
    LaunchedEffect(Unit) {
        delay(100)
        refocusBarcode()
    }

    // ── Verification Functions ────────────────────────────────────────────────
    fun resetVerification() {
        scannedRfidInfo = null
        isMatch = null
        rfidInput = ""
    }

    // ขั้นที่ 1: ค้นหาสินค้าจาก Barcode/SKU เพื่อตั้งเป็นตัวอ้างอิง
    fun searchReferenceProduct() {
        val q = barcodeInput.text.trim()
        if (q.isBlank()) {
            error = "กรุณากรอก Barcode หรือ SKU"
            refocusBarcode() // ว่างเปล่าก็ดึงกลับมาให้
            return
        }
        scope.launch {
            loading = true
            error = null
            referenceProduct = null
            referenceStockQty = null
            resetVerification()
            try {
                val token = SessionManager.accessToken
                val p = P1Api.fetchProductByCode(q, token)
                if (p == null) {
                    error = "ไม่พบสินค้า (รหัส: $q)"
                } else {
                    referenceProduct = p
                    referenceStockQty = P1Api.fetchStockQty(p.id, token) ?: 0.0
                }
            } catch (e: Exception) {
                error = AppError.resolve(e)
            } finally {
                loading = false
                refocusBarcode() // ★ ค้นหาจบปุ๊บ (เจอหรือไม่เจอ) ดึง Focus คลุมดำกลับมาทันที
            }
        }
    }

    // ขั้นที่ 2: ตรวจสอบ RFID ว่าตรงกับสินค้าอ้างอิงไหม
    fun verifyRfidTag(tagRfid: String) {
        val q = tagRfid.trim()
        rfidInput = q

        if (referenceProduct == null) {
            error = "กรุณาค้นหาสินค้าต้นแบบก่อนยิง RFID"
            refocusBarcode()
            return
        }

        scope.launch {
            loading = true
            error = null
            try {
                val token = SessionManager.accessToken
                val tag = P1Api.fetchRfidTag(q, token)
                if (tag == null) {
                    error = "ไม่พบ RFID Tag: $q ในระบบ"
                    isMatch = false
                } else {
                    scannedRfidInfo = P1Models.RfidInfo(rfid = q, status = tag.status, lotId = tag.lotId)
                    isMatch = (tag.productId == referenceProduct!!.id)
                }
            } catch (e: Exception) {
                error = AppError.resolve(e)
                isMatch = false
            } finally {
                loading = false
                refocusBarcode() // ★ เช็ก RFID จบปุ๊บ (ตรงหรือไม่ตรง) เด้งไปโฟกัสช่องบาร์โค้ดพร้อมยิงตัวใหม่ทันที!
            }
        }
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
                        Log.e("P1UHF", "HC Init: ${t.message}")
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
                    Log.e("P1UHF", "MagicRF Init: ${t.message}")
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

    // รับ tag จาก channel → หยุดยิง → นำไป Verify กับสินค้า
    LaunchedEffect(Unit) {
        for (tag in scanCh) {
            if (loading) continue
            scanningOn = false
            // เอา fm.clearFocus() ออก เพื่อไม่ให้กวน FocusFlow
            verifyRfidTag(tag)
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

    // ── Clean UI ───────────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("ตรวจสอบ Barcode & RFID", fontWeight = FontWeight.Bold) },
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
        containerColor = Color(0xFFF8FAFC) // สีพื้นหลัง Clean
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

            // ── Step 1: สแกน Barcode สินค้า ──
            item {
                Text(
                    "ขั้นที่ 1: เลือกสินค้าต้นแบบ",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                ElevatedCard(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.padding(16.dp).fillMaxWidth()) {
                        OutlinedTextField(
                            value = barcodeInput,
                            onValueChange = { barcodeInput = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester), // ผูก Focus
                            singleLine = true,
                            placeholder = { Text("สแกนหรือพิมพ์ Barcode / SKU") },
                            leadingIcon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { searchReferenceProduct() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = Color(0xFFE2E8F0)
                            )
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { searchReferenceProduct() },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            enabled = !loading,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("ดึงข้อมูลสินค้า")
                        }

                        if (loading && referenceProduct == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                    }
                }
            }

            // แสดงการ์ดสินค้าถ้าค้นเจอ
            if (referenceProduct != null) {
                item {
                    VerifyProductCard(
                        product = referenceProduct!!,
                        qtyStr = qtyFmt.format(referenceStockQty ?: 0.0),
                        priceStr = moneyFmt.format(referenceProduct!!.price ?: 0.0)
                    )
                }

                // ── Step 2: ยิงตรวจสอบ RFID ──
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "ขั้นที่ 2: ยิงตรวจสอบ RFID",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))

                    val btnColor = if (scanningOn) Color(0xFFEF4444) else MaterialTheme.colorScheme.primary
                    Button(
                        onClick = {
                            if (!isReaderConnected) return@Button
                            scanningOn = !scanningOn
                        },
                        enabled = isReaderConnected && !loading,
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = btnColor)
                    ) {
                        Icon(Icons.Default.Nfc, contentDescription = null, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (scanningOn) "กำลังรอสัญญาณ RFID..." else "กดเพื่อยิง RFID",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // ── Error Message ──
            if (error != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = error!!,
                            color = Color(0xFFDC2626),
                            modifier = Modifier.padding(16.dp),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // ── แสดงผลลัพธ์ว่าตรงกันไหม ──
            if (isMatch != null) {
                item {
                    VerificationResultCard(isMatch = isMatch!!, rfid = rfidInput)
                }
            }

            // ดันพื้นที่ด้านล่างสุดไม่ให้ติดขอบจอ
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

// ── UI Components ที่เป็น Private ─────────────────────────

@Composable
private fun VerifyProductCard(product: P1Models.Product, qtyStr: String, priceStr: String) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.White)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(90.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFF1F5F9)),
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
            Spacer(Modifier.width(16.dp))
            Column {
                Text(product.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("ราคา: ฿$priceStr", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("คงเหลือ: $qtyStr", style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray)
            }
        }
    }
}

@Composable
private fun VerificationResultCard(isMatch: Boolean, rfid: String) {
    val bgColor = if (isMatch) Color(0xFFECFDF5) else Color(0xFFFEF2F2)
    val contentColor = if (isMatch) Color(0xFF059669) else Color(0xFFDC2626)
    val icon = if (isMatch) Icons.Default.CheckCircle else Icons.Default.Cancel

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = bgColor)
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(36.dp)).background(contentColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                if (isMatch) "ข้อมูลตรงกัน" else "ข้อมูลไม่ตรงกัน!",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = contentColor
            )
            Spacer(Modifier.height(8.dp))
            Text("RFID Tag: $rfid", style = MaterialTheme.typography.bodyMedium, color = contentColor.copy(alpha = 0.8f))
        }
    }
}

// ── API ─────────────────────────────────────────────────────────────

private object P1Api {
    private val client = OkHttpClient()

    private fun bearer(token: String?) = token?.takeIf { it.isNotBlank() } ?: SupabaseConfig.ANON_KEY

    private suspend fun http(req: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
    }

    private fun optStr(o: JSONObject, key: String): String? {
        val v = o.opt(key)
        return if (v == null || v.toString() == "null") null else v.toString()
    }

    private fun optD(o: JSONObject, key: String): Double? = when (val v = o.opt(key)) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    suspend fun fetchRfidTag(rfid: String, token: String?): RfidTagRow? {
        val q = URLEncoder.encode(rfid.trim(), "UTF-8")
        val url = "${SupabaseConfig.URL}/rest/v1/product_rfid_tags" +
                "?rfid=eq.$q&select=product_id,status,lot_id&limit=1"

        val req = Request.Builder().url(url).get()
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(token)}")
            .addHeader("Accept", "application/json")
            .build()

        val (code, raw) = http(req)
        if (code !in 200..299) throw IllegalStateException("อ่าน RFID tags ไม่สำเร็จ ($code)")

        val arr = JSONArray(raw)
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)

        return RfidTagRow(
            productId = o.getLong("product_id"),
            status = o.optString("status", "UNKNOWN"),
            lotId = if (o.isNull("lot_id")) null else o.optLong("lot_id")
        )
    }

    suspend fun fetchProductById(productId: Long, token: String?): P1Models.Product? {
        val url = "${SupabaseConfig.URL}/rest/v1/products" +
                "?id=eq.$productId&select=id,name,sku,barcode,price,color,$IMAGE_COL&limit=1"

        val req = Request.Builder().url(url).get()
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(token)}")
            .addHeader("Accept", "application/json")
            .build()

        val (code, raw) = http(req)
        if (code !in 200..299) throw IllegalStateException("อ่าน products ไม่สำเร็จ ($code)")

        val arr = JSONArray(raw)
        if (arr.length() == 0) return null
        return mapProduct(arr.getJSONObject(0))
    }

    suspend fun fetchProductByCode(code: String, token: String?): P1Models.Product? {
        val c = code.trim()
        val idLong = c.toLongOrNull()
        val orExpr = buildString {
            append("(barcode.eq.$c,sku.eq.$c")
            if (idLong != null) append(",id.eq.$idLong")
            append(")")
        }
        val url = "${SupabaseConfig.URL}/rest/v1/products" +
                "?or=${URLEncoder.encode(orExpr, "UTF-8")}" +
                "&select=id,name,sku,barcode,price,color,$IMAGE_COL&limit=1"

        val req = Request.Builder().url(url).get()
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(token)}")
            .addHeader("Accept", "application/json")
            .build()

        val (code2, raw) = http(req)
        if (code2 !in 200..299) throw IllegalStateException("อ่าน products ไม่สำเร็จ ($code2)")

        val arr = JSONArray(raw)
        if (arr.length() == 0) return null
        return mapProduct(arr.getJSONObject(0))
    }

    private fun mapProduct(o: JSONObject) = P1Models.Product(
        id = o.getLong("id"),
        name = o.optString("name", "สินค้า"),
        sku = optStr(o, "sku"),
        barcode = optStr(o, "barcode"),
        price = optD(o, "price"),
        color = optStr(o, "color"),
        imageUrl = optStr(o, IMAGE_COL)
    )

    suspend fun fetchStockQty(productId: Long, token: String?): Double? {
        val url = "${SupabaseConfig.URL}/rest/v1/$STOCK_TABLE" +
                "?product_id=eq.$productId&select=$STOCK_QTY_COL&limit=1"

        val req = Request.Builder().url(url).get()
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(token)}")
            .addHeader("Accept", "application/json")
            .build()

        val (code, raw) = http(req)
        if (code !in 200..299) throw IllegalStateException("อ่าน stock ไม่สำเร็จ ($code)")

        val arr = JSONArray(raw)
        if (arr.length() == 0) return null
        val v = arr.getJSONObject(0).opt(STOCK_QTY_COL)
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }
}
