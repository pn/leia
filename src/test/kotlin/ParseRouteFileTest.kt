package se.zensum.leia

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import se.zensum.leia.config.Format
import se.zensum.leia.config.TomlConfigProvider

class ParseRoutesTest {
    private val routes = TomlConfigProvider.fromPath("src/test/routes").getRoutes()

    @Test
    fun testSize() {
        assertEquals(6, routes.size)
    }

    @Test
    fun testTopic() {
        routes["/status/mail"]!!.apply{
            assertEquals("mail-status", topic)
        }

        routes["/status/sms"]!!.apply{
            assertEquals("sms-status", topic)
        }

        routes["/test"]!!.apply {
            assertEquals("test", topic)
        }

        routes["/auth"]!!.apply {
            assertEquals("test", topic)
        }
    }

    @Test
    fun testPath() {
        routes["/status/mail"]!!.apply{
            assertEquals("/status/mail", path)
        }

        routes["/status/sms"]!!.apply{
            assertEquals("/status/sms", path)
        }

        routes["/test"]!!.apply {
            assertEquals("/test", path)
        }

        routes["/auth"]!!.apply {
            assertEquals("/auth", path)
        }
    }

    @Test
    fun testVerify() {
        routes["/status/mail"]!!.apply{
            assertFalse(verify)
        }

        routes["/status/sms"]!!.apply{
            assertFalse(verify)
        }

        routes["/test"]!!.apply {
            assertFalse(verify)
        }

        routes["/auth"]!!.apply {
            assertTrue(verify)
        }
    }

    @Test
    fun testFormat() {
        routes["/status/mail"]!!.apply{
            assertEquals(Format.PROTOBUF, format)
        }

        routes["/status/sms"]!!.apply{
            assertEquals(Format.PROTOBUF, format)
        }

        routes["/test"]!!.apply {
            assertEquals(Format.PROTOBUF, format)
        }

        routes["/auth"]!!.apply {
            assertEquals(Format.RAW_BODY, format)
        }
    }

    @Test
    fun testExpectedMethods() {
        routes["/status/mail"]!!.apply{
            val expectedMethods = setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Head, HttpMethod.Get)
            assertEquals(expectedMethods, allowedMethods)
        }

        routes["/status/sms"]!!.apply{
            assertEquals(httpMethods.verbs, allowedMethods)
        }

        routes["/test"]!!.apply {
            assertEquals(httpMethods.verbs, allowedMethods)
        }

        routes["/auth"]!!.apply {
            val expectedMethods = setOf(HttpMethod.Post)
            assertEquals(expectedMethods, allowedMethods)
        }
    }

    @Test
    fun testRouteWithNoTopic() {
        assertThrows(NullPointerException::class.java) {
            val conf: String = """title = 'Config'
                [[routes]]
                    topic = 'test'
            """.trimMargin()
            TomlConfigProvider.fromString(conf).getRoutes()
        }
    }

    @Test
    fun testRouteWithNoPath() {
        assertThrows(NullPointerException::class.java) {
            val conf: String = """title = 'Config'
                [[routes]]
                    path = '/test'
            """.trimMargin()
            TomlConfigProvider.fromString(conf).getRoutes()
        }
    }

    @Test
    fun testCors() {
        assertEquals(listOf("*"), routes["/auth"]!!.corsHosts)
        assertEquals(emptyList<String>(), routes["/test"]!!.corsHosts)
    }

    @Test
    fun testResponseCodeDefaultImplicit() {
        assertEquals(HttpStatusCode.NoContent, routes["/test"]!!.response)
    }

    @Test
    fun testResponseCodeDefaultExplicit() {
        assertEquals(HttpStatusCode.NoContent, routes["/status/sms"]!!.response)
    }

    @Test
    fun testResponseCodeCustom() {
        assertEquals(HttpStatusCode.OK, routes["/status/mail"]!!.response)
    }
}