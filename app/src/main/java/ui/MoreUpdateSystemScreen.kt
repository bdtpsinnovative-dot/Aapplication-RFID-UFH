package ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 📦 Data Class จำลองข้อมูลที่จะซิงค์
data class SyncDiffItem(
    val code: String,
    val name: String,
    val isNew: Boolean // true = ของใหม่, false = แค่อัปเดตข้อมูล (เช่น เปลี่ยนชื่อ/ราคา)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreUpdateSystemScreen(onBack: () -> Boolean) {
    val scope = rememberCoroutineScope()

    // State ควบคุมหน้าจอ
    var isFetching by remember { mutableStateOf(false) } // กำลังเช็ค API (git fetch)
    var isSyncing by remember { mutableStateOf(false) }  // กำลังเซฟลง SQLite (git pull)
    var hasChecked by remember { mutableStateOf(false) } // เช็คเสร็จหรือยัง

    // Mock Data รอซิงค์
    val pendingItems = remember { mutableStateListOf<SyncDiffItem>() }

    // ฟังก์ชันจำลองการวิ่งไปเช็ค Supabase API (git fetch)
    fun fetchUpdates() {
        scope.launch(Dispatchers.IO) {
            isFetching = true
            hasChecked = false
            delay(1500) // ⏳ จำลองโหลด

            val mockDiffs = listOf(
                SyncDiffItem("FC-T25017", "Prop - Wood Ornament FC-T25017", isNew = true),
                SyncDiffItem("FB-MC23017", "Solid Wood Decoration (อัปเดตราคา)", isNew = false),
                SyncDiffItem("FB-PG25001", "MDF Ornament (เปลี่ยนรูปภาพ)", isNew = false)
            )

            withContext(Dispatchers.Main) {
                pendingItems.clear()
                pendingItems.addAll(mockDiffs)
                isFetching = false
                hasChecked = true
            }
        }
    }

    // ฟังก์ชันจำลองการดึงข้อมูลลง SQLite (git pull)
    fun pullDataToLocal() {
        scope.launch(Dispatchers.IO) {
            isSyncing = true
            delay(2000) // ⏳ จำลองเซฟลง DB

            withContext(Dispatchers.Main) {
                pendingItems.clear()
                isSyncing = false
            }
        }
    }

    Scaffold(
        containerColor = Color(0xFFF8FAFF),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("ซิงค์ข้อมูลสินค้า", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        bottomBar = {
            if (hasChecked && pendingItems.isNotEmpty()) {
                Surface(
                    shadowElevation = 16.dp,
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                ) {
                    Column(Modifier.padding(16.dp).navigationBarsPadding()) {
                        Button(
                            onClick = { pullDataToLocal() },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            enabled = !isSyncing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text("กำลังบันทึกลงเครื่อง...", fontWeight = FontWeight.Bold)
                            } else {
                                Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("ดึงข้อมูลลงเครื่อง (Pull)", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))

            // 🔵 ส่วนหัว: เช็คสถานะ
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Outlined.CloudSync,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "สถานะฐานข้อมูล",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "เปรียบเทียบข้อมูลเครื่องกับเซิร์ฟเวอร์",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = { fetchUpdates() },
                        enabled = !isFetching && !isSyncing,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEFF6FF), contentColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isFetching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("กำลังตรวจสอบ...", fontWeight = FontWeight.Bold)
                        } else {
                            Text("ตรวจสอบอัปเดต (Fetch)", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // 🟢 ส่วนรายการอัปเดต
            AnimatedVisibility(visible = hasChecked) {
                if (pendingItems.isEmpty()) {
                    // กรณีไม่มีอัปเดต (Up to date)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp)
                    ) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF22C55E), modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("ข้อมูลเป็นเวอร์ชันล่าสุดแล้ว", fontWeight = FontWeight.Bold, color = Color(0xFF16A34A), fontSize = 18.sp)
                    }
                } else {
                    // กรณีมีอัปเดต (Show Diff)
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "รายการที่รออัปเดต",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.weight(1f))
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    "${pendingItems.size} รายการ",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(pendingItems) { item ->
                                DiffItemCard(item)
                            }
                            item { Spacer(Modifier.height(80.dp)) } // เผื่อที่ให้ปุ่มด้านล่าง
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiffItemCard(item: SyncDiffItem) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF3F4F6)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Inventory2, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.code,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
            Spacer(Modifier.width(8.dp))

            // Badge บอกสถานะ
            val badgeColor = if (item.isNew) Color(0xFFDCFCE7) else Color(0xFFDBEAFE)
            val textColor = if (item.isNew) Color(0xFF16A34A) else Color(0xFF2563EB)
            val textStr = if (item.isNew) "NEW" else "UPDATE"

            Surface(color = badgeColor, shape = RoundedCornerShape(6.dp)) {
                Text(
                    textStr,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = textColor
                )
            }
        }
    }
}