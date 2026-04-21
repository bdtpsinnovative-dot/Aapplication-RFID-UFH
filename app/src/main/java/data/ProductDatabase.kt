package data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ProductCache(
    val id: Long,
    val name: String,
    val sku: String?,
    val barcode: String?,
    val price: Double,
    val unit: String?,
    val imageUrl: String?,
    val status: String?,
    val color: String?,
    val weight: Double
)

class ProductDatabase(ctx: Context) : SQLiteOpenHelper(ctx, "products_cache.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS products (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL DEFAULT '',
                sku TEXT,
                barcode TEXT,
                price REAL DEFAULT 0,
                unit TEXT,
                image_url TEXT,
                status TEXT,
                color TEXT,
                weight REAL DEFAULT 0,
                synced_at INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_barcode ON products(barcode)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sku ON products(sku)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS products")
        onCreate(db)
    }

    fun upsertBatch(products: List<ProductCache>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for (p in products) {
                db.insertWithOnConflict(
                    "products", null,
                    ContentValues().apply {
                        put("id", p.id)
                        put("name", p.name)
                        put("sku", p.sku)
                        put("barcode", p.barcode)
                        put("price", p.price)
                        put("unit", p.unit)
                        put("image_url", p.imageUrl)
                        put("status", p.status)
                        put("color", p.color)
                        put("weight", p.weight)
                        put("synced_at", now)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun findByCode(code: String): ProductCache? {
        val c = code.trim()
        if (c.isBlank()) return null
        return readableDatabase.rawQuery(
            "SELECT * FROM products WHERE barcode=? OR sku=? OR CAST(id AS TEXT)=? LIMIT 1",
            arrayOf(c, c, c)
        ).use { cur ->
            if (!cur.moveToFirst()) return null
            ProductCache(
                id = cur.getLong(cur.getColumnIndexOrThrow("id")),
                name = cur.getString(cur.getColumnIndexOrThrow("name")) ?: "",
                sku = cur.getString(cur.getColumnIndexOrThrow("sku")),
                barcode = cur.getString(cur.getColumnIndexOrThrow("barcode")),
                price = cur.getDouble(cur.getColumnIndexOrThrow("price")),
                unit = cur.getString(cur.getColumnIndexOrThrow("unit")),
                imageUrl = cur.getString(cur.getColumnIndexOrThrow("image_url")),
                status = cur.getString(cur.getColumnIndexOrThrow("status")),
                color = cur.getString(cur.getColumnIndexOrThrow("color")),
                weight = cur.getDouble(cur.getColumnIndexOrThrow("weight"))
            )
        }
    }

    fun count(): Int = readableDatabase
        .rawQuery("SELECT COUNT(*) FROM products", null)
        .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun lastSyncMs(): Long = readableDatabase
        .rawQuery("SELECT MAX(synced_at) FROM products", null)
        .use { if (it.moveToFirst()) it.getLong(0) else 0L }
}
