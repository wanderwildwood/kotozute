package com.wanderwildwood.kotozute.feature.desktopsync

import com.wanderwildwood.kotozute.feature.desktopsync.DesktopSyncService.Companion.isAllowedPeer
import com.wanderwildwood.kotozute.feature.desktopsync.DesktopSyncService.Companion.Subnet
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Who "VPN only" lets through, before the token is looked at.
 *
 * Tailscale is recognised by its fixed ranges. Any other VPN (ZeroTier, WireGuard, Nebula)
 * picks its own, so the phone's address and prefix on the tunnel are what say which peers
 * are on the far side of it.
 */
class VpnPeerGateTest {

    private fun net(cidr: String): Subnet {
        val (addr, prefix) = cidr.split('/')
        return Subnet(InetAddress.getByName(addr), prefix.toInt())
    }

    @Test
    fun `tailscale and loopback pass with no other vpn up`() {
        assertTrue(isAllowedPeer("100.97.232.53", emptyList()))
        assertTrue(isAllowedPeer("fd7a:115c:a1e0::1", emptyList()))
        assertTrue(isAllowedPeer("127.0.0.1", emptyList()))
        assertTrue(isAllowedPeer("::1", emptyList()))
    }

    @Test
    fun `the home lan is refused with no other vpn up`() {
        assertFalse(isAllowedPeer("192.168.1.20", emptyList()))
        assertFalse(isAllowedPeer("10.147.17.5", emptyList()))
        assertFalse(isAllowedPeer(null, emptyList()))
    }

    @Test
    fun `a peer on the zerotier network passes and one beside it does not`() {
        val zt = listOf(net("10.147.17.42/24"))
        assertTrue(isAllowedPeer("10.147.17.5", zt))
        assertTrue(isAllowedPeer("10.147.17.254", zt))
        assertFalse(isAllowedPeer("10.147.18.5", zt))
        assertFalse(isAllowedPeer("192.168.1.20", zt))
    }

    @Test
    fun `a prefix that is not a whole byte is honoured`() {
        val nets = listOf(net("172.22.100.9/20"))
        assertTrue(isAllowedPeer("172.22.96.1", nets))
        assertTrue(isAllowedPeer("172.22.111.255", nets))
        assertFalse(isAllowedPeer("172.22.112.0", nets))
        assertFalse(isAllowedPeer("172.22.95.255", nets))
    }

    @Test
    fun `ipv6 vpn networks work and never match a v4 peer`() {
        val nets = listOf(net("fd80:56c2:e21c:0:199:9300:4b11:7b8d/88"))
        assertTrue(isAllowedPeer("fd80:56c2:e21c:0:199:9300:1:2", nets))
        assertTrue(isAllowedPeer("FD80:56C2:E21C:0:199:9300:1:2%tun0", nets))
        // /88 is eleven bytes: the "93" of 9300 is the last one fixed, the "00" is free.
        assertTrue(isAllowedPeer("fd80:56c2:e21c:0:199:93ff:1:2", nets))
        assertFalse(isAllowedPeer("fd80:56c2:e21c:0:199:9400:1:2", nets))
        assertFalse(isAllowedPeer("10.0.0.1", nets))
    }

    @Test
    fun `a vpn that is only this phone lets nobody else in`() {
        // A commercial VPN's tunnel is a /32: the phone and nothing else.
        val nets = listOf(net("10.64.12.7/32"))
        assertFalse(isAllowedPeer("10.64.12.8", nets))
    }

    @Test
    fun `a name is never looked up`() {
        assertFalse(isAllowedPeer("localhost", listOf(net("127.0.0.0/8"))))
        assertFalse(isAllowedPeer("example.com", listOf(net("0.0.0.0/8"))))
    }
}
