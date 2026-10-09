// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.kotlin.shop

import javax.money.MonetaryAmount
import kotlinx.serialization.Serializable

/** A shop item that can be purchased. */
@Serializable
data class Item(
  val id: String,
  val name: String,
  @Serializable(MonetaryAmountSerializer::class) val price: MonetaryAmount,
)

/** An order item referencing a shop item and the quantity. */
@Serializable
data class OrderItem(
  val itemId: String,
  val amount: Int,
)

/** A shop order with its items. */
@Serializable
data class Order(
  val orderId: String,
  val items: List<OrderItem>,
)

/** The state of an order. */
enum class OrderState {
  RESOLVING_PAYMENT_OPTIONS,
  PAYMENT_OPTIONS_RESOLVED,
  PAYING,
  PAID,
  FAILED,
}

/** Payment options returned by the payment service. */
@Serializable
data class PaymentOptions(
  val options: List<PaymentOption>,
)

@Serializable
data class PaymentOption(
  val id: String,
  val name: String,
)

/** The result of a payment attempt. */
@Serializable
data class PaymentResult(
  val success: Boolean,
  val transactionId: String?,
  val errorMessage: String?,
)
