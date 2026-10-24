// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import java.io.Serial;

/**
 * Thrown when a bean's {@code @JobHandler} methods cannot be registered: an invalid signature, or a
 * job name another bean (or the application) already handles. Fails the context at startup; {@link
 * HandlerRegistrationFailureAnalyzer} turns it into a message naming the bean.
 */
public class HandlerRegistrationException extends IllegalArgumentException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what is wrong, including the bean
   * @param cause the refused registration
   */
  public HandlerRegistrationException(String message, Throwable cause) {
    super(message, cause);
  }
}
