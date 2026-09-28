package com.remindly.location

import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.DetectedActivity
import com.remindly.data.settings.VoiceAlarmSettings
import com.remindly.data.settings.VoiceAlarmSettingsRepository
import com.remindly.domain.model.Reminder
import com.remindly.domain.model.ReminderStatus
import com.remindly.domain.model.TriggerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeVoiceAlarmSettingsRepository(
    var currentSettings: VoiceAlarmSettings = VoiceAlarmSettings()
) : VoiceAlarmSettingsRepository {
    override val settingsFlow: Flow<VoiceAlarmSettings> = flowOf(currentSettings)
    override suspend fun getSettings(): VoiceAlarmSettings = currentSettings
    override suspend fun setVolume(volume: Float) {}
    override suspend fun setRepeatCount(repeatCount: Int) {}
    override suspend fun setVibrate(vibrate: Boolean) {}
    override suspend fun setAppLanguage(languageCode: String) {}
    override suspend fun setHasSelectedLanguage(selected: Boolean) {}
    override suspend fun setAppTheme(theme: String) {}
    override suspend fun setPoiSearchRadius(radiusKm: Int) {}
    override suspend fun setPoiDetectionRadiusM(radiusM: Int) {}
    override suspend fun setRollingExitRadiusM(radiusM: Int) {}
    override suspend fun setGeofenceCooldownSeconds(seconds: Int) {}
    override suspend fun setSmartGeofenceFiltering(enabled: Boolean) {}
    override suspend fun setMaxFilterSpeedKmh(speedKmh: Int) {}
    override suspend fun setAnnouncePlaceByVoice(enabled: Boolean) {}
    override suspend fun setReadTextRemindersAloud(enabled: Boolean) {}
    override suspend fun setCommuteStart(lat: Double, lng: Double, label: String) {}
    override suspend fun setCommuteEnd(lat: Double, lng: Double, label: String) {}
    override suspend fun setCommuteRoute(startLat: Double, startLng: Double, startLabel: String, endLat: Double, endLng: Double, endLabel: String, polyline: String?) {}
    override suspend fun clearCommuteRoute() {}
    override suspend fun setPassiveLocationMonitoring(enabled: Boolean) {}
    override suspend fun setAutoVehicleDetection(enabled: Boolean) {}
    override suspend fun setAdaptiveActivityRadius(enabled: Boolean) {}
}

class ContextRelevanceEngineTest {

    private lateinit var tracker: UserActivityTracker
    private lateinit var repository: FakeVoiceAlarmSettingsRepository
    private lateinit var engine: ContextRelevanceEngine

    @Before
    fun setUp() {
        tracker = UserActivityTracker()
        repository = FakeVoiceAlarmSettingsRepository()
        engine = ContextRelevanceEngine(tracker, repository)
    }

    @Test
    fun testFixedPlaceDeliberatePriority_returnsFullAlarm() {
        val fixedReminder = Reminder(
            id = 101L,
            text = "Chez Brahimi",
            triggerType = TriggerType.PLACE,
            placeLat = 35.5540,
            placeLng = 6.1470,
            placeRadiusM = 450f,
            placeCategory = null, // Lieu fixe délibéré
            status = ReminderStatus.ACTIVE
        )

        val evaluation = engine.evaluate(
            reminder = fixedReminder,
            currentLocation = null,
            targetLat = 35.5540,
            targetLng = 6.1470,
            currentDistanceM = 150f
        )

        assertEquals(85, evaluation.score)
        assertEquals(ContextDecision.FULL_ALARM, evaluation.decision)
        assertTrue(evaluation.factors.any { it.description.contains("Lieu fixe") })
    }

    @Test
    fun testFixedPlaceRecedingDistant_returnsDiscreetNotif() {
        val fixedReminder = Reminder(
            id = 102L,
            text = "Petit Prince",
            triggerType = TriggerType.PLACE,
            placeLat = 35.5540,
            placeLng = 6.1470,
            placeRadiusM = 450f,
            placeCategory = null,
            status = ReminderStatus.ACTIVE
        )

        val recentDistances = listOf(
            DistanceSample(distanceM = 310f, timestamp = 1000L),
            DistanceSample(distanceM = 360f, timestamp = 6000L) // +50m d'éloignement, >300m
        )

        val evaluation = engine.evaluate(
            reminder = fixedReminder,
            currentLocation = null,
            targetLat = 35.5540,
            targetLng = 6.1470,
            currentDistanceM = 360f,
            recentDistances = recentDistances
        )

        assertEquals(40, evaluation.score)
        assertEquals(ContextDecision.DISCREET_NOTIF, evaluation.decision)
    }

