package org.bikesched.kotlin

import java.time.Duration
import java.util.UUID
import javax.sql.DataSource
import org.awaitility.Awaitility
import org.bikesched.Bikesched
import org.bikesched.JsonMapper
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.KotlinModule

/** One PostgreSQL container per test JVM, shared by all test classes; Ryuk removes it. */
object TestDatabase {

  private val postgres = PostgreSQLContainer("postgres:18").apply { start() }

  val dataSource: DataSource =
    PGSimpleDataSource().apply {
      setUrl(postgres.jdbcUrl)
      user = postgres.username
      password = postgres.password
    }

  init {
    Awaitility.setDefaultTimeout(Duration.ofSeconds(10))
    Awaitility.setDefaultPollInterval(Duration.ofMillis(50))
    Awaitility.setDefaultPollDelay(Duration.ZERO)
  }
}

/** Maps Kotlin data classes with Jackson and its Kotlin module. */
object JacksonJsonMapper : JsonMapper {

  private val mapper: ObjectMapper =
    tools.jackson.databind.json.JsonMapper.builder()
      .addModule(KotlinModule.Builder().build())
      .build()

  override fun toJson(value: Any): String = mapper.writeValueAsString(value)

  override fun <T : Any?> fromJson(json: String, type: Class<T>): T = mapper.readValue(json, type)
}

/** A polling node on the test database with a unique name, mapping payloads with Jackson. */
fun testNode(name: String): Bikesched = bikesched {
  dataSource = TestDatabase.dataSource
  nodeName = "$name-${UUID.randomUUID()}"
  jsonMapper = JacksonJsonMapper
}

/** A unique job type or event name per test run: the database is shared with other tests. */
fun unique(name: String): String = "$name-${UUID.randomUUID()}"
