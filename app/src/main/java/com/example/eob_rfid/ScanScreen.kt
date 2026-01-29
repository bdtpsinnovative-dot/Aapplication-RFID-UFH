package com.example.eob_rfid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(onBack: () -> Unit) {
    var last by remember { mutableStateOf("-") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("สแกน RFID") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("ย้อนกลับ") }
                }
            )
        }
    ) { pad ->
        Box(
            Modifier.fillMaxSize().padding(pad).padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(Modifier.fillMaxWidth().widthIn(max = 420.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("สถานะ: รออุปกรณ์/รอข้อมูล", style = MaterialTheme.typography.bodyMedium)
                    Divider()
                    Text("EPC ล่าสุด:", style = MaterialTheme.typography.labelLarge)
                    Text(last, style = MaterialTheme.typography.titleMedium)

                    Button(
                        onClick = { last = "ทดสอบ-1234567890" },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("กดทดสอบ (จำลองการสแกน)") }
                }
            }
        }
    }
}
