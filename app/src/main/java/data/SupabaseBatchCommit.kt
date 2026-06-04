package data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal object SupabaseBatchCommit {
    private val client = OkHttpClient()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun bearer(accessToken: String?) =
        accessToken?.takeIf { it.isNotBlank() } ?: SupabaseConfig.ANON_KEY

    private suspend fun http(req: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { res ->
            res.code to (res.body?.string().orEmpty())
        }
    }

    suspend fun commitAll(
        data: Map<Long, List<String>>,
        accessToken: String?,
        branchId: Long,
        branchName: String,
        userId: String,
        userName: String,
        lotId: Long? = null
    ) {
        for ((pid, rfids) in data) {
            if (rfids.isEmpty()) continue
            insertTags(pid, rfids, accessToken, branchId, lotId)
            addStock(pid, rfids.size.toDouble(), accessToken, branchId)
            insertMovement(
                productId = pid,
                qty = rfids.size.toDouble(),
                accessToken = accessToken,
                branchId = branchId,
                branchName = branchName,
                userId = userId,
                userName = userName,
                lotId = lotId
            )
        }
    }

    private suspend fun insertTags(
        productId: Long,
        rfids: List<String>,
        accessToken: String?,
        branchId: Long,
        lotId: Long? = null
    ) {
        val arr = JSONArray()
        rfids.forEach { r ->
            val obj = JSONObject()
                .put("product_id", productId)
                .put("rfid", r)
                .put("status", "IN_STOCK")
                .put("branch_id", branchId)
            if (lotId != null) obj.put("lot_id", lotId) else obj.put("lot_id", JSONObject.NULL)
            arr.put(obj)
        }
        val req = Request.Builder()
            .url("${SupabaseConfig.URL}/rest/v1/product_rfid_tags?on_conflict=rfid")
            .post(arr.toString().toRequestBody(jsonMedia))
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "resolution=ignore-duplicates,return=minimal")
            .build()
        val (code, raw) = http(req)
        if (code !in 200..299) throw IllegalStateException("บันทึกแท็กไม่สำเร็จ ($code) $raw")
    }

    private suspend fun getStockQty(productId: Long, accessToken: String?, branchId: Long): Double? {
        val url = "${SupabaseConfig.URL}/rest/v1/stock?product_id=eq.$productId&branch_id=eq.$branchId&select=qty&limit=1"
        val req = Request.Builder().url(url).get()
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
            .addHeader("Accept", "application/json")
            .build()
        val (code, raw) = http(req)
        if (code !in 200..299) throw IllegalStateException("อ่าน stock ไม่สำเร็จ ($code) $raw")
        val arr = JSONArray(raw)
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        return when (val v = o.opt("qty")) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }

    private suspend fun addStock(productId: Long, inc: Double, accessToken: String?, branchId: Long) {
        val rpcBody = JSONObject()
            .put("p_product_id", productId)
            .put("p_branch_id", branchId)
            .put("p_delta", inc)
        val rpcReq = Request.Builder()
            .url("${SupabaseConfig.URL}/rest/v1/rpc/increment_stock")
            .post(rpcBody.toString().toRequestBody(jsonMedia))
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
            .addHeader("Content-Type", "application/json")
            .build()
        val (rpcCode, _) = http(rpcReq)
        if (rpcCode in 200..299) return

        for (attempt in 0..2) {
            val old = getStockQty(productId, accessToken, branchId)
            val newQty = (old ?: 0.0) + inc
            val body = JSONObject().put("qty", newQty)
            val builder = if (old == null) {
                body.put("product_id", productId).put("branch_id", branchId)
                Request.Builder()
                    .url("${SupabaseConfig.URL}/rest/v1/stock")
                    .post(body.toString().toRequestBody(jsonMedia))
                    .addHeader("Prefer", "resolution=ignore-duplicates,return=minimal")
            } else {
                Request.Builder()
                    .url("${SupabaseConfig.URL}/rest/v1/stock?product_id=eq.$productId&branch_id=eq.$branchId&qty=eq.$old")
                    .patch(body.toString().toRequestBody(jsonMedia))
                    .addHeader("Prefer", "return=representation")
            }
            val req = builder
                .addHeader("apikey", SupabaseConfig.ANON_KEY)
                .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
                .addHeader("Content-Type", "application/json")
                .build()
            val (code, raw) = http(req)
            if (code !in 200..299) throw IllegalStateException("อัปเดต stock ไม่สำเร็จ ($code) $raw")
            val updated = old == null || try { JSONArray(raw).length() > 0 } catch (_: Exception) { true }
            if (updated) return
            if (attempt < 2) kotlinx.coroutines.delay(80L * (attempt + 1))
        }
        throw IllegalStateException("อัปเดต stock ไม่สำเร็จ หลังลอง 3 ครั้ง (concurrent conflict)")
    }

    private suspend fun insertMovement(
        productId: Long,
        qty: Double,
        accessToken: String?,
        branchId: Long,
        branchName: String,
        userId: String,
        userName: String,
        lotId: Long? = null
    ) {
        val body = JSONObject()
            .put("product_id", productId.toString())
            .put("product_id_bigint", productId)
            .put("type", "IN")
            .put("qty", qty)
            .put("branch_id", branchId)
            .put("note", "รับสินค้าเข้าสาขา $branchId ($branchName) [RFID]")
            .put("created_by", userId)
            .put("created_by_name", userName)
        if (lotId != null) body.put("lot_id", lotId) else body.put("lot_id", JSONObject.NULL)
        val req = Request.Builder()
            .url("${SupabaseConfig.URL}/rest/v1/stock_movements")
            .post(body.toString().toRequestBody(jsonMedia))
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal")
            .build()
        val (code, raw) = http(req)
        if (code !in 200..299) throw IllegalStateException("บันทึก movement ไม่สำเร็จ ($code) $raw")
    }

    suspend fun findConflictingTags(
        data: Map<Long, List<String>>,
        accessToken: String?
    ): List<Pair<String, Long>> {
        val tagToProduct = mutableMapOf<String, Long>()
        for ((pid, tags) in data) tags.forEach { tagToProduct[it] = pid }
        if (tagToProduct.isEmpty()) return emptyList()

        val tagList = tagToProduct.keys.joinToString(",")
        val url = "${SupabaseConfig.URL}/rest/v1/product_rfid_tags?rfid=in.($tagList)&select=rfid,product_id"
        val req = Request.Builder().url(url).get()
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
            .addHeader("Accept", "application/json")
            .build()
        val (code, raw) = http(req)
        if (code !in 200..299) return emptyList()

        val arr = JSONArray(raw)
        val conflicts = mutableListOf<Pair<String, Long>>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val rfid = obj.getString("rfid")
            val existingPid = obj.getLong("product_id")
            val expectedPid = tagToProduct[rfid]
            if (expectedPid != null && existingPid != expectedPid) {
                conflicts.add(rfid to existingPid)
            }
        }
        return conflicts
    }

    suspend fun clearStockReceiving(accessToken: String?, branchId: Long? = null, lotId: Long? = null) {
        var url = "${SupabaseConfig.URL}/rest/v1/stock_receiving?product_id=gt.0"
        if (branchId != null) url += "&branch_id=eq.$branchId"
        url += if (lotId != null) "&lot_id=eq.$lotId" else "&lot_id=is.null"
        val req = Request.Builder().url(url).delete()
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
            .addHeader("Prefer", "return=minimal")
            .build()
        val (code, raw) = http(req)
        if (code !in 200..299) throw IllegalStateException("ล้าง stock_receiving ไม่สำเร็จ ($code) $raw")
    }

    suspend fun deleteProduct(productId: Long, branchId: Long, accessToken: String?) {
        val url = "${SupabaseConfig.URL}/rest/v1/stock_receiving?product_id=eq.$productId&branch_id=eq.$branchId"
        val req = Request.Builder().url(url).delete()
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer ${bearer(accessToken)}")
            .addHeader("Prefer", "return=minimal")
            .build()
        val (code, raw) = http(req)
        if (code !in 200..299) throw IllegalStateException("ลบสินค้าออกจากระบบไม่สำเร็จ ($code) $raw")
    }
}
