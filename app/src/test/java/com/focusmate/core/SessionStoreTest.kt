package com.focusmate.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `写入后能原样读回`() {
        val store = FileSessionStore(tmp.newFile("session.json"))
        val clock = FakeClock()
        val session = Presets.geminiDock("com.google.android.apps.bard", clock.nowMillis(), Enforcement.OVERLAY_PLUS_A11Y)
        store.write(session)
        val back = store.read()
        assertNotNull(back)
        assertEquals(session.id, back!!.id)
        assertEquals(session.targetPackage, back.targetPackage)
        assertEquals(session.expiresAtMillis, back.expiresAtMillis)
        assertEquals(session.exceptions.size, back.exceptions.size)
    }

    @Test
    fun `没有文件时返回 null 且无错误`() {
        val store = FileSessionStore(tmp.newFile("absent.json").also { it.delete() })
        assertNull(store.read())
        assertNull(store.lastError)
    }

    @Test
    fun `文件损坏时返回 null 但留下错误原因 不静默`() {
        val f = tmp.newFile("broken.json")
        f.writeText("{ not json")
        val store = FileSessionStore(f)
        assertNull(store.read())
        assertNotNull(store.lastError)
        assertTrue(store.lastError!!.contains("读取会话失败"))
    }

    @Test
    fun `重复写入保持单份 不留临时文件`() {
        val f = java.io.File(tmp.root, "session.json")
        val store = FileSessionStore(f)
        val clock = FakeClock()
        repeat(3) { store.write(Presets.geminiDock("com.google.android.apps.bard", clock.nowMillis(), Enforcement.OVERLAY)) }
        val siblings = f.parentFile!!.listFiles()!!.map { it.name }
        assertEquals(listOf("session.json"), siblings)
        assertNotNull(store.read())
    }

    @Test
    fun `clear 之后不再读到会话`() {
        val f = java.io.File(tmp.root, "session.json")
        val store = FileSessionStore(f)
        store.write(Presets.geminiDock("com.google.android.apps.bard", 1L, Enforcement.OVERLAY))
        store.clear()
        assertNull(store.read())
    }
}
