// SPDX-License-Identifier: Apache-2.0
/**
 * Kotlin helpers for bikesched: a configuration DSL, reified generics, {@code kotlin.time.Duration}
 * overloads, nullable lookups, and job handlers receiving their typed payload.
 */
module org.bikesched.kotlin {
  requires transitive org.bikesched;
  requires transitive kotlin.stdlib;

  exports org.bikesched.kotlin;
}
