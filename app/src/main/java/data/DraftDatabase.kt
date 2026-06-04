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

data class LotReceiveDraftRow(
    val productId: Long,
    val lotId: Long,
    val lotItemId: Long,
    val code: String,
    val name: String,
    val imageUrl: String?,
    val qty: Int,
    val expectedQty: Int
)

class DraftDatabase(ctx: Context) : SQLiteOpenHelper(ctx, "drafts.db", null, 4) {

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
        db.execSQL(CREATE_LOT_DRAFT)
        db.execSQL(CREATE_RFID_BATCH_DRAFT)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS stock_count_draft (rfid TEXT PRIMARY KEY)"
            )
        }
        if (oldVersion < 3) {
            db.execSQL(CREATE_LOT_DRAFT)
        }
        if (oldVersion < 4) {
            db.execSQL(CREATE_RFID_BATCH_DRAFT)
        }
    }

    companion object {
        private const val CREATE_RFID_BATCH_DRAFT = """
            CREATE TABLE IF NOT EXISTS rfid_batch_draft (
                batch_num  INTEGER NOT NULL,
                branch_id  INTEGER NOT NULL,
                product_id INTEGER NOT NULL,
                rfid       TEXT    NOT NULL,
                PRIMARY KEY (batch_num, branch_id, product_id, rfid)
            )
        """
        private const val CREATE_LOT_DRAFT = """
            CREATE TABLE IF NOT EXISTS lot_receive_draft (
                product_id   INTEGER NOT NULL,
                branch_id    INTEGER NOT NULL,
                lot_id       INTEGER NOT NULL,
                lot_item_id  INTEGER NOT NULL,
                code         TEXT NOT NULL DEFAULT '',
                name         TEXT NOT NULL DEFAULT '',
                image_url    TEXT,
                qty          INTEGER DEFAULT 0,
                expected_qty INTEGER DEFAULT 0,
                PRIMARY KEY (product_id, branch_id, lot_id)
            )
        """
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

    // ── Lot Receive Draft ────────────────────────────────────────────────────

    // replace ทั้งหมดของ lot นั้น (ใช้กับ snapshotFlow auto-save)
    fun setLotReceiveDraft(rows: List<LotReceiveDraftRow>, branchId: Long, lotId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("lot_receive_draft", "branch_id=? AND lot_id=?",
                arrayOf(branchId.toString(), lotId.toString()))
            for (r in rows) {
                db.insert("lot_receive_draft", null, ContentValues().apply {
                    put("product_id",   r.productId)
                    put("branch_id",    branchId)
                    put("lot_id",       r.lotId)
                    put("lot_item_id",  r.lotItemId)
                    put("code",         r.code)
                    put("name",         r.name)
                    put("image_url",    r.imageUrl)
                    put("qty",          r.qty)
                    put("expected_qty", r.expectedQty)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // cumulative add ชิ้นเดียว (ยังคงไว้เผื่อใช้)
    fun saveLotReceiveItem(row: LotReceiveDraftRow, branchId: Long) {
        val db = writableDatabase
        val existing = db.rawQuery(
            "SELECT qty FROM lot_receive_draft WHERE product_id=? AND branch_id=? AND lot_id=?",
            arrayOf(row.productId.toString(), branchId.toString(), row.lotId.toString())
        ).use { cur -> if (cur.moveToFirst()) cur.getInt(0) else -1 }

        if (existing >= 0) {
            db.execSQL(
                "UPDATE lot_receive_draft SET qty = qty + ? WHERE product_id=? AND branch_id=? AND lot_id=?",
                arrayOf(row.qty, row.productId, branchId, row.lotId)
            )
        } else {
            db.insert("lot_receive_draft", null, ContentValues().apply {
                put("product_id",   row.productId)
                put("branch_id",    branchId)
                put("lot_id",       row.lotId)
                put("lot_item_id",  row.lotItemId)
                put("code",         row.code)
                put("name",         row.name)
                put("image_url",    row.imageUrl)
                put("qty",          row.qty)
                put("expected_qty", row.expectedQty)
            })
        }
    }

    fun getLotReceivedQty(productId: Long, branchId: Long, lotId: Long): Int {
        return readableDatabase.rawQuery(
            "SELECT qty FROM lot_receive_draft WHERE product_id=? AND branch_id=? AND lot_id=?",
            arrayOf(productId.toString(), branchId.toString(), lotId.toString())
        ).use { cur -> if (cur.moveToFirst()) cur.getInt(0) else 0 }
    }

    fun loadLotReceiveDraft(branchId: Long, lotId: Long): List<LotReceiveDraftRow> {
        return readableDatabase.rawQuery(
            "SELECT * FROM lot_receive_draft WHERE branch_id=? AND lot_id=? ORDER BY rowid ASC",
            arrayOf(branchId.toString(), lotId.toString())
        ).use { cur ->
            val list = mutableListOf<LotReceiveDraftRow>()
            while (cur.moveToNext()) {
                list.add(LotReceiveDraftRow(
                    productId   = cur.getLong(cur.getColumnIndexOrThrow("product_id")),
                    lotId       = cur.getLong(cur.getColumnIndexOrThrow("lot_id")),
                    lotItemId   = cur.getLong(cur.getColumnIndexOrThrow("lot_item_id")),
                    code        = cur.getString(cur.getColumnIndexOrThrow("code")) ?: "",
                    name        = cur.getString(cur.getColumnIndexOrThrow("name")) ?: "",
                    imageUrl    = cur.getString(cur.getColumnIndexOrThrow("image_url")),
                    qty         = cur.getInt(cur.getColumnIndexOrThrow("qty")),
                    expectedQty = cur.getInt(cur.getColumnIndexOrThrow("expected_qty"))
                ))
            }
            list
        }
    }

    fun clearLotReceiveDraft(branchId: Long, lotId: Long) {
        writableDatabase.delete(
            "lot_receive_draft",
            "branch_id=? AND lot_id=?",
            arrayOf(branchId.toString(), lotId.toString())
        )
    }

    // ── RFID Batch Draft ─────────────────────────────────────────────────────

    fun saveRfidBatches(batches: List<Pair<Int, Map<Long, List<String>>>>, branchId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("rfid_batch_draft", "branch_id=?", arrayOf(branchId.toString()))
            for ((batchNum, tags) in batches) {
                for ((pid, rfids) in tags) {
                    for (rfid in rfids) {
                        db.insert("rfid_batch_draft", null, ContentValues().apply {
                            put("batch_num",  batchNum)
                            put("branch_id",  branchId)
                            put("product_id", pid)
                            put("rfid",       rfid)
                        })
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // คืน Pair<maxBatchNum, Map<batchNum → Map<productId → [rfids]>>>
    fun loadRfidBatches(branchId: Long): Pair<Int, Map<Int, Map<Long, List<String>>>> {
        val map = mutableMapOf<Int, MutableMap<Long, MutableList<String>>>()
        readableDatabase.rawQuery(
            "SELECT batch_num, product_id, rfid FROM rfid_batch_draft WHERE branch_id=? ORDER BY batch_num ASC",
            arrayOf(branchId.toString())
        ).use { cur ->
            while (cur.moveToNext()) {
                val batchNum = cur.getInt(0)
                val pid      = cur.getLong(1)
                val rfid     = cur.getString(2) ?: continue
                map.getOrPut(batchNum) { mutableMapOf() }
                    .getOrPut(pid) { mutableListOf() }
                    .add(rfid)
            }
        }
        val maxBatchNum = if (map.isEmpty()) 0 else map.keys.max()
        return Pair(maxBatchNum, map)
    }

    fun clearRfidBatches(branchId: Long) {
        writableDatabase.delete("rfid_batch_draft", "branch_id=?", arrayOf(branchId.toString()))
    }
}
