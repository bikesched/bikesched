// SPDX-License-Identifier: Apache-2.0
package org.bikesched.annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.bikesched.job.FailedJob;
import org.bikesched.job.Job;
import org.junit.jupiter.api.Test;

/** The signatures {@link JobHandlers#scan(Object)} accepts and rejects. */
class JobHandlersScanTest {

  public record Mail(String to) {}

  public static class ValidHandlers {
    @JobHandler(type = "job-and-payload")
    public void jobAndPayload(Job job, Mail mail) {}

    @JobHandler(type = "payload-and-job")
    public void payloadAndJob(Mail mail, Job job) {}

    @JobHandler(type = "payload")
    public void payload(Mail mail) {}

    @JobHandler(type = "job")
    public void job(Job job) {}

    @JobHandler(type = "none")
    public void none() {}

    public void notAnnotated(Mail mail) {}
  }

  public static class RecurringHandlers {
    @JobHandler(type = "recurring", schedule = "PT5M")
    public void recurring() {}
  }

  public static class UnsupportedSchedule {
    @JobHandler(type = "unsupported", schedule = "every 5 minutes")
    public void recurring() {}
  }

  @Test
  void keepsTheScheduleForRegistration() {
    var handler = JobHandlers.scan(RecurringHandlers.class).getFirst();

    assertThat(handler.schedule()).contains("PT5M");
  }

  @Test
  void doesNotParseTheSchedule() {
    // parsed by the node's recurrence parsers when registering, see JobHandlersTest
    var handler = JobHandlers.scan(UnsupportedSchedule.class).getFirst();

    assertThat(handler.schedule()).contains("every 5 minutes");
  }

  @Test
  void oneOffHandlersHaveNoSchedule() {
    assertThat(JobHandlers.scan(ValidHandlers.class))
        .allSatisfy(handler -> assertThat(handler.schedule()).isEmpty());
  }

  @Test
  void findsAllValidSignatures() {
    List<AnnotatedJobHandler> handlers = JobHandlers.scan(new ValidHandlers());

    assertThat(handlers)
        .extracting(AnnotatedJobHandler::type)
        .containsExactly("job", "job-and-payload", "none", "payload", "payload-and-job");
    assertThat(handlers)
        .extracting(handler -> handler.payloadType().map(Class::getSimpleName).orElse("-"))
        .containsExactly("-", "Mail", "-", "Mail", "Mail");
  }

  @Test
  void objectWithoutAnnotatedMethodsHasNoHandlers() {
    assertThat(JobHandlers.scan(new Object())).isEmpty();
  }

  public static class Base {
    @JobHandler(type = "inherited")
    public void inherited(Mail mail) {}
  }

  public static class Sub extends Base {}

  @Test
  void findsInheritedMethods() {
    assertThat(JobHandlers.scan(new Sub()))
        .extracting(AnnotatedJobHandler::type)
        .containsExactly("inherited");
  }

  public static class ThreeParameters {
    @JobHandler(type = "x")
    public void handle(Job job, Mail mail, String extra) {}
  }

  public static class TwoPayloads {
    @JobHandler(type = "x")
    public void handle(Mail mail, String other) {}
  }

  public static class TwoJobs {
    @JobHandler(type = "x")
    public void handle(Job job, Job other) {}
  }

  public static class ReturnsValue {
    @JobHandler(type = "x")
    public String handle(Mail mail) {
      return mail.to();
    }
  }

  public static class NotPublic {
    @JobHandler(type = "x")
    void handle(Mail mail) {}
  }

  public static class Static {
    @JobHandler(type = "x")
    public static void handle(Mail mail) {}
  }

  public static class GenericPayload {
    @JobHandler(type = "x")
    public void handle(List<Mail> mails) {}
  }

  public static class PrimitivePayload {
    @JobHandler(type = "x")
    public void handle(int count) {}
  }

  public static class BlankType {
    @JobHandler(type = " ")
    public void handle() {}
  }

  public static class SameTypeTwice {
    @JobHandler(type = "x")
    public void first() {}

    @JobHandler(type = "x")
    public void second() {}
  }

  public interface MailApi {
    void send(Mail mail);
  }

  /** Implements an interface, so the class can be proxied with a JDK dynamic proxy. */
  public static class MailJobsApi implements MailApi {
    @JobHandler(type = "fallback-type")
    public void send(Mail mail) {}
  }

  @Test
  void scansAClassWithoutBindingIt() {
    List<AnnotatedJobHandler> handlers = JobHandlers.scan(ValidHandlers.class);

    assertThat(handlers)
        .extracting(AnnotatedJobHandler::type)
        .containsExactly("job", "job-and-payload", "none", "payload", "payload-and-job");
    assertThat(handlers).allMatch(handler -> !handler.isBound());
    assertThat(handlers)
        .allMatch(handler -> handler.method().getDeclaringClass() == ValidHandlers.class);
  }

  @Test
  void aJdkProxyHidesItsTargetClassesAnnotations() {
    MailApi proxy =
        (MailApi)
            java.lang.reflect.Proxy.newProxyInstance(
                MailApi.class.getClassLoader(),
                new Class<?>[] {MailApi.class},
                (ignored, ignored2, ignored3) -> null);

    assertThat(JobHandlers.scan(proxy.getClass())).as("the proxy class").isEmpty();
    assertThat(JobHandlers.scan(MailJobsApi.class))
        .extracting(AnnotatedJobHandler::type)
        .containsExactly("fallback-type");
    assertThat(JobHandlers.hasAnnotatedMethods(MailJobsApi.class)).isTrue();
    assertThat(JobHandlers.hasAnnotatedMethods(MailApi.class)).isFalse();
  }

