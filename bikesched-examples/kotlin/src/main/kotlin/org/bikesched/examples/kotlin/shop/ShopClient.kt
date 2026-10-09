// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.kotlin.shop

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ShopClient")

/** A client of the shop REST API. */
class ShopClient(private val baseUrl: String = "http://localhost:8080") : AutoCloseable {
  private val http = HttpClient(CIO) { install(ContentNegotiation) { json(ShopJson) } }

  /** Places an order and returns its id. */
  suspend fun createOrder(items: List<OrderItem>): String {
    val orderId = UUID.randomUUID().toString()
    http.post("$baseUrl/api/orders") {
      contentType(ContentType.Application.Json)
      setBody(Order(orderId = orderId, items = items))
    }
    return orderId
  }

  /** Returns the state of an order. */
  suspend fun orderState(orderId: String): String =
    http.get("$baseUrl/api/orders/$orderId").body<OrderResponse>().state

  /** Polls until the order is in one of the [states] and returns that state. */
  suspend fun awaitState(orderId: String, states: Set<OrderState>): OrderState {
    var last: String? = null
    while (true) {
      val state = orderState(orderId)
      if (state != last) {
        log.info("Order {} is {}", orderId, state)
        last = state
      }
      OrderState.valueOf(state).takeIf { it in states }?.let {
        return it
      }
      delay(200.milliseconds)
    }
  }

  /** Returns the ids of the payment options offered for an order. */
  suspend fun paymentOptions(orderId: String): List<String> =
    http
      .get("$baseUrl/api/orders/$orderId/payment-options")
      .body<PaymentOptionsResponse>()
      .paymentOptions
      .map { it.id }

  /** Pays for an order with one of the offered payment options. */
  suspend fun pay(orderId: String, paymentOptionId: String) {
    http.post("$baseUrl/api/orders/$orderId/pay") {
      contentType(ContentType.Application.Json)
      setBody(PaymentRequest(paymentOptionId))
    }
  }

  override fun close() = http.close()
}
