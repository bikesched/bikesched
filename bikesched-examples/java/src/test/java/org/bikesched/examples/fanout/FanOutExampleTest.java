// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.fanout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.bikesched.Bikesched;
import org.bikesched.examples.fanout.FanOutExample.ImportedAlbum;
import org.bikesched.examples.fanout.FanOutExample.Thumbnail;
import org.bikesched.examples.util.Database;
import org.bikesched.jackson.JacksonJsonMapper;
import org.bikesched.job.JobState;
import org.junit.jupiter.api.Test;

/** Runs the fan-out example's jobs against a real PostgreSQL. */
class FanOutExampleTest {

  private static final DataSource DATA_SOURCE = Database.dataSource();

  private final ByteArrayOutputStream output = new ByteArrayOutputStream();

  @Test
  void importsAnAlbumWithOneChildJobPerPhoto() throws SQLException {
    try (Bikesched bikesched = node()) {
      var example =
          new FanOutExample(bikesched, new PrintStream(output, true, StandardCharsets.UTF_8));
      List<String> files = List.of("beach.jpg", "sunset.jpg", "notes.txt", "harbour.jpg");
      long start = System.nanoTime();

      UUID jobId = example.importAlbum("album-" + UUID.randomUUID(), files);

      await()
          .atMost(Duration.ofSeconds(30))
          .until(() -> bikesched.findJob(jobId).orElseThrow().state(), JobState::isEnded);
      Duration took = Duration.ofNanos(System.nanoTime() - start);
      assertThat(bikesched.findJob(jobId).orElseThrow().state()).isEqualTo(JobState.SUCCEEDED);
      ImportedAlbum result =
          bikesched.findJob(jobId).orElseThrow().result(ImportedAlbum.class).orElseThrow();
      assertThat(result.thumbnails())
          .extracting(Thumbnail::file)
          .containsExactly("beach.jpg", "sunset.jpg", "harbour.jpg");
      assertThat(result.skipped()).containsExactly("notes.txt");

      assertThat(count("parent_job_id = '" + jobId + "'")).as("one child per file").isEqualTo(4);
      assertThat(count("parent_job_id = '" + jobId + "' and state = 'FAILED'")).isEqualTo(1);
      assertThat(files).allSatisfy(file -> assertThat(example.images().calls(file)).isEqualTo(1));
      assertThat(took).as("children ran in parallel (1 s each)").isLessThan(Duration.ofSeconds(4));
      assertThat(output.toString(StandardCharsets.UTF_8))
          .contains("with 3 photos, skipped [notes.txt]");
    }
  }

  private static int count(String where) throws SQLException {
    try (var connection = DATA_SOURCE.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("select count(*) from bikesched_jobs where " + where)) {
      rows.next();
      return rows.getInt(1);
    }
  }

  private static Bikesched node() {
    return Bikesched.builder()
        .dataSource(DATA_SOURCE)
        .nodeName("fanout-" + UUID.randomUUID())
        .pollInterval(Duration.ofMillis(50))
        .jsonMapper(new JacksonJsonMapper())
        .build();
  }
}
