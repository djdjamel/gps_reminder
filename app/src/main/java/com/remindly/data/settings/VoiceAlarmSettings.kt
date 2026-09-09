package com.remindly.data.settings

data class VoiceAlarmSettings(
    val volume: Float = 1.0f,          // 0.05f à 1.0f (5% à 100%)
    val repeatCount: Int = 1,          // 1, 2, 3, 5, ou -1 (boucle continue)
    val vibrate: Boolean = true,
    // Périmètre de recherche globale des catégories (1, 2, 3, 5, 10 km)
    val poiSearchRadiusKm: Int = 3,
    // Rayon de détection de chaque géofence de commerce POI en mètres (défaut : 450m)
    val poiDetectionRadiusM: Int = 450,
    // Annoncer le nom du lieu à voix haute par synthèse vocale (TTS)
    val announcePlaceByVoice: Boolean = true,
    // Lire les rappels textuels à voix haute par synthèse vocale
    val readTextRemindersAloud: Boolean = true,
    // Rayon de la zone tampon de sortie (Fenêtre glissante pour déplacements) en mètres (défaut : 2500m / 2.5 km)
    val rollingExitRadiusM: Int = 2500,
    // Trajet habituel
    val commuteStartLat: Double? = null,
    val commuteStartLng: Double? = null,
    val commuteStartLabel: String? = null,
    val commuteEndLat: Double? = null,
    val commuteEndLng: Double? = null,
    val commuteEndLabel: String? = null,
    val commuteRoutePolyline: String? = null
) {
    val hasCommuteRoute: Boolean
        get() = commuteStartLat != null && commuteStartLng != null &&
                commuteEndLat != null && commuteEndLng != null

    companion object {
        const val REPEAT_LOOP = -1
    }
}
