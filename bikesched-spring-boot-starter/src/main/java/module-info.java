// SPDX-License-Identifier: Apache-2.0
/**
 * Spring Boot starter for bikesched: {@code BikeschedAutoConfiguration} creates the {@link
 * org.bikesched.Bikesched} node from the application context, registers the {@code @JobHandler}
 * methods of the context's beans and starts polling with the context.
 *
 * <p>Open, because Spring reflects into these classes for binding and bean processing.
 */
// Spring and Micrometer ship as automatic modules (no module descriptor); the optional ones are
// only used by condition-guarded code.
@SuppressWarnings({"requires-automatic", "requires-transitive-automatic"})
open module org.bikesched.spring {
  requires transitive org.bikesched;
  requires transitive org.bikesched.annotation;
  // @ConfigurationProperties and @EnableConfigurationProperties live in spring.boot, the
  // auto-configuration annotations in spring.boot.autoconfigure.
  requires transitive spring.boot;
  requires transitive spring.boot.autoconfigure;
  requires spring.context;
  requires spring.beans;
  requires spring.core;
  requires spring.aop;
  requires org.slf4j;
  requires static spring.tx;
  requires static spring.jdbc;
  requires static micrometer.core;
  requires static org.bikesched.jackson;
  requires static org.bikesched.micrometer;

  exports org.bikesched.spring;
}
