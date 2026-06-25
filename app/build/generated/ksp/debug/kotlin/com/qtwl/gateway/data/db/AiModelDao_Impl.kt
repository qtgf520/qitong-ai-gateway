package com.qtwl.gateway.`data`.db

import androidx.room.EntityDeleteOrUpdateAdapter
import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import com.qtwl.gateway.`data`.model.AiModel
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
public class AiModelDao_Impl(
  __db: RoomDatabase,
) : AiModelDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfAiModel: EntityInsertAdapter<AiModel>

  private val __deleteAdapterOfAiModel: EntityDeleteOrUpdateAdapter<AiModel>

  private val __updateAdapterOfAiModel: EntityDeleteOrUpdateAdapter<AiModel>
  init {
    this.__db = __db
    this.__insertAdapterOfAiModel = object : EntityInsertAdapter<AiModel>() {
      protected override fun createQuery(): String = "INSERT OR REPLACE INTO `models` (`id`,`provider_id`,`model_id`,`display_name`,`is_default`,`sync_status`,`is_enabled`,`custom_alias`,`use_proxy`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: AiModel) {
        statement.bindLong(1, entity.id)
        statement.bindLong(2, entity.providerId)
        statement.bindText(3, entity.modelId)
        statement.bindText(4, entity.displayName)
        val _tmp: Int = if (entity.isDefault) 1 else 0
        statement.bindLong(5, _tmp.toLong())
        statement.bindText(6, entity.syncStatus)
        val _tmp_1: Int = if (entity.isEnabled) 1 else 0
        statement.bindLong(7, _tmp_1.toLong())
        statement.bindText(8, entity.customAlias)
        val _tmp_2: Int = if (entity.useProxy) 1 else 0
        statement.bindLong(9, _tmp_2.toLong())
      }
    }
    this.__deleteAdapterOfAiModel = object : EntityDeleteOrUpdateAdapter<AiModel>() {
      protected override fun createQuery(): String = "DELETE FROM `models` WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: AiModel) {
        statement.bindLong(1, entity.id)
      }
    }
    this.__updateAdapterOfAiModel = object : EntityDeleteOrUpdateAdapter<AiModel>() {
      protected override fun createQuery(): String = "UPDATE OR ABORT `models` SET `id` = ?,`provider_id` = ?,`model_id` = ?,`display_name` = ?,`is_default` = ?,`sync_status` = ?,`is_enabled` = ?,`custom_alias` = ?,`use_proxy` = ? WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: AiModel) {
        statement.bindLong(1, entity.id)
        statement.bindLong(2, entity.providerId)
        statement.bindText(3, entity.modelId)
        statement.bindText(4, entity.displayName)
        val _tmp: Int = if (entity.isDefault) 1 else 0
        statement.bindLong(5, _tmp.toLong())
        statement.bindText(6, entity.syncStatus)
        val _tmp_1: Int = if (entity.isEnabled) 1 else 0
        statement.bindLong(7, _tmp_1.toLong())
        statement.bindText(8, entity.customAlias)
        val _tmp_2: Int = if (entity.useProxy) 1 else 0
        statement.bindLong(9, _tmp_2.toLong())
        statement.bindLong(10, entity.id)
      }
    }
  }

  public override suspend fun insert(model: AiModel): Long = performSuspending(__db, false, true) { _connection ->
    val _result: Long = __insertAdapterOfAiModel.insertAndReturnId(_connection, model)
    _result
  }

  public override suspend fun insertAll(models: List<AiModel>): Unit = performSuspending(__db, false, true) { _connection ->
    __insertAdapterOfAiModel.insert(_connection, models)
  }

  public override suspend fun delete(model: AiModel): Unit = performSuspending(__db, false, true) { _connection ->
    __deleteAdapterOfAiModel.handle(_connection, model)
  }

  public override suspend fun update(model: AiModel): Unit = performSuspending(__db, false, true) { _connection ->
    __updateAdapterOfAiModel.handle(_connection, model)
  }

  public override fun getAllModels(): Flow<List<AiModel>> {
    val _sql: String = "SELECT * FROM models ORDER BY provider_id ASC, model_id ASC"
    return createFlow(__db, false, arrayOf("models")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfProviderId: Int = getColumnIndexOrThrow(_stmt, "provider_id")
        val _columnIndexOfModelId: Int = getColumnIndexOrThrow(_stmt, "model_id")
        val _columnIndexOfDisplayName: Int = getColumnIndexOrThrow(_stmt, "display_name")
        val _columnIndexOfIsDefault: Int = getColumnIndexOrThrow(_stmt, "is_default")
        val _columnIndexOfSyncStatus: Int = getColumnIndexOrThrow(_stmt, "sync_status")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "is_enabled")
        val _columnIndexOfCustomAlias: Int = getColumnIndexOrThrow(_stmt, "custom_alias")
        val _columnIndexOfUseProxy: Int = getColumnIndexOrThrow(_stmt, "use_proxy")
        val _result: MutableList<AiModel> = mutableListOf()
        while (_stmt.step()) {
          val _item: AiModel
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpProviderId: Long
          _tmpProviderId = _stmt.getLong(_columnIndexOfProviderId)
          val _tmpModelId: String
          _tmpModelId = _stmt.getText(_columnIndexOfModelId)
          val _tmpDisplayName: String
          _tmpDisplayName = _stmt.getText(_columnIndexOfDisplayName)
          val _tmpIsDefault: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsDefault).toInt()
          _tmpIsDefault = _tmp != 0
          val _tmpSyncStatus: String
          _tmpSyncStatus = _stmt.getText(_columnIndexOfSyncStatus)
          val _tmpIsEnabled: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp_1 != 0
          val _tmpCustomAlias: String
          _tmpCustomAlias = _stmt.getText(_columnIndexOfCustomAlias)
          val _tmpUseProxy: Boolean
          val _tmp_2: Int
          _tmp_2 = _stmt.getLong(_columnIndexOfUseProxy).toInt()
          _tmpUseProxy = _tmp_2 != 0
          _item = AiModel(_tmpId,_tmpProviderId,_tmpModelId,_tmpDisplayName,_tmpIsDefault,_tmpSyncStatus,_tmpIsEnabled,_tmpCustomAlias,_tmpUseProxy)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getAllModelsList(): List<AiModel> {
    val _sql: String = "SELECT * FROM models ORDER BY provider_id ASC, model_id ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfProviderId: Int = getColumnIndexOrThrow(_stmt, "provider_id")
        val _columnIndexOfModelId: Int = getColumnIndexOrThrow(_stmt, "model_id")
        val _columnIndexOfDisplayName: Int = getColumnIndexOrThrow(_stmt, "display_name")
        val _columnIndexOfIsDefault: Int = getColumnIndexOrThrow(_stmt, "is_default")
        val _columnIndexOfSyncStatus: Int = getColumnIndexOrThrow(_stmt, "sync_status")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "is_enabled")
        val _columnIndexOfCustomAlias: Int = getColumnIndexOrThrow(_stmt, "custom_alias")
        val _columnIndexOfUseProxy: Int = getColumnIndexOrThrow(_stmt, "use_proxy")
        val _result: MutableList<AiModel> = mutableListOf()
        while (_stmt.step()) {
          val _item: AiModel
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpProviderId: Long
          _tmpProviderId = _stmt.getLong(_columnIndexOfProviderId)
          val _tmpModelId: String
          _tmpModelId = _stmt.getText(_columnIndexOfModelId)
          val _tmpDisplayName: String
          _tmpDisplayName = _stmt.getText(_columnIndexOfDisplayName)
          val _tmpIsDefault: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsDefault).toInt()
          _tmpIsDefault = _tmp != 0
          val _tmpSyncStatus: String
          _tmpSyncStatus = _stmt.getText(_columnIndexOfSyncStatus)
          val _tmpIsEnabled: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp_1 != 0
          val _tmpCustomAlias: String
          _tmpCustomAlias = _stmt.getText(_columnIndexOfCustomAlias)
          val _tmpUseProxy: Boolean
          val _tmp_2: Int
          _tmp_2 = _stmt.getLong(_columnIndexOfUseProxy).toInt()
          _tmpUseProxy = _tmp_2 != 0
          _item = AiModel(_tmpId,_tmpProviderId,_tmpModelId,_tmpDisplayName,_tmpIsDefault,_tmpSyncStatus,_tmpIsEnabled,_tmpCustomAlias,_tmpUseProxy)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun getEnabledModels(): Flow<List<AiModel>> {
    val _sql: String = "SELECT m.* FROM models m INNER JOIN providers p ON m.provider_id = p.id WHERE m.is_enabled = 1 AND p.is_enabled = 1 ORDER BY m.provider_id ASC, m.model_id ASC"
    return createFlow(__db, false, arrayOf("models", "providers")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfProviderId: Int = getColumnIndexOrThrow(_stmt, "provider_id")
        val _columnIndexOfModelId: Int = getColumnIndexOrThrow(_stmt, "model_id")
        val _columnIndexOfDisplayName: Int = getColumnIndexOrThrow(_stmt, "display_name")
        val _columnIndexOfIsDefault: Int = getColumnIndexOrThrow(_stmt, "is_default")
        val _columnIndexOfSyncStatus: Int = getColumnIndexOrThrow(_stmt, "sync_status")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "is_enabled")
        val _columnIndexOfCustomAlias: Int = getColumnIndexOrThrow(_stmt, "custom_alias")
        val _columnIndexOfUseProxy: Int = getColumnIndexOrThrow(_stmt, "use_proxy")
        val _result: MutableList<AiModel> = mutableListOf()
        while (_stmt.step()) {
          val _item: AiModel
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpProviderId: Long
          _tmpProviderId = _stmt.getLong(_columnIndexOfProviderId)
          val _tmpModelId: String
          _tmpModelId = _stmt.getText(_columnIndexOfModelId)
          val _tmpDisplayName: String
          _tmpDisplayName = _stmt.getText(_columnIndexOfDisplayName)
          val _tmpIsDefault: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsDefault).toInt()
          _tmpIsDefault = _tmp != 0
          val _tmpSyncStatus: String
          _tmpSyncStatus = _stmt.getText(_columnIndexOfSyncStatus)
          val _tmpIsEnabled: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp_1 != 0
          val _tmpCustomAlias: String
          _tmpCustomAlias = _stmt.getText(_columnIndexOfCustomAlias)
          val _tmpUseProxy: Boolean
          val _tmp_2: Int
          _tmp_2 = _stmt.getLong(_columnIndexOfUseProxy).toInt()
          _tmpUseProxy = _tmp_2 != 0
          _item = AiModel(_tmpId,_tmpProviderId,_tmpModelId,_tmpDisplayName,_tmpIsDefault,_tmpSyncStatus,_tmpIsEnabled,_tmpCustomAlias,_tmpUseProxy)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getEnabledModelsList(): List<AiModel> {
    val _sql: String = "SELECT m.* FROM models m INNER JOIN providers p ON m.provider_id = p.id WHERE m.is_enabled = 1 AND p.is_enabled = 1 ORDER BY m.provider_id ASC, m.model_id ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfProviderId: Int = getColumnIndexOrThrow(_stmt, "provider_id")
        val _columnIndexOfModelId: Int = getColumnIndexOrThrow(_stmt, "model_id")
        val _columnIndexOfDisplayName: Int = getColumnIndexOrThrow(_stmt, "display_name")
        val _columnIndexOfIsDefault: Int = getColumnIndexOrThrow(_stmt, "is_default")
        val _columnIndexOfSyncStatus: Int = getColumnIndexOrThrow(_stmt, "sync_status")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "is_enabled")
        val _columnIndexOfCustomAlias: Int = getColumnIndexOrThrow(_stmt, "custom_alias")
        val _columnIndexOfUseProxy: Int = getColumnIndexOrThrow(_stmt, "use_proxy")
        val _result: MutableList<AiModel> = mutableListOf()
        while (_stmt.step()) {
          val _item: AiModel
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpProviderId: Long
          _tmpProviderId = _stmt.getLong(_columnIndexOfProviderId)
          val _tmpModelId: String
          _tmpModelId = _stmt.getText(_columnIndexOfModelId)
          val _tmpDisplayName: String
          _tmpDisplayName = _stmt.getText(_columnIndexOfDisplayName)
          val _tmpIsDefault: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsDefault).toInt()
          _tmpIsDefault = _tmp != 0
          val _tmpSyncStatus: String
          _tmpSyncStatus = _stmt.getText(_columnIndexOfSyncStatus)
          val _tmpIsEnabled: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp_1 != 0
          val _tmpCustomAlias: String
          _tmpCustomAlias = _stmt.getText(_columnIndexOfCustomAlias)
          val _tmpUseProxy: Boolean
          val _tmp_2: Int
          _tmp_2 = _stmt.getLong(_columnIndexOfUseProxy).toInt()
          _tmpUseProxy = _tmp_2 != 0
          _item = AiModel(_tmpId,_tmpProviderId,_tmpModelId,_tmpDisplayName,_tmpIsDefault,_tmpSyncStatus,_tmpIsEnabled,_tmpCustomAlias,_tmpUseProxy)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getModelsByProvider(providerId: Long): List<AiModel> {
    val _sql: String = "SELECT * FROM models WHERE provider_id = ? ORDER BY model_id ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, providerId)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfProviderId: Int = getColumnIndexOrThrow(_stmt, "provider_id")
        val _columnIndexOfModelId: Int = getColumnIndexOrThrow(_stmt, "model_id")
        val _columnIndexOfDisplayName: Int = getColumnIndexOrThrow(_stmt, "display_name")
        val _columnIndexOfIsDefault: Int = getColumnIndexOrThrow(_stmt, "is_default")
        val _columnIndexOfSyncStatus: Int = getColumnIndexOrThrow(_stmt, "sync_status")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "is_enabled")
        val _columnIndexOfCustomAlias: Int = getColumnIndexOrThrow(_stmt, "custom_alias")
        val _columnIndexOfUseProxy: Int = getColumnIndexOrThrow(_stmt, "use_proxy")
        val _result: MutableList<AiModel> = mutableListOf()
        while (_stmt.step()) {
          val _item: AiModel
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpProviderId: Long
          _tmpProviderId = _stmt.getLong(_columnIndexOfProviderId)
          val _tmpModelId: String
          _tmpModelId = _stmt.getText(_columnIndexOfModelId)
          val _tmpDisplayName: String
          _tmpDisplayName = _stmt.getText(_columnIndexOfDisplayName)
          val _tmpIsDefault: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsDefault).toInt()
          _tmpIsDefault = _tmp != 0
          val _tmpSyncStatus: String
          _tmpSyncStatus = _stmt.getText(_columnIndexOfSyncStatus)
          val _tmpIsEnabled: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp_1 != 0
          val _tmpCustomAlias: String
          _tmpCustomAlias = _stmt.getText(_columnIndexOfCustomAlias)
          val _tmpUseProxy: Boolean
          val _tmp_2: Int
          _tmp_2 = _stmt.getLong(_columnIndexOfUseProxy).toInt()
          _tmpUseProxy = _tmp_2 != 0
          _item = AiModel(_tmpId,_tmpProviderId,_tmpModelId,_tmpDisplayName,_tmpIsDefault,_tmpSyncStatus,_tmpIsEnabled,_tmpCustomAlias,_tmpUseProxy)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getModelById(id: Long): AiModel? {
    val _sql: String = "SELECT * FROM models WHERE id = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, id)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfProviderId: Int = getColumnIndexOrThrow(_stmt, "provider_id")
        val _columnIndexOfModelId: Int = getColumnIndexOrThrow(_stmt, "model_id")
        val _columnIndexOfDisplayName: Int = getColumnIndexOrThrow(_stmt, "display_name")
        val _columnIndexOfIsDefault: Int = getColumnIndexOrThrow(_stmt, "is_default")
        val _columnIndexOfSyncStatus: Int = getColumnIndexOrThrow(_stmt, "sync_status")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "is_enabled")
        val _columnIndexOfCustomAlias: Int = getColumnIndexOrThrow(_stmt, "custom_alias")
        val _columnIndexOfUseProxy: Int = getColumnIndexOrThrow(_stmt, "use_proxy")
        val _result: AiModel?
        if (_stmt.step()) {
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpProviderId: Long
          _tmpProviderId = _stmt.getLong(_columnIndexOfProviderId)
          val _tmpModelId: String
          _tmpModelId = _stmt.getText(_columnIndexOfModelId)
          val _tmpDisplayName: String
          _tmpDisplayName = _stmt.getText(_columnIndexOfDisplayName)
          val _tmpIsDefault: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsDefault).toInt()
          _tmpIsDefault = _tmp != 0
          val _tmpSyncStatus: String
          _tmpSyncStatus = _stmt.getText(_columnIndexOfSyncStatus)
          val _tmpIsEnabled: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp_1 != 0
          val _tmpCustomAlias: String
          _tmpCustomAlias = _stmt.getText(_columnIndexOfCustomAlias)
          val _tmpUseProxy: Boolean
          val _tmp_2: Int
          _tmp_2 = _stmt.getLong(_columnIndexOfUseProxy).toInt()
          _tmpUseProxy = _tmp_2 != 0
          _result = AiModel(_tmpId,_tmpProviderId,_tmpModelId,_tmpDisplayName,_tmpIsDefault,_tmpSyncStatus,_tmpIsEnabled,_tmpCustomAlias,_tmpUseProxy)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getDefaultModel(): AiModel? {
    val _sql: String = "SELECT * FROM models WHERE is_default = 1 LIMIT 1"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfProviderId: Int = getColumnIndexOrThrow(_stmt, "provider_id")
        val _columnIndexOfModelId: Int = getColumnIndexOrThrow(_stmt, "model_id")
        val _columnIndexOfDisplayName: Int = getColumnIndexOrThrow(_stmt, "display_name")
        val _columnIndexOfIsDefault: Int = getColumnIndexOrThrow(_stmt, "is_default")
        val _columnIndexOfSyncStatus: Int = getColumnIndexOrThrow(_stmt, "sync_status")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "is_enabled")
        val _columnIndexOfCustomAlias: Int = getColumnIndexOrThrow(_stmt, "custom_alias")
        val _columnIndexOfUseProxy: Int = getColumnIndexOrThrow(_stmt, "use_proxy")
        val _result: AiModel?
        if (_stmt.step()) {
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpProviderId: Long
          _tmpProviderId = _stmt.getLong(_columnIndexOfProviderId)
          val _tmpModelId: String
          _tmpModelId = _stmt.getText(_columnIndexOfModelId)
          val _tmpDisplayName: String
          _tmpDisplayName = _stmt.getText(_columnIndexOfDisplayName)
          val _tmpIsDefault: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsDefault).toInt()
          _tmpIsDefault = _tmp != 0
          val _tmpSyncStatus: String
          _tmpSyncStatus = _stmt.getText(_columnIndexOfSyncStatus)
          val _tmpIsEnabled: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp_1 != 0
          val _tmpCustomAlias: String
          _tmpCustomAlias = _stmt.getText(_columnIndexOfCustomAlias)
          val _tmpUseProxy: Boolean
          val _tmp_2: Int
          _tmp_2 = _stmt.getLong(_columnIndexOfUseProxy).toInt()
          _tmpUseProxy = _tmp_2 != 0
          _result = AiModel(_tmpId,_tmpProviderId,_tmpModelId,_tmpDisplayName,_tmpIsDefault,_tmpSyncStatus,_tmpIsEnabled,_tmpCustomAlias,_tmpUseProxy)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getModelByProviderAndId(providerId: Long, modelId: String): AiModel? {
    val _sql: String = "SELECT * FROM models WHERE model_id = ? AND provider_id = ? LIMIT 1"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, modelId)
        _argIndex = 2
        _stmt.bindLong(_argIndex, providerId)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfProviderId: Int = getColumnIndexOrThrow(_stmt, "provider_id")
        val _columnIndexOfModelId: Int = getColumnIndexOrThrow(_stmt, "model_id")
        val _columnIndexOfDisplayName: Int = getColumnIndexOrThrow(_stmt, "display_name")
        val _columnIndexOfIsDefault: Int = getColumnIndexOrThrow(_stmt, "is_default")
        val _columnIndexOfSyncStatus: Int = getColumnIndexOrThrow(_stmt, "sync_status")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "is_enabled")
        val _columnIndexOfCustomAlias: Int = getColumnIndexOrThrow(_stmt, "custom_alias")
        val _columnIndexOfUseProxy: Int = getColumnIndexOrThrow(_stmt, "use_proxy")
        val _result: AiModel?
        if (_stmt.step()) {
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpProviderId: Long
          _tmpProviderId = _stmt.getLong(_columnIndexOfProviderId)
          val _tmpModelId: String
          _tmpModelId = _stmt.getText(_columnIndexOfModelId)
          val _tmpDisplayName: String
          _tmpDisplayName = _stmt.getText(_columnIndexOfDisplayName)
          val _tmpIsDefault: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsDefault).toInt()
          _tmpIsDefault = _tmp != 0
          val _tmpSyncStatus: String
          _tmpSyncStatus = _stmt.getText(_columnIndexOfSyncStatus)
          val _tmpIsEnabled: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp_1 != 0
          val _tmpCustomAlias: String
          _tmpCustomAlias = _stmt.getText(_columnIndexOfCustomAlias)
          val _tmpUseProxy: Boolean
          val _tmp_2: Int
          _tmp_2 = _stmt.getLong(_columnIndexOfUseProxy).toInt()
          _tmpUseProxy = _tmp_2 != 0
          _result = AiModel(_tmpId,_tmpProviderId,_tmpModelId,_tmpDisplayName,_tmpIsDefault,_tmpSyncStatus,_tmpIsEnabled,_tmpCustomAlias,_tmpUseProxy)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getAllModelsOnce(): List<AiModel> {
    val _sql: String = "SELECT * FROM models ORDER BY provider_id ASC, model_id ASC"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfProviderId: Int = getColumnIndexOrThrow(_stmt, "provider_id")
        val _columnIndexOfModelId: Int = getColumnIndexOrThrow(_stmt, "model_id")
        val _columnIndexOfDisplayName: Int = getColumnIndexOrThrow(_stmt, "display_name")
        val _columnIndexOfIsDefault: Int = getColumnIndexOrThrow(_stmt, "is_default")
        val _columnIndexOfSyncStatus: Int = getColumnIndexOrThrow(_stmt, "sync_status")
        val _columnIndexOfIsEnabled: Int = getColumnIndexOrThrow(_stmt, "is_enabled")
        val _columnIndexOfCustomAlias: Int = getColumnIndexOrThrow(_stmt, "custom_alias")
        val _columnIndexOfUseProxy: Int = getColumnIndexOrThrow(_stmt, "use_proxy")
        val _result: MutableList<AiModel> = mutableListOf()
        while (_stmt.step()) {
          val _item: AiModel
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpProviderId: Long
          _tmpProviderId = _stmt.getLong(_columnIndexOfProviderId)
          val _tmpModelId: String
          _tmpModelId = _stmt.getText(_columnIndexOfModelId)
          val _tmpDisplayName: String
          _tmpDisplayName = _stmt.getText(_columnIndexOfDisplayName)
          val _tmpIsDefault: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsDefault).toInt()
          _tmpIsDefault = _tmp != 0
          val _tmpSyncStatus: String
          _tmpSyncStatus = _stmt.getText(_columnIndexOfSyncStatus)
          val _tmpIsEnabled: Boolean
          val _tmp_1: Int
          _tmp_1 = _stmt.getLong(_columnIndexOfIsEnabled).toInt()
          _tmpIsEnabled = _tmp_1 != 0
          val _tmpCustomAlias: String
          _tmpCustomAlias = _stmt.getText(_columnIndexOfCustomAlias)
          val _tmpUseProxy: Boolean
          val _tmp_2: Int
          _tmp_2 = _stmt.getLong(_columnIndexOfUseProxy).toInt()
          _tmpUseProxy = _tmp_2 != 0
          _item = AiModel(_tmpId,_tmpProviderId,_tmpModelId,_tmpDisplayName,_tmpIsDefault,_tmpSyncStatus,_tmpIsEnabled,_tmpCustomAlias,_tmpUseProxy)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteByProvider(providerId: Long) {
    val _sql: String = "DELETE FROM models WHERE provider_id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, providerId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun clearDefaultModel() {
    val _sql: String = "UPDATE models SET is_default = 0 WHERE is_default = 1"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updateSyncStatusByProvider(providerId: Long, status: String) {
    val _sql: String = "UPDATE models SET sync_status = ? WHERE provider_id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, status)
        _argIndex = 2
        _stmt.bindLong(_argIndex, providerId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteAll() {
    val _sql: String = "DELETE FROM models"
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
