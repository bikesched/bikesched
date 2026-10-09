// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** The version of this bikesched library, filled into {@code version.properties} by the build. */
public final class Version {

  /** Returned if the version cannot be determined. */
  public static final String UNKNOWN = "unknown";

  private static final String RESOURCE = "version.properties";

  private static final String VERSION = read();

  private Version() {}

  /**
   * Returns the library version, e.g. {@code 0.1.0} or {@code 0.2.0-SNAPSHOT}.
   *
   * @return the version, or {@value #UNKNOWN} if the resource is missing or was not filtered
   */
  public static String get() {
    return VERSION;
  }

  private static String read() {
    try (InputStream in = Version.class.getResourceAsStream("/org/bikesched/" + RESOURCE)) {
      if (in == null) {
        return UNKNOWN;
      }
      var properties = new Properties();
      properties.load(in);
      String version = properties.getProperty("version", UNKNOWN).trim();
      // An unfiltered resource (e.g. copied by an IDE) still holds the placeholder.
      return version.isEmpty() || version.startsWith("$") ? UNKNOWN : version;
    } catch (IOException e) {
      return UNKNOWN;
    }
  }
}
