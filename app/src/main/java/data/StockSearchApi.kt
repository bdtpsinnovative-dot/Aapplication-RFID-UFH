package data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

// Data Class สำหรับเก็บใน RAM
data class TargetRfidItem(
    val rfid: String,
    val productId: Long,
    val targetId: Long,
    val productName: String,
    val sku: String,
    val imageUrl: String?
)

object StockSearchApi {
    private val client = OkHttpClient()

    // 1. โหลดของที่ต้องหาทั้งหมดมาประกอบร่างใน RAM
    suspend fun getPreloadedTargets(ctx: Context): Map<String, TargetRfidItem> = withContext(Dispatchers.IO) {
        AuthManager.withValidToken(ctx) { token ->
            val branchId = SessionStore.getBranchId(ctx).takeIf { it > 0 } ?: 1L

            val targetUrl = "${SupabaseConfig.URL}/rest/v1/search_targets?select=id,product_id,products(name,sku,image_url)&branch_id=eq.$branchId"
            val targetReq = Request.Builder().url(targetUrl)
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Authorization", "Bearer $token").build()

            val targetRes = client.newCall(targetReq).execute()
            if (!targetRes.isSuccessful) throw Exception("โหลด Targets พัง: ${targetRes.code}")
            val targetJson = JSONArray(targetRes.body?.string() ?: "[]")

            val productIds = mutableListOf<Long>()
            val targetInfoMap = mutableMapOf<Long, JSONObject>()

            for (i in 0 until targetJson.length()) {
                val item = targetJson.getJSONObject(i)
                val pId = item.getLong("product_id")
                productIds.add(pId)
                targetInfoMap[pId] = item
            }

            if (productIds.isEmpty()) return@withValidToken emptyMap()

            val pIdsString = productIds.joinToString(",")
            val rfidUrl = "${SupabaseConfig.URL}/rest/v1/product_rfid_tags?select=rfid,product_id&product_id=in.($pIdsString)"
            val rfidReq = Request.Builder().url(rfidUrl)
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Authorization", "Bearer $token").build()

            val rfidRes = client.newCall(rfidReq).execute()
            if (!rfidRes.isSuccessful) throw Exception("โหลด RFIDs พัง: ${rfidRes.code}")
            val rfidJson = JSONArray(rfidRes.body?.string() ?: "[]")

            val resultMap = mutableMapOf<String, TargetRfidItem>()
            for (i in 0 until rfidJson.length()) {
                val rItem = rfidJson.getJSONObject(i)
                val rfid = rItem.getString("rfid").uppercase().trim()
                val pId = rItem.getLong("product_id")

                val targetData = targetInfoMap[pId] ?: continue
                val productsObj = targetData.optJSONObject("products")

                resultMap[rfid] = TargetRfidItem(
                    rfid = rfid,
                    productId = pId,
                    targetId = targetData.getLong("id"),
                    productName = productsObj?.optString("name") ?: "ไม่ทราบชื่อ",
                    sku = productsObj?.optString("sku") ?: "N/A",
                    imageUrl = productsObj?.optString("image_url")?.takeIf { it != "null" && it.isNotBlank() }
                )
            }
            resultMap
        }
    }

    // 🔴 2. [เพิ่มใหม่] ฟังก์ชันลบเป้าหมายเมื่อสแกนเจอแล้ว
    suspend fun removeSearchTarget(ctx: Context, targetId: Long): Boolean = withContext(Dispatchers.IO) {
        AuthManager.withValidToken(ctx) { token ->
            val url = "${SupabaseConfig.URL}/rest/v1/search_targets?id=eq.$targetId"
            val req = Request.Builder()
                .url(url)
                .delete()
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .build()

            client.newCall(req).execute().use { res ->
                res.isSuccessful
            }
        }
    }
}