package org.bikesched.internal.database.schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A single versioned SQL migration loaded from the classpath.
 *
 * @param version strictly increasing version number
 * @param description human-readable description stored in the history table
 * @param resource classpath location of the SQL script
 */
record Migration(int version, String description, String resource) {

  Migration {
    if (version < 1) {
      throw new IllegalArgumentException("version must be >= 1: " + version);
    }
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(resource, "resource");
  }

  /** Loads the script; line endings are normalized so checksums are platform independent. */
  String sql() {
    try (InputStream in = Migration.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new SchemaMigrationException("Migration script not found on classpath: " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read migration script " + resource, e);
    }
  }

  /** SHA-256 of the script, used to detect edits of already applied migrations. */
  String checksum() {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(sql().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
