package org.bikesched.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bikesched.AbstractBikeschedTest;
import org.bikesched.Bikesched;
import org.bikesched.PaymentJsonMapper;
import org.bikesched.PaymentJsonMapper.Payment;
import org.junit.jupiter.api.Test;

/** Job types with a payload class: {@link JobType}, next to the string-based API. */
class JobTypeTest extends AbstractBikeschedTest {

  @Test
  void typedHandlerReceivesTheMappedPayload() throws Exception {
    JobType<Payment> payment = JobType.of(unique("payment"), Payment.class);
    var received = new CompletableFuture<Payment>();
    try (Bikesched bikesched = node("node-job-type")) {
      bikesched.registerJobHandler(payment, (_, p) -> received.complete(p));

      UUID jobId = bikesched.newJob(payment).payload(new Payment("order-1", 100)).schedule();

      assertThat(received.get(10, TimeUnit.SECONDS)).isEqualTo(new Payment("order-1", 100));
      awaitState(jobId, "SUCCEEDED");
    }
  }

  @Test
  void builderTakesATypedHandler() throws Exception {
    JobType<Payment> payment = JobType.of(unique("payment-inline"), Payment.class);
    var received = new CompletableFuture<Payment>();
    try (Bikesched bikesched = node("node-job-type-inline")) {
      bikesched
          .newJob(payment)
          .payload(new Payment("order-2", 200))
          .handler((_, p) -> received.complete(p))
          .schedule();

      assertThat(received.get(10, TimeUnit.SECONDS)).isEqualTo(new Payment("order-2", 200));
    }
  }

  /** A handler class carrying its type, as registered from a DI container. */
  record PaymentHandler(JobType<Payment> type, CompletableFuture<Payment> received)
      implements JobTypeHandler<Payment> {
    @Override
    public void handle(Job job, Payment payment) {
      received.complete(payment);
    }
  }

  @Test
  void handlerCarryingItsTypeIsRegisteredOnItsOwn() throws Exception {
    var first =
        new PaymentHandler(
            JobType.of(unique("payment-a"), Payment.class), new CompletableFuture<>());
    var second =
        new PaymentHandler(
            JobType.of(unique("payment-b"), Payment.class), new CompletableFuture<>());
    List<JobTypeHandler<?>> handlers = List.of(first, second);
    try (Bikesched bikesched = node("node-job-type-handler")) {
      handlers.forEach(bikesched::registerJobHandler);

      bikesched.newJob(first.type()).payload(new Payment("order-a", 1)).schedule();
      bikesched.newJob(second.type()).payload(new Payment("order-b", 2)).schedule();

      assertThat(first.received().get(10, TimeUnit.SECONDS)).isEqualTo(new Payment("order-a", 1));
      assertThat(second.received().get(10, TimeUnit.SECONDS)).isEqualTo(new Payment("order-b", 2));
      assertThatThrownBy(() -> bikesched.registerJobHandler(first))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("already registered");
    }
  }

  @Test
  void jobWithoutPayloadPassesNull() throws Exception {
    JobType<Payment> payment = JobType.of(unique("payment-none"), Payment.class);
    var received = new CompletableFuture<Payment>();
    try (Bikesched bikesched = node("node-job-type-null")) {
      bikesched.registerJobHandler(payment, (_, p) -> received.complete(p));

      bikesched.newJob(payment).schedule();

      assertThat(received.get(10, TimeUnit.SECONDS)).isNull();
    }
  }

  @Test
  void stringAndTypedApisShareTheJobType() throws Exception {
    String name = unique("payment-mixed");
    JobType<Payment> payment = JobType.of(name, Payment.class);
    var received = new CompletableFuture<Payment>();
    try (Bikesched bikesched = node("node-job-type-mixed")) {
      bikesched.registerJobHandler(payment, (_, p) -> received.complete(p));

      bikesched.newJob(name).payload(new Payment("order-3", 300)).schedule();

      assertThat(received.get(10, TimeUnit.SECONDS)).isEqualTo(new Payment("order-3", 300));
    }
  }

  @Test
  void nameIsUsedWithOnePayloadClassOnly() throws Exception {
    String name = unique("payment-conflict");
    try (Bikesched bikesched = node("node-job-type-conflict")) {
      bikesched.registerJobHandler(JobType.of(name, Payment.class), (_, _) -> {});

      assertThatThrownBy(() -> bikesched.newJob(JobType.of(name, String.class)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "Job type '"
                  + name
                  + "' is already used with payload type org.bikesched.PaymentJsonMapper$Payment,"
                  + " not java.lang.String");
      // The same class again is fine, e.g. a constant per producer and consumer.
      assertThat(bikesched.newJob(JobType.of(name, Payment.class))).isNotNull();
    }
  }

  @Test
  void typedRecurringJobCanBeRemovedByItsType() throws Exception {
    JobType<Payment> payment = JobType.of(unique("payment-recurring"), Payment.class);
    try (Bikesched bikesched = node("node-job-type-recurring")) {
      bikesched
          .newJob(payment)
          .payload(new Payment("monthly", 10))
          .handler((_, _) -> {})
          .schedule(Recurrence.every(Duration.ofHours(1)), Duration.ofHours(1));
      assertThat(count("job_type = '" + payment.name() + "' and state = 'SCHEDULED'")).isOne();

      assertThat(bikesched.removeRecurringJob(payment)).isTrue();

      assertThat(count("job_type = '" + payment.name() + "'")).isZero();
    }
  }

  @Test
  void jobTypeNeedsANameAndAPayloadClass() {
    assertThatThrownBy(() -> JobType.of(" ", Payment.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("job type must not be blank");
    assertThatThrownBy(() -> JobType.of("payment", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("payloadType");
  }

  private static Bikesched node(String name) {
    return testNode(name).jsonMapper(new PaymentJsonMapper()).build();
  }

  private static String unique(String name) {
    return name + "-" + UUID.randomUUID();
  }
}
