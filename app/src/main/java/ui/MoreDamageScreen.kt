package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import data.*
import kotlinx.coroutines.launch

enum class DamageMode(val title: String) {
    RFID("ตัดด้วย RFID (รายชิ้น)"),
    QUANTITY("ตัดตามจำนวน (ปกติ)")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreDamageScreen(onBack: () -> Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    var barcodeInput by remember { mutableStateOf("") }
    var selectedProduct by remember { mutableStateOf<ProductCache?>(null) }

    // ✨ State ใหม่เอาไว้เก็บยอดสต็อก
    var currentStock by remember { mutableStateOf<Double?>(null) }

    var selectedMode by remember { mutableStateOf(DamageMode.RFID) }
    var rfidTag by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }
    var reason by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    fun performSearch(code: String) {
        val cleanCode = code.trim()
        if (cleanCode.isBlank()) return

        val db = ProductDatabase(ctx)
        val result = db.findByCode(cleanCode)

        if (result != null) {
            selectedProduct = result
            barcodeInput = cleanCode
            focusManager.clearFocus()
            currentStock = null // ล้างยอดสต็อกเก่ารอโหลดใหม่

            // ✨ ยิงไปดึงสต็อกสาขาตัวเองมาโชว์ครับนาย!
            scope.launch {
                try {
                    val token = AuthManager.getValidAccessToken(ctx)
                    val branchId = SessionStore.getBranchId(ctx)
                    if (token != null && branchId != null && branchId > 0) {
                        currentStock = SupabaseDamageApi.getCurrentStock(token, result.id, branchId)
                    } else {
                        currentStock = 0.0
                    }
                } catch (e: Exception) {
                    currentStock = 0.0
                }
            }
        } else {
            selectedProduct = null
            currentStock = null
            scope.launch { snackbarHostState.showSnackbar("ไม่พบสินค้าบาร์โค้ด: $cleanCode") }
        }
    }

    fun clearScan() {
        barcodeInput = ""
        selectedProduct = null
        currentStock = null
        rfidTag = ""
        quantity = "1"
        reason = ""
    }

