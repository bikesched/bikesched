// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.kotlin.shop

import java.util.UUID
import javax.money.MonetaryAmount
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** The database queries of the shop example. */
object Queries {

  /** Looks up an order by ID, returns (state, totalAmount) or null. */
  fun findOrder(orderId: UUID): Pair<String, MonetaryAmount>? = transaction {
    Orders.selectAll().where { Orders.id eq orderId }.firstOrNull()?.let { row ->
      row[Orders.state] to euro(row[Orders.totalAmount])
    }
  }

  /** Looks up the total amount of an order, or null if it does not exist. */
  fun findOrderTotal(orderId: UUID): MonetaryAmount? = findOrder(orderId)?.second

  /** The payment options stored for an order. */
  fun findPaymentOptions(orderId: UUID): List<PaymentOption> = transaction {
    OrderPaymentOptions.selectAll().where { OrderPaymentOptions.orderId eq orderId }.map {
      PaymentOption(id = it[OrderPaymentOptions.paymentOptionId], name = it[OrderPaymentOptions.name])
    }
  }

  /** Updates the state of an order. */
  fun setOrderState(orderId: UUID, state: String) {
    transaction { Orders.update({ Orders.id eq orderId }) { it[Orders.state] = state } }
  }

  /** Stores the payment options offered for an order, replacing earlier ones. */
  fun storePaymentOptions(orderId: UUID, options: List<PaymentOption>) {
    transaction {
      OrderPaymentOptions.deleteWhere { OrderPaymentOptions.orderId eq orderId }
      options.forEach { option ->
        OrderPaymentOptions.insert {
          it[OrderPaymentOptions.orderId] = orderId
          it[OrderPaymentOptions.paymentOptionId] = option.id
          it[OrderPaymentOptions.name] = option.name
        }
      }
    }
  }
}
