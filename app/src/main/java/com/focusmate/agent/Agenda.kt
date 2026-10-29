package com.focusmate.agent

/**
 * 安排簿：提醒、回访、复盘的唯一来源。纯函数，输入旧列表输出新列表。
 *
 * 移植自 dsh-world 生活运行时的几条行为（见 docs/SOURCES.md S3）：
 * 持久保存下次联系时间；新约定替换旧约定；投递失败有限重试；过期太久的事项不再发出。
 */
object Agenda {

    /** 回访迟到超过这么久就放弃（例如关机一夜），避免早上冒出一句昨晚的「复习得怎么样」。 */
    const val STALE_FOLLOW_UP_MILLIS: Long = 3L * 60 * 60 * 1000

    /** 生成失败的重试间隔（第 1、2、3 次失败后）。 */
    private val RETRY_MILLIS = longArrayOf(60_000L, 3 * 60_000L, 10 * 60_000L)

    /** 生成失败超过这个次数：回访放弃，复盘与提醒改用本地文案。 */
    const val MAX_ATTEMPTS: Int = 3

    /** 非待办记录最多留这么多条。 */
    private const val KEEP_DONE: Int = 200

    /**
     * 加入一条安排。同 key 的待办会被取消（记为被替换），
     * 这样「十分钟后再聊」会更新原来的回访，而不是新旧两条一起响。
     */
    fun upsert(list: List<AgendaItem>, item: AgendaItem, nowMillis: Long): List<AgendaItem> {
        val replaced = list.map {
            if (it.status == AgendaStatus.PENDING && it.key == item.key) {
                it.copy(status = AgendaStatus.CANCELLED, note = "被新的安排替换", doneAtMillis = nowMillis)
            } else {
                it
            }
        }
        return prune(replaced + item)
    }

    fun cancel(list: List<AgendaItem>, keyOrId: String, nowMillis: Long, note: String): Pair<List<AgendaItem>, Int> {
        var n = 0
        val out = list.map {
            if (it.status == AgendaStatus.PENDING && (it.id == keyOrId || it.key == keyOrId)) {
                n++
                it.copy(status = AgendaStatus.CANCELLED, note = note, doneAtMillis = nowMillis)
            } else {
                it
            }
        }
        return out to n
    }

    fun pending(list: List<AgendaItem>): List<AgendaItem> =
        list.filter { it.status == AgendaStatus.PENDING }.sortedBy { it.dueAtMillis }

    fun due(list: List<AgendaItem>, nowMillis: Long): List<AgendaItem> =
        pending(list).filter { it.dueAtMillis <= nowMillis }

    /** 下一次需要醒来的时刻；没有待办返回 null。 */
    fun nextDueAt(list: List<AgendaItem>): Long? = pending(list).minOfOrNull { it.dueAtMillis }

    /** 回访迟到太久就不发了。提醒和复盘不受此限：迟到也要送达，并注明迟到。 */
    fun isStale(item: AgendaItem, nowMillis: Long): Boolean =
        item.kind == AgendaKind.FOLLOW_UP && nowMillis - item.dueAtMillis > STALE_FOLLOW_UP_MILLIS

    fun markDelivered(list: List<AgendaItem>, id: String, nowMillis: Long, note: String? = null): List<AgendaItem> =
        prune(list.map { if (it.id == id) it.copy(status = AgendaStatus.DELIVERED, doneAtMillis = nowMillis, note = note ?: it.note) else it })

    fun markDropped(list: List<AgendaItem>, id: String, nowMillis: Long, note: String): List<AgendaItem> =
        prune(list.map { if (it.id == id) it.copy(status = AgendaStatus.DROPPED, doneAtMillis = nowMillis, note = note) else it })

    /** 推迟到某个时刻（安静时段、专注中），不算失败。 */
    fun defer(list: List<AgendaItem>, id: String, untilMillis: Long, note: String): List<AgendaItem> =
        list.map { if (it.id == id) it.copy(dueAtMillis = untilMillis, note = note) else it }

    /**
     * 生成失败：按次数退避重试。返回新列表与「是否已用尽重试」。
     */
    fun markFailed(list: List<AgendaItem>, id: String, nowMillis: Long, error: String): Pair<List<AgendaItem>, Boolean> {
        val item = list.firstOrNull { it.id == id } ?: return list to false
        val attempts = item.attempts + 1
        if (attempts >= MAX_ATTEMPTS) {
            return list.map { if (it.id == id) it.copy(attempts = attempts, note = error) else it } to true
        }
        val retryAt = nowMillis + RETRY_MILLIS[(attempts - 1).coerceIn(0, RETRY_MILLIS.size - 1)]
        return list.map { if (it.id == id) it.copy(attempts = attempts, dueAtMillis = retryAt, note = error) else it } to false
    }

    private fun prune(list: List<AgendaItem>): List<AgendaItem> {
        val done = list.filter { it.status != AgendaStatus.PENDING }
        if (done.size <= KEEP_DONE) return list
        val dropIds = done.sortedBy { it.doneAtMillis ?: it.createdAtMillis }
            .take(done.size - KEEP_DONE).map { it.id }.toSet()
        return list.filterNot { it.id in dropIds }
    }
}
