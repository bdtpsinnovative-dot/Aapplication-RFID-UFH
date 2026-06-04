package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import coil.compose.AsyncImage // ✨ Import Coil สำหรับดึงรูปภาพ
import data.ReceiveItemData
import data.SupabaseTransferApi
import data.ProductDatabase // ✨ Import SQLite ของนายมาใช้งาน

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferReceiveScreen(
    transferId: Long,
    accessToken: String,
    userId: String,
    api: SupabaseTransferApi,
    onBack: () -> Boolean
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // ✨ สร้าง Instance ของ Database กลาง
    val productDb = remember { ProductDatabase(context) }

    var receiveItems by remember { mutableStateOf<List<ReceiveItemData>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    var scanInput by remember { mutableStateOf("") }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var scanFeedback by remember { mutableStateOf("") }

    // State สำหรับโมดูล (Pop-up)
    var scannedItemToEdit by remember { mutableStateOf<ReceiveItemData?>(null) }
    var editQtyInput by remember { mutableStateOf("") }

    LaunchedEffect(transferId) {
        try {
            isLoading = true
            errorMessage = ""
            val itemsFromDb = api.getTransferItemsForReceive(accessToken, transferId)
            // บังคับยอดตั้งต้นเป็น 0
            receiveItems = itemsFromDb.map { it.copy(receivedQty = 0) }
        } catch (e: Exception) {
            errorMessage = e.message ?: "เกิดข้อผิดพลาดในการดึงข้อมูล"
        } finally {
            isLoading = false
        }
    }

    // 🎯 ฟังก์ชันเมื่อยิงบาร์โค้ด (อัปเกรดให้ใช้ SQLite กลาง + เตรียมรูปภาพ)
    fun handleScan(scannedCode: String) {
        val code = scannedCode.trim()
        if (code.isEmpty()) return

        // 1. ถาม SQLite กลางก่อนเลยว่ารหัสที่ยิงมาคือสินค้าอะไร
        val localProduct = productDb.findByCode(code)

        if (localProduct != null) {
            // 2. เช็คว่าในตั๋วใบโอนใบนี้ มีสินค้านี้รอรับอยู่ไหม
            val itemInTransfer = receiveItems.find { it.productId == localProduct.id }

            if (itemInTransfer != null) {
                // ✨ 3. เอา URL รูปภาพจาก SQLite มาอัปเดตใส่เพื่อให้โชว์ในป๊อปอัพ
                val finalItem = if (itemInTransfer.imageUrl.isEmpty() && !localProduct.imageUrl.isNullOrEmpty()) {
                    itemInTransfer.copy(imageUrl = localProduct.imageUrl)
                } else {
                    itemInTransfer
                }

                // เปิดโมดูล Pop-up ขึ้นมาให้กรอกเลขเลย!
                scannedItemToEdit = finalItem
                editQtyInput = if (finalItem.receivedQty > 0) finalItem.receivedQty.toString() else ""
                scanFeedback = ""
            } else {
                scanFeedback = "⚠️ เจอ '${localProduct.name}' แต่ไม่ได้อยู่ในตั๋วใบนี้!"
            }
        } else {
            scanFeedback = "❌ ไม่พบสินค้าจากรหัส: $code ในระบบฐานข้อมูล"
        }
        scanInput = "" // เคลียร์ช่องแสกนรอตัวต่อไป
    }

    // ฟังก์ชันเซฟจำนวนจากโมดูลลงในลิสต์
    fun saveScannedQty() {
        val newQty = editQtyInput.toIntOrNull() ?: 0
        val targetId = scannedItemToEdit?.id
        if (targetId != null) {
            val newList = receiveItems.toMutableList()
            val index = newList.indexOfFirst { it.id == targetId }
            if (index != -1) {
                val item = newList[index]
                newList[index] = item.copy(receivedQty = newQty)
                receiveItems = newList
                scanFeedback = "✅ อัปเดต ${item.productName} เป็น $newQty ชิ้น"
            }
        }
        scannedItemToEdit = null // ปิดโมดูล
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("ตรวจรับสินค้า", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp)) {

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Scaffold
            }
            if (errorMessage.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(errorMessage, color = Color.Red, fontWeight = FontWeight.Bold) }
                return@Scaffold
            }

            // ช่องสำหรับเครื่องยิงบาร์โค้ด
            OutlinedTextField(
                value = scanInput,
                onValueChange = { scanInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("ยิงบาร์โค้ด / กรอก SKU สินค้าที่นี่") },
                leadingIcon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { handleScan(scanInput) }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.primary)
            )

            if (scanFeedback.isNotEmpty()) {
                Text(
                    text = scanFeedback,
                    color = when {
                        scanFeedback.startsWith("✅") -> Color(0xFF2E7D32)
                        scanFeedback.startsWith("⚠️") -> Color(0xFFE65100)
                        else -> Color.Red
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            val scannedCount = receiveItems.count { it.receivedQty > 0 }
            Text("ความคืบหน้า: ตรวจแล้ว $scannedCount / ${receiveItems.size} รายการ", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.DarkGray)
            Spacer(modifier = Modifier.height(8.dp))

            // ลิสต์รายการสินค้า
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(receiveItems) { _, item ->
                    val variance = item.receivedQty - item.transferQty
                    val isScanned = item.receivedQty > 0

                    Card(
                        onClick = {
                            scannedItemToEdit = item
                            editQtyInput = if (item.receivedQty > 0) item.receivedQty.toString() else ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = if (isScanned) Color(0xFFF1F8E9) else Color.White),
                        border = if (isScanned) null else CardDefaults.outlinedCardBorder()
                    ) {
                        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {

                            // 📸 ✨ แสดงรูปภาพจิ๋วในหน้ารายการลิสต์สินค้าแบบคลีนๆ
                            AsyncImage(
                                model = item.imageUrl.ifEmpty { "https://via.placeholder.com/150" }, // ถ้าไม่มีรูปดึง Placeholder มาใช้
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.LightGray)
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.productName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("Barcode: ${item.barcode}", fontSize = 12.sp, color = Color.Gray)
                            }

                            if (isScanned) {
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("รับแล้ว: ${item.receivedQty}", fontWeight = FontWeight.Black, fontSize = 16.sp, color = Color(0xFF1E3C72))
                                    Text(
                                        text = when {
                                            variance == 0 -> "ครบพอดี"
                                            variance > 0 -> "เกิน +$variance"
                                            else -> "ขาด ${Math.abs(variance)}"
                                        },
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = when {
                                            variance == 0 -> Color(0xFF2E7D32)
                                            variance > 0 -> Color(0xFFE65100)
                                            else -> Color(0xFFC62828)
                                        }
                                    )
                                }
                            } else {
                                Text("รอยิงแสกน", fontSize = 12.sp, color = Color.LightGray, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { showConfirmDialog = true },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("สรุปและตรวจสอบยอดรับของ", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    // 🚀 โมดูล (Pop-up) สำหรับกรอกจำนวน
    if (scannedItemToEdit != null) {
        AlertDialog(
            onDismissRequest = { scannedItemToEdit = null },
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    // 📸 ✨ แสดงรูปภาพสินค้าขนาดใหญ่ตรงกลางหน้าจอป๊อปอัพ
                    AsyncImage(
                        model = scannedItemToEdit!!.imageUrl.ifEmpty { "https://via.placeholder.com/150" },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.LightGray)
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(scannedItemToEdit!!.productName, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("Barcode: ${scannedItemToEdit!!.barcode}", fontSize = 12.sp, color = Color.Gray)
                }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Surface(color = Color(0xFFE3F2FD), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            text = "จำนวนที่ต้นทางส่งมา: ${scannedItemToEdit!!.transferQty} ชิ้น",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1565C0),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("ระบุจำนวนที่นับได้จริง", fontWeight = FontWeight.Bold, color = Color.DarkGray)
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = editQtyInput,
                        onValueChange = { newValue ->
                            if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                                editQtyInput = newValue
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { saveScannedQty() }),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center, fontSize = 28.sp, fontWeight = FontWeight.Black),
                        modifier = Modifier.fillMaxWidth(0.6f).height(80.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.Gray
                        )
                    )
                }
            },
            confirmButton = {
                Button(onClick = { saveScannedQty() }, modifier = Modifier.fillMaxWidth()) {
                    Text("บันทึกจำนวน", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // 🎯 หน้าต่าง Dialog ตรวจสอบสรุปยอดก่อนเซฟลง Database
    if (showConfirmDialog) {
        val hasVariance = receiveItems.any { it.receivedQty != it.transferQty }
        val unscannedCount = receiveItems.count { it.receivedQty == 0 }

        AlertDialog(
            onDismissRequest = { if (!isSubmitting) showConfirmDialog = false },
            title = { Text(if (hasVariance || unscannedCount > 0) "พบสินค้าไม่ครบ/เกิน!" else "ยอดถูกต้องครบถ้วน", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("สรุปผลการตรวจรับสินค้า:")
                    Spacer(modifier = Modifier.height(12.dp))

                    if (unscannedCount > 0) {
                        Text("⚠️ มีสินค้ายังไม่ได้สแกน $unscannedCount รายการ (ยอดรับจะเป็น 0)", color = Color.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    receiveItems.forEach { item ->
                        val diff = item.receivedQty - item.transferQty
                        if (diff != 0) {
                            Text(
                                text = "• ${item.productName}: ${if(diff > 0) "เกิน +$diff" else "ขาด ${Math.abs(diff)} (นับได้ ${item.receivedQty})"}",
                                color = if (diff > 0) Color(0xFFE65100) else Color.Red,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                    if (!hasVariance && unscannedCount == 0) {
                        Text("✅ สินค้าทุกรายการรับครบตามจำนวนที่โอนมา", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            try {
                                isSubmitting = true
                                val success = api.submitReceivedStockRPC(accessToken, transferId, receiveItems, userId)
                                if (success) {
                                    showConfirmDialog = false
                                    onBack()
                                }
                            } catch (e: Exception) {
                                errorMessage = e.message ?: "บันทึกข้อมูลล้มเหลว"
                            } finally {
                                isSubmitting = false
                            }
                        }
                    },
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.buttonColors(containerColor = if(hasVariance || unscannedCount > 0) Color(0xFFE65100) else Color(0xFF2E7D32))
                ) {
                    if (isSubmitting) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                    else Text("ยืนยันบันทึกสต๊อก")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }, enabled = !isSubmitting) { Text("กลับไปแก้ไข", color = Color.Gray) }
            }
        )
    }
}