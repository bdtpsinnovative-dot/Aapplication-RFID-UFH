package data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

object SupabaseDamageApi {
    private val client = OkHttpClient()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ✨ ฟังก์ชันใหม่: ดึงยอดสต็อกปัจจุบันของสาขาตัวเอง
    suspend fun getCurrentStock(accessToken: String, productId: Long, branchId: Long): Double = withContext(Dispatchers.IO) {
        val url = "${SupabaseConfig.URL}/rest/v1/stock?select=qty&product_id=eq.$productId&branch_id=eq.$branchId"
        val req = Request.Builder()
            .url(url)
            .get()
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Accept", "application/json")
            .build()

        try {
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext 0.0
                val raw = res.body?.string().orEmpty()
                val arr = JSONArray(raw)
                if (arr.length() > 0) {
                    return@withContext arr.getJSONObject(0).optDouble("qty", 0.0)
                }
                return@withContext 0.0
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext 0.0
        }
    }

    // ฟังก์ชันบันทึกของเสีย (ใช้ของเดิมที่เพิ่งแก้ไปครับ)
    suspend fun recordDamage(
        accessToken: String,
        productId: Long,
        branchId: Long,
        qty: Double,
        reason: String?,
        recordedBy: String,
        recordedByName: String?,
        rfidTag: String? = null
    ): Boolean = withContext(Dispatchers.IO) {

        val json = JSONObject().apply {
            put("product_id", productId)
            put("branch_id", branchId)
            put("qty", qty)
            put("reason", reason ?: "")
            put("recorded_by", recordedBy)
            if (recordedByName != null) put("recorded_by_name", recordedByName)

            if (rfidTag != null && rfidTag.isNotBlank()) {
                put("rfid_tag", rfidTag)
            }
        }

        val body = json.toString().toRequestBody(jsonMedia)
        val url = "${SupabaseConfig.URL}/rest/v1/damaged_goods_records"
        val req = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("Accept", "application/json")
            .addHeader("Prefer", "return=minimal")
            .build()

        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) {
                val errorBody = res.body?.string() ?: "Unknown Error"
                throw IllegalStateException("Save failed (${res.code}): $errorBody")
            }
            res.isSuccessful
        }
    }
}