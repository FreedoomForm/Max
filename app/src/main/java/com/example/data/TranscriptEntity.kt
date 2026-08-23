package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transcripts")
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val rawContent: String,
    val formattedContent: String? = null,
    val summary: String? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
    val durationSeconds: Long = 0,
    val languageCode: String = "auto",
    val wordCount: Int = 0
)
