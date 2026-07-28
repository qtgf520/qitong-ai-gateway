package com.qtwl.gateway.data.db

import android.content.ContentValues
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.qtwl.gateway.data.model.AiModel
import com.qtwl.gateway.data.model.ChatMessage
import com.qtwl.gateway.data.model.Conversation
import com.qtwl.gateway.data.model.Provider
import com.qtwl.gateway.data.model.TokenUsage
import com.qtwl.gateway.data.model.SpeedHistory

    @Database(
entities = [
    Provider::class,
    AiModel::class,
    Conversation::class,
    ChatMessage::class,
    TokenUsage::class,
    SpeedHistory::class
],
version = 8,
exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun aiModelDao(): AiModelDao
    abstract fun conversationDao(): ConversationDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun tokenUsageDao(): TokenUsageDao
    abstract fun speedHistoryDao(): SpeedHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ai_gateway.db"
                )
                    .addMigrations(MIGRATION_2_TO_3)
                .addMigrations(MIGRATION_3_TO_4)
                .addMigrations(MIGRATION_5_TO_6)
                .addMigrations(MIGRATION_6_TO_7)
                .addMigrations(MIGRATION_7_TO_8)
//                .addMigrations(MIGRATION_4_TO_5)// 已被 MIGRATION_5_TO_6 覆盖
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_2_TO_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE providers ADD COLUMN port TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_TO_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 添加模型启用/暂停字段
                db.execSQL("ALTER TABLE models ADD COLUMN is_enabled INTEGER NOT NULL DEFAULT 1")
                // 添加自定义别名字段
                db.execSQL("ALTER TABLE models ADD COLUMN custom_alias TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_5_TO_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE providers ADD COLUMN chat_path TEXT")
        db.execSQL("ALTER TABLE providers ADD COLUMN supports_system_role INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE models ADD COLUMN context_window INTEGER NOT NULL DEFAULT 4096")
    }
}

        private val MIGRATION_6_TO_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE token_usage ADD COLUMN upload_bytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE token_usage ADD COLUMN download_bytes INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_7_TO_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS speed_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        model_key TEXT NOT NULL,
                        model_name TEXT NOT NULL,
                        provider_id INTEGER NOT NULL,
                        ttft_ms INTEGER NOT NULL,
                        tps REAL NOT NULL,
                        total_ms INTEGER NOT NULL,
                        success INTEGER NOT NULL,
                        measured_at INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_speed_history_model_key ON speed_history(model_key)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_speed_history_measured_at ON speed_history(measured_at)")
            }
        }
    }
}