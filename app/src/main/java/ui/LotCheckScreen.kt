    package ui

    import androidx.compose.foundation.background
    import androidx.compose.foundation.layout.*
    import androidx.compose.foundation.lazy.LazyColumn
    import androidx.compose.foundation.lazy.itemsIndexed
    import androidx.compose.foundation.shape.RoundedCornerShape
    import androidx.compose.foundation.text.BasicTextField
    import androidx.compose.foundation.text.KeyboardOptions
    import androidx.compose.material.icons.Icons
    import androidx.compose.material.icons.outlined.*
    import androidx.compose.material.icons.rounded.ArrowBack
    import androidx.compose.material3.*
    import androidx.compose.runtime.*
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.draw.clip
    import androidx.compose.ui.graphics.Color
    import androidx.compose.ui.layout.ContentScale
    import androidx.compose.ui.platform.LocalContext
    import androidx.compose.ui.text.TextStyle
    import androidx.compose.ui.text.font.FontWeight
    import androidx.compose.ui.text.input.KeyboardType
    import androidx.compose.ui.text.style.TextAlign
    import androidx.compose.ui.text.style.TextOverflow
    import androidx.compose.ui.unit.dp
    import androidx.compose.ui.unit.sp
    import coil.compose.AsyncImage
    import data.AppError
    import data.AuthManager
    import data.DraftDatabase
    import data.LotReceiveDraftRow
    import data.SessionStore
    import data.StockLotApi
    import data.StockReceivingApi
    import data.StockUpdateDto
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.launch
    import kotlinx.coroutines.withContext

    private data class CheckRow(
        val productId: Long,
        val lotItemId: Long,
        val name: String,
        val code: String,
        val imageUrl: String?,
        val expectedQty: Int,
        val receivedQty: Int   // จาก SQLite draft
    ) {
        val isExtraItem get() = lotItemId == 0L          // ยิงเพิ่มนอกลอต
        val shortage get() = if (isExtraItem) 0 else (expectedQty - receivedQty).coerceAtLeast(0)
        val excess   get() = (receivedQty - expectedQty).coerceAtLeast(0)
        val isComplete get() = isExtraItem || receivedQty >= expectedQty
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun LotCheckScreen(
        lotId: Long,
        lotCode: String,
        onBack: () -> Unit,
        onDone: () -> Unit
    ) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val db = remember { DraftDatabase(ctx) }
        val branchId = remember { SessionStore.getBranchId(ctx) ?: 0L }

        var rows by remember { mutableStateOf<List<CheckRow>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var showConfirm by remember { mutableStateOf(false) }

        fun load() {
            scope.launch {
                loading = true; error = null
                try {
                    val token = AuthManager.getValidAccessToken(ctx)
                        ?: throw IllegalStateException("Session หมดอายุ")

                    // โหลด expected จาก API + received จาก SQLite
                    val lotItems = StockLotApi.fetchLotItems(lotId, token)
                    val allDraft: List<LotReceiveDraftRow> = withContext(Dispatchers.IO) {
                        db.loadLotReceiveDraft(branchId, lotId)
                    }
                    val lotDraftMap  = allDraft.filter { it.lotItemId > 0 }.associateBy { it.productId }
                    val extraDraft   = allDraft.filter { it.lotItemId == 0L }  // สินค้านอกลอต

                    // สินค้าในลอต
                    val lotRows = lotItems.map { item ->
                        val draft = lotDraftMap[item.productId]
                        CheckRow(
                            productId   = item.productId,
                            lotItemId   = item.id,
                            name        = item.productName,
                            code        = item.sku ?: item.barcode ?: "${item.productId}",
                            imageUrl    = item.imageUrl,
                            expectedQty = item.expectedQty,
                            receivedQty = (draft?.qty ?: 0) + item.receivedQty
                        )
                    }
                    // สินค้านอกลอต (ยิงเพิ่มมาเอง)
                    val extraRows = extraDraft.map { d ->
                        CheckRow(
                            productId   = d.productId,
                            lotItemId   = 0L,
                            name        = d.name,
                            code        = d.code,
                            imageUrl    = d.imageUrl,
                            expectedQty = 0,
                            receivedQty = d.qty
                        )
                    }
                    rows = lotRows + extraRows
                } catch (e: Exception) {
                    error = AppError.resolve(e)
                } finally {
                    loading = false
                }
            }
        }

        LaunchedEffect(Unit) { load() }

        val totalExpected = rows.sumOf { it.expectedQty }
        val totalReceived = rows.sumOf { it.receivedQty }
        val shortageCount = rows.count { it.shortage > 0 }
        val allComplete = rows.isNotEmpty() && rows.all { it.isComplete }

        // เรียง: ขาด → เกิน → นอกลอต → ครบ
        val sortedRows = remember(rows) {
            rows.sortedWith(
                compareBy<CheckRow> { row ->
                    when {
                        row.shortage > 0  -> 0
                        row.excess > 0    -> 1
                        row.isExtraItem   -> 2
                        else              -> 3
                    }
                }.thenByDescending { it.shortage }
                 .thenByDescending { it.excess }
            )
        }

        fun updateQty(productId: Long, newQty: Int) {
            rows = rows.map {
                if (it.productId == productId) it.copy(receivedQty = newQty.coerceAtLeast(0))
                else it
            }
        }

        fun confirm() {
            scope.launch {
                saving = true
                try {
                    val token = AuthManager.getValidAccessToken(ctx)
                        ?: throw IllegalStateException("Session หมดอายุ")

                    // อัปเดต stock_receiving
                    val receivedRows = rows.filter { it.receivedQty > 0 }
                    if (receivedRows.isNotEmpty()) {
                        val ids = receivedRows.map { it.productId }.distinct()
                        val currentMap = StockReceivingApi.fetchQtyMap(ids, branchId, token, lotId)

                        // 🔴 แก้ไขตรงนี้ครับ: เพิ่ม lot_id เข้าไปตอนสร้าง StockUpdateDto
                        val updates = receivedRows.map { row ->
                            StockUpdateDto(
                                branch_id = branchId,
                                product_id = row.productId,
                                qty = (currentMap[row.productId] ?: 0.0) + row.receivedQty,
                                lot_id = lotId
                            )
                        }

                        StockReceivingApi.upsertStockList(updates, token)
                    }

                    // อัปเดต stock_lot_items received_qty (เฉพาะ item ที่อยู่ในลอต)
                    val lotUpdates = rows.filter { it.lotItemId > 0 }.map { it.lotItemId to it.receivedQty }
                    if (lotUpdates.isNotEmpty()) StockLotApi.updateLotItemsReceived(lotUpdates, token)

                    // อัปเดต lot status
                    val newStatus = if (allComplete) "COMPLETED" else "PARTIAL"
                    StockLotApi.updateLotStatus(lotId, newStatus, token)

                    // ล้าง SQLite draft ของ lot นี้
                    withContext(Dispatchers.IO) { db.clearLotReceiveDraft(branchId, lotId) }

                    onDone()
                } catch (e: Exception) {
                    error = AppError.resolve(e)
                } finally {
                    saving = false
                    showConfirm = false
                }
            }
        }

        if (showConfirm) {
            AlertDialog(
                onDismissRequest = { showConfirm = false },
                title = { Text("ยืนยันรับเข้า") },
                text = {
                    Text(
                        if (allComplete)
                            "รับครบทุกรายการ ($totalReceived/$totalExpected) ต้องการยืนยันและปิดลอต?"
                        else
                            "ยังขาด $shortageCount รายการ ต้องการบันทึกสถานะ PARTIAL?"
                    )
                },
                confirmButton = {
                    Button(onClick = { confirm() }, enabled = !saving) {
                        if (saving)
                            CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        else
                            Text("ยืนยัน")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showConfirm = false }) { Text("ยกเลิก") }
                }
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("ตรวจสอบ: $lotCode", fontWeight = FontWeight.Bold)
                            Text("รับแล้ว $totalReceived / $totalExpected", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, null) }
                    }
                )
            },
            bottomBar = {
                if (!loading && rows.isNotEmpty()) {
                    Surface(shadowElevation = 8.dp) {
                        Button(
                            onClick = { showConfirm = true },
                            enabled = !saving && totalReceived > 0,
                            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (allComplete) Color(0xFF4CAF50) else Color(0xFFFF9800)
                            )
                        ) {
                            Icon(
                                if (allComplete) Icons.Outlined.CheckCircle else Icons.Outlined.Save,
                                null, modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (allComplete) "ยืนยันรับเข้าครบ"
                                else "บันทึก (ขาด $shortageCount รายการ)",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        ) { pad ->
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { load() }) { Text("ลองใหม่") }
                    }
                }
                rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("ไม่มีรายการ", color = Color.Gray)
                }
                else -> LazyColumn(
                    modifier = Modifier.padding(pad),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Summary banner
                    item {
                        SummaryBanner(
                            totalExpected = totalExpected,
                            totalReceived = totalReceived,
                            shortageCount = shortageCount,
                            allComplete = allComplete
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    itemsIndexed(sortedRows) { _, row ->
                        CheckItemCard(row, onQtyChange = { newQty -> updateQty(row.productId, newQty) })
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }

    @Composable
    private fun SummaryBanner(totalExpected: Int, totalReceived: Int, shortageCount: Int, allComplete: Boolean) {
        val bgColor = if (allComplete) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
        val iconColor = if (allComplete) Color(0xFF4CAF50) else Color(0xFFFF9800)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor, RoundedCornerShape(12.dp))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (allComplete) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                null, tint = iconColor, modifier = Modifier.size(28.dp)
            )
            SummaryItem("คาดหวัง", "$totalExpected", Color.Gray)
            SummaryItem("รับแล้ว", "$totalReceived", Color(0xFF388E3C))
            SummaryItem("ขาด", "$shortageCount รายการ", if (shortageCount > 0) Color(0xFFE53935) else Color(0xFF388E3C))
        }
    }

    @Composable
    private fun SummaryItem(label: String, value: String, color: Color) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = color)
            Text(label, fontSize = 11.sp, color = Color.Gray)
        }
    }

    @Composable
    private fun CheckItemCard(row: CheckRow, onQtyChange: (Int) -> Unit = {}) {
        // 💡 สถานะ นอกลอต หรือ รับเกิน ให้เป็นจุดสีส้ม
        val statusColor = when {
            row.isExtraItem || row.excess > 0 -> Color(0xFFFF9800) // 🟠 นอกลอต หรือ รับเกิน -> สีส้ม
            row.isComplete -> Color(0xFF4CAF50)                    // 🟢 ครบพอดี -> สีเขียว
            row.receivedQty > 0 -> Color(0xFFFF9800)               // 🟠 ขาด (แต่รับมาบ้างแล้ว) -> สีส้ม
            else -> Color(0xFFE53935)                              // 🔴 ยังไม่ได้สแกนเลย -> สีแดง
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.05f))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // สถานะ dot
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = statusColor,
                    modifier = Modifier.size(10.dp)
                ) {}

                Spacer(Modifier.width(10.dp))

                // 📸 เพิ่มรูปภาพสินค้าตรงนี้!
                val imgUrl = getProductImageUrl(row.imageUrl)
                AsyncImage(
                    model = imgUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Gray.copy(0.1f)),
                    contentScale = ContentScale.Crop,
                    error = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Outlined.Inventory2)
                )

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            row.name,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (row.isExtraItem) {
                            Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFFFFF3E0)) {
                                Text(
                                    "นอกลอต",
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    fontSize = 10.sp,
                                    color = Color(0xFFE65100)
                                )
                            }
                        }
                    }
                    Text(row.code, color = Color.Gray, fontSize = 12.sp)
                }

                Spacer(Modifier.width(8.dp))

                // qty summary + edit
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!row.isExtraItem) {
                        QtyCol("ส่งมา", "${row.expectedQty}", Color(0xFF1976D2))
                    }
                    EditableQtyCol(qty = row.receivedQty, onQtyChange = onQtyChange)
                    when {
                        row.shortage > 0 -> QtyCol("ขาด", "${row.shortage}", Color(0xFFE53935))
                        row.excess > 0   -> QtyCol("เกิน", "${row.excess}", Color(0xFFFF9800))
                        row.isExtraItem  -> QtyCol("นอกลอต", "", Color(0xFFFF9800))
                        else             -> QtyCol("✓", "ครบ", Color(0xFF4CAF50))
                    }
                }
            }
        }
    }

    @Composable
    private fun EditableQtyCol(qty: Int, onQtyChange: (Int) -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { if (qty > 0) onQtyChange(qty - 1) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Outlined.Remove, null,
                        modifier = Modifier.size(14.dp),
                        tint = if (qty > 0) Color(0xFF388E3C) else Color.LightGray
                    )
                }
                var text by remember(qty) { mutableStateOf(qty.toString()) }
                BasicTextField(
                    value = text,
                    onValueChange = { v ->
                        text = v.filter { it.isDigit() }.take(4)
                        text.toIntOrNull()?.let { onQtyChange(it) }
                    },
                    modifier = Modifier.width(36.dp),
                    textStyle = TextStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        color = Color(0xFF388E3C)
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    decorationBox = { inner ->
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFE8F5E9), RoundedCornerShape(4.dp))
                                .padding(horizontal = 2.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center
                        ) { inner() }
                    }
                )
                IconButton(
                    onClick = { onQtyChange(qty + 1) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Outlined.Add, null, modifier = Modifier.size(14.dp), tint = Color(0xFF388E3C))
                }
            }
            Text("รับ", fontSize = 10.sp, color = Color.Gray)
        }
    }

    @Composable
    private fun QtyCol(label: String, value: String, color: Color) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = color)
            Text(label, fontSize = 10.sp, color = Color.Gray)
        }
    }