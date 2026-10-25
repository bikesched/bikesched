// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.fanout;

import static org.awaitility.Awaitility.await;

import java.io.PrintStream;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bikesched.Bikesched;
import org.bikesched.examples.util.BikeschedUtil;
import org.bikesched.job.JobResult;
import org.bikesched.job.JobState;
import org.bikesched.job.TypedJob;

/**
 * Fan-out/fan-in with child jobs: importing a photo album starts one child job per photo, which
 * resizes it, and waits for all of them before publishing the album. The children run in parallel,
 * on any node; the parent waits without holding a thread and decides what a failed child means
 * (here: the photo is skipped). Runs against the Postgres from {@code compose.yaml} ({@code docker
 * compose up -d}).
 *
 * <p>Run with {@code ./gradlew :bikesched-examples:java:runFanOut}; honors {@code POSTGRES_PORT}
 * like the compose file (default 5432).
 */
public final class FanOutExample {

  /** Imports an album: the parent job. */
  public static final TypedJob<Album> IMPORT_ALBUM = TypedJob.of("import-album", Album.class);

  /** Resizes one photo: the child job. */
  public static final TypedJob<Photo> RESIZE_PHOTO = TypedJob.of("resize-photo", Photo.class);

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

    // The child: resizes one photo and sets the thumbnail as the job's result. A file that is not
    // an image fails the job without retries; the parent receives the failure as an outcome.
    bikesched.registerJobHandler(
        RESIZE_PHOTO,
        (job, photo) ->
            images
                .resize(photo.albumId(), photo.file())
                .ifPresentOrElse(
                    job::complete,
                    () -> job.fail(photo.file() + " is not an image").remainingAttempts(0)));

    bikesched.registerJobHandler(
        IMPORT_ALBUM,
        (job, album) -> {
          // Fan out: one child per file. Each start is a durable step, so a replay of the parent
          // returns the same child instead of starting another one.
          List<UUID> children =
              album.files().stream()
                  .map(
                      file ->
                          job.newJob(RESIZE_PHOTO)
                              .payload(new Photo(album.albumId(), file))
                              .stepName("resize " + file)
                              .schedule())
                  .toList();

          // Fan in: wait for each child in turn. A child that already ended returns right away,
          // so the parent is done once the slowest child is.
          List<Thumbnail> thumbnails = new ArrayList<>();
          List<String> skipped = new ArrayList<>();
          for (int i = 0; i < children.size(); i++) {
            switch (job.waitForJob(children.get(i), Thumbnail.class)) {
              case JobResult.Succeeded<Thumbnail>(UUID ignored, Thumbnail thumbnail) ->
                  thumbnails.add(thumbnail);
              case JobResult.Failed<Thumbnail> ignored -> skipped.add(album.files().get(i));
              case JobResult<Thumbnail> other ->
                  throw new IllegalStateException("Unexpected outcome: " + other);
            }
          }

          job.step(
              "publish-album",
              () ->
                  out.printf(
                      "%s [album]   published %s with %d photos, skipped %s%n",
                      timestamp(), album.albumId(), thumbnails.size(), skipped));
          job.complete(new ImportedAlbum(thumbnails, skipped));
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
