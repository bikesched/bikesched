package org.bikesched;

import java.io.Serial;

/** Base exception for failures reported by bikesched. */
public class BikeschedException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates an exception with a message.
   *
   * @param message the detail message
   */
  public BikeschedException(String message) {
    super(message);
  }

  /**
   * Creates an exception with a message and cause.
   *
   * @param message the detail message
   * @param cause the underlying cause
   */
  public BikeschedException(String message, Throwable cause) {
    super(message, cause);
  }
}
