// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.kotlin.shop

import java.math.BigDecimal
import javax.money.MonetaryAmount
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.javamoney.moneta.Money

/** The currency of all amounts in the shop. */
const val SHOP_CURRENCY = "EUR"

/** Creates an amount in the shop currency, e.g. `euro("29.99")`. */
fun euro(amount: String): MonetaryAmount = euro(BigDecimal(amount))

/** Creates an amount in the shop currency. */
fun euro(amount: BigDecimal): MonetaryAmount = Money.of(amount, SHOP_CURRENCY)

/** The numeric part of an amount, as stored in the database. */
fun MonetaryAmount.toBigDecimal(): BigDecimal = number.numberValue(BigDecimal::class.java)

/** Serializes a [MonetaryAmount] as a string like `29.99 EUR`. */
object MonetaryAmountSerializer : KSerializer<MonetaryAmount> {
  override val descriptor = PrimitiveSerialDescriptor("MonetaryAmount", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: MonetaryAmount) =
    encoder.encodeString("${value.toBigDecimal().toPlainString()} ${value.currency.currencyCode}")

  override fun deserialize(decoder: Decoder): MonetaryAmount {
    val (amount, currency) = decoder.decodeString().split(' ', limit = 2)
    return Money.of(BigDecimal(amount), currency)
  }
}
