package com.focusmate.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.focusmate.runtime.GuardRuntime

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardRuntime.install(applicationContext)
        // 刚装完还没重启过手机时收不到开机广播，靠这里把主动引擎拉起来；
        // 拉起来之后由 START_STICKY + 开机广播维持，不再依赖用户打开本页。
        runCatching {
            com.focusmate.proactive.ProactiveRuntime.ensureService(applicationContext)
        }
        val transient = mutableStateOf("")
        // 状态变化 → 把运行时留下的一条提示交给界面显示；界面自己不做状态解释。
        GuardRuntime.onSessionChanged = {
            GuardRuntime.message()?.let { msg -> transient.value = msg }
        }
        setContent {
            FocusMateTheme {
                HomeScreen(runtime = GuardRuntime, transientMessage = transient)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        GuardRuntime.checkExpiry()
    }
}
