package com.docwallet.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.docwallet.data.model.Document
import net.sqlcipher.database.SupportFactory

@Database(entities = [Document::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class DocWalletDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao

    companion object {
        private const val DB_NAME = "docwallet.db"

        fun create(context: Context, passphrase: ByteArray): DocWalletDatabase {
            val factory = SupportFactory(passphrase)
            return Room.databaseBuilder(
                context.applicationContext,
                DocWalletDatabase::class.java,
                DB_NAME
            )
                .openHelperFactory(factory)
                .build()
        }
    }
}
