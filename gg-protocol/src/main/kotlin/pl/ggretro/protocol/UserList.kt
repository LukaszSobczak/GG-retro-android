package pl.ggretro.protocol

/** A contact imported from the server-side contact list (GG 6 export format). */
data class ImportedContact(val uin: Int, val name: String, val group: String = "")

/**
 * Parses the CSV-like list stored by GG 6 on the server:
 * `imie;nazwisko;pseudonim;wyswietlane;telefon;grupa;numer;email;...` (one contact per line).
 */
object UserList {
    fun parse(data: ByteArray): List<ImportedContact> = parse(Cp1250.decode(data))

    fun parse(text: String): List<ImportedContact> {
        val out = LinkedHashMap<Int, ImportedContact>()
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.startsWith("GG70ExportString")) continue
            val f = line.split(';')
            if (f.size < 7) continue
            val uin = f[6].trim().toIntOrNull() ?: continue
            if (uin <= 0) continue
            val first = f.getOrElse(0) { "" }.trim()
            val last = f.getOrElse(1) { "" }.trim()
            val nick = f.getOrElse(2) { "" }.trim()
            val display = f.getOrElse(3) { "" }.trim()
            val name = display.ifEmpty { nick }.ifEmpty { "$first $last".trim() }.ifEmpty { uin.toString() }
            out[uin] = ImportedContact(uin, name, f.getOrElse(5) { "" }.trim())
        }
        return out.values.toList()
    }

    /** Serializes contacts in the same format (used for export / tests). */
    fun format(contacts: List<ImportedContact>): String =
        contacts.joinToString("") { c ->
            ";;${c.name};${c.name};;${c.group};${c.uin};;0;;0;;0;\r\n"
        }
}
