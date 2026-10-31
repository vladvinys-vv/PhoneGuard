package com.phoneguard.firewall.packet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportPacketParserTest {
    @Test
    fun `parses TCP ports header length and SYN ACK flags`() {
        val packet = tcpPacket(sourcePort = 12_345, destinationPort = 443, headerLength = 24)
        packet[33] = 0x12
        val metadata = TcpPacketParser.parse(packet, requireNotNull(IPv4PacketParser.parse(packet)))
        requireNotNull(metadata)
        assertEquals(12_345, metadata.sourcePort)
        assertEquals(443, metadata.destinationPort)
        assertEquals(24, metadata.headerLength)
        assertTrue(metadata.syn)
        assertTrue(metadata.ack)
        assertFalse(metadata.fin)
        assertFalse(metadata.rst)
    }

    @Test
    fun `parses TCP FIN and RST flags`() {
        val packet = tcpPacket()
        packet[33] = 0x05
        val metadata = TcpPacketParser.parse(packet, requireNotNull(IPv4PacketParser.parse(packet)))
        requireNotNull(metadata)
        assertTrue(metadata.fin)
        assertTrue(metadata.rst)
        assertFalse(metadata.syn)
        assertFalse(metadata.ack)
    }

    @Test
    fun `rejects truncated and invalid TCP headers`() {
        val truncated = tcpPacket().copyOf(39).also { setUnsignedShort(it, 2, it.size) }
        val invalidDataOffset = tcpPacket().also { it[32] = 0x40 }
        val oversizedDataOffset = tcpPacket().also { it[32] = 0x60 }
        assertNull(TcpPacketParser.parse(truncated, requireNotNull(IPv4PacketParser.parse(truncated))))
        assertNull(TcpPacketParser.parse(invalidDataOffset, requireNotNull(IPv4PacketParser.parse(invalidDataOffset))))
        assertNull(TcpPacketParser.parse(oversizedDataOffset, requireNotNull(IPv4PacketParser.parse(oversizedDataOffset))))
    }

    @Test
    fun `parses UDP ports and length`() {
        val packet = udpPacket(sourcePort = 53, destinationPort = 60_000, udpLength = 8)
        val metadata = UdpPacketParser.parse(packet, requireNotNull(IPv4PacketParser.parse(packet)))
        requireNotNull(metadata)
        assertEquals(53, metadata.sourcePort)
        assertEquals(60_000, metadata.destinationPort)
        assertEquals(8, metadata.length)
    }

    @Test
    fun `rejects truncated UDP header`() {
        val packet = udpPacket().copyOf(27).also { setUnsignedShort(it, 2, it.size) }
        assertNull(UdpPacketParser.parse(packet, requireNotNull(IPv4PacketParser.parse(packet))))
    }

    @Test
    fun `rejects invalid UDP lengths`() {
        val tooShort = udpPacket(udpLength = 7)
        val longerThanPacket = udpPacket(udpLength = 9)
        assertNull(UdpPacketParser.parse(tooShort, requireNotNull(IPv4PacketParser.parse(tooShort))))
        assertNull(UdpPacketParser.parse(longerThanPacket, requireNotNull(IPv4PacketParser.parse(longerThanPacket))))
    }

    @Test
    fun `first fragments can expose transport headers`() {
        val tcp = tcpPacket(fragmentation = 0x2000)
        val udp = udpPacket(udpLength = 40, fragmentation = 0x2000)
        assertEquals(1_000, TcpPacketParser.parse(tcp, requireNotNull(IPv4PacketParser.parse(tcp)))?.sourcePort)
        assertEquals(40, UdpPacketParser.parse(udp, requireNotNull(IPv4PacketParser.parse(udp)))?.length)
    }

    @Test
    fun `non-first fragments do not expose transport metadata`() {
        val tcp = tcpPacket(fragmentation = 1)
        val udp = udpPacket(fragmentation = 1)
        assertNull(TcpPacketParser.parse(tcp, requireNotNull(IPv4PacketParser.parse(tcp))))
        assertNull(UdpPacketParser.parse(udp, requireNotNull(IPv4PacketParser.parse(udp))))
    }

    private fun tcpPacket(
        sourcePort: Int = 1_000,
        destinationPort: Int = 2_000,
        headerLength: Int = 20,
        fragmentation: Int = 0,
    ): ByteArray = ipv4Packet(protocol = 6, transportLength = headerLength, fragmentation = fragmentation).also {
        setUnsignedShort(it, 20, sourcePort)
        setUnsignedShort(it, 22, destinationPort)
        it[32] = ((headerLength / 4) shl 4).toByte()
    }

    private fun udpPacket(
        sourcePort: Int = 1_000,
        destinationPort: Int = 2_000,
        udpLength: Int = 8,
        fragmentation: Int = 0,
    ): ByteArray = ipv4Packet(protocol = 17, transportLength = 8, fragmentation = fragmentation).also {
        setUnsignedShort(it, 20, sourcePort)
        setUnsignedShort(it, 22, destinationPort)
        setUnsignedShort(it, 24, udpLength)
    }

    private fun ipv4Packet(
        protocol: Int,
        transportLength: Int,
        fragmentation: Int,
    ): ByteArray = ByteArray(20 + transportLength).also {
        it[0] = 0x45
        setUnsignedShort(it, 2, it.size)
        setUnsignedShort(it, 6, fragmentation)
        it[9] = protocol.toByte()
        byteArrayOf(192.toByte(), 0, 2, 1).copyInto(it, 12)
        byteArrayOf(198.toByte(), 51, 100, 2).copyInto(it, 16)
    }

    private fun setUnsignedShort(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value ushr 8).toByte()
        packet[offset + 1] = value.toByte()
    }
}
