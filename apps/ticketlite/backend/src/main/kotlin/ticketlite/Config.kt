package ticketlite

import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom
import org.slf4j.LoggerFactory

data class DbConfig(val jdbcUrl: String, val user: String, val password: String)

data class AppConfig(
    val role: String,
    val port: Int,
    val db: DbConfig,
    val redisUrl: String,
    val sessionSecret: ByteArray,
    val cookieSecure: Boolean,
    val workerDelayMs: Long,
) {
    companion object {
        private val log = LoggerFactory.getLogger(AppConfig::class.java)

        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            val secretHex = env["SESSION_SECRET"]
            val secret = if (secretHex.isNullOrBlank()) {
                log.warn("SESSION_SECRET not set: using a random key (sessions reset on restart)")
                ByteArray(32).also { SecureRandom().nextBytes(it) }
            } else {
                hexToBytes(secretHex)
            }
            return AppConfig(
                role = env["ROLE"] ?: "api",
                port = env["PORT"]?.toInt() ?: 8080,
                db = parseDatabaseUrl(env["DATABASE_URL"] ?: "postgresql://app:app@localhost:5432/app"),
                redisUrl = env["REDIS_URL"] ?: "redis://localhost:6379/0",
                sessionSecret = secret,
                cookieSecure = env["COOKIE_SECURE"]?.toBoolean() ?: false,
                workerDelayMs = env["WORKER_DELAY_MS"]?.toLong() ?: 200,
            )
        }
    }
}

/** Converts `postgresql://user:pass@host:port/db` (as in CNPG's `uri`) into JDBC settings. */
fun parseDatabaseUrl(url: String): DbConfig {
    val uri = URI(url.replaceFirst(Regex("^postgres(ql)?://"), "postgresql://"))
    val userInfo = uri.rawUserInfo?.split(":", limit = 2) ?: emptyList()
    val user = userInfo.getOrNull(0)?.let { URLDecoder.decode(it, Charsets.UTF_8) } ?: ""
    val password = userInfo.getOrNull(1)?.let { URLDecoder.decode(it, Charsets.UTF_8) } ?: ""
    val port = if (uri.port == -1) 5432 else uri.port
    val query = uri.rawQuery?.let { "?$it" } ?: ""
    return DbConfig("jdbc:postgresql://${uri.host}:$port${uri.rawPath}$query", user, password)
}

fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex string must have even length" }
    return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/** Max tickets one user may hold for a concert. Hot concerts are stricter to share demand fairly. */
fun maxPerUser(hot: Boolean): Int = if (hot) 2 else 6
