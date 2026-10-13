// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import org.jspecify.annotations.Nullable;

/**
 * A job's payload and result as JSON text, see {@link Admin#findJobData(java.util.UUID)}.
 *
 * @param payload the payload; {@code null} if the job has none
 * @param result the result of a succeeded job; {@code null} otherwise
 */
public record JobData(@Nullable String payload, @Nullable String result) {}
