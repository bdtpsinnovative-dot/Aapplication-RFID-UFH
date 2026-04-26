package data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class LotSummary(
    val id: Long,
    val lotCode: String,
    val status: String,
    val sentAt: String?,
    val itemCount: Int,
    val expectedTotal: Int,
    val receivedTotal: Int
)

data class LotItemDetail(
    val id: Long,
    val productId: Long,
    val productName: String,
    val sku: String?,
    val barcode: String?,
    val imageUrl: String?,
    val expectedQty: Int,
    val receivedQty: Int   // ที่ DB บันทึกไว้แล้ว (ก่อน session นี้)
)

object StockLotApi {
    private val client = OkHttpClient()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun headers(token: String) = listOf(
        "apikey" to SupabaseConfig.ANON_KEY,
        "Authorization" to "Bearer $token",
        "Accept" to "application/json"
    )

    // ---- ดึงลอตของสาขานี้ กรองตาม statuses ที่ระบุ ----
    suspend fun fetchActiveLots(
        branchId: Long,
        token: String,
        statuses: List<String> = listOf("SENT", "PARTIAL")
    ): List<LotSummary> =
        withContext(Dispatchers.IO) {
            val statusFilter = statuses.joinToString(",")
            val url = "${SupabaseConfig.URL}/rest/v1/stock_lots" +
                "?branch_id=eq.$branchId" +
                "&status=in.($statusFilter)" +
                "&select=id,lot_code,status,sent_at,stock_lot_items(expected_qty,received_qty)" +
                "&order=sent_at.desc"

            val req = Request.Builder().url(url).get().apply {
                headers(token).forEach { (k, v) -> addHeader(k, v) }
            }.build()

            val lots = mutableListOf<LotSummary>()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    val body = res.body?.string().orEmpty()
                    throw IllegalStateException("HTTP ${res.code}: $body")
                }
                val arr = JSONArray(res.body?.string().orEmpty())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val items = o.optJSONArray("stock_lot_items") ?: JSONArray()
                    var expTotal = 0
                    var recTotal = 0
                    for (j in 0 until items.length()) {
                        val item = items.getJSONObject(j)
                        expTotal += item.optInt("expected_qty", 0)
                        recTotal += item.optInt("received_qty", 0)
                    }
                    lots.add(
                        LotSummary(
                            id = o.getLong("id"),
                            lotCode = o.optString("lot_code", "—"),
                            status = o.optString("status", "SENT"),
                            sentAt = o.optString("sent_at").ifBlank { null },
                            itemCount = items.length(),
                            expectedTotal = expTotal,
                            receivedTotal = recTotal
                        )
                    )
                }
            }
            lots
        }

    // ---- ดึงรายการสินค้าในลอต ----
    suspend fun fetchLotItems(lotId: Long, token: String): List<LotItemDetail> =
        withContext(Dispatchers.IO) {
            val url = "${SupabaseConfig.URL}/rest/v1/stock_lot_items" +
                "?lot_id=eq.$lotId" +
                "&select=id,product_id,expected_qty,received_qty,products(name,sku,barcode,image_url)" +
                "&order=id.asc"

            val req = Request.Builder().url(url).get().apply {
                headers(token).forEach { (k, v) -> addHeader(k, v) }
            }.build()

            val items = mutableListOf<LotItemDetail>()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext emptyList()
                val arr = JSONArray(res.body?.string().orEmpty())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val p = o.optJSONObject("products")
                    items.add(
                        LotItemDetail(
                            id = o.getLong("id"),
                            productId = o.getLong("product_id"),
                            productName = p?.optString("name") ?: "สินค้า",
                            sku = p?.optString("sku")?.ifBlank { null },
                            barcode = p?.optString("barcode")?.ifBlank { null },
                            imageUrl = p?.optString("image_url")?.ifBlank { null },
                            expectedQty = o.optInt("expected_qty", 0),
                            receivedQty = o.optInt("received_qty", 0)
                        )
                    )
                }
            }
            items
        }

    // ---- อัปเดต received_qty ของแต่ละ lot_item ----
    suspend fun updateLotItemsReceived(
        updates: List<Pair<Long, Int>>,  // (lot_item_id, new_received_qty)
        token: String
    ) = withContext(Dispatchers.IO) {
        for ((itemId, newQty) in updates) {
            val url = "${SupabaseConfig.URL}/rest/v1/stock_lot_items?id=eq.$itemId"
            val body = JSONObject().put("received_qty", newQty).toString()
                .toRequestBody(jsonMedia)
            val req = Request.Builder().url(url).patch(body).apply {
                headers(token).forEach { (k, v) -> addHeader(k, v) }
                addHeader("Content-Type", "application/json")
            }.build()
            client.newCall(req).execute().use { /* ignore individual errors */ }
        }
    }

    // ---- สร้าง lot อัตโนมัติสำหรับการรับเข้าแบบไม่มีลอต ----
    suspend fun createAutoLot(branchId: Long, userId: String, userName: String, token: String): Long =
        withContext(Dispatchers.IO) {
            val ts = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val lotCode = "WALKIN-B${branchId}-$ts"

            val body = JSONObject()
                .put("lot_code", lotCode)
                .put("branch_id", branchId)
                .put("status", "SUCCESS")
                .put("note", "รับเข้าแบบไม่มีลอต (Auto)")
                .put("created_by", userId)
                .put("created_by_name", userName)

            val req = Request.Builder()
                .url("${SupabaseConfig.URL}/rest/v1/stock_lots")
                .post(body.toString().toRequestBody(jsonMedia))
                .apply { headers(token).forEach { (k, v) -> addHeader(k, v) } }
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            client.newCall(req).execute().use { res ->
                val raw = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw IllegalStateException("สร้าง lot อัตโนมัติไม่สำเร็จ ($lotCode): $raw")
                JSONArray(raw).getJSONObject(0).getLong("id")
            }
        }

    // ---- สร้าง lot items (ใช้ตอนสร้าง auto lot) ----
    suspend fun createLotItems(lotId: Long, items: Map<Long, Int>, token: String) =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext
            val arr = JSONArray()
            for ((productId, qty) in items) {
                arr.put(
                    JSONObject()
                        .put("lot_id", lotId)
                        .put("product_id", productId)
                        .put("expected_qty", 0)   // walk-in ไม่มี expected
                        .put("received_qty", qty)
                )
            }
            val req = Request.Builder()
                .url("${SupabaseConfig.URL}/rest/v1/stock_lot_items")
                .post(arr.toString().toRequestBody(jsonMedia))
                .apply { headers(token).forEach { (k, v) -> addHeader(k, v) } }
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    val raw = res.body?.string().orEmpty()
                    throw IllegalStateException("สร้าง lot items ไม่สำเร็จ: $raw")
                }
            }
        }

    // ---- อัปเดต lot status ----
    suspend fun updateLotStatus(lotId: Long, newStatus: String, token: String) =
        withContext(Dispatchers.IO) {
            val url = "${SupabaseConfig.URL}/rest/v1/stock_lots?id=eq.$lotId"
            val body = JSONObject().put("status", newStatus).toString()
                .toRequestBody(jsonMedia)
            val req = Request.Builder().url(url).patch(body).apply {
                headers(token).forEach { (k, v) -> addHeader(k, v) }
                addHeader("Content-Type", "application/json")
            }.build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    val err = res.body?.string().orEmpty()
                    throw IllegalStateException("อัปเดต lot status ไม่สำเร็จ (${res.code}): $err")
                }
            }
        }
}
