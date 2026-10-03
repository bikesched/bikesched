/**
 * Kotlin helpers for bikesched: a configuration DSL, reified generics, {@code kotlin.time.Duration}
 * overloads, nullable lookups, and job handlers receiving their typed payload.
 */
module bikesched.kotlin {
  requires transitive bikesched.core;
  requires transitive kotlin.stdlib;

  exports bikesched.kotlin;
}
