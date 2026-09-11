package com.remindly.data.remote

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentSnapshot
import com.remindly.domain.model.Reminder
import com.remindly.domain.model.SharedList
import com.remindly.domain.model.ReminderStatus
import com.remindly.domain.model.TriggerType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreDataSource @Inject constructor() {
    private val db = FirebaseFirestore.getInstance()

    suspend fun getSharedListsForUser(userId: String): List<SharedList> {
        val snapshot = db.collection("users").document(userId)
            .collection("shared_lists")
            .get()
            .await()
        
        return snapshot.documents.mapNotNull { doc ->
            SharedList(
                id = doc.id,
                name = doc.getString("name") ?: "",
                ownerId = doc.getString("ownerId") ?: userId,
                color = doc.getLong("color")?.toInt()
            )
        }
    }

    suspend fun saveSharedList(userId: String, list: SharedList) {
        val data = hashMapOf(
            "name" to list.name,
            "ownerId" to list.ownerId,
            "color" to list.color
        )
        db.collection("users").document(userId)
            .collection("shared_lists").document(list.id)
            .set(data).await()
    }

    suspend fun saveReminderInSharedList(userId: String, listId: String, reminder: Reminder) {
        val data = hashMapOf(
            "text" to reminder.text,
            "status" to reminder.status.name,
            "pinned" to reminder.pinned,
            "createdAt" to reminder.createdAt
        )
        db.collection("users").document(userId)
            .collection("shared_lists").document(listId)
            .collection("reminders").document(reminder.id.toString())
            .set(data).await()
    }

    // --- WORKSPACE COLLABORATION ---

    suspend fun saveUserProfile(uid: String, email: String, name: String?) {
        val data = hashMapOf(
            "email" to email,
            "name" to (name ?: "")
        )
        db.collection("users").document(uid).set(data).await()
    }

    suspend fun getUserIdByEmail(email: String): String? {
        val snapshot = db.collection("users")
            .whereEqualTo("email", email)
            .limit(1)
            .get()
            .await()
        return snapshot.documents.firstOrNull()?.id
    }

    fun observeSharedReminders(receiverId: String, senderId: String): Flow<List<Reminder>> = callbackFlow {
        val registration = db.collection("shared_reminders")
            .whereEqualTo("receiverId", receiverId)
            .whereEqualTo("senderId", senderId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                // Vue sortante : on conserve un id numérique dérivé du docId (compat historique).
                val reminders = snapshot?.documents?.map { it.toReminder(useNumericId = true) } ?: emptyList()
                trySend(reminders)
            }
        awaitClose { registration.remove() }
    }

    fun observeIncomingSharedReminders(receiverId: String): Flow<List<Reminder>> = callbackFlow {
        val registration = db.collection("shared_reminders")
            .whereEqualTo("receiverId", receiverId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                // Ingestion : l'id local est attribué par Room, on ne garde que remoteId.
                val reminders = snapshot?.documents?.map { it.toReminder(useNumericId = false) } ?: emptyList()
                trySend(reminders)
            }
        awaitClose { registration.remove() }
    }

    // Version ponctuelle (.get()) utilisée par le WorkManager quand l'app est fermée.
    suspend fun getIncomingSharedRemindersOnce(receiverId: String): List<Reminder> {
        val snapshot = db.collection("shared_reminders")
            .whereEqualTo("receiverId", receiverId)
            .get()
            .await()
        return snapshot.documents.map { it.toReminder(useNumericId = false) }
    }

    // Mapper Firestore -> domaine. useNumericId=true : id dérivé du docId (vue sortante).
    // useNumericId=false : id=0, l'id d'exécution sera l'id Room local après ingestion.
    private fun DocumentSnapshot.toReminder(useNumericId: Boolean): Reminder {
        val localId = if (useNumericId) (id.toLongOrNull() ?: id.hashCode().toLong()) else 0L
        return Reminder(
            id = localId,
            remoteId = id,
            text = getString("text"),
            status = try { ReminderStatus.valueOf(getString("status") ?: "ACTIVE") } catch (e: Exception) { ReminderStatus.ACTIVE },
            pinned = getBoolean("pinned") ?: false,
            createdAt = getLong("createdAt") ?: System.currentTimeMillis(),
            triggerType = try { TriggerType.valueOf(getString("triggerType") ?: "NONE") } catch (e: Exception) { TriggerType.NONE },
            triggerTimeMillis = getLong("triggerTimeMillis"),
            placeLat = getDouble("placeLat"),
            placeLng = getDouble("placeLng"),
            placeRadiusM = getDouble("placeRadiusM")?.toFloat(),
            placeLabel = getString("placeLabel"),
            placeCategory = getString("placeCategory"),
            categoryRefType = getString("categoryRefType"),
            commuteDirection = getString("commuteDirection"),
            isRepeating = getBoolean("isRepeating") ?: false,
            authorId = getString("senderId"),
            authorName = getString("senderName")
        )
    }

    suspend fun saveSharedReminder(reminder: Reminder, receiverId: String, senderId: String, senderName: String?) {
        val data = hashMapOf(
            "receiverId" to receiverId,
            "senderId" to senderId,
            "senderName" to senderName,
            "text" to reminder.text,
            "status" to reminder.status.name,
            "pinned" to reminder.pinned,
            "createdAt" to reminder.createdAt,
            "triggerType" to reminder.triggerType.name,
            "triggerTimeMillis" to reminder.triggerTimeMillis,
            "placeLat" to reminder.placeLat,
            "placeLng" to reminder.placeLng,
            "placeRadiusM" to reminder.placeRadiusM,
            "placeLabel" to reminder.placeLabel,
            "placeCategory" to reminder.placeCategory,
            "categoryRefType" to reminder.categoryRefType,
            "commuteDirection" to reminder.commuteDirection,
            "isRepeating" to reminder.isRepeating
        )
        // Use a consistent String ID for Firestore. If reminder.id is 0, generate one, else use it.
        val docId = if (reminder.id == 0L) System.currentTimeMillis().toString() else reminder.id.toString()
        db.collection("shared_reminders").document(docId).set(data).await()
    }

    suspend fun updateSharedReminderStatus(reminderId: String, status: ReminderStatus) {
        db.collection("shared_reminders").document(reminderId)
            .update("status", status.name).await()
    }

    suspend fun deleteSharedReminder(reminderId: String) {
        db.collection("shared_reminders").document(reminderId)
            .delete().await()
    }
}
