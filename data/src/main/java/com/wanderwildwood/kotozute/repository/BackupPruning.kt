package com.wanderwildwood.kotozute.repository

/**
 * Which old backups an automatic backup removes.
 *
 * Only a file this app wrote is ever a candidate: its name is `backup-` and fourteen digits and
 * `.json` (older, plain) or `.bin` (encrypted, see BackupCrypto; `.kzb` on test builds of
 * 2026-11-19 only), exactly what [BackupRepositoryImpl.performBackup] creates. Anything else in the
 * folder, including a copy the file provider renamed to `backup-... (1).json`, is never named.
 */
object BackupPruning {

    /** What [BackupRepositoryImpl.performBackup] names a backup file. */
    val OWN_BACKUP_NAME = Regex("^backup-\\d{14}\\.(json|bin|kzb)$")

    data class Entry(val name: String, val modifiedAt: Long)

    /**
     * The names to delete so that [keep] of this app's backups remain.
     *
     * The newest are kept, by modified time and then by name (the name carries the time, and
     * some providers report 0 for every file). [justWritten] is never deleted, whatever its
     * time says, and counts towards [keep]. A [keep] below 1 deletes nothing.
     */
    fun toDelete(entries: List<Entry>, keep: Int, justWritten: String): List<String> {
        if (keep < 1) return emptyList()
        val own = entries
            .filter { OWN_BACKUP_NAME.matches(it.name) }
            .distinctBy { it.name }
            .sortedWith(compareByDescending<Entry> { it.modifiedAt }.thenByDescending { it.name })
        val kept = own.filter { it.name == justWritten }.map { it.name }.toMutableSet()
        for (entry in own) {
            if (kept.size >= keep) break
            kept += entry.name
        }
        return own.map { it.name }.filter { it !in kept }
    }
}
