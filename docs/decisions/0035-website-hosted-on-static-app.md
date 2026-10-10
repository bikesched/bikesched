---
title: "35. Website hosted on static.app instead of GitHub Pages"
weight: 35
---

# 35. Website hosted on static.app instead of GitHub Pages

Date: 2026-10-10

## Status

Accepted. Narrows [the website spec](../specs/website-project-site.md)'s §4.6, which planned
GitHub Pages.

## Context

The website spec planned deploying `bikesched-website` to GitHub Pages under the custom domain
`bikesched.org`, built and published by a GitHub Actions workflow
(`actions/configure-pages`/`upload-pages-artifact`/`deploy-pages`). GitHub Pages with a custom
domain and enforced HTTPS needs a paid plan (GitHub Pro) for a private repository, or the
repository has to go public first (open question in the spec) — a billing and visibility decision
outside the scope of shipping a website.

static.app is a hosted static-site API: one POST with a zip archive deploys or updates a site
(`POST /v1/sites/zip`), authenticated with a personal API key, independent of the repository's
visibility or GitHub's own Pages feature.

## Decision

bikesched publishes `bikesched-website` to static.app instead of GitHub Pages.
`.github/workflows/website.yml` builds the Hugo site
(`./gradlew :bikesched-website:hugoBuild`), zips `build/hugo/publish/` with its contents at the
archive root, and POSTs it to `https://api.static.app/v1/sites/zip` for the pre-created site
(`pid` `le3rpst1ob`), authenticated with the `STATIC_APP_API_KEY` repository secret as an
`--oauth2-bearer` token. It runs on push to `main` touching `docs/**`, `README.md` or
`bikesched-website/**`, plus `workflow_dispatch` for manual runs; pull requests are not deployed.
A `purge-cache` call follows every publish, since static.app's CDN otherwise caches assets for up
to 4 hours (`cache-control: max-age=14400`), which would leave a deploy's new files invisible for
that long.

The site currently serves from the default `https://bikesched.staticdomains.app` subdomain;
`hugo.toml`'s `baseURL` still targets the eventual `https://bikesched.org/` domain, which is not
yet pointed at static.app.

## Consequences

- Deployment no longer depends on making the repository public or buying GitHub Pro.
- A new secret (`STATIC_APP_API_KEY`) and an external account (static.app) are now part of the
  deploy path, outside GitHub's own infrastructure.
- The custom domain still needs to be configured on static.app and its DNS pointed there before
  `bikesched.org` actually serves the site — a one-time dashboard/DNS step, not automated.
- Pull requests get no deployed preview, only a local `hugoBuild` check (unchanged from the
  original plan).
