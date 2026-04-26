package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.AppError
import data.AuthManager
import data.LotSummary
import data.SessionStore
import data.StockLotApi
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LotSelectScreen(
    onBack: () -> Unit,
    onSelectLot: (lotId: Long, lotCode: String) -> Unit,
    onNoLot: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var lots by remember { mutableStateOf<List<LotSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadLots() {
        scope.launch {
            loading = true
            error = null
            try {
                val branchId = SessionStore.getBranchId(ctx)
                    ?: throw IllegalStateException("ไม่พบ branchId ในระบบ กรุณา login ใหม่")
                val token = AuthManager.getValidAccessToken(ctx)
                    ?: throw IllegalStateException("Session หมดอายุ กรุณา login ใหม่")
                lots = StockLotApi.fetchActiveLots(branchId, token)
            } catch (e: Exception) {
                error = AppError.resolve(e)
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadLots() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("เลือกลอตรับสินค้า") },
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
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            // ปุ่มรับเข้าแบบไม่มีลอต
            Button(
                onClick = onNoLot,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
            ) {
                Icon(Icons.Outlined.Inbox, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("รับเข้าแบบไม่มีลอต", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(20.dp))

            Text(
                "ลอตที่รอรับ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(10.dp))

            when {
                loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("กำลังโหลด...", color = Color.Gray)
                        }
                    }
                }

                error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(error!!, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { loadLots() }) { Text("ลองใหม่") }
                        }
                    }
                }

                lots.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.LocalShipping,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = Color.LightGray
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("ไม่มีลอตที่รอรับสินค้า", color = Color.Gray)
                        }
                    }
                }

                else -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(lots) { lot ->
                            LotCard(lot = lot, onClick = { onSelectLot(lot.id, lot.lotCode) })
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LotCard(lot: LotSummary, onClick: () -> Unit) {
    val statusColor = if (lot.status == "PARTIAL") Color(0xFFFF9800) else Color(0xFF4CAF50)
    val statusLabel = if (lot.status == "PARTIAL") "รับบางส่วน" else "รอรับ"

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = lot.lotCode,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = statusColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = statusLabel,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    InfoChip(label = "รายการ", value = "${lot.itemCount}")
                    InfoChip(label = "คาดหวัง", value = "${lot.expectedTotal}")
                    InfoChip(label = "รับแล้ว", value = "${lot.receivedTotal}")
                }
            }

            Icon(
                Icons.Outlined.ArrowForwardIos,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color.LightGray
            )
        }
    }
}

@Composable
private fun InfoChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(label, color = Color.Gray, fontSize = 11.sp)
    }
}
