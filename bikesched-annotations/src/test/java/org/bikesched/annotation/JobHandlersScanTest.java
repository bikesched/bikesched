// SPDX-License-Identifier: Apache-2.0
package org.bikesched.annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
                MailApi.class.getClassLoader(), new Class<?>[] {MailApi.class}, (_, _, _) -> null);

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
