package io.github.cyyzh.personaltimeagent

import io.github.cyyzh.personaltimeagent.data.*
import io.github.cyyzh.personaltimeagent.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PolicyTest {
    private val zone=ZoneId.systemDefault()
    private val date=LocalDate.of(2026,11,13)
    private fun at(day:Int,h:Int,m:Int=0)=date.plusDays(day.toLong()).atTime(h,m).atZone(zone).toInstant().toEpochMilli()
    private val task=TaskEntity("t","Report",deadline=at(1,20),revision=2)
    private fun ch(kind:String="SCHEDULE_TASK",start:Long=at(0,9),end:Long=at(0,10),rev:Int?=2)=ProposedChange(kind,"t",rev,start=start,end=end)
    private fun check(c:ProposedChange,t:TaskEntity?=task,slots:List<SlotEntity> = emptyList(),confirmed:Boolean=true)=
        Policy.rejection(c,t,slots,null,null,confirmed)
    @Test fun approvalAndIdentityCases() {
        assertEquals("Needs your confirmation",check(ch(),confirmed=false)) // 1
        assertEquals("Target missing",check(ch(),t=null)) // 2
        assertEquals("Proposal is stale",check(ch(rev=1))) // 3
        assertEquals("Proposal is stale",check(ch(rev=null))) // 4
        assertNull(check(ch())) // 5
        assertEquals("Unsupported action",check(ch(kind="DELETE_TASK"))) // 6
        assertEquals("Title missing",check(ProposedChange("CREATE_TASK",null,null))) // 7
        assertNull(check(ProposedChange("CREATE_TASK",null,null,title="New"),t=null)) // 8
        assertEquals("Interval missing",check(ProposedChange("SCHEDULE_TASK","t",2))) // 9
        assertEquals("Invalid interval",check(ch(start=at(0,10),end=at(0,10)))) // 10
        assertEquals("Invalid interval",check(ch(start=at(0,11),end=at(0,10)))) // 11
    }
    @Test fun deadlineAndLockedCases() {
        assertEquals("Past real deadline",check(ch(start=at(1,19),end=at(1,21)))) // 12
        assertNull(check(ch(start=at(1,19),end=at(1,20)))) // 13
        assertEquals("Task is locked",check(ch(),t=task.copy(locked=true))) // 14
        assertEquals("Task is locked",check(ProposedChange("CHANGE_DEADLINE","t",2,deadline=at(2,12)),t=task.copy(locked=true))) // 15
        assertEquals("Deadline missing",check(ProposedChange("CHANGE_DEADLINE","t",2))) // 16
        assertNull(check(ProposedChange("COMPLETE_TASK","t",2))) // 17
        assertNull(check(ProposedChange("CHANGE_DEADLINE","t",2,deadline=at(2,12)))) // 18
    }
    @Test fun eventAndSleepCases() {
        val other=SlotEntity("s","other","Class",at(0,9),at(0,10))
        assertEquals("Conflicts with existing event",check(ch(),slots=listOf(other))) // 19
        assertEquals("Conflicts with existing event",check(ch(),slots=listOf(other.copy(locked=true)))) // 20
        assertEquals("Conflicts with existing event",check(ch(),slots=listOf(other.copy(protected=true)))) // 21
        assertNull(check(ch(),slots=listOf(other.copy(taskId="t")))) // 22
        assertNull(check(ch(start=at(0,10),end=at(0,11)),slots=listOf(other))) // 23
        assertEquals("Conflicts with protected sleep",check(ch(start=at(0,22,59),end=at(0,23,1)))) // 24
        assertEquals("Conflicts with protected sleep",check(ch(start=at(0,6,59),end=at(0,7,1)))) // 25
        assertNull(check(ch(start=at(0,7),end=at(0,8)))) // 26
        assertEquals("Conflicts with protected sleep",check(ch(start=at(0,23),end=at(1,1)))) // 27
        assertEquals("Conflicts with protected sleep",check(ch(start=at(0,22),end=at(1,8)))) // 28
    }
    @Test fun undoGuardCases() {
        val audit=AuditEntity("a","p","UPDATE_TASK","t","before","after")
        assertTrue(Policy.canUndo(audit,3,3)) // 29
        assertFalse(Policy.canUndo(audit,4,3)) // 30
        assertFalse(Policy.canUndo(audit,null,3)) // 31
        assertFalse(Policy.canUndo(audit.copy(undoneAt=1),3,3)) // 32
    }
    @Test fun actualTimeCases() {
        val rows=listOf(LedgerEntity("a",category="Study",startAt=0,endAt=100,source="MANUAL"),
            LedgerEntity("b",category="Usage",startAt=50,endAt=150,source="SYSTEM"),
            LedgerEntity("c",category="Plan",startAt=0,endAt=200,source="PLANNED"))
        assertEquals(150,TimeMath.unionMillis(rows.filter{it.source!="PLANNED"}.map{it.startAt to it.endAt})) // 33
        val by=TimeMath.byCategory(rows,0,200).associate{it.category to it.millis}
        assertEquals(100L,by["Study"]) // 34
        assertEquals(50L,by["Usage"]) // 35
        assertFalse(by.containsKey("Plan")) // 36
        assertEquals(0,TimeMath.unionMillis(listOf(5L to 5L,10L to 2L))) // 37
        assertEquals(30,TimeMath.unionMillis(listOf(0L to 20L,10L to 30L))) // 38
    }
}
