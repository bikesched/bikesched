// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.cinema;

import java.math.BigDecimal;
import java.util.List;

/**
 * A customer's ticket order: the payload of the ticket order job. The seats are a list inside the
 * order record (not the payload itself), so the order can gain properties later.
 *
 * @param orderId identifies the order
 * @param customerEmail where the tickets are mailed to
 * @param movie the movie
 * @param showtime when the show starts, e.g. {@code 2026-10-09 20:15}
 * @param seats the seats, e.g. {@code F7}
 * @param pricePerSeat the price of one seat in EUR
 */
public record TicketOrder(
    String orderId,
    String customerEmail,
    String movie,
    String showtime,
    List<String> seats,
    BigDecimal pricePerSeat) {

  /**
   * Returns the order's total.
   *
   * @return the price of all seats
   */
  public BigDecimal total() {
    return pricePerSeat.multiply(BigDecimal.valueOf(seats.size()));
  }
}
