package data

object SessionManager {

    @Volatile
    var accessToken: String? = null

    @Volatile
    var refreshToken: String? = null

    @Volatile
    var userId: String? = null

    @Volatile
    var email: String? = null

    fun setSession(r: SignInResult) {
        accessToken = r.accessToken
        refreshToken = r.refreshToken
        userId = r.userId
        email = r.email
    }

    fun clear() {
        accessToken = null
        refreshToken = null
        userId = null
        email = null
    }

    fun isLoggedIn(): Boolean {
        return !accessToken.isNullOrBlank()
    }
}
