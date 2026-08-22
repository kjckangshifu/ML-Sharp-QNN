package com.sharp.qnn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sharp.qnn.ui.nav.SHARPApp

/**
 * 唯一 Activity。采用 edge-to-edge 布局，全部 UI 由 Jetpack Compose (MD3) 构建。
 * The single Activity. Uses an edge-to-edge layout; all UI is built with
 * Jetpack Compose (MD3).
 *
 * 日志记录由 [com.sharp.qnn.service.LogRecorderService] 承载, 不依赖
 * Activity 被回收时仍持续记录。
 * Log recording is hosted by [com.sharp.qnn.service.LogRecorderService] and does
 * not depend on the Activity lifecycle; it keeps running in the background /
 * when the Activity is reclaimed.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SHARPApp()
        }
    }
}
