package io.github.cyyzh.personaltimeagent.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tasks", indices = [Index("status"), Index("goalId")])
data class TaskEntity(
    @PrimaryKey val id: String, val title: String, val type: String = "TASK", val notes: String = "",
    val importance: Int = 2, val status: String = "OPEN", val deadline: Long? = null,
    val plannedCompletion: Long? = null, val scheduledStart: Long? = null, val scheduledEnd: Long? = null,
    val estimatedMinutes: Int = 30, val actualMinutes: Int = 0, val goalId: String? = null,
    val reminderAt: Long? = null, val source: String = "USER", val locked: Boolean = false,
    val revision: Int = 0, val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = System.currentTimeMillis()
)
@Entity(tableName = "slots", indices = [Index("startAt"), Index("taskId")])
data class SlotEntity(@PrimaryKey val id: String, val taskId: String?, val title: String, val startAt: Long,
    val endAt: Long, val kind: String = "TASK", val locked: Boolean = false, val protected: Boolean = false,
    val source: String = "USER")
@Entity(tableName = "goals")
data class GoalEntity(@PrimaryKey val id: String, val title: String, val description: String = "",
    val targetAt: Long? = null, val targetCount: Int = 0, val period: String = "WEEK",
    val status: String = "ACTIVE", val important: Boolean = false, val createdAt: Long = System.currentTimeMillis())
@Entity(tableName = "habits")
data class HabitEntity(@PrimaryKey val id: String, val goalId: String? = null, val title: String,
    val frequencyPerWeek: Int = 3, val minutes: Int = 30, val preferredHour: Int? = null,
    val enabled: Boolean = true, val category: String = "OTHER")
@Entity(tableName = "habit_occurrences", indices = [Index("habitId"), Index("scheduledAt")])
data class HabitOccurrenceEntity(@PrimaryKey val id: String, val habitId: String, val scheduledAt: Long,
    val completedAt: Long? = null, val skipped: Boolean = false)
@Entity(tableName = "ledger", indices = [Index("startAt"), Index("taskId")])
data class LedgerEntity(@PrimaryKey val id: String, val taskId: String? = null, val category: String,
    val startAt: Long, val endAt: Long, val source: String = "MANUAL", val confidence: Float = 1f,
    val appPackage: String? = null, val evidence: String = "", val confirmed: Boolean = true,
    val createdAt: Long = System.currentTimeMillis())
@Entity(tableName = "proposals", indices = [Index("state")])
data class ProposalEntity(@PrimaryKey val id: String, val kind: String, val payload: String,
    val summary: String, val reason: String = "", val state: String = "PENDING",
    val targetId: String? = null, val targetRevision: Int? = null,
    val source: String = "AGENT", val createdAt: Long = System.currentTimeMillis(), val resolvedAt: Long? = null)
@Entity(tableName = "audit", indices = [Index("targetId")])
data class AuditEntity(@PrimaryKey val id: String, val proposalId: String?, val action: String,
    val targetId: String, val beforeJson: String?, val afterJson: String?, val occurredAt: Long = System.currentTimeMillis(),
    val undoneAt: Long? = null)
@Entity(tableName = "inbox")
data class InboxEntity(@PrimaryKey val id: String, val text: String, val source: String = "USER",
    val status: String = "QUEUED", val receivedAt: Long = System.currentTimeMillis(), val error: String? = null)
@Entity(tableName = "memories")
data class MemoryEntity(@PrimaryKey val id: String, val key: String, val value: String, val evidenceCount: Int,
    val source: String, val updatedAt: Long = System.currentTimeMillis())
@Entity(tableName = "usage_samples", indices = [Index("dayStart"), Index("packageName")])
data class UsageSampleEntity(@PrimaryKey val id: String, val dayStart: Long, val packageName: String,
    val foregroundMillis: Long, val source: String = "USAGE_STATS")

