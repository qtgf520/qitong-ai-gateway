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

    @Database(
    entities = [
        Provider::class,
        AiModel::class,
        Conversation::class,
        ChatMessage::class,
        TokenUsage::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun aiModelDao(): AiModelDao
    abstract fun conversationDao(): ConversationDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun tokenUsageDao(): TokenUsageDao

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
                    .addMigrations(MIGRATION_4_TO_5)
                    .fallbackToDestructiveMigration()
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

        private val MIGRATION_4_TO_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 添加按模型粒度控制代理字段（默认=1 走代理）
                db.execSQL("ALTER TABLE models ADD COLUMN use_proxy INTEGER NOT NULL DEFAULT 1")
            }
        }
    }
}