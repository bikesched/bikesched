// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import org.bikesched.BikeschedBuilder;

/**
 * Customizes the {@link BikeschedBuilder} the starter uses, for everything the {@code bikesched.*}
 * properties do not cover (e.g. a JSON mapper or a job listener):
 *
 * <pre>{@code
 * @Bean
 * BikeschedCustomizer timeouts() {
 *   return builder -> builder.defaultJobTimeout(Duration.ofMinutes(5));
 * }
 * }</pre>
 *
 * <p>Customizers run after the properties, in {@code @Order} order.
 */
@FunctionalInterface
public interface BikeschedCustomizer {

  /**
   * Customizes the builder before the node is built.
   *
   * @param builder the builder, with the properties already applied
   */
  void customize(BikeschedBuilder builder);
}
