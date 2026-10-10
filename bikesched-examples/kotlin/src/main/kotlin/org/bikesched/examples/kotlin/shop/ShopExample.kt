// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.kotlin.shop

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ShopExample")

/**
 * Runs the shop and, once it is up, a client that places an order, waits until the payment options
 * are offered, pays with the first one and waits for the final state.
 */
fun main() {
  ShopServer().use { app ->
    app.start()
    runBlocking {
      ShopClient().use { client ->
        var orders = mutableSetOf<String>()

        orders += client.createOrder(listOf(OrderItem("item-1", 1), OrderItem("item-3", 1)))
        orders += client.createOrder(listOf(OrderItem("item-3", 1), OrderItem("item-5", 2)))

        orders.forEach { order ->
          log.info("Created order {}", order)

          val orderId = order
          withTimeout(30.seconds) {
            client.awaitState(orderId, setOf(OrderState.PAYMENT_OPTIONS_RESOLVED))
            val options = client.paymentOptions(orderId)
            log.info("Offered payment options: {}", options)

            client.pay(orderId, options.first())
            val result = client.awaitState(orderId, setOf(OrderState.PAID, OrderState.FAILED))
            log.info("Order {} finished: {}", orderId, result)
          }
        }
      }
    }
  }
}
