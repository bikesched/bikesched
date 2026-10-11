// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@link BikeschedAdmin#httpHandler()}: translates an {@link HttpExchange}. */
final class ExchangeHandler implements HttpHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ExchangeHandler.class);

  private final BikeschedAdmin admin;

  private final boolean sendsPasswords;

  private final AtomicBoolean warned = new AtomicBoolean();

  ExchangeHandler(BikeschedAdmin admin, boolean sendsPasswords) {
    this.admin = admin;
    this.sendsPasswords = sendsPasswords;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      try (InputStream body = exchange.getRequestBody()) {
        body.transferTo(OutputStream.nullOutputStream()); // the API reads no request bodies
      }
      if (sendsPasswords
          && !(exchange instanceof HttpsExchange)
          && warned.compareAndSet(false, true)) {
        LOG.warn(
            "The bikesched admin uses Basic authentication over plain HTTP: passwords are sent"
                + " unencrypted unless a proxy in front of it terminates TLS");
      }
      String context = exchange.getHttpContext().getPath();
      String path = exchange.getRequestURI().getRawPath();
      String relative = path.startsWith(context) ? path.substring(context.length()) : path;
      if (context.endsWith("/")) {
        relative = "/" + relative;
      }
      if (relative.isEmpty()) {
        exchange.getResponseHeaders().set("Location", context + "/");
        exchange.sendResponseHeaders(308, -1);
        return;
      }
      if (!relative.startsWith("/")) { // e.g. /bikesched-other for the context /bikesched
        exchange.sendResponseHeaders(404, -1);
        return;
      }
      Map<String, String> headers = new LinkedHashMap<>();
      exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, values.getFirst()));
      var request =
          new AdminRequest(
              exchange.getRequestMethod(),
              relative,
              exchange.getRequestURI().getRawQuery(),
              headers,
              exchange.getRemoteAddress().getAddress().getHostAddress());
      AdminResponse response = admin.handle(request);
      response.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
      boolean empty = response.body().length == 0;
      exchange.sendResponseHeaders(response.status(), empty ? -1 : response.body().length);
      if (!empty) {
        try (OutputStream out = exchange.getResponseBody()) {
          out.write(response.body());
        }
      }
    }
  }
}