    @Test
    fun testPoiApproaching_withDeceleration_andWalking_returnsFullAlarm() {
        val poiReminder = Reminder(
            id = 201L,
            text = "Pharmacie de garde",
            triggerType = TriggerType.PLACE,
            placeLat = 35.5500,
            placeLng = 6.1400,
            placeRadiusM = 450f,
            placeCategory = "pharmacy",
            status = ReminderStatus.ACTIVE
        )

        val recentDistances = listOf(
            DistanceSample(distanceM = 400f, timestamp = 1000L),
            DistanceSample(distanceM = 250f, timestamp = 6000L) // -150m rapprochement
        )

        val recentSpeeds = listOf(
            SpeedSample(speedKmh = 45f, timestamp = 1000L),
            SpeedSample(speedKmh = 20f, timestamp = 6000L) // Décélération nette
        )

        val evaluation = engine.evaluate(
            reminder = poiReminder,
            currentLocation = null,
            targetLat = 35.5500,
            targetLng = 6.1400,
            currentDistanceM = 250f,
            recentDistances = recentDistances,
            recentSpeeds = recentSpeeds,
            overrideActivity = DetectedActivity.WALKING,
            overrideSpeedKmh = 20f,
            overrideBearing = 0f,
            overrideCurrentLat = 35.5400, // Au sud du POI se déplaçant vers le Nord (0°)
            overrideCurrentLng = 6.1400
        )

        assertTrue("Le score doit être très élevé (>= 70)", evaluation.score >= 70)
        assertEquals(ContextDecision.FULL_ALARM, evaluation.decision)
    }

    @Test
    fun testPoiHighwaySpeed_receding_returnsSuppressed() {
        val poiReminder = Reminder(
            id = 202L,
            text = "Boulangerie",
            triggerType = TriggerType.PLACE,
            placeLat = 35.5500,
            placeLng = 6.1400,
            placeRadiusM = 450f,
            placeCategory = "bakery",
            status = ReminderStatus.ACTIVE
        )

        val recentDistances = listOf(
            DistanceSample(distanceM = 200f, timestamp = 1000L),
            DistanceSample(distanceM = 270f, timestamp = 6000L) // +70m éloignement
        )

        val evaluation = engine.evaluate(
            reminder = poiReminder,
            currentLocation = null,
            targetLat = 35.5500,
            targetLng = 6.1400,
            currentDistanceM = 270f,
            recentDistances = recentDistances,
            overrideActivity = DetectedActivity.IN_VEHICLE,
            overrideSpeedKmh = 105f // Vitesse autoroutière
        )

        assertTrue("Le score doit être faible (< 25)", evaluation.score < 25)
        assertEquals(ContextDecision.SUPPRESS, evaluation.decision)
    }

    @Test
    fun testPoiPostDrivingArrival_boostsScore() {
        val now = System.currentTimeMillis()
        // Simuler conduite il y a 2 minutes puis passage à pied
        tracker.recordTransition(DetectedActivity.IN_VEHICLE, ActivityTransition.ACTIVITY_TRANSITION_ENTER, now - 120_000L)
        tracker.recordTransition(DetectedActivity.WALKING, ActivityTransition.ACTIVITY_TRANSITION_ENTER, now)

        val poiReminder = Reminder(
            id = 203L,
            text = "Supermarché",
            triggerType = TriggerType.PLACE,
            placeLat = 35.5500,
            placeLng = 6.1400,
            placeRadiusM = 450f,
            placeCategory = "supermarket",
            status = ReminderStatus.ACTIVE
        )

        val recentDistances = listOf(
            DistanceSample(distanceM = 150f, timestamp = now - 5000L),
            DistanceSample(distanceM = 130f, timestamp = now) // -20m rapprochement
        )

        val evaluation = engine.evaluate(
            reminder = poiReminder,
            currentLocation = null,
            targetLat = 35.5500,
            targetLng = 6.1400,
            currentDistanceM = 130f,
            recentDistances = recentDistances
        )

        assertTrue("Le score doit contenir le bonus post-conduite", evaluation.factors.any { it.description.contains("post-conduite") })
        assertTrue("Le score doit dépasser le seuil d'alarme (>= 70)", evaluation.score >= 70)
        assertEquals(ContextDecision.FULL_ALARM, evaluation.decision)
    }

    @Test
    fun testPoiFacingAwayBearing_penalizesScore() {
        val poiReminder = Reminder(
            id = 204L,
            text = "Station service",
            triggerType = TriggerType.PLACE,
            placeLat = 35.5600, // Au Nord
            placeLng = 6.1400,
            placeRadiusM = 450f,
            placeCategory = "gas_station",
            status = ReminderStatus.ACTIVE
        )

        val evaluation = engine.evaluate(
            reminder = poiReminder,
            currentLocation = null,
            targetLat = 35.5600,
            targetLng = 6.1400,
            currentDistanceM = 200f,
            overrideBearing = 180f, // Cap plein Sud (s'éloigne du Nord)
            overrideCurrentLat = 35.5500,
            overrideCurrentLng = 6.1400
        )

        assertTrue("Un cap opposé (>110°) doit infliger une pénalité", evaluation.factors.any { it.points < 0 && it.description.contains("sens inverse") })
    }

