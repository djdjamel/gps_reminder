package com.remindly.data.settings

data class VoiceAlarmSettings(
    val volume: Float = 1.0f,          // 0.05f à 1.0f (5% à 100%)
    val repeatCount: Int = 1,          // 1, 2, 3, 5, ou -1 (boucle continue)
    val vibrate: Boolean = true,
    // Langue de l'application ("fr", "ar", "en")
    val appLanguage: String = "fr",
    // L'utilisateur a-t-il déjà sélectionné la langue initiale au 1er démarrage
    val hasSelectedLanguage: Boolean = false,
    // Thème de l'application ("LIGHT", "DARK", "SYSTEM")
    val appTheme: String = "SYSTEM",
    // Périmètre de recherche globale des catégories (1, 2, 3, 5, 10 km)
    val poiSearchRadiusKm: Int = 3,
    // Rayon de détection de chaque géofence de commerce POI en mètres (défaut : 450m)
    val poiDetectionRadiusM: Int = 450,
    // Annoncer le nom du lieu à voix haute par synthèse vocale (TTS)
    val announcePlaceByVoice: Boolean = true,
    // Lire les rappels textuels à voix haute par synthèse vocale
    val readTextRemindersAloud: Boolean = true,
    // Rayon de la zone tampon de sortie (Fenêtre glissante pour déplacements) en mètres (défaut : 900m)
    val rollingExitRadiusM: Int = 900,
    // Délai anti-rebond entre alertes successives pour le même rappel en secondes (défaut : 15s)
    val geofenceCooldownSeconds: Int = 15,
    // Filtrage intelligent de pertinence des géofences (Vitesse de transit autoroute + Direction/Cap)
    val smartGeofenceFiltering: Boolean = true,
    // Vitesse maximale autorisée en km/h pour déclencher un rappel POI (défaut : 65 km/h)
    val maxFilterSpeedKmh: Int = 65,
    // Trajet habituel
    val commuteStartLat: Double? = null,
    val commuteStartLng: Double? = null,
    val commuteStartLabel: String? = null,
    val commuteEndLat: Double? = null,
    val commuteEndLng: Double? = null,
    val commuteEndLabel: String? = null,
    val commuteRoutePolyline: String? = null,
    // Moniteur passif de localisation pour analyser les calculs de Google Play Services (0% batterie)
    val passiveLocationMonitoring: Boolean = false,
    // Détection automatique de transition de véhicule (Activity Recognition) pour pulse GPS 40s
    val autoVehicleDetection: Boolean = true,
    // Adaptation automatique du rayon de détection selon l'activité (ex: 100m à pied, 450m en voiture)
    val adaptiveActivityRadius: Boolean = true
) {
    val hasCommuteRoute: Boolean
        get() = commuteStartLat != null && commuteStartLng != null &&
                commuteEndLat != null && commuteEndLng != null

    companion object {
        const val REPEAT_LOOP = -1
    }
}
