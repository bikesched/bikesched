// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin;

/**
 * The unexpired events of one name and the steps waiting for one, see {@link
 * Admin#findEventNames()}.
 *
 * @param name the event name
 * @param count the number of unexpired events, 0 if steps only wait for the name
 * @param waiting the number of steps waiting for an event of the name
 */
public record EventNameSummary(String name, int count, int waiting) {}
