package com.remindly.domain.model

data class SharedList(
    val id: String,
    val name: String,
    val ownerId: String,
    val color: Int? = null
)
