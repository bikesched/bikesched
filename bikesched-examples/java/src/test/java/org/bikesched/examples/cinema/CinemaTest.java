// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.cinema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.examples.util.Database;
import org.bikesched.jackson.JacksonJsonMapper;
import org.bikesched.test.BikeschedTest;
import org.junit.jupiter.api.Test;

/** Runs the cinema ticket order against a real PostgreSQL. */
class CinemaTest {

  private static final DataSource DATA_SOURCE = Database.dataSource();

  @Test
  void ticketsAreReservedPaidAndMailed() throws Exception {
    var output = new ByteArrayOutputStream();
    var order =
        new TicketOrder(
            "order-1",
            "alice@example.com",
            "Metropolis",
            "2026-10-09 20:15",
            List.of("F7", "F8"),
            new BigDecimal("12.50"));
    try (Bikesched bikesched =
        BikeschedTest.testNode(DATA_SOURCE, "cinema").jsonMapper(new JacksonJsonMapper()).build()) {
      bikesched.registerJobHandler(
          new TicketOrderHandler(new PrintStream(output, true, StandardCharsets.UTF_8)));

      UUID jobId = bikesched.newJob(CinemaJobs.TICKET_ORDER).payload(order).schedule();

      BikeschedTest.awaitJob(bikesched, jobId)
          .untilSucceededSteps("reserve-seats", "charge-payment", "mail-tickets");
    }

    List<String> lines = output.toString(StandardCharsets.UTF_8).lines().toList();
    assertThat(lines).hasSize(3);
    assertThat(lines.get(0)).startsWith("[cinema]  reserved seats F7, F8 for Metropolis");
    assertThat(lines.get(1)).startsWith("[payment] charged 25.00 EUR to alice@example.com");
    assertThat(lines.get(2))
        .startsWith("[mail]    sent 2 ticket(s) for Metropolis to alice@example.com");
  }
}
