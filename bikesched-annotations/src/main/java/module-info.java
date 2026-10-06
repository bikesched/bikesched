/**
 * Annotations for bikesched: {@link org.bikesched.annotation.JobHandler @JobHandler} declares job
 * handlers as methods, {@link org.bikesched.annotation.JobHandlers} finds and registers them.
 */
module org.bikesched.annotation {
  requires transitive org.bikesched;

  exports org.bikesched.annotation;
}
