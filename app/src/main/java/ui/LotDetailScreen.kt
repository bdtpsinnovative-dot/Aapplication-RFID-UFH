package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.AppError
import data.AuthManager
import data.LotItemDetail
import data.SessionStore
import data.StockLotApi
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LotDetailScreen(
    lotId: Long,
    lotCode: String,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<LotItemDetail>>(emptyList()) }
    // lot_item_id → received qty (local editable copy)
    var receivedMap by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableStateOf(0) }
    var showConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(lotId) {
        loading = true
        error = null
        try {
            val token = AuthManager.getValidAccessToken(ctx)
                ?: throw IllegalStateException("Session หมดอายุ")
            val loaded = StockLotApi.fetchLotItems(lotId, token)
            items = loaded
            receivedMap = loaded.associate { it.id to it.receivedQty }
        } catch (e: Exception) {
            error = AppError.resolve(e)
        } finally {
            loading = false
        }
    }

    // รายการที่ยังขาด
    val shortageItems = items.filter { (receivedMap[it.id] ?: 0) < it.expectedQty }
    val allComplete = shortageItems.isEmpty() && items.isNotEmpty()

    fun confirm() {
        scope.launch {
            saving = true
            try {
                val token = SessionStore.getAccessToken(ctx)
                    ?: throw IllegalStateException("Session หมดอายุ")
                val updates = receivedMap.map { (id, qty) -> id to qty }
                StockLotApi.updateLotItemsReceived(updates, token)
                val newStatus = if (allComplete) "COMPLETED" else "PARTIAL"
                StockLotApi.updateLotStatus(lotId, newStatus, token)
                onDone()
            } catch (e: Exception) {
                error = AppError.resolve(e)
            } finally {
                saving = false
                showConfirmDialog = false
            }
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("ยืนยันรับเข้า") },
            text = {
                if (allComplete)
                    Text("รับครบทุกรายการ ต้องการยืนยันและปิดลอตนี้?")
                else
                    Text("ยังขาดสินค้า ${shortageItems.size} รายการ ต้องการบันทึกและตั้งสถานะ PARTIAL?")
            },
            confirmButton = {
                Button(onClick = { confirm() }, enabled = !saving) {
                    if (saving) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text("ยืนยัน")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showConfirmDialog = false }) { Text("ยกเลิก") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(lotCode, fontWeight = FontWeight.Bold)
                        Text("รับสินค้าเข้าลอต", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "กลับ")
                    }
                }
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Button(
                    onClick = { showConfirmDialog = true },
                    enabled = !loading && !saving && items.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (allComplete) Color(0xFF4CAF50) else Color(0xFFFF9800)
                    )
                ) {
                    Icon(
                        if (allComplete) Icons.Outlined.CheckCircle else Icons.Outlined.Save,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (allComplete) "ยืนยันรับเข้าครบ" else "บันทึก (ยังขาด ${shortageItems.size} รายการ)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            // Tab bar
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("รับเข้า (${items.size})") },
                    icon = { Icon(Icons.Outlined.MoveToInbox, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            "ขาด (${shortageItems.size})",
                            color = if (shortageItems.isNotEmpty()) Color(0xFFE53935) else LocalContentColor.current
                        )
                    },
                    icon = {
                        Icon(
                            Icons.Outlined.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (shortageItems.isNotEmpty()) Color(0xFFE53935) else LocalContentColor.current
                        )
                    }
                )
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("กำลังโหลด...", color = Color.Gray)
                    }
                }

                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }

                selectedTab == 0 -> ReceiveTab(
                    items = items,
                    receivedMap = receivedMap,
                    onQtyChange = { id, qty -> receivedMap = receivedMap + (id to qty) }
                )

                else -> ShortageTab(items = items, receivedMap = receivedMap)
            }
        }
    }
}

