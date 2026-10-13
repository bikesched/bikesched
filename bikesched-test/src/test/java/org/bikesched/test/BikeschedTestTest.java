// SPDX-License-Identifier: Apache-2.0
package org.bikesched.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import org.bikesched.Bikesched;
import org.junit.jupiter.api.Test;

/** Tests {@link BikeschedTest#testNode}. */
class BikeschedTestTest extends AbstractTestModuleTest {

  @Test
  void testNodeUsesTheGivenDataSourceAndAUniquePrefixedName() {
    try (Bikesched bikesched = BikeschedTest.testNode(DATA_SOURCE, "my-prefix").build()) {
      assertThat(bikesched.node().name()).startsWith("my-prefix-");
    }
  }

  @Test
  void testNodeWithoutAPrefixUsesTest() {
    try (Bikesched bikesched = BikeschedTest.testNode(DATA_SOURCE).build()) {
      assertThat(bikesched.node().name()).startsWith("test-");
    }
  }

  @Test
  void testNodeGeneratesAUniqueNameOnEveryCall() {
    var names = new HashSet<String>();
    try (Bikesched first = BikeschedTest.testNode(DATA_SOURCE, "unique").build();
        Bikesched second = BikeschedTest.testNode(DATA_SOURCE, "unique").build()) {
      names.add(first.node().name());
      names.add(second.node().name());
    }
    assertThat(names).hasSize(2);
  }
}
