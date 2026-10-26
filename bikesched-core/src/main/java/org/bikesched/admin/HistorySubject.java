// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

import java.util.UUID;
import org.bikesched.history.SubjectType;
import org.jspecify.annotations.Nullable;

/**
 * A job, event or node a page of {@link Admin#findHistory(HistoryQuery)} refers to: the node that
 * wrote an entry or the subject that caused one.
 *
 * @param id the subject's id
 * @param type what it is
 * @param name its name: a registered node's, or else the one recorded with its latest entry; {@code
 *     null} if none was recorded
 */
public record HistorySubject(UUID id, SubjectType type, @Nullable String name) {}
