package data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface TransferDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransfer(transfer: TransferEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransferItems(items: List<TransferItemEntity>)

    @Transaction
    @Query("SELECT * FROM local_stock_transfers WHERE status = 'DRAFT'")
    suspend fun getDraftTransfers(): List<TransferEntity>

    // 💡 คำสั่งใหม่ที่หน้าจอต้องการเรียกใช้
    @Query("SELECT * FROM local_stock_transfer_items WHERE transferId = :transferId")
    suspend fun getTransferItemsByTransferId(transferId: Long): List<TransferItemEntity>

    @Query("DELETE FROM local_stock_transfers WHERE id = :transferId")
    suspend fun deleteTransferById(transferId: Long)

    @Query("DELETE FROM local_stock_transfer_items WHERE transferId = :transferId")
    suspend fun deleteTransferItemsByTransferId(transferId: Long)
}