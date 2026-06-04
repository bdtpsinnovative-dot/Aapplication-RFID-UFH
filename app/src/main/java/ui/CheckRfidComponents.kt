package ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import data.StockReceivingItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun BarcodeSearchBar(
    query: String,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var scanBuffer by remember { mutableStateOf("") }
    var idleJob by remember { mutableStateOf<Job?>(null) }

    Box(modifier = Modifier.fillMaxWidth()) {
        BasicTextField(
            value = "",
            onValueChange = { v ->
                // หากต้องการเพิ่ม _ และ / เข้าไปด้วย
                val newChars = v.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '/' }
                if (newChars.isEmpty()) return@BasicTextField
                scanBuffer += newChars
                onQueryChange(scanBuffer)
                keyboardController?.hide()
                idleJob?.cancel()
                idleJob = scope.launch {
                    delay(400)
                    scanBuffer = ""
                }
            },
            modifier = Modifier
                .focusRequester(focusRequester)
                .size(1.dp)
                .alpha(0.01f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search
            ),
            keyboardActions = KeyboardActions(
                onSearch = { keyboardController?.hide() }
            )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.5.dp, if (query.isNotBlank()) ColorPrimary.copy(0.5f) else Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                Icons.Default.QrCodeScanner, null,
                tint = if (query.isNotBlank()) ColorPrimary else ColorTextSec,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = if (query.isBlank()) "ยิงบาร์โค้ด / SKU เพื่อค้นหา" else query,
                style = MaterialTheme.typography.bodyMedium,
                color = if (query.isBlank()) ColorTextSec else ColorTextMain,
                fontFamily = if (query.isBlank()) null else FontFamily.Monospace,
                fontWeight = if (query.isBlank()) FontWeight.Normal else FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (query.isNotBlank()) {
                IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, null, tint = ColorTextSec, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun BatchControlRow(
    batches: List<RfidBatch>,
    currentDraftCount: Int,
    onSaveBatch: () -> Unit,
    onDeleteBatch: (Int) -> Unit,
    saving: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF1F5F9))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Inventory2, null, tint = ColorPrimary, modifier = Modifier.size(18.dp))
            Text(
                "จัดการชุดสแกน",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = ColorTextMain,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onSaveBatch,
                enabled = currentDraftCount > 0 && !saving,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ColorPrimary)
            ) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("เก็บเป็นชุด ($currentDraftCount)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }

        if (batches.isEmpty()) {
            Text(
                "ยังไม่มีชุดที่บันทึก — สแกนแล้วกด \"เก็บเป็นชุด\"",
                style = MaterialTheme.typography.labelSmall,
                color = ColorTextSec
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                batches.forEachIndexed { idx, batch ->
                    val tagCount = batch.tags.values.sumOf { it.size }
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = ColorPrimarySoft,
                        border = BorderStroke(1.dp, ColorPrimary.copy(0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Filled.CheckCircle, null, tint = ColorPrimary, modifier = Modifier.size(14.dp))
                            Text(
                                "ชุด ${batch.batchNum} ($tagCount tag)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = ColorPrimary
                            )
                            IconButton(onClick = { onDeleteBatch(idx) }, modifier = Modifier.size(22.dp)) {
                                Icon(Icons.Default.Close, null, tint = Color(0xFFEF4444), modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    loading: Boolean,
    onClearAll: (() -> Unit)? = null
) {
    CenterAlignedTopAppBar(
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = ColorTextMain))
                Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(color = ColorTextSec))
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBackIosNew, contentDescription = "Back", tint = ColorTextMain, modifier = Modifier.size(20.dp))
            }
        },
        actions = {
            if (onClearAll != null) {
                IconButton(onClick = onClearAll) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "ล้าง tag ทั้งหมด", tint = Color(0xFFEF4444))
                }
            }
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp).padding(2.dp), strokeWidth = 2.dp, color = ColorPrimary)
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh", tint = ColorTextMain)
                }
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = ColorBg)
    )
}

@Composable
fun ModernSummaryCard(totalHave: Int, totalNeed: Int, hasDuplicate: Boolean) {
    val progress = if (totalNeed > 0) totalHave.toFloat() / totalNeed.toFloat() else 0f
    val animatedProgress by animateFloatAsState(progress, label = "progress")
    val progressColor = if (hasDuplicate) Color.Red else ColorPrimary

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.shadow(6.dp, RoundedCornerShape(16.dp), ambientColor = Color.Black.copy(0.05f), spotColor = Color.Black.copy(0.05f))
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp)) {
                    CircularProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxSize(),
                        color = progressColor,
                        strokeWidth = 4.dp,
                        trackColor = ColorPrimarySoft,
                        strokeCap = StrokeCap.Round
                    )
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ColorTextMain
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("ภาพรวมการสแกน", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = ColorTextMain)
                    if (hasDuplicate)
                        Text("พบ Tag ซ้ำ! กรุณาตรวจสอบ", style = MaterialTheme.typography.labelSmall, color = Color.Red)
                    else
                        Text("เสร็จแล้ว $totalHave / ทั้งหมด $totalNeed", style = MaterialTheme.typography.labelSmall, color = ColorTextSec)
                }
                Text(
                    "$totalHave/$totalNeed",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (hasDuplicate) Color.Red else ColorPrimary
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)),
                color = progressColor,
                trackColor = ColorPrimarySoft
            )
        }
    }
}

