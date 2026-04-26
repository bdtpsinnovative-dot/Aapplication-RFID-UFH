package data

object AppError {

    fun resolve(e: Throwable): String {
        val msg = e.message ?: ""
        return when {

            // ── เน็ต / การเชื่อมต่อ ────────────────────────────────────────
            isNoNetwork(e)  -> "ไม่มีการเชื่อมต่ออินเตอร์เน็ต"
            isTimeout(e)    -> "การเชื่อมต่อใช้เวลานานเกินไป กรุณาลองใหม่"
            isSSL(e)        -> "การเชื่อมต่อไม่ปลอดภัย (SSL) กรุณาตรวจสอบเน็ต"

            // ── Session / Auth ────────────────────────────────────────────────
            hasAny(msg, "401", "JWT", "PGRST303", "token is expired",
                          "invalid JWT", "session หมดอายุ", "กรุณาล็อกอินใหม่",
                          "กรุณาเข้าสู่ระบบใหม่") ->
                "Session หมดอายุ กรุณาเข้าสู่ระบบใหม่"

            hasAny(msg, "Invalid login credentials", "invalid_grant",
                          "invalid email or password", "wrong password") ->
                "อีเมลหรือรหัสผ่านไม่ถูกต้อง"

            hasAny(msg, "Email not confirmed", "email not confirmed") ->
                "กรุณายืนยันอีเมลก่อนเข้าสู่ระบบ"

            hasAny(msg, "User not found", "user not found") ->
                "ไม่พบบัญชีผู้ใช้นี้ในระบบ"

            hasAny(msg, "ไม่พบ branchId", "ไม่พบข้อมูลสาขา") ->
                "ไม่พบข้อมูลสาขา กรุณาเข้าสู่ระบบใหม่"

            // ── สิทธิ์ (RLS / 403) ─────────────────────────────────────────
            hasAny(msg, "42501", "row-level security", "violates row-level") ->
                "ไม่มีสิทธิ์ดำเนินการนี้ (กรุณาตรวจสอบ Policy)"

            msg.contains("403") ->
                "ไม่มีสิทธิ์เข้าถึงข้อมูลนี้"

            // ── HTTP codes ───────────────────────────────────────────────────
            msg.contains("404") -> "ไม่พบข้อมูลที่ต้องการ"
            msg.contains("409") -> "ข้อมูลซ้ำกันในระบบ"
            msg.contains("422") -> "ข้อมูลที่ส่งไม่ถูกต้อง"
            hasAny(msg, "500", "502", "503") ->
                "เซิร์ฟเวอร์เกิดข้อผิดพลาด กรุณาลองใหม่ภายหลัง"

            // ── ข้อมูลซ้ำ / unique constraint ───────────────────────────────
            hasAny(msg, "unique constraint", "duplicate key", "23505") ->
                "ข้อมูลนี้มีอยู่ในระบบแล้ว"

            // ── Lot / Stock ──────────────────────────────────────────────────
            hasAny(msg, "สร้าง lot อัตโนมัติไม่สำเร็จ") ->
                "สร้างรหัสลอตอัตโนมัติไม่สำเร็จ กรุณาลองใหม่"

            hasAny(msg, "อัปเดต lot status ไม่สำเร็จ") ->
                "อัปเดตสถานะลอตไม่สำเร็จ"

            hasAny(msg, "บันทึกแท็กไม่สำเร็จ") ->
                "บันทึก RFID Tag ไม่สำเร็จ"

            hasAny(msg, "อัปเดต stock ไม่สำเร็จ", "concurrent conflict") ->
                "อัปเดตสต็อกไม่สำเร็จ (ข้อมูลถูกแก้ไขพร้อมกัน) กรุณาลองใหม่"

            hasAny(msg, "ล้าง stock_receiving ไม่สำเร็จ") ->
                "ล้างรายการรับเข้าไม่สำเร็จ"

            hasAny(msg, "บันทึก movement ไม่สำเร็จ") ->
                "บันทึกประวัติการเคลื่อนไหวไม่สำเร็จ"

            // ── Thai messages ส่งต่อตรงๆ ────────────────────────────────────
            msg.matches(Regex(".*[\u0E00-\u0E7F].*")) -> msg

            // ── Fallback ─────────────────────────────────────────────────────
            msg.isNotBlank() -> "เกิดข้อผิดพลาด: ${msg.take(80)}"
            else -> "เกิดข้อผิดพลาดที่ไม่ทราบสาเหตุ กรุณาลองใหม่"
        }
    }

    private fun hasAny(msg: String, vararg keywords: String): Boolean =
        keywords.any { msg.contains(it, ignoreCase = true) }

    private fun isNoNetwork(e: Throwable): Boolean {
        val cls = e.javaClass.name
        return cls.contains("UnknownHostException") ||
               cls.contains("ConnectException") ||
               hasAny(e.message ?: "", "Unable to resolve host", "Failed to connect",
                      "No address associated", "Network is unreachable")
    }

    private fun isTimeout(e: Throwable): Boolean {
        val cls = e.javaClass.name
        return cls.contains("SocketTimeoutException") ||
               cls.contains("TimeoutException") ||
               hasAny(e.message ?: "", "timeout", "timed out")
    }

    private fun isSSL(e: Throwable): Boolean {
        val cls = e.javaClass.name
        return cls.contains("SSLException") ||
               cls.contains("CertPathValidatorException") ||
               cls.contains("SSLHandshakeException")
    }
}
