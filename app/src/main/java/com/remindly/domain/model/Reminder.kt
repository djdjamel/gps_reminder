package com.remindly.domain.model

data class Reminder(
    val id: Long = 0,
    val text: String? = null,
    val status: ReminderStatus = ReminderStatus.ACTIVE,
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val attachments: List<Attachment> = emptyList(),
    // Trigger temporel
    val triggerType: TriggerType = TriggerType.NONE,
    val triggerTimeMillis: Long? = null,
    // Trigger géographique
    val placeLat: Double? = null,
    val placeLng: Double? = null,
    val placeRadiusM: Float? = null,
    val placeLabel: String? = null,
    val placeCategory: String? = null,
    val categoryRefType: String? = null,
    val sortOrder: Int = 0,
    // Collaboration
    val authorId: String? = null,
    val authorName: String? = null,
    // Identifiant du document Firestore (rappels partagés). Null = rappel purement local.
    val remoteId: String? = null
)

data class Attachment(
    val id: Long = 0,
    val reminderId: Long = 0,
    val type: AttachmentType,
    val localPath: String,
    val mimeType: String
)
