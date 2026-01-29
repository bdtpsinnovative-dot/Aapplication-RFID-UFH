package com.example.eob_rfid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private data class MoreMenuItem(
    val title: String,
    val subtitle: String,
    val route: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
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
                icon = Icons.Outlined.SwapHoriz
            ),
            MoreMenuItem(
                title = "จัดเรียงสินค้า",
                subtitle = "จัดหมวดหมู่/เรียงลำดับการแสดงผล",
                route = Routes.MORE_ARRANGE,
                icon = Icons.Outlined.Sort
            ),
            MoreMenuItem(
                title = "นับสินค้าตั้งต้น",
                subtitle = "เริ่มต้นยอดนับครั้งแรกสำหรับระบบ",
                route = Routes.MORE_INITIAL_COUNT,
                icon = Icons.Outlined.Inventory2
            ),
            MoreMenuItem(
                title = "สินค้าเสียหาย",
                subtitle = "บันทึกของเสีย/แตก/หมดอายุ",
                route = Routes.MORE_DAMAGE,
                icon = Icons.Outlined.ReportProblem
            ),
            MoreMenuItem(
                title = "รายการปัญหา",
                subtitle = "แจ้งปัญหา/ติดตามการแก้ไข",
                route = Routes.MORE_ISSUES,
                icon = Icons.Outlined.SupportAgent
            ),
            MoreMenuItem(
                title = "อัพเดตระบบ",
                subtitle = "ตรวจสอบเวอร์ชันและอัปเดต",
                route = Routes.MORE_UPDATE_SYSTEM,
                icon = Icons.Outlined.SystemUpdateAlt
            )
        )
    }

    val pageBg = remember {
        Brush.verticalGradient(
            colors = listOf(Color(0xFFF8FAFF), Color(0xFFF5F5F5))
        )
    }

    val headerBg = remember {
        Brush.horizontalGradient(
            colors = listOf(Color(0xFF5B86E5), Color(0xFF36D1DC))
        )
    }

    Scaffold(
        topBar = {
            // ✅ Top bar แบบโปร + มีพื้นหลัง gradient
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(headerBg)
                    .statusBarsPadding()
            ) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            "เมนูผู้ดูแล",
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { onBack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.White
                    )
                )
            }
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(pageBg)
                .padding(pad)
                .padding(horizontal = 16.dp, vertical = 16.dp),
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
                    onClick = { onGo(item.route) }
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
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ไอคอนในกรอบสวยๆ
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
