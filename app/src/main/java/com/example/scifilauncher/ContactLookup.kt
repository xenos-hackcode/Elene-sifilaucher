package com.example.scifilauncher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

data class ResolvedContact(val displayName: String, val phoneNumber: String)

/** Forward lookup (spoken name -> possible contacts) for Elene's general messaging feature -
 * distinct from SequenceMode.resolveFamilyContacts, which is a fixed role-alias lookup
 * (father/mother/etc.) for anti-theft alerts, not a general "find this person" helper. Returns
 * every match rather than just the first, so the caller can ask "which John did you mean" when
 * there's more than one. */
fun findContactsByName(context: Context, query: String): List<ResolvedContact> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
        return emptyList()
    }
    if (query.isBlank()) return emptyList()

    val results = mutableListOf<ResolvedContact>()
    context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
        arrayOf("%$query%"),
        null
    )?.use { cursor ->
        val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
        while (cursor.moveToNext()) {
            val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
            val number = if (numberIdx >= 0) cursor.getString(numberIdx) else null
            if (!name.isNullOrBlank() && !number.isNullOrBlank()) {
                // A contact can have multiple numbers (home/mobile/work) - only keep the first
                // seen per display name so "message John" doesn't surface the same person twice.
                if (results.none { it.displayName == name }) {
                    results.add(ResolvedContact(name, number))
                }
            }
        }
    }
    return results
}

/** Reverse lookup (incoming phone number -> contact name), for call-awareness announcements.
 * Mirror of findContactsByName's direction. Returns null for an unknown/non-contact number. */
fun reverseLookupContactName(context: Context, phoneNumber: String): String? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
        return null
    }
    if (phoneNumber.isBlank()) return null

    val uri = android.net.Uri.withAppendedPath(
        ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
        android.net.Uri.encode(phoneNumber)
    )
    return context.contentResolver.query(
        uri,
        arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
        null, null, null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
            if (nameIdx >= 0) cursor.getString(nameIdx) else null
        } else {
            null
        }
    }
}
