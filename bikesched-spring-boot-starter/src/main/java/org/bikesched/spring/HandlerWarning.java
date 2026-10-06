// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import org.bikesched.annotation.AnnotatedJobHandler;

/**
 * Warns about a {@code @JobHandler} method that will not behave as its author may expect, e.g.
 * because it is annotated with a Spring annotation that has no effect on a job thread.
 * Implementations are beans of the starter, so they can be conditional on the annotation's library
 * being present.
 */
@FunctionalInterface
interface HandlerWarning {

  /**
   * Warns about a handler, if there is anything to warn about.
   *
   * @param handler the handler, bound to its bean
   */
  void warn(AnnotatedJobHandler handler);
}
