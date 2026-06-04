package data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ui.ScannedItem

// เปลี่ยนชื่อคลาสและชื่อไฟล์ฐานข้อมูล (transfer_cart.db) ไม่ให้ซ้ำใคร
class TransferCartDatabase(context: Context) : SQLiteOpenHelper(context, "transfer_cart.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS transfer_cart_items (
                id INTEGER PRIMARY KEY,
                name TEXT,
                sku TEXT,
                image_url TEXT,
                qty INTEGER
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS transfer_cart_items")
        onCreate(db)
    }

    // 💾 บันทึกตะกร้าทั้งหมดลงเครื่อง
    fun saveCart(items: List<ScannedItem>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM transfer_cart_items") // เคลียร์ของเก่าทิ้งก่อน
            for (item in items) {
                val values = ContentValues().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("sku", item.sku)
                    put("image_url", item.imageUrl)
                    put("qty", item.qty)
                }
                db.insert("transfer_cart_items", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // 🔄 โหลดตะกร้ากลับมาโชว์บนจอ
    fun loadCart(): List<ScannedItem> {
        val list = mutableListOf<ScannedItem>()
        readableDatabase.rawQuery("SELECT * FROM transfer_cart_items", null).use { cur ->
            while (cur.moveToNext()) {
                list.add(
                    ScannedItem(
                        id = cur.getLong(cur.getColumnIndexOrThrow("id")),
                        name = cur.getString(cur.getColumnIndexOrThrow("name")),
                        sku = cur.getString(cur.getColumnIndexOrThrow("sku")),
                        imageUrl = cur.getString(cur.getColumnIndexOrThrow("image_url")),
                        qty = cur.getInt(cur.getColumnIndexOrThrow("qty")),
                        currentStock = 0
                    )
                )
            }
        }
        return list
    }

    // 🧹 ล้างตะกร้าเมื่อส่งขึ้นคลาวด์สำเร็จ
    fun clearCart() {
        writableDatabase.execSQL("DELETE FROM transfer_cart_items")
    }
}