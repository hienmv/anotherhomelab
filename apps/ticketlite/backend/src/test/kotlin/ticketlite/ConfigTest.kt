package ticketlite

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ConfigTest {
    @Test
    fun `parses CNPG style database url`() {
        val cfg = parseDatabaseUrl("postgresql://app:s3cr%40t@pg-rw.data.svc.cluster.local:5432/app")
        assertEquals("jdbc:postgresql://pg-rw.data.svc.cluster.local:5432/app", cfg.jdbcUrl)
        assertEquals("app", cfg.user)
        assertEquals("s3cr@t", cfg.password)
    }

    @Test
    fun `defaults port and accepts postgres scheme`() {
        val cfg = parseDatabaseUrl("postgres://u:p@db/tickets?sslmode=disable")
        assertEquals("jdbc:postgresql://db:5432/tickets?sslmode=disable", cfg.jdbcUrl)
    }

    @Test
    fun `hot concerts have a stricter per-user limit`() {
        assertEquals(2, maxPerUser(hot = true))
        assertEquals(6, maxPerUser(hot = false))
    }

    @Test
    fun `decodes hex session secret`() {
        assertContentEquals(byteArrayOf(0x0a, 0xff.toByte()), hexToBytes("0aff"))
    }
}
