package com.wanderwildwood.kotozute.util

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.VCardVersion
import ezvcard.parameter.AddressType
import ezvcard.parameter.EmailType
import ezvcard.parameter.TelephoneType
import ezvcard.property.Address
import ezvcard.property.Birthday
import ezvcard.property.StructuredName
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A contact picked to be sent as a card, turned into a `.vcf` file in this app's cache the
 * moment it is picked. Both the text side and the Signal side attach the file, never the
 * contact.
 *
 * ⚠ **Messaging used to attach the contacts provider's `as_vcard` address and read it later**:
 * the card's name, size, type and bytes were each asked of the provider again, every time
 * the composer drew it and once more when it went. On a phone whose provider would not
 * compose the card for this app -- it logs "Failed to init VCardComposer" and hands back a
 * stream with nothing in it -- every one of those reads came back empty. Nothing checked:
 * a text quietly lost the attachment (the provider's row was not there, so the send filtered
 * it out) and a Signal chat sent an empty message with no card in it.
 *
 * So the card is read once, here, and checked: it must parse and carry a name or a way to
 * reach the person. When the provider's card does not pass, the card is written from the
 * contact's own rows -- name, numbers, emails, addresses, organisation, birthday, note --
 * with ez-vcard. When that cannot be done either the answer is null, and the screen says it
 * could not attach the contact. An empty card is never attached.
 */
object ContactCardFile {

    /** The card on disk, with the name it is shown and sent under. */
    class Card(val file: File, val name: String, val bytes: ByteArray) {
        val uri: Uri get() = Uri.fromFile(file)
        val dataUri: String
            get() = "data:$TYPE;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    /** What an MMS part carrying a card says it is; what other phones expect. */
    const val TYPE = "text/x-vcard"

    /** A card is a few hundred bytes. Anything near this is not a card worth sending. */
    private const val MAX_BYTES = 512 * 1024

    private const val DIR = "cards"

    /** Older cards in the cache than this are left over from a draft nobody finished. */
    private const val STALE_MS = 24 * 60 * 60 * 1000L

    /** Whether a type is a vCard, under either of the names it goes by. */
    fun isVCardType(type: String?): Boolean {
        val t = type?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return t == "text/x-vcard" || t == "text/vcard"
    }

    /**
     * The card for [picked], the address a contacts app hands back from a pick, written to the
     * cache; null if no card with anything in it could be made.
     *
     * Reads the provider, so call it off the main thread.
     */
    fun make(context: Context, picked: Uri): Card? {
        val contact = runCatching { readContact(context, picked) }
            .onFailure { Timber.w(it, "contact card: could not read the picked contact") }
            .getOrNull() ?: return null

        val text = fromProvider(context, contact.lookupKey)?.takeIf(::usable)
            ?: run {
                Timber.w("contact card: the provider's card was empty or unreadable; writing it from the contact's rows")
                runCatching { fromRows(contact.displayName, readRows(context, contact.id)) }
                    .onFailure { Timber.w(it, "contact card: could not read the contact's rows") }
                    .getOrNull()
                    ?.takeIf(::usable)
            }
            ?: return null

        val bytes = text.toByteArray(Charsets.UTF_8)
        val name = nameIn(text) ?: contact.displayName?.takeIf { it.isNotBlank() } ?: "contact"
        return runCatching { write(context, name, bytes) }
            .onFailure { Timber.w(it, "contact card: could not write the card to the cache") }
            .getOrNull()
            ?.let { Card(it, name, bytes) }
    }

    private class Contact(val id: Long, val lookupKey: String?, val displayName: String?)

    private fun readContact(context: Context, picked: Uri): Contact? =
        context.contentResolver.query(
            picked,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME
            ),
            null, null, null
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            Contact(
                id = c.getLong(0),
                // The column as the provider stores it, which is already encoded for a path
                // segment; the lookup uri's own segment is the fallback if a contacts app
                // answers with something that has no such column.
                lookupKey = c.getString(1)?.takeIf { it.isNotBlank() }
                    ?: lookupKeyIn(picked)?.let(Uri::encode),
                displayName = c.getString(2)
            )
        }

    /** The key in a `contacts/lookup/<key>/<id>` address, decoded; null for any other kind. */
    private fun lookupKeyIn(uri: Uri): String? {
        val segments = uri.pathSegments
        val i = segments.indexOf("lookup")
        return if (i >= 0 && i + 1 < segments.size) segments[i + 1] else null
    }

