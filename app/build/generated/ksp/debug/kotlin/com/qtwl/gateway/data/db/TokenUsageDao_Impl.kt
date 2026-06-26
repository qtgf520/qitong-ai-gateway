package com.qtwl.gateway.`data`.db

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import com.qtwl.gateway.`data`.model.TokenUsage
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
public class TokenUsageDao_Impl(
  __db: RoomDatabase,
) : TokenUsageDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfTokenUsage: EntityInsertAdapter<TokenUsage>
  init {
    this.__db = __db
    this.__insertAdapterOfTokenUsage = object : EntityInsertAdapter<TokenUsage>() {
      protected override fun createQuery(): String = "INSERT OR REPLACE INTO `token_usage` (`id`,`provider_id`,`model_id`,`prompt_tokens`,`completion_tokens`,`total_tokens`,`timestamp`) VALUES (nullif(?, 0),?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: TokenUsage) {
        statement.bindLong(1, entity.id)
        statement.bindLong(2, entity.providerId)
        statement.bindText(3, entity.modelId)
        statement.bindLong(4, entity.promptTokens.toLong())
        statement.bindLong(5, entity.completionTokens.toLong())
        statement.bindLong(6, entity.totalTokens.toLong())
        statement.bindLong(7, entity.timestamp)
      }
    }
  }

  public override suspend fun insert(usage: TokenUsage): Long = performSuspending(__db, false, true) { _connection ->
    val _result: Long = __insertAdapterOfTokenUsage.insertAndReturnId(_connection, usage)
    _result
  }

  public override suspend fun insertAll(usages: List<TokenUsage>): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfTokenUsage.insert(_connection, usages)
  }

  public override fun getAllUsage(): Flow<List<TokenUsage>> {
    val _sql: String = "SELECT * FROM token_usage ORDER BY timestamp DESC"
    return createFlow(__db, false, arrayOf("token_usage")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfProviderId: Int = getColumnIndexOrThrow(_stmt, "provider_id")
        val _columnIndexOfModelId: Int = getColumnIndexOrThrow(_stmt, "model_id")
        val _columnIndexOfPromptTokens: Int = getColumnIndexOrThrow(_stmt, "prompt_tokens")
        val _columnIndexOfCompletionTokens: Int = getColumnIndexOrThrow(_stmt, "completion_tokens")
        val _columnIndexOfTotalTokens: Int = getColumnIndexOrThrow(_stmt, "total_tokens")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _result: MutableList<TokenUsage> = mutableListOf()
        while (_stmt.step()) {
          val _item: TokenUsage
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpProviderId: Long
          _tmpProviderId = _stmt.getLong(_columnIndexOfProviderId)
          val _tmpModelId: String
          _tmpModelId = _stmt.getText(_columnIndexOfModelId)
          val _tmpPromptTokens: Int
          _tmpPromptTokens = _stmt.getLong(_columnIndexOfPromptTokens).toInt()
          val _tmpCompletionTokens: Int
          _tmpCompletionTokens = _stmt.getLong(_columnIndexOfCompletionTokens).toInt()
          val _tmpTotalTokens: Int
          _tmpTotalTokens = _stmt.getLong(_columnIndexOfTotalTokens).toInt()
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          _item = TokenUsage(_tmpId,_tmpProviderId,_tmpModelId,_tmpPromptTokens,_tmpCompletionTokens,_tmpTotalTokens,_tmpTimestamp)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun getUsageByProvider(providerId: Long): Flow<List<TokenUsage>> {
    val _sql: String = "SELECT * FROM token_usage WHERE provider_id = ? ORDER BY timestamp DESC"
    return createFlow(__db, false, arrayOf("token_usage")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, providerId)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfProviderId: Int = getColumnIndexOrThrow(_stmt, "provider_id")
        val _columnIndexOfModelId: Int = getColumnIndexOrThrow(_stmt, "model_id")
        val _columnIndexOfPromptTokens: Int = getColumnIndexOrThrow(_stmt, "prompt_tokens")
        val _columnIndexOfCompletionTokens: Int = getColumnIndexOrThrow(_stmt, "completion_tokens")
        val _columnIndexOfTotalTokens: Int = getColumnIndexOrThrow(_stmt, "total_tokens")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _result: MutableList<TokenUsage> = mutableListOf()
        while (_stmt.step()) {
          val _item: TokenUsage
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpProviderId: Long
          _tmpProviderId = _stmt.getLong(_columnIndexOfProviderId)
          val _tmpModelId: String
          _tmpModelId = _stmt.getText(_columnIndexOfModelId)
          val _tmpPromptTokens: Int
          _tmpPromptTokens = _stmt.getLong(_columnIndexOfPromptTokens).toInt()
          val _tmpCompletionTokens: Int
          _tmpCompletionTokens = _stmt.getLong(_columnIndexOfCompletionTokens).toInt()
          val _tmpTotalTokens: Int
          _tmpTotalTokens = _stmt.getLong(_columnIndexOfTotalTokens).toInt()
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          _item = TokenUsage(_tmpId,_tmpProviderId,_tmpModelId,_tmpPromptTokens,_tmpCompletionTokens,_tmpTotalTokens,_tmpTimestamp)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun getUsageSince(since: Long): Flow<List<TokenUsage>> {
    val _sql: String = "SELECT * FROM token_usage WHERE timestamp >= ? ORDER BY timestamp DESC"
    return createFlow(__db, false, arrayOf("token_usage")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, since)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfProviderId: Int = getColumnIndexOrThrow(_stmt, "provider_id")
        val _columnIndexOfModelId: Int = getColumnIndexOrThrow(_stmt, "model_id")
        val _columnIndexOfPromptTokens: Int = getColumnIndexOrThrow(_stmt, "prompt_tokens")
        val _columnIndexOfCompletionTokens: Int = getColumnIndexOrThrow(_stmt, "completion_tokens")
        val _columnIndexOfTotalTokens: Int = getColumnIndexOrThrow(_stmt, "total_tokens")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _result: MutableList<TokenUsage> = mutableListOf()
        while (_stmt.step()) {
          val _item: TokenUsage
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpProviderId: Long
          _tmpProviderId = _stmt.getLong(_columnIndexOfProviderId)
          val _tmpModelId: String
          _tmpModelId = _stmt.getText(_columnIndexOfModelId)
          val _tmpPromptTokens: Int
          _tmpPromptTokens = _stmt.getLong(_columnIndexOfPromptTokens).toInt()
          val _tmpCompletionTokens: Int
          _tmpCompletionTokens = _stmt.getLong(_columnIndexOfCompletionTokens).toInt()
          val _tmpTotalTokens: Int
          _tmpTotalTokens = _stmt.getLong(_columnIndexOfTotalTokens).toInt()
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          _item = TokenUsage(_tmpId,_tmpProviderId,_tmpModelId,_tmpPromptTokens,_tmpCompletionTokens,_tmpTotalTokens,_tmpTimestamp)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getTotalPromptTokens(): Long {
    val _sql: String = "SELECT SUM(prompt_tokens) FROM token_usage"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _result: Long
        if (_stmt.step()) {
          val _tmp: Long
          _tmp = _stmt.getLong(0)
          _result = _tmp
        } else {
          _result = 0L
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getTotalCompletionTokens(): Long {
    val _sql: String = "SELECT SUM(completion_tokens) FROM token_usage"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _result: Long
        if (_stmt.step()) {
          val _tmp: Long
          _tmp = _stmt.getLong(0)
          _result = _tmp
        } else {
          _result = 0L
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getTotalTokens(): Long {
    val _sql: String = "SELECT SUM(total_tokens) FROM token_usage"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _result: Long
        if (_stmt.step()) {
          val _tmp: Long
          _tmp = _stmt.getLong(0)
          _result = _tmp
        } else {
          _result = 0L
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getTotalTokensByProvider(providerId: Long): Long {
    val _sql: String = "SELECT SUM(total_tokens) FROM token_usage WHERE provider_id = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, providerId)
        val _result: Long
        if (_stmt.step()) {
          val _tmp: Long
          _tmp = _stmt.getLong(0)
          _result = _tmp
        } else {
          _result = 0L
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getTotalTokensByModel(modelId: String): Long {
    val _sql: String = "SELECT SUM(total_tokens) FROM token_usage WHERE model_id = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, modelId)
        val _result: Long
        if (_stmt.step()) {
          val _tmp: Long
          _tmp = _stmt.getLong(0)
          _result = _tmp
        } else {
          _result = 0L
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getAllUsageOnce(): List<TokenUsage> {
    val _sql: String = "SELECT * FROM token_usage ORDER BY timestamp DESC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfProviderId: Int = getColumnIndexOrThrow(_stmt, "provider_id")
        val _columnIndexOfModelId: Int = getColumnIndexOrThrow(_stmt, "model_id")
        val _columnIndexOfPromptTokens: Int = getColumnIndexOrThrow(_stmt, "prompt_tokens")
        val _columnIndexOfCompletionTokens: Int = getColumnIndexOrThrow(_stmt, "completion_tokens")
        val _columnIndexOfTotalTokens: Int = getColumnIndexOrThrow(_stmt, "total_tokens")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _result: MutableList<TokenUsage> = mutableListOf()
        while (_stmt.step()) {
          val _item: TokenUsage
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpProviderId: Long
          _tmpProviderId = _stmt.getLong(_columnIndexOfProviderId)
          val _tmpModelId: String
          _tmpModelId = _stmt.getText(_columnIndexOfModelId)
          val _tmpPromptTokens: Int
          _tmpPromptTokens = _stmt.getLong(_columnIndexOfPromptTokens).toInt()
          val _tmpCompletionTokens: Int
          _tmpCompletionTokens = _stmt.getLong(_columnIndexOfCompletionTokens).toInt()
          val _tmpTotalTokens: Int
          _tmpTotalTokens = _stmt.getLong(_columnIndexOfTotalTokens).toInt()
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          _item = TokenUsage(_tmpId,_tmpProviderId,_tmpModelId,_tmpPromptTokens,_tmpCompletionTokens,_tmpTotalTokens,_tmpTimestamp)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteOlderThan(before: Long) {
    val _sql: String = "DELETE FROM token_usage WHERE timestamp < ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, before)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun clearAll() {
    val _sql: String = "DELETE FROM token_usage"
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
