// SPDX-License-Identifier: Apache-2.0
/**
 * Lookups and actions for operating bikesched: jobs by name and state, their steps, events, waiting
 * steps and nodes, and cancelling, retrying and removing jobs in the name of a user, see {@link
 * org.bikesched.Bikesched#admin()}. The basis of the admin UI in {@code bikesched-admin} and of
 * operational scripts. Summaries never carry payloads or results.
 */
@NullMarked
package org.bikesched.admin;

import org.jspecify.annotations.NullMarked;
