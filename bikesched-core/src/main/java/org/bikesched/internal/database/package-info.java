// SPDX-License-Identifier: Apache-2.0
/**
 * Database access of the bikesched implementation: the stores ({@code JobStore}, {@code StepStore},
 * {@code EventStore}, {@code NodeStore}) with their JDBC statements, the {@code Sql} handle and
 * connection/transaction handling ({@code DatabaseAccess}), and schema migration on first use. Not
 * exported by the module; not part of the API.
 */
@NullMarked
package org.bikesched.internal.database;

import org.jspecify.annotations.NullMarked;
