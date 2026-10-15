---
title: "47. Website hosted on Cloudflare Workers instead of static.app"
weight: 47
---

# 47. Website hosted on Cloudflare Workers instead of static.app

Date: 2026-10-15

## Status

Accepted. Supersedes [ADR 0035](0035-website-hosted-on-static-app.md).

## Context

[ADR 0035](0035-website-hosted-on-static-app.md) publishes `bikesched-website` to static.app: the
workflow zips the built site, POSTs it to static.app's API and purges static.app's CDN cache
afterwards. The project now hosts the site on Cloudflare instead, on the existing Worker
`bikesched-website`.

## Decision

bikesched deploys `bikesched-website` to the Cloudflare Worker `bikesched-website` as Workers static
assets, without a Worker script. `bikesched-website/wrangler.jsonc` names the Worker and points its
`assets.directory` at `build/hugo/publish/`, with `not_found_handling: "404-page"` so Hugo's
`404.html` answers unknown paths. `.github/workflows/website.yml` builds the site
(`./gradlew :bikesched-website:hugoBuild`) and runs `wrangler deploy` in `bikesched-website/`
through `cloudflare/wrangler-action`, authenticated with the `CLOUDFLARE_API_KEY` repository secret
(a Cloudflare API token allowed to edit Workers). The triggers are unchanged: push to `main`
touching `docs/**`, `README.md` or `bikesched-website/**`, plus `workflow_dispatch`; pull requests
are not deployed.

## Consequences

- The deploy is one step without hand-written API calls; Cloudflare serves the new version at once,
  so the separate cache purge of ADR 0035 is gone.
- The `STATIC_APP_API_KEY` secret and the static.app site are no longer used and can be removed.
- Wrangler takes the account from the API token; a token with access to several accounts needs a
  `CLOUDFLARE_ACCOUNT_ID` (or `account_id` in `wrangler.jsonc`) as well.
- `bikesched.org` is attached to the Worker as its custom domain (a one-time Cloudflare dashboard
  step), so the site serves at the domain `hugo.toml`'s `baseURL` already names.
