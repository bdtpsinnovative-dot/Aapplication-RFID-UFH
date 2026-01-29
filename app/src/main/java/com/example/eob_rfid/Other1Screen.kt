package com.example.eob_rfid

import android.Manifest
import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.DecimalFormat
import java.util.concurrent.Executors

private const val IMAGE_COL = "image_url"
private const val STOCK_TABLE = "stock"
private const val STOCK_QTY_COL = "qty"

private object Other1Models {
    data class Product(
        val id: Long,
        val name: String,
        val barcode: String?,
        val price: Double?,
        val color: String?,
        val imageUrl: String?
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Other1Screen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val fm = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var barcodeText by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var product by remember { mutableStateOf<Other1Models.Product?>(null) }
    var stockQty by remember { mutableStateOf<Double?>(null) }

    var openScanner by remember { mutableStateOf(false) }

    val moneyFmt = remember { DecimalFormat("#,##0.00") }
    val qtyFmt = remember { DecimalFormat("#,##0.###") }

    fun clearResult() {
        error = null
        product = null
        stockQty = null
    }

    fun search() {
        val q = barcodeText.trim()
        if (q.isBlank()) {
            clearResult()
            error = "กรุณากรอก/สแกนบาร์โค้ดก่อน"
            return
        }

        scope.launch {
            loading = true
            clearResult()
            try {
                // แก้ไข: เปลี่ยนจาก SessionStore.getAccessToken(ctx) เป็น SessionManager.accessToken
                val token = SessionManager.accessToken

                val p = Api.fetchProductByBarcode(q, token)
                if (p == null) {
                    error = "ไม่พบสินค้า (barcode: $q)"
                } else {
                    product = p
                    stockQty = Api.fetchStockQty(p.id, token) ?: 0.0
                }
            } catch (e: Exception) {
                error = e.message ?: "เกิดข้อผิดพลาด"
            } finally {
                loading = false
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("ค้นหาสินค้า") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "ย้อนกลับ")
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // -------- Search Card --------
            item {
                ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "สแกนหรือพิมพ์บาร์โค้ด",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        OutlinedTextField(
                            value = barcodeText,
                            onValueChange = { barcodeText = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Barcode") },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Search
                            ),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    fm.clearFocus()
                                    if (!loading) search()
                                }
                            ),
                            trailingIcon = {
                                IconButton(onClick = { openScanner = true }) {
                                    Icon(Icons.Outlined.CameraAlt, contentDescription = "สแกน")
                                }
                            }
                        )

                        Button(
                            onClick = { fm.clearFocus(); if (!loading) search() },
                            enabled = !loading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("ค้นหา") }

                        if (loading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            // -------- Error --------
            if (!loading && error != null) {
                item {
                    ElevatedCard(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = error!!,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // -------- Result Card --------
            val p = product
            if (!loading && p != null) {
                item {
                    ElevatedCard(shape = RoundedCornerShape(24.dp)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                p.name,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )

                            ProductImage(
                                imageUrl = p.imageUrl,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                StatCard(
                                    title = "จำนวน",
                                    value = qtyFmt.format(stockQty ?: 0.0),
                                    modifier = Modifier.weight(1f)
                                )
                                StatCard(
                                    title = "ราคา",
                                    value = moneyFmt.format(p.price ?: 0.0),
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("สี:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                AssistChip(
                                    onClick = {},
                                    label = { Text(p.color?.takeIf { it.isNotBlank() } ?: "-") }
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    p.barcode?.let { "Barcode: $it" } ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        BarcodeScannerDialog(
            open = openScanner,
            onDismiss = { openScanner = false },
            onScanned = { code ->
                openScanner = false
                barcodeText = code
                fm.clearFocus()
                if (!loading) search()
            }
        )
    }
}

@Composable
private fun ProductImage(imageUrl: String?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)

    Card(shape = shape) {
        Box(
            modifier = modifier
                .height(220.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (imageUrl.isNullOrBlank()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Image, contentDescription = null)
                    Spacer(Modifier.height(6.dp))
                    Text("ไม่มีรูป", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = "รูปสินค้า",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/* ----------------------------- Scanner Logic & UI (Beautified) ----------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BarcodeScannerDialog(
    open: Boolean,
    onDismiss: () -> Unit,
    onScanned: (String) -> Unit
) {
    if (!open) return

    var granted by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) } // เพิ่มสถานะไฟฉาย
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> granted = ok }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // ใช้ Dialog แบบ Full Screen เพื่อความสวยงาม
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false, // เต็มจอ
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(Modifier.fillMaxSize()) {
                if (!granted) {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("ต้องการสิทธิ์เข้าถึงกล้อง", color = Color.White)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("อนุญาต")
                        }
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = onDismiss) {
                            Text("ปิด", color = Color.LightGray)
                        }
                    }
                } else {
                    // 1. Layer กล้อง (ล่างสุด)
                    CameraBarcodePreview(
                        torchEnabled = torchEnabled,
                        onResult = onScanned
                    )

                    // 2. Layer Overlay (พื้นมืดเจาะรู + เส้นเลเซอร์)
                    ScannerOverlay(boxSize = 280.dp)

                    // 3. Layer Controls (ปุ่มบน + Text ล่าง)
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(vertical = 48.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Top Buttons
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // ปุ่มปิด
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.background(Color.Black.copy(0.4f), CircleShape)
                            ) {
                                Icon(Icons.Outlined.Close, "Close", tint = Color.White)
                            }
                            // ปุ่มไฟฉาย
                            IconButton(
                                onClick = { torchEnabled = !torchEnabled },
                                modifier = Modifier.background(
                                    if (torchEnabled) MaterialTheme.colorScheme.primary else Color.Black.copy(0.4f),
                                    CircleShape
                                )
                            ) {
                                val icon = if (torchEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff
                                Icon(icon, "Flash", tint = Color.White)
                            }
                        }

                        // Bottom Text
                        Text(
                            text = "วางบาร์โค้ดให้อยู่ในกรอบ",
                            color = Color.White.copy(0.8f),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .background(Color.Black.copy(0.4f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScannerOverlay(boxSize: Dp) {
    val infiniteTransition = rememberInfiniteTransition(label = "laser_anim")
    val animProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laser_val"
    )

    val primaryColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasW = size.width
        val canvasH = size.height
        val boxSizePx = boxSize.toPx()
        val cx = canvasW / 2
        val cy = canvasH / 2

        val left = cx - boxSizePx / 2
        val top = cy - boxSizePx / 2
        val right = left + boxSizePx
        val bottom = top + boxSizePx
        val cornerRad = 24.dp.toPx()

        // 1. วาดพื้นหลังมืด (เจาะรู)
        val rectPath = Path().apply { addRect(Rect(0f, 0f, canvasW, canvasH)) }
        val holePath = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    rect = Rect(left, top, right, bottom),
                    cornerRadius = CornerRadius(cornerRad, cornerRad)
                )
            )
        }
        val finalPath = Path.combine(PathOperation.Difference, rectPath, holePath)
        drawPath(path = finalPath, color = Color.Black.copy(alpha = 0.6f))

        // 2. วาดมุม (Corner Indicators)
        val strokeW = 4.dp.toPx()
        val lineLen = 30.dp.toPx()

        drawContext.canvas.save()
        clipPath(holePath) {
            // กรอบบางๆ รอบรู
            drawRoundRect(
                color = Color.White.copy(alpha = 0.2f),
                topLeft = Offset(left, top),
                size = Size(boxSizePx, boxSizePx),
                cornerRadius = CornerRadius(cornerRad, cornerRad),
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // มุมบนซ้าย
        drawLine(primaryColor, Offset(left, top), Offset(left + lineLen, top), strokeW)
        drawLine(primaryColor, Offset(left, top), Offset(left, top + lineLen), strokeW)
        // มุมบนขวา
        drawLine(primaryColor, Offset(right, top), Offset(right - lineLen, top), strokeW)
        drawLine(primaryColor, Offset(right, top), Offset(right, top + lineLen), strokeW)
        // มุมล่างซ้าย
        drawLine(primaryColor, Offset(left, bottom), Offset(left + lineLen, bottom), strokeW)
        drawLine(primaryColor, Offset(left, bottom), Offset(left, bottom - lineLen), strokeW)
        // มุมล่างขวา
        drawLine(primaryColor, Offset(right, bottom), Offset(right - lineLen, bottom), strokeW)
        drawLine(primaryColor, Offset(right, bottom), Offset(right, bottom - lineLen), strokeW)

        // 3. วาดเลเซอร์วิ่งขึ้นลง
        val laserY = top + (boxSizePx * animProgress)
        // แสงฟุ้ง
        drawLine(
            color = primaryColor.copy(alpha = 0.5f),
            start = Offset(left + 10f, laserY),
            end = Offset(right - 10f, laserY),
            strokeWidth = 4.dp.toPx()
        )
        // เส้นหลัก
        drawLine(
            color = Color.Red,
            start = Offset(left, laserY),
            end = Offset(right, laserY),
            strokeWidth = 2.dp.toPx()
        )

        drawContext.canvas.restore()
    }
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun CameraBarcodePreview(
    torchEnabled: Boolean,
    onResult: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val previewView = remember { PreviewView(context) }
    val analyzeExecutor = remember { Executors.newSingleThreadExecutor() }
    var scannedOnce by remember { mutableStateOf(false) }

    // เก็บ Camera เพื่อคุมไฟฉาย
    var camera: Camera? by remember { mutableStateOf(null) }

    LaunchedEffect(torchEnabled, camera) {
        try {
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                camera?.cameraControl?.enableTorch(torchEnabled)
            }
        } catch (_: Exception) {}
    }

    DisposableEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        var cameraProvider: ProcessCameraProvider? = null

        providerFuture.addListener({
            cameraProvider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS) // รองรับหมด
                .build()

            val scanner = BarcodeScanning.getClient(options)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(analyzeExecutor) { proxy ->
                val media = proxy.image
                if (media == null || scannedOnce) {
                    proxy.close()
                    return@setAnalyzer
                }
                try {
                    val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(input)
                        .addOnSuccessListener { list ->
                            if (scannedOnce) return@addOnSuccessListener
                            val raw = list.firstOrNull()?.rawValue?.trim()
                            if (!raw.isNullOrBlank()) {
                                scannedOnce = true
                                onResult(raw)
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                } catch (_: Exception) {
                    proxy.close()
                }
            }

            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (_: Exception) {}
        }, mainExecutor)

        onDispose {
            try {
                camera?.cameraControl?.enableTorch(false)
                cameraProvider?.unbindAll()
            } catch (_: Exception) {}
            analyzeExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}

/* ----------------------------- API ----------------------------- */

private object Api {
    private val client = OkHttpClient()

    private fun bearer(accessToken: String?) =
        accessToken?.takeIf { it.isNotBlank() } ?: SupabaseConfig.ANON_KEY

    private suspend fun http(req: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { res ->
            res.code to (res.body?.string().orEmpty())
        }
    }

    suspend fun fetchProductByBarcode(barcode: String, accessToken: String?): Other1Models.Product? {
        val q = URLEncoder.encode(barcode, "UTF-8")

        val select = "id,name,barcode,price,color,$IMAGE_COL"
        val url =
            "${SupabaseConfig.URL}/rest/v1/products" +
                    "?barcode=eq.$q" +
                    "&select=$select" +
                    "&limit=1"

        val req = Request.Builder()
            .url(url)
            .get()
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
            .addHeader("Accept", "application/json")
            .build()

        val (code, raw) = http(req)
        if (code !in 200..299) throw IllegalStateException("อ่าน products ไม่สำเร็จ ($code) $raw")

        val arr = JSONArray(raw)
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)

        fun optStr(key: String): String? {
            val v = o.opt(key)
            return if (v == null || v.toString() == "null") null else v.toString()
        }

        fun optD(key: String): Double? {
            val v = o.opt(key)
            return when (v) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull()
                else -> null
            }
        }

        return Other1Models.Product(
            id = o.getLong("id"),
            name = o.optString("name", "สินค้า"),
            barcode = optStr("barcode"),
            price = optD("price"),
            color = optStr("color"),
            imageUrl = optStr(IMAGE_COL)
        )
    }

    suspend fun fetchStockQty(productId: Long, accessToken: String?): Double? {
        val url =
            "${SupabaseConfig.URL}/rest/v1/$STOCK_TABLE" +
                    "?id=eq.$productId" +
                    "&select=$STOCK_QTY_COL" +
                    "&limit=1"

        val req = Request.Builder()
            .url(url)
            .get()
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
            .addHeader("Accept", "application/json")
            .build()

        val (code, raw) = http(req)
        if (code !in 200..299) throw IllegalStateException("อ่าน $STOCK_TABLE ไม่สำเร็จ ($code) $raw")

        val arr = JSONArray(raw)
        if (arr.length() == 0) return null
        val o: JSONObject = arr.getJSONObject(0)

        val v = o.opt(STOCK_QTY_COL)
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }
}