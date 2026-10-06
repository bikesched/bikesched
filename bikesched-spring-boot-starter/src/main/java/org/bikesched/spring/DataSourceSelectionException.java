// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import java.io.Serial;

/**
 * Thrown when the starter cannot tell which {@code DataSource} bean the node should use: the
 * context has several and neither {@code bikesched.data-source} nor {@code @Primary} picks one, or
 * the configured name is not a {@code DataSource} bean. Fails the context at startup; {@link
 * DataSourceSelectionFailureAnalyzer} turns it into a readable message.
 */
public class DataSourceSelectionException extends IllegalStateException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what is wrong
   */
  public DataSourceSelectionException(String message) {
    super(message);
  }
}
