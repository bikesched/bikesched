// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.kotlin.shop

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.util.UUID
import javax.money.MonetaryAmount
import org.bikesched.Bikesched
import org.bikesched.kotlin.createJob
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory

/**
 * REST API routes for the shop example.
 *
 * - POST /api/orders: Create a new order with items
 * - GET /api/orders/{orderId}/payment-options: Get available payment options for an order
 * - POST /api/orders/{orderId}/pay: Initiate payment for an order
 */
fun Route.shopRoutes(bikesched: Bikesched) {
  val log = LoggerFactory.getLogger("ShopApi")

  route("/api") {
    // List available items
    get("/items") {
      call.respond(FixtureItems.items)
    }

    // Create a new order
    post("/orders") {
      val orderRequest = call.receive<Order>()
      val orderId = orderRequest.orderId

      log.info("Creating order {} with {} items", orderId, orderRequest.items.size)

      // Validate items exist
      val itemIds = orderRequest.items.map { it.itemId }.toSet()
      val invalidItems = itemIds.filter { FixtureItems.find(it) == null }
      if (invalidItems.isNotEmpty()) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid item IDs: $invalidItems"))
        return@post
      }

      // Calculate total amount
      val totalAmount =
        orderRequest.items.fold("0".euro()) { sum, item ->
          val shopItem =
            FixtureItems.find(item.itemId)
              ?: throw IllegalStateException("Item ${item.itemId} not found")
          sum.add(shopItem.price.multiply(item.amount.toLong()))
        }


      // Store the order and schedule the job in the same Exposed transaction
      transaction {
        Orders.insert {
          it[Orders.id] = UUID.fromString(orderId)
          it[Orders.state] = OrderState.RESOLVING_PAYMENT_OPTIONS.name
          it[Orders.totalAmount] = totalAmount.toBigDecimal()
          it[Orders.currency] = totalAmount.currencyCode()
        }

        orderRequest.items.forEach { orderItem ->
          OrderItems.insert {
            it[OrderItems.orderId] = UUID.fromString(orderId)
            it[OrderItems.itemId] = orderItem.itemId
            it[OrderItems.amount] = orderItem.amount
          }
        }

        bikesched.createJob(
          PROCESS_ORDER,
          ProcessOrderPayload(orderId = orderId),
          connection = connection.connection as Connection,
        )
      }

      log.info("Order {} stored with total amount {}", orderId, totalAmount)

      call.respond(
        HttpStatusCode.Created,
        OrderResponse(orderId, OrderState.RESOLVING_PAYMENT_OPTIONS.name, totalAmount),
      )
    }

    // Get payment options for an order
    get("/orders/{orderId}/payment-options") {
      val orderId = call.pathParameters["orderId"]
        ?: throw IllegalArgumentException("Order ID is required")

      val order = Queries.findOrder(UUID.fromString(orderId))
      if (order == null) {
        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Order not found"))
        return@get
      }

      val (_, totalAmount) = order
      val paymentOptions = Queries.findPaymentOptions(UUID.fromString(orderId))
      call.respond(PaymentOptionsResponse(orderId, totalAmount, paymentOptions))
    }

    // Initiate payment for an order
    post("/orders/{orderId}/pay") {
      val orderId = call.pathParameters["orderId"]
        ?: throw IllegalArgumentException("Order ID is required")
      val paymentRequest = call.receive<PaymentRequest>()

      val order = Queries.findOrder(UUID.fromString(orderId))
      if (order == null) {
        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Order not found"))
        return@post
      }

      // Update order state to PAYING
      // and schedule the payment job in the same transaction
      transaction {
        Orders.update({ Orders.id eq UUID.fromString(orderId) }) {
          it[Orders.state] = OrderState.PAYING.name
        }

        bikesched.createJob(
          PROCESS_PAYMENT,
          ProcessPaymentPayload(
            orderId = orderId,
            paymentOptionId = paymentRequest.paymentOptionId,
          ),
          connection = connection.connection as Connection,
        )
      }

      call.respond(OrderResponse(orderId, OrderState.PAYING.name))
    }

    // Get order status
    get("/orders/{orderId}") {
      val orderId = call.pathParameters["orderId"]
        ?: throw IllegalArgumentException("Order ID is required")

      val order = Queries.findOrder(UUID.fromString(orderId))
      if (order == null) {
        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Order not found"))
        return@get
      }

      val (state, totalAmount) = order
      call.respond(OrderResponse(orderId, state, totalAmount))
    }
  }
}

/** Request body for initiating payment. */
@Serializable
data class PaymentRequest(
  val paymentOptionId: String,
)

/** Response with the state of an order. */
@Serializable
data class OrderResponse(
  val orderId: String,
  val state: String,
  @Serializable(MonetaryAmountSerializer::class) val totalAmount: MonetaryAmount? = null,
)

/** Response with the payment options offered for an order. */
@Serializable
data class PaymentOptionsResponse(
  val orderId: String,
  @Serializable(MonetaryAmountSerializer::class) val totalAmount: MonetaryAmount,
  val paymentOptions: List<PaymentOption>,
)
