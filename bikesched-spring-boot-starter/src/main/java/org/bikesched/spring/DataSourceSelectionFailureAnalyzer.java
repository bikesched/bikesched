// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns {@link DataSourceSelectionException} into a startup message with an action instead of a
 * stack trace.
 */
class DataSourceSelectionFailureAnalyzer
    extends AbstractFailureAnalyzer<DataSourceSelectionException> {

  @Override
  protected FailureAnalysis analyze(Throwable rootFailure, DataSourceSelectionException cause) {
    return new FailureAnalysis(
        cause.getMessage(),
        "give the DataSource to use a @Primary annotation, or name it in bikesched.data-source",
        cause);
  }
}
