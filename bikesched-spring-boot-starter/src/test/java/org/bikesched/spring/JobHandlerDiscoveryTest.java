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
import org.bikesched.annotation.JobHandler;
import org.bikesched.job.Job;
import org.bikesched.job.JobState;
import org.bikesched.job.JobType;
import org.bikesched.job.JobTypeHandler;
import org.bikesched.job.Recurrence;
import org.bikesched.job.RecurrenceParser;
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

/** Discovery of {@code @JobHandler} methods and {@link JobTypeHandler} beans on the context. */
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
              await().until(() -> node.findJob(jobId).orElseThrow().state(), JobState::isEnded);
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
                await().until(() -> node.findJob(id).orElseThrow().state(), JobState::isEnded);
              }
              assertThat(Signatures.handled)
                  .containsExactlyInAnyOrder("both:b", "payload:p", "none", "job:" + job);
            });
  }

  @Test
  void registersJobTypeHandlerBeans() {
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
              await().until(() -> node.findJob(jobId).orElseThrow().state(), JobState::isEnded);
              assertThat(ReportHandler.handled).containsExactly("weekly");
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
              await().until(() -> node.findJob(jobId).orElseThrow().state(), JobState::isEnded);
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
              await().until(() -> node.findJob(jobId).orElseThrow().state(), JobState::isEnded);
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

    @JobHandler(type = "millis-job", schedule = "every 100 ms")
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

    @JobHandler(type = "discover-mail")
    public void sendMail(Job job, Mail mail) {
      handled.add(mail.to());
    }
  }

  /** A recurring handler using Spring's six-field cron format. */
  public static class CronJobs {

    public static final List<String> handled = new CopyOnWriteArrayList<>();

    @JobHandler(type = "cron-job", schedule = "*/1 * * * * *")
    public void handle() {
      handled.add("ran");
    }
  }

  /** The four supported {@code @JobHandler} signatures. */
  public static class Signatures {

    public record Item(String value) {}

    public static final List<String> handled = new CopyOnWriteArrayList<>();

    @JobHandler(type = "sig-both")
    public void both(Job job, Item item) {
      handled.add("both:" + item.value());
    }

    @JobHandler(type = "sig-job")
    public void job(Job job) {
      handled.add("job:" + job.id());
    }

    @JobHandler(type = "sig-payload")
    public void payload(Item item) {
      handled.add("payload:" + item.value());
    }

    @JobHandler(type = "sig-none")
    public void none() {
      handled.add("none");
    }
  }

  /** A class-per-handler bean registered via {@link JobTypeHandler}. */
  public static class ReportHandler implements JobTypeHandler<ReportHandler.Report> {

    public record Report(String name) {}

    public static final List<String> handled = new CopyOnWriteArrayList<>();

    @Override
    public JobType<Report> type() {
      return JobType.of("type-handler-report", Report.class);
    }

    @Override
    public void handle(Job job, Report report) {
      handled.add(report.name());
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
    @JobHandler(type = "tx-proxy")
    @Transactional
    public void place(Job job, Order order) {
      transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
    }
  }

  /** Two beans handling the same job type. */
  public static class DupA {
    @JobHandler(type = "dup-type")
    public void handle() {}
  }

  public static class DupB {
    @JobHandler(type = "dup-type")
    public void handle() {}
  }

  /** A handler with a signature {@link JobHandler} rejects. */
  public static class InvalidSignature {
    @JobHandler(type = "invalid-sig")
    public String handle(Job job) {
      return "not void";
    }
  }

  /** A lazy bean: its handler registers only once the bean is created. */
  public static class LazyJobs {

    public record Item(String value) {}

    public static final List<String> handled = new CopyOnWriteArrayList<>();

    @JobHandler(type = "lazy-handler")
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
