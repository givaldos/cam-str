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
}
