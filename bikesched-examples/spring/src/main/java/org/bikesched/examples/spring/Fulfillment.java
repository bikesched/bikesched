// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.spring;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** A fulfillment of an {@link Order}, referenced by its id only (no foreign key). */
@Entity
@Table(name = "fulfillments")
public class Fulfillment {

  @Id private UUID id;

  private UUID orderId;

  protected Fulfillment() {}

  public Fulfillment(UUID id, UUID orderId) {
    this.id = id;
    this.orderId = orderId;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrderId() {
    return orderId;
  }
}
