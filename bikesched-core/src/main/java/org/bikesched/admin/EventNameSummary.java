// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

/**
 * The unexpired events of one name, see {@link Admin#findEventNames()}.
 *
 * @param name the event name
 * @param count the number of unexpired events
 */
public record EventNameSummary(String name, int count) {}
