// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.annotation.JobHandler;
import org.bikesched.job.Job;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The startup warning about {@code @Transactional} handlers using the default propagation. */
class TransactionWarningTest extends AbstractSpringTest {

  private static final String WARNING_LOGGER = "org.bikesched.spring.TransactionPropagationWarning";

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BikeschedAutoConfiguration.class));
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  @Test
  void warnsAboutADefaultPropagationHandler() {
    try (Logs logs = Logs.of(WARNING_LOGGER)) {
      runner()
          .withBean("dataSource", DataSource.class, () -> dataSource())
          .withBean("txJobs", TxDefaultJobs.class, TxDefaultJobs::new)
          .withPropertyValues("bikesched.node-name=" + unique("warn-node"))
          .run(context -> assertThat(context).hasSingleBean(Bikesched.class));
      assertThat(logs.messages()).singleElement().asString().contains("handle", "REQUIRES_NEW");
    }
  }

  @Test
  void warnsAboutAClassLevelTransactionalHandler() {
    try (Logs logs = Logs.of(WARNING_LOGGER)) {
      runner()
          .withBean("dataSource", DataSource.class, () -> dataSource())
          .withBean("txClassJobs", TxClassLevelJobs.class, TxClassLevelJobs::new)
          .withPropertyValues("bikesched.node-name=" + unique("class-warn-node"))
          .run(context -> assertThat(context).hasSingleBean(Bikesched.class));
      assertThat(logs.messages()).singleElement().asString().contains("handle", "REQUIRES_NEW");
    }
  }

  @Test
  void isSilentForAnExplicitPropagation() {
    try (Logs logs = Logs.of(WARNING_LOGGER)) {
      runner()
          .withBean("dataSource", DataSource.class, () -> dataSource())
          .withBean("txExplicitJobs", TxExplicitJobs.class, TxExplicitJobs::new)
          .withPropertyValues("bikesched.node-name=" + unique("explicit-node"))
          .run(context -> assertThat(context).hasSingleBean(Bikesched.class));
      assertThat(logs.messages()).isEmpty();
    }
  }

  public static class TxDefaultJobs {
    @JobHandler(name = "tx-warn")
    @Transactional
    public void handle(Job job, String payload) {}
  }

  @Transactional
  public static class TxClassLevelJobs {
    @JobHandler(name = "tx-warn-class")
    public void handle(Job job, String payload) {}
  }

  public static class TxExplicitJobs {
    @JobHandler(name = "tx-explicit")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(Job job, String payload) {}
  }
}
