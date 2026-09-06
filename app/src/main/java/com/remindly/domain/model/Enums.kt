package com.remindly.domain.model

enum class TriggerType {
    NONE, TIME, PLACE, BOTH
}

enum class ReminderStatus {
    ACTIVE, COMPLETED, SNOOZED, ARCHIVED
}

enum class RepeatRule {
    NONE, DAILY, WEEKLY, MONTHLY, CUSTOM
}

enum class SyncState {
    SYNCED, PENDING_UPLOAD, PENDING_DELETE
}

enum class AttachmentType {
    IMAGE, AUDIO
}
