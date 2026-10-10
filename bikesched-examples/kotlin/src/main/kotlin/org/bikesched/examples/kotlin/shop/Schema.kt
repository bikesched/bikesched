// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.kotlin.shop

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import java.math.BigDecimal
import java.util.UUID

/** Exposed table for shop items (fixture data). */
object Items : Table("shop_items") {
  val id: Column<String> = text("id")
  val name: Column<String> = text("name")
  val price: Column<BigDecimal> = decimal("price", 12, 2)

  override val primaryKey = PrimaryKey(id)
}

/** Exposed table for orders. */
object Orders : Table("shop_orders") {
  val id: Column<UUID> = javaUUID("id")
  val state: Column<String> = text("state")
  val totalAmount: Column<BigDecimal> = decimal("total_amount", 12, 2)

  override val primaryKey = PrimaryKey(id)
}

/** Exposed table for the items of an order and their amounts. */
object OrderItems : Table("shop_order_items") {
  val orderId: Column<UUID> = javaUUID("order_id").references(Orders.id)
  val itemId: Column<String> = text("item_id").references(Items.id)
  val amount: Column<Int> = integer("amount")

  override val primaryKey = PrimaryKey(orderId, itemId)
}

/** Exposed table for the payment options offered for an order. */
object OrderPaymentOptions : Table("shop_order_payment_options") {
  val orderId: Column<UUID> = javaUUID("order_id").references(Orders.id)
  val paymentOptionId: Column<String> = text("payment_option_id")
  val name: Column<String> = text("name")

  override val primaryKey = PrimaryKey(orderId, paymentOptionId)
}
