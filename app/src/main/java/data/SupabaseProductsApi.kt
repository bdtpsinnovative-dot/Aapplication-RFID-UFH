// ไฟล์: com/example/eob_rfid/SupabaseProductsApi.kt

package data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

// ✅ 1. เพิ่ม image_url ตรงนี้ครับ
data class ProductLite(
    val id: Long,
    val name: String,
    val price: Double,
    val barcode: String?,
    val sku: String?,
    val image_url: String? = null // <--- ต้องมีบรรทัดนี้
)

object SupabaseProductsApi {
    private val client = OkHttpClient()

    private fun numToDouble(any: Any?): Double {
        return when (any) {
            is Number -> any.toDouble()
            is String -> any.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }

    suspend fun fetchByCode(code: String, accessToken: String?): ProductLite? =
        withContext(Dispatchers.IO) {

            val c = code.trim()
            if (c.isBlank()) return@withContext null

            val idLong = c.toLongOrNull()
            val orExpr = buildString {
                append("(barcode.eq.$c,sku.eq.$c")
                if (idLong != null) append(",id.eq.$idLong")
                append(")")
            }

            // ✅ 2. เพิ่ม image_url ใน select
            val url = "${SupabaseConfig.URL}/rest/v1/products"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("select", "id,name,price,barcode,sku,image_url")
                .addQueryParameter("or", orExpr)
                .addQueryParameter("limit", "1")
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
                if (!res.isSuccessful) return@withContext null

                val arr = JSONArray(raw)
                if (arr.length() == 0) return@use null

                val o: JSONObject = arr.getJSONObject(0)

                // ✅ 3. รับค่า image_url จาก JSON
                ProductLite(
                    id = o.getLong("id"),
                    name = o.optString("name", "-"),
                    price = numToDouble(o.opt("price")),
                    barcode = o.optString("barcode").ifBlank { null },
                    sku = o.optString("sku").ifBlank { null },
                    image_url = o.optString("image_url").ifBlank { null } // <--- เพิ่มบรรทัดนี้
                )
            }
        }
}