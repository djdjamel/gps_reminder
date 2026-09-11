package com.remindly.sync

import com.remindly.auth.AuthManager
import com.remindly.data.db.dao.ReminderDao
import com.remindly.data.db.entity.ReminderEntity
import com.remindly.data.remote.FirestoreDataSource
import com.remindly.domain.model.Reminder
import com.remindly.domain.model.ReminderStatus
import com.remindly.domain.model.SyncState
import com.remindly.domain.model.TriggerType
import com.remindly.location.GeofenceManager
import com.remindly.time.AlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ingestion des rappels entrants (Cloud -> Room -> planification).
 *
 * Room reste la source de vérité qui pilote les alarmes/geofences. Ce gestionnaire :
 *  - écoute en temps réel `shared_reminders (receiverId == moi)` quand l'app est active ([start]),
 *  - permet une synchro ponctuelle en arrière-plan via WorkManager ([syncOnce]),
 *  - fait un upsert par `remoteId`, (re)planifie alarme/geofence, et purge les rappels
 *    supprimés côté Cloud.
 */
@Singleton
class SharedReminderSyncManager @Inject constructor(
    private val reminderDao: ReminderDao,
    private val firestoreDataSource: FirestoreDataSource,
    private val alarmScheduler: AlarmScheduler,
    private val geofenceManager: GeofenceManager,
    private val authManager: AuthManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ingestMutex = Mutex()
    @Volatile private var started = false

    /** Démarre le collecteur temps réel (portée application). Idempotent. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            authManager.currentUserState
                .flatMapLatest { user ->
                    val uid = user?.uid
                    if (uid == null) flowOf(emptyList()) else firestoreDataSource.observeIncomingSharedReminders(uid)
                }
                .catch { it.printStackTrace() }
                .collect { incoming ->
                    try {
                        ingest(incoming)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
        }
    }

    /** Synchro ponctuelle (une passe `.get()`), utilisée par le WorkManager quand l'app est fermée. */
    suspend fun syncOnce() {
        val uid = authManager.currentUser?.uid ?: return
        val incoming = firestoreDataSource.getIncomingSharedRemindersOnce(uid)
        ingest(incoming)
    }

    /**
     * Upsert des rappels distants dans Room + (re)planification, puis purge des lignes locales
     * dont le document Cloud a disparu. Sérialisé par [ingestMutex] (temps réel + worker sûrs).
     */
    private suspend fun ingest(remote: List<Reminder>) = ingestMutex.withLock {
        val remoteIds = remote.mapNotNull { it.remoteId }.toSet()

        for (r in remote) {
            val remoteId = r.remoteId ?: continue
            val existing = reminderDao.getByRemoteId(remoteId)
            val entity = ReminderEntity(
                id = existing?.id ?: 0L,
                remoteId = remoteId,
                listId = null,
                authorId = r.authorId,
                authorName = r.authorName,
                text = r.text,
                createdAt = existing?.createdAt ?: r.createdAt,
                updatedAt = System.currentTimeMillis(),
                status = r.status,
                pinned = existing?.pinned ?: r.pinned,
                triggerType = r.triggerType,
                triggerTimeMillis = r.triggerTimeMillis,
                placeLat = r.placeLat,
                placeLng = r.placeLng,
                placeRadiusM = r.placeRadiusM,
                placeLabel = r.placeLabel,
                placeCategory = r.placeCategory,
                categoryRefType = r.categoryRefType,
                commuteDirection = r.commuteDirection,
                placeActiveFromMillis = r.placeActiveFromMillis,
                isRepeating = r.isRepeating,
                syncState = SyncState.SYNCED,
                sortOrder = existing?.sortOrder ?: 0
            )
            val localId = if (existing == null) {
                reminderDao.insert(entity)
            } else {
                reminderDao.update(entity)
                existing.id
            }
            scheduleFor(entity.copy(id = localId))
        }

        // Purge : lignes entrantes locales dont le document Cloud n'existe plus.
        val localIncoming = reminderDao.getAllIncoming()
        for (row in localIncoming) {
            val rid = row.remoteId
            if (rid == null || rid !in remoteIds) {
                alarmScheduler.cancel(row.id)
                geofenceManager.removeGeofence(row.id)
                reminderDao.delete(row.id)
            }
        }
    }

    /** (Re)planifie ou annule alarme/geofence selon le statut et le type de déclencheur. */
    private fun scheduleFor(e: ReminderEntity) {
        if (e.status != ReminderStatus.ACTIVE) {
            alarmScheduler.cancel(e.id)
            geofenceManager.removeGeofence(e.id)
            return
        }
        val domain = e.toReminderForScheduling()
        val now = System.currentTimeMillis()
        if (e.triggerType == TriggerType.TIME || e.triggerType == TriggerType.BOTH) {
            val t = e.triggerTimeMillis
            // Un temps déjà échu n'est pas reprogrammé (évite les notifications tardives en masse).
            if (t != null && t > now) {
                alarmScheduler.schedule(domain, t)
            }
        }
        if (e.triggerType == TriggerType.PLACE || e.triggerType == TriggerType.BOTH) {
            if ((e.placeLat != null && e.placeLng != null) || e.placeCategory != null) {
                if (e.placeActiveFromMillis != null && e.placeActiveFromMillis > now) {
                    alarmScheduler.scheduleDeferredGeofence(domain, e.placeActiveFromMillis)
                } else {
                    geofenceManager.addGeofence(domain)
                }
            }
        }
    }

    private fun ReminderEntity.toReminderForScheduling(): Reminder = Reminder(
        id = id,
        text = text,
        status = status,
        pinned = pinned,
        createdAt = createdAt,
        triggerType = triggerType,
        triggerTimeMillis = triggerTimeMillis,
        placeLat = placeLat,
        placeLng = placeLng,
        placeRadiusM = placeRadiusM,
        placeLabel = placeLabel,
        placeCategory = placeCategory,
        categoryRefType = categoryRefType,
        commuteDirection = commuteDirection,
        placeActiveFromMillis = placeActiveFromMillis,
        isRepeating = isRepeating,
        authorId = authorId,
        authorName = authorName,
        remoteId = remoteId
    )
}
