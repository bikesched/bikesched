// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.kotlin.shop

import java.util.UUID
import javax.money.MonetaryAmount
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Mock payment service that fails the first two attempts of every order and succeeds on the third.
 *
 * It remembers the attempts per order id and forgets the order after the successful payment.
 */
object MockPaymentService {

  private val log = LoggerFactory.getLogger(MockPaymentService::class.java)

  private const val FAILING_ATTEMPTS = 3

  private val attempts = ConcurrentHashMap<String, Int>()

  /**
   * Process a payment for the given order.
   *
   * @param orderId the order id
   * @param paymentOptionId the payment option (e.g., "credit_card" or "buy_now_pay_later")
   * @return the payment result
   */
  fun processPayment(orderId: String, paymentOptionId: String): PaymentResult {
    log.info("Processing payment for order {} with option {}", orderId, paymentOptionId)

    // The first two attempts of an order fail, the third succeeds and clears the order's state
    val attempt = attempts.merge(orderId, 1, Int::plus)!!
    if (attempt <= FAILING_ATTEMPTS) {
      val error = "Payment gateway error"
      log.warn("Payment failed for order {}: {}", orderId, error)
      return PaymentResult(success = false, transactionId = null, errorMessage = error)
    }

    attempts.remove(orderId)
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
