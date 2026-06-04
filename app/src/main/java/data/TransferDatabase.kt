package data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TransferEntity::class, TransferItemEntity::class],
    version = 1,
    exportSchema = false
)
abstract class TransferDatabase : RoomDatabase() {
    abstract fun transferDao(): TransferDao

    companion object {
        @Volatile
        private var INSTANCE: TransferDatabase? = null

        fun getInstance(context: Context): TransferDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TransferDatabase::class.java,
                    "transfer_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}