
package data
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_stock_transfers")
data class TransferEntity(
    @PrimaryKey
    val id: Long, // ใช้ ID จาก Supabase หรือถ้าทำ Offline-first อาจจะต้องใช้ UUID
    val transferCode: String,
    val fromBranchId: Long,
    val toBranchId: Long,
    val status: String,
    val note: String?,
    val isSynced: Boolean = false // Flag ไว้เช็คว่า sync ขึ้น Supabase หรือยัง
)

@Entity(tableName = "local_stock_transfer_items")
data class TransferItemEntity(
    @PrimaryKey(autoGenerate = true)
    val localId: Long = 0,
    val transferId: Long, // FK โยงไปหา TransferEntity
    val productId: Long,
    val qty: Double,
    val itemStatus: String
)