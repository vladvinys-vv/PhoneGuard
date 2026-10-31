package com.phoneguard.firewall.packet

object TcpPacketParser {
    private const val TCP_PROTOCOL = 6
    private const val MINIMUM_HEADER_LENGTH = 20
    private const val DATA_OFFSET_BYTE = 12
    private const val FLAGS_BYTE = 13
    private const val FIN_MASK = 0x01
    private const val SYN_MASK = 0x02
    private const val RST_MASK = 0x04
    private const val ACK_MASK = 0x10

    fun parse(packet: ByteArray): TcpMetadata? =
        IPv4PacketParser.parse(packet)?.let { parse(packet, it) }

    fun parse(packet: ByteArray, ipv4: IPv4PacketMetadata): TcpMetadata? {
        if (ipv4.protocol != TCP_PROTOCOL || !ipv4.canParseTransportHeader) return null
        val offset = ipv4.headerLength
        if (!hasBytes(ipv4, packet, offset, MINIMUM_HEADER_LENGTH)) return null
        val headerLength = ((packet[offset + DATA_OFFSET_BYTE].toInt() and 0xff) ushr 4) * 4
        if (headerLength < MINIMUM_HEADER_LENGTH || !hasBytes(ipv4, packet, offset, headerLength)) return null
        val flags = packet[offset + FLAGS_BYTE].toInt() and 0xff
        return TcpMetadata(
            sourcePort = unsignedShort(packet, offset),
            destinationPort = unsignedShort(packet, offset + 2),
            headerLength = headerLength,
            syn = flags and SYN_MASK != 0,
            ack = flags and ACK_MASK != 0,
            fin = flags and FIN_MASK != 0,
            rst = flags and RST_MASK != 0,
        )
    }
}

internal fun unsignedShort(packet: ByteArray, offset: Int): Int =
    ((packet[offset].toInt() and 0xff) shl 8) or (packet[offset + 1].toInt() and 0xff)

internal fun hasBytes(
    ipv4: IPv4PacketMetadata,
    packet: ByteArray,
    offset: Int,
    length: Int,
): Boolean = offset >= 0 && length >= 0 && offset <= ipv4.totalLength - length &&
    offset <= packet.size - length
