// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.cinema;

import java.io.PrintStream;
import java.util.Objects;
import java.util.UUID;
import org.bikesched.job.Job;
import org.bikesched.job.JobType;
import org.bikesched.job.JobTypeHandler;

/**
 * Handles ticket orders as three durable steps. The cinema, the payment provider and the mail
 * server are simulated by printing what they would do.
 *
 * <p>As a {@link JobTypeHandler} it carries its job type, so it is registered without a separate
 * type argument: {@code bikesched.registerJobHandler(new TicketOrderHandler())}. If an attempt
 * fails after a step completed, the retry skips that step and reuses its stored result, so a seat
 * is never reserved or a card never charged twice.
 */
public final class TicketOrderHandler implements JobTypeHandler<TicketOrder> {

  private final PrintStream out;

  /** Creates the handler printing to {@code System.out}. */
  public TicketOrderHandler() {
    this(System.out);
  }

  /**
   * Creates the handler printing to the given stream, e.g. to capture the output in a test.
   *
   * @param out where the simulated services print to
   */
  public TicketOrderHandler(PrintStream out) {
    this.out = Objects.requireNonNull(out, "out");
  }

  @Override
  public JobType<TicketOrder> type() {
    return CinemaJobs.TICKET_ORDER;
  }

  @Override
  public void handle(Job job, TicketOrder order) throws Exception {
    // 1. Hold the seats at the cinema.
    SeatReservation reservation =
        job.step(
            "reserve-seats",
            SeatReservation.class,
            ignored -> {
              var reserved = new SeatReservation("R-" + shortId(), order.seats());
              out.printf(
                  "[cinema]  reserved seats %s for %s at %s (%s)%n",
                  String.join(", ", reserved.seats()),
                  order.movie(),
                  order.showtime(),
                  reserved.reservationId());
              return reserved;
            });

    // 2. Charge the customer.
    PaymentReceipt receipt =
        job.step(
            "charge-payment",
            PaymentReceipt.class,
            ignored -> {
              var charged = new PaymentReceipt("P-" + shortId(), order.total());
              out.printf(
                  "[payment] charged %s EUR to %s (%s)%n",
                  charged.amount(), order.customerEmail(), charged.paymentId());
              return charged;
            });

    // 3. Mail the tickets.
    job.step(
        "mail-tickets",
        Void.class,
        ignored -> {
          out.printf(
              "[mail]    sent %d ticket(s) for %s to %s (reservation %s, payment %s)%n",
              reservation.seats().size(),
              order.movie(),
              order.customerEmail(),
              reservation.reservationId(),
              receipt.paymentId());
          return null;
        });
  }

  private static String shortId() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
