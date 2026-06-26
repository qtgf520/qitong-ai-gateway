package com.qtwl.gateway.`data`.db

import androidx.room.EntityDeleteOrUpdateAdapter
import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import com.qtwl.gateway.`data`.model.Conversation
import javax.`annotation`.processing.Generated
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class ConversationDao_Impl(
  __db: RoomDatabase,
) : ConversationDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfConversation: EntityInsertAdapter<Conversation>

  private val __deleteAdapterOfConversation: EntityDeleteOrUpdateAdapter<Conversation>

  private val __updateAdapterOfConversation: EntityDeleteOrUpdateAdapter<Conversation>
  init {
    this.__db = __db
    this.__insertAdapterOfConversation = object : EntityInsertAdapter<Conversation>() {
      protected override fun createQuery(): String = "INSERT OR REPLACE INTO `conversations` (`id`,`title`,`model_id`,`provider_id`,`created_at`,`updated_at`,`message_count`,`total_tokens`) VALUES (nullif(?, 0),?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: Conversation) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.title)
        val _tmpModelId: String? = entity.modelId
        if (_tmpModelId == null) {
          statement.bindNull(3)
        } else {
          statement.bindText(3, _tmpModelId)
        }
        val _tmpProviderId: Long? = entity.providerId
        if (_tmpProviderId == null) {
          statement.bindNull(4)
        } else {
          statement.bindLong(4, _tmpProviderId)
        }
        statement.bindLong(5, entity.createdAt)
        statement.bindLong(6, entity.updatedAt)
        statement.bindLong(7, entity.messageCount.toLong())
        statement.bindLong(8, entity.totalTokens.toLong())
      }
    }
    this.__deleteAdapterOfConversation = object : EntityDeleteOrUpdateAdapter<Conversation>() {
      protected override fun createQuery(): String = "DELETE FROM `conversations` WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: Conversation) {
        statement.bindLong(1, entity.id)
      }
    }
    this.__updateAdapterOfConversation = object : EntityDeleteOrUpdateAdapter<Conversation>() {
      protected override fun createQuery(): String = "UPDATE OR ABORT `conversations` SET `id` = ?,`title` = ?,`model_id` = ?,`provider_id` = ?,`created_at` = ?,`updated_at` = ?,`message_count` = ?,`total_tokens` = ? WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: Conversation) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.title)
        val _tmpModelId: String? = entity.modelId
        if (_tmpModelId == null) {
          statement.bindNull(3)
        } else {
          statement.bindText(3, _tmpModelId)
        }
        val _tmpProviderId: Long? = entity.providerId
        if (_tmpProviderId == null) {
          statement.bindNull(4)
        } else {
          statement.bindLong(4, _tmpProviderId)
        }
        statement.bindLong(5, entity.createdAt)
        statement.bindLong(6, entity.updatedAt)
        statement.bindLong(7, entity.messageCount.toLong())
        statement.bindLong(8, entity.totalTokens.toLong())
        statement.bindLong(9, entity.id)
      }
    }
  }

  public override suspend fun insert(conversation: Conversation): Long = performSuspending(__db, false, true) { _connection ->
    val _result: Long = __insertAdapterOfConversation.insertAndReturnId(_connection, conversation)
    _result
  }

  public override suspend fun insertAll(conversations: List<Conversation>): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfConversation.insert(_connection, conversations)
  }

  public override suspend fun delete(conversation: Conversation): Unit = performSuspending(__db, false, true) { _connection ->
    __deleteAdapterOfConversation.handle(_connection, conversation)
  }

  public override suspend fun update(conversation: Conversation): Unit = performSuspending(__db, false, true) { _connection ->
    __updateAdapterOfConversation.handle(_connection, conversation)
  }

  public override fun getAllConversations(): Flow<List<Conversation>> {
    val _sql: String = "SELECT * FROM conversations ORDER BY updated_at DESC"
    return createFlow(__db, false, arrayOf("conversations")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTitle: Int = getColumnIndexOrThrow(_stmt, "title")
        val _columnIndexOfModelId: Int = getColumnIndexOrThrow(_stmt, "model_id")
        val _columnIndexOfProviderId: Int = getColumnIndexOrThrow(_stmt, "provider_id")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _columnIndexOfMessageCount: Int = getColumnIndexOrThrow(_stmt, "message_count")
        val _columnIndexOfTotalTokens: Int = getColumnIndexOrThrow(_stmt, "total_tokens")
        val _result: MutableList<Conversation> = mutableListOf()
        while (_stmt.step()) {
          val _item: Conversation
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpModelId: String?
          if (_stmt.isNull(_columnIndexOfModelId)) {
            _tmpModelId = null
          } else {
            _tmpModelId = _stmt.getText(_columnIndexOfModelId)
          }
          val _tmpProviderId: Long?
          if (_stmt.isNull(_columnIndexOfProviderId)) {
            _tmpProviderId = null
          } else {
            _tmpProviderId = _stmt.getLong(_columnIndexOfProviderId)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          val _tmpMessageCount: Int
          _tmpMessageCount = _stmt.getLong(_columnIndexOfMessageCount).toInt()
          val _tmpTotalTokens: Int
          _tmpTotalTokens = _stmt.getLong(_columnIndexOfTotalTokens).toInt()
          _item = Conversation(_tmpId,_tmpTitle,_tmpModelId,_tmpProviderId,_tmpCreatedAt,_tmpUpdatedAt,_tmpMessageCount,_tmpTotalTokens)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getAllConversationsList(): List<Conversation> {
    val _sql: String = "SELECT * FROM conversations ORDER BY updated_at DESC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTitle: Int = getColumnIndexOrThrow(_stmt, "title")
        val _columnIndexOfModelId: Int = getColumnIndexOrThrow(_stmt, "model_id")
        val _columnIndexOfProviderId: Int = getColumnIndexOrThrow(_stmt, "provider_id")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _columnIndexOfMessageCount: Int = getColumnIndexOrThrow(_stmt, "message_count")
        val _columnIndexOfTotalTokens: Int = getColumnIndexOrThrow(_stmt, "total_tokens")
        val _result: MutableList<Conversation> = mutableListOf()
        while (_stmt.step()) {
          val _item: Conversation
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpModelId: String?
          if (_stmt.isNull(_columnIndexOfModelId)) {
            _tmpModelId = null
          } else {
            _tmpModelId = _stmt.getText(_columnIndexOfModelId)
          }
          val _tmpProviderId: Long?
          if (_stmt.isNull(_columnIndexOfProviderId)) {
            _tmpProviderId = null
          } else {
            _tmpProviderId = _stmt.getLong(_columnIndexOfProviderId)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          val _tmpMessageCount: Int
          _tmpMessageCount = _stmt.getLong(_columnIndexOfMessageCount).toInt()
          val _tmpTotalTokens: Int
          _tmpTotalTokens = _stmt.getLong(_columnIndexOfTotalTokens).toInt()
          _item = Conversation(_tmpId,_tmpTitle,_tmpModelId,_tmpProviderId,_tmpCreatedAt,_tmpUpdatedAt,_tmpMessageCount,_tmpTotalTokens)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getConversationById(id: Long): Conversation? {
    val _sql: String = "SELECT * FROM conversations WHERE id = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, id)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTitle: Int = getColumnIndexOrThrow(_stmt, "title")
        val _columnIndexOfModelId: Int = getColumnIndexOrThrow(_stmt, "model_id")
        val _columnIndexOfProviderId: Int = getColumnIndexOrThrow(_stmt, "provider_id")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _columnIndexOfMessageCount: Int = getColumnIndexOrThrow(_stmt, "message_count")
        val _columnIndexOfTotalTokens: Int = getColumnIndexOrThrow(_stmt, "total_tokens")
        val _result: Conversation?
        if (_stmt.step()) {
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpModelId: String?
          if (_stmt.isNull(_columnIndexOfModelId)) {
            _tmpModelId = null
          } else {
            _tmpModelId = _stmt.getText(_columnIndexOfModelId)
          }
          val _tmpProviderId: Long?
          if (_stmt.isNull(_columnIndexOfProviderId)) {
            _tmpProviderId = null
          } else {
            _tmpProviderId = _stmt.getLong(_columnIndexOfProviderId)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          val _tmpMessageCount: Int
          _tmpMessageCount = _stmt.getLong(_columnIndexOfMessageCount).toInt()
          val _tmpTotalTokens: Int
          _tmpTotalTokens = _stmt.getLong(_columnIndexOfTotalTokens).toInt()
          _result = Conversation(_tmpId,_tmpTitle,_tmpModelId,_tmpProviderId,_tmpCreatedAt,_tmpUpdatedAt,_tmpMessageCount,_tmpTotalTokens)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getAllConversationsOnce(): List<Conversation> {
    val _sql: String = "SELECT * FROM conversations ORDER BY updated_at DESC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfTitle: Int = getColumnIndexOrThrow(_stmt, "title")
        val _columnIndexOfModelId: Int = getColumnIndexOrThrow(_stmt, "model_id")
        val _columnIndexOfProviderId: Int = getColumnIndexOrThrow(_stmt, "provider_id")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfUpdatedAt: Int = getColumnIndexOrThrow(_stmt, "updated_at")
        val _columnIndexOfMessageCount: Int = getColumnIndexOrThrow(_stmt, "message_count")
        val _columnIndexOfTotalTokens: Int = getColumnIndexOrThrow(_stmt, "total_tokens")
        val _result: MutableList<Conversation> = mutableListOf()
        while (_stmt.step()) {
          val _item: Conversation
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpTitle: String
          _tmpTitle = _stmt.getText(_columnIndexOfTitle)
          val _tmpModelId: String?
          if (_stmt.isNull(_columnIndexOfModelId)) {
            _tmpModelId = null
          } else {
            _tmpModelId = _stmt.getText(_columnIndexOfModelId)
          }
          val _tmpProviderId: Long?
          if (_stmt.isNull(_columnIndexOfProviderId)) {
            _tmpProviderId = null
          } else {
            _tmpProviderId = _stmt.getLong(_columnIndexOfProviderId)
          }
          val _tmpCreatedAt: Long
          _tmpCreatedAt = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpUpdatedAt: Long
          _tmpUpdatedAt = _stmt.getLong(_columnIndexOfUpdatedAt)
          val _tmpMessageCount: Int
          _tmpMessageCount = _stmt.getLong(_columnIndexOfMessageCount).toInt()
          val _tmpTotalTokens: Int
          _tmpTotalTokens = _stmt.getLong(_columnIndexOfTotalTokens).toInt()
          _item = Conversation(_tmpId,_tmpTitle,_tmpModelId,_tmpProviderId,_tmpCreatedAt,_tmpUpdatedAt,_tmpMessageCount,_tmpTotalTokens)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteById(id: Long) {
    val _sql: String = "DELETE FROM conversations WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, id)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun touchConversation(id: Long, timestamp: Long) {
    val _sql: String = "UPDATE conversations SET updated_at = ?, message_count = message_count + 1 WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, timestamp)
        _argIndex = 2
        _stmt.bindLong(_argIndex, id)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updateTitle(id: Long, title: String) {
    val _sql: String = "UPDATE conversations SET title = ? WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, title)
        _argIndex = 2
        _stmt.bindLong(_argIndex, id)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun addTokens(id: Long, tokens: Int) {
    val _sql: String = "UPDATE conversations SET total_tokens = total_tokens + ? WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, tokens.toLong())
        _argIndex = 2
        _stmt.bindLong(_argIndex, id)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteAll() {
    val _sql: String = "DELETE FROM conversations"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
