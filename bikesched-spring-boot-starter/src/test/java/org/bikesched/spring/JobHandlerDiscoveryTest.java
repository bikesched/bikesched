// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.annotation.JobFailureHandler;
import org.bikesched.annotation.JobHandler;
import org.bikesched.job.FailedJob;
import org.bikesched.job.Job;
import org.bikesched.job.JobState;
import org.bikesched.job.Recurrence;
import org.bikesched.job.RecurrenceParser;
import org.bikesched.job.StandaloneJobFailureHandler;
import org.bikesched.job.StandaloneJobHandler;
import org.bikesched.job.TypedJob;
import org.bikesched.test.BikeschedTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Discovery of {@code @JobHandler} methods and {@link StandaloneJobHandler} beans on the context.
 */
class JobHandlerDiscoveryTest extends AbstractSpringTest {

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BikeschedAutoConfiguration.class));
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  @Test
  void handlesAJobEndToEnd() {
    String nodeName = unique("discover-node");
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withBean("mailJobs", MailJobs.class, MailJobs::new)
        .withPropertyValues("bikesched.node-name=" + nodeName)
        .run(
            context -> {
              Bikesched node = context.getBean(Bikesched.class);
              UUID jobId =
                  node.newJob("discover-mail")
                      .payload(new MailJobs.Mail("alice@example.com"))
                      .schedule();
              BikeschedTest.awaitJob(node, jobId).untilEnded();
              assertThat(MailJobs.handled).containsExactly("alice@example.com");
            });
  }

  @Test
  void supportsAllHandlerSignatures() {
    Signatures.handled.clear();
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withBean("signatures", Signatures.class, Signatures::new)
        .withPropertyValues("bikesched.node-name=" + unique("signatures-node"))
        .run(
            context -> {
              Bikesched node = context.getBean(Bikesched.class);
              UUID both = node.newJob("sig-both").payload(new Signatures.Item("b")).schedule();
              UUID job = node.newJob("sig-job").schedule();
              UUID payload =
                  node.newJob("sig-payload").payload(new Signatures.Item("p")).schedule();
              UUID none = node.newJob("sig-none").schedule();
              for (UUID id : List.of(both, job, payload, none)) {
                BikeschedTest.awaitJob(node, id).untilEnded();
              }
              assertThat(Signatures.handled)
                  .containsExactlyInAnyOrder("both:b", "payload:p", "none", "job:" + job);
            });
  }

  @Test
  void registersStandaloneJobHandlerBeans() {
    ReportHandler.handled.clear();
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withBean("reports", ReportHandler.class, ReportHandler::new)
        .withPropertyValues("bikesched.node-name=" + unique("type-handler-node"))
        .run(
            context -> {
              Bikesched node = context.getBean(Bikesched.class);
              UUID jobId =
                  node.newJob("type-handler-report")
                      .payload(new ReportHandler.Report("weekly"))
                      .schedule();
              BikeschedTest.awaitJob(node, jobId).untilEnded();
              assertThat(ReportHandler.handled).containsExactly("weekly");
            });
  }

  @Test
  void registersFailureHandlerMethodsAndStandaloneJobFailureHandlerBeans() {
    PaymentJobs.handled.clear();
    InvoiceHandler.handled.clear();
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withBean("paymentJobs", PaymentJobs.class, PaymentJobs::new)
        .withBean("invoices", InvoiceHandler.class, InvoiceHandler::new)
        .withPropertyValues("bikesched.node-name=" + unique("failure-handler-node"))
        .run(
            context -> {
              Bikesched node = context.getBean(Bikesched.class);
              UUID charge =
                  node.newJob("discover-charge")
                      .payload(new PaymentJobs.Charge("o1"))
                      .maxAttempts(1)
                      .schedule();
              UUID invoice =
                  node.newJob(InvoiceHandler.TYPE)
                      .payload(new InvoiceHandler.Invoice("i1"))
                      .maxAttempts(1)
                      .schedule();
              for (UUID id : List.of(charge, invoice)) {
                BikeschedTest.awaitJob(node, id).untilState(JobState.FAILED);
              }
              assertThat(PaymentJobs.handled).containsExactly("o1");
              assertThat(InvoiceHandler.handled).containsExactly("i1");
            });
  }

  @Test
  void invokesAJdkProxiedTransactionalHandlerThroughTheProxy() {
    invokeAProxiedHandler(JdkProxyConfig.class, "jdk-node");
  }

  @Test
  void invokesACglibProxiedTransactionalHandlerThroughTheProxy() {
    invokeAProxiedHandler(CglibProxyConfig.class, "cglib-node");
  }

  private void invokeAProxiedHandler(Class<?> config, String nodePrefix) {
    TransactionalOrderHandler.transactionActive.set(false);
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withUserConfiguration(config)
        .withPropertyValues("bikesched.node-name=" + unique(nodePrefix))
        .run(
            context -> {
              Bikesched node = context.getBean(Bikesched.class);
              UUID jobId =
                  node.newJob("tx-proxy")
                      .payload(new TransactionalOrderHandler.Order("o1"))
                      .schedule();
              BikeschedTest.awaitJob(node, jobId).untilEnded();
              assertThat(TransactionalOrderHandler.transactionActive)
                  .as("the handler runs through the proxy, with an active transaction")
                  .isTrue();
            });
  }

  @Test
  void failsOnDuplicateJobTypesAcrossBeans() {
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withBean("dupA", DupA.class, DupA::new)
        .withBean("dupB", DupB.class, DupB::new)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(anyCauseMessage(context.getStartupFailure(), "dup-type")).isTrue();
              assertThat(anyCauseMessage(context.getStartupFailure(), "already registered"))
                  .isTrue();
            });
  }

  @Test
  void failsOnAnInvalidHandlerSignature() {
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withBean("invalid", InvalidSignature.class, InvalidSignature::new)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(anyCauseMessage(context.getStartupFailure(), "does not return void"))
                  .isTrue();
            });
  }

  @Test
  void registersALazyBeansHandlerOnceItIsCreated() {
    LazyJobs.handled.clear();
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withBean(
            "lazyJobs", LazyJobs.class, LazyJobs::new, definition -> definition.setLazyInit(true))
        .withPropertyValues("bikesched.node-name=" + unique("lazy-node"))
        .run(
            context -> {
              Bikesched node = context.getBean(Bikesched.class);
              context.getBean(LazyJobs.class); // created after startup: the handler registers now
              UUID jobId = node.newJob("lazy-handler").payload(new LazyJobs.Item("x")).schedule();
              BikeschedTest.awaitJob(node, jobId).untilEnded();
              assertThat(LazyJobs.handled).containsExactly("x");
            });
  }

  @Test
  void registersCronScheduledHandlers() {
    CronJobs.handled.clear();
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withBean("cronJobs", CronJobs.class, CronJobs::new)
        .withPropertyValues("bikesched.node-name=" + unique("cron-node"))
        .run(context -> await().untilAsserted(() -> assertThat(CronJobs.handled).isNotEmpty()));
  }

  @Test
  void addsRecurrenceParserBeansToTheBuiltInParsers() {
    MillisJobs.handled.clear();
    CronJobs.handled.clear();
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withBean("millisJobs", MillisJobs.class, MillisJobs::new)
        .withBean("cronJobs", CronJobs.class, CronJobs::new)
        .withBean("millisParser", RecurrenceParser.class, MillisJobs::parser)
        .withPropertyValues("bikesched.node-name=" + unique("parser-node"))
        .run(
            context ->
                await()
                    .untilAsserted(
                        () -> {
                          assertThat(MillisJobs.handled).isNotEmpty();
                          assertThat(CronJobs.handled).isNotEmpty();
                        }));
  }

  /** A recurring handler with a schedule format of a {@link RecurrenceParser} bean. */
  public static class MillisJobs {

    public static final List<String> handled = new CopyOnWriteArrayList<>();

    @JobHandler(name = "millis-job", schedule = "every 100 ms")
    public void handle() {
      handled.add("ran");
    }

    static RecurrenceParser parser() {
      return new RecurrenceParser() {
        @Override
        public boolean supports(String expression) {
          return expression.equals("every 100 ms");
        }

        @Override
        public Recurrence parse(String expression) {
          return Recurrence.every(Duration.ofMillis(100));
        }
      };
    }
  }

  /** A {@code @JobHandler} bean discovered and invoked with a record payload. */
  public static class MailJobs {

    public static final List<String> handled = new CopyOnWriteArrayList<>();

    public record Mail(String to) {}

    @JobHandler(name = "discover-mail")
    public void sendMail(Job job, Mail mail) {
      handled.add(mail.to());
    }
  }

  /** A recurring handler using Spring's six-field cron format. */
  public static class CronJobs {

    public static final List<String> handled = new CopyOnWriteArrayList<>();

    @JobHandler(name = "cron-job", schedule = "*/1 * * * * *")
    public void handle() {
      handled.add("ran");
    }
  }

  /** The four supported {@code @JobHandler} signatures. */
  public static class Signatures {

    public record Item(String value) {}

    public static final List<String> handled = new CopyOnWriteArrayList<>();

    @JobHandler(name = "sig-both")
    public void both(Job job, Item item) {
      handled.add("both:" + item.value());
    }

    @JobHandler(name = "sig-job")
    public void job(Job job) {
      handled.add("job:" + job.id());
    }

    @JobHandler(name = "sig-payload")
    public void payload(Item item) {
      handled.add("payload:" + item.value());
    }

    @JobHandler(name = "sig-none")
    public void none() {
      handled.add("none");
    }
  }

  /** A class-per-handler bean registered via {@link StandaloneJobHandler}. */
  public static class ReportHandler implements StandaloneJobHandler<ReportHandler.Report> {

    public record Report(String name) {}

    public static final List<String> handled = new CopyOnWriteArrayList<>();

    @Override
    public TypedJob<Report> typedJob() {
      return TypedJob.of("type-handler-report", Report.class);
    }

    @Override
    public void handle(Job job, Report report) {
      handled.add(report.name());
    }
  }

  /** A bean with a job handler that always fails and its {@code @JobFailureHandler}. */
  public static class PaymentJobs {

    public record Charge(String orderId) {}

    public static final List<String> handled = new CopyOnWriteArrayList<>();

    @JobHandler(name = "discover-charge")
    public void charge(Charge charge) {
      throw new IllegalStateException("declined");
    }

    @JobFailureHandler(name = "discover-charge")
    public void chargeFailed(FailedJob failed, Charge charge) {
      handled.add(charge.orderId());
    }
  }

  /** A class-per-handler bean with its failure handler, registered via both interfaces. */
  public static class InvoiceHandler
      implements StandaloneJobHandler<InvoiceHandler.Invoice>,
          StandaloneJobFailureHandler<InvoiceHandler.Invoice> {

    public record Invoice(String id) {}

    static final TypedJob<Invoice> TYPE =
        TypedJob.of("type-failure-handler-invoice", Invoice.class);

    public static final List<String> handled = new CopyOnWriteArrayList<>();

    @Override
    public TypedJob<Invoice> typedJob() {
      return TYPE;
    }

    @Override
    public void handle(Job job, Invoice invoice) {
      throw new IllegalStateException("cannot invoice");
    }

    @Override
    public void handle(FailedJob failed, Invoice invoice) {
      handled.add(invoice.id());
    }
  }

  /** A transactional handler behind a JDK or CGLIB proxy. */
  public interface OrderApi {
    void place(Job job, TransactionalOrderHandler.Order order);
  }

  public static class TransactionalOrderHandler implements OrderApi {

    public record Order(String id) {}

    public static final AtomicBoolean transactionActive = new AtomicBoolean();

    @Override
    @JobHandler(name = "tx-proxy")
    @Transactional
    public void place(Job job, Order order) {
      transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
    }
  }

  /** Two beans handling the same job name. */
  public static class DupA {
    @JobHandler(name = "dup-type")
    public void handle() {}
  }

  public static class DupB {
    @JobHandler(name = "dup-type")
    public void handle() {}
  }

  /** A handler with a signature {@link JobHandler} rejects. */
  public static class InvalidSignature {
    @JobHandler(name = "invalid-sig")
    public String handle(Job job) {
      return "not void";
    }
  }

  /** A lazy bean: its handler registers only once the bean is created. */
  public static class LazyJobs {

    public record Item(String value) {}

    public static final List<String> handled = new CopyOnWriteArrayList<>();

    @JobHandler(name = "lazy-handler")
    public void handle(Item item) {
      handled.add(item.value());
    }
  }

  @Configuration
  @EnableTransactionManagement(proxyTargetClass = false)
  static class JdkProxyConfig {
    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    TransactionalOrderHandler handler() {
      return new TransactionalOrderHandler();
    }
  }

  @Configuration
  @EnableTransactionManagement(proxyTargetClass = true)
  static class CglibProxyConfig {
    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    TransactionalOrderHandler handler() {
      return new TransactionalOrderHandler();
    }
  }
}
