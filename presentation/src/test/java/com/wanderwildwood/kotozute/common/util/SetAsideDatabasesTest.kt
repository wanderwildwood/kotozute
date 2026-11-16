package com.wanderwildwood.kotozute.common.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the "remove unreadable message files" row is allowed to delete is decided by these two
 * predicates and nothing else. The cases that matter are the ones that must NOT match.
 */
class SetAsideDatabasesTest {

    @Test
    fun aSetAsideDatabaseMatches() {
        assertTrue(SetAsideDatabases.isSetAsideFileName("default.realm.unreadable-1794866400000"))
        assertTrue(SetAsideDatabases.isSetAsideFileName("default.realm.unreadable-1"))
    }

    @Test
    fun theLiveDatabaseNeverMatches() {
        assertFalse(SetAsideDatabases.isSetAsideFileName("default.realm"))
    }

    @Test
    fun theLiveDatabasesOwnFilesNeverMatch() {
        listOf(
            "default.realm.lock",
            "default.realm.note",
            "default.realm.management",
            "default.realm.pre-encryption",
            "default.realm.encrypting"
        ).forEach { assertFalse(it, SetAsideDatabases.isSetAsideFileName(it)) }
    }

    @Test
    fun nearMissesDoNotMatch() {
        listOf(
            "",
            "default.realm.unreadable-",
            "default.realm.unreadable-abc",
            "default.realm.unreadable-123.lock",
            "default.realm.unreadable-123/..",
            "../default.realm.unreadable-123",
            "xdefault.realm.unreadable-123",
            "default_realm.unreadable-123",
            "defaultXrealm.unreadable-123",
            "other.realm.unreadable-123",
            "default.realm.unreadable-123\n",
            "default.realm.unreadable--123",
            "signal-protocol.db",
            "unreadable-123"
        ).forEach { assertFalse(it, SetAsideDatabases.isSetAsideFileName(it)) }
    }

    @Test
    fun aSetAsideKeyMatches() {
        assertTrue(SetAsideDatabases.isSetAsideKeyName("sealed_key.unreadable-1794866400000"))
        assertTrue(SetAsideDatabases.isSetAsideKeyName("iv.unreadable-1794866400000"))
    }

    @Test
    fun theLiveKeyNeverMatches() {
        listOf("sealed_key", "iv", "realm_is_encrypted")
            .forEach { assertFalse(it, SetAsideDatabases.isSetAsideKeyName(it)) }
    }

    @Test
    fun keyNearMissesDoNotMatch() {
        listOf(
            "",
            "sealed_key.unreadable-",
            "sealed_key.unreadable-abc",
            "sealed_keyXunreadable-123",
            "xsealed_key.unreadable-123",
            "iv.unreadable-123x",
            "ivy.unreadable-123",
            "realm_is_encrypted.unreadable-123",
            "default.realm.unreadable-123"
        ).forEach { assertFalse(it, SetAsideDatabases.isSetAsideKeyName(it)) }
    }
}
