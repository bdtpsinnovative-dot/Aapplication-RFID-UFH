package ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

data class CompareItem(val code: String, val a: Int, val b: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareStockScreen(onBack: () -> Unit) {
    var codeA by remember { mutableStateOf("") }
    var qtyA by remember { mutableStateOf("1") }
    var codeB by remember { mutableStateOf("") }
    var qtyB by remember { mutableStateOf("1") }

    val mapA = remember { mutableStateMapOf<String, Int>() }
    val mapB = remember { mutableStateMapOf<String, Int>() }
    var showResult by remember { mutableStateOf(false) }

    fun add(map: MutableMap<String, Int>, code: String, qty: Int) {
        val c = code.trim()
        if (c.isBlank() || qty <= 0) return
        map[c] = (map[c] ?: 0) + qty
    }

    val results = remember(mapA.size, mapB.size, showResult) {
        if (!showResult) emptyList()
        else {
            val keys = (mapA.keys + mapB.keys).toSet()
            keys.map { k -> CompareItem(k, mapA[k] ?: 0, mapB[k] ?: 0) }
                .sortedBy { it.code }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("เทียบสต๊อก") },
                navigationIcon = { TextButton(onClick = onBack) { Text("ย้อนกลับ") } }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("ชุด A", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = codeA, onValueChange = { codeA = it },
                            label = { Text("รหัสสินค้า A") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = qtyA,
                            onValueChange = { qtyA = it.filter { ch -> ch.isDigit() }.ifBlank { "" } },
                            label = { Text("จำนวน A") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        FilledTonalButton(
                            onClick = {
                                add(mapA, codeA, qtyA.toIntOrNull() ?: 0)
                                codeA = ""; qtyA = "1"; showResult = false
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("เพิ่มเข้าชุด A") }

                        Divider()

                        Text("ชุด B", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = codeB, onValueChange = { codeB = it },
                            label = { Text("รหัสสินค้า B") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = qtyB,
                            onValueChange = { qtyB = it.filter { ch -> ch.isDigit() }.ifBlank { "" } },
                            label = { Text("จำนวน B") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        FilledTonalButton(
                            onClick = {
                                add(mapB, codeB, qtyB.toIntOrNull() ?: 0)
                                codeB = ""; qtyB = "1"; showResult = false
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("เพิ่มเข้าชุด B") }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { showResult = true },
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape = RoundedCornerShape(14.dp),
                                enabled = mapA.isNotEmpty() || mapB.isNotEmpty()
                            ) { Text("เทียบ") }

                            OutlinedButton(
                                onClick = {
                                    mapA.clear(); mapB.clear(); showResult = false
                                },
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) { Text("ล้างทั้งหมด") }
                        }
                    }
                }
            }

            if (showResult) {
                item {
                    Text("ผลการเทียบ", style = MaterialTheme.typography.titleMedium)
                }

                items(results) { r ->
                    val diff = r.a - r.b
                    Card {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(r.code, style = MaterialTheme.typography.titleMedium)
                            Text("A: ${r.a}  |  B: ${r.b}")
                            Text(
                                "ต่างกัน: $diff",
                                color = if (diff == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}
