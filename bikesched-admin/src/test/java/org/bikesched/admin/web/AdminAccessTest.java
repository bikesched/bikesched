// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.bikesched.Bikesched;
import org.bikesched.admin.web.access.AccessGrant;
import org.bikesched.admin.web.access.AccessLevel;
import org.bikesched.admin.web.access.AdminAccess;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** {@link AdminAccess}: open access, Basic authentication, custom implementations. */
class AdminAccessTest {

  private static final String OPS_PASSWORD = "ops-password-1";

  private static final String VIEWER_PASSWORD = "viewer-pw";

  private static Bikesched bikesched;

  private static BikeschedAdmin basicAuth;

  @BeforeAll
  static void startNode() {
    bikesched = AdminServer.node("admin-access-");
    basicAuth =
        BikeschedAdmin.of(bikesched)
            .access(
                AdminAccess.basicAuth()
                    .operator("ops", OPS_PASSWORD)
                    .viewer("support", VIEWER_PASSWORD)
                    .build());
  }

  @AfterAll
  static void stopNode() {
    bikesched.close();
  }

  @Test
  void openAccessAllowsEverything() {
    BikeschedAdmin admin = BikeschedAdmin.of(bikesched);
    JsonObject me = json(admin.handle(AdminRequest.of("GET", "/api/me", null)));
    assertThat(me.isNull("user")).isTrue();
    assertThat(me.getString("level")).isEqualTo("OPERATE");
    assertThat(me.getBoolean("payloadsShown")).isFalse();
    AdminResponse cancel = cancel(admin, null);
    assertThat(cancel.status()).isEqualTo(200);
    assertThat(json(cancel).getBoolean("cancelled")).isFalse(); // no such job
  }

  @Test
  void basicAuthChallengesRequestsWithoutValidCredentials() {
    AdminResponse anonymous = basicAuth.handle(AdminRequest.of("GET", "/", null));
    assertThat(anonymous.status()).isEqualTo(401);
    assertThat(anonymous.header("WWW-Authenticate"))
        .isEqualTo("Basic realm=\"bikesched admin\", charset=\"UTF-8\"");
    assertThat(get("/api/jobs", basic("ops", "wrong")).status()).isEqualTo(401);
    assertThat(get("/api/jobs", basic("ops", "ops-password-2")).status())
        .as("a wrong password of the right length")
        .isEqualTo(401);
    assertThat(get("/api/jobs", basic("nobody", OPS_PASSWORD)).status()).isEqualTo(401);
    assertThat(get("/api/jobs", "Basic not-base64!").status()).isEqualTo(401);
    assertThat(get("/api/jobs", "Bearer token").status()).isEqualTo(401);
  }

  @Test
  void viewersReadAndOperatorsAlsoChangeJobs() {
    JsonObject viewer = json(get("/api/me", basic("support", VIEWER_PASSWORD)));
    assertThat(viewer.getString("user")).isEqualTo("support");
    assertThat(viewer.getString("level")).isEqualTo("VIEW");
    assertThat(get("/api/jobs", basic("support", VIEWER_PASSWORD)).status()).isEqualTo(200);
    AdminResponse viewerCancel = cancel(basicAuth, basic("support", VIEWER_PASSWORD));
    assertThat(viewerCancel.status()).isEqualTo(403);
    assertThat(viewerCancel.bodyText()).contains("Viewers cannot change jobs");

    JsonObject operator = json(get("/api/me", basic("ops", OPS_PASSWORD)));
    assertThat(operator.getString("user")).isEqualTo("ops");
    assertThat(operator.getString("level")).isEqualTo("OPERATE");
    assertThat(cancel(basicAuth, basic("ops", OPS_PASSWORD)).status()).isEqualTo(200);
  }

