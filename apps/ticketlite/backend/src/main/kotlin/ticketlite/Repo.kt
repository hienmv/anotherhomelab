package ticketlite

import at.favre.lib.crypto.bcrypt.BCrypt
import java.sql.ResultSet
import java.util.UUID

data class UserRow(val id: Long, val username: String, val displayName: String, val passwordHash: String)

class Repo(private val db: Db) {

    fun findUser(username: String): UserRow? = db.read { c ->
        c.queryOne("select id, username, display_name, password_hash from users where username = ?", username) {
            UserRow(it.getLong(1), it.getString(2), it.getString(3), it.getString(4))
        }
    }

    fun concerts(): List<ConcertDto> = db.read { c ->
        c.queryList("$CONCERT_SELECT order by hot desc, starts_at", map = ::toConcert)
    }

    fun concert(id: Long): ConcertDto? = db.read { c ->
        c.queryOne("$CONCERT_SELECT where id = ?", id, map = ::toConcert)
    }

    fun available(concertId: Long): Int = db.read { c ->
        c.queryOne("select available from concerts where id = ?", concertId) { it.getInt(1) } ?: 0
    }

    /** Tickets a user holds or is waiting for (pending + confirmed) for one concert. */
    fun userHeld(userId: Long, concertId: Long): Int = db.read { c ->
        c.queryOne(
            "select coalesce(sum(quantity), 0) from orders where user_id = ? and concert_id = ? and status in ('PENDING','CONFIRMED')",
            userId, concertId,
        ) { it.getInt(1) } ?: 0
    }

    /** concertId -> tickets held (pending + confirmed) for one user, in one query. */
    fun userHeldByConcert(userId: Long): Map<Long, Int> = db.read { c ->
        c.queryList(
            "select concert_id, sum(quantity) from orders where user_id = ? and status in ('PENDING','CONFIRMED') group by concert_id",
            userId,
        ) { it.getLong(1) to it.getInt(2) }.toMap()
    }

    fun insertPending(id: UUID, userId: Long, concertId: Long, quantity: Int) = db.tx { c ->
        c.update(
            "insert into orders (id, user_id, concert_id, quantity, status) values (?, ?, ?, ?, ?)",
            id, userId, concertId, quantity, OrderStatus.PENDING,
        )
    }

    fun markFailed(id: UUID, reason: String) = db.tx { c ->
        c.update("update orders set status = ?, reason = ?, updated_at = now() where id = ?", OrderStatus.FAILED, reason, id)
    }

    fun order(id: UUID, userId: Long): OrderDto? = db.read { c ->
        c.queryOne("$ORDER_SELECT where o.id = ? and o.user_id = ?", id, userId, map = ::toOrder)
    }

    fun orders(userId: Long): List<OrderDto> = db.read { c ->
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
