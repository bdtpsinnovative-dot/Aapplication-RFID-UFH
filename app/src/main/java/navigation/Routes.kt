package navigation

object Routes {
    const val LOGIN = "login"
    const val MENU = "menu"

    const val RECEIVE = "receive"
    const val CHECK_RFID = "check_rfid"
    const val STOCK_COUNT = "stock_count"
    const val COMPARE = "compare"
    const val OTHER1 = "other1"
    const val OTHER2 = "other2"
    const val SPLASH = "splash"
    const val MORE_TRANSFER = "more_transfer"
    const val MORE_ARRANGE = "more_arrange"
    const val MORE_INITIAL_COUNT = "more_initial_count"
    const val MORE_DAMAGE = "more_damage"
    const val MORE_ISSUES = "more_issues"
    const val MORE_UPDATE_SYSTEM = "more_update_system"
    const val MORE_RFID_MANAGE = "more_rfid_manage"

    const val LOT_SELECT  = "lot_select"
    const val LOT_MENU    = "lot_menu/{lotId}/{lotCode}"
    const val LOT_RECEIVE = "lot_receive/{lotId}/{lotCode}"
    const val LOT_CHECK   = "lot_check/{lotId}/{lotCode}"

    fun lotMenu(lotId: Long, lotCode: String)    = "lot_menu/$lotId/${lotCode.encodeUrl()}"
    fun lotReceive(lotId: Long, lotCode: String) = "lot_receive/$lotId/${lotCode.encodeUrl()}"
    fun lotCheck(lotId: Long, lotCode: String)   = "lot_check/$lotId/${lotCode.encodeUrl()}"
}

private fun String.encodeUrl() =
    java.net.URLEncoder.encode(this, "UTF-8")