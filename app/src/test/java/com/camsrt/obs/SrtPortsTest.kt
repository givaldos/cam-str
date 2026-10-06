package com.camsrt.obs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtPortsTest {

    @Test
    fun singlePortScansItFirstPlusDefaults() {
        val ports = SrtPorts.parseScanPorts("9998")
        assertEquals(9998, ports.first())
        assertTrue(ports.containsAll(SrtPorts.defaultScanPorts))
        assertEquals(1 + SrtPorts.defaultScanPorts.size - 1, ports.size)
    }

    @Test
    fun garbageScansDefaultsOnly() {
        assertEquals(SrtPorts.defaultScanPorts, SrtPorts.parseScanPorts("abc"))
    }

    @Test
    fun rangeScansExactlyTheRange() {
        assertEquals(listOf(9900, 9901, 9902), SrtPorts.parseScanPorts("9900-9902"))
    }

    @Test
    fun reversedRangeStartsAtLower() {
        val ports = SrtPorts.parseScanPorts("9910-9900")
        assertEquals(9900, ports.first())
        assertEquals(9910, ports.last())
    }

    @Test
    fun rangeIsCappedAt50Ports() {
        val ports = SrtPorts.parseScanPorts("9000-9200")
        assertEquals(50, ports.size)
        assertEquals(9000, ports.first())
        assertEquals(9049, ports.last())
    }

    @Test
    fun connectUsesSinglePort() {
        assertEquals(9998, SrtPorts.parsePortOrFirst("9998"))
    }

    @Test
    fun connectUsesRangeStart() {
        assertEquals(9900, SrtPorts.parsePortOrFirst("9900-9910"))
    }

    @Test
    fun connectFallsBackTo9998() {
        assertEquals(9998, SrtPorts.parsePortOrFirst("abc"))
        assertEquals(9998, SrtPorts.parsePortOrFirst(""))
    }

    @Test
    fun networkSniffCovers9990to9999() {
        assertEquals((9990..9999).toList(), SrtPorts.networkSniffPorts)
    }

    @Test
    fun defaultScanIncludes9990to9999() {
        assertTrue(SrtPorts.defaultScanPorts.containsAll((9990..9999).toList()))
    }

    @Test
    fun blankPortSniffs9990to9999() {
        assertEquals((9990..9999).toList(), SrtPorts.parseNetworkScanPorts(""))
        assertEquals((9990..9999).toList(), SrtPorts.parseNetworkScanPorts("abc"))
    }

    @Test
    fun singlePortNetworkScanKeepsItFirstPlusSniffRange() {
        val ports = SrtPorts.parseNetworkScanPorts("9998")
        assertEquals(9998, ports.first())
        assertTrue(ports.containsAll((9990..9999).toList()))
    }

    @Test
    fun networkRangeScansExactlyTheRange() {
        assertEquals(listOf(9990, 9991, 9992), SrtPorts.parseNetworkScanPorts("9990-9992"))
    }

    @Test
    fun networkReversedRangeStartsAtLower() {
        val ports = SrtPorts.parseNetworkScanPorts("9992-9990")
        assertEquals(9990, ports.first())
        assertEquals(9992, ports.last())
    }

    @Test
    fun networkRangeIsCappedAt50Ports() {
        val ports = SrtPorts.parseNetworkScanPorts("9000-9200")
        assertEquals(50, ports.size)
        assertEquals(9000, ports.first())
    }

    @Test
    fun formatKeepsIpv4AndHostnames() {
        assertEquals("192.168.0.10", SrtPorts.formatSrtHost("192.168.0.10"))
        assertEquals("192.168.0.10", SrtPorts.formatSrtHost("  192.168.0.10  "))
        assertEquals("meumac.local", SrtPorts.formatSrtHost("meumac.local"))
    }

    @Test
    fun formatWrapsIpv6InBrackets() {
        assertEquals("[fe80::1]", SrtPorts.formatSrtHost("fe80::1"))
        assertEquals("[fe80::1]", SrtPorts.formatSrtHost("[fe80::1]"))
        assertEquals("[::1]", SrtPorts.formatSrtHost("::1"))
    }

    @Test
    fun formatEncodesIpv6ZoneId() {
        assertEquals("[fe80::1%25wlan0]", SrtPorts.formatSrtHost("fe80::1%wlan0"))
    }

    @Test
    fun bareHostStripsBrackets() {
        assertEquals("fe80::1", SrtPorts.bareHost("[fe80::1]"))
        assertEquals("fe80::1", SrtPorts.bareHost("fe80::1"))
        assertEquals("192.168.0.10", SrtPorts.bareHost(" 192.168.0.10 "))
    }

    @Test
    fun sweepListsSlash24WithoutSelf() {
        val hosts = SrtPorts.ipv4SweepHosts("192.168.2.5")
        assertEquals(253, hosts.size)
        assertTrue(hosts.contains("192.168.2.1"))
        assertTrue(hosts.none { it == "192.168.2.5" })
        assertTrue(hosts.all { it.startsWith("192.168.2.") })
    }

    @Test
    fun sweepRejectsGarbage() {
        assertEquals(emptyList<String>(), SrtPorts.ipv4SweepHosts("abc"))
        assertEquals(emptyList<String>(), SrtPorts.ipv4SweepHosts("fe80::1"))
        assertEquals(emptyList<String>(), SrtPorts.ipv4SweepHosts("999.1.1.1"))
        assertEquals(emptyList<String>(), SrtPorts.ipv4SweepHosts(""))
    }

    @Test
    fun ipv4LiteralGoesDirectUnchanged() {
        val route = SrtPorts.routeFor("192.168.0.10")
        assertTrue(route is SrtPorts.StreamRoute.Direct)
        assertEquals("192.168.0.10", (route as SrtPorts.StreamRoute.Direct).hostForUrl)
    }

    @Test
    fun ipv6LiteralGoesViaRelay() {
        val route = SrtPorts.routeFor("fe80::1")
        assertTrue(route is SrtPorts.StreamRoute.Relay)
        val addr = (route as SrtPorts.StreamRoute.Relay).addr
        assertTrue(addr is java.net.Inet6Address)
    }

    @Test
    fun bracketedIpv6GoesViaRelay() {
        val route = SrtPorts.routeFor("[fe80::1]")
        assertTrue(route is SrtPorts.StreamRoute.Relay)
    }

    @Test
    fun hostnameWithBothPrefersIpv4Direct() {
        val route = SrtPorts.routeFor("duplo.local") {
            listOf(
                java.net.InetAddress.getByName("fe80::5"),
                java.net.InetAddress.getByName("192.168.0.20")
            )
        }
        assertTrue(route is SrtPorts.StreamRoute.Direct)
        assertEquals(
            "192.168.0.20",
            (route as SrtPorts.StreamRoute.Direct).hostForUrl
        )
    }

    @Test
    fun hostnameWithOnlyIpv6GoesViaRelay() {
        val route = SrtPorts.routeFor("so6.local") {
            listOf(java.net.InetAddress.getByName("fe80::6"))
        }
        assertTrue(route is SrtPorts.StreamRoute.Relay)
    }

    @Test
    fun unresolvableHostPassesThroughDirect() {
        val route = SrtPorts.routeFor("nao-existe.local") { throw java.net.UnknownHostException() }
        assertTrue(route is SrtPorts.StreamRoute.Direct)
        assertEquals(
            "nao-existe.local",
            (route as SrtPorts.StreamRoute.Direct).hostForUrl
        )
    }

    @Test
    fun interfaceScorePrefersWifiOverMobile() {
        assertEquals(0, SrtPorts.interfaceNameScore("wlan0"))
        assertEquals(0, SrtPorts.interfaceNameScore("eth0"))
        assertEquals(0, SrtPorts.interfaceNameScore("en0"))
        assertEquals(0, SrtPorts.interfaceNameScore("ap0"))
        assertEquals(1, SrtPorts.interfaceNameScore("tun0"))
        assertEquals(2, SrtPorts.interfaceNameScore("rmnet0"))
        assertEquals(2, SrtPorts.interfaceNameScore("ccinet0"))
    }

    @Test
    fun lastOctetExpandsWithLocalSlash24() {
        assertEquals("192.168.0.10", SrtPorts.expandLastOctet("10", "192.168.0.20"))
        assertEquals("192.168.0.10", SrtPorts.expandLastOctet(".10", "192.168.0.20"))
        assertEquals("192.168.0.10", SrtPorts.expandLastOctet("  10  ", "192.168.0.20"))
        assertEquals("10.5.9.1", SrtPorts.expandLastOctet("1", "10.5.9.200"))
    }

    @Test
    fun fullHostsAreNotExpanded() {
        assertEquals("192.168.0.10", SrtPorts.expandLastOctet("192.168.0.10", "192.168.0.20"))
        assertEquals("meumac.local", SrtPorts.expandLastOctet("meumac.local", "192.168.0.20"))
        assertEquals("fe80::1", SrtPorts.expandLastOctet("fe80::1", "192.168.0.20"))
        assertEquals("1.2.3", SrtPorts.expandLastOctet("1.2.3", "192.168.0.20"))
        assertEquals("", SrtPorts.expandLastOctet("", "192.168.0.20"))
    }

    @Test
    fun shortAndExpandedFormResolveToSameTarget() {
        // O campo guarda o digitado ("10") e expande só ao usar: as
        // duas formas precisam levar ao mesmo alvo.
        val local = "192.168.0.20"
        val expanded = SrtPorts.expandLastOctet("10", local)
        assertEquals("192.168.0.10", expanded)
        assertEquals(expanded, SrtPorts.expandLastOctet(expanded, local))
    }

    @Test
    fun badOctetOrMissingLocalStaysAsIs() {
        assertEquals("256", SrtPorts.expandLastOctet("256", "192.168.0.20"))
        assertEquals("1234", SrtPorts.expandLastOctet("1234", "192.168.0.20"))
        assertEquals("10", SrtPorts.expandLastOctet("10", null))
        assertEquals("10", SrtPorts.expandLastOctet("10", "abc"))
        assertEquals("10", SrtPorts.expandLastOctet("10", ""))
    }

    @Test
    fun callerUrlUsesLatencyInMilliseconds() {
        assertEquals(
            "srt://192.168.0.10:9998?latency=120&connect_timeout=15000",
            SrtPorts.callerSrtUrl("192.168.0.10", 9998, 120)
        )
        assertEquals(
            "srt://127.0.0.1:45678?latency=200&connect_timeout=15000",
            SrtPorts.callerSrtUrl("127.0.0.1", 45678, 200)
        )
    }

    @Test
    fun callerUrlClampsLatencyToSaneRange() {
        assertEquals(
            "srt://192.168.0.10:9998?latency=5000&connect_timeout=15000",
            SrtPorts.callerSrtUrl("192.168.0.10", 9998, 120000)
        )
        assertEquals(
            "srt://192.168.0.10:9998?latency=20&connect_timeout=15000",
            SrtPorts.callerSrtUrl("192.168.0.10", 9998, 0)
        )
    }

    @Test
    fun callerUrlFormatsIpv6Host() {
        assertEquals(
            "srt://[fe80::1]:9998?latency=120&connect_timeout=15000",
            SrtPorts.callerSrtUrl("fe80::1", 9998, 120)
        )
    }

    @Test
    fun onlyPrivateIpv4IsSweepable() {
        assertTrue(SrtPorts.isSweepableIpv4("192.168.0.10"))
        assertTrue(SrtPorts.isSweepableIpv4("10.0.0.5"))
        assertTrue(SrtPorts.isSweepableIpv4("172.16.4.2"))
        assertTrue(SrtPorts.isSweepableIpv4("172.31.255.1"))
        assertTrue(SrtPorts.isSweepableIpv4("169.254.1.2"))
        assertTrue(!SrtPorts.isSweepableIpv4("8.8.8.8"))
        assertTrue(!SrtPorts.isSweepableIpv4("172.32.0.1"))
        assertTrue(!SrtPorts.isSweepableIpv4("abc"))
    }
}
