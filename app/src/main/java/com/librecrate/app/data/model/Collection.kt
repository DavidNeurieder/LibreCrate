package com.librecrate.app.data.model

data class Collection(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val icon: String,
    val sortOrder: Int,
    val parentId: String? = null,
)
