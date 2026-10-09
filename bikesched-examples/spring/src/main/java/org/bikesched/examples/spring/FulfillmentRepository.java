// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.spring;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FulfillmentRepository extends JpaRepository<Fulfillment, UUID> {}
