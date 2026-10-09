// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.outcome;

import java.io.Serial;

/** Thrown by the payment provider when it declines a card, like the card errors of payment SDKs. */
public final class CardDeclinedException extends Exception {

  @Serial private static final long serialVersionUID = 1L;

  /** The provider's decline code. */
  private final String code;

  CardDeclinedException(String code, String message) {
    super(message);
    this.code = code;
  }

  /**
   * Returns the provider's decline code.
   *
   * @return e.g. {@code card_declined} or {@code insufficient_funds}
   */
  public String code() {
    return code;
  }
}
