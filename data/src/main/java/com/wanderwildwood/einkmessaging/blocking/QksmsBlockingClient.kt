/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.wanderwildwood.einkmessaging.blocking

import com.wanderwildwood.einkmessaging.manager.PermissionManager
import com.wanderwildwood.einkmessaging.repository.BlockingRepository
import com.wanderwildwood.einkmessaging.repository.ContactRepository
import com.wanderwildwood.einkmessaging.util.Preferences
import io.reactivex.Completable
import io.reactivex.Single
import javax.inject.Inject

/**
 * Decides whether a message should be blocked.
 *
 * Kept as a pure function so the truth table can be tested without Android, a content resolver or
 * a preferences store. The permission argument matters: without it, a denied contacts permission
 * makes every lookup fail, "not a contact" quietly becomes "everyone", and the rule blocks the
 * whole inbox.
 */
internal fun shouldBlockMessage(
    isBlacklisted: Boolean,
    onlyAllowContacts: Boolean,
    canReadContacts: Boolean,
    isContact: Boolean,
): Boolean = isBlacklisted || (onlyAllowContacts && canReadContacts && !isContact)

class QksmsBlockingClient @Inject constructor(
    private val blockingRepo: BlockingRepository,
    private val contactRepo: ContactRepository,
    private val permissionManager: PermissionManager,
    private val prefs: Preferences
) : BlockingClient {

    override fun isAvailable(): Boolean = true

    override fun getClientCapability() = BlockingClient.Capability.BLOCK_WITHOUT_PERMISSION

    override fun shouldBlock(address: String): Single<BlockingClient.Action> = Single.fromCallable {
        val onlyAllowContacts = prefs.blockNonContacts.get()
        val block = shouldBlockMessage(
            isBlacklisted = blockingRepo.isBlocked(address),
            onlyAllowContacts = onlyAllowContacts,
            canReadContacts = permissionManager.hasContacts(),
            // Only looked up when the setting is on, so the usual case stays one Realm read
            // rather than a contacts-provider query per incoming message.
            isContact = onlyAllowContacts && contactRepo.isContact(address),
        )
        when (block) {
            true -> BlockingClient.Action.Block()
            false -> BlockingClient.Action.Unblock
        }
    }

    override fun isBlacklisted(address: String): Single<BlockingClient.Action> = Single.fromCallable {
        when (blockingRepo.isBlocked(address)) {
            true -> BlockingClient.Action.Block()
            false -> BlockingClient.Action.Unblock
        }
    }

    override fun block(addresses: List<String>): Completable = Completable.fromCallable {
        blockingRepo.blockNumber(*addresses.toTypedArray())
    }

    override fun unblock(addresses: List<String>): Completable = Completable.fromCallable {
        blockingRepo.unblockNumbers(*addresses.toTypedArray())
    }

    override fun openSettings() = Unit // TODO: Do this here once we implement AndroidX navigation

}
