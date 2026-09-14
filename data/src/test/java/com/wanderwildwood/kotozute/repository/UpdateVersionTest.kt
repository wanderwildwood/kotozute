package com.wanderwildwood.kotozute.repository

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of two versions is newer, which is the whole of what the update check decides.
 *
 * Worth its own test because the obvious shortcut is wrong here. The release workflow packs a
 * version into a versionCode as `major * 10000 + minor * 100 + patch`, and comparing those
 * packed numbers works right up until a patch reaches 100 -- at which point 1.19.100 and
 * 1.20.0 are the same number and the app stops seeing a release it should offer. This app has
 * published sixty-six patches in one minor version already, so that is a matter of weeks.
 */
class UpdateVersionTest {

    private fun newer(a: String, b: String) = UpdateRepositoryImpl.compare(a, b) > 0

    @Test
    fun `a later patch is newer`() {
        assertTrue(newer("1.19.66", "1.19.65"))
    }

    @Test
    fun `a later minor is newer`() {
        assertTrue(newer("1.20.0", "1.19.66"))
    }

    @Test
    fun `a later major is newer`() {
        assertTrue(newer("2.0.0", "1.19.66"))
    }

    @Test
    fun `the same version is not newer`() {
        assertTrue(!newer("1.19.66", "1.19.66"))
    }

    @Test
    fun `an earlier version is not newer`() {
        assertTrue(!newer("1.19.65", "1.19.66"))
    }

    /** The case the packed versionCode gets wrong. */
    @Test
    fun `a three digit patch is older than the next minor`() {
        assertTrue(newer("1.20.0", "1.19.100"))
        assertTrue(!newer("1.19.100", "1.20.0"))
    }

    @Test
    fun `a three digit patch is newer than a two digit one`() {
        assertTrue(newer("1.19.100", "1.19.99"))
    }

    /** A version with fewer parts is read as zeroes, not as a failure. */
    @Test
    fun `a short version still orders`() {
        assertTrue(newer("1.19.1", "1.19"))
        assertTrue(!newer("1.19", "1.19.1"))
    }

}
