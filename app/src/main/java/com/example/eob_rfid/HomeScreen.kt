package com.example.eob_rfid

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(onGoScan: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth().widthIn(max = 420.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("EOB_RFID", style = MaterialTheme.typography.titleLarge)
                Text("เลือกเมนู", style = MaterialTheme.typography.bodyMedium)

                Button(onClick = onGoScan, modifier = Modifier.fillMaxWidth()) {
                    Text("ไปหน้าสแกน RFID (ทดลอง)")
                }

                OutlinedButton(onClick = { /* ไว้เพิ่มทีหลัง */ }, modifier = Modifier.fillMaxWidth()) {
                    Text("ตั้งค่า (ยังไม่ทำ)")
                }

                OutlinedButton(onClick = { /* ไว้เพิ่มทีหลัง */ }, modifier = Modifier.fillMaxWidth()) {
                    Text("ประวัติการสแกน (ยังไม่ทำ)")
                }
            }
        }
    }
}
