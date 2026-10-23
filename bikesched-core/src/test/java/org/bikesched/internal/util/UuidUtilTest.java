// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidUtilTest {

  @Test
  void idsAreVersion7WithTheCurrentTime() {
    long before = System.currentTimeMillis();
    UUID id = UuidUtil.timeOrdered();
    long after = System.currentTimeMillis();

    assertThat(id.version()).isEqualTo(7);
    assertThat(id.variant()).isEqualTo(2);
    assertThat(id.getMostSignificantBits() >>> 16).isBetween(before, after + 1);
  }

  @Test
  void idsIncreaseAlsoWithinAMillisecond() {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 10_000; i++) {
      ids.add(UuidUtil.timeOrdered());
    }
    // PostgreSQL compares uuids byte by byte, i.e. as unsigned numbers, like their hex text.
    for (int i = 1; i < ids.size(); i++) {
      assertThat(ids.get(i).toString()).isGreaterThan(ids.get(i - 1).toString());
    }
  }
}
