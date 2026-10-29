package com.focusmate.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FactEngineTest {

    private val t0 = 1_700_000_000_000L
    private fun fresh() = CompanionState(dayKey = "2026-10-29")

    // ---------------------------------------------------------------- 三种来源要可区分

    @Test
    fun `用户说的比模型猜的重要`() {
        val (iu, _) = FactEngine.score("我下周三要面试", FactSource.USER_STATED)
        val (im, _) = FactEngine.score("我下周三要面试", FactSource.MODEL_INFERRED)
        assertTrue("用户陈述应显著高于模型推测：$iu vs $im", iu > im)
    }

    @Test
    fun `模型推测永远标成推测 来源不被改写`() {
        val (s, f) = FactEngine.add(fresh(), "他好像有点累", FactSource.MODEL_INFERRED, null, t0)
        val stored = s.facts.first { it.id == f.id }
        assertEquals(FactSource.MODEL_INFERRED, stored.source)
        assertEquals("我猜的", stored.source.label())
    }

    @Test
    fun `系统观察的事实不会写成用户说的`() {
        val (_, f) = FactEngine.add(fresh(), "10 分钟专注已跑完", FactSource.SYSTEM_OBSERVED, "session:abc", t0)
        assertEquals(FactSource.SYSTEM_OBSERVED, f.source)
        assertEquals("session:abc", f.sourceRef)
    }

    @Test
    fun `太短的话重要度被压低`() {
        val (short, _) = FactEngine.score("嗯", FactSource.USER_STATED)
        val (long, _) = FactEngine.score("我下周三下午两点要去医院复查", FactSource.USER_STATED)
        assertTrue(short < long)
    }

    @Test
    fun `约定类内容重要度被抬高`() {
        val (plain, _) = FactEngine.score("今天天气一般", FactSource.USER_STATED)
        val (promise, _) = FactEngine.score("我们说好下次一起复盘", FactSource.USER_STATED)
        assertTrue(promise > plain)
    }

    // ---------------------------------------------------------------- 去重与复习

    @Test
    fun `同来源同文本两天内不重复占位 只加复习次数`() {
        val (s1, f1) = FactEngine.add(fresh(), "我在准备面试", FactSource.USER_STATED, null, t0)
        val (s2, f2) = FactEngine.add(s1, "我在准备面试", FactSource.USER_STATED, null, t0 + 1000)
        assertEquals(1, s2.facts.size)
        assertEquals(f1.id, f2.id)
        assertEquals(1, s2.facts.single().recallCount)
    }

    @Test
    fun `不同来源的同文本算两条 便于区分谁说的`() {
        val (s1, _) = FactEngine.add(fresh(), "最近很累", FactSource.USER_STATED, null, t0)
        val (s2, _) = FactEngine.add(s1, "最近很累", FactSource.MODEL_INFERRED, null, t0)
        assertEquals(2, s2.facts.size)
    }

    @Test
    fun `被提到算复习 只改次数不改内容`() {
        val (s1, f) = FactEngine.add(fresh(), "我在准备面试", FactSource.USER_STATED, null, t0)
        val s2 = FactEngine.touch(s1, "面试准备得怎么样了", t0 + 5000)
        val after = s2.facts.first { it.id == f.id }
        assertEquals(f.text, after.text)
        assertTrue(after.recallCount > f.recallCount)
    }

    // ---------------------------------------------------------------- 更正与忘记（核心）

    @Test
    fun `更正后旧事实退出上下文 新事实进入`() {
        val (s1, old) = FactEngine.add(fresh(), "我下周三要面试", FactSource.USER_STATED, null, t0)
        val (s2, freshFact) = FactEngine.correct(s1, old.id, "改成下周四面试", t0 + 1000)!!
        val recalled = FactEngine.recall(s2, "面试", limit = 10)
        assertTrue("新事实必须在上下文里", recalled.any { it.text == "改成下周四面试" })
        assertTrue("旧事实不能出现在上下文里", recalled.none { it.text == "我下周三要面试" })
        assertEquals(FactStatus.SUPERSEDED, s2.facts.first { it.id == old.id }.status)
        assertEquals(freshFact.id, s2.facts.first { it.id == old.id }.supersededBy)
    }

    @Test
    fun `忘记后不再出现在上下文里 但墓碑还在`() {
        val (s1, f) = FactEngine.add(fresh(), "我讨厌咖啡", FactSource.USER_STATED, null, t0)
        val s2 = FactEngine.forget(s1, f.id, t0 + 1000)!!
        assertTrue(FactEngine.recall(s2, "咖啡", limit = 10).isEmpty())
        assertEquals(FactStatus.RETRACTED, s2.facts.first { it.id == f.id }.status)
        assertEquals(1, s2.facts.size)
    }

    @Test
    fun `忘记不存在的条目返回 null`() {
        assertNull(FactEngine.forget(fresh(), "nope", t0))
    }

    @Test
    fun `更正不存在的条目返回 null`() {
        assertNull(FactEngine.correct(fresh(), "nope", "新内容", t0))
    }

    @Test
    fun `墓碑不会被同样的文本重新激活`() {
        val (s1, f) = FactEngine.add(fresh(), "我讨厌咖啡", FactSource.USER_STATED, null, t0)
        val s2 = FactEngine.forget(s1, f.id, t0 + 1000)!!
        // 用户重复同一句，会新增一条 ACTIVE（这是"又说了一次"，不是"复活的旧记录"）
        val (s3, _) = FactEngine.add(s2, "我讨厌咖啡", FactSource.USER_STATED, null, t0 + 2000)
        assertEquals(2, s3.facts.size)
        assertEquals(1, s3.facts.count { it.status == FactStatus.RETRACTED })
        assertEquals(1, s3.facts.count { it.status == FactStatus.ACTIVE })
    }

    // ---------------------------------------------------------------- 召回

    @Test
    fun `召回按相关度与重要度排序 且有上限`() {
        var s = fresh()
        repeat(12) { i ->
            val (ns, _) = FactEngine.add(s, "常驻事实编号$i 关于专注", FactSource.SYSTEM_OBSERVED, null, t0 + i)
            s = ns
        }
        val (s2, _) = FactEngine.add(s, "我下周三要面试", FactSource.USER_STATED, null, t0 + 100)
        val recalled = FactEngine.recall(s2, "面试", limit = 5)
        assertTrue(recalled.size <= 5)
        assertEquals("我下周三要面试", recalled.first().text)
    }

    @Test
    fun `空查询也会按重要度给出一条上下文 不返回空`() {
        val (s, _) = FactEngine.add(fresh(), "我下周三要面试", FactSource.USER_STATED, null, t0)
        val recalled = FactEngine.recall(s, "", limit = 5)
        assertEquals(1, recalled.size)
    }

    @Test
    fun `分词能切出中文双字并去停用词`() {
        val tokens = FactEngine.tokensOf("我们今天面试准备")
        assertTrue(tokens.isNotEmpty())
        assertTrue(tokens.none { it == "今天" })
    }
}
