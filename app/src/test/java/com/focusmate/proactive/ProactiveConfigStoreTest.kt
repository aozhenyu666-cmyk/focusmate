package com.focusmate.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 配置落盘。
 *
 * 这组测试盯的是「改完重启还在」这条承诺：
 * 原来 updateConfig 只 persist 状态、不 persist 配置，设置页上却写着「立刻生效」，
 * 实际进程一重启就回默认值。
 */
class ProactiveConfigStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `写入后能原样读回`() {
        val store = FileProactiveConfigStore(File(tmp.root, "proactive/config.json"))
        store.write(
            ProactiveConfig(
                hourlyOpportunities = 12,
                quietStartMinute = 0,
                quietEndMinute = 0,
                dailyMax = 5,
            ),
        )

        val back = store.read()
        assertEquals(12, back?.hourlyOpportunities)
        assertEquals(0, back?.quietStartMinute)
        assertEquals(5, back?.dailyMax)
    }

    @Test
    fun `覆盖写入读回的是新值`() {
        val store = FileProactiveConfigStore(File(tmp.root, "proactive/config.json"))
        store.write(ProactiveConfig(hourlyOpportunities = 3))
        store.write(ProactiveConfig(hourlyOpportunities = 12))

        assertEquals(12, store.read()?.hourlyOpportunities)
    }

    @Test
    fun `没有文件时返回 null 而不是默认值`() {
        // 调用方靠 null 判断「用户没设过」，自己退回默认；不能在这里替它决定。
        val store = FileProactiveConfigStore(File(tmp.root, "nope/config.json"))
        assertNull(store.read())
        assertNull(store.lastError)
    }

    @Test
    fun `文件损坏时返回 null 并留下错误说明`() {
        val f = File(tmp.root, "proactive/config.json")
        f.parentFile?.mkdirs()
        f.writeText("{ 这不是合法 JSON", Charsets.UTF_8)

        val store = FileProactiveConfigStore(f)
        assertNull(store.read())
        assertTrue(
            "损坏要说清楚，不能静默吞掉",
            store.lastError?.contains("读取主动配置失败") == true,
        )
    }

    @Test
    fun `目录不存在时会自己建出来`() {
        // 首次写入时 proactive/ 这个目录还没人建过。
        val nested = File(tmp.root, "a/b/c/config.json")
        val store = FileProactiveConfigStore(nested)
        store.write(ProactiveConfig(hourlyOpportunities = 6))

        assertTrue(nested.exists())
        assertEquals(6, store.read()?.hourlyOpportunities)
    }
}
