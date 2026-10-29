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
