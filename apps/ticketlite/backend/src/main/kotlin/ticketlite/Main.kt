package ticketlite

import kotlin.system.exitProcess
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ticketlite")

fun main() {
    // Crashes go through the logger too (one JSON line at ERROR with the stack trace), not as a
    // plain-text Java stack trace that the log search cannot parse.
    Thread.setDefaultUncaughtExceptionHandler { t, e -> log.error("uncaught exception in thread {}", t.name, e) }
    try {
        start()
    } catch (e: Throwable) {
        log.error("fatal: {}", e.message, e)
        exitProcess(1)
    }
}

private fun start() {
    val cfg = AppConfig.fromEnv()
    val db = Db(cfg.db, connectTimeoutMs = cfg.dbConnectTimeoutMs)
    val gate = RedisGate(cfg.redisUrl)
    log.info("starting role={} port={}", cfg.role, cfg.port)
    when (cfg.role) {
        "api" -> {
            db.migrate() // Flyway takes a DB lock, so parallel api replicas are safe
            val repo = Repo(db)
            repo.seedUsers()
            runApi(cfg, repo, gate)
        }
        "worker" -> runWorker(cfg, db, gate)
        else -> error("ROLE must be 'api' or 'worker', got '${cfg.role}'")
    }
}
