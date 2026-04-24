package data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ReceiveDraftRow(
    val productId: Long,
    val code: String,
    val name: String,
    val price: Double,
    val qty: Int,
    val stockBefore: Double,
    val imageUrl: String?
)

class DraftDatabase(ctx: Context) : SQLiteOpenHelper(ctx, "drafts.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS receive_draft (
                product_id INTEGER NOT NULL,
                branch_id  INTEGER NOT NULL,
                code       TEXT    NOT NULL DEFAULT '',
                name       TEXT    NOT NULL DEFAULT '',
                price      REAL    DEFAULT 0,
                qty        INTEGER DEFAULT 1,
                stock_before REAL  DEFAULT 0,
                image_url  TEXT,
                PRIMARY KEY (product_id, branch_id)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS rfid_draft (
                product_id INTEGER NOT NULL,
                branch_id  INTEGER NOT NULL,
                rfid       TEXT    NOT NULL,
                PRIMARY KEY (product_id, branch_id, rfid)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS stock_count_draft (
                rfid TEXT PRIMARY KEY
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // เพิ่มตาราง stock_count_draft โดยไม่ทำลายข้อมูลเดิม
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS stock_count_draft (
                    rfid TEXT PRIMARY KEY
                )
                """.trimIndent()
            )
        }
    }

    // ── Receive Draft ────────────────────────────────────────────────────────

    fun saveReceiveDraft(rows: List<ReceiveDraftRow>, branchId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("receive_draft", "branch_id=?", arrayOf(branchId.toString()))
            for (r in rows) {
                db.insert("receive_draft", null, ContentValues().apply {
                    put("product_id",  r.productId)
                    put("branch_id",   branchId)
                    put("code",        r.code)
                    put("name",        r.name)
                    put("price",       r.price)
                    put("qty",         r.qty)
                    put("stock_before", r.stockBefore)
                    put("image_url",   r.imageUrl)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun loadReceiveDraft(branchId: Long): List<ReceiveDraftRow> {
        return readableDatabase.rawQuery(
            "SELECT * FROM receive_draft WHERE branch_id=? ORDER BY rowid ASC",
            arrayOf(branchId.toString())
        ).use { cur ->
            val list = mutableListOf<ReceiveDraftRow>()
            while (cur.moveToNext()) {
                list.add(
                    ReceiveDraftRow(
                        productId  = cur.getLong(cur.getColumnIndexOrThrow("product_id")),
                        code       = cur.getString(cur.getColumnIndexOrThrow("code"))   ?: "",
                        name       = cur.getString(cur.getColumnIndexOrThrow("name"))   ?: "",
                        price      = cur.getDouble(cur.getColumnIndexOrThrow("price")),
                        qty        = cur.getInt(cur.getColumnIndexOrThrow("qty")),
                        stockBefore = cur.getDouble(cur.getColumnIndexOrThrow("stock_before")),
                        imageUrl   = cur.getString(cur.getColumnIndexOrThrow("image_url"))
                    )
                )
            }
            list
        }
    }

    fun clearReceiveDraft(branchId: Long) {
        writableDatabase.delete("receive_draft", "branch_id=?", arrayOf(branchId.toString()))
    }

    // ── RFID Draft ───────────────────────────────────────────────────────────

    fun saveRfidDraft(tags: Map<Long, List<String>>, branchId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("rfid_draft", "branch_id=?", arrayOf(branchId.toString()))
            for ((pid, rfids) in tags) {
                for (rfid in rfids) {
                    db.insert("rfid_draft", null, ContentValues().apply {
                        put("product_id", pid)
                        put("branch_id",  branchId)
                        put("rfid",       rfid)
                    })
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun loadRfidDraft(branchId: Long): Map<Long, List<String>> {
        return readableDatabase.rawQuery(
            "SELECT product_id, rfid FROM rfid_draft WHERE branch_id=?",
            arrayOf(branchId.toString())
        ).use { cur ->
            val map = mutableMapOf<Long, MutableList<String>>()
            while (cur.moveToNext()) {
                val pid  = cur.getLong(cur.getColumnIndexOrThrow("product_id"))
                val rfid = cur.getString(cur.getColumnIndexOrThrow("rfid")) ?: continue
                map.getOrPut(pid) { mutableListOf() }.add(rfid)
            }
            map
        }
    }

    fun clearRfidDraft(branchId: Long) {
        writableDatabase.delete("rfid_draft", "branch_id=?", arrayOf(branchId.toString()))
    }

    // ── Stock Count Draft ────────────────────────────────────────────────────

    fun saveStockCountDraft(rfids: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("stock_count_draft", null, null)
            for (rfid in rfids) {
                db.insertWithOnConflict(
                    "stock_count_draft", null,
                    ContentValues().apply { put("rfid", rfid) },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun loadStockCountDraft(): List<String> {
        return readableDatabase.rawQuery(
            "SELECT rfid FROM stock_count_draft ORDER BY rowid ASC", null
        ).use { cur ->
            val list = mutableListOf<String>()
            while (cur.moveToNext()) list.add(cur.getString(0))
            list
        }
    }

    fun clearStockCountDraft() {
        writableDatabase.delete("stock_count_draft", null, null)
    }
}
