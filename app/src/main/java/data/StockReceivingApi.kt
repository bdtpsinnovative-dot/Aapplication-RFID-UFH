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

// 💡 DTO สำหรับส่งข้อมูลไป update (เพิ่ม lotId)
data class StockUpdateDto(
    val branch_id: Long,
    val product_id: Long,
    val qty: Double,
    val lot_id: Long? = null // 👈 เพิ่ม lot_id (เป็น null ได้ ถ้ารับเข้านอกลอต)
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

    // 2. ดึง Map สต็อกหลายรายการเพื่อเช็คก่อนบันทึก (filter ตาม lotId ด้วย เพื่อกัน row ซ้ำ)
    suspend fun fetchQtyMap(productIds: List<Long>, branchId: Long, accessToken: String, lotId: Long? = null): Map<Long, Double> =
        withContext(Dispatchers.IO) {
            if (productIds.isEmpty()) return@withContext emptyMap()

            val idsStr = productIds.joinToString(",")
            val lotFilter = if (lotId != null) "&lot_id=eq.$lotId" else "&lot_id=is.null"
            val url = "$baseUrl/rest/v1/stock_receiving?product_id=in.($idsStr)&branch_id=eq.$branchId$lotFilter&select=product_id,qty"

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

    // 3. บันทึกสต็อก (Upsert โดยมี branch_id และ lot_id)
    suspend fun upsertStockList(updates: List<StockUpdateDto>, accessToken: String) =
        withContext(Dispatchers.IO) {
            if (updates.isEmpty()) return@withContext

            // on_conflict บอก PostgREST ให้ใช้ unique constraint (branch_id, product_id, lot_id)
            // ไม่ใช่ surrogate PK "id" ที่เพิ่มมา
            val url = "$baseUrl/rest/v1/stock_receiving?on_conflict=branch_id,product_id,lot_id"

            // แปลง DTO เป็น JSON Array
            val jsonArray = JSONArray()
            for (u in updates) {
                val j = JSONObject()
                j.put("branch_id", u.branch_id)
                j.put("product_id", u.product_id)
                j.put("qty", u.qty)

                // 💡 เพิ่ม lot_id เข้าไปใน JSON
                if (u.lot_id != null) {
                    j.put("lot_id", u.lot_id)
                } else {
                    j.put("lot_id", JSONObject.NULL) // ถ้าไม่มี ส่ง null เข้าดาต้าเบส
                }

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