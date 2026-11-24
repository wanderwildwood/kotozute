package com.wanderwildwood.kotozute.common.util

import com.wanderwildwood.kotozute.model.SavedFileNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a saved picture, video or file is called, and which kind goes to the Gallery. */
class SavedFileNamesTest {

    @Test
    fun aNamelessPictureGetsAnExtensionFromItsType() {
        assertEquals("part-7.jpg", SavedFileNames.nameFor(null, "image/jpeg", "part-7"))
        assertEquals("part-7.jpg", SavedFileNames.nameFor("", "image/jpeg", "part-7"))
        assertEquals("part-7.png", SavedFileNames.nameFor("   ", "IMAGE/PNG", "part-7"))
    }

    @Test
    fun theOldUnknownWithNoExtensionIsGone() {
        val name = SavedFileNames.nameFor(null, "video/mp4", "part-9")
        assertEquals("part-9.mp4", name)
        assertTrue(name != "unknown")
    }

    @Test
    fun aNameWithoutAnExtensionGetsOne() {
        assertEquals("IMG_0001.jpg", SavedFileNames.nameFor("IMG_0001", "image/jpeg", "x"))
    }

    @Test
    fun aNameWithAnExtensionKeepsIt() {
        assertEquals("notes.pdf", SavedFileNames.nameFor("notes.pdf", "application/pdf", "x"))
        assertEquals("book.epub", SavedFileNames.nameFor("book.epub", "application/octet-stream", "x"))
    }

    @Test
    fun aVagueTypeAddsNothing() {
        assertEquals("part-3", SavedFileNames.nameFor(null, "application/octet-stream", "part-3"))
        assertEquals("part-3", SavedFileNames.nameFor(null, "", "part-3"))
        assertEquals("part-3", SavedFileNames.nameFor(null, "*/*", "part-3"))
    }

    @Test
    fun theSystemIsAskedForATypeTheTableDoesNotKnow() {
        assertEquals("part-1.xyz", SavedFileNames.nameFor(null, "application/x-thing", "part-1") { "xyz" })
        assertNull(SavedFileNames.extensionFor("application/x-thing") { null })
        assertNull(SavedFileNames.extensionFor("application/x-thing") { throw IllegalStateException() })
    }

    @Test
    fun aSendersPathNeverReachesAFilePath() {
        assertEquals("passwd.jpg", SavedFileNames.nameFor("../../etc/passwd", "image/jpeg", "x"))
        assertEquals("evil.png", SavedFileNames.nameFor("C:\\temp\\evil.png", "image/png", "x"))
        assertEquals("part-5.jpg", SavedFileNames.nameFor("..", "image/jpeg", "part-5"))
        assertEquals("part-5.jpg", SavedFileNames.nameFor("///", "image/jpeg", "part-5"))
        assertEquals("part-5.jpg", SavedFileNames.nameFor(".hidden/", "image/jpeg", "part-5"))
        for (bad in listOf("a/b", "a\\b", "..", "x\u0000y", "a:b*c?.jpg")) {
            val out = SavedFileNames.nameFor(bad, "image/jpeg", "part-5")
            assertFalse(out, out.contains('/') || out.contains('\\') || out.contains(".."))
        }
    }

    @Test
    fun oddCharactersAreReplaced() {
        assertEquals("my_photo__1_.jpg", SavedFileNames.nameFor("my photo (1).jpg", "image/jpeg", "x"))
    }

    @Test
    fun aLongNameIsCut() {
        assertTrue(SavedFileNames.nameFor("a".repeat(500), "image/jpeg", "x").length <= 125)
    }

    @Test
    fun picturesAndVideosGoToTheGallery() {
        assertTrue(SavedFileNames.isGalleryMedia("image/jpeg"))
        assertTrue(SavedFileNames.isGalleryMedia("image/jpg"))
        assertTrue(SavedFileNames.isGalleryMedia("image/gif"))
        assertTrue(SavedFileNames.isGalleryMedia("video/3gpp"))
        assertTrue(SavedFileNames.isGalleryMedia("Video/MP4; codecs=avc1"))
    }

    @Test
    fun everythingElseKeepsThePicker() {
        assertFalse(SavedFileNames.isGalleryMedia("application/pdf"))
        assertFalse(SavedFileNames.isGalleryMedia("application/epub+zip"))
        assertFalse(SavedFileNames.isGalleryMedia("text/x-vcard"))
        assertFalse(SavedFileNames.isGalleryMedia("audio/mpeg"))
        assertFalse(SavedFileNames.isGalleryMedia("application/octet-stream"))
        assertFalse(SavedFileNames.isGalleryMedia("image/*"))
        assertFalse(SavedFileNames.isGalleryMedia(""))
        assertFalse(SavedFileNames.isGalleryMedia(null))
    }

    @Test
    fun videosAreToldFromPictures() {
        assertTrue(SavedFileNames.isVideo("video/mp4"))
        assertFalse(SavedFileNames.isVideo("image/png"))
    }

    @Test
    fun typesAreFoldedToOneSpelling() {
        assertEquals("image/jpeg", SavedFileNames.normalizeType(" Image/JPG "))
        assertEquals("text/plain", SavedFileNames.normalizeType("text/plain; charset=utf-8"))
        assertEquals("jpg", SavedFileNames.extensionFor("image/jpg"))
    }
}
