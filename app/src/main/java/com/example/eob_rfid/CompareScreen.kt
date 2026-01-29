package com.example.eob_rfid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons as MIcons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import kotlin.math.abs

/* ----------------------------- Models ----------------------------- */

private data class ReaderRow(val productId: Long, val qty: Long)
private data class StockRow(val productId: Long, val qty: Double)

/** ✅ ใช้ “ราคาขาย” */
private data class CompareProductRow(
    val id: Long,
    val name: String,
    val unit: String?,
    val imageUrl: String?,
    val price: Double
)

private data class CompareRow(
    val productId: Long,
    val name: String,
    val unit: String?,
    val imageUrl: String?,
    val price: Double,
    val systemQty: Double,
    val countedQty: Long,
    val diff: Double,
    val valueDiff: Double,
    val valueDiffAbs: Double
)

private enum class BannerState { NONE, OK, WARN, ERROR }

/* ----------------------------- Screen ----------------------------- */
/**
 * ✅ ตั้งชื่อ V2 เพื่อ “ตัดปัญหา Conflicting overloads ของ CompareStockScreen”
 * แล้วไปแก้ AppNav ให้เรียก CompareStockScreenV2 แทน
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareStockScreenV2(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }

    var bannerText by remember { mutableStateOf<String?>(null) }
    var bannerState by remember { mutableStateOf(BannerState.NONE) }

    var rows by remember { mutableStateOf<List<CompareRow>>(emptyList()) }

    // ✅ ปุ่มเดียว สลับ แสดงเฉพาะที่ต่าง / แสดงทั้งหมด
    var showOnlyDiff by remember { mutableStateOf(true) }

    val headerBrush = remember {
        Brush.linearGradient(listOf(Color(0xFF6A11CB), Color(0xFF2575FC)))
    }

    fun setBanner(state: BannerState, text: String?) {
        bannerState = state
        bannerText = text
    }

    val diffRows by remember(rows) {
        derivedStateOf { rows.filter { abs(it.diff) > 0.0005 } }
    }

    // ✅ 4 ช่องสรุป
    val diffCount by remember(diffRows) { derivedStateOf { diffRows.size } } // “จำนวนรายการที่ต่าง”
    val valueDiffNet by remember(diffRows) { derivedStateOf { diffRows.sumOf { it.valueDiff } } } // +/-
    val damageValue by remember(diffRows) {
        derivedStateOf { diffRows.filter { it.diff < -0.0005 }.sumOf { (-it.diff) * it.price } }
    }
    val overValue by remember(diffRows) {
        derivedStateOf { diffRows.filter { it.diff > 0.0005 }.sumOf { it.diff * it.price } }
    }

    fun load() {
        scope.launch {
            loading = true
            setBanner(BannerState.NONE, null)
            try {
                val reader = SupabaseCompareApi.fetchReaderStock(SessionManager.accessToken)
                val readerMap = reader.associate { it.productId to it.qty }

                val stock = SupabaseCompareApi.fetchSystemStock(SessionManager.accessToken)
                val stockMap = stock.associate { it.productId to it.qty }

                val ids = (readerMap.keys + stockMap.keys).distinct().sorted()
                if (ids.isEmpty()) {
                    rows = emptyList()
                    setBanner(BannerState.WARN, "ยังไม่มีข้อมูลสำหรับเทียบ")
                    return@launch
                }

                val prodList = SupabaseCompareApi.fetchProductsByIds(ids, SessionManager.accessToken)
                val prodMap = prodList.associateBy { it.id }

                val built = ids.map { pid ->
                    val p = prodMap[pid]
                    val name = p?.name ?: "สินค้า #$pid"
                    val unit = p?.unit
                    val img = p?.imageUrl
                    val price = p?.price ?: 0.0

                    val systemQty = stockMap[pid] ?: 0.0
                    val countedQty = readerMap[pid] ?: 0L

                    val diff = countedQty.toDouble() - systemQty
                    val valueDiff = diff * price
                    val valueDiffAbs = abs(valueDiff)

                    CompareRow(
                        productId = pid,
                        name = name,
                        unit = unit,
                        imageUrl = img,
                        price = price,
                        systemQty = systemQty,
                        countedQty = countedQty,
                        diff = diff,
                        valueDiff = valueDiff,
                        valueDiffAbs = valueDiffAbs
                    )
                }.sortedWith(
                    compareByDescending<CompareRow> { abs(it.diff) > 0.0005 }
                        .thenByDescending { it.valueDiffAbs }
                )

                rows = built
                setBanner(BannerState.NONE, null)

            } catch (e: Exception) {
                setBanner(BannerState.ERROR, e.message ?: "โหลดข้อมูลไม่สำเร็จ")
            } finally {
                loading = false
            }
        }
    }

    fun confirmApply() {
        scope.launch {
            confirming = true
            setBanner(BannerState.NONE, null)
            try {
                val nowIso = Instant.now().toString()

                SupabaseCompareApi.resetAllStockToZero(nowIso, SessionManager.accessToken)

                val reader = SupabaseCompareApi.fetchReaderStock(SessionManager.accessToken)
                val readerMap = reader.associate { it.productId to it.qty }

                val setItems = readerMap.entries.map { (pid, qty) ->
                    StockUpsert(id = pid, qty = qty.toDouble(), updatedAt = nowIso)
                }
                SupabaseCompareApi.upsertStock(setItems, SessionManager.accessToken)

                val moveSessionId = System.currentTimeMillis()
                val movements = rows
                    .filter { abs(it.diff) > 0.0005 }
                    .map { r ->
                        StockMovementInsert(
                            productIdText = r.productId.toString(),
                            productIdBigint = r.productId,
                            type = "COMPARE_ADJUST",
                            qty = r.diff,
                            note = "ปรับสต๊อกจากการเทียบ",
                            refType = "READER_STOCK_COMPARE",
                            refIdBigint = moveSessionId
                        )
                    }

                if (movements.isNotEmpty()) {
                    SupabaseCompareApi.insertMovements(movements, SessionManager.accessToken)
                }

                SupabaseCompareApi.clearReaderStock(SessionManager.accessToken)

                setBanner(BannerState.OK, "ยืนยันสำเร็จ ✅ ปรับสต๊อกแล้ว และล้างข้อมูลนับแล้ว")
                load()

            } catch (e: Exception) {
                setBanner(BannerState.ERROR, e.message ?: "ยืนยันไม่สำเร็จ")
            } finally {
                confirming = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("เทียบสต๊อก") },
                navigationIcon = { TextButton(onClick = onBack) { Text("ย้อนกลับ") } },
                actions = {
                    IconButton(onClick = { if (!loading && !confirming) load() }) {
                        Icon(MIcons.Filled.Refresh, contentDescription = "รีเฟรช")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = showOnlyDiff,
                        onClick = { showOnlyDiff = !showOnlyDiff },
                        label = { Text(if (showOnlyDiff) "แสดงเฉพาะที่ต่าง" else "แสดงทั้งหมด") }
                    )

                    Spacer(Modifier.weight(1f))

                    Button(
                        onClick = { if (!confirming) confirmApply() },
                        enabled = !confirming && !loading && diffRows.isNotEmpty(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(46.dp)
                    ) {
                        if (confirming) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("กำลังยืนยัน...")
                        } else {
                            Text("ยืนยันการเทียบ", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SummaryHeader4(
                headerBrush = headerBrush,
                diffCount = diffCount,
                valueDiffNet = valueDiffNet,
                damageValue = damageValue,
                overValue = overValue
            )

            if (bannerText != null) {
                val (bg, fg, icon) = when (bannerState) {
                    BannerState.OK -> Triple(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer,
                        MIcons.Outlined.CheckCircle
                    )
                    BannerState.WARN -> Triple(
                        MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.colorScheme.onTertiaryContainer,
                        MIcons.Outlined.ErrorOutline
                    )
                    BannerState.ERROR -> Triple(
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.colorScheme.onErrorContainer,
                        MIcons.Outlined.ErrorOutline
                    )
                    else -> Triple(
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                        MIcons.Outlined.ErrorOutline
                    )
                }

                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = bg),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(icon, contentDescription = null, tint = fg)
                        Spacer(Modifier.width(10.dp))
                        Text(bannerText!!, color = fg, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (loading) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(10.dp))
                    Text("กำลังโหลดข้อมูล...")
                }
            } else {
                val shown = if (showOnlyDiff) diffRows else rows
                if (shown.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("ไม่มีรายการให้แสดง")
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(shown, key = { _, it -> it.productId }) { _, r ->
                            CompareItem(r)
                        }
                    }
                }
            }
        }
    }
}

/* ----------------------------- UI ----------------------------- */

