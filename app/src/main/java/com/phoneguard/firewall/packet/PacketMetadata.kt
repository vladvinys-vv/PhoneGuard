package com.phoneguard.firewall.packet

import java.net.InetAddress

data class IPv4PacketMetadata(
    val version: Int,
    val headerLength: Int,
    val totalLength: Int,
    val identification: Int,
    val protocol: Int,
    val sourceAddress: InetAddress,
    val destinationAddress: InetAddress,
    val fragmentOffset: Int,
    val moreFragments: Boolean,
) {
    val isFirstFragment: Boolean get() = fragmentOffset == 0
    val isNonFirstFragment: Boolean get() = fragmentOffset != 0
    val isFragmented: Boolean get() = moreFragments || fragmentOffset != 0
    val canParseTransportHeader: Boolean get() = !isNonFirstFragment
}

data class TcpMetadata(
    val sourcePort: Int,
    val destinationPort: Int,
    val headerLength: Int,
    val syn: Boolean,
    val ack: Boolean,
    val fin: Boolean,
    val rst: Boolean,
)

data class UdpMetadata(
    val sourcePort: Int,
    val destinationPort: Int,
    val length: Int,
)
