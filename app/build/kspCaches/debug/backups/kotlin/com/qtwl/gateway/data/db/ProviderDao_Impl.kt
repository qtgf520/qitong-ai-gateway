package com.qtwl.gateway.`data`.db

import androidx.room.EntityDeleteOrUpdateAdapter
import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import com.qtwl.gateway.`data`.model.Provider
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
public class ProviderDao_Impl(
  __db: RoomDatabase,
) : ProviderDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfProvider: EntityInsertAdapter<Provider>

  private val __deleteAdapterOfProvider: EntityDeleteOrUpdateAdapter<Provider>

  private val __updateAdapterOfProvider: EntityDeleteOrUpdateAdapter<Provider>
  init {
    this.__db = __db
    this.__insertAdapterOfProvider = object : EntityInsertAdapter<Provider>() {
      protected override fun createQuery(): String = "INSERT OR REPLACE INTO `providers` (`id`,`name`,`type`,`base_url`,`port`,`api_key`,`is_enabled`,`order_index`) VALUES (nullif(?, 0),?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: Provider) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.name)
        statement.bindText(3, entity.type)
        statement.bindText(4, entity.baseUrl)
        statement.bindText(5, entity.port)
        val _tmpApiKey: String? = entity.apiKey
        if (_tmpApiKey == null) {
          statement.bindNull(6)
        } else {
          statement.bindText(6, _tmpApiKey)
        }
        val _tmp: Int = if (entity.isEnabled) 1 else 0
        statement.bindLong(7, _tmp.toLong())
        statement.bindLong(8, entity.orderIndex.toLong())
      }
    }
    this.__deleteAdapterOfProvider = object : EntityDeleteOrUpdateAdapter<Provider>() {
      protected override fun createQuery(): String = "DELETE FROM `providers` WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: Provider) {
        statement.bindLong(1, entity.id)
      }
    }
    this.__updateAdapterOfProvider = object : EntityDeleteOrUpdateAdapter<Provider>() {
      protected override fun createQuery(): String = "UPDATE OR ABORT `providers` SET `id` = ?,`name` = ?,`type` = ?,`base_url` = ?,`port` = ?,`api_key` = ?,`is_enabled` = ?,`order_index` = ? WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: Provider) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.name)
        statement.bindText(3, entity.type)
        statement.bindText(4, entity.baseUrl)
        statement.bindText(5, entity.port)
        val _tmpApiKey: String? = entity.apiKey
        if (_tmpApiKey == null) {
          statement.bindNull(6)
        } else {
          statement.bindText(6, _tmpApiKey)
        }
        val _tmp: Int = if (entity.isEnabled) 1 else 0
        statement.bindLong(7, _tmp.toLong())
        statement.bindLong(8, entity.orderIndex.toLong())
        statement.bindLong(9, entity.id)
      }
    }
  }

  public override suspend fun insert(provider: Provider): Long = performSuspending(__db, false, true) { _connection ->
    val _result: Long = __insertAdapterOfProvider.insertAndReturnId(_connection, provider)
    _result
  }

  public override suspend fun insertAll(providers: List<Provider>): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfProvider.insert(_connection, providers)
  }

  public override suspend fun delete(provider: Provider): Unit = performSuspending(__db, false, true) { _connection ->
    __deleteAdapterOfProvider.handle(_connection, provider)
  }

  public override suspend fun update(provider: Provider): Unit = performSuspending(__db, false, true) { _connection ->
    __updateAdapterOfProvider.handle(_connection, provider)
  }

  public override fun getAllProviders(): Flow<List<Provider>> {
    val _sql: String = "SELECT * FROM providers ORDER BY order_index ASC"
    return createFlow(__db, false, arrayOf("providers")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfBaseUrl: Int = getColumnIndexOrThrow(_stmt, "base_url")
        val _columnIndexOfPort: Int = getColumnIndexOrThrow(_stmt, "port")
        val _columnIndexOfApiKey: Int = getColumnIndexOrThrow(_stmt, "api_key")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "is_enabled")
        val _columnIndexOfOrderIndex: Int = getColumnIndexOrThrow(_stmt, "order_index")
        val _result: MutableList<Provider> = mutableListOf()
        while (_stmt.step()) {
          val _item: Provider
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpBaseUrl: String
          _tmpBaseUrl = _stmt.getText(_columnIndexOfBaseUrl)
          val _tmpPort: String
          _tmpPort = _stmt.getText(_columnIndexOfPort)
          val _tmpApiKey: String?
          if (_stmt.isNull(_columnIndexOfApiKey)) {
            _tmpApiKey = null
          } else {
            _tmpApiKey = _stmt.getText(_columnIndexOfApiKey)
          }
          val _tmpIsEnabled: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp != 0
          val _tmpOrderIndex: Int
          _tmpOrderIndex = _stmt.getLong(_columnIndexOfOrderIndex).toInt()
          _item = Provider(_tmpId,_tmpName,_tmpType,_tmpBaseUrl,_tmpPort,_tmpApiKey,_tmpIsEnabled,_tmpOrderIndex)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getAllProvidersList(): List<Provider> {
    val _sql: String = "SELECT * FROM providers ORDER BY order_index ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfBaseUrl: Int = getColumnIndexOrThrow(_stmt, "base_url")
        val _columnIndexOfPort: Int = getColumnIndexOrThrow(_stmt, "port")
        val _columnIndexOfApiKey: Int = getColumnIndexOrThrow(_stmt, "api_key")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "is_enabled")
        val _columnIndexOfOrderIndex: Int = getColumnIndexOrThrow(_stmt, "order_index")
        val _result: MutableList<Provider> = mutableListOf()
        while (_stmt.step()) {
          val _item: Provider
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpBaseUrl: String
          _tmpBaseUrl = _stmt.getText(_columnIndexOfBaseUrl)
          val _tmpPort: String
          _tmpPort = _stmt.getText(_columnIndexOfPort)
          val _tmpApiKey: String?
          if (_stmt.isNull(_columnIndexOfApiKey)) {
            _tmpApiKey = null
          } else {
            _tmpApiKey = _stmt.getText(_columnIndexOfApiKey)
          }
          val _tmpIsEnabled: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp != 0
          val _tmpOrderIndex: Int
          _tmpOrderIndex = _stmt.getLong(_columnIndexOfOrderIndex).toInt()
          _item = Provider(_tmpId,_tmpName,_tmpType,_tmpBaseUrl,_tmpPort,_tmpApiKey,_tmpIsEnabled,_tmpOrderIndex)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getEnabledProviders(): List<Provider> {
    val _sql: String = "SELECT * FROM providers WHERE is_enabled = 1 ORDER BY order_index ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfBaseUrl: Int = getColumnIndexOrThrow(_stmt, "base_url")
        val _columnIndexOfPort: Int = getColumnIndexOrThrow(_stmt, "port")
        val _columnIndexOfApiKey: Int = getColumnIndexOrThrow(_stmt, "api_key")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "is_enabled")
        val _columnIndexOfOrderIndex: Int = getColumnIndexOrThrow(_stmt, "order_index")
        val _result: MutableList<Provider> = mutableListOf()
        while (_stmt.step()) {
          val _item: Provider
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpBaseUrl: String
          _tmpBaseUrl = _stmt.getText(_columnIndexOfBaseUrl)
          val _tmpPort: String
          _tmpPort = _stmt.getText(_columnIndexOfPort)
          val _tmpApiKey: String?
          if (_stmt.isNull(_columnIndexOfApiKey)) {
            _tmpApiKey = null
          } else {
            _tmpApiKey = _stmt.getText(_columnIndexOfApiKey)
          }
          val _tmpIsEnabled: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp != 0
          val _tmpOrderIndex: Int
          _tmpOrderIndex = _stmt.getLong(_columnIndexOfOrderIndex).toInt()
          _item = Provider(_tmpId,_tmpName,_tmpType,_tmpBaseUrl,_tmpPort,_tmpApiKey,_tmpIsEnabled,_tmpOrderIndex)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getProviderById(id: Long): Provider? {
    val _sql: String = "SELECT * FROM providers WHERE id = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, id)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfBaseUrl: Int = getColumnIndexOrThrow(_stmt, "base_url")
        val _columnIndexOfPort: Int = getColumnIndexOrThrow(_stmt, "port")
        val _columnIndexOfApiKey: Int = getColumnIndexOrThrow(_stmt, "api_key")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "is_enabled")
        val _columnIndexOfOrderIndex: Int = getColumnIndexOrThrow(_stmt, "order_index")
        val _result: Provider?
        if (_stmt.step()) {
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpBaseUrl: String
          _tmpBaseUrl = _stmt.getText(_columnIndexOfBaseUrl)
          val _tmpPort: String
          _tmpPort = _stmt.getText(_columnIndexOfPort)
          val _tmpApiKey: String?
          if (_stmt.isNull(_columnIndexOfApiKey)) {
            _tmpApiKey = null
          } else {
            _tmpApiKey = _stmt.getText(_columnIndexOfApiKey)
          }
          val _tmpIsEnabled: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp != 0
          val _tmpOrderIndex: Int
          _tmpOrderIndex = _stmt.getLong(_columnIndexOfOrderIndex).toInt()
          _result = Provider(_tmpId,_tmpName,_tmpType,_tmpBaseUrl,_tmpPort,_tmpApiKey,_tmpIsEnabled,_tmpOrderIndex)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getAllProvidersOnce(): List<Provider> {
    val _sql: String = "SELECT * FROM providers ORDER BY order_index ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfName: Int = getColumnIndexOrThrow(_stmt, "name")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfBaseUrl: Int = getColumnIndexOrThrow(_stmt, "base_url")
        val _columnIndexOfPort: Int = getColumnIndexOrThrow(_stmt, "port")
        val _columnIndexOfApiKey: Int = getColumnIndexOrThrow(_stmt, "api_key")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "is_enabled")
        val _columnIndexOfOrderIndex: Int = getColumnIndexOrThrow(_stmt, "order_index")
        val _result: MutableList<Provider> = mutableListOf()
        while (_stmt.step()) {
          val _item: Provider
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpName: String
          _tmpName = _stmt.getText(_columnIndexOfName)
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpBaseUrl: String
          _tmpBaseUrl = _stmt.getText(_columnIndexOfBaseUrl)
          val _tmpPort: String
          _tmpPort = _stmt.getText(_columnIndexOfPort)
          val _tmpApiKey: String?
          if (_stmt.isNull(_columnIndexOfApiKey)) {
            _tmpApiKey = null
          } else {
            _tmpApiKey = _stmt.getText(_columnIndexOfApiKey)
          }
          val _tmpIsEnabled: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp != 0
          val _tmpOrderIndex: Int
          _tmpOrderIndex = _stmt.getLong(_columnIndexOfOrderIndex).toInt()
          _item = Provider(_tmpId,_tmpName,_tmpType,_tmpBaseUrl,_tmpPort,_tmpApiKey,_tmpIsEnabled,_tmpOrderIndex)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteAll() {
    val _sql: String = "DELETE FROM providers"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteById(id: Long) {
    val _sql: String = "DELETE FROM providers WHERE id = ?"
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

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
