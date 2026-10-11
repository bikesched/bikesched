// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bikesched.job.JobState;
import org.junit.jupiter.api.Test;

/** The API's JSON writer. */
class JsonTest {

  @Test
  void writesValuesInOrder() {
    UUID id = UUID.fromString("7f0c2a1e-0000-4000-8000-000000000001");
    String json =
        Json.object()
            .put("id", id)
            .put("at", Instant.parse("2026-10-11T12:00:00.123456Z"))
            .put("state", JobState.FAILED)
            .put("count", 3)
            .put("ok", true)
            .put("none", null)
            .put("list", List.of(1, "two"))
            .put("map", Map.of("k", "v"))
            .put("raw", new Json.Raw("{\"a\": [1, 2]}"))
            .put("rawNull", new Json.Raw(null))
            .toString();
    assertThat(json)
        .isEqualTo(
            "{\"id\":\""
                + id
                + "\",\"at\":\"2026-10-11T12:00:00.123456Z\",\"state\":\"FAILED\",\"count\":3,"
                + "\"ok\":true,\"none\":null,\"list\":[1,\"two\"],\"map\":{\"k\":\"v\"},"
                + "\"raw\":{\"a\": [1, 2]},\"rawNull\":null}");
  }

  @Test
  void escapesStrings() {
    assertThat(Json.write("q\" b\\ n\n r\r t\t <tag> & \u0001 \u2028"))
        .isEqualTo("\"q\\\" b\\\\ n\\n r\\r t\\t \\u003ctag\\u003e \\u0026 \\u0001 \\u2028\"");
    assertThat(Json.write(Arrays.asList("x", null))).isEqualTo("[\"x\",null]");
  }
}
