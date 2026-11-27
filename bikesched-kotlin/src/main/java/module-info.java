// SPDX-License-Identifier: Apache-2.0
/**
 * Kotlin helpers for bikesched: a configuration DSL, reified generics, {@code kotlin.time.Duration}
 * overloads, nullable lookups, and suspending job handlers receiving their typed payload.
 */
module org.bikesched.kotlin {
  requires transitive org.bikesched;
  requires transitive kotlin.stdlib;
  requires transitive kotlinx.coroutines.core;

  exports org.bikesched.kotlin;
}
