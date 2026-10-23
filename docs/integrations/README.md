---
title: "Integrations"
weight: 5
---

# Integrations

Optional modules on top of `bikesched-core`; pull in only the ones you use.

- [Spring Boot](spring.md): the auto-configured node (`bikesched-spring-boot-starter`)
- [Kotlin](kotlin.md): the `bikesched-kotlin` extensions
- [Annotations](annotations.md): job handlers as `@JobHandler` methods (`bikesched-annotations`)
- [Testing](testing.md): test nodes, job wait helpers and time skipping (`bikesched-test`)

Three more modules are described where they are used: JSON mapping with Jackson
(`bikesched-jackson`) in [Configuration](../getting-started/configuration.md#jackson), cron
expressions (`bikesched-cron`) in [Recurring jobs](../jobs/recurring-jobs.md) and the Micrometer
meters (`bikesched-micrometer`) in [Metrics](../operations/metrics.md).
