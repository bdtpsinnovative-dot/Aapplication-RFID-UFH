package data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

object ProductSyncManager {
    private val client = OkHttpClient()
    private const val PAGE = 500
    private const val MAX_AGE_MS = 60L * 60 * 1000 // 1 ชั่วโมง

    suspend fun syncAll(
        ctx: Context,
        accessToken: String?,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val db = ProductDatabase(ctx)
        val bearer = accessToken?.takeIf { it.isNotBlank() } ?: SupabaseConfig.ANON_KEY
        var offset = 0
        var knownTotal = 0
        var done = 0

        while (true) {
            val url = "${SupabaseConfig.URL}/rest/v1/products" +
                "?select=id,name,sku,barcode,price,unit,image_url,status,color,weight" +
                "&order=id.asc&offset=$offset&limit=$PAGE"

            val req = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Authorization", "Bearer $bearer")
                .addHeader("Accept", "application/json")
                .addHeader("Prefer", "count=exact")
                .build()

            val (rangeTotal, batch) = client.newCall(req).execute().use { res ->
                val raw = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw IllegalStateException("Sync products failed (${res.code}): $raw")

                // Content-Range: 0-499/3000
                val tot = res.header("Content-Range")
                    ?.substringAfter("/")?.toIntOrNull() ?: 0

                val arr = JSONArray(raw)
                val list = ArrayList<ProductCache>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list += ProductCache(
                        id = o.getLong("id"),
                        name = o.optString("name", ""),
                        sku = o.optString("sku").ifBlank { null },
                        barcode = o.optString("barcode").ifBlank { null },
                        price = o.optDouble("price", 0.0),
                        unit = o.optString("unit").ifBlank { null },
                        imageUrl = o.optString("image_url").ifBlank { null },
                        status = o.optString("status").ifBlank { null },
                        color = o.optString("color").ifBlank { null },
                        weight = o.optDouble("weight", 0.0)
                    )
                }
                tot to list
            }

            if (knownTotal == 0 && rangeTotal > 0) knownTotal = rangeTotal
            db.upsertBatch(batch)
            done += batch.size

            withContext(Dispatchers.Main) {
                onProgress(done, knownTotal.coerceAtLeast(done))
            }

            if (batch.size < PAGE) break
            offset += PAGE
        }
    }

    fun needsSync(ctx: Context): Boolean {
        val db = ProductDatabase(ctx)
        return db.count() == 0 || System.currentTimeMillis() - db.lastSyncMs() > MAX_AGE_MS
    }
}
