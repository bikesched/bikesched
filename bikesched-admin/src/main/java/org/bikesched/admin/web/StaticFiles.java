// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The page's files from the module's resources ({@code org/bikesched/admin/web/ui/}), read once and
 * cached. Only plain names with a known extension are looked up, so no request reaches other
 * resources.
 */
final class StaticFiles {

  /** A file and its content type. */
  record File(byte[] content, String contentType) {}

  private static final Pattern NAME =
      Pattern.compile("[a-z0-9][a-z0-9-]*(/[a-z0-9][a-z0-9.-]*)*\\.(html|css|js|txt|svg)");

  private static final Map<String, String> CONTENT_TYPES =
      Map.of(
          "html", "text/html; charset=utf-8",
          "css", "text/css; charset=utf-8",
          "js", "text/javascript; charset=utf-8",
          "txt", "text/plain; charset=utf-8",
          "svg", "image/svg+xml");

  private final Map<String, Optional<File>> cache = new ConcurrentHashMap<>();

  /**
   * Looks up a file.
   *
   * @param name the path below {@code ui/}, e.g. {@code app.js} or {@code vendor/htm.module.js}
   * @return the file; empty if there is none or the name is not allowed
   */
  Optional<File> find(String name) {
    if (!NAME.matcher(name).matches() || name.contains("..")) {
      return Optional.empty();
    }
    return cache.computeIfAbsent(name, StaticFiles::load);
  }

  private static Optional<File> load(String name) {
    try (InputStream in = StaticFiles.class.getResourceAsStream("ui/" + name)) {
      if (in == null) {
        return Optional.empty();
      }
      String extension = name.substring(name.lastIndexOf('.') + 1);
      return Optional.of(new File(in.readAllBytes(), CONTENT_TYPES.get(extension)));
    } catch (IOException e) {
      throw new UncheckedIOException("Reading admin UI file " + name + " failed", e);
    }
  }
}
