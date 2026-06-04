package data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant

data class FoundTag(val rfid: String, val productId: Long, val productName: String?)
data class GroupRow(val productId: Long, val name: String, val qty: Long)

object SupabaseBatchCheckApi {
    private val client = OkHttpClient()
    private fun bearer(t: String?): String = t?.takeIf { it.isNotBlank() } ?: SupabaseConfig.ANON_KEY
    private fun quoteForIn(v: String) = "\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    suspend fun lookupMany(rfids: List<String>, accessToken: String?): List<FoundTag> {
        if (rfids.isEmpty()) return emptyList()
        val out = ArrayList<FoundTag>()
        val chunks = rfids.distinct().chunked(120)
        for (chunk in chunks) {
            val inList = chunk.joinToString(",") { quoteForIn(it) }
            val filter = URLEncoder.encode("in.($inList)", "UTF-8")
            val url = "${SupabaseConfig.URL}/rest/v1/product_rfid_tags?select=rfid,product_id,products(name)&rfid=$filter"
            val req = Request.Builder().url(url).get()
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
                .build()
            val res = withContext(Dispatchers.IO) { client.newCall(req).execute() }
            res.use {
                if (!it.isSuccessful) throw IllegalStateException("Lookup failed: ${it.code}")
                val arr = JSONArray(it.body?.string().orEmpty())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val rfid = o.optString("rfid")
                    val pid = o.optLong("product_id", -1L)
                    val name = o.optJSONObject("products")?.optString("name")
                    if (rfid.isNotBlank() && pid > 0) out.add(FoundTag(rfid, pid, name))
                }
            }
        }
        return out
    }
}

object SupabaseReaderStockApi {
    private val client = OkHttpClient()
    private val media = "application/json; charset=utf-8".toMediaType()
    private fun bearer(t: String?): String = t?.takeIf { it.isNotBlank() } ?: SupabaseConfig.ANON_KEY

    // 🔴 ปรับฟังก์ชันให้รับ branchId เพิ่มเข้ามา
    suspend fun upsertFromCounts(groups: List<GroupRow>, branchId: Long, accessToken: String?) {
        val nowIso = Instant.now().toString()
        val arr = JSONArray()
        for (g in groups) {
            arr.put(
                JSONObject()
                    .put("product_id", g.productId)
                    .put("branch_id", branchId) // 🔴 ยิง branch_id เข้าตารางด้วย
                    .put("qty", g.qty)
                    .put("updated_at", nowIso)
            )
        }

        // 🔴 เปลี่ยน on_conflict ให้เช็คคู่กันทั้ง product_id และ branch_id ป้องกันข้อมูลสาขาอื่นโดนทับเขียนซ้ำ
        val req = Request.Builder()
            .url("${SupabaseConfig.URL}/rest/v1/reader_stock?on_conflict=product_id,branch_id")
            .post(arr.toString().toRequestBody(media))
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
            .build()
        val res = withContext(Dispatchers.IO) { client.newCall(req).execute() }
        res.use { if (!it.isSuccessful) throw IllegalStateException("Upsert failed: ${it.code}") }
    }
}