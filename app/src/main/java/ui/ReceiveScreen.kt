package ui

import android.annotation.SuppressLint
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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

import data.AppError
import data.AuthManager
import data.DraftDatabase
import data.ProductDatabase
import data.ProductLite
import data.ReceiveDraftRow
import data.SessionStore
import data.StockLotApi
import data.StockReceivingApi
import data.StockUpdateDto
import data.SupabaseProductsApi
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val imageUrl: String? = null,
    val lotItemId: Long? = null,
    val expectedQty: Int? = null,
    val lotCode: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(
    onBack: () -> Unit,
    lotId: Long? = null,
    lotCode: String? = null,
    onLotDone: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val branchId = remember { SessionStore.getBranchId(context) }
    val moneyFmt = remember { DecimalFormat("#,##0.00") }
    val qtyFmt = remember { DecimalFormat("#,##0.###") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val db = remember { DraftDatabase(context) }

    var msg by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    var codeInput by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    var lastScanned by remember { mutableStateOf<String?>(null) }
    val rows = remember { mutableStateListOf<ReceiveRow>() }
    var draftLoaded by remember { mutableStateOf(false) }

    // State สำหรับเปิด Dialog รับจำนวน
    var productForDialog by remember { mutableStateOf<ProductLite?>(null) }
    var scannedCodeForDialog by remember { mutableStateOf("") }
    var showQuantityDialog by remember { mutableStateOf(false) }

    // Lot mode: map productId → LotItemDetail (โหลดครั้งเดียวตอนเปิดหน้า)
    var lotItemMap by remember { mutableStateOf<Map<Long, data.LotItemDetail>>(emptyMap()) }
    var lotDialogExpected by remember { mutableStateOf<Int?>(null) }
    var lotDialogReceived by remember { mutableStateOf<Int?>(null) }

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
            // Lot mode: ดึง lotItemId / expectedQty จาก lotItemMap
            val lotItem = if (lotId != null) lotItemMap[p.id] else null
            rows.add(ReceiveRow(
                productId   = p.id,
                code        = codeUsed,
                name        = p.name,
                price       = p.price,
                qty         = inc,
                stockBefore = stockBefore,
                imageUrl    = imgUrl,
                lotItemId   = lotItem?.id,
                expectedQty = lotItem?.expectedQty,
                lotCode     = if (lotItem != null) lotCode else null
            ))
        }
    }

    fun searchProduct(code: String) {
        val c = code.trim()
        codeInput = ""

        if (c.isBlank()) return

        scope.launch {
            msg = null
            isError = false
            try {
                // ค้นหาจาก SQLite local ก่อน ไม่ต้องง้อเน็ต
                val cached = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ProductDatabase(context).findByCode(c)
                }
                val p: ProductLite? = if (cached != null) {
                    ProductLite(
                        id = cached.id,
                        name = cached.name,
                        price = cached.price,
                        barcode = cached.barcode,
                        sku = cached.sku,
                        image_url = cached.imageUrl
                    )
                } else {
                    // fallback: ดึงจาก API เมื่อไม่มีใน local
                    val token = AuthManager.getValidAccessToken(context)
                    if (token.isNullOrBlank()) throw Exception("กรุณาล็อกอินใหม่")
                    SupabaseProductsApi.fetchByCode(c, token)
                }

                if (p == null) {
                    msg = "ไม่พบสินค้า: $c"; isError = true; return@launch
                }

                scannedCodeForDialog = c
                productForDialog = p

                // Lot mode: ดึง expected จาก lotItemMap, received จาก rows ที่มีอยู่
                if (lotId != null) {
                    val lotItem = lotItemMap[p.id]
                    lotDialogExpected = lotItem?.expectedQty
                    lotDialogReceived = rows.firstOrNull { it.productId == p.id }?.qty ?: 0
                }
                showQuantityDialog = true

            } catch (e: Exception) {
                msg = AppError.resolve(e)
                isError = true
            }
        }
    }

    // 👵🏼 ฟังก์ชันนี้เรียกตอนกด "ตกลง" ใน Dialog
    fun confirmReceiveQuantity(qty: Int) {
        val p = productForDialog ?: return
        val c = scannedCodeForDialog

        scope.launch {
            try {
                val token = AuthManager.getValidAccessToken(context) ?: throw Exception("กรุณาล็อกอินใหม่")

                val existIdx = rows.indexOfFirst { it.productId == p.id }
                if (existIdx >= 0 && rows[existIdx].stockAfter == null) {
                    upsertRow(p, c, qty, rows[existIdx].stockBefore)
                } else if (existIdx >= 0 && rows[existIdx].stockAfter != null) {
                    upsertRow(p, c, qty, rows[existIdx].stockAfter!!)
                } else {
                    // 💡 ยายแนะนำ: ตรงนี้ถ้ามี SQLite ยอดเดิมก็ดึงจาก Local ได้เลยจ้ะ ไม่ต้องรอ API
                    val stockBefore = StockReceivingApi.fetchQty(p.id, branchId, token)
                    upsertRow(p, c, qty, stockBefore)
                }
                lastScanned = c
                showQuantityDialog = false
                productForDialog = null

                if (lotId == null) {
                    // No-lot mode: save draft ทันที (ป้องกันกดออกก่อน snapshotFlow ทำงาน)
                    val pending = rows.filter { it.stockAfter == null }
                    withContext(Dispatchers.IO) {
                        db.saveReceiveDraft(
                            pending.map { ReceiveDraftRow(it.productId, it.code, it.name, it.price, it.qty, it.stockBefore, it.imageUrl) },
                            branchId
                        )
                    }
                }
                // Lot mode: snapshotFlow จัดการ auto-save ให้อัตโนมัติเมื่อ rows เปลี่ยน

                focusRequester.requestFocus()
                keyboardController?.hide()
            } catch (e: Exception) {
                msg = AppError.resolve(e); isError = true
            }
        }
    }

    fun saveReceiving() {
        val pending = rows.filter { it.stockAfter == null && it.qty > 0 }
        if (pending.isEmpty()) { msg = "ไม่มีรายการใหม่"; isError = true; return }
        scope.launch {
            saving = true; msg = null; isError = false
            try {
                val token = AuthManager.getValidAccessToken(context)
                if (token.isNullOrBlank()) throw Exception("กรุณาล็อกอินใหม่")

                val ids = pending.map { it.productId }.distinct()
                val currentMap = StockReceivingApi.fetchQtyMap(ids, branchId, token, lotId)
                val updates = pending.map {
                    StockUpdateDto(branchId, it.productId, (currentMap[it.productId] ?: 0.0) + it.qty)
                }

                try {
                    StockReceivingApi.upsertStockList(updates, token)
                } catch (e: Exception) {
                    val isJwtExpired = e.message?.contains("JWT expired") == true
                                    || e.message?.contains("PGRST303") == true
                    if (isJwtExpired) {
                        val freshToken = AuthManager.getValidAccessToken(context, forceRefresh = true)
                            ?: throw Exception("Session หมดอายุ กรุณาล็อกอินใหม่")
                        StockReceivingApi.upsertStockList(updates, freshToken)
                    } else {
                        throw e
                    }
                }
                for (i in rows.indices) {
                    val r = rows[i]
                    if (r.stockAfter == null && r.qty > 0)
                        rows[i] = r.copy(stockAfter = (currentMap[r.productId] ?: 0.0) + r.qty)
                }

                if (lotId != null) {
                    // อัปเดต stock_lot_items received_qty
                    val lotUpdates = rows.mapNotNull { r ->
                        val id = r.lotItemId ?: return@mapNotNull null
                        id to (r.stockBefore + r.qty).toInt()
                    }
                    if (lotUpdates.isNotEmpty()) StockLotApi.updateLotItemsReceived(lotUpdates, token)
                    // คำนวณ status ลอต
                    val allComplete = rows.filter { it.lotItemId != null }.all { r ->
                        val received = (r.stockBefore + r.qty).toInt()
                        r.expectedQty == null || received >= r.expectedQty
                    }
                    StockLotApi.updateLotStatus(lotId, if (allComplete) "COMPLETED" else "PARTIAL", token)
                    msg = if (allComplete) "รับครบทุกรายการ!" else "บันทึกสำเร็จ (รับบางส่วน)"
                    isError = false
                    withContext(Dispatchers.IO) { kotlinx.coroutines.delay(1200) }
                    onLotDone?.invoke()
                } else {
                    withContext(Dispatchers.IO) { db.clearReceiveDraft(branchId) }
                    msg = "บันทึกสำเร็จ!"; isError = false
                }
            } catch (e: Exception) {
                msg = AppError.resolve(e); isError = true
            } finally { saving = false }
        }
    }

    LaunchedEffect(Unit) {
        if (lotId != null) {
            // Lot mode: โหลด lotItemMap จาก API + rows จาก SQLite draft
            try {
                val token = AuthManager.getValidAccessToken(context)
                if (!token.isNullOrBlank()) {
                    val items = StockLotApi.fetchLotItems(lotId, token)
                    lotItemMap = items.associateBy { it.productId }
                }
            } catch (e: Exception) {
                msg = AppError.resolve(e); isError = true
            }
            // โหลดเฉพาะที่ยิงไว้แล้วจาก SQLite draft (กรอง qty=0 ออก)
            val draft = withContext(Dispatchers.IO) {
                db.loadLotReceiveDraft(branchId ?: 0L, lotId)
            }
            rows.addAll(draft.filter { it.qty > 0 }.map { r ->
                ReceiveRow(
                    productId   = r.productId,
                    code        = r.code,
                    name        = r.name,
                    price       = 0.0,
                    qty         = r.qty,
                    stockBefore = 0.0,
                    imageUrl    = r.imageUrl,
                    // สำคัญ: ถ้าใน DB เป็น 0 ให้ถือว่าคือ null (นอกลอต)
                    lotItemId   = if (r.lotItemId == 0L) null else r.lotItemId,
                    expectedQty = if (r.lotItemId == 0L) null else r.expectedQty,
                    lotCode     = lotCode
                )
            })
        } else {
            // No-lot mode: โหลด draft
            val draft = withContext(Dispatchers.IO) { db.loadReceiveDraft(branchId) }
            if (draft.isNotEmpty()) {
                rows.addAll(draft.map {
                    ReceiveRow(it.productId, it.code, it.name, it.price, it.qty, it.stockBefore, null, it.imageUrl)
                })
            }
        }
        draftLoaded = true
        kotlinx.coroutines.delay(300)
        focusRequester.requestFocus()
        keyboardController?.hide()
    }

    // Auto-save draft
    LaunchedEffect(draftLoaded) {
        if (!draftLoaded) return@LaunchedEffect
        snapshotFlow { rows.toList() }.collect { current ->
            withContext(Dispatchers.IO) {
                if (lotId != null) {
                    // Lot mode: save ทุก item ที่ยิงแล้ว (ทั้งในลอตและนอกลอต)
                    // สินค้านอกลอต ใช้ lotItemId = 0 เป็น sentinel
                    val scanned = current.filter { it.qty > 0 }
                    db.setLotReceiveDraft(
                        scanned.map { r ->
                            data.LotReceiveDraftRow(
                                productId   = r.productId,
                                lotId       = lotId,
                                lotItemId   = r.lotItemId ?: 0L,
                                code        = r.code,
                                name        = r.name,
                                imageUrl    = r.imageUrl,
                                qty         = r.qty,
                                expectedQty = r.expectedQty ?: 0
                            )
                        },
                        branchId ?: 0L,
                        lotId
                    )
                } else {
                    // No-lot mode: save ลง receive_draft เดิม
                    val pending = current.filter { it.stockAfter == null }
                    db.saveReceiveDraft(
                        pending.map { ReceiveDraftRow(it.productId, it.code, it.name, it.price, it.qty, it.stockBefore, it.imageUrl) },
                        branchId
                    )
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (lotCode != null) "ลอต: $lotCode" else "รับสินค้าเข้า",
                                fontWeight = FontWeight.Bold
                            )
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
                    focusRequester = focusRequester,
                    onCodeChange = { codeInput = it; msg = null },
                    onEnter = { searchProduct(codeInput) }
                )
            }
        },
        bottomBar = {
            BottomActionBar(
                rows = rows,
                saving = saving,
                isLotMode = lotId != null,
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
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        Text(
                            "รายการทั้งหมด (${rows.size})",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 4.dp, start = 2.dp)
                        )
                    }
                    itemsIndexed(rows) { idx, r ->
                        ModernReceiveCard(
                            row = r,
                            moneyFmt = moneyFmt,
                            qtyFmt = qtyFmt,
                            saving = saving,
                            isLotItem = r.lotItemId != null,
                            onQtyChange = { newVal ->
                                if (newVal >= 1) rows[idx] = r.copy(qty = newVal, stockAfter = null)
                            },
                            onDelete = { rows.removeAt(idx) }
                        )
                    }
                }
            }
        }

        // Dialog ถามจำนวน
        if (showQuantityDialog && productForDialog != null) {
            QuantityInputDialog(
                productName = productForDialog!!.name,
                imageUrl = productForDialog!!.image_url,
                lotExpectedQty = lotDialogExpected,
                lotReceivedQty = lotDialogReceived,
                onDismiss = {
                    showQuantityDialog = false
                    productForDialog = null
                    focusRequester.requestFocus()
                    keyboardController?.hide()
                },
                onConfirm = { qty -> confirmReceiveQuantity(qty) }
            )
        }
    }
}