@Composable
fun HardwareStatusChip(message: String, isError: Boolean) {
    val bg = if (isError) Color(0xFFFEF2F2) else Color(0xFFF0FDF4)
    val tint = if (isError) Color(0xFFEF4444) else Color(0xFF15803D)
    val icon = if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Text(message, style = MaterialTheme.typography.labelMedium, color = tint, modifier = Modifier.weight(1f))
    }
}

@Composable
fun ModernItemCard(
    item: StockReceivingItem,
    code: String?,
    qtyText: String,
    have: Int,
    need: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    imageUrl: String? = null
) {
    val isComplete = have >= need && need > 0
    val progress = if (need > 0) (have.toFloat() / need.toFloat()).coerceIn(0f, 1f) else 0f
    val animProgress by animateFloatAsState(progress, label = "itemProgress")
    var showConfirmDelete by remember { mutableStateOf(false) }
    var showImageUrl by remember { mutableStateOf<String?>(null) }

    if (showImageUrl != null) {
        Dialog(
            onDismissRequest = { showImageUrl = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.9f))
                    .clickable { showImageUrl = null },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = showImageUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                IconButton(
                    onClick = { showImageUrl = null },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = if (isComplete) BorderStroke(1.dp, ColorSuccess.copy(alpha = 0.4f)) else null,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(12.dp), ambientColor = Color.Black.copy(0.03f), spotColor = Color.Black.copy(0.03f))
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val fullImageUrl = if (!imageUrl.isNullOrBlank()) {
                    if (imageUrl.startsWith("http")) imageUrl
                    else "https://zexflchjcycxrpjkuews.supabase.co/storage/v1/object/public/product-images/$imageUrl"
                } else null
                if (fullImageUrl != null) {
                    AsyncImage(
                        model = fullImageUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showImageUrl = fullImageUrl },
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFE2E8F0))
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(36.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (isComplete) ColorSuccess
                            else if (have > 0) ColorWarning
                            else ColorPrimary.copy(0.3f)
                        )
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.name ?: "Unknown Product",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = ColorTextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        code ?: "-",
                        style = MaterialTheme.typography.labelSmall,
                        color = ColorTextSec,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when {
                        isComplete -> ColorSuccess
                        have > 0 -> ColorWarning
                        else -> ColorBg
                    }
                ) {
                    Text(
                        "$have/$need",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (have > 0 || isComplete) Color.White else ColorTextSec
                    )
                }
                if (onDelete != null && enabled) {
                    IconButton(onClick = { showConfirmDelete = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Delete, null, tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                    }
                }
            }
            LinearProgressIndicator(
                progress = { animProgress },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = if (isComplete) ColorSuccess else if (have > 0) ColorWarning else ColorPrimary,
                trackColor = ColorBg
            )
        }
    }

    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            icon = { Icon(Icons.Outlined.Delete, null, tint = Color(0xFFEF4444), modifier = Modifier.size(28.dp)) },
            title = { Text("ลบสินค้าออกจากรายการ?", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
            text = {
                Text(
                    "\"${item.name ?: "สินค้า #${item.productId}"}\" จะถูกนำออกจาก session นี้\nข้อมูล Tag ที่ยิงไว้จะถูกล้างด้วย",
                    textAlign = TextAlign.Center,
                    color = ColorTextSec
                )
            },
            confirmButton = {
                Button(
                    onClick = { showConfirmDelete = false; onDelete?.invoke() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) { Text("ลบออก", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDelete = false }) { Text("ยกเลิก") }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
fun ModernBottomBar(
    totalHave: Int,
    totalNeed: Int,
    allFilled: Boolean,
    hasDuplicate: Boolean,
    savingAll: Boolean,
    onSave: () -> Unit
) {
    val enabled = allFilled && !hasDuplicate && !savingAll
    Surface(color = Color.White, shadowElevation = 16.dp, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (allFilled) "ครบถ้วนพร้อมบันทึก" else "กำลังดำเนินการ...",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (allFilled) ColorSuccess else ColorTextSec
                )
                Text(
                    "$totalHave / $totalNeed Tag",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (allFilled) ColorSuccess else ColorTextMain
                )
            }
            Button(
                onClick = onSave,
                enabled = enabled,
                modifier = Modifier.height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ColorPrimary,
                    disabledContainerColor = Color.Gray.copy(0.2f)
                )
            ) {
                if (savingAll) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Save, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("บันทึกเข้าระบบ", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ModernAlertCard(text: String, isError: Boolean) {
    val bg = if (isError) Color(0xFFFEF2F2) else Color(0xFFF0FDF4)
    val contentColor = if (isError) Color(0xFFEF4444) else Color(0xFF15803D)
    val icon = if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = contentColor)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = contentColor, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun EmptyStateView(onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(120.dp).clip(CircleShape).background(ColorPrimarySoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Inventory2, null, modifier = Modifier.size(60.dp), tint = ColorPrimary.copy(alpha = 0.5f))
        }
        Spacer(Modifier.height(24.dp))
        Text("ไม่มีรายการสินค้า", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ColorTextMain)
        Text("ดูเหมือนจะไม่มีรายการที่ต้องทำในขณะนี้", style = MaterialTheme.typography.bodyMedium, color = ColorTextSec)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onRefresh, shape = RoundedCornerShape(12.dp)) { Text("รีเฟรชข้อมูล") }
    }
}
