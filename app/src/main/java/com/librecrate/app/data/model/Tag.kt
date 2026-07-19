package com.librecrate.app.data.model

data class Tag(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val color: Long,
)
