package com.vidora.app.util

/** Display identity only. Package id, signing identity, storage paths and launcher alias ids stay stable. */
object AppBrand {
    const val NAME = "Orvixa"
    fun displayName(saved: String?): String = when (saved) {
        null, "", "Vidora" -> NAME
        else -> saved // never overwrite a person's custom display name
    }
}
