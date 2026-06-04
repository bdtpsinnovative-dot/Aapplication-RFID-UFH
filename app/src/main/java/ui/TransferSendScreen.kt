package ui
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Factory
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

import data.ProductDatabase
import data.SessionStore
import data.AuthManager
import data.SupabaseTransferApi
import data.BranchItem
import data.TransferCartDatabase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScannedItem(
    val id: Long,
    val name: String,
    val sku: String,
    val imageUrl: String?,
    var qty: Int,
    val currentStock: Int = 0
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferSendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val productDb = remember { ProductDatabase(context) }
    val supabaseTransferApi = remember { SupabaseTransferApi() }
    val cartDb = remember { TransferCartDatabase(context) }

    var scannedItems by remember { mutableStateOf(cartDb.loadCart()) }
    var searchQuery by remember { mutableStateOf("") }
    var showQuantityDialog by remember { mutableStateOf<ScannedItem?>(null) }

    // 🔥 1. FocusRequester และ State เช็คว่าโฟกัสติดหรือยัง
    val screenFocusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    var autoFocusMode by remember { mutableStateOf(true) }
    var showBranchDialog by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var selectedTargetBranch by remember { mutableStateOf<BranchItem?>(null) }

    var branchList by remember { mutableStateOf(listOf<BranchItem>()) }
    var isSubmitting by remember { mutableStateOf(false) }

    val currentBranchId = remember { SessionStore.getBranchId(context) }
    val currentBranchName = remember { SessionStore.getBranchName(context) }

    LaunchedEffect(Unit) {
        coroutineScope.launch {
            delay(100)
            autoFocusMode = true // บอกระบบว่านี่คือการ Auto Focus
            try {
                screenFocusRequester.requestFocus()
            } catch (e: Exception) {}
        }

        coroutineScope.launch(Dispatchers.IO) {
            try {
                AuthManager.withValidToken(context) { validToken ->
                    val allBranches = supabaseTransferApi.fetchBranches(validToken)
                    branchList = allBranches.filter { it.id != currentBranchId }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    // ฟังก์ชันช่วยดึง Focus กลับ
    fun regainFocus() {
        coroutineScope.launch {
            delay(50)
            autoFocusMode = true // บอกระบบว่านี่คือการ Auto Focus
            try {
                screenFocusRequester.requestFocus()
            } catch (e: Exception) {}
        }
    }

    fun performSearch(query: String) {
        val cleanQuery = query.trim().replace("\n", "").replace("\r", "")
        if (cleanQuery.isEmpty()) return

        searchQuery = "" // เคลียร์ UI ทันที

        coroutineScope.launch(Dispatchers.IO) {
            val foundProduct = productDb.findByCode(cleanQuery)
            withContext(Dispatchers.Main) {
                if (foundProduct != null) {
                    showQuantityDialog = ScannedItem(
                        id = foundProduct.id, name = foundProduct.name,
                        sku = foundProduct.sku ?: "-", imageUrl = foundProduct.imageUrl,
                        qty = 1, currentStock = 0
                    )
                } else {
                    Toast.makeText(context, "ไม่พบสินค้า: $cleanQuery", Toast.LENGTH_SHORT).show()
                    regainFocus() // ไม่พบสินค้า คืนโฟกัสทันที
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) {
                // 🔥 3. ถ้าเผลอไปกดที่ว่างๆ บนจอ ก็ดึง Focus กลับมาให้ปืนยิงต่อได้
                regainFocus()
            },
        topBar = {
            Column(modifier = Modifier.background(Color.White)) {
                TopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(end = 48.dp)) {
                            Text("โอนสินค้าออก", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Text("ต้นทาง: $currentBranchName", fontSize = 14.sp, color = Color(0xFF5E5CE6))
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { newValue ->
                        searchQuery = newValue
                        if (newValue.endsWith("\n") || newValue.endsWith("\r")) {
                            performSearch(newValue)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .focusRequester(screenFocusRequester)
                        .onFocusChanged { focusState ->
                            hasFocus = focusState.isFocused

                            // 👇 ดักจับตรงนี้! ถ้า Focus ติด และเป็น Auto Mode ให้ซ่อนแป้น
                            if (focusState.isFocused && autoFocusMode) {
                                coroutineScope.launch {
                                    delay(150) // หน่วงให้ชัวร์ว่า TextField กำลังจะเรียกแป้น แล้วเราค่อยตบลง
                                    keyboardController?.hide()
                                    autoFocusMode = false // ปลดล็อก เผื่อคนอยากเอานิ้วจิ้มช่องเพื่อพิมพ์เอง แป้นจะได้ขึ้นปกติ
                                }
                            }
                        },
                    placeholder = {
                        if (hasFocus) Text("พร้อมสแกน...", color = Color.Gray)
                        else Text("แตะที่ว่างเพื่อสแกน", color = Color.Red.copy(alpha = 0.5f))
                    },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = if(hasFocus) Color.Blue else Color.Gray) },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFFF3F4F6),
                        unfocusedContainerColor = Color(0xFFF3F4F6),
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { performSearch(searchQuery) })
                )
            }
        },
        bottomBar = {
            val totalPieces = scannedItems.sumOf { it.qty }
            val totalItems = scannedItems.size

            Surface(shadowElevation = 8.dp, color = Color.White) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("รายการรอโอน", fontSize = 12.sp, color = Color.Gray)
                        Text("$totalPieces ชิ้น · $totalItems รายการ", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Button(
                        onClick = { showBranchDialog = true },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E5CE6)),
                        enabled = scannedItems.isNotEmpty() && !isSubmitting
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("กำลังโอน...", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Text("ดำเนินการต่อ", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }
            }
        },
        containerColor = Color(0xFFF9FAFB)
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            Text(
                "สินค้าในรายการ (${scannedItems.size})",
                fontWeight = FontWeight.Bold,
                color = Color(0xFF5E5CE6),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(scannedItems) { item ->
                    ItemCard(
                        item = item,
                        onUpdateQty = { newQty ->
                            val updatedList = scannedItems.map { if (it.id == item.id) it.copy(qty = newQty) else it }
                            scannedItems = updatedList
                            coroutineScope.launch(Dispatchers.IO) { cartDb.saveCart(updatedList) }
                            regainFocus() // อัปเดตเสร็จ ดึงโฟกัสคืน
                        },
                        onRemove = {
                            val updatedList = scannedItems.filter { it.id != item.id }
                            scannedItems = updatedList
                            coroutineScope.launch(Dispatchers.IO) { cartDb.saveCart(updatedList) }
                            regainFocus() // ลบเสร็จ ดึงโฟกัสคืน
                        }
                    )
                }
            }
        }
    }

    // --- Dialogs Section ---

    if (showQuantityDialog != null) {
        QuantityDialog(
            item = showQuantityDialog!!,
            onDismiss = {
                showQuantityDialog = null
                regainFocus() // ปิด Dialog แล้วคืนโฟกัสให้หน้าจอหลักยิงต่อได้เลย
            },
            onConfirm = { newItem ->
                val existingItem = scannedItems.find { it.id == newItem.id }
                val updatedList = if (existingItem != null) {
                    scannedItems.map { if (it.id == newItem.id) it.copy(qty = it.qty + newItem.qty) else it }
                } else {
                    scannedItems + newItem
                }
                scannedItems = updatedList
                showQuantityDialog = null
                regainFocus()
                coroutineScope.launch(Dispatchers.IO) { cartDb.saveCart(updatedList) }
            }
        )
    }

    if (showBranchDialog) {
        BranchSelectionDialog(
            branches = branchList,
            onDismiss = {
                showBranchDialog = false
                regainFocus()
            },
            onNext = { selectedBranch ->
                selectedTargetBranch = selectedBranch
                showBranchDialog = false
                showConfirmDialog = true
            }
        )
    }

    if (showConfirmDialog && selectedTargetBranch != null) {
        ConfirmTransferDialog(
            targetBranch = selectedTargetBranch!!,
            itemCount = scannedItems.size,
            totalQty = scannedItems.sumOf { it.qty },
            onDismiss = {
                showConfirmDialog = false
                regainFocus()
            },
            onConfirm = {
                showConfirmDialog = false
                coroutineScope.launch {
                    isSubmitting = true
                    try {
                        val userId = SessionStore.getUserId(context)
                        val userName = SessionStore.getDisplayName(context)
                        AuthManager.withValidToken(context) { validToken ->
                            val transferCode = "TR-${System.currentTimeMillis()}"
                            supabaseTransferApi.createTransfer(
                                accessToken = validToken, transferCode = transferCode,
                                fromBranchId = currentBranchId, toBranchId = selectedTargetBranch!!.id,
                                createdBy = userId, createdByName = userName, items = scannedItems
                            )
                        }
                        Toast.makeText(context, "โอนสำเร็จไปที่ ${selectedTargetBranch!!.branchName}", Toast.LENGTH_LONG).show()
                        scannedItems = emptyList()
                        withContext(Dispatchers.IO) { cartDb.clearCart() }
                        onBack()
                    } catch (e: Exception) {
                        Toast.makeText(context, "โอนไม่สำเร็จ: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        regainFocus()
                    } finally { isSubmitting = false }
                }
            }
        )
    }
}

// ---------------- UI Components ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BranchSelectionDialog(branches: List<BranchItem>, onDismiss: () -> Unit, onNext: (BranchItem) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var selectedBranch by remember { mutableStateOf<BranchItem?>(null) }

    val sortedBranches = remember(branches) {
        branches.sortedByDescending { it.branchType.uppercase().contains("FACTORY") || it.branchType.contains("โรงงาน") }
    }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color.White, shape = RoundedCornerShape(20.dp),
        title = { Text("เลือกปลายทาง", fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("กรุณาระบุสาขาหรือโรงงานที่ต้องการส่งสินค้า", fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 12.dp))

                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = selectedBranch?.branchName ?: "แตะเพื่อเลือก...",
                        onValueChange = {}, readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFFF3F4F6), unfocusedContainerColor = Color(0xFFF3F4F6),
                            focusedBorderColor = Color(0xFF5E5CE6), unfocusedBorderColor = Color.Transparent
                        ),
                        modifier = Modifier.menuAnchor().fillMaxWidth(), shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(Color.White)) {
                        sortedBranches.forEach { branch ->
                            val isFactory = branch.branchType.uppercase().contains("FACTORY") || branch.branchType.contains("โรงงาน")
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(branch.branchName, fontWeight = FontWeight.Bold)
                                        Text(branch.branchType, fontSize = 12.sp, color = Color.Gray)
                                    }
                                },
                                leadingIcon = {
                                    Surface(color = if (isFactory) Color(0xFFFFE0B2) else Color(0xFFE1F5FE), shape = RoundedCornerShape(8.dp), modifier = Modifier.size(36.dp)) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (isFactory) Icons.Default.Factory else Icons.Default.Storefront,
                                                contentDescription = null, tint = if (isFactory) Color(0xFFE65100) else Color(0xFF0288D1),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                },
                                onClick = { selectedBranch = branch; expanded = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { selectedBranch?.let { onNext(it) } }, enabled = selectedBranch != null,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E5CE6)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
            ) { Text("ยืนยันสาขา", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("ยกเลิก", color = Color.Gray) } }
    )
}

