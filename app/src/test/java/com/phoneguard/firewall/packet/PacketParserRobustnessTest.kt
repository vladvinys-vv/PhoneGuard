package com.phoneguard.firewall.packet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Разбор пакетов из TUN-интерфейса не имеет права бросать исключения.
 *
 * Через VPN идёт произвольный пользовательский трафик, а также то, что присылают недоверенные
 * приложения. Любое исключение здесь — падение `PhoneGuardVpnService` и отключение firewall,
 * то есть защита исчезает ровно тогда, когда нужна. Поэтому: мусор → `null`, а не crash.
 */
class PacketParserRobustnessTest {

    private fun packet(
        headerLength: Int = 20,
        totalLength: Int? = null,
        protocol: Int = 6,
        fragmentOffset: Int = 0,
        moreFragments: Boolean = false,
        payload: ByteArray = ByteArray(0),
        bufferExtra: Int = 0,
        bufferSize: Int = 0,
    ): ByteArray {
        val total = totalLength ?: (headerLength + payload.size)
        val size = if (bufferSize > 0) bufferSize else maxOf(total + bufferExtra, 20)
        val bytes = ByteArray(size)
        bytes[0] = ((4 shl 4) or (headerLength / 4)).toByte()
        bytes[2] = (total shr 8).toByte()
        bytes[3] = total.toByte()
        bytes[6] = ((fragmentOffset shr 8) or (if (moreFragments) 0x20 else 0)).toByte()
        bytes[7] = fragmentOffset.toByte()
        bytes[9] = protocol.toByte()
        bytes[12] = 192.toByte(); bytes[13] = 0; bytes[14] = 2; bytes[15] = 1
        bytes[16] = 198.toByte(); bytes[17] = 51; bytes[18] = 100; bytes[19] = 2
        payload.copyInto(bytes, headerLength)
        return bytes
    }

    private fun tcpSegment(sourcePort: Int, destinationPort: Int, dataOffsetWords: Int, flags: Int): ByteArray {
        val segment = ByteArray(20)
        segment[0] = (sourcePort shr 8).toByte(); segment[1] = sourcePort.toByte()
        segment[2] = (destinationPort shr 8).toByte(); segment[3] = destinationPort.toByte()
        segment[12] = (dataOffsetWords shl 4).toByte()
        segment[13] = flags.toByte()
        return segment
    }

    @Test
    fun `random bytes never throw and never produce inconsistent metadata`() {
        val random = Random(20261101)
        for (length in 0..64) {
            repeat(50) {
                val bytes = ByteArray(length).also { random.nextBytes(it) }
                val metadata = IPv4PacketParser.parse(bytes)
                if (metadata == null) return@repeat
                assertEquals(4, metadata.version)
                assertTrue(metadata.headerLength in 20..60)
                assertTrue(metadata.headerLength <= metadata.totalLength)
                assertTrue(metadata.totalLength <= bytes.size)
                assertEquals(4, metadata.sourceAddress.address.size)
                assertTrue(metadata.fragmentOffset in 0..0x1fff)
            }
        }
    }

    @Test
    fun `random bytes never throw in transport parsers`() {
        val random = Random(7)
        repeat(500) {
            val bytes = ByteArray(random.nextInt(0, 80)).also { random.nextBytes(it) }
            TcpPacketParser.parse(bytes)
            UdpPacketParser.parse(bytes)
        }
    }

    @Test
    fun `truncated header is rejected`() {
        for (length in 0..19) {
            assertNull("длина $length не может содержать заголовок IPv4", IPv4PacketParser.parse(ByteArray(length)))
        }
    }

    @Test
    fun `non IPv4 version is rejected`() {
        val bytes = packet()
        bytes[0] = ((6 shl 4) or 5).toByte() // IPv6
        assertNull(IPv4PacketParser.parse(bytes))
    }

    @Test
    fun `header length below minimum is rejected`() {
        val bytes = packet()
        bytes[0] = ((4 shl 4) or 4).toByte() // IHL = 4 -> 16 байт
        assertNull(IPv4PacketParser.parse(bytes))
    }

