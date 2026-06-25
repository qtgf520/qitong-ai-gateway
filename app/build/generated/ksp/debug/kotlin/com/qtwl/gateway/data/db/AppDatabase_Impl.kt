package com.qtwl.gateway.`data`.db

import androidx.room.InvalidationTracker
import androidx.room.RoomOpenDelegate
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.room.util.TableInfo
import androidx.room.util.TableInfo.Companion.read
import androidx.room.util.dropFtsSyncTriggers
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import javax.`annotation`.processing.Generated
import kotlin.Lazy
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.Set
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class AppDatabase_Impl : AppDatabase() {
  private val _providerDao: Lazy<ProviderDao> = lazy {
    ProviderDao_Impl(this)
  }

  private val _aiModelDao: Lazy<AiModelDao> = lazy {
    AiModelDao_Impl(this)
  }

  private val _conversationDao: Lazy<ConversationDao> = lazy {
    ConversationDao_Impl(this)
  }

  private val _chatMessageDao: Lazy<ChatMessageDao> = lazy {
    ChatMessageDao_Impl(this)
  }

  private val _tokenUsageDao: Lazy<TokenUsageDao> = lazy {
    TokenUsageDao_Impl(this)
  }

  protected override fun createOpenDelegate(): RoomOpenDelegate {
    val _openDelegate: RoomOpenDelegate = object : RoomOpenDelegate(5, "fc37ae0086dc544c4814241ffef15b84", "c58001dffbf9668f930cf2cece598013") {
      public override fun createAllTables(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `providers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `base_url` TEXT NOT NULL, `port` TEXT NOT NULL, `api_key` TEXT, `is_enabled` INTEGER NOT NULL, `order_index` INTEGER NOT NULL)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `models` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `provider_id` INTEGER NOT NULL, `model_id` TEXT NOT NULL, `display_name` TEXT NOT NULL, `is_default` INTEGER NOT NULL, `sync_status` TEXT NOT NULL, `is_enabled` INTEGER NOT NULL, `custom_alias` TEXT NOT NULL, `use_proxy` INTEGER NOT NULL, FOREIGN KEY(`provider_id`) REFERENCES `providers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_models_provider_id` ON `models` (`provider_id`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `conversations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `model_id` TEXT, `provider_id` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `message_count` INTEGER NOT NULL, `total_tokens` INTEGER NOT NULL)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `chat_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `conversation_id` INTEGER NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `model_id` TEXT, `provider_id` INTEGER, `prompt_tokens` INTEGER NOT NULL, `completion_tokens` INTEGER NOT NULL, `total_tokens` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `is_streaming` INTEGER NOT NULL, FOREIGN KEY(`conversation_id`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_messages_conversation_id` ON `chat_messages` (`conversation_id`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `token_usage` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `provider_id` INTEGER NOT NULL, `model_id` TEXT NOT NULL, `prompt_tokens` INTEGER NOT NULL, `completion_tokens` INTEGER NOT NULL, `total_tokens` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, FOREIGN KEY(`provider_id`) REFERENCES `providers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_token_usage_provider_id` ON `token_usage` (`provider_id`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_token_usage_timestamp` ON `token_usage` (`timestamp`)")
        connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        connection.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'fc37ae0086dc544c4814241ffef15b84')")
      }

      public override fun dropAllTables(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS `providers`")
        connection.execSQL("DROP TABLE IF EXISTS `models`")
        connection.execSQL("DROP TABLE IF EXISTS `conversations`")
        connection.execSQL("DROP TABLE IF EXISTS `chat_messages`")
        connection.execSQL("DROP TABLE IF EXISTS `token_usage`")
      }

      public override fun onCreate(connection: SQLiteConnection) {
      }

      public override fun onOpen(connection: SQLiteConnection) {
        connection.execSQL("PRAGMA foreign_keys = ON")
        internalInitInvalidationTracker(connection)
      }

      public override fun onPreMigrate(connection: SQLiteConnection) {
        dropFtsSyncTriggers(connection)
      }

      public override fun onPostMigrate(connection: SQLiteConnection) {
      }

      public override fun onValidateSchema(connection: SQLiteConnection): RoomOpenDelegate.ValidationResult {
        val _columnsProviders: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsProviders.put("id", TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsProviders.put("name", TableInfo.Column("name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsProviders.put("type", TableInfo.Column("type", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsProviders.put("base_url", TableInfo.Column("base_url", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsProviders.put("port", TableInfo.Column("port", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsProviders.put("api_key", TableInfo.Column("api_key", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsProviders.put("is_enabled", TableInfo.Column("is_enabled", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsProviders.put("order_index", TableInfo.Column("order_index", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysProviders: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesProviders: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoProviders: TableInfo = TableInfo("providers", _columnsProviders, _foreignKeysProviders, _indicesProviders)
        val _existingProviders: TableInfo = read(connection, "providers")
        if (!_infoProviders.equals(_existingProviders)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |providers(com.qtwl.gateway.data.model.Provider).
              | Expected:
              |""".trimMargin() + _infoProviders + """
              |
              | Found:
              |""".trimMargin() + _existingProviders)
        }
        val _columnsModels: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsModels.put("id", TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsModels.put("provider_id", TableInfo.Column("provider_id", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsModels.put("model_id", TableInfo.Column("model_id", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsModels.put("display_name", TableInfo.Column("display_name", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsModels.put("is_default", TableInfo.Column("is_default", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsModels.put("sync_status", TableInfo.Column("sync_status", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsModels.put("is_enabled", TableInfo.Column("is_enabled", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsModels.put("custom_alias", TableInfo.Column("custom_alias", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsModels.put("use_proxy", TableInfo.Column("use_proxy", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysModels: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        _foreignKeysModels.add(TableInfo.ForeignKey("providers", "CASCADE", "NO ACTION", listOf("provider_id"), listOf("id")))
        val _indicesModels: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesModels.add(TableInfo.Index("index_models_provider_id", false, listOf("provider_id"), listOf("ASC")))
        val _infoModels: TableInfo = TableInfo("models", _columnsModels, _foreignKeysModels, _indicesModels)
        val _existingModels: TableInfo = read(connection, "models")
        if (!_infoModels.equals(_existingModels)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |models(com.qtwl.gateway.data.model.AiModel).
              | Expected:
              |""".trimMargin() + _infoModels + """
              |
              | Found:
              |""".trimMargin() + _existingModels)
        }
        val _columnsConversations: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsConversations.put("id", TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConversations.put("title", TableInfo.Column("title", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConversations.put("model_id", TableInfo.Column("model_id", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConversations.put("provider_id", TableInfo.Column("provider_id", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConversations.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConversations.put("updated_at", TableInfo.Column("updated_at", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConversations.put("message_count", TableInfo.Column("message_count", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsConversations.put("total_tokens", TableInfo.Column("total_tokens", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysConversations: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesConversations: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoConversations: TableInfo = TableInfo("conversations", _columnsConversations, _foreignKeysConversations, _indicesConversations)
        val _existingConversations: TableInfo = read(connection, "conversations")
        if (!_infoConversations.equals(_existingConversations)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |conversations(com.qtwl.gateway.data.model.Conversation).
              | Expected:
              |""".trimMargin() + _infoConversations + """
              |
              | Found:
              |""".trimMargin() + _existingConversations)
        }
        val _columnsChatMessages: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsChatMessages.put("id", TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsChatMessages.put("conversation_id", TableInfo.Column("conversation_id", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsChatMessages.put("role", TableInfo.Column("role", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsChatMessages.put("content", TableInfo.Column("content", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsChatMessages.put("model_id", TableInfo.Column("model_id", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsChatMessages.put("provider_id", TableInfo.Column("provider_id", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsChatMessages.put("prompt_tokens", TableInfo.Column("prompt_tokens", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsChatMessages.put("completion_tokens", TableInfo.Column("completion_tokens", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsChatMessages.put("total_tokens", TableInfo.Column("total_tokens", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsChatMessages.put("timestamp", TableInfo.Column("timestamp", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsChatMessages.put("is_streaming", TableInfo.Column("is_streaming", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysChatMessages: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        _foreignKeysChatMessages.add(TableInfo.ForeignKey("conversations", "CASCADE", "NO ACTION", listOf("conversation_id"), listOf("id")))
        val _indicesChatMessages: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesChatMessages.add(TableInfo.Index("index_chat_messages_conversation_id", false, listOf("conversation_id"), listOf("ASC")))
        val _infoChatMessages: TableInfo = TableInfo("chat_messages", _columnsChatMessages, _foreignKeysChatMessages, _indicesChatMessages)
        val _existingChatMessages: TableInfo = read(connection, "chat_messages")
        if (!_infoChatMessages.equals(_existingChatMessages)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |chat_messages(com.qtwl.gateway.data.model.ChatMessage).
              | Expected:
              |""".trimMargin() + _infoChatMessages + """
              |
              | Found:
              |""".trimMargin() + _existingChatMessages)
        }
        val _columnsTokenUsage: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsTokenUsage.put("id", TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTokenUsage.put("provider_id", TableInfo.Column("provider_id", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTokenUsage.put("model_id", TableInfo.Column("model_id", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTokenUsage.put("prompt_tokens", TableInfo.Column("prompt_tokens", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTokenUsage.put("completion_tokens", TableInfo.Column("completion_tokens", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTokenUsage.put("total_tokens", TableInfo.Column("total_tokens", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTokenUsage.put("timestamp", TableInfo.Column("timestamp", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysTokenUsage: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        _foreignKeysTokenUsage.add(TableInfo.ForeignKey("providers", "CASCADE", "NO ACTION", listOf("provider_id"), listOf("id")))
        val _indicesTokenUsage: MutableSet<TableInfo.Index> = mutableSetOf()
        _indicesTokenUsage.add(TableInfo.Index("index_token_usage_provider_id", false, listOf("provider_id"), listOf("ASC")))
        _indicesTokenUsage.add(TableInfo.Index("index_token_usage_timestamp", false, listOf("timestamp"), listOf("ASC")))
        val _infoTokenUsage: TableInfo = TableInfo("token_usage", _columnsTokenUsage, _foreignKeysTokenUsage, _indicesTokenUsage)
        val _existingTokenUsage: TableInfo = read(connection, "token_usage")
        if (!_infoTokenUsage.equals(_existingTokenUsage)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |token_usage(com.qtwl.gateway.data.model.TokenUsage).
              | Expected:
              |""".trimMargin() + _infoTokenUsage + """
              |
              | Found:
              |""".trimMargin() + _existingTokenUsage)
        }
        return RoomOpenDelegate.ValidationResult(true, null)
      }
    }
    return _openDelegate
  }

  protected override fun createInvalidationTracker(): InvalidationTracker {
    val _shadowTablesMap: MutableMap<String, String> = mutableMapOf()
    val _viewTables: MutableMap<String, Set<String>> = mutableMapOf()
    return InvalidationTracker(this, _shadowTablesMap, _viewTables, "providers", "models", "conversations", "chat_messages", "token_usage")
  }

  public override fun clearAllTables() {
    super.performClear(true, "providers", "models", "conversations", "chat_messages", "token_usage")
  }

  protected override fun getRequiredTypeConverterClasses(): Map<KClass<*>, List<KClass<*>>> {
    val _typeConvertersMap: MutableMap<KClass<*>, List<KClass<*>>> = mutableMapOf()
    _typeConvertersMap.put(ProviderDao::class, ProviderDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(AiModelDao::class, AiModelDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(ConversationDao::class, ConversationDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(ChatMessageDao::class, ChatMessageDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(TokenUsageDao::class, TokenUsageDao_Impl.getRequiredConverters())
    return _typeConvertersMap
  }

  public override fun getRequiredAutoMigrationSpecClasses(): Set<KClass<out AutoMigrationSpec>> {
    val _autoMigrationSpecsSet: MutableSet<KClass<out AutoMigrationSpec>> = mutableSetOf()
    return _autoMigrationSpecsSet
  }

  public override fun createAutoMigrations(autoMigrationSpecs: Map<KClass<out AutoMigrationSpec>, AutoMigrationSpec>): List<Migration> {
    val _autoMigrations: MutableList<Migration> = mutableListOf()
    return _autoMigrations
  }

  public override fun providerDao(): ProviderDao = _providerDao.value

  public override fun aiModelDao(): AiModelDao = _aiModelDao.value

  public override fun conversationDao(): ConversationDao = _conversationDao.value

  public override fun chatMessageDao(): ChatMessageDao = _chatMessageDao.value

  public override fun tokenUsageDao(): TokenUsageDao = _tokenUsageDao.value
}
