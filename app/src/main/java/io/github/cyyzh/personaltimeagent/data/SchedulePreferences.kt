package io.github.cyyzh.personaltimeagent.data

import android.content.Context

class SchedulePreferences(context:Context) {
    private val prefs=context.getSharedPreferences("schedule",Context.MODE_PRIVATE)
    val sleepStartHour:Int get()=prefs.getInt("sleep_start",23)
    val sleepEndHour:Int get()=prefs.getInt("sleep_end",7)
    fun setSleepHours(start:Int,end:Int):Boolean {
        require(start in 0..23 && end in 0..23 && start!=end)
        return prefs.edit().putInt("sleep_start",start).putInt("sleep_end",end).commit()
    }
}
