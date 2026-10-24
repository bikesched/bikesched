// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.kotlin.shop

import kotlinx.serialization.Serializable
import java.time.Duration
import java.util.UUID
import org.bikesched.Bikesched
import org.bikesched.RetryBackoff
import org.bikesched.job.TypedJob
import org.bikesched.job.TypedJobHandler
import org.bikesched.kotlin.step
import org.slf4j.LoggerFactory

/** Job payload for processing a new order. */
@Serializable
data class ProcessOrderPayload(
  val orderId: String,
)

/** Job payload for processing a payment. */
@Serializable
data class ProcessPaymentPayload(
  val orderId: String,
  val paymentOptionId: String,
)

/** Typed job of new orders. */
val PROCESS_ORDER = TypedJob("process-order", ProcessOrderPayload::class.java)

/** Typed job of payments. */
val PROCESS_PAYMENT = TypedJob("process-payment", ProcessPaymentPayload::class.java)

/** Handles new orders: offers payment options. */
private val processOrderHandler = TypedJobHandler<ProcessOrderPayload> { job, payload ->
  val log = LoggerFactory.getLogger("ProcessOrderHandler")
  checkNotNull(payload) { "Job ${job.id()} has no payload" }
  log.info("Processing order {}", payload.orderId)

  val orderId = UUID.fromString(payload.orderId)
  val totalAmount = Queries.findOrderTotal(orderId)
    ?: throw IllegalStateException("Order ${payload.orderId} not found")

  val paymentOptions = job.step<PaymentOptions>("offer-payment-options") {
    MockPaymentService.getPaymentOptions(totalAmount)
  }

  log.info("Order {} payment options: {}", payload.orderId, paymentOptions.options.map { it.id })

  // Store the received options and mark the order as ready to pay
  job.step<Unit>("store-payment-options") {
    Queries.storePaymentOptions(orderId, paymentOptions.options)
    Queries.setOrderState(orderId, OrderState.PAYMENT_OPTIONS_RESOLVED.name)
  }
}

/** Handles payment processing. */
private val processPaymentHandler = TypedJobHandler<ProcessPaymentPayload> { job, payload ->
  val log = LoggerFactory.getLogger("ProcessPaymentHandler")
  checkNotNull(payload) { "Job ${job.id()} has no payload" }
  log.info("Processing payment for order {} with option {}", payload.orderId, payload.paymentOptionId)

  val orderId = UUID.fromString(payload.orderId)

  // A failing charge throws, so the step is not stored as done and the retry charges again. Once
  // all attempts are used up, the failure handler marks the order as failed before the job fails.
  val result = job.step<PaymentResult>(
    name = "charge",
    retryBackoff = RetryBackoff.exponential(Duration.ofSeconds(1), Duration.ofSeconds(30)),
    onFailure = { failing ->
      log.error("Payment for order {} failed for good: {}", payload.orderId, failing.error())
      Queries.setOrderState(orderId, OrderState.FAILED.name)
    },
  ) { step ->
    MockPaymentService.processPayment(payload.orderId, payload.paymentOptionId).also {
      check(it.success) { "Payment failed: ${it.errorMessage} at attempt: ${step.attempts}" }
    }
  }

  log.info("Payment successful for order {}: {}", payload.orderId, result.transactionId)
  Queries.setOrderState(orderId, OrderState.PAID.name)
}

/** Register the job handlers for the shop example. */
fun Bikesched.registerShopHandlers() {
  registerJobHandler(PROCESS_ORDER, processOrderHandler)
  registerJobHandler(PROCESS_PAYMENT, processPaymentHandler)
}
