// SPDX-License-Identifier: Apache-2.0
/**
 * Testing support for applications using bikesched: {@link org.bikesched.test.BikeschedTest} builds
 * a test node and {@link org.bikesched.test.JobAwait} waits for a job's outcome.
 *
 * <p>This module has no {@code module-info.java}: Awaitility, used internally by {@link
 * org.bikesched.test.JobAwait}, has neither a module descriptor nor an {@code
 * Automatic-Module-Name}, so Gradle cannot place it on the module path, and {@code bikesched-test}
 * stays off it too (like {@code bikesched-examples}).
 */
package org.bikesched.test;