// ─── Tab 1: รับเข้า ───────────────────────────────────────────────────────────
@Composable
private fun ReceiveTab(
    items: List<LotItemDetail>,
    receivedMap: Map<Long, Int>,
    onQtyChange: (Long, Int) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(items) { idx, item ->
            val received = receivedMap[item.id] ?: 0
            val complete = received >= item.expectedQty
            ReceiveItemCard(
                index = idx + 1,
                item = item,
                received = received,
                complete = complete,
                onQtyChange = { onQtyChange(item.id, it) }
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ReceiveItemCard(
    index: Int,
    item: LotItemDetail,
    received: Int,
    complete: Boolean,
    onQtyChange: (Int) -> Unit
) {
    val borderColor = when {
        complete -> Color(0xFF4CAF50)
        received > 0 -> Color(0xFFFF9800)
        else -> Color(0xFFE0E0E0)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = CardDefaults.outlinedCardBorder().copy() // use border via modifier below
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(borderColor.copy(alpha = 0.06f))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // เลขที่ + สถานะ
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(borderColor.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (complete)
                    Icon(Icons.Outlined.Check, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
                else
                    Text("$index", fontWeight = FontWeight.Bold, color = borderColor, fontSize = 14.sp)
            }

            Spacer(Modifier.width(10.dp))

            // ชื่อสินค้า + SKU
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.productName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!item.sku.isNullOrBlank()) {
                    Text(item.sku, color = Color.Gray, fontSize = 12.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "คาดหวัง: ${item.expectedQty}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            Spacer(Modifier.width(8.dp))

            // +/- qty control
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = { if (received > 0) onQtyChange(received - 1) },
                        modifier = Modifier.size(32.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color(0xFFEEEEEE)
                        )
                    ) {
                        Icon(Icons.Outlined.Remove, null, modifier = Modifier.size(16.dp))
                    }

                    // Editable qty field
                    var text by remember(received) { mutableStateOf(received.toString()) }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { v ->
                            text = v.filter { it.isDigit() }
                            text.toIntOrNull()?.let { onQtyChange(it) }
                        },
                        modifier = Modifier.width(52.dp),
                        textStyle = LocalTextStyle.current.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = borderColor,
                            unfocusedBorderColor = borderColor.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )

                    FilledTonalIconButton(
                        onClick = { onQtyChange(received + 1) },
                        modifier = Modifier.size(32.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color(0xFFE3F2FD)
                        )
                    ) {
                        Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp), tint = Color(0xFF1976D2))
                    }
                }
                Text(
                    if (complete) "ครบ ✓" else "รับ/${item.expectedQty}",
                    fontSize = 11.sp,
                    color = borderColor
                )
            }
        }
    }
}

// ─── Tab 2: ขาด ───────────────────────────────────────────────────────────────
@Composable
private fun ShortageTab(
    items: List<LotItemDetail>,
    receivedMap: Map<Long, Int>
) {
    val shortageItems = items.filter { (receivedMap[it.id] ?: 0) < it.expectedQty }

    if (shortageItems.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = Color(0xFF4CAF50)
                )
                Spacer(Modifier.height(12.dp))
                Text("รับครบทุกรายการ", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFFF3E0), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Warning, null, tint = Color(0xFFE65100), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "สินค้าขาด ${shortageItems.size} รายการ",
                    color = Color(0xFFE65100),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        itemsIndexed(shortageItems) { idx, item ->
            val received = receivedMap[item.id] ?: 0
            val shortage = item.expectedQty - received
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8F8))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.productName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        if (!item.sku.isNullOrBlank())
                            Text(item.sku, color = Color.Gray, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            QtyBadge("คาดหวัง", "${item.expectedQty}", Color(0xFF1976D2))
                            QtyBadge("รับแล้ว", "$received", Color(0xFF388E3C))
                            QtyBadge("ขาด", "$shortage", Color(0xFFE53935))
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun QtyBadge(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, color = color, fontSize = 15.sp)
        Text(label, color = Color.Gray, fontSize = 10.sp)
    }
}