    @Test
    fun `header length larger than packet is rejected`() {
        val bytes = packet(headerLength = 20)
        bytes[0] = ((4 shl 4) or 15).toByte() // IHL = 15 -> 60 байт при пакете в 20
        assertNull(IPv4PacketParser.parse(bytes))
    }

    @Test
    fun `total length smaller than header is rejected`() {
        assertNull(IPv4PacketParser.parse(packet(headerLength = 20, totalLength = 10)))
    }

    @Test
    fun `total length larger than buffer is rejected`() {
        val truncated = packet(headerLength = 20, totalLength = 60, bufferSize = 40)
        assertNull(IPv4PacketParser.parse(truncated))
    }

    @Test
    fun `buffer larger than declared length is accepted`() {
        // Чтение из TUN может вернуть буфер больше пакета — важен declared length.
        val metadata = IPv4PacketParser.parse(packet(bufferExtra = 16))
        assertNotNull(metadata)
        assertEquals(20, metadata!!.totalLength)
    }

    @Test
    fun `tcp segment is parsed with ports and flags`() {
        val segment = tcpSegment(sourcePort = 443, destinationPort = 51000, dataOffsetWords = 5, flags = 0x12)
        val metadata = TcpPacketParser.parse(packet(payload = segment))
        assertNotNull(metadata)
        assertEquals(443, metadata!!.sourcePort)
        assertEquals(51000, metadata.destinationPort)
        assertEquals(20, metadata.headerLength)
        assertTrue(metadata.syn)
        assertTrue(metadata.ack)
        assertTrue(!metadata.fin && !metadata.rst)
    }

    @Test
    fun `tcp segment with data offset below minimum is rejected`() {
        val segment = tcpSegment(1, 2, dataOffsetWords = 4, flags = 0x02)
        assertNull(TcpPacketParser.parse(packet(payload = segment)))
    }

    @Test
    fun `tcp segment with data offset beyond packet is rejected`() {
        val segment = tcpSegment(1, 2, dataOffsetWords = 15, flags = 0x02)
        assertNull(TcpPacketParser.parse(packet(payload = segment)))
    }

    @Test
    fun `tcp is not parsed for non first fragment`() {
        val segment = tcpSegment(1, 2, 5, 0x02)
        assertNull(TcpPacketParser.parse(packet(fragmentOffset = 185, payload = segment)))
    }

    @Test
    fun `tcp is not parsed for foreign protocol`() {
        val segment = tcpSegment(1, 2, 5, 0x02)
        assertNull(TcpPacketParser.parse(packet(protocol = 17, payload = segment)))
    }

    @Test
    fun `udp datagram is parsed with ports and length`() {
        val datagram = ByteArray(8).apply {
            this[0] = 0; this[1] = 53
            this[2] = 0xc7.toByte(); this[3] = 0x38.toByte()
            this[4] = 0; this[5] = 8
        }
        val metadata = UdpPacketParser.parse(packet(protocol = 17, payload = datagram))
        assertNotNull(metadata)
        assertEquals(53, metadata!!.sourcePort)
        assertEquals(51000, metadata.destinationPort)
        assertEquals(8, metadata.length)
    }

    @Test
    fun `udp length below header size is rejected`() {
        val datagram = ByteArray(8).apply { this[4] = 0; this[5] = 7 }
        assertNull(UdpPacketParser.parse(packet(protocol = 17, payload = datagram)))
    }

    @Test
    fun `udp length beyond the packet is rejected when not fragmented`() {
        val datagram = ByteArray(8).apply { this[4] = 0; this[5] = 40 }
        assertNull(UdpPacketParser.parse(packet(protocol = 17, payload = datagram)))
    }

    @Test
    fun `udp is not parsed for foreign protocol`() {
        val datagram = ByteArray(8)
        assertNull(UdpPacketParser.parse(packet(protocol = 6, payload = datagram)))
    }

    @Test
    fun `half filled packet with matching length is accepted`() {
        // Нулевой payload и корректные длины — валидный пустой пакет, а не мусор.
        val metadata = IPv4PacketParser.parse(packet(payload = ByteArray(0)))
        assertNotNull(metadata)
        assertEquals(20, metadata!!.totalLength)
    }
}
