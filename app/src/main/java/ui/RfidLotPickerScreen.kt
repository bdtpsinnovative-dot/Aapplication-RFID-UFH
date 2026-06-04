package ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBackIosNew
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import data.LotSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RfidLotPickerScreen(
    onBack: () -> Unit,
    loading: Boolean,
    error: String?,
    lots: List<LotSummary>,
    onSelectLot: (LotSummary) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("เลือกลอต", fontWeight = FontWeight.Bold) },
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
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ColorPrimary)
                }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
                lots.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("ไม่มีลอตที่รอดำเนินการ", color = ColorTextSec)
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(lots) { lot ->
                        Card(
                            onClick = { onSelectLot(lot) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(2.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = ColorPrimarySoft,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Outlined.Inventory2, null, tint = ColorPrimary, modifier = Modifier.size(24.dp))
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(lot.lotCode, fontWeight = FontWeight.Bold, color = ColorTextMain)
                                    Text(
                                        "${lot.itemCount} รายการ | รับแล้ว ${lot.receivedTotal}/${lot.expectedTotal}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ColorTextSec
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (lot.status == "PARTIAL") ColorWarningSoft else ColorSuccessSoft
                                ) {
                                    Text(
                                        lot.status,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (lot.status == "PARTIAL") ColorWarning else ColorSuccess
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
