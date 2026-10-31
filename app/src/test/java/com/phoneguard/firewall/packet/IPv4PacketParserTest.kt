package com.phoneguard.firewall.packet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IPv4PacketParserTest {
    @Test
    fun `parses a valid minimal IPv4 packet`() {
        val metadata = requireNotNull(IPv4PacketParser.parse(packet()))
        assertEquals(4, metadata.version)
        assertEquals(20, metadata.headerLength)
        assertEquals(20, metadata.totalLength)
        assertEquals("192.0.2.1", metadata.sourceAddress.hostAddress)
        assertEquals("198.51.100.2", metadata.destinationAddress.hostAddress)
        assertFalse(metadata.isFragmented)
        assertTrue(metadata.canParseTransportHeader)
    }

    @Test
    fun `parses a valid header containing options`() {
        val metadata = requireNotNull(IPv4PacketParser.parse(packet(headerLength = 24)))
        assertEquals(24, metadata.headerLength)
        assertEquals(24, metadata.totalLength)
    }

    @Test
    fun `extracts TCP protocol number`() {
        assertEquals(6, IPv4PacketParser.parse(packet(protocol = 6))?.protocol)
    }

    @Test
    fun `extracts UDP protocol number`() {
        assertEquals(17, IPv4PacketParser.parse(packet(protocol = 17))?.protocol)
    }

    @Test
    fun `extracts IPv4 identification`() {
        assertEquals(0x1234, IPv4PacketParser.parse(packet(identification = 0x1234))?.identification)
    }

    @Test
    fun `rejects non-IPv4 version`() {
        assertNull(IPv4PacketParser.parse(packet().also { it[0] = 0x65 }))
    }

    @Test
    fun `rejects packet shorter than minimum header`() {
        assertNull(IPv4PacketParser.parse(ByteArray(19)))
    }

    @Test
    fun `rejects IHL smaller than minimum`() {
        assertNull(IPv4PacketParser.parse(packet().also { it[0] = 0x44 }))
    }

    @Test
    fun `rejects header length larger than supplied data`() {
        assertNull(IPv4PacketParser.parse(packet().also { it[0] = 0x46 }))
    }

    @Test
    fun `rejects total length smaller than header length`() {
        assertNull(IPv4PacketParser.parse(packet().also { setUnsignedShort(it, 2, 19) }))
    }

    @Test
    fun `rejects total length larger than supplied data`() {
        assertNull(IPv4PacketParser.parse(packet().also { setUnsignedShort(it, 2, 21) }))
    }

    @Test
    fun `exposes first fragment metadata`() {
        val metadata = requireNotNull(IPv4PacketParser.parse(packet(fragmentation = 0x2000)))
        assertEquals(0, metadata.fragmentOffset)
        assertTrue(metadata.moreFragments)
        assertTrue(metadata.isFirstFragment)
        assertFalse(metadata.isNonFirstFragment)
        assertTrue(metadata.isFragmented)
        assertTrue(metadata.canParseTransportHeader)
    }

    @Test
    fun `exposes non-first fragment metadata`() {
        val metadata = requireNotNull(IPv4PacketParser.parse(packet(fragmentation = 25)))
        assertEquals(25, metadata.fragmentOffset)
        assertFalse(metadata.moreFragments)
        assertFalse(metadata.isFirstFragment)
        assertTrue(metadata.isNonFirstFragment)
        assertTrue(metadata.isFragmented)
        assertFalse(metadata.canParseTransportHeader)
    }

    private fun packet(
        headerLength: Int = 20,
        protocol: Int = 0,
        fragmentation: Int = 0,
        identification: Int = 0,
    ): ByteArray = ByteArray(headerLength).also { packet ->
        packet[0] = ((4 shl 4) or (headerLength / 4)).toByte()
        setUnsignedShort(packet, 2, headerLength)
        setUnsignedShort(packet, 4, identification)
        setUnsignedShort(packet, 6, fragmentation)
        packet[9] = protocol.toByte()
        byteArrayOf(192.toByte(), 0, 2, 1).copyInto(packet, 12)
        byteArrayOf(198.toByte(), 51, 100, 2).copyInto(packet, 16)
    }

    private fun setUnsignedShort(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value ushr 8).toByte()
        packet[offset + 1] = value.toByte()
    }

    @Test
    fun `empty and truncated packets are rejected without exception`() {
        assertNull(IPv4PacketParser.parse(ByteArray(0)))
        assertNull(IPv4PacketParser.parse(ByteArray(1)))
        assertNull(IPv4PacketParser.parse(ByteArray(19)))
    }

    @Test
    fun `ipv6 packet is not parsed as ipv4`() {
        // Версия 6 в старшем ниббле первого байта: туннель фаервола забирает такие
        // пакеты (маршрут ::/0), и они должны просто отбрасываться.
        val ipv6Header = ByteArray(40).apply { this[0] = 0x60 }
        assertNull(IPv4PacketParser.parse(ipv6Header))
    }
}