  @Test
  void usersOfAnyNameLengthCanAct() {
    String longName = "o".repeat(250);
    BikeschedAdmin admin =
        BikeschedAdmin.of(bikesched)
            .access(AdminAccess.basicAuth().operator(longName, OPS_PASSWORD).build());
    assertThat(cancel(admin, basic(longName, OPS_PASSWORD)).status()).isEqualTo(200);

    // The history keeps the first 200 characters and 600 bytes of the name.
    assertThat(AdminHandler.actor(longName).name()).isEqualTo("o".repeat(200));
    assertThat(AdminHandler.actor("\uD83D\uDE00".repeat(200)).name())
        .isEqualTo("\uD83D\uDE00".repeat(150));
    assertThat(AdminHandler.actor("ops").name()).isEqualTo("ops");
    assertThat(AdminHandler.actor(" ")).isNull();
    assertThat(AdminHandler.actor(null)).isNull();
  }

  @Test
  void readOnlyTurnsOperatorsIntoViewers() {
    BikeschedAdmin readOnly = basicAuth.readOnly(true);
    AdminResponse me =
        readOnly.handle(
            AdminRequest.of("GET", "/api/me", null)
                .withHeader("Authorization", basic("ops", OPS_PASSWORD)));
    assertThat(json(me).getString("level")).isEqualTo("VIEW");
    assertThat(json(me).getBoolean("readOnly")).isTrue();
    assertThat(cancel(readOnly, basic("ops", OPS_PASSWORD)).status()).isEqualTo(403);
  }

  @Test
  void failedLoginsAreLoggedWithoutPasswords() {
    try (Logs logs = Logs.of("org.bikesched.admin.web.access.BasicAuthAccess")) {
      get("/api/jobs", basic("ops", "guessed-password"));
      get("/api/jobs", basic("ops", OPS_PASSWORD));
      get("/api/jobs", null);

      assertThat(logs.messages())
          .singleElement()
          .satisfies(
              message ->
                  assertThat(message)
                      .contains("Failed admin login of user ops from 10.0.0.1")
                      .doesNotContain("guessed-password", OPS_PASSWORD));
    }
  }

  @Test
  void basicAuthWarnsOnceOverPlainHttp() {
    try (Logs logs = Logs.of(ExchangeHandler.class.getName());
        var server = AdminServer.serve(basicAuth)) {
      server.get("/");
      server.get("/");
      assertThat(logs.messages())
          .singleElement()
          .satisfies(
              message -> assertThat(message).contains("Basic authentication over plain HTTP"));
    }
  }

  @Test
  void aCustomAccessWithoutChallengeAnswersForbidden() {
    AdminAccess byHeader =
        request ->
            "trusted".equals(request.header("X-Proxy-User"))
                ? new AccessGrant("trusted", AccessLevel.VIEW)
                : AccessGrant.none();
    BikeschedAdmin admin = BikeschedAdmin.of(bikesched).access(byHeader);
    assertThat(admin.handle(AdminRequest.of("GET", "/api/me", null)).status()).isEqualTo(403);
    assertThat(
            json(admin.handle(
                    AdminRequest.of("GET", "/api/me", null).withHeader("x-proxy-user", "trusted")))
                .getString("user"))
        .isEqualTo("trusted");
  }

  @Test
  void theBuilderRejectsInvalidUsers() {
    assertThatThrownBy(() -> AdminAccess.basicAuth().build())
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> AdminAccess.basicAuth().operator("", "pw"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AdminAccess.basicAuth().operator("a:b", "pw"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AdminAccess.basicAuth().operator("a", ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AdminAccess.basicAuth().operator("a", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AdminAccess.basicAuth().operator("a", "pw").viewer("a", "pw2"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static AdminResponse get(String path, String authorization) {
    AdminRequest request = AdminRequest.of("GET", path, null).withRemoteAddress("10.0.0.1");
    return basicAuth.handle(
        authorization == null ? request : request.withHeader("Authorization", authorization));
  }

  private static AdminResponse cancel(BikeschedAdmin admin, String authorization) {
    AdminRequest request =
        AdminRequest.of("POST", "/api/jobs/" + UUID.randomUUID() + "/cancel", null)
            .withHeader(AdminHandler.CSRF_HEADER, "1");
    return admin.handle(
        authorization == null ? request : request.withHeader("Authorization", authorization));
  }

  private static String basic(String user, String password) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  private static JsonObject json(AdminResponse response) {
    try {
      return JsonParser.object().from(response.bodyText());
    } catch (JsonParserException e) {
      throw new AssertionError("Not a JSON object: " + response.bodyText(), e);
    }
  }
}
