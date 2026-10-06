// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns {@link HandlerRegistrationException} into a startup message with an action instead of a
 * stack trace.
 */
class HandlerRegistrationFailureAnalyzer
    extends AbstractFailureAnalyzer<HandlerRegistrationException> {

  @Override
  protected FailureAnalysis analyze(Throwable rootFailure, HandlerRegistrationException cause) {
    return new FailureAnalysis(
        cause.getMessage(),
        "give every @JobHandler method its own job type, or register the colliding handler by hand",
        cause);
  }
}
