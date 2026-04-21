package ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
// ✅ Import AuthManager
import data.AuthManager
import data.ProductLite
import data.SessionStore
import data.StockReceivingApi
import data.StockUpdateDto
import data.SupabaseProductsApi
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.util.concurrent.Executors

// --- Helper Functions ---
fun getProductImageUrl(imagePath: String?): String? {
    if (imagePath.isNullOrBlank()) return null
    if (imagePath.startsWith("http")) return imagePath
    val supabaseUrl = "https://zexflchjcycxrpjkuews.supabase.co"
    val bucketName = "product-images"
    return "$supabaseUrl/storage/v1/object/public/$bucketName/$imagePath"
}

// --- Data Class ---
data class ReceiveRow(
    val productId: Long,
    val code: String,
    val name: String,
    val price: Double,
    val qty: Int,
    val stockBefore: Double,
    val stockAfter: Double? = null,
    val imageUrl: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val branchId = remember { SessionStore.getBranchId(context) }
    // ❌ ลบบรรทัด val token = remember... ออก เพราะเราจะดึงใหม่สดๆ ทุกครั้ง

    val moneyFmt = remember { DecimalFormat("#,##0.00") }
    val qtyFmt = remember { DecimalFormat("#,##0.###") }

    var msg by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var codeInput by remember { mutableStateOf("") }
    var lastScanned by remember { mutableStateOf<String?>(null) }
    val rows = remember { mutableStateListOf<ReceiveRow>() }

    fun upsertRow(p: ProductLite, codeUsed: String, inc: Int, stockBefore: Double) {
        val idx = rows.indexOfFirst { it.productId == p.id }
        val imgUrl = p.image_url

        if (idx >= 0) {
            val old = rows[idx]
            if (old.stockAfter != null) {
                rows[idx] = old.copy(code = codeUsed, qty = inc, stockBefore = old.stockAfter, stockAfter = null, imageUrl = imgUrl)
            } else {
                val newQty = old.qty + inc
                if (stockBefore + newQty >= 0) {
                    rows[idx] = old.copy(qty = newQty, imageUrl = imgUrl)
                }
            }
        } else {
            rows.add(ReceiveRow(p.id, codeUsed, p.name, p.price, inc, stockBefore, null, imgUrl))
        }
    }

    fun handleCode(code: String, incQty: Int = 1) {
        val c = code.trim()
        if (c.isBlank()) return
        scope.launch {
            msg = null
            isError = false
            try {
                // ✅ ใช้ AuthManager ดึง Token ล่าสุด (ถ้าหมดอายุ มันจะต่อให้เอง)
                val token = AuthManager.getValidAccessToken(context)
                if (token.isNullOrBlank()) throw Exception("กรุณาล็อกอินใหม่")

                val p = SupabaseProductsApi.fetchByCode(c, token)
                if (p == null) {
                    msg = "ไม่พบสินค้า: $c"; isError = true; return@launch
                }
                val existIdx = rows.indexOfFirst { it.productId == p.id }
                if (existIdx >= 0 && rows[existIdx].stockAfter == null) {
                    upsertRow(p, c, incQty, rows[existIdx].stockBefore)
                } else if (existIdx >= 0 && rows[existIdx].stockAfter != null) {
                    upsertRow(p, c, incQty, rows[existIdx].stockAfter!!)
                } else {
                    val stockBefore = StockReceivingApi.fetchQty(p.id, branchId, token)
                    upsertRow(p, c, incQty, stockBefore)
                }
                lastScanned = c
                codeInput = ""
            } catch (e: Exception) {
                if (e.message?.contains("401") == true) msg = "Session หมดอายุ"; else msg = e.message
                isError = true
            }
        }
    }

    val camPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) showScanner = true else { msg = "ขอสิทธิ์กล้องก่อน"; isError = true }
    }

    fun openScanner() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showScanner = true
        else camPermLauncher.launch(Manifest.permission.CAMERA)
    }

    fun saveReceiving() {
        val pending = rows.filter { it.stockAfter == null }
        if (pending.isEmpty()) { msg = "ไม่มีรายการใหม่"; isError = true; return }
        scope.launch {
            saving = true; msg = null; isError = false
            try {
                // ✅ ใช้ AuthManager ก่อนบันทึก
                val token = AuthManager.getValidAccessToken(context)
                if (token.isNullOrBlank()) throw Exception("กรุณาล็อกอินใหม่")

                val ids = pending.map { it.productId }.distinct()
                val currentMap = StockReceivingApi.fetchQtyMap(ids, branchId, token)
                val updates = pending.map {
                    StockUpdateDto(
                        branchId,
                        it.productId,
                        (currentMap[it.productId] ?: 0.0) + it.qty
                    )
                }
                StockReceivingApi.upsertStockList(updates, token)
                for (i in rows.indices) {
                    val r = rows[i]
                    if (r.stockAfter == null) rows[i] = r.copy(stockAfter = (currentMap[r.productId] ?: 0.0) + r.qty)
                }
                msg = "บันทึกสำเร็จ!"; isError = false
            } catch (e: Exception) {
                msg = e.message; isError = true
            } finally { saving = false }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("รับสินค้าเข้า", fontWeight = FontWeight.Bold)
                            Text("Branch #$branchId", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Rounded.ArrowBack, null)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
                InputHeader(
                    codeInput = codeInput,
                    onCodeChange = { codeInput = it; msg = null },
                    onEnter = { handleCode(codeInput, 1) },
                    onScanClick = { openScanner() }
                )
            }
        },
        bottomBar = {
            BottomActionBar(
                rows = rows,
                saving = saving,
                onSave = { saveReceiving() }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            AnimatedVisibility(visible = !msg.isNullOrBlank()) {
                StatusBanner(msg = msg, isError = isError, lastScanned = lastScanned)
            }

            if (rows.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            "รายการทั้งหมด (${rows.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                        )
                    }
                    itemsIndexed(rows) { idx, r ->
                        ModernReceiveCard(
                            row = r,
                            moneyFmt = moneyFmt,
                            qtyFmt = qtyFmt,
                            saving = saving,
                            onQtyChange = { newVal -> if (r.stockBefore + newVal >= 0) rows[idx] = r.copy(qty = newVal, stockAfter = null) },
                            onDelete = { rows.removeAt(idx) }
                        )
                    }
                }
            }
        }

        if (showScanner) {
            ReceiveBarcodeScannerDialog(onDismiss = { showScanner = false }, onScanned = { showScanner = false; codeInput = it; handleCode(it, 1) })
        }
    }
}