    fun onSave() {
        val prod = selectedProduct
        val userId = SessionStore.getUserId(ctx)
        val email = SessionManager.email
        val branchId = SessionStore.getBranchId(ctx)

        if (prod == null) {
            scope.launch { snackbarHostState.showSnackbar("นายครับ ต้องสแกนสินค้าก่อนครับ") }
            return
        }
        if (selectedMode == DamageMode.RFID && rfidTag.isBlank()) {
            scope.launch { snackbarHostState.showSnackbar("นายครับ ยิงปืนสแกน RFID ใส่ช่องก่อนครับ") }
            return
        }
        if (selectedMode == DamageMode.QUANTITY && (quantity.toDoubleOrNull() ?: 0.0) <= 0.0) {
            scope.launch { snackbarHostState.showSnackbar("จำนวนต้องมากกว่า 0 ครับนาย") }
            return
        }
        if (reason.isBlank()) {
            scope.launch { snackbarHostState.showSnackbar("ระบุสาเหตุให้หน่อยครับนาย (เช่น แตก, หัก)") }
            return
        }
        if (branchId == 0L || branchId == null) {
            scope.launch { snackbarHostState.showSnackbar("ไม่พบข้อมูลสาขา กรุณาล็อกอินใหม่ครับนาย") }
            return
        }

        isLoading = true
        scope.launch {
            try {
                val success = AuthManager.withValidToken(ctx) { token ->
                    SupabaseDamageApi.recordDamage(
                        accessToken = token,
                        productId = prod.id,
                        branchId = branchId,
                        qty = if (selectedMode == DamageMode.RFID) 1.0 else (quantity.toDoubleOrNull() ?: 1.0),
                        reason = reason,
                        recordedBy = userId ?: "",
                        recordedByName = email,
                        rfidTag = if (selectedMode == DamageMode.RFID) rfidTag else null
                    )
                }

                if (success) {
                    snackbarHostState.showSnackbar("✅ บันทึกของเสียเรียบร้อย ตัดสต็อกแล้วครับนาย!")
                    clearScan()
                } else {
                    snackbarHostState.showSnackbar("❌ บันทึกไม่สำเร็จ ลองอีกครั้งครับนาย")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                snackbarHostState.showSnackbar("เกิดข้อผิดพลาด: ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Box(Modifier.fillMaxWidth().background(Color(0xFFE53935)).statusBarsPadding()) {
                CenterAlignedTopAppBar(
                    title = { Text("บันทึกสินค้าเสียหาย", fontWeight = FontWeight.Bold, color = Color.White) },
                    navigationIcon = {
                        IconButton(onClick = { onBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
                )
            }
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            OutlinedTextField(
                value = barcodeInput,
                onValueChange = { newValue ->
                    if (newValue.endsWith("\n") || newValue.endsWith("\r")) {
                        performSearch(newValue)
                    } else {
                        barcodeInput = newValue
                        if (selectedProduct != null) {
                            selectedProduct = null
                            currentStock = null
                        }
                    }
                },
                label = { Text("ยิงบาร์โค้ดสินค้าที่นี่...", fontWeight = FontWeight.Bold) },
                modifier = Modifier.fillMaxWidth().height(72.dp),
                shape = RoundedCornerShape(12.dp),
                textStyle = LocalTextStyle.current.copy(fontWeight = FontWeight.ExtraBold, color = Color.Blue),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { performSearch(barcodeInput) }),
                leadingIcon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(32.dp), tint = Color.Gray) },
                trailingIcon = {
                    if (barcodeInput.isNotEmpty()) {
                        IconButton(onClick = { clearScan() }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color.Red)
                        }
                    }
                }
            )

            if (selectedProduct != null) {
                val prod = selectedProduct!!
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF8F9FA)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        AsyncImage(
                            model = prod.imageUrl,
                            contentDescription = null,
                            modifier = Modifier.size(150.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(4.dp),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = prod.name,
                            fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))

                        // ✨ ส่วนแสดงยอดสต็อกสาขาปัจจุบันแบบสะดุดตา
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (currentStock == null) Color.LightGray.copy(alpha = 0.2f) else Color(0xFFE3F2FD))
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            if (currentStock == null) {
                                Text("กำลังเช็คสต็อกสาขา...", color = Color.Gray, fontWeight = FontWeight.Bold)
                            } else {
                                Text(
                                    "สต็อกคงเหลือสาขา: $currentStock",
                                    color = Color(0xFF1976D2),
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 18.sp
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color.Gray.copy(alpha = 0.2f))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DamageMode.values().forEach { mode ->
                        val isSelected = selectedMode == mode
                        Button(
                            onClick = {
                                selectedMode = mode
                                rfidTag = ""
                                if (mode == DamageMode.RFID) quantity = "1"
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) Color(0xFFE53935) else Color.LightGray.copy(alpha = 0.3f),
                                contentColor = if (isSelected) Color.White else Color.DarkGray
                            ),
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(mode.title, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (selectedMode == DamageMode.RFID) {
                    OutlinedTextField(
                        value = rfidTag,
                        onValueChange = {
                            if (it.endsWith("\n") || it.endsWith("\r")) {
                                rfidTag = it.trim()
                                focusManager.clearFocus()
                            } else {
                                rfidTag = it
                            }
                        },
                        label = { Text("ยิงรหัส UHF RFID ที่นี่", fontWeight = FontWeight.Bold) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Sensors, contentDescription = null, tint = Color(0xFFE53935)) }
                    )
                } else {
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = { Text("ระบุจำนวนที่ต้องการตัดของเสีย") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Numbers, contentDescription = null, tint = Color.Gray) }
                    )
                }

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("ระบุสาเหตุ (เช่น แตก, เปียกน้ำ)") },
                    modifier = Modifier.fillMaxWidth().height(90.dp),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 2
                )

                Button(
                    onClick = ::onSave,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    } else {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("ตัดสต็อกของเสีย", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                    }
                }
                Spacer(Modifier.height(16.dp))
            } else {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.DocumentScanner, contentDescription = null, modifier = Modifier.size(64.dp), tint = Color.LightGray)
                        Spacer(Modifier.height(16.dp))
                        Text("นายครับ กรุณายิงบาร์โค้ดเพื่อเริ่มรายการ", color = Color.Gray, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}