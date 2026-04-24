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

    // ---- ดึงลอตที่รอรับ (SENT / PARTIAL) ของสาขานี้ ----
    suspend fun fetchActiveLots(branchId: Long, token: String): List<LotSummary> =
        withContext(Dispatchers.IO) {
            val url = "${SupabaseConfig.URL}/rest/v1/stock_lots" +
                "?branch_id=eq.$branchId" +
                "&status=in.(SENT,PARTIAL)" +
                "&select=id,lot_code,status,sent_at,stock_lot_items(expected_qty,received_qty)" +
                "&order=sent_at.desc"

            val req = Request.Builder().url(url).get().apply {
                headers(token).forEach { (k, v) -> addHeader(k, v) }
            }.build()

            val lots = mutableListOf<LotSummary>()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext emptyList()
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
            client.newCall(req).execute().use { }
        }
}
