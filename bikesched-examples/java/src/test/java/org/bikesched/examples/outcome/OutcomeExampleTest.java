// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.outcome;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.examples.util.Database;
import org.bikesched.jackson.JacksonJsonMapper;
import org.bikesched.job.JobState;
import org.bikesched.test.BikeschedTest;
import org.junit.jupiter.api.Test;

/** Runs the outcome example's job against a real PostgreSQL. */
class OutcomeExampleTest {

  private static final DataSource DATA_SOURCE = Database.dataSource();

  private final SimulatedPaymentProvider provider = new SimulatedPaymentProvider();

  @Test
  void chargedCardSucceeds() {
    assertCharge(SimulatedPaymentProvider.CARD_OK, JobState.SUCCEEDED, 1);
  }

  @Test
  void declinedCardIsStoredAsOutcomeAndNotRetried() {
    assertCharge(SimulatedPaymentProvider.CARD_DECLINED, JobState.CANCELLED, 1);
  }

  @Test
  void insufficientFundsIsStoredAsOutcomeAndNotRetried() {
    assertCharge(SimulatedPaymentProvider.CARD_INSUFFICIENT_FUNDS, JobState.CANCELLED, 1);
  }

  @Test
  void outageIsRetriedAndThenCharged() {
    assertCharge(SimulatedPaymentProvider.CARD_OUTAGE_ONCE, JobState.SUCCEEDED, 2);
  }

  @Test
  void storedOutcomeMapsBackToItsRecord() {
    var mapper = new JacksonJsonMapper();
    ChargeOutcome declined = new ChargeOutcome.Declined("card_declined", "Card was declined");

    String json = mapper.toJson(declined);

    assertThat(json).contains("\"@type\":\"declined\"");
    assertThat(mapper.fromJson(json, ChargeOutcome.class)).isEqualTo(declined);
  }

  private void assertCharge(String cardNumber, JobState expectedState, int expectedCalls) {
    try (Bikesched bikesched = node()) {
      var example = new OutcomeExample(bikesched, provider);
      String orderId = "order-" + UUID.randomUUID();

      UUID jobId = example.chargeOrder(orderId, cardNumber);

      BikeschedTest.awaitJob(bikesched, jobId).untilState(expectedState);
      assertThat(provider.calls(orderId)).isEqualTo(expectedCalls);
    }
  }

  private static Bikesched node() {
    return BikeschedTest.testNode(DATA_SOURCE, "outcome")
        .jsonMapper(new JacksonJsonMapper())
        .build();
  }
}