@Dao interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY status ASC, scheduledStart ASC, deadline ASC") fun observeAll(): Flow<List<TaskEntity>>
    @Query("SELECT * FROM tasks WHERE id=:id LIMIT 1") suspend fun get(id: String): TaskEntity?
    @Query("SELECT * FROM tasks WHERE status='OPEN' ORDER BY scheduledStart ASC, deadline ASC") suspend fun open(): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE source=:source LIMIT 1") suspend fun bySource(source:String):TaskEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(task: TaskEntity)
    @Query("DELETE FROM tasks WHERE id=:id") suspend fun delete(id: String)
}
@Dao interface SlotDao {
    @Query("SELECT * FROM slots ORDER BY startAt") fun observeAll(): Flow<List<SlotEntity>>
    @Query("SELECT * FROM slots WHERE endAt>:start AND startAt<:end ORDER BY startAt") suspend fun inRange(start: Long,end: Long): List<SlotEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(slot: SlotEntity)
    @Query("DELETE FROM slots WHERE id=:id") suspend fun delete(id: String)
}
@Dao interface GoalDao {
    @Query("SELECT * FROM goals ORDER BY createdAt DESC") fun observeAll(): Flow<List<GoalEntity>>
    @Query("SELECT * FROM goals") suspend fun all(): List<GoalEntity>
    @Query("SELECT * FROM goals WHERE id=:id") suspend fun get(id:String):GoalEntity?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsert(value:GoalEntity)
    @Query("DELETE FROM goals WHERE id=:id") suspend fun delete(id:String)
}
@Dao interface HabitDao {
    @Query("SELECT * FROM habits") fun observeAll():Flow<List<HabitEntity>>
    @Query("SELECT * FROM habits WHERE enabled=1") suspend fun active():List<HabitEntity>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsert(value:HabitEntity)
    @Query("SELECT * FROM habits WHERE id=:id LIMIT 1") suspend fun get(id:String):HabitEntity?
    @Query("DELETE FROM habits WHERE id=:id") suspend fun delete(id:String)
    @Query("SELECT * FROM habit_occurrences WHERE scheduledAt>=:start AND scheduledAt<:end") suspend fun occurrences(start:Long,end:Long):List<HabitOccurrenceEntity>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsertOccurrence(value:HabitOccurrenceEntity)
}
@Dao interface LedgerDao {
    @Query("SELECT * FROM ledger ORDER BY startAt DESC") fun observeAll():Flow<List<LedgerEntity>>
    @Query("SELECT * FROM ledger WHERE endAt>:start AND startAt<:end ORDER BY startAt") suspend fun inRange(start:Long,end:Long):List<LedgerEntity>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsert(value:LedgerEntity)
    @Query("SELECT * FROM ledger WHERE id=:id LIMIT 1") suspend fun get(id:String):LedgerEntity?
    @Query("DELETE FROM ledger WHERE id=:id") suspend fun delete(id:String)
}
@Dao interface ProposalDao {
    @Query("SELECT * FROM proposals WHERE state='PENDING' ORDER BY createdAt DESC") fun observePending():Flow<List<ProposalEntity>>
    @Query("SELECT * FROM proposals WHERE id=:id") suspend fun get(id:String):ProposalEntity?
    @Query("SELECT * FROM proposals WHERE source=:source AND state!='REJECTED' LIMIT 1") suspend fun bySource(source:String):ProposalEntity?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsert(value:ProposalEntity)
}
@Dao interface AuditDao {
    @Query("SELECT * FROM audit ORDER BY occurredAt DESC") fun observeAll():Flow<List<AuditEntity>>
    @Query("SELECT * FROM audit WHERE id=:id") suspend fun get(id:String):AuditEntity?
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsert(value:AuditEntity)
}
@Dao interface InboxDao {
    @Query("SELECT * FROM inbox ORDER BY receivedAt DESC") fun observeAll():Flow<List<InboxEntity>>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsert(value:InboxEntity)
    @Query("SELECT * FROM inbox WHERE id=:id") suspend fun get(id:String):InboxEntity?
}
@Dao interface MemoryDao {
    @Query("SELECT * FROM memories") suspend fun all():List<MemoryEntity>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsert(value:MemoryEntity)
}
@Dao interface UsageDao {
    @Query("SELECT * FROM usage_samples WHERE dayStart>=:start AND dayStart<:end ORDER BY dayStart DESC") fun observeRange(start:Long,end:Long):Flow<List<UsageSampleEntity>>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun upsert(value:UsageSampleEntity)
}

@Database(entities=[TaskEntity::class,SlotEntity::class,GoalEntity::class,HabitEntity::class,HabitOccurrenceEntity::class,
    LedgerEntity::class,ProposalEntity::class,AuditEntity::class,InboxEntity::class,MemoryEntity::class,UsageSampleEntity::class], version=1, exportSchema=true)
abstract class AgentDatabase:RoomDatabase() {
    abstract fun taskDao():TaskDao
    abstract fun slotDao():SlotDao
    abstract fun goalDao():GoalDao
    abstract fun habitDao():HabitDao
    abstract fun ledgerDao():LedgerDao
    abstract fun proposalDao():ProposalDao
    abstract fun auditDao():AuditDao
    abstract fun inboxDao():InboxDao
    abstract fun memoryDao():MemoryDao
    abstract fun usageDao():UsageDao
    companion object {
        @Volatile private var instance:AgentDatabase?=null
        fun get(context:Context):AgentDatabase=instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,AgentDatabase::class.java,"personal_time_agent.db").build().also{instance=it}
        }
    }
}