@Composable
private fun SummaryHeader4(
    headerBrush: Brush,
    diffCount: Int,
    valueDiffNet: Double,
    damageValue: Double,
    overValue: Double
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(headerBrush, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).background(Color.White.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(MIcons.Outlined.Inventory2, contentDescription = null, tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "สรุปผลต่าง",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryChip("สินค้าที่มีผลต่าง", diffCount.toString(), Modifier.weight(1f))
                SummaryChip("มูลค่าความต่าง", fmtSignedMoney(valueDiffNet), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryChip("มูลค่าความเสียหาย", "฿${fmtMoney(damageValue)}", Modifier.weight(1f))
                SummaryChip("มูลค่าสินค้าเกิน", "฿${fmtMoney(overValue)}", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SummaryChip(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = 0.16f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, color = Color.White.copy(alpha = 0.92f), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun ThumbImage(imageUrl: String?, size: Dp = 64.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl.isNullOrBlank()) {
            Icon(MIcons.Outlined.Image, contentDescription = null)
        } else {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop // ✅ รูปเท่ากันทุกใบ
            )
        }
    }
}

@Composable
private fun CompareItem(r: CompareRow) {
    val isDiff = abs(r.diff) > 0.0005
    val badgeColor = if (isDiff) Color(0xFFFFE0B2) else Color(0xFFE8F5E9)
    val badgeTextColor = if (isDiff) Color(0xFF8A4B00) else Color(0xFF1B5E20)

    val diffQtyText = fmtSignedQty(r.diff)              // ✅ + / -
    val diffValueText = fmtSignedMoney(r.valueDiff)     // ✅ +฿ / -฿

    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                ThumbImage(r.imageUrl, 64.dp)
                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        r.name,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "ID: ${r.productId}  •  ราคาขาย ฿${fmtMoney(r.price)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Box(
                    Modifier
                        .background(badgeColor, RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        if (isDiff) "ต่าง" else "ตรง",
                        color = badgeTextColor,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Divider()

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("ระบบ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${fmtQty(r.systemQty)} ${r.unit ?: ""}".trim(), fontWeight = FontWeight.SemiBold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("นับได้", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${r.countedQty} ${r.unit ?: ""}".trim(), fontWeight = FontWeight.SemiBold)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("ผลต่าง", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(diffQtyText, fontWeight = FontWeight.ExtraBold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("มูลค่าความต่าง", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(diffValueText, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

/* ----------------------------- Helpers ----------------------------- */

private fun fmtQty(v: Double): String {
    val s = "%.3f".format(v)
    return s.trimEnd('0').trimEnd('.')
}

private fun fmtMoney(v: Double): String = "%.2f".format(v)

private fun fmtSignedQty(v: Double): String = when {
    v > 0.0005 -> "+${fmtQty(v)}"
    v < -0.0005 -> "-${fmtQty(abs(v))}"
    else -> "0"
}

private fun fmtSignedMoney(v: Double): String {
    val a = abs(v)
    return when {
        v > 0.005 -> "+฿${fmtMoney(a)}"
        v < -0.005 -> "-฿${fmtMoney(a)}"
        else -> "฿0.00"
    }
}

/* ----------------------------- API ----------------------------- */

private data class StockUpsert(val id: Long, val qty: Double, val updatedAt: String)

private data class StockMovementInsert(
    val productIdText: String,
    val productIdBigint: Long,
    val type: String,
    val qty: Double,
    val note: String,
    val refType: String,
    val refIdBigint: Long
)

private object SupabaseCompareApi {
    private val client = OkHttpClient()
    private val media = "application/json; charset=utf-8".toMediaType()

    private fun bearer(accessToken: String?) =
        accessToken?.takeIf { it.isNotBlank() } ?: SupabaseConfig.ANON_KEY

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun jsonNumToDouble(v: Any?): Double = when (v) {
        null -> 0.0
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    suspend fun fetchReaderStock(accessToken: String?): List<ReaderRow> {
        val url = "${SupabaseConfig.URL}/rest/v1/reader_stock?select=product_id,qty&order=product_id.asc"
        val req = Request.Builder()
            .url(url)
            .get()
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
            .addHeader("Accept", "application/json")
            .build()

        val (ok, code, raw) = withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { res ->
                Triple(res.isSuccessful, res.code, res.body?.string().orEmpty())
            }
        }
        if (!ok) throw IllegalStateException("fetch reader_stock failed ($code): $raw")

        val arr = JSONArray(raw)
        val out = ArrayList<ReaderRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pid = o.optLong("product_id", -1L)
            val qty = o.optLong("qty", 0L)
            if (pid > 0) out.add(ReaderRow(pid, qty))
        }
        return out
    }

    suspend fun fetchSystemStock(accessToken: String?): List<StockRow> {
        val url = "${SupabaseConfig.URL}/rest/v1/stock?select=id,qty&order=id.asc"
        val req = Request.Builder()
            .url(url)
            .get()
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
            .addHeader("Accept", "application/json")
            .build()

        val (ok, code, raw) = withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { res ->
                Triple(res.isSuccessful, res.code, res.body?.string().orEmpty())
            }
        }
        if (!ok) throw IllegalStateException("fetch stock failed ($code): $raw")

        val arr = JSONArray(raw)
        val out = ArrayList<StockRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pid = o.optLong("id", -1L)
            val qty = jsonNumToDouble(o.opt("qty"))
            if (pid > 0) out.add(StockRow(pid, qty))
        }
        return out
    }

    // ✅ เผื่อชื่อคอลัมน์ราคาขายไม่ตรงกัน: จะลอง price ก่อน แล้วค่อยลองชื่ออื่น
    private val priceFields = listOf("price", "sell_price", "sale_price", "selling_price", "retail_price")

    suspend fun fetchProductsByIds(ids: List<Long>, accessToken: String?): List<CompareProductRow> {
        if (ids.isEmpty()) return emptyList()
        val chunks = ids.distinct().chunked(120)
        val out = ArrayList<CompareProductRow>()

        var lastErr: String? = null
        for (priceField in priceFields) {
            try {
                out.clear()
                for (chunk in chunks) {
                    val inList = chunk.joinToString(",") { it.toString() }
                    val filter = "in.($inList)"
                    val url =
                        "${SupabaseConfig.URL}/rest/v1/products" +
                                "?select=id,name,unit,image_url,$priceField" +
                                "&id=${enc(filter)}"

                    val req = Request.Builder()
                        .url(url)
                        .get()
                        .addHeader("apikey", SupabaseConfig.ANON_KEY)
                        .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
                        .addHeader("Accept", "application/json")
                        .build()

                    val (ok, code, raw) = withContext(Dispatchers.IO) {
                        client.newCall(req).execute().use { res ->
                            Triple(res.isSuccessful, res.code, res.body?.string().orEmpty())
                        }
                    }
                    if (!ok) throw IllegalStateException("fetch products failed ($code): $raw")

                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val id = o.optLong("id", -1L)
                        val name = o.optString("name", "สินค้า #$id")
                        val unit = o.optString("unit", null)
                        val img = o.optString("image_url", null)
                        val price = jsonNumToDouble(o.opt(priceField))
                        if (id > 0) out.add(CompareProductRow(id, name, unit, img, price))
                    }
                }
                return out.toList()
            } catch (e: Exception) {
                lastErr = e.message
            }
        }

        throw IllegalStateException(
            "อ่านราคาขายจาก products ไม่สำเร็จ (ลองแล้ว: ${priceFields.joinToString(", ")})\n$lastErr"
        )
    }

    suspend fun resetAllStockToZero(nowIso: String, accessToken: String?) {
        val body = JSONObject()
            .put("qty", 0)
            .put("updated_at", nowIso)
            .toString()
            .toRequestBody(media)

        val url = "${SupabaseConfig.URL}/rest/v1/stock?id=gt.0"
        val req = Request.Builder()
            .url(url)
            .patch(body)
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal")
            .build()

        val (ok, code, raw) = withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { res ->
                Triple(res.isSuccessful, res.code, res.body?.string().orEmpty())
            }
        }
        if (!ok) throw IllegalStateException("reset stock failed ($code): $raw")
    }

    suspend fun upsertStock(items: List<StockUpsert>, accessToken: String?) {
        if (items.isEmpty()) return

        val arr = JSONArray()
        for (it in items) {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("qty", it.qty)
                    .put("updated_at", it.updatedAt)
            )
        }

        val body = arr.toString().toRequestBody(media)
        val url = "${SupabaseConfig.URL}/rest/v1/stock?on_conflict=id"

        val req = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
            .build()

        val (ok, code, raw) = withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { res ->
                Triple(res.isSuccessful, res.code, res.body?.string().orEmpty())
            }
        }
        if (!ok) throw IllegalStateException("upsert stock failed ($code): $raw")
    }

    suspend fun insertMovements(items: List<StockMovementInsert>, accessToken: String?) {
        if (items.isEmpty()) return

        val arr = JSONArray()
        for (m in items) {
            arr.put(
                JSONObject()
                    .put("product_id", m.productIdText)
                    .put("product_id_bigint", m.productIdBigint)
                    .put("type", m.type)
                    .put("qty", m.qty)
                    .put("note", m.note)
                    .put("ref_type", m.refType)
                    .put("ref_id_bigint", m.refIdBigint)
            )
        }

        val body = arr.toString().toRequestBody(media)
        val url = "${SupabaseConfig.URL}/rest/v1/stock_movements"

        val req = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal")
            .build()

        val (ok, code, raw) = withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { res ->
                Triple(res.isSuccessful, res.code, res.body?.string().orEmpty())
            }
        }
        if (!ok) throw IllegalStateException("insert movements failed ($code): $raw")
    }

    suspend fun clearReaderStock(accessToken: String?) {
        val url = "${SupabaseConfig.URL}/rest/v1/reader_stock?product_id=gt.0"
        val req = Request.Builder()
            .url(url)
            .delete()
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
            .addHeader("Prefer", "return=minimal")
            .build()

        val (ok, code, raw) = withContext(Dispatchers.IO) {
            client.newCall(req).execute().use { res ->
                Triple(res.isSuccessful, res.code, res.body?.string().orEmpty())
            }
        }
        if (!ok) throw IllegalStateException("clear reader_stock failed ($code): $raw")
    }
}
