/**
 * Database access of the bikesched implementation: the stores ({@code JobStore}, {@code StepStore},
 * {@code EventStore}, {@code NodeStore}) with their JDBC statements, the {@code Sql} handle and
 * connection/transaction handling ({@code DatabaseAccess}), and schema migration on first use. Not
 * exported by the module; not part of the API.
 */
package bikesched.core.internal.database;
