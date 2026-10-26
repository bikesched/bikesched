// SPDX-License-Identifier: Apache-2.0
/**
 * The history: what happened to jobs, their steps, events and nodes, and who did it, recorded by
 * nodes with the history enabled ({@link
 * org.bikesched.BikeschedBuilder#history(java.time.Duration)}) and read with {@link
 * org.bikesched.Bikesched#findJobHistory(java.util.UUID)}. Each {@link
 * org.bikesched.history.HistoryEntry entry} names its {@link org.bikesched.history.HistoryAction
 * action} and its {@link org.bikesched.history.ActorType actor}.
 */
@NullMarked
package org.bikesched.history;

import org.jspecify.annotations.NullMarked;