    /** The provider's own card, without the photo, which only makes a text heavier. */
    private fun fromProvider(context: Context, lookupKey: String?): String? {
        if (lookupKey.isNullOrBlank()) return null
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookupKey)
            .buildUpon()
            .appendQueryParameter(ContactsContract.Contacts.QUERY_PARAMETER_VCARD_NO_PHOTO, "true")
            .build()
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BYTES) return@use null
                }
                out.toByteArray()
            }
        }.onFailure { Timber.w(it, "contact card: the provider would not open the card") }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.toString(Charsets.UTF_8)
    }

    /** One row of a contact's data, as the provider keeps it: a kind and its columns. */
    class Row(val mimeType: String, val values: Map<String, String?>) {
        operator fun get(column: String): String? = values[column]?.takeIf { it.isNotBlank() }
    }

    private val COLUMNS = arrayOf(
        ContactsContract.Data.DATA1, ContactsContract.Data.DATA2, ContactsContract.Data.DATA3,
        ContactsContract.Data.DATA4, ContactsContract.Data.DATA5, ContactsContract.Data.DATA6,
        ContactsContract.Data.DATA7, ContactsContract.Data.DATA8, ContactsContract.Data.DATA9,
        ContactsContract.Data.DATA10
    )

    private fun readRows(context: Context, contactId: Long): List<Row> =
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.MIMETYPE, *COLUMNS),
            "${ContactsContract.Data.CONTACT_ID}=?",
            arrayOf(contactId.toString()),
            null
        )?.use { c ->
            val rows = mutableListOf<Row>()
            while (c.moveToNext()) {
                val mime = c.getString(0) ?: continue
                rows += Row(mime, COLUMNS.withIndex().associate { (i, col) -> col to c.getString(i + 1) })
            }
            rows
        } ?: emptyList()

    /**
     * A vCard 3.0 written from a contact's rows; null when there is nothing in them to send.
     *
     * The column numbers are the provider's (`CommonDataKinds`), written out here as their
     * `dataN` names because that is what [Row] carries. A contact made of two joined ones has
     * each number twice; the second copy is left out.
     */
    fun fromRows(displayName: String?, rows: List<Row>): String? {
        val card = VCard()
        val seen = HashSet<String>()
        fun firstTime(kind: String, value: String) = seen.add(kind + "\u0000" + value.lowercase())

        rows.firstOrNull { it.mimeType == CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE }?.let { r ->
            val name = StructuredName().apply {
                given = r["data2"]
                family = r["data3"]
                r["data4"]?.let { prefixes.add(it) }
                r["data5"]?.let { additionalNames.add(it) }
                r["data6"]?.let { suffixes.add(it) }
            }
            if (name.given != null || name.family != null) card.structuredName = name
        }
        val formatted = displayName?.takeIf { it.isNotBlank() }
            ?: rows.firstOrNull { it.mimeType == CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE }?.get("data1")
            ?: listOfNotNull(card.structuredName?.given, card.structuredName?.family).joinToString(" ").ifBlank { null }

        for (r in rows) {
            when (r.mimeType) {
                CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                    val number = r["data1"] ?: continue
                    if (!firstTime("tel", number.filter { it.isDigit() || it == '+' })) continue
                    card.addTelephoneNumber(number, *phoneTypes(r["data2"]?.toIntOrNull()))
                }
                CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                    val address = r["data1"] ?: continue
                    if (!firstTime("email", address)) continue
                    when (r["data2"]?.toIntOrNull()) {
                        CommonDataKinds.Email.TYPE_HOME -> card.addEmail(address, EmailType.HOME)
                        CommonDataKinds.Email.TYPE_WORK -> card.addEmail(address, EmailType.WORK)
                        else -> card.addEmail(address)
                    }
                }
                CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                    val address = Address().apply {
                        streetAddress = r["data4"]
                        poBox = r["data5"]
                        locality = r["data7"]
                        region = r["data8"]
                        postalCode = r["data9"]
                        country = r["data10"]
                    }
                    val parts = listOfNotNull(
                        address.streetAddress, address.poBox, address.locality,
                        address.region, address.postalCode, address.country
                    )
                    if (parts.isEmpty()) {
                        // Only the address as typed, all in one line: it goes in the street.
                        address.streetAddress = r["data1"] ?: continue
                    }
                    if (!firstTime("adr", parts.joinToString("|").ifEmpty { r["data1"].orEmpty() })) continue
                    when (r["data2"]?.toIntOrNull()) {
                        CommonDataKinds.StructuredPostal.TYPE_HOME -> address.types.add(AddressType.HOME)
                        CommonDataKinds.StructuredPostal.TYPE_WORK -> address.types.add(AddressType.WORK)
                    }
                    card.addAddress(address)
                }
                CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                    if (card.organization != null) continue
                    val company = r["data1"]
                    val department = r["data5"]
                    if (company != null || department != null) {
                        card.setOrganization(*listOfNotNull(company ?: "", department).toTypedArray())
                    }
                    r["data4"]?.let { card.addTitle(it) }
                }
                CommonDataKinds.Event.CONTENT_ITEM_TYPE -> {
                    if (r["data2"]?.toIntOrNull() != CommonDataKinds.Event.TYPE_BIRTHDAY) continue
                    if (card.birthday != null || card.getExtendedProperty("BDAY") != null) continue
                    val text = r["data1"]?.trim() ?: continue
                    val noYear = Regex("""--(\d{2})-?(\d{2})""").matchEntire(text)
                    if (noYear != null) {
                        // vCard 3.0 has no date without a year and ez-vcard writes an empty
                        // BDAY for one, so it goes as Android's own composer writes it.
                        val (month, day) = noYear.destructured
                        card.setExtendedProperty("BDAY", "--$month-$day")
                    } else {
                        birthday(text)?.let { card.birthday = it }
                    }
                }
                CommonDataKinds.Note.CONTENT_ITEM_TYPE -> {
                    val note = r["data1"] ?: continue
                    if (firstTime("note", note)) card.addNote(note)
                }
            }
        }

        val reachable = card.telephoneNumbers.isNotEmpty() || card.emails.isNotEmpty()
        if (formatted == null && !reachable) return null
        card.setFormattedName(formatted ?: card.telephoneNumbers.firstOrNull()?.text ?: card.emails.first().value)
        return Ezvcard.write(card).version(VCardVersion.V3_0).prodId(false).go()
    }

    private fun phoneTypes(type: Int?): Array<TelephoneType> = when (type) {
        CommonDataKinds.Phone.TYPE_HOME -> arrayOf(TelephoneType.HOME)
        CommonDataKinds.Phone.TYPE_MOBILE -> arrayOf(TelephoneType.CELL)
        CommonDataKinds.Phone.TYPE_WORK -> arrayOf(TelephoneType.WORK)
        CommonDataKinds.Phone.TYPE_FAX_WORK -> arrayOf(TelephoneType.WORK, TelephoneType.FAX)
        CommonDataKinds.Phone.TYPE_FAX_HOME -> arrayOf(TelephoneType.HOME, TelephoneType.FAX)
        CommonDataKinds.Phone.TYPE_PAGER -> arrayOf(TelephoneType.PAGER)
        CommonDataKinds.Phone.TYPE_WORK_MOBILE -> arrayOf(TelephoneType.WORK, TelephoneType.CELL)
        CommonDataKinds.Phone.TYPE_MAIN -> arrayOf(TelephoneType.PREF)
        else -> arrayOf(TelephoneType.VOICE)
    }

    /**
     * A birthday with its year, as contacts apps store it: `1990-05-01`. Anything else is
     * left off rather than guessed at.
     */
    private fun birthday(text: String): Birthday? {
        val t = text.trim()
        Regex("""(\d{4})-(\d{2})-(\d{2})""").matchEntire(t)?.let {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
            return runCatching { Birthday(format.parse(t), false) }.getOrNull()
        }
        return null
    }

    /** Whether [text] is a card worth sending: it parses, and names somebody or reaches them. */
    fun usable(text: String): Boolean {
        val card = runCatching { Ezvcard.parse(text).first() }.getOrNull() ?: return false
        return nameOf(card) != null || card.telephoneNumbers.any { !it.text.isNullOrBlank() } ||
            card.emails.any { !it.value.isNullOrBlank() }
    }

    /** The name on a card, for the attachment and its file. */
    fun nameIn(text: String): String? =
        runCatching { Ezvcard.parse(text).first() }.getOrNull()?.let(::nameOf)

    private fun nameOf(card: VCard): String? =
        card.formattedName?.value?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(card.structuredName?.given, card.structuredName?.family)
                .joinToString(" ").takeIf { it.isNotBlank() }

    /**
     * A file name for a card: the person's name in plain ASCII, `Zoe_Bronte.vcf`.
     *
     * It becomes the MMS part's Content-Location and Content-ID, which are ASCII text in the
     * PDU; an accent or an emoji there is bytes a carrier's server may refuse or mangle. The
     * name the other phone shows comes from the card itself, so nothing is lost by it.
     */
    fun fileName(name: String): String {
        val plain = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
            .filter { it.isLetterOrDigit() && it.code < 128 || it == ' ' || it == '-' || it == '.' }
            .trim().trim('.').replace(Regex("\\s+"), "_").take(60)
        return plain.ifEmpty { "contact" } + ".vcf"
    }

    private fun write(context: Context, name: String, bytes: ByteArray): File {
        val root = File(context.cacheDir, DIR)
        // A card a draft was abandoned with. Those still attached are a day old at most.
        root.listFiles()?.forEach { dir ->
            if (System.currentTimeMillis() - dir.lastModified() > STALE_MS) dir.deleteRecursively()
        }
        // A folder each, so two people of the same name are two files and each keeps its name.
        val dir = File(root, System.nanoTime().toString()).apply { mkdirs() }
        return File(dir, fileName(name)).apply { writeBytes(bytes) }
    }
}
