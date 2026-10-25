// SPDX-License-Identifier: Apache-2.0
package org.bikesched.internal.node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultNodeTest {

  @Test
  void pollsFromRegistrationUntilClosed() {
    var node = new DefaultNode("node-a");
    assertThat(node.id()).isNull();
    assertThat(node.isPolling()).isFalse();

    UUID id = UUID.randomUUID();
    node.registered(id);
    assertThat(node.id()).isEqualTo(id);
    assertThat(node.isPolling()).isTrue();
    assertThat(node).hasToString("Node[name=node-a, id=" + id + "]");
    assertThatThrownBy(() -> node.registered(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("bikesched node node-a is already registered");

    node.requireOpen();
    assertThat(node.close()).isTrue();
    assertThat(node.close()).as("only the first call").isFalse();
    assertThat(node.isPolling()).isFalse();
    assertThat(node.id()).as("kept after closing").contains(id);
    assertThatThrownBy(node::requireOpen)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("bikesched node node-a is closed");
  }
}
