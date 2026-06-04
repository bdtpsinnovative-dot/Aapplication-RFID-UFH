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
import ui.ScannedItem

// 📦 Data Class รองรับข้อมูลสาขา
data class BranchItem(
    val id: Long,
    val branchCode: String,
    val branchName: String,
    val branchType: String
)

// 📦 Data Class สำหรับแสดงรายการใบโอนในหน้าเลือกตั๋ว
data class TransferListItem(
    val id: Long,
    val transferCode: String,
    val fromBranchName: String,
    val status: String
)

// 📦 Data Class สำหรับหน้าตรวจรับสินค้า
data class ReceiveItemData(
    val id: Long,
    val transferId: Long,
    val productId: Long,
    val transferQty: Int,
    var receivedQty: Int,
    val productName: String,
    val barcode: String,
    val imageUrl: String // ✨ เพิ่มฟิลด์เก็บลิ้งก์รูปภาพตรงนี้!
)

class SupabaseTransferApi {
    private val baseUrl = BuildConfig.SUPABASE_URL
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY
    private val client = OkHttpClient()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // 🏢 ดึงข้อมูลสาขาทั้งหมด
    suspend fun fetchBranches(accessToken: String): List<BranchItem> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/rest/v1/branches?select=id,branch_code,branch_name,branch_type&order=branch_name.asc"
        val req = Request.Builder().url(url).get().addHeader("apikey", anonKey).addHeader("Authorization", "Bearer $accessToken").build()
        client.newCall(req).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IllegalStateException("ดึงข้อมูลสาขาล้มเหลว: $raw")
            val array = JSONArray(raw)
            val list = mutableListOf<BranchItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(BranchItem(obj.getLong("id"), obj.getString("branch_code"), obj.getString("branch_name"), obj.getString("branch_type")))
            }
            return@withContext list
        }
    }

    // 📥 ดึงรายการใบโอนที่ "ส่งมาหาเรา" และสถานะเป็น "PENDING" ของจริงจาก DB
    suspend fun fetchPendingTransfers(accessToken: String, toBranchId: Long): List<TransferListItem> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/rest/v1/stock_transfers?select=id,transfer_code,status,from_branch_id,to_branch_id,branches!stock_transfers_from_branch_fkey(branch_name)&status=eq.PENDING&to_branch_id=eq.$toBranchId&order=created_at.desc"

        val req = Request.Builder()
            .url(url)
            .get()
            .addHeader("apikey", anonKey)
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        client.newCall(req).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IllegalStateException("ดึงรายการตั๋วโอนล้มเหลว: $raw")

            val array = JSONArray(raw)
            val list = mutableListOf<TransferListItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val branchObj = obj.optJSONObject("branches")
                val fromBranchName = branchObj?.optString("branch_name") ?: "ไม่ระบุสาขา"

                list.add(
                    TransferListItem(
                        id = obj.getLong("id"),
                        transferCode = obj.getString("transfer_code"),
                        fromBranchName = fromBranchName,
                        status = obj.getString("status")
                    )
                )
            }
            return@withContext list
        }
    }

    // 🚀 สร้างใบโอน (ฝั่งโอนออก)
    suspend fun createTransfer(
        accessToken: String,
        transferCode: String,
        fromBranchId: Long,
        toBranchId: Long,
        createdBy: String,
        createdByName: String,
        items: List<ScannedItem>
    ): Long = withContext(Dispatchers.IO) {
        val transferUrl = "$baseUrl/rest/v1/stock_transfers"
        val transferBody = JSONObject().apply {
            put("transfer_code", transferCode)
            put("from_branch_id", fromBranchId)
            put("to_branch_id", toBranchId)
            put("status", "PENDING")
            put("created_by", createdBy)
            put("created_by_name", createdByName)
        }.toString()

        val transferReq = Request.Builder().url(transferUrl).post(transferBody.toRequestBody(jsonMedia)).addHeader("apikey", anonKey).addHeader("Authorization", "Bearer $accessToken").addHeader("Prefer", "return=representation").build()
        val generatedTransferId = client.newCall(transferReq).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IllegalStateException("สร้างใบโอนล้มเหลว: $raw")
            val array = JSONArray(raw)
            if (array.length() == 0) throw IllegalStateException("ไม่ได้รับข้อมูลใบโอนกลับมาสำเร็จ")
            array.getJSONObject(0).getLong("id")
        }

        val itemsUrl = "$baseUrl/rest/v1/stock_transfer_items"
        val itemsArray = JSONArray()
        for (item in items) {
            val itemJson = JSONObject().apply {
                put("transfer_id", generatedTransferId)
                put("product_id", item.id)
                put("qty", item.qty)
                put("transfer_qty", item.qty)
                put("item_status", "PENDING")
            }
            itemsArray.put(itemJson)
        }

        val itemsReq = Request.Builder().url(itemsUrl).post(itemsArray.toString().toRequestBody(jsonMedia)).addHeader("apikey", anonKey).addHeader("Authorization", "Bearer $accessToken").build()
        client.newCall(itemsReq).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("เพิ่มรายการสินค้าเข้าใบโอนล้มเหลว: ${res.body?.string()}")
        }
        return@withContext generatedTransferId
    }

    // 📥 ดึงรายการสินค้าในตั๋วโอน (ฝั่งรับเข้า)
    suspend fun getTransferItemsForReceive(accessToken: String, transferId: Long): List<ReceiveItemData> = withContext(Dispatchers.IO) {
        // ✨ อัปเดตคำสั่ง select ให้ดึง image_url มาด้วย
        val url = "$baseUrl/rest/v1/stock_transfer_items?select=id,transfer_id,product_id,transfer_qty,received_qty,products(name,barcode,image_url)&transfer_id=eq.$transferId"
        val req = Request.Builder().url(url).get().addHeader("apikey", anonKey).addHeader("Authorization", "Bearer $accessToken").build()
        client.newCall(req).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IllegalStateException("ดึงรายการสินค้าล้มเหลว: $raw")
            val array = JSONArray(raw)
            val list = mutableListOf<ReceiveItemData>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val productObj = obj.optJSONObject("products")
                list.add(
                    ReceiveItemData(
                        id = obj.getLong("id"),
                        transferId = obj.getLong("transfer_id"),
                        productId = obj.getLong("product_id"),
                        transferQty = obj.getInt("transfer_qty"),
                        receivedQty = obj.getInt("transfer_qty"),
                        productName = productObj?.optString("name") ?: "ไม่ทราบชื่อสินค้า",
                        barcode = productObj?.optString("barcode") ?: "",
                        imageUrl = productObj?.optString("image_url") ?: "" // ✨ รับค่ารูปลงตัวแปร
                    )
                )
            }
            return@withContext list
        }
    }

    // 📤 ยิงอัปเดตสต็อกม้วนเดียวจบผ่าน RPC
    suspend fun submitReceivedStockRPC(accessToken: String, transferId: Long, receivedItems: List<ReceiveItemData>, userId: String): Boolean = withContext(Dispatchers.IO) {
        val rpcUrl = "$baseUrl/rest/v1/rpc/receive_stock_transaction"
        val itemsArray = JSONArray()
        for (item in receivedItems) {
            val itemJson = JSONObject().apply {
                put("id", item.id)
                put("product_id", item.productId)
                put("transfer_qty", item.transferQty)
                put("received_qty", item.receivedQty)
            }
            itemsArray.put(itemJson)
        }
        val body = JSONObject().apply {
            put("p_transfer_id", transferId)
            put("p_user_id", userId)
            put("p_items", itemsArray)
        }.toString()

        val req = Request.Builder().url(rpcUrl).post(body.toRequestBody(jsonMedia)).addHeader("apikey", anonKey).addHeader("Authorization", "Bearer $accessToken").build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("เกิดข้อผิดพลาดในการบันทึกสต๊อก (RPC Failed): ${res.body?.string()}")
        }
        return@withContext true
    }
}