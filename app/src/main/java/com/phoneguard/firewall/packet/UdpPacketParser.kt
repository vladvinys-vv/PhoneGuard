package com.phoneguard.firewall.packet

object UdpPacketParser {
    private const val UDP_PROTOCOL = 17
    private const val HEADER_LENGTH = 8

    fun parse(packet: ByteArray): UdpMetadata? =
        IPv4PacketParser.parse(packet)?.let { parse(packet, it) }

    fun parse(packet: ByteArray, ipv4: IPv4PacketMetadata): UdpMetadata? {
        if (ipv4.protocol != UDP_PROTOCOL || !ipv4.canParseTransportHeader) return null
        val offset = ipv4.headerLength
        if (!hasBytes(ipv4, packet, offset, HEADER_LENGTH)) return null
        val udpLength = unsignedShort(packet, offset + 4)
        if (udpLength < HEADER_LENGTH) return null
        if (!ipv4.moreFragments && !hasBytes(ipv4, packet, offset, udpLength)) return null
        return UdpMetadata(
            sourcePort = unsignedShort(packet, offset),
            destinationPort = unsignedShort(packet, offset + 2),
            length = udpLength,
        )
    }
}
