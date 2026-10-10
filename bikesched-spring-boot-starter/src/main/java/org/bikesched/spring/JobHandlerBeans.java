// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bikesched.Bikesched;
import org.bikesched.annotation.AnnotatedJobHandler;
import org.bikesched.annotation.JobHandlers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Collects the beans that declare {@code @JobHandler} methods and registers them on the node: the
 * {@link JobHandlerBeanPostProcessor} adds every bean as it is created (this must not touch the
 * {@code Bikesched} bean, which may not exist yet), the node's factory method calls {@link
 * #registerAll(Bikesched)} once it is built, and beans created later are registered right away.
 */
final class JobHandlerBeans {

  private static final Logger LOG = LoggerFactory.getLogger(JobHandlerBeans.class);

  private final Map<String, Bean> beans = new ConcurrentHashMap<>();
  private final ObjectProvider<HandlerWarning> warningProvider;
  private volatile List<HandlerWarning> warnings; // resolved lazily: the beans may come later
  private volatile Bikesched bikesched;
  private volatile boolean nodeClosed;

  JobHandlerBeans(ObjectProvider<HandlerWarning> warningProvider) {
    this.warningProvider = warningProvider;
  }

  /** Records a bean whose class declares {@code @JobHandler} methods. */
  void add(String beanName, Object bean, Class<?> targetClass) {
    beans.putIfAbsent(beanName, new Bean(bean, targetClass));
    Bikesched node = bikesched;
    if (node != null) {
      register(beanName, node);
    }
  }

  /**
   * Registers the collected beans on the node.
   *
   * @param node the node
   * @return the number of registered handlers
   */
  int registerAll(Bikesched node) {
    bikesched = node;
    int registered = 0;
    for (String beanName : beans.keySet()) {
      registered += register(beanName, node);
    }
    return registered;
  }

  /** Marks the node closed: beans created later are skipped instead of failing the application. */
  void nodeClosed() {
    nodeClosed = true;
  }

  /** Registers the annotated methods of one bean; returns the number of handlers. */
  private int register(String beanName, Bikesched node) {
    Bean bean = beans.get(beanName);
    if (bean == null) {
      return 0;
    }
    if (nodeClosed) {
      LOG.debug(
          "bikesched: bean '{}' declares @JobHandler/@JobFailureHandler methods, but the node is"
              + " closed; not registering",
          beanName);
      return 0;
    }
    List<AnnotatedJobHandler> handlers;
    try {
      handlers = JobHandlers.scan(bean.targetClass());
      for (AnnotatedJobHandler handler : handlers) {
        AnnotatedJobHandler bound = handler.bindTo(bean.instance());
        warnings().forEach(warning -> warning.warn(bound));
        bound.registerOn(node);
        LOG.debug(
            "bikesched registered {} {} from bean '{}' method {}",
            bound.isFailureHandler() ? "the failure handler of job type" : "job type",
            bound.type(),
            beanName,
            bound.method());
      }
    } catch (IllegalArgumentException e) {
      throw new HandlerRegistrationException(
          "bikesched cannot register the @JobHandler/@JobFailureHandler methods of bean '"
              + beanName
              + "': "
              + e.getMessage(),
          e);
    }
    return handlers.size();
  }

  /** The warnings, resolved once, after all bean definitions exist. */
  private List<HandlerWarning> warnings() {
    List<HandlerWarning> resolved = warnings;
    if (resolved == null) {
      resolved = warningProvider.orderedStream().toList();
      warnings = resolved;
    }
    return resolved;
  }

  /** A bean with annotated methods, with the class declaring them (a proxy's target class). */
  private record Bean(Object instance, Class<?> targetClass) {}
}
