package ticketlite

import at.favre.lib.crypto.bcrypt.BCrypt
import java.sql.ResultSet
import java.util.UUID

data class UserRow(val id: Long, val username: String, val displayName: String, val passwordHash: String)

/**
 * Every query says which consistency it needs (docs/design/2026-11-25-read-routing.md):
 *   reads.read(null)     eventual: the replica is fine (lag-limited)
 *   reads.read(ctx)      read-your-writes + monotonic for this user (LSN token)
 *   reads.linearizable   part of a sale decision: the primary, the latest value
 *   reads.write(ctx)     the primary; afterwards the user's token covers the write
 */
class Repo(private val db: Db, private val reads: ReadRouter = ReadRouter(db, null)) {

    // eventual: accounts are seeded, there is no sign-up
    fun findUser(username: String): UserRow? = reads.read(null) { c ->
        c.queryOne("select id, username, display_name, password_hash from users where username = ?", username) {
            UserRow(it.getLong(1), it.getString(2), it.getString(3), it.getString(4))
        }
    }

    // eventual (anonymous) or read-your-writes; `available` is overridden by the Redis gate anyway
    fun concerts(ctx: ReadCtx? = null): List<ConcertDto> = reads.read(ctx) { c ->
        c.queryList("$CONCERT_SELECT order by hot desc, starts_at", map = ::toConcert)
    }

    fun concert(id: Long): ConcertDto? = reads.linearizable { c ->
        c.queryOne("$CONCERT_SELECT where id = ?", id, map = ::toConcert)
    }

    fun available(concertId: Long): Int = reads.linearizable { c ->
        c.queryOne("select available from concerts where id = ?", concertId) { it.getInt(1) } ?: 0
    }

    /** For the /metrics gauge: monitoring reads must not load the primary (eventual is fine). */
    fun availableForMetrics(concertId: Long): Int = reads.read(null) { c ->
        c.queryOne("select available from concerts where id = ?", concertId) { it.getInt(1) } ?: 0
    }

    /** Tickets a user holds or is waiting for (pending + confirmed) for one concert. */
    fun userHeld(userId: Long, concertId: Long): Int = reads.linearizable { c ->
        c.queryOne(
            "select coalesce(sum(quantity), 0) from orders where user_id = ? and concert_id = ? and status in ('PENDING','CONFIRMED')",
            userId, concertId,
        ) { it.getInt(1) } ?: 0
    }

    /** concertId -> tickets held (pending + confirmed) for one user, in one query. */
    fun userHeldByConcert(userId: Long, ctx: ReadCtx): Map<Long, Int> = reads.read(ctx) { c ->
        c.queryList(
            "select concert_id, sum(quantity) from orders where user_id = ? and status in ('PENDING','CONFIRMED') group by concert_id",
            userId,
        ) { it.getLong(1) to it.getInt(2) }.toMap()
    }

    fun insertPending(id: UUID, userId: Long, concertId: Long, quantity: Int, ctx: ReadCtx) = reads.write(ctx) { c ->
        c.update(
            "insert into orders (id, user_id, concert_id, quantity, status) values (?, ?, ?, ?, ?)",
            id, userId, concertId, quantity, OrderStatus.PENDING,
        )
    }

    fun markFailed(id: UUID, reason: String, ctx: ReadCtx) = reads.write(ctx) { c ->
        c.update("update orders set status = ?, reason = ?, updated_at = now() where id = ?", OrderStatus.FAILED, reason, id)
    }

    fun order(id: UUID, userId: Long, ctx: ReadCtx): OrderDto? = reads.read(ctx) { c ->
        c.queryOne("$ORDER_SELECT where o.id = ? and o.user_id = ?", id, userId, map = ::toOrder)
    }

    fun orders(userId: Long, ctx: ReadCtx): List<OrderDto> = reads.read(ctx) { c ->
        c.queryList("$ORDER_SELECT where o.user_id = ? order by o.created_at desc limit 50", userId, map = ::toOrder)
    }

    /** Creates the sample accounts once. There is no registration. */
    fun seedUsers() {
        val samples = listOf(
            Triple("alice", "alice123", "Alice"),
            Triple("bob", "bob123", "Bob"),
            Triple("carol", "carol123", "Carol"),
        )
        db.tx { c ->
            for ((username, password, name) in samples) {
                val exists = c.queryOne("select 1 from users where username = ?", username) { true } ?: false
                if (!exists) {
                    val hash = BCrypt.withDefaults().hashToString(10, password.toCharArray())
                    c.update("insert into users (username, password_hash, display_name) values (?, ?, ?)", username, hash, name)
                }
            }
        }
    }

    companion object {
        private const val CONCERT_SELECT =
            "select id, title, artist, venue, starts_at, price_cents, total_tickets, available, hot from concerts"
        private const val ORDER_SELECT =
            "select o.id, o.concert_id, c.title, o.quantity, o.status, o.reason, o.created_at from orders o join concerts c on c.id = o.concert_id"

        fun toConcert(rs: ResultSet): ConcertDto {
            val hot = rs.getBoolean(9)
            return ConcertDto(
                id = rs.getLong(1),
                title = rs.getString(2),
                artist = rs.getString(3),
                venue = rs.getString(4),
                startsAt = rs.getTimestamp(5).toInstant().toString(),
                priceCents = rs.getInt(6),
                totalTickets = rs.getInt(7),
                available = rs.getInt(8),
                hot = hot,
                maxPerUser = maxPerUser(hot),
            )
        }

        fun toOrder(rs: ResultSet) = OrderDto(
            id = rs.getObject(1).toString(),
            concertId = rs.getLong(2),
            concertTitle = rs.getString(3),
            quantity = rs.getInt(4),
            status = rs.getString(5),
            reason = rs.getString(6),
            createdAt = rs.getTimestamp(7).toInstant().toString(),
        )
    }
}

fun verifyPassword(password: String, hash: String): Boolean =
    BCrypt.verifyer().verify(password.toCharArray(), hash).verified
