package ticketlite

import org.slf4j.LoggerFactory

fun main() {
    val log = LoggerFactory.getLogger("ticketlite")
    val cfg = AppConfig.fromEnv()
    val db = Db(cfg.db)
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