    @Test
    fun testFixedPlaceOppositeBearingWhileDriving_suppressesAlarm() {
        val hotelReminder = Reminder(
            id = 301L,
            text = "Hôtel des Pins",
            triggerType = TriggerType.PLACE,
            placeLat = 35.5600,
            placeLng = 6.1400,
            placeRadiusM = 450f,
            placeCategory = null, // Lieu fixe
            status = ReminderStatus.ACTIVE
        )

        // Véhicule au Sud de l'hôtel (35.5560) roulant vers le Sud (bearing 180°), s'éloignant de la cible (bearing vers cible = 0°)
        val evaluation = engine.evaluate(
            reminder = hotelReminder,
            currentLocation = null,
            targetLat = 35.5600,
            targetLng = 6.1400,
            currentDistanceM = 401f, // Cas réel du log du 27 sept
            overrideBearing = 180f,  // Roule plein Sud (dos à l'hôtel)
            overrideSpeedKmh = 42f,  // 42 km/h
            overrideCurrentLat = 35.5560,
            overrideCurrentLng = 6.1400
        )

        assertEquals("Le déclenchement tardif en sens opposé (>95°) doit être SUPPRIMÉ", ContextDecision.SUPPRESS, evaluation.decision)
        assertTrue(evaluation.factors.any { it.description.contains("Sens opposé") })
    }

    @Test
    fun testFixedPlaceApproachingFrontally_firesFullAlarm() {
        val hotelReminder = Reminder(
            id = 302L,
            text = "Hôtel des Pins",
            triggerType = TriggerType.PLACE,
            placeLat = 35.5600,
            placeLng = 6.1400,
            placeRadiusM = 450f,
            placeCategory = null,
            status = ReminderStatus.ACTIVE
        )

        // Véhicule roulant vers le Nord (bearing 0°), face à l'hôtel
        val evaluation = engine.evaluate(
            reminder = hotelReminder,
            currentLocation = null,
            targetLat = 35.5600,
            targetLng = 6.1400,
            currentDistanceM = 400f,
            overrideBearing = 0f,
            overrideSpeedKmh = 42f,
            overrideCurrentLat = 35.5560,
            overrideCurrentLng = 6.1400
        )

        assertEquals(ContextDecision.FULL_ALARM, evaluation.decision)
        assertEquals(85, evaluation.score)
    }

    @Test
    fun testFixedPlaceCloseDistance_ignoresOpposingBearing() {
        val hotelReminder = Reminder(
            id = 303L,
            text = "Hôtel des Pins",
            triggerType = TriggerType.PLACE,
            placeLat = 35.5600,
            placeLng = 6.1400,
            placeRadiusM = 450f,
            placeCategory = null,
            status = ReminderStatus.ACTIVE
        )

        // Proximité immédiate (120m <= 200m) : manœuvre de stationnement ou demi-tour
        val evaluation = engine.evaluate(
            reminder = hotelReminder,
            currentLocation = null,
            targetLat = 35.5600,
            targetLng = 6.1400,
            currentDistanceM = 120f,
            overrideBearing = 180f,
            overrideSpeedKmh = 25f,
            overrideCurrentLat = 35.5589,
            overrideCurrentLng = 6.1400
        )

        assertEquals("À proximité immédiate (<=200m), le lieu fixe délibéré reste garanti", ContextDecision.FULL_ALARM, evaluation.decision)
    }

    @Test
    fun testFixedPlaceHighSpeedTransit_returnsWaitAndMonitor() {
        val hotelReminder = Reminder(
            id = 304L,
            text = "Hôtel des Pins",
            triggerType = TriggerType.PLACE,
            placeLat = 35.5600,
            placeLng = 6.1400,
            placeRadiusM = 450f,
            placeCategory = null,
            status = ReminderStatus.ACTIVE
        )

        // Véhicule approchant à 75 km/h à 350m (pas encore ralenti)
        val evaluation = engine.evaluate(
            reminder = hotelReminder,
            currentLocation = null,
            targetLat = 35.5600,
            targetLng = 6.1400,
            currentDistanceM = 350f,
            overrideBearing = 0f,
            overrideSpeedKmh = 75f,
            overrideCurrentLat = 35.5560,
            overrideCurrentLng = 6.1400
        )

        assertEquals("À vitesse élevée (>65 km/h) en approche, doit passer en WAIT_AND_MONITOR", ContextDecision.WAIT_AND_MONITOR, evaluation.decision)
        assertEquals(40, evaluation.score)
    }
}
