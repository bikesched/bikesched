// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.taxi;

/**
 * The broker's answer to approving a ride: the driver is on the way.
 *
 * @param driverName the driver's name
 * @param etaMinutes the estimated time until the driver arrives at the pickup, in minutes
 */
public record RideApproval(String driverName, int etaMinutes) {}
