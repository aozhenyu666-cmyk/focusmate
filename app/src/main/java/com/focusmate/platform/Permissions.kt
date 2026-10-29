package com.focusmate.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils

/**
 * 权限状态读取。这里只回答「现在到底能不能」，不负责申请；
 * 调用方拿到 false 必须解释能力差距，不能把弱限制说成强限制。
 */
object Permissions {

    fun hasOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${com.focusmate.service.GuardAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val item = splitter.next()
            if (item.equals(expected, ignoreCase = true)) return true
            // 部分厂商会写成 package/.ServiceClass 的短形式
            if (item.startsWith(context.packageName + "/") && item.endsWith(".GuardAccessibilityService")) return true
        }
        return false
    }

    fun overlaySettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}
