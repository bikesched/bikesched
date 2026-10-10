// SPDX-License-Identifier: Apache-2.0
package org.bikesched.examples.kotlin.shop

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.Json
import org.bikesched.Bikesched
import org.bikesched.kotlin.bikesched
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ShopServer")

/** Shared JSON configuration for the REST API and order persistence. */
val ShopJson: Json = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
}

/**
 * Creates the HikariCP data source for the PostgreSQL database.
 *
 * Reads connection parameters from environment variables, defaulting to the docker compose setup.
 */
fun createDataSource(): DataSource {
  val host = System.getenv("POSTGRES_HOST") ?: "localhost"
  val port = System.getenv("POSTGRES_PORT") ?: "5432"
  val database = System.getenv("POSTGRES_DB") ?: "bikesched"
  val user = System.getenv("POSTGRES_USER") ?: "bikesched"
  val password = System.getenv("POSTGRES_PASSWORD") ?: "bikesched"

  val jdbcUrl = "jdbc:postgresql://$host:$port/$database"
  log.info("Connecting to PostgreSQL at {}", jdbcUrl)

  val config = HikariConfig()
  config.jdbcUrl = jdbcUrl
  config.username = user
  config.password = password
  config.maximumPoolSize = 10
  config.minimumIdle = 2
  config.poolName = "shop-pool"

  return HikariDataSource(config)
}

/**
 * Initializes the Exposed database connection and creates the schema.
 */
fun initDatabase(dataSource: DataSource) {
  Database.connect(dataSource)
  transaction {
    SchemaUtils.createMissingTablesAndColumns(Items, Orders, OrderItems, OrderPaymentOptions)
    log.info("Database schema initialized")
  }
  FixtureItems.load()
}

/**
 * Creates and configures the bikesched instance.
 */
fun createBikesched(dataSource: DataSource) = bikesched {
  this.dataSource = dataSource
  nodeName = "shop-node"
  maxConcurrentJobs = 5
  pollInterval = 100.milliseconds
  jsonMapper = KotlinxSerializationJsonMapper()
}.also {
  it.registerShopHandlers()
  log.info("Bikesched node started: {}", it.node().name())
}

/**
 * The shop application: database, bikesched node and Ktor server with the REST API.
 *
 * @param port the HTTP port
 */
class ShopServer(private val port: Int = 8080) : AutoCloseable {
  private var dataSource: DataSource? = null
  private var bikesched: Bikesched? = null
  private var server: EmbeddedServer<*, *>? = null

  /** Initializes the schema and fixtures, starts the bikesched node and the HTTP server. */
  fun start(wait: Boolean = false) {
    val dataSource = createDataSource().also { this.dataSource = it }
    initDatabase(dataSource)

    val bikesched = createBikesched(dataSource).also { this.bikesched = it }

    server =
      embeddedServer(Netty, port = port, host = "0.0.0.0") {
          install(ContentNegotiation) { json(ShopJson) }

          routing {
            shopRoutes(bikesched)

            get("/health") {
              call.respond(mapOf("status" to "ok", "node" to bikesched.node().name()))
            }
          }
        }
        .start(wait = wait)
  }

  override fun close() {
    server?.stop(500, 1000)
    bikesched?.close()
    (dataSource as? AutoCloseable)?.close()
  }
}
