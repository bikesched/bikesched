// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.bikesched.Bikesched;
import org.bikesched.test.BikeschedTest;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The test database and a JDK {@link HttpServer} serving a {@link BikeschedAdmin} under {@code
 * /bikesched} on a random port (test-only).
 */
final class AdminServer implements AutoCloseable {

  /** One container for the tests of this module; Ryuk removes it when the JVM ends. */
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

  static final PGSimpleDataSource DATA_SOURCE = new PGSimpleDataSource();

  static {
    POSTGRES.start();
    DATA_SOURCE.setUrl(POSTGRES.getJdbcUrl());
    DATA_SOURCE.setUser(POSTGRES.getUsername());
    DATA_SOURCE.setPassword(POSTGRES.getPassword());
  }

  private static final HttpClient CLIENT =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  private final HttpServer server;

  private final URI base;

  private AdminServer(BikeschedAdmin admin) {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext("/bikesched", admin.httpHandler());
    server.start();
    base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/bikesched");
  }

  static AdminServer serve(BikeschedAdmin admin) {
    return new AdminServer(admin);
  }

  /** A node on the test database polling every 50 ms. */
  static Bikesched node(String prefix) {
    return BikeschedTest.testNode(DATA_SOURCE, prefix.replaceAll("-$", "")).build();
  }

  /** A path below the mount point, e.g. {@code /api/jobs}. */
  URI uri(String path) {
    return URI.create(base + path);
  }

  HttpResponse<String> get(String path, String... headers) {
    return send(HttpRequest.newBuilder(uri(path)).GET(), headers);
  }

  HttpResponse<String> post(String path, String... headers) {
    return send(
        HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody()), headers);
  }

  /** A POST with a form-encoded body, e.g. {@code name=a&key=b}. */
  HttpResponse<String> postForm(String path, String form, String... headers) {
    return send(
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)),
        headers);
  }

  HttpResponse<String> send(HttpRequest.Builder request, String... headers) {
    if (headers.length > 0) {
      request.headers(headers);
    }
    try {
      return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  static JsonObject json(HttpResponse<String> response) {
    try {
      return JsonParser.object().from(response.body());
    } catch (JsonParserException e) {
      throw new AssertionError("Not a JSON object: " + response.body(), e);
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
