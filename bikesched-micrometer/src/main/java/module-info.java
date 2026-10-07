// SPDX-License-Identifier: Apache-2.0
/**
 * Micrometer metrics for bikesched: {@link org.bikesched.micrometer.MicrometerJobListener} records
 * the jobs a node runs, {@link org.bikesched.micrometer.MicrometerEventListener} the events
 * published through it, in a {@code MeterRegistry}.
 */
// Micrometer has no module descriptor, only an Automatic-Module-Name.
@SuppressWarnings({"requires-automatic", "requires-transitive-automatic"})
module org.bikesched.micrometer {
  requires transitive org.bikesched;
  requires transitive micrometer.core;

  exports org.bikesched.micrometer;
}
