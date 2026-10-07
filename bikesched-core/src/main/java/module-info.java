// SPDX-License-Identifier: Apache-2.0
/**
 * bikesched: a PostgreSQL-backed job scheduler shared by all connected nodes.
 *
 * <ul>
 *   <li>{@link org.bikesched}: entry point ({@link org.bikesched.Bikesched}, its builder) and
 *       node-wide settings like JSON mapping and retry backoff
 *   <li>{@link org.bikesched.job}: jobs, their handlers, durable steps, recurring schedules and the
 *       job builders
 *   <li>{@link org.bikesched.job.listener}: job execution listener SPI and reported event types
 *   <li>{@link org.bikesched.event}: waiting for and publishing events
 *   <li>{@link org.bikesched.event.listener}: event publication listener SPI and reported types
 * </ul>
 *
 * <p>The {@code org.bikesched.internal} packages hold the implementation and are not exported.
 */
module org.bikesched {
  requires transitive java.sql;
  requires org.slf4j;

  exports org.bikesched;
  exports org.bikesched.job;
  exports org.bikesched.job.listener;
  exports org.bikesched.event;
  exports org.bikesched.event.listener;
}
