package ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBackIosNew
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RfidModePickerScreen(
    onBack: () -> Unit,
    onSelectNoLot: () -> Unit,
    onSelectLot: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("เช็ค RFID", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBackIosNew, null, modifier = Modifier.size(20.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ColorBg)
            )
        },
        containerColor = ColorBg
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
                "เลือกโหมดการติก RFID",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = ColorTextMain
            )
            Spacer(Modifier.height(8.dp))
            Text("จะติก tag แบบไหน?", style = MaterialTheme.typography.bodyMedium, color = ColorTextSec)
            Spacer(Modifier.height(40.dp))

            Card(
                onClick = onSelectLot,
                modifier = Modifier.fillMaxWidth().height(110.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF4CAF50).copy(0.2f),
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Inventory2, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(30.dp))
                        }
                    }
                    Column {
                        Text("ติก RFID แบบมีลอต", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF2E7D32))
                        Text("เลือกลอตที่ต้องการติก tag", style = MaterialTheme.typography.bodySmall, color = Color(0xFF388E3C))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(
                onClick = onSelectNoLot,
                modifier = Modifier.fillMaxWidth().height(110.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFFF9800).copy(0.2f),
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.QrCodeScanner, null, tint = Color(0xFFE65100), modifier = Modifier.size(30.dp))
                        }
                    }
                    Column {
                        Text("ติก RFID ไม่มีลอต", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFFE65100))
                        Text("สินค้าที่ไม่ได้อยู่ในลอต", style = MaterialTheme.typography.bodySmall, color = Color(0xFFF57C00))
                    }
                }
            }
        }
    }
}