@Composable
fun ConfirmTransferDialog(targetBranch: BranchItem, itemCount: Int, totalQty: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val isFactory = targetBranch.branchType.uppercase().contains("FACTORY") || targetBranch.branchType.contains("โรงงาน")
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color.White, shape = RoundedCornerShape(24.dp),
        title = { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Surface(color = Color(0xFFFFEBEE), shape = RoundedCornerShape(50.dp), modifier = Modifier.size(60.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, contentDescription = null, tint = Color.Red, modifier = Modifier.size(30.dp)) }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text("ยืนยันการโอนสินค้า?", fontWeight = FontWeight.Bold)
        }},
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("คุณกำลังจะส่งสินค้าจำนวน $itemCount รายการ ($totalQty ชิ้น)", textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F4F6)), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isFactory) Icons.Default.Factory else Icons.Default.Storefront,
                            contentDescription = null, tint = if (isFactory) Color(0xFFE65100) else Color(0xFF0288D1)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("ปลายทาง: ${targetBranch.branchName}", fontWeight = FontWeight.Bold)
                            Text(targetBranch.branchType, fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text("ตกลง โอนสินค้าเลย", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("กลับไปตรวจสอบ", color = Color.Gray) } }
    )
}

@Composable
fun ItemCard(item: ScannedItem, onUpdateQty: (Int) -> Unit, onRemove: () -> Unit) {
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF3F4F6)), contentAlignment = Alignment.Center) {
                if (item.imageUrl != null) { AsyncImage(model = item.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                else { Icon(Icons.Default.Add, contentDescription = null, tint = Color.LightGray) }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("SKU: ${item.sku}", fontSize = 12.sp, color = Color.Gray)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.background(Color(0xFFF3F4F6), RoundedCornerShape(8.dp)).padding(4.dp)) {
                IconButton(onClick = { if (item.qty > 1) onUpdateQty(item.qty - 1) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(16.dp)) }
                Text("${item.qty}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
                IconButton(onClick = { onUpdateQty(item.qty + 1) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)) }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp)) }
            }
        }
    }
}

@Composable
fun QuantityDialog(item: ScannedItem, onDismiss: () -> Unit, onConfirm: (ScannedItem) -> Unit) {
    var tempQty by remember { mutableStateOf(1) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color.White, shape = RoundedCornerShape(16.dp),
        title = { Text("เพิ่มจำนวน", fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.size(120.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFF3F4F6)), contentAlignment = Alignment.Center) {
                    if (item.imageUrl != null) { AsyncImage(model = item.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(item.name, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    IconButton(onClick = { if (tempQty > 1) tempQty-- }, modifier = Modifier.background(Color(0xFFF3F4F6), RoundedCornerShape(8.dp)).size(40.dp)) { Icon(Icons.Default.Remove, contentDescription = null) }
                    Text(tempQty.toString(), modifier = Modifier.padding(horizontal = 24.dp), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    IconButton(onClick = { tempQty++ }, modifier = Modifier.background(Color(0xFFF3F4F6), RoundedCornerShape(8.dp)).size(40.dp)) { Icon(Icons.Default.Add, contentDescription = null) }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(item.copy(qty = tempQty)) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5E5CE6)), shape = RoundedCornerShape(8.dp)) { Text("ยืนยันจำนวน", fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("ยกเลิก", color = Color.Gray) } }
    )
}