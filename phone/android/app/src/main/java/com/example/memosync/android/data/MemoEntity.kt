package com.example.memosync.android.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.memosync.core.MemoRecord

/**
 * NOT COMPILED IN THIS POC - Android source skeleton only (Room entity outline).
 *
 * Mirrors design doc §3.3 `memos` table and the tested Java core record
 * [com.example.memosync.core.MemoRecord]. The mapping functions are the single conversion point
 * between Room (snake_case columns) and the sync core (long-key record model).
 */
@Entity(
    tableName = "memos",
    indices = [Index("updated_hlc"), Index("deleted")],
)
data class MemoEntity(
    @PrimaryKey val id: String,
    val title: String,
    val body: String,
    @ColumnInfo(name = "created_hlc") val createdHlc: String,
    @ColumnInfo(name = "updated_hlc") val updatedHlc: String,
    @ColumnInfo(name = "title_hlc") val titleHlc: String,
    @ColumnInfo(name = "body_hlc") val bodyHlc: String,
    val deleted: Boolean,
    @ColumnInfo(name = "deleted_hlc") val deletedHlc: String?,
    val rev: Int,
    @ColumnInfo(name = "last_synced_hlc") val lastSyncedHlc: String? = null,
)

/**
 * Per-peer three-way merge base (design doc §3.3 `memo_peer_base`).
 *
 * NOTE: the design-doc v1 schema only lists base_hlc/base_title/base_body, but the POC merge
 * contract (`shared/PROTOCOL.md` §2) compares base.bodyHlc to decide conflicts, so the base's
 * field HLCs are stored too (or, alternatively, snapshot the whole base record as JSON).
 */
@Entity(
    tableName = "memo_peer_base",
    primaryKeys = ["peer_id", "memo_id"],
)
data class MemoPeerBaseEntity(
    @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "memo_id") val memoId: String,
    @ColumnInfo(name = "base_updated_hlc") val baseUpdatedHlc: String,
    @ColumnInfo(name = "base_title") val baseTitle: String,
    @ColumnInfo(name = "base_body") val baseBody: String,
    @ColumnInfo(name = "base_title_hlc") val baseTitleHlc: String,
    @ColumnInfo(name = "base_body_hlc") val baseBodyHlc: String,
)

/** Per-peer sync cursors (design doc §3.3 `sync_state`). */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "last_pull_hlc") val lastPullHlc: String?,
    @ColumnInfo(name = "last_push_hlc") val lastPushHlc: String?,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long?,
    val status: String?,
)

/** Local conflict archive (design doc §3.3 `conflicts`; v1.0 UI hook). */
@Entity(
    tableName = "conflicts",
    foreignKeys = [
        ForeignKey(
            entity = MemoEntity::class,
            parentColumns = ["id"],
            childColumns = ["memo_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("memo_id")],
)
data class ConflictEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "memo_id") val memoId: String,
    @ColumnInfo(name = "local_json") val localJson: String,
    @ColumnInfo(name = "remote_json") val remoteJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val resolved: Boolean,
)

// ---------------------------------------------------------------------------
// Core mapping: Room <- -> Java 17 POC sync core
// Wire-only MemoRecord fields ("base"/"force") are intentionally not persisted:
// "base" lives in memo_peer_base, "force" is only meaningful inside a pull message.
// ---------------------------------------------------------------------------

fun MemoEntity.toCoreRecord(): MemoRecord = MemoRecord(
    id, title, body, createdHlc, updatedHlc, titleHlc, bodyHlc, deleted, deletedHlc, rev,
)

fun MemoRecord.toEntity(lastSynced: String? = null): MemoEntity = MemoEntity(
    id = id,
    title = title,
    body = body ?: "",
    createdHlc = created ?: "",
    updatedHlc = updated ?: "",
    titleHlc = titleHlc ?: "",
    bodyHlc = bodyHlc ?: "",
    deleted = deleted,
    deletedHlc = deletedHlc,
    rev = rev,
    lastSyncedHlc = lastSynced,
)
