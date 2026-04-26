package ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChecklistRtl
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LotMenuScreen(
    lotId: Long?,
    lotCode: String?,
    onBack: () -> Unit,
    onReceive: () -> Unit,
    onCheck: () -> Unit
) {
    // 💡 เช็คว่าเป็นโหมดนอกลอตหรือไม่ (ถ้า lotId เป็น null หรือ 0)
    val isOutOfLot = lotId == null || lotId == 0L

    // ตั้งค่าข้อความและสีให้เข้ากับโหมด
    val titleText = if (isOutOfLot) "รับเข้านอกลอต" else "ลอต: $lotCode"
    val themeColor = if (isOutOfLot) Color(0xFFF57F17) else Color(0xFF4CAF50) // สีส้ม สำหรับนอกลอต, สีเขียว สำหรับมีลอต

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(titleText, fontWeight = FontWeight.Bold, color = if (isOutOfLot) themeColor else MaterialTheme.colorScheme.onSurface)
                        Text("เลือกการดำเนินการ", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "กลับ")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                titleText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (isOutOfLot) themeColor else MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "กรุณาเลือกการดำเนินการ",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )

            Spacer(Modifier.height(48.dp))

            // ปุ่มรับเข้า (เปลี่ยนสีและคำอธิบายตามโหมด)
            LotActionButton(
                icon = Icons.Outlined.MoveToInbox,
                label = "รับเข้า",
                description = if (isOutOfLot) "สแกนรับสินค้าแบบไม่มีลอต" else "สแกนรับสินค้าเข้าตามลอต",
                color = themeColor,
                onClick = onReceive
            )

            // ซ่อนปุ่ม "ตรวจสอบ" ถ้าเป็นโหมดนอกลอต
            if (!isOutOfLot) {
                Spacer(Modifier.height(20.dp))
                LotActionButton(
                    icon = Icons.Outlined.ChecklistRtl,
                    label = "ตรวจสอบ",
                    description = "ดูรายการที่รับแล้วและของขาด",
                    color = Color(0xFF2196F3),
                    onClick = onCheck
                )
            }

        }
    }
}

@Composable
private fun LotActionButton(
    icon: ImageVector,
    label: String,
    description: String,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = color.copy(alpha = 0.15f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(30.dp))
                }
            }
            Column {
                Text(label, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = color)
                Text(description, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}