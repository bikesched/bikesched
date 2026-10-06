// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bikesched.annotation.JobHandlers;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

/**
 * Collects the beans that declare {@code @JobHandler} methods so {@link JobHandlerBeans} can
 * register them. It sees every bean once, after initialization — i.e. after Spring's auto-proxy
 * creator (which runs at {@link Ordered#HIGHEST_PRECEDENCE}), so the target class of a proxied bean
 * is still resolvable and the proxy itself is passed on to be invoked through.
 *
 * <p>It deliberately does <em>not</em> touch the {@code Bikesched} bean: a bean post-processor is
 * instantiated before the regular beans, so injecting the node (or the {@code DataSource} behind
 * it) here would break auto-configuration ordering. The node's factory method drains the collected
 * beans once it is built.
 *
 * <p>The per-class filter is cached, so the reflective cost is one method scan per class in the
 * whole application, whatever the number of beans.
 */
final class JobHandlerBeanPostProcessor implements BeanPostProcessor, Ordered {

  private final JobHandlerBeans candidates;
  private final Map<Class<?>, Boolean> hasAnnotatedMethods = new ConcurrentHashMap<>();

  JobHandlerBeanPostProcessor(JobHandlerBeans candidates) {
    this.candidates = candidates;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);
    if (!hasAnnotatedMethods(targetClass)) {
      return bean;
    }
    candidates.add(beanName, bean, targetClass);
    return bean; // never wrapped, never modified
  }

  private boolean hasAnnotatedMethods(Class<?> targetClass) {
    return hasAnnotatedMethods.computeIfAbsent(targetClass, JobHandlers::hasAnnotatedMethods);
  }

  /** Lowest precedence: after every post-processor that creates proxies. */
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
