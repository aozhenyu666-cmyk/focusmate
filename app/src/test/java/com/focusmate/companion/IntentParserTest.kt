package com.focusmate.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IntentParserTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `十分钟后再聊 解析成推迟十分钟`() {
        val it0 = IntentParser.parse("十分钟后再聊", t0, "复盘")!!
        assertEquals(IntentParser.Kind.POSTPONE, it0.kind)
        assertEquals(t0 + 10 * 60_000L, it0.dueAtMillis)
        assertEquals("复盘", it0.topic)
    }

    @Test
    fun `半小时后再说 解析成三十分钟`() {
        val it0 = IntentParser.parse("过半小时再说", t0, null)!!
        assertEquals(t0 + 30 * 60_000L, it0.dueAtMillis)
    }

    @Test
    fun `半小时后再说 不带过字也认`() {
        val it0 = IntentParser.parse("半小时后再聊", t0, null)!!
        assertEquals(t0 + 30 * 60_000L, it0.dueAtMillis)
    }

    @Test
    fun `两小时后接着聊 中文数字也解析`() {
        val it0 = IntentParser.parse("两小时后接着聊", t0, null)!!
        assertEquals(t0 + 2 * 3_600_000L, it0.dueAtMillis)
        val it2 = IntentParser.parse("2小时后接着聊", t0, null)!!
        assertEquals(t0 + 2 * 3_600_000L, it2.dueAtMillis)
    }

    @Test
    fun `中文数字解析只处理 1 到 99 常见写法`() {
        assertEquals(10, IntentParser.chineseNumber("十"))
        assertEquals(11, IntentParser.chineseNumber("十一"))
        assertEquals(20, IntentParser.chineseNumber("二十"))
        assertEquals(23, IntentParser.chineseNumber("二十三"))
        assertEquals(2, IntentParser.chineseNumber("两"))
        assertNull(IntentParser.chineseNumber("百"))
        assertNull(IntentParser.chineseNumber(""))
    }

    @Test
    fun `等下再聊 解析成默认十分钟`() {
        val it0 = IntentParser.parse("等下再聊吧", t0, null)!!
        assertEquals(t0 + 10 * 60_000L, it0.dueAtMillis)
    }

    @Test
    fun `说不用提醒了 解析成取消`() {
        val it0 = IntentParser.parse("不用再提醒我了", t0, "复盘")!!
        assertEquals(IntentParser.Kind.CANCEL, it0.kind)
        assertEquals("复盘", it0.topic)
    }

    @Test
    fun `要求缩短专注 解析成锁定变更请求 而不是改期`() {
        val it0 = IntentParser.parse("帮我缩短专注时间", t0, null)!!
        assertEquals(IntentParser.Kind.LOCK_CHANGE_REQUEST, it0.kind)
        assertNull(it0.dueAtMillis)
    }

    @Test
    fun `普通聊天不产生任何意图`() {
        assertNull(IntentParser.parse("今天天气不错", t0, null))
        assertNull(IntentParser.parse("", t0, null))
    }

    @Test
    fun `改期必须留下依据片段`() {
        val it0 = IntentParser.parse("我们十分钟后再聊这个", t0, "复盘")!!
        assertNotNull(it0.evidence)
        assertEquals(true, it0.evidence.isNotBlank())
    }
}
