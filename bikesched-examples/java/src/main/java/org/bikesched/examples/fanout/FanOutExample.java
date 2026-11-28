// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.fanout;

import static org.awaitility.Awaitility.await;

import java.io.PrintStream;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bikesched.Bikesched;
import org.bikesched.examples.util.BikeschedUtil;
import org.bikesched.job.JobBatchResult;
import org.bikesched.job.JobState;
import org.bikesched.job.ResultJob;
import org.bikesched.job.TypedJob;

/**
 * Fan-out/fan-in with a job batch: importing a photo album starts one child job per photo as one
 * batch, which resizes it, and waits once for all of them before publishing the album. The children
 * run in parallel, on any node, at most two at a time; the parent waits without holding a thread
 * and decides what a failed child means (here: the photo is skipped). Runs against the Postgres
 * from {@code compose.yaml} ({@code docker compose up -d}).
 *
 * <p>Run with {@code ./gradlew :bikesched-examples:java:runFanOut}; honors {@code POSTGRES_PORT}
 * like the compose file (default 5432).
 */
public final class FanOutExample {

  /** Imports an album: the parent job. */
  public static final TypedJob<Album> IMPORT_ALBUM = TypedJob.of("import-album", Album.class);

  /** Resizes one photo: the child job, returning its thumbnail. */
  public static final ResultJob<Photo, Thumbnail> RESIZE_PHOTO =
      TypedJob.of("resize-photo", Photo.class, Thumbnail.class);

  /**
   * The parent's payload.
   *
   * @param albumId the album
   * @param files the uploaded files
   */
  public record Album(String albumId, List<String> files) {}

  /**
   * A child's payload.
   *
   * @param albumId the album
   * @param file the file to resize
   */
  public record Photo(String albumId, String file) {}

  /**
   * A child's result.
   *
   * @param file the resized file
   * @param url where the thumbnail is stored
   */
  public record Thumbnail(String file, String url) {}

  /**
   * The parent's result.
   *
   * @param thumbnails the thumbnails of the published album
   * @param skipped the files that could not be resized
   */
  public record ImportedAlbum(List<Thumbnail> thumbnails, List<String> skipped) {}

  private static final DateTimeFormatter TIMESTAMP_FORMAT =
      DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

  /**
   * The current time, formatted for the example's console output.
   *
   * @return the formatted time
   */
  static String timestamp() {
    return LocalTime.now().format(TIMESTAMP_FORMAT);
  }

  private final Bikesched bikesched;
  private final SimulatedImageService images;

  FanOutExample(Bikesched bikesched, PrintStream out) {
    this.bikesched = bikesched;
    this.images = new SimulatedImageService(out);

    // The child: resizes one photo and returns the thumbnail as the job's result. A file that is
    // not an image fails the job without retries; the parent receives the failure as an outcome.
    bikesched.registerResultJobHandler(
        RESIZE_PHOTO,
        (job, photo) -> {
          Optional<Thumbnail> thumbnail = images.resize(photo.albumId(), photo.file());
          if (thumbnail.isEmpty()) {
            job.fail(photo.file() + " is not an image").remainingAttempts(0);
          }
          return thumbnail.orElse(null);
        });

    bikesched.registerJobHandler(
        IMPORT_ALBUM,
        (job, album) -> {
          // Fan out: one child per file, keyed by the file, as one durable step: a replay of the
          // parent returns the same batch instead of starting another one. At most two photos
          // are resized at a time; the others wait as HELD jobs.
          JobBatchResult<Thumbnail> resized =
              job.newJobBatch("resize", RESIZE_PHOTO)
                  .addAll(album.files(), file -> file, file -> new Photo(album.albumId(), file))
                  .parallelism(2)
                  .schedule()
                  // Fan in: the parent waits once, and resumes when every photo is done.
                  .awaitResult();

          ImportedAlbum imported;
          switch (resized) {
            case JobBatchResult.Succeeded<Thumbnail> all ->
                imported = new ImportedAlbum(all.results(), List.of());
            case JobBatchResult.Failed.Incomplete<Thumbnail> partial ->
                imported =
                    new ImportedAlbum(
                        List.copyOf(partial.succeeded().values()),
                        List.copyOf(partial.unsucceeded().keySet()));
            case JobBatchResult.Failed<Thumbnail> failed -> {
              job.fail("No photo could be resized: " + failed.unsucceeded().keySet())
                  .remainingAttempts(0);
              return;
            }
          }

          job.step(
              "publish-album",
              () ->
                  out.printf(
                      "%s [album]   published %s with %d photos, skipped %s%n",
                      timestamp(),
                      album.albumId(),
                      imported.thumbnails().size(),
                      imported.skipped()));
          job.complete(imported);
        });
  }

  /**
   * Schedules importing an album.
   *
   * @param albumId the album
   * @param files the uploaded files
   * @return the parent's job id
   */
  public UUID importAlbum(String albumId, List<String> files) {
    return bikesched.newJob(IMPORT_ALBUM).payload(new Album(albumId, files)).schedule();
  }

  /** The image service, e.g. to count its calls. */
  SimulatedImageService images() {
    return images;
  }

  static void main() {
    try (Bikesched bikesched = BikeschedUtil.bikesched("fanout-example")) {
      var example = new FanOutExample(bikesched, System.out);

      UUID jobId =
          example.importAlbum(
              "summer-2026", List.of("beach.jpg", "sunset.jpg", "notes.txt", "harbour.jpg"));

      JobState state =
          await()
              .atMost(Duration.ofSeconds(30))
              .until(() -> bikesched.findJob(jobId).orElseThrow().state(), JobState::isEnded);
      ImportedAlbum result = bikesched.findJob(jobId).orElseThrow().result(ImportedAlbum.class);
      System.out.printf("%s [app]     job %s ended %s: %s%n", timestamp(), jobId, state, result);
    }
  }
}
