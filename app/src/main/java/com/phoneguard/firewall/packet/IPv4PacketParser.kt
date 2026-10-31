package com.phoneguard.firewall.packet

import java.net.InetAddress

object IPv4PacketParser {
    private const val MINIMUM_HEADER_LENGTH = 20
    private const val IPV4_VERSION = 4
    private const val FRAGMENT_OFFSET_MASK = 0x1fff
    private const val MORE_FRAGMENTS_MASK = 0x2000

    fun parse(packet: ByteArray): IPv4PacketMetadata? {
        if (packet.size < MINIMUM_HEADER_LENGTH) return null
        val versionAndIhl = packet[0].toInt() and 0xff
        val version = versionAndIhl ushr 4
        if (version != IPV4_VERSION) return null
        val headerLength = (versionAndIhl and 0x0f) * 4
        if (headerLength < MINIMUM_HEADER_LENGTH || headerLength > packet.size) return null
        val totalLength = unsignedShort(packet, 2)
        if (totalLength < headerLength || totalLength > packet.size) return null
        val fragmentation = unsignedShort(packet, 6)
        return IPv4PacketMetadata(
            version = version,
            headerLength = headerLength,
            totalLength = totalLength,
            identification = unsignedShort(packet, 4),
            protocol = packet[9].toInt() and 0xff,
            sourceAddress = addressFrom(packet, 12),
            destinationAddress = addressFrom(packet, 16),
            fragmentOffset = fragmentation and FRAGMENT_OFFSET_MASK,
            moreFragments = fragmentation and MORE_FRAGMENTS_MASK != 0,
        )
    }

    private fun unsignedShort(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xff) shl 8) or (packet[offset + 1].toInt() and 0xff)

    private fun addressFrom(packet: ByteArray, offset: Int): InetAddress =
        InetAddress.getByAddress(packet.copyOfRange(offset, offset + 4))
}
