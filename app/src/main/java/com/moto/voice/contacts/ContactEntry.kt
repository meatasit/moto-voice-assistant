package com.moto.voice.contacts

data class ContactEntry(
    val id: String,
    val displayName: String,
    val phoneNumber: String,
)

data class MatchResult(
    val contact: ContactEntry,
    val score: Float,
)
