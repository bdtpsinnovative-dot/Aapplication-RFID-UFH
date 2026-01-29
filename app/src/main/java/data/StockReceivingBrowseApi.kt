package data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.Locale

data class StockReceivingItem(
    val productId: Long,
    val qty: Double,
    val updatedAt: String?,
    val name: String?,
    val barcode: String?,
    val sku: String?
)

object StockReceivingBrowseApi {
    private val client = OkHttpClient()

    private fun anyToDouble(v: Any?): Double =
        when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }

    private suspend fun fetchStockReceiving(accessToken: String?): List<Triple<Long, Double, String?>> =
        withContext(Dispatchers.IO) {

            val url = "${SupabaseConfig.URL}/rest/v1/stock_receiving"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("select", "product_id,qty,updated_at")
                .addQueryParameter("order", "updated_at.desc")
                .build()

            val bearer = accessToken?.takeIf { it.isNotBlank() } ?: SupabaseConfig.ANON_KEY

            val req = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Authorization", "Bearer $bearer")
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { res ->
                val raw = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    throw IllegalStateException("โหลด stock_receiving ไม่สำเร็จ (${res.code}) $raw")
                }
                val arr = JSONArray(raw)
                val out = ArrayList<Triple<Long, Double, String?>>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val pid = o.getLong("product_id")
                    val qty = anyToDouble(o.opt("qty"))
                    val updated = o.optString("updated_at").ifBlank { null }
                    out.add(Triple(pid, qty, updated))
                }
                out
            }
        }

    private suspend fun fetchProductsMap(ids: List<Long>, accessToken: String?): Map<Long, Triple<String?, String?, String?>> =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext emptyMap()

            val bearer = accessToken?.takeIf { it.isNotBlank() } ?: SupabaseConfig.ANON_KEY
            val map = HashMap<Long, Triple<String?, String?, String?>>()

            // กัน URL ยาวเกิน: chunk ทีละ 200 id
            val chunkSize = 200
            val distinct = ids.distinct()

            for (chunk in distinct.chunked(chunkSize)) {
                val url = "${SupabaseConfig.URL}/rest/v1/products"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("select", "id,name,barcode,sku")
                    .addQueryParameter("id", "in.(${chunk.joinToString(",")})")
                    .build()

                val req = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("apikey", SupabaseConfig.ANON_KEY)
                    .addHeader("Authorization", "Bearer $bearer")
                    .addHeader("Accept", "application/json")
                    .build()

                client.newCall(req).execute().use { res ->
                    val raw = res.body?.string().orEmpty()
                    if (!res.isSuccessful) {
                        throw IllegalStateException("โหลด products ไม่สำเร็จ (${res.code}) $raw")
                    }
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val id = o.getLong("id")
                        val name = o.optString("name").ifBlank { null }
                        val barcode = o.optString("barcode").ifBlank { null }
                        val sku = o.optString("sku").ifBlank { null }
                        map[id] = Triple(name, barcode, sku)
                    }
                }
            }
            map
        }

    suspend fun fetchAll(accessToken: String?): List<StockReceivingItem> =
        withContext(Dispatchers.IO) {
            val stock = fetchStockReceiving(accessToken)
            val ids = stock.map { it.first }
            val pmap = fetchProductsMap(ids, accessToken)

            stock.map { (pid, qty, updated) ->
                val (name, barcode, sku) = pmap[pid] ?: Triple(null, null, null)
                StockReceivingItem(
                    productId = pid,
                    qty = qty,
                    updatedAt = updated,
                    name = name,
                    barcode = barcode,
                    sku = sku
                )
            }
        }

    fun formatUpdatedAt(raw: String?): String {
        if (raw.isNullOrBlank()) return "-"
        // รูปแบบประมาณ: 2025-12-19T10:20:30+00:00 -> 2025-12-19 10:20:30
        val s = raw.replace('T', ' ')
        return if (s.length >= 19) s.substring(0, 19) else s
    }

    fun normalize(s: String): String = s.lowercase(Locale.getDefault()).trim()
}
