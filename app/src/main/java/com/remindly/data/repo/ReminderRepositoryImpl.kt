package com.remindly.data.repo

import com.remindly.data.db.dao.AttachmentDao
import com.remindly.data.db.dao.ReminderDao
import com.remindly.data.db.entity.ReminderAttachmentEntity
import com.remindly.data.db.entity.ReminderEntity
import com.remindly.domain.model.Attachment
import com.remindly.domain.model.Reminder
import com.remindly.domain.model.ReminderStatus
import com.remindly.media.AttachmentStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import com.remindly.auth.AuthManager
import com.remindly.auth.WorkspaceManager
import com.remindly.data.remote.FirestoreDataSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderRepositoryImpl @Inject constructor(
    private val reminderDao: ReminderDao,
    private val attachmentDao: AttachmentDao,
    private val attachmentStore: AttachmentStore,
    private val workspaceManager: WorkspaceManager,
    private val authManager: AuthManager,
    private val firestoreDataSource: FirestoreDataSource,
    private val context: android.content.Context
) : ReminderRepository {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observePersonalActive(): Flow<List<Reminder>> {
        return workspaceManager.currentWorkspaceEmail.flatMapLatest { workspaceEmail ->
            if (workspaceEmail == null) {
                // Espace personnel : Room est la source de vérité unique.
                // Filtre strictement sur status = 'ACTIVE'
                reminderDao.observePersonalActiveWithAttachments().map { relations ->
                    relations.map { relation ->
                        relation.reminder.toDomain(relation.attachments.map { it.toDomain() })
                    }
                }
            } else {
                // Espace collaborateur : lire depuis Firestore
                val myUid = authManager.currentUser?.uid
                if (myUid == null) {
                    flowOf(emptyList()) // Non connecté, impossible de lire les partages
                } else {
                    val targetUid = firestoreDataSource.getUserIdByEmail(workspaceEmail)
                    if (targetUid != null) {
                        firestoreDataSource.observeSharedReminders(receiverId = targetUid, senderId = myUid).map { list ->
                            list.filter { it.status == ReminderStatus.ACTIVE }
                        }
                    } else {
                        flowOf(emptyList()) // Utilisateur introuvable
                    }
                }
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observePersonalNonArchived(): Flow<List<Reminder>> {
        return workspaceManager.currentWorkspaceEmail.flatMapLatest { workspaceEmail ->
            if (workspaceEmail == null) {
                // Espace personnel : inclut ACTIVE et COMPLETED pour l'affichage dans l'écran principal
                reminderDao.observePersonalNonArchivedWithAttachments().map { relations ->
                    relations.map { relation ->
                        relation.reminder.toDomain(relation.attachments.map { it.toDomain() })
                    }
                }
            } else {
                val myUid = authManager.currentUser?.uid
                if (myUid == null) {
                    flowOf(emptyList())
                } else {
                    val targetUid = firestoreDataSource.getUserIdByEmail(workspaceEmail)
                    if (targetUid != null) {
                        firestoreDataSource.observeSharedReminders(receiverId = targetUid, senderId = myUid)
                    } else {
                        flowOf(emptyList())
                    }
                }
            }
        }
    }

    override fun observeById(id: Long): Flow<Reminder?> {
        return reminderDao.observeByIdWithAttachments(id).map { relation -> 
            relation?.reminder?.toDomain(relation.attachments.map { it.toDomain() }) 
        }
    }

    override suspend fun getById(id: Long): Reminder? {
        return reminderDao.getByIdWithAttachments(id)?.let { relation ->
            relation.reminder.toDomain(relation.attachments.map { it.toDomain() })
        }
    }

    override suspend fun save(reminder: Reminder): Long {
        val workspaceEmail = workspaceManager.currentWorkspaceEmail.value
        if (workspaceEmail != null) {
            val myUid = authManager.currentUser?.uid
            val myName = authManager.currentUser?.displayName ?: "Collaborateur"
            if (myUid != null) {
                val targetUid = firestoreDataSource.getUserIdByEmail(workspaceEmail)
                if (targetUid != null) {
                    firestoreDataSource.saveSharedReminder(reminder, targetUid, myUid, myName)
                    return reminder.id // Retourne l'id (0 si c'est nouveau, mais généré côté Firestore)
                } else {
                    throw IllegalStateException("Le collaborateur $workspaceEmail est introuvable. A-t-il lancé l'application récemment ?")
                }
            } else {
                throw IllegalStateException("Vous devez être connecté pour envoyer un rappel.")
            }
        }

        val entity = ReminderEntity(
            id = reminder.id,
            text = reminder.text,
            status = reminder.status,
            pinned = reminder.pinned,
            createdAt = reminder.createdAt,
            triggerType = reminder.triggerType,
            triggerTimeMillis = reminder.triggerTimeMillis,
            repeatRule = reminder.repeatRule,
            repeatIntervalMin = reminder.repeatIntervalMin,
            repeatDaysMask = reminder.repeatDaysMask,
            placeLat = reminder.placeLat,
            placeLng = reminder.placeLng,
            placeRadiusM = reminder.placeRadiusM,
            placeLabel = reminder.placeLabel,
            placeCategory = reminder.placeCategory,
            categoryKeyword = reminder.categoryKeyword,
            categoryRefType = reminder.categoryRefType,
            commuteDirection = reminder.commuteDirection,
            placeActiveFromMillis = reminder.placeActiveFromMillis,
            isRepeating = reminder.isRepeating,
            sortOrder = reminder.sortOrder,
            authorId = reminder.authorId,
            authorName = reminder.authorName
        )

        // IMPORTANT : utiliser update() pour les rappels existants (id > 0) pour éviter le
        // CASCADE DELETE via ForeignKey qui supprimerait toutes les pièces jointes.
        // insert() avec REPLACE supprime d'abord l'enregistrement existant → cascade sur reminder_attachments.
        return if (entity.id > 0L) {
            reminderDao.update(entity)
            entity.id
        } else {
            reminderDao.insert(entity) // Génère un nouvel ID auto
        }
    }

    override suspend fun setStatus(id: Long, status: ReminderStatus) {
        val workspaceEmail = workspaceManager.currentWorkspaceEmail.value
        if (workspaceEmail != null) {
            // Vue collaborateur (sortante) : l'id numérique round-trip vers le docId Firestore.
            firestoreDataSource.updateSharedReminderStatus(id.toString(), status)
            return
        }

        // Espace personnel : Room est la source de vérité.
        reminderDao.setStatus(id, status)
        // Si c'est un rappel entrant (remoteId non nul), propager vers Firestore via son docId.
        val remoteId = reminderDao.getById(id)?.remoteId
        if (remoteId != null) {
            try {
                firestoreDataSource.updateSharedReminderStatus(remoteId, status)
            } catch (e: Exception) { /* Ignoré si le document n'existe pas */ }
        }
    }

    override suspend fun setPinned(id: Long, pinned: Boolean) {
        val workspaceEmail = workspaceManager.currentWorkspaceEmail.value
        if (workspaceEmail != null) return // Pinned pas forcément synchronisé
        reminderDao.setPinned(id, pinned)
    }

    override suspend fun delete(id: Long) {
        val workspaceEmail = workspaceManager.currentWorkspaceEmail.value
        if (workspaceEmail != null) {
            firestoreDataSource.deleteSharedReminder(id.toString())
            return
        }

        // Lire le remoteId AVANT la suppression (getById renverrait null après).
        val remoteId = reminderDao.getById(id)?.remoteId

        // Obtenir d'abord les attachements pour les supprimer du disque
        val attachments = attachmentDao.getByReminderId(id)
        for (att in attachments) {
            attachmentStore.deleteFile(att.localPath)
        }
        // Supprime de Room
        reminderDao.delete(id)

        // Si c'était un rappel entrant, supprime aussi le document Firestore via son docId.
        if (remoteId != null) {
            try {
                firestoreDataSource.deleteSharedReminder(remoteId)
            } catch (e: Exception) { /* Ignoré */ }
        }
    }

    override suspend fun addAttachment(reminderId: Long, attachment: Attachment): Long {
        val entity = ReminderAttachmentEntity(
            reminderId = reminderId,
            type = attachment.type,
            localPath = attachment.localPath,
            mimeType = attachment.mimeType
        )
        return attachmentDao.insert(entity)
    }

    override suspend fun removeAttachment(attachmentId: Long, localPath: String) {
        attachmentStore.deleteFile(localPath)
        attachmentDao.delete(attachmentId)
    }

    // Mapper simple
    private fun ReminderEntity.toDomain(attachments: List<Attachment> = emptyList()): Reminder {
        return Reminder(
            id = id,
            text = text,
            status = status,
            pinned = pinned,
            createdAt = createdAt,
            attachments = attachments,
            triggerType = triggerType,
            triggerTimeMillis = triggerTimeMillis,
            repeatRule = repeatRule,
            repeatIntervalMin = repeatIntervalMin,
            repeatDaysMask = repeatDaysMask,
            placeLat = placeLat,
            placeLng = placeLng,
            placeRadiusM = placeRadiusM,
            placeLabel = placeLabel,
            placeCategory = placeCategory,
            categoryKeyword = categoryKeyword,
            categoryRefType = categoryRefType,
            commuteDirection = commuteDirection,
            placeActiveFromMillis = placeActiveFromMillis,
            isRepeating = isRepeating,
            sortOrder = sortOrder,
            authorId = authorId,
            authorName = authorName,
            remoteId = remoteId
        )
    }

    private fun ReminderAttachmentEntity.toDomain(): Attachment {
        return Attachment(
            id = id,
            reminderId = reminderId,
            type = type,
            localPath = localPath,
            mimeType = mimeType
        )
    }

    override suspend fun updateSortOrder(id: Long, sortOrder: Int) {
        reminderDao.updateSortOrder(id, sortOrder)
    }
}

