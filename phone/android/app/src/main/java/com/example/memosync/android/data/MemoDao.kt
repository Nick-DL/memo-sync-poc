package com.example.memosync.android.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.memosync.core.Hlc
import com.example.memosync.core.MemoRecord
import com.example.memosync.sync.MemoStore
import com.example.memosync.sync.PeerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * NOT COMPILED IN THIS POC - Android source skeleton only.
 *
 * Room DAOs + [RoomMemoStore], the production implementation of the tested Java core
 * repository interface [com.example.memosync.sync.MemoStore] (design doc §3.3 / §5.1).
 *
 * `runBlocking(Dispatchers.IO)` is a POC-skeleton shortcut: the Java POC core calls the store
 * synchronously from transport callbacks. In the real app, run the sync engine on a dedicated
 * IO dispatcher (or make the store suspend) instead of blocking a Binder/main thread.
 */
@Dao
interface MemoDao {
    @Query("SELECT * FROM memos WHERE deleted = 0 ORDER BY updated_hlc DESC")
    suspend fun listVisible(): List<MemoEntity>

    @Query("SELECT * FROM memos WHERE id = :id")
    suspend fun find(id: String): MemoEntity?

    @Query("SELECT * FROM memos")
    suspend fun all(): List<MemoEntity>

    @Query("SELECT * FROM memos WHERE updated_hlc IS NOT NULL")
    suspend fun allWithUpdatedHlc(): List<MemoEntity>

    @Upsert
    suspend fun upsert(entity: MemoEntity)

    @Query("DELETE FROM memos WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface PeerBaseDao {
    @Query("SELECT * FROM memo_peer_base WHERE peer_id = :peerId AND memo_id = :memoId")
    suspend fun find(peerId: String, memoId: String): MemoPeerBaseEntity?

    @Upsert
    suspend fun upsert(entity: MemoPeerBaseEntity)

    @Query("DELETE FROM memo_peer_base WHERE peer_id = :peerId AND memo_id = :memoId")
    suspend fun delete(peerId: String, memoId: String)
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE peer_id = :peerId")
    suspend fun find(peerId: String): SyncStateEntity?

    @Upsert
    suspend fun upsert(entity: SyncStateEntity)
}

@Dao
interface ConflictDao {
    @Query("SELECT * FROM conflicts WHERE resolved = 0 ORDER BY created_at DESC")
    suspend fun unresolved(): List<ConflictEntity>

    @Upsert
    suspend fun upsert(entity: ConflictEntity)
}

/**
 * Adapts Room to the Java 17 sync core repository contract. This is the only class where the
 * sync core touches the Android database.
 */
class RoomMemoStore(private val database: AppDatabase) : MemoStore {

    private val memoDao get() = database.memoDao()
    private val peerBaseDao get() = database.peerBaseDao()
    private val syncStateDao get() = database.syncStateDao()

    override fun get(id: String): MemoRecord? = runBlocking(Dispatchers.IO) {
        memoDao.find(id)?.toCoreRecord()
    }

    override fun put(record: MemoRecord) = runBlocking(Dispatchers.IO) {
        memoDao.upsert(record.toEntity(lastSynced = record.updated))
    }

    override fun all(): List<MemoRecord> = runBlocking(Dispatchers.IO) {
        memoDao.all().map { it.toCoreRecord() }
    }

    /**
     * TODO(Android): use an indexed HLC range query once the collation is validated; for the
     * skeleton we filter with the core HLC comparator, which is authority for ordering.
     */
    override fun changedSince(cursorHlc: String?): List<MemoRecord> = runBlocking(Dispatchers.IO) {
        memoDao.allWithUpdatedHlc()
            .filter { cursorHlc == null || Hlc.isAfter(it.updatedHlc, cursorHlc) }
            .map { it.toCoreRecord() }
    }

    override fun getBase(peerId: String?, memoId: String?): MemoRecord? {
        if (peerId == null || memoId == null) {
            return null
        }
        return runBlocking(Dispatchers.IO) {
            peerBaseDao.find(peerId, memoId)?.toBaseRecord()
        }
    }

    override fun saveBase(peerId: String?, memoId: String?, canonical: MemoRecord) {
        if (peerId == null || memoId == null) {
            return
        }
        runBlocking(Dispatchers.IO) {
            peerBaseDao.upsert(canonical.toPeerBaseEntity(peerId, memoId))
        }
    }

    override fun getPeerState(peerId: String?): PeerState? {
        if (peerId == null) {
            return null
        }
        return runBlocking(Dispatchers.IO) {
            syncStateDao.find(peerId)?.let {
                PeerState(it.peerId, it.lastPullHlc, it.lastPushHlc)
            }
        }
    }

    override fun savePeerState(peerState: PeerState) {
        runBlocking(Dispatchers.IO) {
            val existing = syncStateDao.find(peerState.peerId)
            syncStateDao.upsert(
                SyncStateEntity(
                    peerId = peerState.peerId,
                    lastPullHlc = peerState.lastPullHlc,
                    lastPushHlc = peerState.lastPushHlc,
                    lastSyncAt = System.currentTimeMillis(),
                    status = existing?.status,
                )
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Entity <-> core record mapping
// ---------------------------------------------------------------------------

private fun MemoRecord.toPeerBaseEntity(peerId: String, memoId: String): MemoPeerBaseEntity =
    MemoPeerBaseEntity(
        peerId = peerId,
        memoId = memoId,
        baseUpdatedHlc = updated ?: "",
        baseTitle = title ?: "",
        baseBody = body ?: "",
        baseTitleHlc = titleHlc ?: "",
        baseBodyHlc = bodyHlc ?: "",
    )

private fun MemoPeerBaseEntity.toBaseRecord(): MemoRecord = MemoRecord(
    memoId, baseTitle, baseBody, baseUpdatedHlc, baseUpdatedHlc,
    baseTitleHlc, baseBodyHlc, false, null, 0,
)
