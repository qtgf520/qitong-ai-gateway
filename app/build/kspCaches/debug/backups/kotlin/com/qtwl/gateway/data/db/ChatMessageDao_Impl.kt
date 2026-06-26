package com.qtwl.gateway.`data`.db

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import com.qtwl.gateway.`data`.model.ChatMessage
import javax.`annotation`.processing.Generated
import kotlin.Boolean
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
public class ChatMessageDao_Impl(
  __db: RoomDatabase,
) : ChatMessageDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfChatMessage: EntityInsertAdapter<ChatMessage>
  init {
    this.__db = __db
    this.__insertAdapterOfChatMessage = object : EntityInsertAdapter<ChatMessage>() {
      protected override fun createQuery(): String = "INSERT OR REPLACE INTO `chat_messages` (`id`,`conversation_id`,`role`,`content`,`model_id`,`provider_id`,`prompt_tokens`,`completion_tokens`,`total_tokens`,`timestamp`,`is_streaming`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: ChatMessage) {
        statement.bindLong(1, entity.id)
        statement.bindLong(2, entity.conversationId)
        statement.bindText(3, entity.role)
        statement.bindText(4, entity.content)
        val _tmpModelId: String? = entity.modelId
        if (_tmpModelId == null) {
          statement.bindNull(5)
        } else {
          statement.bindText(5, _tmpModelId)
        }
        val _tmpProviderId: Long? = entity.providerId
        if (_tmpProviderId == null) {
          statement.bindNull(6)
        } else {
          statement.bindLong(6, _tmpProviderId)
        }
        statement.bindLong(7, entity.promptTokens.toLong())
        statement.bindLong(8, entity.completionTokens.toLong())
        statement.bindLong(9, entity.totalTokens.toLong())
        statement.bindLong(10, entity.timestamp)
        val _tmp: Int = if (entity.isStreaming) 1 else 0
        statement.bindLong(11, _tmp.toLong())
      }
    }
  }

  public override suspend fun insert(message: ChatMessage): Long = performSuspending(__db, false, true) { _connection ->
    val _result: Long = __insertAdapterOfChatMessage.insertAndReturnId(_connection, message)
    _result
  }

  public override suspend fun insertAll(messages: List<ChatMessage>): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfChatMessage.insert(_connection, messages)
  }

  public override fun getMessagesByConversation(conversationId: Long): Flow<List<ChatMessage>> {
    val _sql: String = "SELECT * FROM chat_messages WHERE conversation_id = ? ORDER BY timestamp ASC"
    return createFlow(__db, false, arrayOf("chat_messages")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, conversationId)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfConversationId: Int = getColumnIndexOrThrow(_stmt, "conversation_id")
        val _columnIndexOfRole: Int = getColumnIndexOrThrow(_stmt, "role")
        val _columnIndexOfContent: Int = getColumnIndexOrThrow(_stmt, "content")
        val _columnIndexOfModelId: Int = getColumnIndexOrThrow(_stmt, "model_id")
        val _columnIndexOfProviderId: Int = getColumnIndexOrThrow(_stmt, "provider_id")
        val _columnIndexOfPromptTokens: Int = getColumnIndexOrThrow(_stmt, "prompt_tokens")
        val _columnIndexOfCompletionTokens: Int = getColumnIndexOrThrow(_stmt, "completion_tokens")
        val _columnIndexOfTotalTokens: Int = getColumnIndexOrThrow(_stmt, "total_tokens")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfIsStreaming: Int = getColumnIndexOrThrow(_stmt, "is_streaming")
        val _result: MutableList<ChatMessage> = mutableListOf()
        while (_stmt.step()) {
          val _item: ChatMessage
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpConversationId: Long
          _tmpConversationId = _stmt.getLong(_columnIndexOfConversationId)
          val _tmpRole: String
          _tmpRole = _stmt.getText(_columnIndexOfRole)
          val _tmpContent: String
          _tmpContent = _stmt.getText(_columnIndexOfContent)
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
          val _tmpPromptTokens: Int
          _tmpPromptTokens = _stmt.getLong(_columnIndexOfPromptTokens).toInt()
          val _tmpCompletionTokens: Int
          _tmpCompletionTokens = _stmt.getLong(_columnIndexOfCompletionTokens).toInt()
          val _tmpTotalTokens: Int
          _tmpTotalTokens = _stmt.getLong(_columnIndexOfTotalTokens).toInt()
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpIsStreaming: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsStreaming).toInt()
          _tmpIsStreaming = _tmp != 0
          _item = ChatMessage(_tmpId,_tmpConversationId,_tmpRole,_tmpContent,_tmpModelId,_tmpProviderId,_tmpPromptTokens,_tmpCompletionTokens,_tmpTotalTokens,_tmpTimestamp,_tmpIsStreaming)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getMessagesByConversationList(conversationId: Long): List<ChatMessage> {
    val _sql: String = "SELECT * FROM chat_messages WHERE conversation_id = ? ORDER BY timestamp ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, conversationId)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfConversationId: Int = getColumnIndexOrThrow(_stmt, "conversation_id")
        val _columnIndexOfRole: Int = getColumnIndexOrThrow(_stmt, "role")
        val _columnIndexOfContent: Int = getColumnIndexOrThrow(_stmt, "content")
        val _columnIndexOfModelId: Int = getColumnIndexOrThrow(_stmt, "model_id")
        val _columnIndexOfProviderId: Int = getColumnIndexOrThrow(_stmt, "provider_id")
        val _columnIndexOfPromptTokens: Int = getColumnIndexOrThrow(_stmt, "prompt_tokens")
        val _columnIndexOfCompletionTokens: Int = getColumnIndexOrThrow(_stmt, "completion_tokens")
        val _columnIndexOfTotalTokens: Int = getColumnIndexOrThrow(_stmt, "total_tokens")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfIsStreaming: Int = getColumnIndexOrThrow(_stmt, "is_streaming")
        val _result: MutableList<ChatMessage> = mutableListOf()
        while (_stmt.step()) {
          val _item: ChatMessage
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpConversationId: Long
          _tmpConversationId = _stmt.getLong(_columnIndexOfConversationId)
          val _tmpRole: String
          _tmpRole = _stmt.getText(_columnIndexOfRole)
          val _tmpContent: String
          _tmpContent = _stmt.getText(_columnIndexOfContent)
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
          val _tmpPromptTokens: Int
          _tmpPromptTokens = _stmt.getLong(_columnIndexOfPromptTokens).toInt()
          val _tmpCompletionTokens: Int
          _tmpCompletionTokens = _stmt.getLong(_columnIndexOfCompletionTokens).toInt()
          val _tmpTotalTokens: Int
          _tmpTotalTokens = _stmt.getLong(_columnIndexOfTotalTokens).toInt()
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpIsStreaming: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsStreaming).toInt()
          _tmpIsStreaming = _tmp != 0
          _item = ChatMessage(_tmpId,_tmpConversationId,_tmpRole,_tmpContent,_tmpModelId,_tmpProviderId,_tmpPromptTokens,_tmpCompletionTokens,_tmpTotalTokens,_tmpTimestamp,_tmpIsStreaming)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getMessageById(id: Long): ChatMessage? {
    val _sql: String = "SELECT * FROM chat_messages WHERE id = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, id)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfConversationId: Int = getColumnIndexOrThrow(_stmt, "conversation_id")
        val _columnIndexOfRole: Int = getColumnIndexOrThrow(_stmt, "role")
        val _columnIndexOfContent: Int = getColumnIndexOrThrow(_stmt, "content")
        val _columnIndexOfModelId: Int = getColumnIndexOrThrow(_stmt, "model_id")
        val _columnIndexOfProviderId: Int = getColumnIndexOrThrow(_stmt, "provider_id")
        val _columnIndexOfPromptTokens: Int = getColumnIndexOrThrow(_stmt, "prompt_tokens")
        val _columnIndexOfCompletionTokens: Int = getColumnIndexOrThrow(_stmt, "completion_tokens")
        val _columnIndexOfTotalTokens: Int = getColumnIndexOrThrow(_stmt, "total_tokens")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfIsStreaming: Int = getColumnIndexOrThrow(_stmt, "is_streaming")
        val _result: ChatMessage?
        if (_stmt.step()) {
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpConversationId: Long
          _tmpConversationId = _stmt.getLong(_columnIndexOfConversationId)
          val _tmpRole: String
          _tmpRole = _stmt.getText(_columnIndexOfRole)
          val _tmpContent: String
          _tmpContent = _stmt.getText(_columnIndexOfContent)
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
          val _tmpPromptTokens: Int
          _tmpPromptTokens = _stmt.getLong(_columnIndexOfPromptTokens).toInt()
          val _tmpCompletionTokens: Int
          _tmpCompletionTokens = _stmt.getLong(_columnIndexOfCompletionTokens).toInt()
          val _tmpTotalTokens: Int
          _tmpTotalTokens = _stmt.getLong(_columnIndexOfTotalTokens).toInt()
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpIsStreaming: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsStreaming).toInt()
          _tmpIsStreaming = _tmp != 0
          _result = ChatMessage(_tmpId,_tmpConversationId,_tmpRole,_tmpContent,_tmpModelId,_tmpProviderId,_tmpPromptTokens,_tmpCompletionTokens,_tmpTotalTokens,_tmpTimestamp,_tmpIsStreaming)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getMessageCount(conversationId: Long): Int {
    val _sql: String = "SELECT COUNT(*) FROM chat_messages WHERE conversation_id = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, conversationId)
        val _result: Int
        if (_stmt.step()) {
          val _tmp: Int
          _tmp = _stmt.getLong(0).toInt()
          _result = _tmp
        } else {
          _result = 0
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getAllMessagesOnce(): List<ChatMessage> {
    val _sql: String = "SELECT * FROM chat_messages ORDER BY timestamp ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfConversationId: Int = getColumnIndexOrThrow(_stmt, "conversation_id")
        val _columnIndexOfRole: Int = getColumnIndexOrThrow(_stmt, "role")
        val _columnIndexOfContent: Int = getColumnIndexOrThrow(_stmt, "content")
        val _columnIndexOfModelId: Int = getColumnIndexOrThrow(_stmt, "model_id")
        val _columnIndexOfProviderId: Int = getColumnIndexOrThrow(_stmt, "provider_id")
        val _columnIndexOfPromptTokens: Int = getColumnIndexOrThrow(_stmt, "prompt_tokens")
        val _columnIndexOfCompletionTokens: Int = getColumnIndexOrThrow(_stmt, "completion_tokens")
        val _columnIndexOfTotalTokens: Int = getColumnIndexOrThrow(_stmt, "total_tokens")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfIsStreaming: Int = getColumnIndexOrThrow(_stmt, "is_streaming")
        val _result: MutableList<ChatMessage> = mutableListOf()
        while (_stmt.step()) {
          val _item: ChatMessage
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpConversationId: Long
          _tmpConversationId = _stmt.getLong(_columnIndexOfConversationId)
          val _tmpRole: String
          _tmpRole = _stmt.getText(_columnIndexOfRole)
          val _tmpContent: String
          _tmpContent = _stmt.getText(_columnIndexOfContent)
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
          val _tmpPromptTokens: Int
          _tmpPromptTokens = _stmt.getLong(_columnIndexOfPromptTokens).toInt()
          val _tmpCompletionTokens: Int
          _tmpCompletionTokens = _stmt.getLong(_columnIndexOfCompletionTokens).toInt()
          val _tmpTotalTokens: Int
          _tmpTotalTokens = _stmt.getLong(_columnIndexOfTotalTokens).toInt()
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpIsStreaming: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsStreaming).toInt()
          _tmpIsStreaming = _tmp != 0
          _item = ChatMessage(_tmpId,_tmpConversationId,_tmpRole,_tmpContent,_tmpModelId,_tmpProviderId,_tmpPromptTokens,_tmpCompletionTokens,_tmpTotalTokens,_tmpTimestamp,_tmpIsStreaming)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteByConversation(conversationId: Long) {
    val _sql: String = "DELETE FROM chat_messages WHERE conversation_id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, conversationId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteById(id: Long) {
    val _sql: String = "DELETE FROM chat_messages WHERE id = ?"
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

  public override suspend fun finalizeStreamingMessage(
    id: Long,
    content: String,
    completionTokens: Int,
    totalTokens: Int,
  ) {
    val _sql: String = "UPDATE chat_messages SET content = ?, is_streaming = 0, completion_tokens = ?, total_tokens = ? WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, content)
        _argIndex = 2
        _stmt.bindLong(_argIndex, completionTokens.toLong())
        _argIndex = 3
        _stmt.bindLong(_argIndex, totalTokens.toLong())
        _argIndex = 4
        _stmt.bindLong(_argIndex, id)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun markStreaming(id: Long) {
    val _sql: String = "UPDATE chat_messages SET is_streaming = 1 WHERE id = ?"
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

  public override suspend fun deleteAll() {
    val _sql: String = "DELETE FROM chat_messages"
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
