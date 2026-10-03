/**
 * bikesched: a PostgreSQL-backed job scheduler shared by all connected nodes.
 *
 * <ul>
 *   <li>{@link bikesched.core}: entry point ({@link bikesched.core.Bikesched}, its builder) and
 *       node-wide settings like JSON mapping and retry backoff
 *   <li>{@link bikesched.core.job}: jobs, their handlers, durable steps, recurring schedules and
 *       the job builders
 *   <li>{@link bikesched.core.event}: waiting for and publishing events
 * </ul>
 *
 * <p>The {@code bikesched.core.internal} packages hold the implementation and are not exported.
 */
module bikesched.core {
  requires transitive java.sql;
  requires org.slf4j;

  exports bikesched.core;
  exports bikesched.core.job;
  exports bikesched.core.event;
}