// 👵🏼 เพิ่ม Dialog Component สำหรับถามจำนวน
@Composable
fun QuantityInputDialog(
    productName: String,
    imageUrl: String?,
    lotExpectedQty: Int? = null,
    lotReceivedQty: Int? = null,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var quantityInput by remember { mutableStateOf("1") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ระบุจำนวนรับเข้า", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 📸 ส่วนแสดงรูปภาพสินค้า — Fit เพื่อแสดงรูปเต็มไม่ถูกตัด
                val imgUrl = getProductImageUrl(imageUrl)
                AsyncImage(
                    model = imgUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Gray.copy(0.1f)),
                    contentScale = ContentScale.Fit,
                    error = rememberVectorPainter(Icons.Outlined.Inventory2)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 🏷️ ชื่อสินค้า
                Text(
                    text = productName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // แสดง ส่งมา / รับแล้ว ถ้าเป็น lot mode
                if (lotExpectedQty != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFE3F2FD), RoundedCornerShape(10.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$lotExpectedQty", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF1565C0))
                            Text("ส่งมา", fontSize = 11.sp, color = Color.Gray)
                        }
                        Text("/", fontSize = 22.sp, color = Color.LightGray, modifier = Modifier.align(Alignment.CenterVertically))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${lotReceivedQty ?: 0}", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF388E3C))
                            Text("รับแล้ว", fontSize = 11.sp, color = Color.Gray)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 🎛️ ส่วนปุ่ม + / - และช่องพิมพ์จำนวน
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // ปุ่มลบ (-)
                    IconButton(
                        onClick = {
                            val current = quantityInput.toIntOrNull() ?: 1
                            if (current > 1) quantityInput = (current - 1).toString()
                        },
                        modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                    ) {
                        Icon(Icons.Rounded.Remove, contentDescription = "ลดจำนวน", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // ช่องพิมพ์ตัวเลข
                    OutlinedTextField(
                        value = quantityInput,
                        onValueChange = {
                            // ยอมให้ว่างเปล่าได้ตอนกำลังลบตัวเลขพิมพ์ใหม่ แต่ต้องเป็นตัวเลขเท่านั้น
                            if (it.isEmpty() || it.all { char -> char.isDigit() }) quantityInput = it
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                val qty = quantityInput.toIntOrNull() ?: 1
                                if (qty > 0) onConfirm(qty)
                            }
                        ),
                        singleLine = true,
                        textStyle = TextStyle(textAlign = TextAlign.Center, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.width(80.dp)
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    // ปุ่มบวก (+)
                    IconButton(
                        onClick = {
                            val current = quantityInput.toIntOrNull() ?: 0
                            quantityInput = (current + 1).toString()
                        },
                        modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "เพิ่มจำนวน", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val qty = quantityInput.toIntOrNull() ?: 1
                    if (qty > 0) onConfirm(qty)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("เพิ่มเข้าลิสต์", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("ยกเลิก")
            }
        }
    )
}
@Composable
fun InputHeader(
    codeInput: String,
    focusRequester: FocusRequester,
    onCodeChange: (String) -> Unit,
    onEnter: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shadowElevation = 4.dp,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        TextField(
            value = codeInput,
            onValueChange = onCodeChange,
            placeholder = { Text("สแกนหรือพิมพ์รหัสสินค้า...", style = TextStyle(color = Color.Gray)) },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { /* keeps focus state in sync */ },
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
    }
}

// ... (StatusBanner, BottomActionBar, ModernReceiveCard, ModernQtyControl, EmptyState คงเดิมเลยลูก) ...

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
fun BottomActionBar(rows: List<ReceiveRow>, saving: Boolean, onSave: () -> Unit, isLotMode: Boolean = false) {
    val pendingCount = rows.count { it.stockAfter == null }
    val totalQty = rows.filter { it.stockAfter == null }.sumOf { it.qty }

    Surface(
        shadowElevation = 12.dp,
        tonalElevation = 4.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (isLotMode) "บันทึกลงเครื่องแล้ว ✓" else "รายการรอรับ",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isLotMode) Color(0xFF388E3C) else Color.Gray
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("$totalQty", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("ชิ้น · $pendingCount รายการ", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            if (!isLotMode) {
                Button(
                    onClick = onSave,
                    enabled = pendingCount > 0 && !saving,
                    modifier = Modifier.height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (saving) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Save, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("ยืนยัน", fontWeight = FontWeight.Bold)
                    }
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
    onDelete: () -> Unit,
    isLotItem: Boolean = false
) {
    val isSaved = row.stockAfter != null
    val containerColor = when {
        isSaved               -> Color(0xFFF0FDF4)                      // บันทึกแล้ว → เขียวอ่อน
        isLotItem && row.qty == 0 -> Color(0xFFFAFAFA)                  // lot item ยังไม่รับ → เทาอ่อน
        else                  -> MaterialTheme.colorScheme.surface
    }
    val borderColor = if (isSaved) Color(0xFFBBF7D0) else Color.Transparent

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isSaved) Modifier.border(1.dp, borderColor, RoundedCornerShape(12.dp)) else Modifier),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (isSaved) 0.dp else 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // รูปสินค้า เล็กลง
            val imgUrl = getProductImageUrl(row.imageUrl)
            AsyncImage(
                model = imgUrl,
                contentDescription = null,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Color.Gray.copy(0.1f)),
                contentScale = ContentScale.Crop,
                error = rememberVectorPainter(Icons.Outlined.Inventory2)
            )
            Spacer(Modifier.width(10.dp))
            // ชื่อ + code
            Column(Modifier.weight(1f)) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(row.code, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                if (row.lotCode != null) {
                    // 1. เช็คว่าเป็นสินค้านอกลอตหรือไม่ (ถ้าโหลดจาก Draft อาจจะเป็น 0L เลยต้องเช็คด้วย)
                    val isOutOfLot = row.lotItemId == null || row.lotItemId == 0L

                    // 2. เช็คว่ารับเกินไหม (รับเข้ามา > ยอดที่คาดหวัง)
                    val expected = row.expectedQty ?: 0
                    val isOverQty = !isOutOfLot && row.qty > expected

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        if (isOutOfLot) {
                            // 🔴 แจ้งเตือน: สินค้านอกลอต (ป้ายสีเหลือง/ส้ม เหมือนหน้าตรวจสอบ)
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFFFFF8E1) // สีเหลืองอ่อน
                            ) {
                                Text(
                                    "นอกลอต",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFF57F17), // สีส้มเข้ม
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else if (isOverQty) {
                            // 🔴 แจ้งเตือน: รับเกิน (ป้ายสีแดง บอกจำนวนที่เกิน)
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFFFFEBEE) // สีแดงอ่อน
                            ) {
                                Text(
                                    "รับเกิน (${row.qty}/$expected)",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFC62828), // สีแดงเข้ม
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            // 🟢 ปกติ: สินค้าในลอต (ป้ายสีฟ้าเดิม พร้อมบอกยอด)
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFFE3F2FD) // สีฟ้าอ่อน
                            ) {
                                Text(
                                    "ลอต ${row.lotCode} (${row.qty}/$expected)",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF1565C0) // สีฟ้าเข้ม
                                )
                            }
                        }
                    }
                } else {
                    // โหมดรับเข้าปกติ (ไม่มีลอต)
                    Text(
                        "คงเหลือ: ${qtyFmt.format(row.stockBefore)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // ฝั่งขวา: qty control หรือ saved badge
            if (!isSaved) {
                ModernQtyControl(qty = row.qty, onQtyChange = onQtyChange, enabled = !saving)
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.Close, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFDCFCE7)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF22C55E), modifier = Modifier.size(14.dp))
                        Text("+${row.qty}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF16A34A))
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
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .height(30.dp)
    ) {
        IconButton(onClick = { onQtyChange(qty - 1) }, enabled = enabled, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Rounded.Remove, null, modifier = Modifier.size(15.dp))
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
                fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            ),
            singleLine = true,
            modifier = Modifier.width(34.dp)
        )

        IconButton(onClick = { onQtyChange(qty + 1) }, enabled = enabled, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Rounded.Add, null, modifier = Modifier.size(15.dp))
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
// CAMERA
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