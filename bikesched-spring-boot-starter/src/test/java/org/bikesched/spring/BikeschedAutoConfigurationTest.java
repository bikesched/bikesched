// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The conditions of {@link BikeschedAutoConfiguration} and the {@code bikesched.*} properties. */
class BikeschedAutoConfigurationTest extends AbstractSpringTest {

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BikeschedAutoConfiguration.class));
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  @Test
  void backsOffWithoutADataSource() {
    runner().run(context -> assertThat(context).doesNotHaveBean(Bikesched.class));
  }

  @Test
  void createsAPollingNodeWhenADataSourceExists() {
    String nodeName = unique("conditions-node");
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withPropertyValues("bikesched.node-name=" + nodeName)
        .run(
            context -> {
              assertThat(context).hasSingleBean(Bikesched.class);
              Bikesched node = context.getBean(Bikesched.class);
              assertThat(node.node().name()).isEqualTo(nodeName);
              assertThat(node.node().isPolling()).isTrue();
            });
  }

  @Test
  void doesNotPollWhenPollingIsDisabled() {
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withPropertyValues(
            "bikesched.node-name=" + unique("conditions-node"),
            "bikesched.auto-start.enabled=false")
        .run(context -> assertThat(context.getBean(Bikesched.class).node().isPolling()).isFalse());
  }

  @Test
  void aUserDefinedNodeWins() {
    String nodeName = unique("user-node");
    try (Bikesched userNode =
        Bikesched.builder(dataSource()).nodeName(nodeName).autoStartPolling(false).build()) {
      runner()
          .withBean("dataSource", DataSource.class, () -> dataSource())
          .withPropertyValues("bikesched.auto-start.enabled=false")
          .withBean("bikesched", Bikesched.class, () -> userNode)
          .run(context -> assertThat(context.getBean(Bikesched.class)).isSameAs(userNode));
    }
  }

  @Test
  void appliesCustomizersAfterTheProperties() {
    String nodeName = unique("customized-node");
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withBean(
            BikeschedCustomizer.class,
            () -> (BikeschedCustomizer) builder -> builder.nodeName(nodeName))
        .run(
            context ->
                assertThat(context.getBean(Bikesched.class).node().name()).isEqualTo(nodeName));
  }

  @Test
  void bindsPropertiesWithRelaxedNamesAndDurationSuffixes() {
    String nodeName = unique("properties-node");
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withPropertyValues(
            "bikesched.node-name=" + nodeName,
            "bikesched.poll-interval=100ms",
            "bikesched.listen-for-due-jobs=false",
            "bikesched.notify-interval=250ms",
            "bikesched.max-concurrent-jobs=20",
            "bikesched.heartbeat-interval=10s",
            "bikesched.node-timeout=60s",
            "bikesched.retry-backoff.fixed=2s",
            "bikesched.failure-handler-attempts=5",
            "bikesched.event-retention=3d")
        .run(
            context -> {
              BikeschedProperties properties = context.getBean(BikeschedProperties.class);
              assertThat(properties.nodeName()).isEqualTo(nodeName);
              assertThat(properties.pollInterval()).isEqualTo(Duration.ofMillis(100));
              assertThat(properties.listenForDueJobs()).isFalse();
              assertThat(properties.notifyInterval()).isEqualTo(Duration.ofMillis(250));
              assertThat(properties.maxConcurrentJobs()).isEqualTo(20);
              assertThat(properties.heartbeatInterval()).isEqualTo(Duration.ofSeconds(10));
              assertThat(properties.nodeTimeout()).isEqualTo(Duration.ofSeconds(60));
              assertThat(properties.retryBackoff().fixed()).isEqualTo(Duration.ofSeconds(2));
              assertThat(properties.failureHandlerAttempts()).isEqualTo(5);
              assertThat(properties.eventRetention()).isEqualTo(Duration.ofDays(3));
            });
  }

  @Test
  void rejectsARetryBackoffThatSetsBothFixedAndExponential() {
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withPropertyValues(
            "bikesched.retry-backoff.fixed=1s",
            "bikesched.retry-backoff.exponential.initial=1s",
            "bikesched.retry-backoff.exponential.max=2s")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(
                      anyCauseMessage(
                          context.getStartupFailure(),
                          "set either fixed or" + " exponential, not both"))
                  .isTrue();
            });
  }

  @Test
  void rejectsANodeTimeoutNotLongerThanTheHeartbeatInterval() {
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withPropertyValues("bikesched.heartbeat-interval=5s", "bikesched.node-timeout=5s")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(
                      anyCauseMessage(
                          context.getStartupFailure(),
                          "must be at least twice the heartbeatInterval"))
                  .isTrue();
            });
  }

  @Test
  void failsWithSeveralDataSourcesAndNoneSelected() {
    runner()
        .withBean("ds1", DataSource.class, () -> dataSource())
        .withBean("ds2", DataSource.class, () -> dataSource())
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(anyCauseMessage(context.getStartupFailure(), "2 DataSource beans"))
                  .isTrue();
              assertThat(anyCauseMessage(context.getStartupFailure(), "bikesched.data-source"))
                  .isTrue();
            });
  }

  @Test
  void usesThePrimaryDataSourceAmongSeveral() {
    RecordingDataSource primary = new RecordingDataSource(dataSource());
    RecordingDataSource secondary = new RecordingDataSource(dataSource());
    runner()
        .withBean("ds1", DataSource.class, () -> primary, definition -> definition.setPrimary(true))
        .withBean("ds2", DataSource.class, () -> secondary)
        .withPropertyValues("bikesched.node-name=" + unique("primary-node"))
        .run(
            context -> {
              assertThat(context).hasSingleBean(Bikesched.class);
              assertThat(primary.used()).isTrue();
              assertThat(secondary.used()).isFalse();
            });
  }

  @Test
  void usesTheDataSourceNamedByTheProperty() {
    RecordingDataSource primary = new RecordingDataSource(dataSource());
    RecordingDataSource named = new RecordingDataSource(dataSource());
    runner()
        .withBean("ds1", DataSource.class, () -> primary, definition -> definition.setPrimary(true))
        .withBean("ds2", DataSource.class, () -> named)
        .withPropertyValues(
            "bikesched.data-source=ds2", "bikesched.node-name=" + unique("named-node"))
        .run(
            context -> {
              assertThat(context).hasSingleBean(Bikesched.class);
              assertThat(named.used()).isTrue();
              assertThat(primary.used()).isFalse();
            });
  }

  @Test
  void failsWhenThePropertyNamesAMissingBean() {
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withPropertyValues("bikesched.data-source=nope")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(anyCauseMessage(context.getStartupFailure(), "no bean with that name"))
                  .isTrue();
            });
  }

  @Test
  void failsWhenThePropertyNamesANonDataSourceBean() {
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withBean("notADataSource", String.class, () -> "nope")
        .withPropertyValues("bikesched.data-source=notADataSource")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(anyCauseMessage(context.getStartupFailure(), "not a DataSource")).isTrue();
            });
  }

  /** A {@link DataSource} delegating to a real one, recording whether it was used. */
  private static final class RecordingDataSource implements DataSource {

    private final DataSource delegate;
    private volatile boolean used;

    RecordingDataSource(DataSource delegate) {
      this.delegate = delegate;
    }

    boolean used() {
      return used;
    }

    @Override
    public java.sql.Connection getConnection() throws java.sql.SQLException {
      used = true;
      return delegate.getConnection();
    }

    @Override
    public java.sql.Connection getConnection(String username, String password)
        throws java.sql.SQLException {
      used = true;
      return delegate.getConnection(username, password);
    }

    @Override
    public java.io.PrintWriter getLogWriter() throws java.sql.SQLException {
      return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(java.io.PrintWriter out) throws java.sql.SQLException {
      delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws java.sql.SQLException {
      delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws java.sql.SQLException {
      return delegate.getLoginTimeout();
    }

    @Override
    public java.util.logging.Logger getParentLogger()
        throws java.sql.SQLFeatureNotSupportedException {
      return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws java.sql.SQLException {
      return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws java.sql.SQLException {
      return delegate.isWrapperFor(iface);
    }
  }
}
