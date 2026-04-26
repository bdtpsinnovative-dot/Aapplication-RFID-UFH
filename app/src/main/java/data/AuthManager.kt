package data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object AuthManager {
    private val client = OkHttpClient()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ฟังก์ชันพระเอก: ขอ Token ที่ใช้งานได้แน่นอน (ถ้าหมดอายุก็ต่อให้เอง)
    // forceRefresh = true → บังคับ refresh ทันทีโดยไม่เช็คเวลา (ใช้ตอน server บอกว่า JWT expired)
    suspend fun getValidAccessToken(ctx: Context, forceRefresh: Boolean = false): String? {
        // 1. ลองดูใน RAM (SessionManager) ก่อน
        var token = SessionManager.accessToken

        // 2. ถ้า RAM ไม่มี ลองกู้จาก Disk
        if (token.isNullOrBlank()) {
            token = SessionStore.getAccessToken(ctx)
            if (!token.isNullOrBlank()) {
                // กู้คืนเข้า RAM
                SessionManager.accessToken = token
                SessionManager.refreshToken = SessionStore.getRefreshToken(ctx)
            }
        }

        // 3. เช็คว่าหมดอายุหรือยัง? (หรือถูกบังคับ refresh)
        if (forceRefresh || SessionStore.isTokenExpired(ctx)) {
            // ⚠️ หมดอายุแล้ว! ต้องต่ออายุ (Refresh)
            val refreshToken = SessionStore.getRefreshToken(ctx) ?: return null // ถ้าไม่มีใบต่ออายุก็ช่วยไม่ได้

            return withContext(Dispatchers.IO) {
                try {
                    val body = JSONObject().put("refresh_token", refreshToken)
                    val req = Request.Builder()
                        .url("${SupabaseConfig.URL}/auth/v1/token?grant_type=refresh_token")
                        .post(body.toString().toRequestBody(jsonMedia))
                        .addHeader("apikey", SupabaseConfig.ANON_KEY)
                        .build()

                    client.newCall(req).execute().use { res ->
                        if (res.isSuccessful) {
                            val responseBody = res.body?.string()
                            val json = JSONObject(responseBody)

                            val newAccessToken = json.getString("access_token")
                            val newRefreshToken = json.getString("refresh_token")
                            val expiresIn = json.optLong("expires_in", 3600)

                            // ✅ ต่ออายุสำเร็จ! อัปเดตทั้ง RAM และ Disk
                            withContext(Dispatchers.Main) {
                                SessionStore.updateTokens(ctx, newAccessToken, newRefreshToken, expiresIn)
                                SessionManager.accessToken = newAccessToken
                                SessionManager.refreshToken = newRefreshToken
                            }
                            newAccessToken // ส่งบัตรใบใหม่ไปให้ใช้
                        } else {
                            SessionEventBus.emitExpired()
                            null
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    SessionEventBus.emitExpired()
                    null
                }
            }
        }

        // 4. ถ้ายังไม่หมดอายุ ก็ใช้ใบเดิมได้เลย
        return token
    }

    // Helper: เรียก API แล้ว retry อัตโนมัติเมื่อ server ตอบ 401 (JWT expired จริงๆ)
    // ใช้แทนการเรียก getValidAccessToken เอง เพื่อกัน clock skew
    suspend fun <T> withValidToken(ctx: Context, call: suspend (token: String) -> T): T {
        val token = getValidAccessToken(ctx)
            ?: throw IllegalStateException("ไม่มี session กรุณาล็อกอินใหม่")

        return try {
            call(token)
        } catch (e: Exception) {
            val is401 = e.message?.contains("401") == true
                     || e.message?.contains("JWT") == true
                     || e.message?.contains("PGRST303") == true
            if (is401) {
                // token หมดอายุจริงบน server → force refresh แล้ว retry 1 ครั้ง
                val newToken = getValidAccessToken(ctx, forceRefresh = true)
                    ?: run {
                        SessionEventBus.emitExpired()
                        throw IllegalStateException("Session หมดอายุ กรุณาล็อกอินใหม่")
                    }
                call(newToken)
            } else throw e
        }
    }
}