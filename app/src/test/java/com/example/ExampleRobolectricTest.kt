package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.ProxyProtocol
import com.example.parser.XrayConfigParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("KirushaVPN", appName)
    }

    @Test
    fun `parse vless config link`() {
        val link = "vless://77a944e8-46fb-45d6-8488-8dc0c3c6ef12@104.16.132.229:443?security=reality&sni=speedtest.net&type=tcp#Frankfurt-Node"
        val servers = XrayConfigParser.parse(link)
        assertEquals(1, servers.size)
        val server = servers.first()
        assertEquals(ProxyProtocol.VLESS, server.protocol)
        assertEquals("104.16.132.229", server.host)
        assertEquals(443, server.port)
        assertEquals("Frankfurt-Node", server.name)
        assertEquals("speedtest.net", server.sni)
    }

    @Test
    fun `parse trojan config link`() {
        val link = "trojan://password123@1.1.1.1:443?security=tls&sni=cloudflare-dns.com#Trojan-US"
        val servers = XrayConfigParser.parse(link)
        assertEquals(1, servers.size)
        val server = servers.first()
        assertEquals(ProxyProtocol.TROJAN, server.protocol)
        assertEquals("1.1.1.1", server.host)
        assertEquals(443, server.port)
        assertEquals("Trojan-US", server.name)
    }

    @Test
    fun `parse happ and incy subscription with metadata`() {
        val sub = """
            #profile-title: Кирюша Випиэн
            #announce: Кирюша Випиэн бустит
            #profile-update-interval: 1
            vless://b6c958fa-02db-4a72-99b7-ee5ae2ba8633@titanjuniper.yournextidea.digital:443?encryption=none&security=tls&sni=titanjuniper.yournextidea.digital&fp=firefox&type=ws&path=%2Fmedia%2Fws%2F#%F0%9F%87%B5%F0%9F%87%B1%20%D0%9F%D0%BE%D0%BB%D1%8C%D1%88%D0%B0
            vless://b6c958fa-02db-4a72-99b7-ee5ae2ba8633@trcdn3.tcncdnn.digital:443?encryption=none&security=tls&sni=trcdn3.tcncdnn.digital&fp=firefox&type=ws&host=&path=%2Fmedia%2Fws%2F#%F0%9F%87%B9%F0%9F%87%B7%20%D0%A2%D1%83%D1%80%D1%86%D0%B8%D1%8F
            vless://b6c958fa-02db-4a72-99b7-ee5ae2ba8633@othertags.animwatch.biz:443?encryption=none&security=tls&sni=othertags.animwatch.biz&fp=firefox&type=ws&path=%2Fmedia%2Fws%2F#%F0%9F%87%A9%F0%9F%87%AA%20%D0%93%D0%B5%D1%80%D0%BC%D0%B0%D0%BD%D0%B8%D1%8F%20%D0%AE%D1%82%D1%83%D0%B1
            vless://b6c958fa-02db-4a72-99b7-ee5ae2ba8633@paneltimeout.yournextidea.digital:443?encryption=none&security=tls&sni=paneltimeout.yournextidea.digital&fp=firefox&type=ws&path=%2Fmedia%2Fws%2F#%F0%9F%87%B1%F0%9F%87%B9%20%D0%9B%D0%B8%D1%82%D0%B2%D0%B0%20%D0%AE%D1%82%D1%83%D0%B1
            hysteria2://b6c958fa-02db-4a72-99b7-ee5ae2ba8633@new.animwatch.biz:443/?sni=new.animwatch.biz&alpn=h3&fp=firefox#%F0%9F%87%B3%F0%9F%87%B1%20%D0%9D%D0%B8%D0%B4%D0%B5%D1%80%D0%BB%D0%B0%D0%BD%D0%B4%D1%8B%20(%D0%98%D0%93%D0%A0%D0%AB)
            vless://b6c958fa-02db-4a72-99b7-ee5ae2ba8633@packetquery.yournextidea.digital:443?encryption=none&security=tls&sni=packetquery.yournextidea.digital&fp=firefox&type=ws&path=%2Fmedia%2Fws%2F#%F0%9F%87%A8%F0%9F%87%AD%20%D0%A8%D0%B2%D0%B5%D0%B9%D1%86%D0%B0%D1%80%D0%B8%D1%8F
            vless://b6c958fa-02db-4a72-99b7-ee5ae2ba8633@insightatlas.yournextidea.digital:443?encryption=none&security=tls&sni=insightatlas.yournextidea.digital&fp=firefox&type=ws&path=%2Fmedia%2Fws%2F#%F0%9F%87%B3%F0%9F%87%B1%20%D0%9D%D0%B8%D0%B4%D0%B5%D1%80%D0%BB%D0%B0%D0%BD%D0%B4%D1%8B
        """.trimIndent()
        val parsed = XrayConfigParser.parseSubscription(sub)
        assertEquals("Кирюша Випиэн", parsed.title)
        assertEquals("Кирюша Випиэн бустит", parsed.announcement)
        assertEquals(7, parsed.servers.size)
        assertEquals("PL", parsed.servers[0].countryCode)
        assertEquals("TR", parsed.servers[1].countryCode)
        assertEquals("DE", parsed.servers[2].countryCode)
        assertEquals("LT", parsed.servers[3].countryCode)
        assertEquals("NL", parsed.servers[4].countryCode)
        assertEquals("CH", parsed.servers[5].countryCode)
        assertEquals("NL", parsed.servers[6].countryCode)
    }
}
