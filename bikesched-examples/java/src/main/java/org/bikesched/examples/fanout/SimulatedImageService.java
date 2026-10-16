// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.fanout;

import java.io.PrintStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.bikesched.examples.fanout.FanOutExample.Thumbnail;

/**
 * An image service resizing photos, simulated in memory: each call takes a second, so the output
 * shows the children running in parallel. Files that are not {@code .jpg} are not images.
 */
final class SimulatedImageService {

  private final PrintStream out;
  private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

  SimulatedImageService(PrintStream out) {
    this.out = out;
  }

  /**
   * Resizes a photo.
   *
   * @param albumId the album
   * @param file the file
   * @return the thumbnail, or empty if the file is not an image
   * @throws InterruptedException if interrupted while resizing
   */
  Optional<Thumbnail> resize(String albumId, String file) throws InterruptedException {
    calls.computeIfAbsent(file, ignored -> new AtomicInteger()).incrementAndGet();
    out.printf("%s [images]  resizing %s%n", FanOutExample.timestamp(), file);
    Thread.sleep(1000);
    if (!file.endsWith(".jpg")) {
      out.printf("%s [images]  %s is not an image%n", FanOutExample.timestamp(), file);
      return Optional.empty();
    }
    out.printf("%s [images]  resized %s%n", FanOutExample.timestamp(), file);
    return Optional.of(
        new Thumbnail(file, "https://img.example.com/" + albumId + "/thumb-" + file));
  }

  /** The number of calls for a file. */
  int calls(String file) {
    AtomicInteger count = calls.get(file);
    return count == null ? 0 : count.get();
  }
}
