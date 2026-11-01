package com.phoneguard.threatintel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Разбор хоста из ссылки — вход в проверку репутации домена.
 *
 * От этой функции зависит, попадёт ли домен из SMS в `hostsToCheck` и будет ли он сверен с
 * URLhaus/OpenPhish. Ошибка тихая: `null` означает «хост не найден», проверки нет и находки нет —
 * ровно то, чего добиваются мошенники.
 */
class HostExtractorTest {

    @Test
    fun `plain https url gives host`() {
        assertEquals("evil.example", HostExtractor.extractHost("https://evil.example/path?q=1#frag"))
        assertEquals("evil.example", HostExtractor.extractHost("http://evil.example"))
    }

    @Test
    fun `scheme is optional and case insensitive`() {
        assertEquals("evil.example", HostExtractor.extractHost("evil.example/phish"))
        assertEquals("evil.example", HostExtractor.extractHost("www.evil.example"))
        // Регресс: раньше проверка была `startsWith("http://")` без учёта регистра,
        // поэтому «HTTPS://…» склеивался с префиксом и хост не находился вовсе.
        assertEquals("evil.example", HostExtractor.extractHost("HTTPS://Evil.Example/Track"))
        assertEquals("evil.example", HostExtractor.extractHost("//evil.example/x"))
    }

    @Test
    fun `user info port and trailing dot are dropped`() {
        assertEquals("evil.example", HostExtractor.extractHost("http://user:pass@evil.example/x"))
        assertEquals("evil.example", HostExtractor.extractHost("http://evil.example:8080/x"))
        assertEquals("evil.example", HostExtractor.extractHost("http://evil.example./x"))
    }

    @Test
    fun `host is lowercased`() {
        assertEquals("evil.example", HostExtractor.extractHost("https://EVIL.ExAmPlE/Path"))
    }

    @Test
    fun `punctuation around a link is trimmed`() {
        assertEquals("evil.example", HostExtractor.extractHost("\"https://evil.example\","))
        assertEquals("evil.example", HostExtractor.extractHost("(evil.example)"))
        assertEquals("evil.example", HostExtractor.extractHost("  https://evil.example;  "))
    }

    @Test
    fun `single label and ipv6 are not domains`() {
        assertNull(HostExtractor.extractHost("http://localhost:8080/x"))
        assertNull(HostExtractor.extractHost("http://intranet/x"))
        assertNull(HostExtractor.extractHost("http://[::1]:8080/x"))
        assertNull(HostExtractor.extractHost("http://[2001:db8::1]/x"))
    }

    @Test
    fun `reserved example domains are ignored`() {
        assertNull(HostExtractor.extractHost("https://example.com/x"))
        assertNull(HostExtractor.extractHost("example.org"))
        assertNull(HostExtractor.extractHost("https://example.net:443/"))
    }

    @Test
    fun `empty and garbage input gives null`() {
        assertNull(HostExtractor.extractHost(""))
        assertNull(HostExtractor.extractHost("   "))
        assertNull(HostExtractor.extractHost("https://"))
        assertNull(HostExtractor.extractHost("https:///path"))
        assertNull(HostExtractor.extractHost("not a link"))
        assertNull(HostExtractor.extractHost("юникод без домена"))
    }

    @Test
    fun `ipv4 host is kept as is`() {
        // Ленты работают с именами, но IPv4 — валидный хост, и раньше он уходил в проверку.
        assertEquals("192.168.1.10", HostExtractor.extractHost("http://192.168.1.10:8080/admin"))
    }

    @Test
    fun `idn host is kept lowercased`() {
        assertEquals("сбербанк.рф", HostExtractor.extractHost("https://Сбербанк.РФ/вход"))
        assertEquals("xn--80a1acny.xn--p1ai", HostExtractor.extractHost("https://xn--80a1acny.xn--p1ai/login"))
    }

    @Test
    fun `unusual characters in host are rejected`() {
        assertNull(HostExtractor.extractHost("https://evil.example%2F.com/x"))
        assertNull(HostExtractor.extractHost("https://ev il.example/x"))
    }
}
