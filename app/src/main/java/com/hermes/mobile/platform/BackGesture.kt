package com.hermes.mobile.platform

/**
 * 系统返回手势的一帧：[progress] 是系统给的进度 0..1（已经按 Android 的手感做过缓动），
 * [fromRightEdge] 表示从屏幕右边缘开始滑。
 */
data class BackGesture(val progress: Float, val fromRightEdge: Boolean)
