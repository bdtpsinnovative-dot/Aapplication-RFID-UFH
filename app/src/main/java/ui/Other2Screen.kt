package ui

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import navigation.Routes

// กลับมาใช้ route แบบปกติ ไม่ต้องมี Action ซับซ้อนแล้ว
private data class MoreMenuItem(
    val title: String,
    val subtitle: String,
    val route: String,
    val icon: ImageVector,
    val isEnabled: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Other2Screen(
    onBack: () -> Boolean,
    onGo: (String) -> Unit
) {
    val items = remember {
        listOf(
            MoreMenuItem(
                title = "โอนสินค้า",
                subtitle = "ย้ายสต๊อกระหว่างคลัง/สาขา",
                route = Routes.MORE_TRANSFER,
                icon = Icons.Outlined.SwapHoriz,
                isEnabled = true
            ),
            MoreMenuItem(
                title = "จัดเรียงสินค้า",
                subtitle = "จัดหมวดหมู่/เรียงลำดับการแสดงผล",
                route = Routes.MORE_ARRANGE,
                icon = Icons.Outlined.Sort,
                isEnabled = false
            ),
            MoreMenuItem(
                title = "นับสินค้าตั้งต้น",
                subtitle = "เริ่มต้นยอดนับครั้งแรกสำหรับระบบ",
                route = Routes.MORE_INITIAL_COUNT,
                icon = Icons.Outlined.Inventory2,
                isEnabled = true
            ),
            MoreMenuItem(
                title = "สินค้าเสียหาย",
                subtitle = "บันทึกของเสีย/แตก/หมดอายุ",
                route = Routes.MORE_DAMAGE,
                icon = Icons.Outlined.ReportProblem,
                isEnabled = true
            ),
            MoreMenuItem(
                title = "รายการปัญหา",
                subtitle = "แจ้งปัญหา/ติดตามการแก้ไข",
                route = Routes.MORE_ISSUES,
                icon = Icons.Outlined.SupportAgent,
                isEnabled = false
            ),
            // ✅ เปลี่ยนไอคอนและข้อความ และสั่งให้วิ่งไปหน้า MORE_UPDATE_SYSTEM
            MoreMenuItem(
                title = "ซิงค์ข้อมูล (อัพเดตระบบ)",
                subtitle = "ตรวจสอบและดึงฐานข้อมูลสินค้าล่าสุด",
                route = Routes.MORE_UPDATE_SYSTEM,
                icon = Icons.Outlined.CloudSync,
                isEnabled = true
            )
        )
    }

    val pageBg = remember { Brush.verticalGradient(colors = listOf(Color(0xFFF8FAFF), Color(0xFFF5F5F5))) }
    val headerBg = remember { Brush.horizontalGradient(colors = listOf(Color(0xFF5B86E5), Color(0xFF36D1DC))) }

    Scaffold(
        topBar = {
            Box(Modifier.fillMaxWidth().background(headerBg).statusBarsPadding()) {
                CenterAlignedTopAppBar(
                    title = { Text("เมนูผู้ดูแล", fontWeight = FontWeight.ExtraBold, color = Color.White) },
                    navigationIcon = {
                        IconButton(onClick = { onBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent, titleContentColor = Color.White
                    )
                )
            }
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(pageBg).padding(pad).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "งานจัดการสต๊อก",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
            }

            items(items) { item ->
                AdminMenuCard(
                    title = item.title,
                    subtitle = item.subtitle,
                    icon = item.icon,
                    isEnabled = item.isEnabled,
                    onClick = {
                        if (item.isEnabled) {
                            // โยน route ไปให้ Navigation จัดการพาไปหน้า Git Pull เลย!
                            onGo(item.route)
                        }
                    }
                )
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminMenuCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (isEnabled) Color.White else Color(0xFFF0F0F0)

    Card(
        onClick = { if (isEnabled) onClick() },
        enabled = isEnabled,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isEnabled) 2.dp else 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        if (isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                        else Color.Gray.copy(alpha = 0.15f),
                        RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (isEnabled) MaterialTheme.colorScheme.primary else Color.Gray
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isEnabled) Color.Unspecified else Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (!isEnabled) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = Color.LightGray.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "เร็วๆ นี้",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.DarkGray
                            )
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isEnabled) MaterialTheme.colorScheme.onSurfaceVariant else Color.Gray.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                if (isEnabled) Icons.Outlined.ChevronRight else Icons.Outlined.Lock,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isEnabled) MaterialTheme.colorScheme.onSurfaceVariant else Color.Gray.copy(alpha = 0.5f)
            )
        }
    }
}