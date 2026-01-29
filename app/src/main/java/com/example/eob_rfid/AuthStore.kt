package com.example.eob_rfid

import android.content.Context

class AuthStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE)

    fun save(accessToken: String, refreshToken: String, userId: String, email: String) {
        sp.edit()
            .putString("access_token", accessToken)
            .putString("refresh_token", refreshToken)
            .putString("user_id", userId)
            .putString("email", email)
            .apply()
    }

    fun clear() = sp.edit().clear().apply()

    fun accessToken(): String? = sp.getString("access_token", null)
    fun userId(): String? = sp.getString("user_id", null)
    fun email(): String? = sp.getString("email", null)

    fun isLoggedIn(): Boolean = !accessToken().isNullOrBlank()
}
