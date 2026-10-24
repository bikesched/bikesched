// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.cinema;

import org.bikesched.job.TypedJob;

/**
 * The typed jobs of the cinema, shared by the code ordering tickets and the {@link
 * TicketOrderHandler}: producers don't depend on the handler class.
 */
public final class CinemaJobs {

  /** Orders tickets: reserve the seats, charge the payment, mail the tickets. */
  public static final TypedJob<TicketOrder> TICKET_ORDER =
      TypedJob.of("cinema-ticket-order", TicketOrder.class);

  private CinemaJobs() {}
}
