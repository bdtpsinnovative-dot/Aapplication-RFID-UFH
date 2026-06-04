package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Import ข้อมูลและ API จริง
import data.SessionManager
import data.SessionStore
import data.SupabaseTransferApi
import data.TransferListItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreTransferScreen(onBack: () -> Boolean) {
    // ใช้ควบคุมสถานะสลับหน้าจอ: "MENU", "SEND", "RECEIVE_LIST", "RECEIVE_DETAIL"
    var currentSubMode by remember { mutableStateOf("MENU") }
    var selectedTransferId by remember { mutableStateOf<Long?>(null) }

    val context = LocalContext.current
    val api = remember { SupabaseTransferApi() }
    val accessToken = remember { SessionManager.accessToken ?: SessionStore.getAccessToken(context) ?: "" }
    val myBranchId = remember { SessionStore.getBranchId(context) }
    val userId = remember { SessionManager.userId ?: SessionStore.getUserId(context) }

    when (currentSubMode) {
        // 1. หน้าจอสร้างใบโอนออก
        "SEND" -> TransferSendScreen(onBack = { currentSubMode = "MENU"; true })

        // 2. 📥 หน้าจอแสดงรายการใบโอนจริงที่ค้างรับ (ดึงมาจาก Supabase)
        "RECEIVE_LIST" -> {
            var pendingTransfers by remember { mutableStateOf<List<TransferListItem>>(emptyList()) }
            var isListLoading by remember { mutableStateOf(true) }

            // ดึงตั๋วจริงของสาขาที่เข้าใช้งาน
            LaunchedEffect(Unit) {
                try {
                    isListLoading = true
                    pendingTransfers = api.fetchPendingTransfers(accessToken, myBranchId)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    isListLoading = false
                }
            }

            Scaffold(
                topBar = {
                    CenterAlignedTopAppBar(
                        title = { Text("เลือกใบโอนค้างรับ", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = { currentSubMode = "MENU" }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    )
                }
            ) { pad ->
                Box(modifier = Modifier.fillMaxSize().padding(pad).background(Color(0xFFF8F9FA))) {
                    if (isListLoading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (pendingTransfers.isEmpty()) {
                        Text("ไม่มีตั๋วสินค้าโอนเข้าค้างรับในตอนนี้", modifier = Modifier.align(Alignment.Center), color = Color.Gray, fontWeight = FontWeight.Bold)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(pendingTransfers) { transfer ->
                                Card(
                                    onClick = {
                                        // 🎯 จิ้มเอาตั๋วจริง ID จริง ส่งไปทำงานต่อ!
                                        selectedTransferId = transfer.id
                                        currentSubMode = "RECEIVE_DETAIL"
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = Color.White)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(text = "เลขที่เอกสาร: ${transfer.transferCode}", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color(0xFF1E3C72))
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(text = "ส่งมาจาก: ${transfer.fromBranchName}", fontSize = 13.sp, color = Color.DarkGray)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Surface(color = Color(0xFFFFF3E0), shape = RoundedCornerShape(4.dp)) {
                                            Text(text = " รอตรวจรับ ", fontSize = 11.sp, color = Color(0xFFE65100), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. 🎯 หน้าสแกนตรวจรับของจริง (ได้รับ ID จริงส่งมาจากหน้า List แล้ว)
        "RECEIVE_DETAIL" -> {
            selectedTransferId?.let { transferId ->
                TransferReceiveScreen(
                    transferId = transferId,
                    accessToken = accessToken,
                    userId = userId,
                    api = api,
                    onBack = { currentSubMode = "RECEIVE_LIST"; true }
                )
            }
        }

        // หน้าเมนูเลือกหลัก
        else -> {
            Scaffold(
                topBar = {
                    CenterAlignedTopAppBar(
                        title = { Text("ระบบโอนย้ายสต๊อก", fontWeight = FontWeight.ExtraBold) },
                        navigationIcon = {
                            IconButton(onClick = { onBack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    )
                }
            ) { pad ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(pad).background(Color(0xFFF8F9FA)).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "เลือกเมนูการทำงาน", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray, modifier = Modifier.align(Alignment.Start).padding(bottom = 16.dp))

                    // ปุ่มที่ 1: โอนสินค้าออก
                    Card(
                        onClick = { currentSubMode = "SEND" },
                        modifier = Modifier.fillMaxWidth().height(130.dp).padding(bottom = 20.dp),
                        shape = RoundedCornerShape(20.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF1E3C72), Color(0xFF2A5298)))).padding(20.dp)) {
                            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text("1. โอนสินค้าออก", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                    Text("สร้างตั๋วและตัดสต๊อกต้นทาง", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                                }
                                Icon(Icons.Default.LocalShipping, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                            }
                        }
                    }

                    // ปุ่มที่ 2: ตรวจรับสินค้าเข้า (เปลี่ยนให้เปิดหน้ารายการดึงข้อมูลจริง)
                    Card(
                        onClick = { currentSubMode = "RECEIVE_LIST" },
                        modifier = Modifier.fillMaxWidth().height(130.dp),
                        shape = RoundedCornerShape(20.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF0F9D58), Color(0xFF11998E)))).padding(20.dp)) {
                            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text("2. ตรวจรับสินค้าเข้า", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                    Text("สแกนเช็คสต๊อกของเข้าคลังปลายทาง", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                                }
                                Icon(Icons.Default.MoveToInbox, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}