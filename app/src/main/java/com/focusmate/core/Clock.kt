package com.focusmate.core

/**
 * 时间来源。分别使用墙钟与单调时钟：
 * 墙钟用于「什么时候到期」这种必须跨重启判断的时刻；
 * 单调时钟用于「这次开机内还剩多久」，避免用户改系统时间把倒计时改乱。
 */
interface WallClock {
    fun nowMillis(): Long
}

/**
 * 单调时钟。只保证同一开机内单调不减，重启后可能归零，
 * 因此任何持久化数据都不能存这个值，只能存墙钟时刻。
 */
interface MonoClock {
    fun elapsedMillis(): Long
}

class SystemWallClock : WallClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

class SystemMonoClock : MonoClock {
    override fun elapsedMillis(): Long = android.os.SystemClock.elapsedRealtime()
}

/** 测试用时钟：可任意推进，两个时钟分别独立。 */
class FakeClock(
    private var wall: Long = 1_700_000_000_000L,
    private var mono: Long = 0L,
) : WallClock, MonoClock {
    override fun nowMillis(): Long = wall
    override fun elapsedMillis(): Long = mono

    fun advance(millis: Long) {
        require(millis >= 0) { "时间不能倒流" }
        wall += millis
        mono += millis
    }

    /** 模拟用户改系统时间：只有墙钟动，单调时钟不动。 */
    fun jumpWall(millis: Long) {
        wall += millis
    }

    /** 模拟重启：单调时钟归零，墙钟继续。 */
    fun reboot(newMonoBase: Long = 0L) {
        mono = newMonoBase
    }
}
