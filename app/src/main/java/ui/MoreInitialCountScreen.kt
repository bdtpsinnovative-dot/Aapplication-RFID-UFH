package ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import data.AuthManager
import data.SessionStore
import data.SupabaseInitialCountApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CountColorPrimary = Color(0xFF4F46E5)
private val CountColorBg = Color(0xFFF1F5F9)
private val CountColorSurface = Color(0xFFFFFFFF)
private val CountColorSuccess = Color(0xFF10B981)
private val CountColorError = Color(0xFFEF4444)
private val CountColorTextMain = Color(0xFF1E293B)
private val CountColorTextSub = Color(0xFF64748B)

data class InitialCountItem(val productId: Long, val productName: String, val barcodeOrSku: String, var qty: Int, val imageUrl: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreInitialCountScreen(onBack: () -> Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var isSavingItem by remember { mutableStateOf(false) } // State ตอนกำลังกดตกลงเซฟลงดีบี
    var showDeleteDialog by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }

    var showQtyDialog by remember { mutableStateOf(false) }
    var qtyInput by remember { mutableStateOf("1") }
    var currentScannedProduct by remember { mutableStateOf<InitialCountItem?>(null) }
    val qtyFocusRequester = remember { FocusRequester() }

    val dbItems = remember { mutableStateListOf<InitialCountItem>() }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    fun loadDatabaseItems() {
        scope.launch {
            isLoading = true
            try {
                val token = AuthManager.getValidAccessToken(context)
                val branchId = SessionStore.getBranchId(context)
                val items = SupabaseInitialCountApi.getSavedItems(branchId, token)
                dbItems.clear()
                dbItems.addAll(items)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "โหลดข้อมูลคลังไม่สำเร็จ", Toast.LENGTH_SHORT).show()
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadDatabaseItems()
        delay(100)
        try { focusRequester.requestFocus() } catch (_: Exception) {}
        keyboardController?.hide()
    }

    // ฟังก์ชันทำงานเมื่อกด "ตกลง" ยิงตรงเข้าสู่ Database ทันที ลอจิกยึดยอดล่าสุด!
    fun confirmQtyAndSaveDirectly() {
        val finalQty = qtyInput.toIntOrNull() ?: 0
        val product = currentScannedProduct ?: return

        scope.launch {
            isSavingItem = true
            try {
                val token = AuthManager.getValidAccessToken(context)
                val branchId = SessionStore.getBranchId(context)
                val userId = SessionStore.getUserId(context)

                // ยิงตรงบันทึกตัวเดี่ยวลงฐานข้อมูล (ถ้าใส่ 0 หรือติดลบ ระบบ API ฝั่งนู้นจะลบแถวทิ้งให้เลยครับ)
                SupabaseInitialCountApi.saveSingleItem(branchId, userId, product.productId, finalQty, token)

                Toast.makeText(context, "บันทึกยอดสำเร็จ!", Toast.LENGTH_SHORT).show()
                showQtyDialog = false
                inputText = ""
                currentScannedProduct = null

                loadDatabaseItems() // รีโหลดข้อมูลหน้าจอให้เป็นยอดอัปเดตล่าสุด
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "เกิดข้อผิดพลาด: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            } finally {
                isSavingItem = false
                delay(100)
                try { focusRequester.requestFocus() } catch (_: Exception) {}
                keyboardController?.hide()
            }
        }
    }

    fun processInputCode(code: String) {
        if (code.isBlank() || isSavingItem) {
            try { focusRequester.requestFocus() } catch (_: Exception) {}
            return
        }

        scope.launch {
            try {
                // ค้นหาว่าในคลังระบบปัจจุบัน (dbItems) มีสินค้านี้อยู่แล้วไหม
                val matchInDb = dbItems.find { it.barcodeOrSku == code }

                if (matchInDb != null) {
                    // 💡 ถ้าในระบบมีอยู่แล้ว (เช่น ยอดเดิมคือ 2) ดึงยอดเดิมมาตั้งต้นในช่องกรอกให้พนักงานเห็นทันที เพื่อความง่ายในการตรวจทานยอดล่าสุด
                    currentScannedProduct = matchInDb
                    qtyInput = matchInDb.qty.toString()
                    showQtyDialog = true
                } else {
                    // ถ้าในหน้าจอยังไม่มี ให้วิ่งไปค้นหาจากตารางสินค้าหลัก
                    val token = AuthManager.getValidAccessToken(context)
                    val product = SupabaseInitialCountApi.lookupProduct(code, token)

                    if (product != null) {
                        currentScannedProduct = product
                        qtyInput = "" // สินค้าใหม่ ตั้งยอดเริ่มต้นที่ 1
                        showQtyDialog = true
                    } else {
                        Toast.makeText(context, "ไม่พบสินค้าบาร์โค้ด: $code ในคลังระบบ", Toast.LENGTH_SHORT).show()
                        inputText = ""
                        try { focusRequester.requestFocus() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "ค้นหาผิดพลาด: ${e.message}", Toast.LENGTH_SHORT).show()
                inputText = ""
                try { focusRequester.requestFocus() } catch (_: Exception) {}
            }
        }
    }

    Scaffold(
        containerColor = CountColorBg,
        topBar = {
            TopAppBar(
                title = { Text("ตั้งต้นสต็อก", fontWeight = FontWeight.Bold, color = CountColorTextMain, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = { onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = CountColorTextMain)
                    }
                },
                actions = {
                    IconButton(onClick = { showDeleteDialog = true }, enabled = dbItems.isNotEmpty()) {
                        Icon(Icons.Rounded.DeleteForever, contentDescription = "Clear All", tint = if (dbItems.isNotEmpty()) CountColorError else Color.LightGray)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CountColorSurface)
            )
        }
        // ❌ เอาแถบบาร์ปุ่มบันทึกยอดสแกนใหม่ด้านล่างออกเรียบร้อย หน้าจอโล่งสวยงามครับนาย
    ) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad)) {
            Surface(color = CountColorSurface, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("สแกนบาร์โค้ด หรือ พิมพ์ SKU...", color = CountColorTextSub) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .focusRequester(focusRequester),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = CountColorTextSub) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { processInputCode(inputText) }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CountColorTextSub,
                        unfocusedBorderColor = Color.LightGray
                    )
                )
            }

            if (isLoading) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CountColorPrimary)
                }
            } else if (dbItems.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Inventory2, contentDescription = null, modifier = Modifier.size(64.dp), tint = CountColorTextSub.copy(alpha = 0.3f))
                        Spacer(Modifier.height(16.dp))
                        Text("ยังไม่มีการตั้งต้นสต็อกเลย", fontWeight = FontWeight.Bold, color = CountColorTextSub)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text("☁️ ยอดสินค้าตั้งต้นคลังระบบปัจจุบัน", modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp), fontWeight = FontWeight.Bold, color = CountColorTextSub)
                    }
                    itemsIndexed(dbItems) { index, item ->
                        InitialCountItemCard(index = index + 1, item = item)
                    }
                }
            }
        }

        // โมดูลระบุจำนวนสินค้าตรงกลางตัวเลขใหญ่ชัดเจน
        if (showQtyDialog && currentScannedProduct != null) {
            LaunchedEffect(Unit) {
                delay(100)
                try { qtyFocusRequester.requestFocus() } catch (_: Exception) {}
            }

            AlertDialog(
                onDismissRequest = {
                    if (!isSavingItem) {
                        showQtyDialog = false
                        inputText = ""
                        scope.launch { delay(100); try { focusRequester.requestFocus() } catch (_: Exception) {} }
                    }
                },
                title = { Text("ระบุจำนวนสินค้า", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (!currentScannedProduct!!.imageUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = currentScannedProduct!!.imageUrl,
                                contentDescription = null,
                                modifier = Modifier.size(100.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.height(12.dp))
                        }

                        Text(currentScannedProduct!!.productName, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = CountColorTextMain)
                        // ✅ ตัดเลขบาร์โค้ดรกๆ ออกเรียบร้อย คลีนตามใจสั่งครับนาย

                        Spacer(Modifier.height(24.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            IconButton(
                                onClick = {
                                    val current = qtyInput.toIntOrNull() ?: 1
                                    qtyInput = (current - 1).toString()
                                },
                                enabled = !isSavingItem,
                                modifier = Modifier.size(48.dp).clip(CircleShape).background(CountColorBg)
                            ) {
                                Icon(Icons.Rounded.Remove, contentDescription = "Decrease", tint = CountColorError)
                            }

                            Spacer(Modifier.width(16.dp))

                            BasicTextField(
                                value = qtyInput,
                                onValueChange = { qtyInput = it },
                                enabled = !isSavingItem,
                                modifier = Modifier
                                    .width(100.dp)
                                    .height(48.dp)
                                    .focusRequester(qtyFocusRequester),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { confirmQtyAndSaveDirectly() }),
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    textAlign = TextAlign.Center,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 24.sp,
                                    color = CountColorTextMain
                                ),
                                decorationBox = { innerTextField ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .border(2.dp, CountColorPrimary, RoundedCornerShape(12.dp))
                                            .background(CountColorSurface, RoundedCornerShape(12.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        innerTextField()
                                    }
                                }
                            )

                            Spacer(Modifier.width(16.dp))

                            IconButton(
                                onClick = {
                                    val current = qtyInput.toIntOrNull() ?: 0
                                    qtyInput = (current + 1).toString()
                                },
                                enabled = !isSavingItem,
                                modifier = Modifier.size(48.dp).clip(CircleShape).background(CountColorPrimary.copy(alpha = 0.1f))
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = "Increase", tint = CountColorPrimary)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { confirmQtyAndSaveDirectly() },
                        colors = ButtonDefaults.buttonColors(containerColor = CountColorPrimary),
                        enabled = !isSavingItem,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isSavingItem) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("ตกลง", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showQtyDialog = false
                            inputText = ""
                            scope.launch { delay(100); try { focusRequester.requestFocus() } catch (_: Exception) {} }
                        },
                        enabled = !isSavingItem,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ยกเลิก", color = CountColorTextSub)
                    }
                },
                containerColor = CountColorSurface
            )
        }

        // Dialog ยืนยันการลบทั้งหมด
        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                icon = { Icon(Icons.Rounded.Warning, contentDescription = null, tint = CountColorError, modifier = Modifier.size(40.dp)) },
                title = { Text("ล้างข้อมูลทั้งหมด?", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
                text = { Text("คุณต้องการลบข้อมูลตั้งต้นสต็อก 'ทั้งหมด' ของสาขานี้ออกจากระบบใช่หรือไม่?\n\nการกระทำนี้ไม่สามารถกู้คืนได้", textAlign = TextAlign.Center) },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteDialog = false
                            scope.launch {
                                isLoading = true
                                try {
                                    val token = AuthManager.getValidAccessToken(context)
                                    val branchId = SessionStore.getBranchId(context)
                                    SupabaseInitialCountApi.deleteAllCounts(branchId, token)
                                    dbItems.clear()
                                    Toast.makeText(context, "ล้างระบบเรียบร้อยแล้ว", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    Toast.makeText(context, "ลบไม่สำเร็จ: ${e.message}", Toast.LENGTH_LONG).show()
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CountColorError)
                    ) {
                        Text("ยืนยันการลบ", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) { Text("ยกเลิก", color = CountColorTextSub) }
                },
                containerColor = CountColorSurface
            )
        }
    }
}

@Composable
fun InitialCountItemCard(index: Int, item: InitialCountItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CountColorSurface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "$index.", fontWeight = FontWeight.Bold, color = CountColorTextSub, modifier = Modifier.width(28.dp))

            if (!item.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = item.imageUrl,
                    contentDescription = "Product Image",
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color.LightGray.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.ImageNotSupported, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(24.dp))
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.productName,
                    fontWeight = FontWeight.Bold,
                    color = CountColorTextMain,
                    fontSize = 15.sp
                )
                // ✅ เอาไอคอนบาร์โค้ด และรหัสบาร์โค้ดแถวล่างออกไปแล้ว คลีนสะใจแน่นอนครับ
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(CountColorTextSub.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "x${item.qty}", fontWeight = FontWeight.ExtraBold, color = CountColorTextMain, fontSize = 16.sp)
            }
        }
    }
}