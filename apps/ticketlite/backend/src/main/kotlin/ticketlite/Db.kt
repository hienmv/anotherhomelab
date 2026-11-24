package ticketlite

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import org.flywaydb.core.Flyway

class Db(cfg: DbConfig, poolSize: Int = 5, connectTimeoutMs: Long = 5_000) {
    val ds = HikariDataSource(HikariConfig().apply {
        jdbcUrl = cfg.jdbcUrl
        username = cfg.user
        password = cfg.password
        maximumPoolSize = poolSize
        poolName = "ticketlite"
        // Hikari's default is 30 s: during a failover every request hung 30 s, then failed. 5 s: the
        // user gets a quick 503 "retrying" instead (docs/design/2026-11-24-graceful-db-outage.md).
        connectionTimeout = connectTimeoutMs
    })

    fun migrate() {
        Flyway.configure().dataSource(ds).load().migrate()
    }

    fun <T> tx(block: (Connection) -> T): T = ds.connection.use { c ->
        c.autoCommit = false
        try {
            block(c).also { c.commit() }
        } catch (e: Throwable) {
            c.rollback()
            throw e
        }
    }

    fun <T> read(block: (Connection) -> T): T = ds.connection.use(block)
}

fun Connection.stmt(sql: String, vararg params: Any?): PreparedStatement =
    prepareStatement(sql).apply { params.forEachIndexed { i, p -> setObject(i + 1, p) } }

fun <T> Connection.queryList(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
    stmt(sql, *params).use { st ->
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

fun <T> Connection.queryOne(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? =
    queryList(sql, *params, map = map).firstOrNull()

fun Connection.update(sql: String, vararg params: Any?): Int = stmt(sql, *params).use { it.executeUpdate() }
