// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.kotlin.shop

import java.util.UUID
import javax.money.MonetaryAmount
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Mock payment service: the first order it sees fails twice and then succeeds, every further order
 * fails three times (with the default of three attempts the job then fails for good).
 *
 * It remembers the failed attempts per order id and forgets the order after the successful payment.
 */
object MockPaymentService {

  private val log = LoggerFactory.getLogger(MockPaymentService::class.java)

  private const val FIRST_ORDER_FAILURES = 2
  private const val OTHER_ORDER_FAILURES = 3

  private val failuresLeft = ConcurrentHashMap<String, Int>()
  private var ordersSeen = 0

  /**
   * Process a payment for the given order.
   *
   * @param orderId the order id
   * @param paymentOptionId the payment option (e.g., "credit_card" or "buy_now_pay_later")
   * @return the payment result
   */
  fun processPayment(orderId: String, paymentOptionId: String): PaymentResult {
    log.info("Processing payment for order {} with option {}", orderId, paymentOptionId)

    val left =
      synchronized(this) {
        val current =
          failuresLeft[orderId]
            ?: (if (ordersSeen++ == 0) FIRST_ORDER_FAILURES else OTHER_ORDER_FAILURES)
        failuresLeft[orderId] = current - 1
        current
      }
    if (left > 0) {
      val error = "Payment gateway error"
      log.warn("Payment failed for order {}: {}", orderId, error)
      return PaymentResult(success = false, transactionId = null, errorMessage = error)
    }

    failuresLeft.remove(orderId)
    val transactionId = "txn-${UUID.randomUUID()}"
    log.info("Payment successful for order {}: {}", orderId, transactionId)
    return PaymentResult(success = true, transactionId = transactionId, errorMessage = null)
  }

  /**
   * Get available payment options based on the total amount.
   *
   * If the amount is below 100 EUR, "buy_now_pay_later" is offered in addition to "credit_card".
   */
  fun getPaymentOptions(totalAmount: MonetaryAmount): PaymentOptions {
    val options = mutableListOf<PaymentOption>()

    // Credit card is always available
    options.add(PaymentOption(id = "credit_card", name = "Credit Card"))

    // Buy now, pay later for orders under 100 EUR
    if (totalAmount.isLessThan(euro("100"))) {
      options.add(PaymentOption(id = "buy_now_pay_later", name = "Buy Now, Pay Later"))
    }

    return PaymentOptions(options = options)
  }
}
