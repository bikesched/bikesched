package bikesched.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Mapping payloads and step results with the configured {@link JsonMapper}. */
class JsonMapperTest extends AbstractBikeschedTest {

  @Test
  void configuredJsonMapperMapsPayloadsAndTypedStepResults() throws Exception {
    try (Bikesched bikesched =
        Bikesched.builder()
            .dataSource(dataSource)
            .node("node-json-mapper")
            .jsonMapper(new PaymentJsonMapper())
            .pollInterval(Duration.ofMillis(50))
            .build()) {
      var chargeCalls = new java.util.concurrent.atomic.AtomicInteger();
      var seen = new java.util.concurrent.CopyOnWriteArrayList<String>();
      UUID jobId =
          bikesched
              .newJob("typed")
              .payload(new PaymentJsonMapper.Payment("order-1", 100))
              .handler(
                  job -> {
                    PaymentJsonMapper.Payment order = job.payload(PaymentJsonMapper.Payment.class);
                    var charged =
                        job.step(
                            "charge",
                            PaymentJsonMapper.Payment.class,
                            step -> {
                              chargeCalls.incrementAndGet();
                              return new PaymentJsonMapper.Payment(
                                  "pay-" + order.id(), order.amount() * 2);
                            });
                    Void nothing = job.step("nothing", Void.class, step -> null);
                    seen.add(job.payload() + " | " + order + " | " + charged + " | " + nothing);
                    if (job.attempts() == 1) {
                      throw new IllegalStateException("retry to replay the steps");
                    }
                  })
              .schedule();

      awaitState(jobId, "SUCCEEDED");

      assertThat(chargeCalls).as("executed once, replayed on the retry").hasValue(1);
      var expected =
          "{\"id\": \"order-1\", \"amount\": 100} | Payment[id=order-1, amount=100] "
              + "| Payment[id=pay-order-1, amount=200] | null";
      assertThat(seen).containsExactly(expected, expected);
      assertThat(
              count(
                  "id = '"
                      + jobId
                      + "' and payload = '{\"id\": \"order-1\", \"amount\": 100}'::jsonb"))
          .isEqualTo(1);
      assertThat(
              countRows(
                  "bikesched_steps",
                  "job_id = '"
                      + jobId
                      + "' and name = 'charge' "
                      + "and result = '{\"id\": \"pay-order-1\", \"amount\": 200}'::jsonb"))
          .isEqualTo(1);
      assertThat(
              countRows(
                  "bikesched_steps",
                  "job_id = '"
                      + jobId
                      + "' and name = 'nothing' "
                      + "and state = 'SUCCEEDED' and result is null"))
          .isEqualTo(1);
    }
  }

  @Test
  void defaultJsonMapperOnlySupportsJsonStrings() throws Exception {
    try (Bikesched bikesched =
        Bikesched.builder()
            .dataSource(dataSource)
            .node("node-default-mapper")
            .pollInterval(Duration.ofMillis(50))
            .build()) {
      assertThatThrownBy(
              () ->
                  bikesched
                      .newJob("default-mapper")
                      .payload(new PaymentJsonMapper.Payment("order-1", 100)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageStartingWith("JsonStringMapper only supports JSON strings");

      var seen = new java.util.concurrent.CompletableFuture<String>();
      UUID jobId =
          bikesched
              .newJob("default-mapper")
              .payload("{\"a\": 1}")
              .maxAttempts(1)
              .handler(
                  job -> {
                    String raw = job.payload(String.class);
                    String step = job.step("typed-string", String.class, s -> "[1, 2]");
                    try {
                      job.payload(PaymentJsonMapper.Payment.class);
                    } catch (IllegalArgumentException e) {
                      seen.complete(raw + " " + step);
                      throw e;
                    }
                  })
              .schedule();

      assertThat(seen.get(10, java.util.concurrent.TimeUnit.SECONDS))
          .isEqualTo("{\"a\": 1} [1, 2]");
      awaitState(jobId, "FAILED");
    }
  }
}
