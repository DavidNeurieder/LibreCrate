package com.docwallet.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.docwallet.data.model.Document
import com.docwallet.vault.database.DatabaseSchema
import net.sqlcipher.database.SupportFactory

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.docwallet.data.model.Collection
import com.docwallet.data.model.DocumentTag
import com.docwallet.data.model.Tag

@Database(entities = [Document::class, Tag::class, Collection::class, DocumentTag::class], version = 5, exportSchema = false)
@TypeConverters(Converters::class)
abstract class DocWalletDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun tagDao(): TagDao
    abstract fun collectionDao(): CollectionDao
    abstract fun documentTagDao(): DocumentTagDao

    companion object {
        private const val DB_NAME = "docwallet.db"

        internal val FTS_CALLBACK = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                db.execSQL(DatabaseSchema.CREATE_DOCUMENTS_FTS_TABLE)
                db.execSQL(DatabaseSchema.CREATE_FTS_TRIGGER_INSERT)
                db.execSQL(DatabaseSchema.CREATE_FTS_TRIGGER_DELETE)
                db.execSQL(DatabaseSchema.CREATE_FTS_TRIGGER_UPDATE)
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tags (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL UNIQUE,
                        color INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS collections (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        icon TEXT NOT NULL,
                        sort_order INTEGER NOT NULL,
                        parent_id TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS document_tags (
                        document_id TEXT NOT NULL,
                        tag_id TEXT NOT NULL,
                        PRIMARY KEY (document_id, tag_id),
                        FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE,
                        FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_document_tags_document_id ON document_tags(document_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_document_tags_tag_id ON document_tags(tag_id)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN current_page INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE documents ADD COLUMN reading_position TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN modified_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE documents ADD COLUMN is_conflict INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE documents ADD COLUMN conflict_with TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(DatabaseSchema.CREATE_DOCUMENTS_FTS_TABLE)
                db.execSQL(DatabaseSchema.CREATE_FTS_TRIGGER_INSERT)
                db.execSQL(DatabaseSchema.CREATE_FTS_TRIGGER_DELETE)
                db.execSQL(DatabaseSchema.CREATE_FTS_TRIGGER_UPDATE)
                db.execSQL(DatabaseSchema.REBUILD_FTS_INDEX)
            }
        }

        fun create(context: Context, passphrase: ByteArray): DocWalletDatabase {
            val factory = SupportFactory(passphrase, null, false)
            return Room.databaseBuilder(
                context.applicationContext,
                DocWalletDatabase::class.java,
                DB_NAME
            )
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .addCallback(FTS_CALLBACK)
                .build()
        }
    }
}