// ... (UI Components อื่นๆ คงเดิม ไม่ต้องแก้) ...
// ... (InputHeader, StatusBanner, BottomActionBar, ModernReceiveCard, ModernQtyControl, EmptyState, Camera Logic) ...
// ให้คงโค้ดส่วนล่างไว้เหมือนเดิมนะครับ เพราะไม่ได้มีการใช้ Token ในส่วน UI
@Composable
fun InputHeader(
    codeInput: String,
    onCodeChange: (String) -> Unit,
    onEnter: () -> Unit,
    onScanClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shadowElevation = 4.dp,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            TextField(
                value = codeInput,
                onValueChange = onCodeChange,
                placeholder = { Text("สแกนหรือพิมพ์รหัสสินค้า...", style = TextStyle(color = Color.Gray)) },
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onEnter() }),
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary) }
            )
            Box(Modifier.width(1.dp).height(24.dp).background(Color.LightGray))
            IconButton(onClick = onScanClick, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.QrCodeScanner, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun StatusBanner(msg: String?, isError: Boolean, lastScanned: String?) {
    val bgColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer

    Card(
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isError) Icons.Rounded.Warning else Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = contentColor
            )
            Spacer(Modifier.width(12.dp))
            Column {
                if (!lastScanned.isNullOrBlank()) {
                    Text("รหัส: $lastScanned", style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.8f))
                }
                Text(msg ?: "", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = contentColor)
            }
        }
    }
}