  public static class MailJobsSub extends MailJobsApi {}

  @Test
  void bindingReturnsABoundCopyAndFindsTheMethodOnTheTargetClass() {
    AnnotatedJobHandler unbound = JobHandlers.scan(MailJobsApi.class).getFirst();

    AnnotatedJobHandler bound = unbound.bindTo(new MailJobsSub());

    assertThat(unbound.isBound()).as("the scanned handler is unchanged").isFalse();
    assertThat(bound.isBound()).isTrue();
    assertThat(bound.type()).isEqualTo(unbound.type());
    assertThat(bound.method()).isEqualTo(unbound.method());
  }

  public static class FailureHandlers {
    @JobHandler(type = "charge")
    public void charge(Mail mail) {}

    @JobFailureHandler(type = "charge")
    public void chargeFailed(FailedJob failed, Mail mail) {}

    @JobFailureHandler(type = "payload-and-failed")
    public void payloadAndFailed(Mail mail, FailedJob failed) {}

    @JobFailureHandler(type = "none")
    public void none() {}
  }

  public static class FailureHandlersFirst {
    @JobFailureHandler(type = "a")
    public void aFailed() {}

    @JobFailureHandler(type = "b")
    public void bFailed() {}

    @JobHandler(type = "c")
    public void c() {}

    @JobHandler(type = "b")
    public void b() {}
  }

  @Test
  void findsFailureHandlersNextToJobHandlers() {
    List<AnnotatedJobHandler> handlers = JobHandlers.scan(FailureHandlers.class);

    assertThat(handlers)
        .extracting(handler -> handler.type() + (handler.isFailureHandler() ? " failed" : ""))
        .containsExactly("charge", "charge failed", "none failed", "payload-and-failed failed");
    assertThat(handlers.get(1).payloadType()).contains(Mail.class);
    assertThat(handlers.get(1).schedule()).isEmpty();
    // Job handlers first, then failure handlers, each ordered by type.
    assertThat(JobHandlers.scan(FailureHandlersFirst.class))
        .extracting(handler -> handler.type() + (handler.isFailureHandler() ? " failed" : ""))
        .containsExactly("b", "c", "a failed", "b failed");
    assertThat(JobHandlers.hasAnnotatedMethods(FailureHandlers.class)).isTrue();
  }

  public static class BothAnnotations {
    @JobHandler(type = "x")
    @JobFailureHandler(type = "x")
    public void handle() {}
  }

  public static class FailureHandlerWithJob {
    @JobFailureHandler(type = "x")
    public void handle(Job job) {}
  }

  public static class HandlerWithFailedJob {
    @JobHandler(type = "x")
    public void handle(FailedJob job) {}
  }

  public static class TwoFailedJobs {
    @JobFailureHandler(type = "x")
    public void handle(FailedJob job, FailedJob other) {}
  }

  public static class SameFailureTypeTwice {
    @JobFailureHandler(type = "x")
    public void first() {}

    @JobFailureHandler(type = "x")
    public void second() {}
  }

  @Test
  void rejectsInvalidFailureHandlerMethods() {
    assertThatThrownBy(() -> JobHandlers.scan(new BothAnnotations()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageEndingWith(
            "is annotated with both @JobHandler and @JobFailureHandler; use two methods");
    assertThatThrownBy(() -> JobHandlers.scan(new FailureHandlerWithJob()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("@JobFailureHandler method ")
        .hasMessageEndingWith("has a Job parameter; it gets the FailedJob");
    assertThatThrownBy(() -> JobHandlers.scan(new HandlerWithFailedJob()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageEndingWith("has a FailedJob parameter; it gets the Job");
    assertThatThrownBy(() -> JobHandlers.scan(new TwoFailedJobs()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageEndingWith("has two FailedJob parameters");
    assertThatThrownBy(() -> JobHandlers.scan(new SameFailureTypeTwice()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("Failed jobs of type 'x' are handled by two methods");
  }

  @Test
  void rejectsInvalidMethods() {
    assertInvalid(new ThreeParameters(), "has more than two parameters");
    assertInvalid(new TwoPayloads(), "has two payload parameters");
    assertInvalid(new TwoJobs(), "has two Job parameters");
    assertInvalid(new ReturnsValue(), "does not return void");
    assertInvalid(new NotPublic(), "is not public");
    assertInvalid(new Static(), "is static");
    assertInvalid(new GenericPayload(), "has a generic payload parameter");
    assertInvalid(new PrimitivePayload(), "has a primitive payload parameter");
    assertInvalid(new BlankType(), "has no job type");
    assertThatThrownBy(() -> JobHandlers.scan(new SameTypeTwice()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("Job type 'x' is handled by two methods");
  }

  private static void assertInvalid(Object target, String problem) {
    assertThatThrownBy(() -> JobHandlers.scan(target))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("@JobHandler method ")
        .hasMessageContaining(problem);
  }
}
