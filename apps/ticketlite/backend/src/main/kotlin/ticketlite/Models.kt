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
