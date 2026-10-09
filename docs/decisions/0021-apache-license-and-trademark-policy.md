---
title: "21. Apache License 2.0 with a trademark policy"
weight: 21
---

# 21. Apache License 2.0 with a trademark policy

Date: 2026-10-06

## Status

Accepted

## Context

bikesched had no license, so nobody could legally use it. The goals: users and companies embed the
library in any product, open or closed source, without license review friction, while the name
bikesched stays with the project, so forks and products cannot present themselves as bikesched.

Alternatives considered:

- **MIT or BSD**: just as easy to embed, but without a patent grant and without a word on
  trademarks, leaving the name's status open.
- **LGPL**: relinking and modification terms that many companies avoid, especially for shaded or
  fat-jar Java deployments.
- **MPL 2.0 or EPL 2.0**: file-level copyleft; changes to bikesched's files must be published,
  extra compliance work for embedders.
- **Source-available licenses (BSL and similar)**: restrict commercial use instead of the name and
  are not open source.

The runtime dependencies are compatible with any of these: PostgreSQL JDBC (BSD-2-Clause),
`slf4j-api` (MIT), Jackson, Micrometer and the Kotlin stdlib (Apache 2.0).

## Decision

bikesched is licensed under the Apache License 2.0 (`LICENSE`), with a `NOTICE` file carrying the
copyright and trademark notice. Apache 2.0 is permissive, includes a patent grant and explicitly
grants no trademark rights (section 6).

The name and logo are covered by `TRADEMARKS.md`: describing a product as using or being based on
bikesched is allowed; naming a fork, product or company bikesched, publishing modified artifacts
under `org.bikesched`, or using the logo in a way that suggests endorsement needs permission.

Source files carry an `SPDX-License-Identifier: Apache-2.0` header, added and checked by Spotless.
The published POMs declare the license. Contributions are accepted under the same license
(inbound = outbound, Apache 2.0 section 5); there is no CLA.

## Consequences

- Companies can embed bikesched without legal review beyond the usual Apache 2.0 approval, and
  license scanners recognize it from the POM and the SPDX headers.
- Redistributors must keep `LICENSE` and `NOTICE`, so the attribution and trademark note travel
  with the code.
- The trademark policy relies on the name being used consistently; registering the mark (e.g. at
  the DPMA or EUIPO) would make it enforceable beyond unregistered rights and is a separate step.
- Without a CLA, relicensing later needs the consent of all contributors.
