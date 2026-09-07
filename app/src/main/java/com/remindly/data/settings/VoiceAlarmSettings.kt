package com.remindly.data.settings

data class VoiceAlarmSettings(
    val volume: Float = 1.0f,          // 0.05f à 1.0f (5% à 100%)
    val repeatCount: Int = 1,          // 1, 2, 3, 5, ou -1 (boucle continue)
    val vibrate: Boolean = true,
    // Périmètre de détection des catégories POI (1, 2, 3, 5, 10 km)
    val poiSearchRadiusKm: Int = 3,
    // Trajet habituel
    val commuteStartLat: Double? = null,
    val commuteStartLng: Double? = null,
    val commuteStartLabel: String? = null,
    val commuteEndLat: Double? = null,
    val commuteEndLng: Double? = null,
    val commuteEndLabel: String? = null
) {
    val hasCommuteRoute: Boolean
        get() = commuteStartLat != null && commuteStartLng != null &&
                commuteEndLat != null && commuteEndLng != null

    companion object {
        const val REPEAT_LOOP = -1
    }
}
