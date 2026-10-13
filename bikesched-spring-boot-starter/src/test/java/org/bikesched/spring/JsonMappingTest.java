// SPDX-License-Identifier: Apache-2.0
package org.bikesched.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.JsonMapper;
import org.bikesched.annotation.JobHandler;
import org.bikesched.jackson.JacksonJsonMapper;
import org.bikesched.job.Job;
import org.bikesched.test.BikeschedTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** JSON mapping: the automatic Jackson mapper and a user-defined one. */
class JsonMappingTest extends AbstractSpringTest {

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BikeschedAutoConfiguration.class));
  }

  private static String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  @Test
  void mapsARecordPayloadWithJackson() {
    JsonJobs.handled.clear();
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withBean("jsonJobs", JsonJobs.class, JsonJobs::new)
        .withPropertyValues("bikesched.node-name=" + unique("json-node"))
        .run(
            context -> {
              Bikesched node = context.getBean(Bikesched.class);
              UUID jobId = node.newJob("json-record").payload(new JsonJobs.Item("v", 7)).schedule();
              BikeschedTest.awaitJob(node, jobId).untilEnded();
              assertThat(JsonJobs.handled).containsExactly("v:7");
            });
  }

  @Test
  void aUserDefinedJsonMapperWins() {
    RecordingMapper user = new RecordingMapper();
    runner()
        .withBean("dataSource", DataSource.class, () -> dataSource())
        .withBean(JsonMapper.class, () -> user)
        .withBean("jsonJobs", JsonJobs.class, JsonJobs::new)
        .withPropertyValues("bikesched.node-name=" + unique("user-mapper-node"))
        .run(
            context -> {
              Bikesched node = context.getBean(Bikesched.class);
              assertThat(context.getBean(JsonMapper.class)).isSameAs(user);
              UUID jobId = node.newJob("json-record").payload(new JsonJobs.Item("v", 7)).schedule();
              BikeschedTest.awaitJob(node, jobId).untilEnded();
              assertThat(user.fromJsonCalls.get()).as("the user mapper was used").isPositive();
            });
  }

  public static class JsonJobs {

    public record Item(String name, int quantity) {}

    public static final List<String> handled = new CopyOnWriteArrayList<>();

    @JobHandler(type = "json-record")
    public void handle(Job job, Item item) {
      handled.add(item.name() + ":" + item.quantity());
    }
  }

  /** A {@link JsonMapper} recording that it was used, delegating to Jackson. */
  private static final class RecordingMapper implements JsonMapper {

    final AtomicInteger fromJsonCalls = new AtomicInteger();
    final JacksonJsonMapper delegate = new JacksonJsonMapper();

    @Override
    public String toJson(Object value) {
      return delegate.toJson(value);
    }

    @Override
    public <T> T fromJson(String json, Class<T> type) {
      fromJsonCalls.incrementAndGet();
      return delegate.fromJson(json, type);
    }
  }
}
