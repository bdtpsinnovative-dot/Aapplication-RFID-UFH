package ui

import androidx.compose.ui.graphics.Color

internal val ColorPrimary = Color(0xFF2563EB)
internal val ColorPrimarySoft = Color(0xFFEFF6FF)
internal val ColorSuccess = Color(0xFF10B981)
internal val ColorSuccessSoft = Color(0xFFECFDF5)
internal val ColorWarning = Color(0xFFF59E0B)
internal val ColorWarningSoft = Color(0xFFFFFBEB)
internal val ColorTextMain = Color(0xFF1E293B)
internal val ColorTextSec = Color(0xFF64748B)
internal val ColorBg = Color(0xFFF8FAFC)

data class RfidBatch(val batchNum: Int, val tags: Map<Long, List<String>>)

internal sealed class RfidScreenMode {
    object PickMode : RfidScreenMode()
    object PickLot : RfidScreenMode()
    data class Tagging(val lotId: Long?, val lotCode: String?) : RfidScreenMode()
}
