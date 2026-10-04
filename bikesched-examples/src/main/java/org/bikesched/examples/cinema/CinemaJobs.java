package org.bikesched.examples.cinema;

import org.bikesched.job.JobType;

/**
 * The job types of the cinema, shared by the code ordering tickets and the {@link
 * TicketOrderHandler}: producers don't depend on the handler class.
 */
public final class CinemaJobs {

  /** Orders tickets: reserve the seats, charge the payment, mail the tickets. */
  public static final JobType<TicketOrder> TICKET_ORDER =
      JobType.of("cinema-ticket-order", TicketOrder.class);

  private CinemaJobs() {}
}
