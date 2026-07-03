package com.ossm.remote.data.db;

import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class PresetDao_Impl implements PresetDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<PresetEntity> __insertionAdapterOfPresetEntity;

  private final EntityDeletionOrUpdateAdapter<PresetEntity> __deletionAdapterOfPresetEntity;

  private final EntityDeletionOrUpdateAdapter<PresetEntity> __updateAdapterOfPresetEntity;

  private final SharedSQLiteStatement __preparedStmtOfDeleteById;

  public PresetDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfPresetEntity = new EntityInsertionAdapter<PresetEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `presets` (`id`,`name`,`patternKey`,`patternName`,`speed`,`depthMin`,`depthMax`,`createdAt`) VALUES (nullif(?, 0),?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final PresetEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindString(2, entity.getName());
        statement.bindString(3, entity.getPatternKey());
        statement.bindString(4, entity.getPatternName());
        statement.bindDouble(5, entity.getSpeed());
        statement.bindDouble(6, entity.getDepthMin());
        statement.bindDouble(7, entity.getDepthMax());
        statement.bindLong(8, entity.getCreatedAt());
      }
    };
    this.__deletionAdapterOfPresetEntity = new EntityDeletionOrUpdateAdapter<PresetEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "DELETE FROM `presets` WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final PresetEntity entity) {
        statement.bindLong(1, entity.getId());
      }
    };
    this.__updateAdapterOfPresetEntity = new EntityDeletionOrUpdateAdapter<PresetEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE OR ABORT `presets` SET `id` = ?,`name` = ?,`patternKey` = ?,`patternName` = ?,`speed` = ?,`depthMin` = ?,`depthMax` = ?,`createdAt` = ? WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final PresetEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindString(2, entity.getName());
        statement.bindString(3, entity.getPatternKey());
        statement.bindString(4, entity.getPatternName());
        statement.bindDouble(5, entity.getSpeed());
        statement.bindDouble(6, entity.getDepthMin());
        statement.bindDouble(7, entity.getDepthMax());
        statement.bindLong(8, entity.getCreatedAt());
        statement.bindLong(9, entity.getId());
      }
    };
    this.__preparedStmtOfDeleteById = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM presets WHERE id = ?";
        return _query;
      }
    };
  }

  @Override
  public Object insertPreset(final PresetEntity preset,
      final Continuation<? super Long> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Long>() {
      @Override
      @NonNull
      public Long call() throws Exception {
        __db.beginTransaction();
        try {
          final Long _result = __insertionAdapterOfPresetEntity.insertAndReturnId(preset);
          __db.setTransactionSuccessful();
          return _result;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object deletePreset(final PresetEntity preset,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __deletionAdapterOfPresetEntity.handle(preset);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object updatePreset(final PresetEntity preset,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __updateAdapterOfPresetEntity.handle(preset);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteById(final long id, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteById.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteById.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<PresetEntity>> getAllPresets() {
    final String _sql = "SELECT * FROM presets ORDER BY createdAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"presets"}, new Callable<List<PresetEntity>>() {
      @Override
      @NonNull
      public List<PresetEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfName = CursorUtil.getColumnIndexOrThrow(_cursor, "name");
          final int _cursorIndexOfPatternKey = CursorUtil.getColumnIndexOrThrow(_cursor, "patternKey");
          final int _cursorIndexOfPatternName = CursorUtil.getColumnIndexOrThrow(_cursor, "patternName");
          final int _cursorIndexOfSpeed = CursorUtil.getColumnIndexOrThrow(_cursor, "speed");
          final int _cursorIndexOfDepthMin = CursorUtil.getColumnIndexOrThrow(_cursor, "depthMin");
          final int _cursorIndexOfDepthMax = CursorUtil.getColumnIndexOrThrow(_cursor, "depthMax");
          final int _cursorIndexOfCreatedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "createdAt");
          final List<PresetEntity> _result = new ArrayList<PresetEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final PresetEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final String _tmpName;
            _tmpName = _cursor.getString(_cursorIndexOfName);
            final String _tmpPatternKey;
            _tmpPatternKey = _cursor.getString(_cursorIndexOfPatternKey);
            final String _tmpPatternName;
            _tmpPatternName = _cursor.getString(_cursorIndexOfPatternName);
            final float _tmpSpeed;
            _tmpSpeed = _cursor.getFloat(_cursorIndexOfSpeed);
            final float _tmpDepthMin;
            _tmpDepthMin = _cursor.getFloat(_cursorIndexOfDepthMin);
            final float _tmpDepthMax;
            _tmpDepthMax = _cursor.getFloat(_cursorIndexOfDepthMax);
            final long _tmpCreatedAt;
            _tmpCreatedAt = _cursor.getLong(_cursorIndexOfCreatedAt);
            _item = new PresetEntity(_tmpId,_tmpName,_tmpPatternKey,_tmpPatternName,_tmpSpeed,_tmpDepthMin,_tmpDepthMax,_tmpCreatedAt);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
