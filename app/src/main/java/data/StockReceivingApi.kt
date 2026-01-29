package data

import com.example.eob_rfid.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// DTO สำหรับส่งข้อมูลไป update
data class StockUpdateDto(
    val branch_id: Long,
    val product_id: Long,
    val qty: Double
)

object StockReceivingApi {
    private val baseUrl = BuildConfig.SUPABASE_URL
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY
    private val client = OkHttpClient()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // 1. ดึงสต็อก (ต้องระบุ branchId)
    suspend fun fetchQty(productId: Long, branchId: Long, accessToken: String): Double =
        withContext(Dispatchers.IO) {
            // Query: select qty from stock_receiving where product_id=... and branch_id=...
            val url = "$baseUrl/rest/v1/stock_receiving?product_id=eq.$productId&branch_id=eq.$branchId&select=qty"

            val req = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $accessToken")
                .build()

            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext 0.0
                val raw = res.body?.string() ?: return@withContext 0.0
                val arr = JSONArray(raw)
                if (arr.length() > 0) {
                    arr.getJSONObject(0).optDouble("qty", 0.0)
                } else {
                    0.0
                }
            }
        }

    // 2. ดึง Map สต็อกหลายรายการเพื่อเช็คก่อนบันทึก (ต้องระบุ branchId)
    suspend fun fetchQtyMap(productIds: List<Long>, branchId: Long, accessToken: String): Map<Long, Double> =
        withContext(Dispatchers.IO) {
            if (productIds.isEmpty()) return@withContext emptyMap()

            val idsStr = productIds.joinToString(",")
            val url = "$baseUrl/rest/v1/stock_receiving?product_id=in.($idsStr)&branch_id=eq.$branchId&select=product_id,qty"

            val req = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $accessToken")
                .build()

            val map = mutableMapOf<Long, Double>()
            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val raw = res.body?.string().orEmpty()
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val pid = obj.optLong("product_id")
                        val q = obj.optDouble("qty", 0.0)
                        map[pid] = q
                    }
                }
            }
            map
        }

    // 3. บันทึกสต็อก (Upsert โดยมี branch_id)
    suspend fun upsertStockList(updates: List<StockUpdateDto>, accessToken: String) =
        withContext(Dispatchers.IO) {
            if (updates.isEmpty()) return@withContext

            val url = "$baseUrl/rest/v1/stock_receiving"

            // แปลง DTO เป็น JSON Array
            val jsonArray = JSONArray()
            for (u in updates) {
                val j = JSONObject()
                j.put("branch_id", u.branch_id)
                j.put("product_id", u.product_id)
                j.put("qty", u.qty)
                jsonArray.put(j)
            }

            // ใช้ Prefer: resolution=merge-duplicates เพื่อทำการ Upsert
            val req = Request.Builder()
                .url(url)
                .post(jsonArray.toString().toRequestBody(jsonMedia))
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Prefer", "resolution=merge-duplicates")
                .build()

            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    val err = res.body?.string()
                    throw IllegalStateException("Upsert failed: $err")
                }
            }
        }
}