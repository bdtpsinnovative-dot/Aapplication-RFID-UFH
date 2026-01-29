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

data class SignInResult(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val email: String
)

// ✅ เพิ่ม branchName ใน Data Class
data class ProfileResult(
    val fullName: String,
    val role: String,
    val branchId: Long,
    val branchName: String
)

object SupabaseAuthApi {
    private val baseUrl = BuildConfig.SUPABASE_URL
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY
    private val client = OkHttpClient()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ... (ฟังก์ชัน signIn, refresh, bearer เก็บไว้เหมือนเดิมได้เลย) ...

    suspend fun signIn(email: String, password: String): SignInResult =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/auth/v1/token?grant_type=password"
            val bodyJson = JSONObject().put("email", email).put("password", password).toString()
            val req = Request.Builder().url(url).post(bodyJson.toRequestBody(jsonMedia))
                .addHeader("apikey", anonKey).addHeader("Content-Type", "application/json").build()

            client.newCall(req).execute().use { res ->
                val raw = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw IllegalStateException("Login Failed")
                val j = JSONObject(raw)
                val user = j.optJSONObject("user")
                SignInResult(
                    j.getString("access_token"),
                    j.optString("refresh_token", ""),
                    user?.optString("id", "") ?: "",
                    user?.optString("email", email) ?: email
                )
            }
        }

    // ✅ ฟังก์ชัน fetchProfile ที่ถูกต้อง (Join ตาราง branches)
    suspend fun fetchProfile(userId: String, accessToken: String): ProfileResult =
        withContext(Dispatchers.IO) {
            // เทคนิค PostgREST: ใช้ select=*,branches(branch_name) เพื่อ Join
            val url = "$baseUrl/rest/v1/profiles?user_id=eq.$userId&select=*,branches(branch_name)"

            val req = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $accessToken")
                .build()

            client.newCall(req).execute().use { res ->
                val raw = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw IllegalStateException("Fetch Profile Failed: $raw")

                val array = JSONArray(raw)
                if (array.length() == 0) return@withContext ProfileResult("Unknown", "Staff", 0L, "Unknown")

                val obj = array.getJSONObject(0)

                // 🔹 ดึงชื่อสาขาจาก Object ซ้อนที่ชื่อ "branches"
                val branchObj = obj.optJSONObject("branches")
                val bName = branchObj?.optString("branch_name") ?: "สาขาไม่ระบุ"

                ProfileResult(
                    fullName = obj.optString("full_name", ""),
                    role = obj.optString("role", "staff"),
                    branchId = obj.optLong("branch_id", 0L),
                    branchName = bName // ✅ ได้ชื่อสาขาแล้ว
                )
            }
        }
}