package com.librecrate.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "document_tags",
    primaryKeys = ["document_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = Document::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("document_id"),
        Index("tag_id")
    ]
)
data class DocumentTag(
    @ColumnInfo(name = "document_id")
    val documentId: String,
    @ColumnInfo(name = "tag_id")
    val tagId: String
)