@Composable
fun BottomActionBar(rows: List<ReceiveRow>, saving: Boolean, onSave: () -> Unit) {
    val pendingCount = rows.count { it.stockAfter == null }
    val totalQty = rows.filter { it.stockAfter == null }.sumOf { it.qty }

    Surface(
        shadowElevation = 16.dp,
        tonalElevation = 4.dp,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text("รายการรอรับ", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$totalQty", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text("ชิ้น ($pendingCount รายการ)", style = MaterialTheme.typography.bodyMedium, color = Color.Gray, modifier = Modifier.padding(bottom = 2.dp))
                }
            }
            Button(
                onClick = onSave,
                enabled = pendingCount > 0 && !saving,
                modifier = Modifier.height(56.dp).width(160.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                if (saving) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Save, null)
                    Spacer(Modifier.width(8.dp))
                    Text("ยืนยัน", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ModernReceiveCard(
    row: ReceiveRow,
    moneyFmt: DecimalFormat,
    qtyFmt: DecimalFormat,
    saving: Boolean,
    onQtyChange: (Int) -> Unit,
    onDelete: () -> Unit
) {
    val isSaved = row.stockAfter != null
    val containerColor = if (isSaved) Color(0xFFF0FDF4) else MaterialTheme.colorScheme.surface
    val borderColor = if (isSaved) Color(0xFFBBF7D0) else Color.Transparent

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (isSaved) 0.dp else 2.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize().then(if (isSaved) Modifier.border(1.dp, borderColor, RoundedCornerShape(16.dp)) else Modifier)
        ) {
            Row(Modifier.padding(12.dp)) {
                val imgUrl = getProductImageUrl(row.imageUrl)
                AsyncImage(
                    model = imgUrl,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp).clip(RoundedCornerShape(12.dp)).background(Color.Gray.copy(0.1f)),
                    contentScale = ContentScale.Crop,
                    // ✅ ใช้ rememberVectorPainter ได้แล้วเพราะ import มาแล้ว
                    error = rememberVectorPainter(Icons.Outlined.Inventory2)
                )

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(row.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(row.code, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        if (!isSaved) {
                            IconButton(onClick = onDelete, Modifier.size(24.dp)) {
                                Icon(Icons.Rounded.Close, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                            }
                        } else {
                            Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF22C55E), modifier = Modifier.size(20.dp))
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("คงเหลือเดิม", style = MaterialTheme.typography.labelSmall, color = Color.Gray, fontSize = 10.sp)
                            Text(qtyFmt.format(row.stockBefore), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }

                        if (!isSaved) {
                            ModernQtyControl(qty = row.qty, onQtyChange = onQtyChange, enabled = !saving)
                        } else {
                            Text(
                                "+${row.qty}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF16A34A)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModernQtyControl(qty: Int, onQtyChange: (Int) -> Unit, enabled: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .height(36.dp)
    ) {
        IconButton(onClick = { onQtyChange(qty - 1) }, enabled = enabled, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Remove, null, modifier = Modifier.size(18.dp))
        }

        var text by remember(qty) { mutableStateOf(qty.toString()) }
        BasicTextField(
            value = text,
            onValueChange = { input ->
                if (input.isEmpty() || input == "-") { text = input; return@BasicTextField }
                val newValue = input.toIntOrNull()
                if (newValue != null) { text = input; onQtyChange(newValue) }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(
                fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            ),
            singleLine = true,
            modifier = Modifier.width(40.dp)
        )

        IconButton(onClick = { onQtyChange(qty + 1) }, enabled = enabled, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 100.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Outlined.Inventory2, null, modifier = Modifier.size(100.dp), tint = MaterialTheme.colorScheme.surfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text("ยังไม่มีสินค้า", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("สแกนบาร์โค้ด หรือพิมพ์รหัสเพื่อเริ่ม", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
    }
}

// ------------------------
// CAMERA (Keep Original Logic)
// ------------------------
@Composable
private fun ReceiveBarcodeScannerDialog(onDismiss: () -> Unit, onScanned: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            ReceiveCameraBarcodePreview(onResult = onScanned)
            ReceiveScannerOverlay(Modifier.fillMaxSize(), onDismiss)
        }
    }
}

@Composable
private fun ReceiveScannerOverlay(modifier: Modifier, onClose: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val anim by infiniteTransition.animateFloat(0f, 1f, infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = "anim")
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val boxSize = size.width * 0.7f
            val top = (size.height - boxSize) / 2
            val left = (size.width - boxSize) / 2
            drawRect(Color(0x99000000))
            drawRect(Color.Transparent, topLeft = Offset(left, top), size = Size(boxSize, boxSize), blendMode = BlendMode.Clear)
            drawRect(Color.White, topLeft = Offset(left, top), size = Size(boxSize, boxSize), style = Stroke(4.dp.toPx()))
            drawLine(Color.Red, start = Offset(left, top + (boxSize * anim)), end = Offset(left + boxSize, top + (boxSize * anim)), strokeWidth = 4.dp.toPx())
        }
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).padding(32.dp)) {
            Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        // ✅ แก้ Text ตรงนี้ด้วย (ระบุชื่อ Argument ให้ชัดเจนเพื่อแก้ Error: None of the following candidates...)
        Text(
            text = "Scan Barcode",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp)
        )
    }
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun ReceiveCameraBarcodePreview(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val analyzeExecutor = remember { Executors.newSingleThreadExecutor() }
    var scannedOnce by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val options = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build()
            val scanner = BarcodeScanning.getClient(options)
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(analyzeExecutor) { proxy ->
                val media = proxy.image
                if (media != null && !scannedOnce) {
                    try {
                        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                        scanner.process(input).addOnSuccessListener { list ->
                            val raw = list.firstOrNull()?.rawValue?.trim()
                            if (!raw.isNullOrBlank()) { scannedOnce = true; onResult(raw) }
                        }.addOnCompleteListener { proxy.close() }
                    } catch (e: Exception) { proxy.close() }
                } else proxy.close()
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {}
        }, mainExecutor)
        onDispose { analyzeExecutor.shutdown() }
    }
    AndroidView({ previewView }, Modifier.fillMaxSize())
}