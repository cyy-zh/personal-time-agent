package io.github.cyyzh.personaltimeagent.domain

/** Conservative offline parser. Ambiguous messages stay in the inbox. */
object LocalCommand {
    fun interpret(text:String):ProposedChange? {
        val value=text.trim()
        val title=when {
            value.startsWith("添加任务") -> value.removePrefix("添加任务").trim(' ','：',':')
            value.startsWith("记下任务") -> value.removePrefix("记下任务").trim(' ','：',':')
            else -> return null
        }
        return title.takeIf{it.isNotBlank()}?.let{ProposedChange("CREATE_TASK",null,null,title=it)}
    }
}
