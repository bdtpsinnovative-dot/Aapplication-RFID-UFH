package com.example.eob_rfid

import android.content.Context
import androidx.core.content.edit

object SessionStore {
    private const val PREF_NAME = "eob_rfid_prefs"

    // Keys
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_EMAIL = "email"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_ROLE = "role"
    private const val KEY_BRANCH_ID = "branch_id"
    private const val KEY_BRANCH_NAME = "branch_name"

    // ✅ ฟังก์ชัน Save (รองรับ branchId, branchName, userId ที่คุณเรียกใช้ใน AppNav)
    fun save(
        ctx: Context,
        accessToken: String,
        refreshToken: String,
        userId: String,
        email: String,
        displayName: String,
        role: String,
        branchId: Long?,
        branchName: String?
    ) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_REFRESH_TOKEN, refreshToken)
            putString(KEY_USER_ID, userId)
            putString(KEY_EMAIL, email)
            putString(KEY_DISPLAY_NAME, displayName)
            putString(KEY_ROLE, role)

            // เก็บ Branch (ถ้าเป็น null ให้ใส่ค่า default เช่น 0 หรือ "")
            putLong(KEY_BRANCH_ID, branchId ?: 0L)
            putString(KEY_BRANCH_NAME, branchName ?: "สาขาทั่วไป")
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
            .getLong(KEY_BRANCH_ID, 0L) // Default 0 (Main)
    }

    fun getBranchName(ctx: Context): String {
        return ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BRANCH_NAME, "สาขาทั่วไป") ?: "สาขาทั่วไป"
    }
}