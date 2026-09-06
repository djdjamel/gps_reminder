package com.remindly.data.settings

data class VoiceAlarmSettings(
    val volume: Float = 1.0f,          // 0.05f à 1.0f (5% à 100%)
    val repeatCount: Int = 1,          // 1, 2, 3, 5, ou -1 (boucle continue)
    val vibrate: Boolean = true
) {
    companion object {
        const val REPEAT_LOOP = -1
    }
}
