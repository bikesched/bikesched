// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import java.lang.reflect.Method;
import org.bikesched.annotation.AnnotatedJobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Warns about {@code @Transactional} handler methods using the default propagation: a job handler
 * runs without an ambient transaction (on bikesched's own job thread), so {@code REQUIRED} does not
 * join anything — it starts a new transaction, exactly like {@code REQUIRES_NEW}. That is usually
 * what the author wants, but saying it explicitly documents the intent and survives a handler that
 * is later called from a transaction too.
 *
 * <p>Composed annotations and a class-level {@code @Transactional} count as well; an explicit other
 * propagation is silent.
 */
final class TransactionPropagationWarning implements HandlerWarning {

  private static final Logger LOG = LoggerFactory.getLogger(TransactionPropagationWarning.class);

  @Override
  public void warn(AnnotatedJobHandler handler) {
    Method method = handler.method();
    MergedAnnotation<Transactional> transactional = find(method);
    if (transactional == null
        || transactional.getEnum("propagation", Propagation.class) != Propagation.REQUIRED) {
      return;
    }
    LOG.warn(
        "bikesched: handler method {} is @Transactional with the default propagation REQUIRED, but"
            + " job handlers run without an ambient transaction: this starts a new transaction,"
            + " equivalent to REQUIRES_NEW. Make it explicit with @Transactional(propagation ="
            + " REQUIRES_NEW) to document the intent",
        method);
  }

  /** The method's own {@code @Transactional}, or the declaring class's; {@code null} for none. */
  private static MergedAnnotation<Transactional> find(Method method) {
    MergedAnnotation<Transactional> onMethod =
        MergedAnnotations.from(method, SearchStrategy.TYPE_HIERARCHY).get(Transactional.class);
    if (onMethod.isPresent()) {
      return onMethod;
    }
    MergedAnnotation<Transactional> onClass =
        MergedAnnotations.from(method.getDeclaringClass(), SearchStrategy.TYPE_HIERARCHY)
            .get(Transactional.class);
    return onClass.isPresent() ? onClass : null;
  }
}
