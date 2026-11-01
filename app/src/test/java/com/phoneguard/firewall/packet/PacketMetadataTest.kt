package com.phoneguard.firewall.packet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Флаги фрагментации IPv4.
 *
 * От них зависит, можно ли читать TCP/UDP-заголовок: у второго и последующих фрагментов
 * в начале идут данные, а не порты. Если это перепутать, firewall применит правило не к тому
 * соединению — порт из «данных» будет случайным числом.
 */
class PacketMetadataTest {
    private val address: InetAddress = InetAddress.getByName("192.0.2.1")

    private fun metadata(fragmentOffset: Int, moreFragments: Boolean) = IPv4PacketMetadata(
        version = 4,
        headerLength = 20,
        totalLength = 40,
        identification = 1,
        protocol = 6,
        sourceAddress = address,
        destinationAddress = address,
        fragmentOffset = fragmentOffset,
        moreFragments = moreFragments,
    )

    @Test
    fun `unfragmented packet is not fragmented and allows transport parsing`() {
        val packet = metadata(fragmentOffset = 0, moreFragments = false)
        assertFalse(packet.isFragmented)
        assertTrue(packet.isFirstFragment)
        assertFalse(packet.isNonFirstFragment)
        assertTrue(packet.canParseTransportHeader)
    }

    @Test
    fun `first fragment with more fragments flag is fragmented but still parseable`() {
        val packet = metadata(fragmentOffset = 0, moreFragments = true)
        assertTrue(packet.isFragmented)
        assertTrue(packet.isFirstFragment)
        assertTrue(packet.canParseTransportHeader)
    }

    @Test
    fun `non first fragment is fragmented and not parseable`() {
        val packet = metadata(fragmentOffset = 185, moreFragments = false)
        assertTrue(packet.isFragmented)
        assertFalse(packet.isFirstFragment)
        assertTrue(packet.isNonFirstFragment)
        assertFalse(packet.canParseTransportHeader)
    }

    @Test
    fun `last fragment of a fragmented datagram is not parseable`() {
        val packet = metadata(fragmentOffset = 370, moreFragments = false)
        assertTrue(packet.isFragmented)
        assertFalse(packet.canParseTransportHeader)
    }

    @Test
    fun `first fragment helper is based on offset only`() {
        // moreFragments не влияет на «первый ли это фрагмент»: важно только смещение.
        assertTrue(metadata(fragmentOffset = 0, moreFragments = true).isFirstFragment)
        assertFalse(metadata(fragmentOffset = 1, moreFragments = true).isFirstFragment)
    }
}
