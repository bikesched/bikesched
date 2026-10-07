// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.outcome;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * The business outcome of charging a card: the success and the expected error, both stored as the
 * step's result. The Jackson annotations store the subtype with the JSON, so the stored result maps
 * back to the right record.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
@JsonSubTypes({
  @JsonSubTypes.Type(value = ChargeOutcome.Charged.class, name = "charged"),
  @JsonSubTypes.Type(value = ChargeOutcome.Declined.class, name = "declined")
})
public sealed interface ChargeOutcome {

  /**
   * The card was charged.
   *
   * @param receiptId the payment provider's receipt
   */
  record Charged(String receiptId) implements ChargeOutcome {}

  /**
   * The card was declined; retrying would not change that.
   *
   * @param code the provider's decline code, e.g. {@code insufficient_funds}
   * @param reason a readable reason
   */
  record Declined(String code, String reason) implements ChargeOutcome {}
}
