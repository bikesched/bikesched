// SPDX-License-Identifier: Apache-2.0
package org.bikesched.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grack.nanojson.JsonParser;
import java.time.Duration;
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
  void rejectsUnsupportedValuesInsteadOfStringifyingThem() {
    Object unsupported = new Object();
    assertThatThrownBy(() -> Json.write(unsupported))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported JSON value type: java.lang.Object");
    assertThatThrownBy(() -> Json.write(Json.object().put("nested", List.of(unsupported))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Json.write(Map.of("nested", unsupported)))
        .isInstanceOf(IllegalArgumentException.class);
  }

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
                + "\"raw\":{\"a\":[1,2]},\"rawNull\":null}");
  }

  @Test
  void escapesStrings() throws Exception {
    String value = "q\" b\\ n\n r\r t\t <tag> & \u0001 \u2028 \b \f \u2029 café 🚲 </script>";
    String json = Json.write(value);
    assertThat(JsonParser.any().from(json)).isEqualTo(value);
    assertThat(json).doesNotContain("\n", "\r", "\t", "\u0001", "\u2028", "\u2029", "</script>");
    assertThat(Json.write(Arrays.asList("x", null))).isEqualTo("[\"x\",null]");
  }

  @Test
  void normalizesNestedApiValues() {
    UUID id = UUID.fromString("7f0c2a1e-0000-4000-8000-000000000001");
    var nested =
        Json.object()
            .put("values", Arrays.asList(id, Duration.ofMillis(1250), DisplayState.READY, null))
            .put("count", Long.MAX_VALUE);
    assertThat(Json.write(Map.of("nested", List.of(nested))))
        .isEqualTo(
            "{\"nested\":[{\"values\":[\""
                + id
                + "\",\"PT1.25S\",\"READY\",null],\"count\":9223372036854775807}]}");
  }

  @Test
  void embedsRawValuesWithoutQuotingOrLosingNumericPrecision() {
    var values =
        Arrays.asList(
            new Json.Raw("[true, null, {\"value\": \"x\"}]"),
            new Json.Raw("\"a\\nstring\""),
            new Json.Raw("false"),
            new Json.Raw("null"),
            new Json.Raw(null),
            new Json.Raw("123456789012345678901234567890"),
            new Json.Raw("0.123456789012345678901234567890"),
            new Json.Raw("1.234567890123456789e+1000"));
    assertThat(Json.write(values))
        .isEqualTo(
            "[[true,null,{\"value\":\"x\"}],\"a\\nstring\",false,null,null,"
                + "123456789012345678901234567890,0.123456789012345678901234567890,"
                + "1.234567890123456789e+1000]");
  }

  private enum DisplayState {
    READY;

    @Override
    public String toString() {
      return "Ready to run";
    }
  }
}
