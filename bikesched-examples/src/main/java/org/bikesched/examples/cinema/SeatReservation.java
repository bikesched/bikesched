// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.cinema;

import java.util.List;

/**
 * Seats held for an order: the result of the {@code reserve-seats} step.
 *
 * @param reservationId identifies the reservation at the cinema
 * @param seats the reserved seats
 */
public record SeatReservation(String reservationId, List<String> seats) {}
