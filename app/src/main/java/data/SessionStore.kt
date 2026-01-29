package data

import android.content.Context
import androidx.core.content.edit

object SessionStore {
    private const val PREF_NAME = "eob_rfid_prefs"

    // Keys เดิม
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_EMAIL = "email"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_ROLE = "role"
    private const val KEY_BRANCH_ID = "branch_id"
    private const val KEY_BRANCH_NAME = "branch_name"

    // ✅ NEW: เพิ่ม Key สำหรับจำเวลาหมดอายุ
    private const val KEY_EXPIRES_AT = "expires_at"

    // ✅ ฟังก์ชัน Save (เพิ่ม expiresIn รับค่าเวลาหมดอายุจาก Supabase)
    fun save(
        ctx: Context,
        accessToken: String,
        refreshToken: String,
        userId: String,
        email: String,
        displayName: String,
        role: String,
        branchId: Long?,
        branchName: String?,
        expiresIn: Long = 3600 // ค่าเริ่มต้น 1 ชั่วโมง (3600 วิ)
    ) {
        // คำนวณเวลาที่จะหมดอายุจริง (เวลาปัจจุบัน + วินาทีที่เหลือ - เผื่อเวลาไว้ 60 วินาทีกันพลาด)
        val expireTime = System.currentTimeMillis() + (expiresIn * 1000) - 60000

        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_REFRESH_TOKEN, refreshToken)
            putString(KEY_USER_ID, userId)
            putString(KEY_EMAIL, email)
            putString(KEY_DISPLAY_NAME, displayName)
            putString(KEY_ROLE, role)

            // เก็บ Branch
            putLong(KEY_BRANCH_ID, branchId ?: 0L)
            putString(KEY_BRANCH_NAME, branchName ?: "สาขาทั่วไป")

            // ✅ บันทึกเวลาหมดอายุ
            putLong(KEY_EXPIRES_AT, expireTime)
        }
    }

    // ✅ NEW: ฟังก์ชันสำหรับอัปเดตเฉพาะ Token (ใช้ตอน Auto Refresh ทำงาน)
    fun updateTokens(ctx: Context, accessToken: String, refreshToken: String, expiresIn: Long) {
        val expireTime = System.currentTimeMillis() + (expiresIn * 1000) - 60000
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_REFRESH_TOKEN, refreshToken)
            putLong(KEY_EXPIRES_AT, expireTime)
        }
    }

    // ✅ ฟังก์ชัน Clear (ตอน Logout)
    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit {
            clear()
        }
    }

    // --- Getters ---

    fun getAccessToken(ctx: Context): String? {
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACCESS_TOKEN, null)
    }

    // ✅ NEW: เพิ่ม getter สำหรับ Refresh Token
    fun getRefreshToken(ctx: Context): String? {
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_REFRESH_TOKEN, null)
    }

    // ✅ NEW: เช็คว่า Token หมดอายุหรือยัง (คืนค่า true ถ้าหมดแล้ว)
    fun isTokenExpired(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val expireTime = prefs.getLong(KEY_EXPIRES_AT, 0)
        // ถ้าเวลาปัจจุบัน มากกว่า เวลาหมดอายุ แสดงว่า Token ตายแล้ว
        return System.currentTimeMillis() > expireTime
    }

    fun getUserId(ctx: Context): String {
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_USER_ID, "") ?: ""
    }

    fun getDisplayName(ctx: Context): String {
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DISPLAY_NAME, "พนักงาน") ?: "พนักงาน"
    }

    fun getRole(ctx: Context): String {
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ROLE, "staff") ?: "staff"
    }

    fun getBranchId(ctx: Context): Long {
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_BRANCH_ID, 0L)
    }

    fun getBranchName(ctx: Context): String {
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BRANCH_NAME, "สาขาทั่วไป") ?: "สาขาทั่วไป"
    }
}