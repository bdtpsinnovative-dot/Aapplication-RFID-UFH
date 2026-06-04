package data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ui.InitialCountItem
import java.net.URLEncoder

object SupabaseInitialCountApi {
    private val client = OkHttpClient()
    private val media = "application/json; charset=utf-8".toMediaType()

    private fun bearer(t: String?): String = t?.takeIf { it.isNotBlank() } ?: SupabaseConfig.ANON_KEY

    // 🔥 ระบบบันทึกยอดเดี่ยว ยึดยอดล่าสุดทันที ( Instant Overwrite & Save )
    suspend fun saveSingleItem(branchId: Long, userId: String, productId: Long, qty: Int, accessToken: String?) {
        withContext(Dispatchers.IO) {
            // 1. ค้นหาหัวเอกสาร (Header) "ทั้งหมด" ของสาขานี้ เพื่อเตรียมลบของเก่าที่อาจจะกระจัดกระจายอยู่
            val checkUrl = "${SupabaseConfig.URL}/rest/v1/stock_initial_counts?select=id&branch_id=eq.$branchId"
            val checkReq = Request.Builder()
                .url(checkUrl)
                .get()
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
                .build()

            val headerIds = mutableListOf<Long>()
            client.newCall(checkReq).execute().use { res ->
                if (res.isSuccessful) {
                    val arr = JSONArray(res.body?.string().orEmpty())
                    for (i in 0 until arr.length()) {
                        headerIds.add(arr.getJSONObject(i).getLong("id"))
                    }
                }
            }

            var activeHeaderId: Long = -1

            if (headerIds.isNotEmpty()) {
                activeHeaderId = headerIds.last() // ใช้ Header ล่าสุดเป็นตัวอ้างอิง

                // 2. 🔥 สั่งลบข้อมูลเก่าของสินค้านี้ "ในทุกๆ Header ของสาขานี้" (กวาดล้างยอดเก่าทั้งหมด จะได้ไม่บวกเบิ้ล!)
                val inQuery = headerIds.joinToString(",")
                val deleteUrl = "${SupabaseConfig.URL}/rest/v1/stock_initial_count_items?product_id=eq.$productId&initial_count_id=in.($inQuery)"
                val deleteReq = Request.Builder()
                    .url(deleteUrl)
                    .delete()
                    .addHeader("apikey", SupabaseConfig.ANON_KEY)
                    .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
                    .build()
                client.newCall(deleteReq).execute().use { res ->
                    if (!res.isSuccessful) Log.e("SupabaseAPI", "ลบยอดเก่าเพื่อ Overwrite ล้มเหลว")
                }
            } else {
                // ถ้าสาขานี้ยังไม่มีหัวเอกสารตั้งต้น ให้สร้างขึ้นมาใหม่ 1 อัน
                val bodyObj = JSONObject().apply {
                    put("branch_id", branchId)
                    put("counted_by", userId)
                    put("status", "confirmed")
                }
                val req = Request.Builder()
                    .url("${SupabaseConfig.URL}/rest/v1/stock_initial_counts")
                    .post(bodyObj.toString().toRequestBody(media))
                    .addHeader("apikey", SupabaseConfig.ANON_KEY)
                    .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=representation")
                    .build()

                client.newCall(req).execute().use { res ->
                    val responseBody = res.body?.string().orEmpty()
                    if (!res.isSuccessful) throw IllegalStateException("สร้างหัวเอกสารล้มเหลว: $responseBody")
                    val arr = JSONArray(responseBody)
                    if (arr.length() > 0) {
                        activeHeaderId = arr.getJSONObject(0).getLong("id")
                    } else {
                        throw IllegalStateException("ไม่ได้รับ ID หัวเอกสารกลับมาจากเซิร์ฟเวอร์")
                    }
                }
            }

            // 3. บันทึกยอดใหม่เข้าไปตรงๆ แค่ 1 Record (ถ้ายอดเป็น 0 หรือติดลบ จะไม่มีการ Insert = ลบทิ้ง)
            if (qty > 0 && activeHeaderId != -1L) {
                val itemObj = JSONObject().apply {
                    put("initial_count_id", activeHeaderId)
                    put("product_id", productId)
                    put("qty", qty)
                }
                val insertReq = Request.Builder()
                    .url("${SupabaseConfig.URL}/rest/v1/stock_initial_count_items")
                    .post(itemObj.toString().toRequestBody(media))
                    .addHeader("apikey", SupabaseConfig.ANON_KEY)
                    .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
                    .addHeader("Content-Type", "application/json")
                    .build()

                client.newCall(insertReq).execute().use { res ->
                    if (!res.isSuccessful) {
                        val responseBody = res.body?.string().orEmpty()
                        throw IllegalStateException("บันทึกยอดสินค้าใหม่ล้มเหลว: $responseBody")
                    }
                }
            }
        }
    }

    // ฟังก์ชันค้นหาสินค้าตัวเดี่ยว
    suspend fun lookupProduct(code: String, accessToken: String?): InitialCountItem? {
        return withContext(Dispatchers.IO) {
            val safeCode = URLEncoder.encode(code, "UTF-8")
            val url = "${SupabaseConfig.URL}/rest/v1/products?select=id,name,barcode,sku,image_url&or=(barcode.eq.$safeCode,sku.eq.$safeCode)&limit=1"

            val req = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
                .build()

            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val arr = JSONArray(res.body?.string().orEmpty())
                if (arr.length() > 0) {
                    val obj = arr.getJSONObject(0)
                    val pId = obj.getLong("id")
                    val pName = obj.getString("name")
                    val pImg = obj.optString("image_url", "").takeIf { it.isNotBlank() }
                    InitialCountItem(pId, pName, code, 1, pImg)
                } else {
                    null
                }
            }
        }
    }

    // โหลดรายการทั้งหมดในระบบมาโชว์
    suspend fun getSavedItems(branchId: Long, accessToken: String?): List<InitialCountItem> {
        return withContext(Dispatchers.IO) {
            val url = "${SupabaseConfig.URL}/rest/v1/stock_initial_count_items?select=qty,product_id,products(name,barcode,sku,image_url),stock_initial_counts!inner(branch_id)&stock_initial_counts.branch_id=eq.$branchId"
            val req = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
                .build()

            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw IllegalStateException("โหลดคลังล้มเหลว: $body")

                val arr = JSONArray(body)
                val map = mutableMapOf<Long, InitialCountItem>()

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val pId = obj.getLong("product_id")
                    val qty = obj.getInt("qty")
                    val pObj = obj.optJSONObject("products")
                    val pName = pObj?.optString("name") ?: "Unknown"
                    val barcode = pObj?.optString("barcode") ?: pObj?.optString("sku") ?: "-"
                    val pImg = pObj?.optString("image_url", "")?.takeIf { it.isNotBlank() }

                    if (map.containsKey(pId)) {
                        map[pId]!!.qty += qty
                    } else {
                        map[pId] = InitialCountItem(pId, pName, barcode, qty, pImg)
                    }
                }
                map.values.toList().sortedByDescending { it.qty }
            }
        }
    }

    // ลบข้อมูลทั้งหมด
    suspend fun deleteAllCounts(branchId: Long, accessToken: String?) {
        withContext(Dispatchers.IO) {
            val url = "${SupabaseConfig.URL}/rest/v1/stock_initial_counts?branch_id=eq.$branchId"
            val req = Request.Builder()
                .url(url)
                .delete()
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw IllegalStateException("ล้างคลังล้มเหลว")
            }
        }
    }
}