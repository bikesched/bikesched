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

/** Creates an amount in the shop currency, e.g. `"29.99".euro()`. */
fun String.euro(): MonetaryAmount = BigDecimal(this).euro()

/** Creates an amount in the shop currency. */
fun BigDecimal.euro(): MonetaryAmount = Money.of(this, SHOP_CURRENCY)

/** Creates an amount from the number and the currency code stored in the database. */
fun moneyOf(amount: BigDecimal, currency: String): MonetaryAmount = Money.of(amount, currency)

/** The currency code of an amount, as stored in the database. */
fun MonetaryAmount.currencyCode(): String = currency.currencyCode

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
