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
      Item(id = "item-1", name = "Wireless Mouse", price = euro("29.99")),
      Item(id = "item-2", name = "Mechanical Keyboard", price = euro("89.99")),
      Item(id = "item-3", name = "USB-C Hub", price = euro("49.99")),
      Item(id = "item-4", name = "Monitor Stand", price = euro("59.99")),
      Item(id = "item-5", name = "Webcam HD", price = euro("79.99")),
      Item(id = "item-6", name = "Desk Lamp", price = euro("34.99")),
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
        }
      }
      log.info("Loaded {} fixture items", missing.size)
    }
  }
}
