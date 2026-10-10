// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.kotlin.shop

import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory

/** The small set of items that can be purchased. */
object FixtureItems {
  private val log = LoggerFactory.getLogger(FixtureItems::class.java)

  val items: List<Item> =
    listOf(
      Item(id = "item-1", name = "Wireless Mouse", price = "29.99".euro()),
      Item(id = "item-2", name = "Mechanical Keyboard", price = "89.99".euro()),
      Item(id = "item-3", name = "USB-C Hub", price = "49.99".euro()),
      Item(id = "item-4", name = "Monitor Stand", price = "59.99".euro()),
      Item(id = "item-5", name = "Webcam HD", price = "79.99".euro()),
      Item(id = "item-6", name = "Desk Lamp", price = "34.99".euro()),
    )

  private val itemsById: Map<String, Item> = items.associateBy { it.id }

  fun find(id: String): Item? = itemsById[id]

  /** Inserts the fixture items that are not yet in the database. Needs the schema to exist. */
  fun load() {
    transaction {
      val existing = Items.selectAll().map { it[Items.id] }.toSet()
      val missing = items.filter { it.id !in existing }
      missing.forEach { item ->
        Items.insert {
          it[Items.id] = item.id
          it[Items.name] = item.name
          it[Items.price] = item.price.toBigDecimal()
          it[Items.currency] = item.price.currencyCode()
        }
      }
      log.info("Loaded {} fixture items", missing.size)
    }
  }
}
