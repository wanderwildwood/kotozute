package com.wanderwildwood.kotozute.signalstore

import com.wanderwildwood.kotozute.repository.SignalRepository
import com.wanderwildwood.kotozute.repository.SignalRepository.HistoryTransfer as State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.signal.core.models.ServiceId
import org.signal.network.NetworkResult
import org.signal.network.exceptions.NonSuccessfulResponseCodeException
import org.whispersystems.signalservice.api.link.TransferArchiveResponse
import java.io.File
import java.io.IOException

/**
 * When the key to a transferred history is kept and when it is let go.
 *
 * The key arrives once, in the provisioning message, and opens nothing else. Dropping it on a
 * passing network failure loses the history for good; keeping it after the archive is proven
 * unusable retries a failure for ever. These pin which outcome is which.
 */
class HistoryTransferTest {

    @get:Rule val temp = TemporaryFolder()

    private val aci = ServiceId.ACI.parseOrThrow("00000000-0000-4000-8000-000000000001")
    private val linkedAt = 1_700_000_000_000L

    private class Store(var row: HistoryTransfer.Pending?) : HistoryTransfer.Store {
        override fun pending() = row
        override fun saveLocation(linkedAt: Long, cdn: Int, cdnKey: String) {
            row = row?.takeIf { it.linkedAt == linkedAt }
                ?.let { HistoryTransfer.Pending(it.ephemeralKey, it.linkedAt, cdn, cdnKey) }
        }
        override fun finish(linkedAt: Long) {
            if (row?.linkedAt == linkedAt) row = null
        }
    }

    private fun status(code: Int) = NetworkResult.StatusCodeError<Unit>(
        code, null, null, emptyMap(), NonSuccessfulResponseCodeException(code)
    )

    private fun transfer(
        store: Store,
        dir: File = temp.newFolder(),
        primary: () -> NetworkResult<TransferArchiveResponse> =
            { NetworkResult.Success(TransferArchiveResponse(cdn = 3, key = "made-up-key")) },
        download: (File) -> NetworkResult<Unit> = { NetworkResult.NetworkError(IOException("offline")) },
        imported: MutableList<Int> = mutableListOf(),
        now: Long = linkedAt + 1_000
    ) = HistoryTransfer(
        store = store,
        aci = { aci },
        waitForPrimary = primary,
        download = { _, _, into, _ -> download(into) },
        dir = dir,
        import = { _, _ -> imported += 1; SignalRepository.ImportStats(messages = 3) },
        now = { now },
        sleep = {}
    )

    private fun pending(cdn: Int? = null) =
        HistoryTransfer.Pending(ByteArray(32) { 1 }, linkedAt, cdn, cdn?.let { "made-up-key" })

    private fun HistoryTransfer.states(): List<State> {
        val list = mutableListOf<State>()
        this.run { state -> list += state }
        return list
    }

    @Test
    fun `nothing to do when the link brought no key`() {
        val states = transfer(Store(null)).states()
        assertTrue(states.isEmpty())
    }

    @Test
    fun `the primary declining ends it and lets the key go`() {
        val store = Store(pending())
        val states = transfer(
            store,
            primary = { NetworkResult.Success(TransferArchiveResponse(error = TransferArchiveResponse.ERROR_CONTINUE_WITHOUT_UPLOAD)) }
        ).states()
        assertEquals(listOf(State.Waiting, State.Declined), states)
        assertNull(store.row)
    }

    @Test
    fun `a download that fails on the network keeps the key and where the archive is`() {
        val store = Store(pending())
        val states = transfer(store).states()
        assertEquals(State.Later, states.last())
        assertNotNull(store.row)
        assertEquals(3, store.row?.cdn)
    }

    @Test
    fun `the next start downloads without waiting for the primary again`() {
        val store = Store(pending(cdn = 3))
        var asked = 0
        transfer(store, primary = { asked++; NetworkResult.Success(TransferArchiveResponse()) }).states()
        assertEquals(0, asked)
    }

    @Test
    fun `an archive the CDN no longer has is the end of it`() {
        val store = Store(pending(cdn = 3))
        val states = transfer(store, download = { status(404) }).states()
        assertEquals(State.Unusable, states.last())
        assertNull(store.row)
    }

    @Test
    fun `a server hiccup on the download is retried, not given up`() {
        val store = Store(pending(cdn = 3))
        val states = transfer(store, download = { status(503) }).states()
        assertEquals(State.Later, states.last())
        assertNotNull(store.row)
    }

    @Test
    fun `an archive that fails its MAC is not retried, and is deleted`() {
        val store = Store(pending(cdn = 3))
        val dir = temp.newFolder()
        val imported = mutableListOf<Int>()
        val states = transfer(
            store,
            dir = dir,
            download = { into -> into.writeBytes(ByteArray(200) { 5 }); NetworkResult.Success(Unit) },
            imported = imported
        ).states()
        assertEquals(State.Unusable, states.last())
        assertNull(store.row)
        assertTrue(imported.isEmpty())
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `no answer and a day gone since linking is given up`() {
        val store = Store(pending())
        val states = transfer(store, now = linkedAt + HistoryTransfer.GIVE_UP_AFTER_MS + 1).states()
        assertEquals(listOf<State>(State.Unusable), states)
        assertNull(store.row)
    }

    @Test
    fun `a good archive is imported, then the key and the file are let go`() {
        val key = ByteArray(32) { 1 }
        val store = Store(pending(cdn = 3))
        val dir = temp.newFolder()
        val imported = mutableListOf<Int>()
        val states = transfer(
            store,
            dir = dir,
            download = { into ->
                TransferArchiveFixture.write(
                    into, key, aci,
                    listOf(org.signal.archive.proto.Frame(recipient = org.signal.archive.proto.Recipient(id = 1)))
                )
                NetworkResult.Success(Unit)
            },
            imported = imported
        ).states()
        assertEquals(State.Done(SignalRepository.ImportStats(messages = 3)), states.last())
        assertEquals(listOf(1), imported)
        assertNull(store.row)
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }
}
