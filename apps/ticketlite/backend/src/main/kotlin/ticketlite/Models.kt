package ticketlite

import kotlinx.serialization.Serializable

@Serializable
data class UserSession(val userId: Long, val username: String, val displayName: String)

@Serializable
data class UserDto(val id: Long, val username: String, val displayName: String)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class ConcertDto(
    val id: Long,
    val title: String,
    val artist: String,
    val venue: String,
    val startsAt: String,
    val priceCents: Int,
    val totalTickets: Int,
    val available: Int,
    val hot: Boolean,
    val maxPerUser: Int,
    /** Tickets the logged-in user holds or waits for (pending + confirmed); null when anonymous. */
    val userHeld: Int? = null,
)

@Serializable
data class OrderRequest(val concertId: Long, val quantity: Int)

@Serializable
data class OrderDto(
    val id: String,
    val concertId: Long,
    val concertTitle: String,
    val quantity: Int,
    val status: String,
    val reason: String? = null,
    val createdAt: String,
)

@Serializable
data class ErrorDto(val error: String, val message: String? = null)

object OrderStatus {
    const val PENDING = "PENDING"
    const val CONFIRMED = "CONFIRMED"
    const val FAILED = "FAILED"
}

/**
 * Read-your-writes token (docs/design/2026-11-25-read-routing.md): the newest WAL position (LSN) this
 * browser has seen. Its OWN cookie (TL_RYW), deliberately not a field of [UserSession]: Ktor's session
 * decoder rejects unknown keys, so a new field would make pods of the previous version fail to read
 * the session during a rolling update (the user would look logged out). Pods that don't know TL_RYW
 * simply ignore it, in both directions.
 */
@Serializable
data class ReadToken(val minLsn: String)
