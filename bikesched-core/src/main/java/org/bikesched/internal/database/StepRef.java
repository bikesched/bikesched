// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.database;

import java.util.UUID;

/**
 * A stored step of a job: its id, the primary key every write to the step uses, and its name, for
 * the job history and messages. The id is created once, when the step first starts, and kept across
 * its attempts.
 *
 * @param id the step id, a UUIDv7
 * @param name the step name, unique within its job
 */
public record StepRef(UUID id, String name) {}
