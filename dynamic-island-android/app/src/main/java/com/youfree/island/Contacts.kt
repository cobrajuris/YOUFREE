package com.youfree.island

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

data class ContactPhone(val name: String, val number: String)

/** Procura um contato pelo nome falado ("liga para a Maria", "manda mensagem pro João"). */
class Contacts(private val ctx: Context) {

    fun canRead(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun find(spokenName: String): ContactPhone? {
        if (!canRead()) return null
        val query = Assistant.normalize(spokenName)
        if (query.isEmpty()) return null
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
        )
        var best: ContactPhone? = null
        var bestScore = Int.MAX_VALUE
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val number = c.getString(1) ?: continue
                    val n = Assistant.normalize(name)
                    val score = when {
                        n == query -> 0
                        n.startsWith("$query ") -> 1
                        n.split(" ").contains(query) -> 2
                        n.contains(query) -> 3
                        else -> continue
                    } * 2 - (if (c.getInt(2) == 1) 1 else 0)
                    if (score < bestScore) {
                        bestScore = score
                        best = ContactPhone(name, number)
                    }
                }
            }
        } catch (_: SecurityException) {
        }
        return best
    }

    companion object {
        /** Número só com dígitos e DDI (55 para números brasileiros sem DDI). */
        fun internationalDigits(number: String): String {
            val plus = number.trim().startsWith("+")
            var digits = number.filter { it.isDigit() }
            if (!plus) {
                digits = digits.trimStart('0')
                if (digits.length in 10..11) digits = "55$digits"
            }
            return digits
        }
    }
}
