---
title: "Project website (bikesched.org)"
---

# Specification: project website (bikesched.org)

Status: **implemented** — generation (module `bikesched-website`) and deployment to the Cloudflare
Worker `green-paper-4e4f` (§4.6,
[ADR 0047](../decisions/0047-website-hosted-on-cloudflare-workers.md), which superseded static.app
from [ADR 0035](../decisions/0035-website-hosted-on-static-app.md)) both done, and `bikesched.org`
serves the site as the Worker's custom domain.

## 1. Goal

A public project website at `bikesched.org`, built from this repository without duplicating
content: the home page is the project's `README.md`, the docs section is `docs/*.md`, and both
stay in sync with the repository automatically (no separate copy to keep up to date, no second
place to edit).

## 2. Non-goals

- A blog, changelog or news section: the pitch, docs and ADRs are enough for now.
- Versioned documentation (multiple released versions side by side): the project is unreleased
  (`0.1.0-SNAPSHOT`), so there is only ever one version to document.
- Search, multi-language content: the theme (§3) supports both, but nothing in the project needs
  them yet.

## 3. Module and dependencies

New Gradle module `bikesched-website`, registered in the root `settings.gradle.kts`, applying the
[`gradle-hugo-plugin`](https://github.com/fstaudt/gradle-hugo-plugin) (`io.github.fstaudt.hugo`,
version in `gradle/libs.versions.toml`, like every other dependency). The plugin downloads a
pinned Hugo binary and exposes a `hugoBuild` task; no other build-time dependency. The module has
no Java/Kotlin sources, only `site/` (Hugo's working directory: `hugo.toml`, `content/`,
`layouts/`, `assets/`, `themes/`).

The [Hextra](https://github.com/imfing/hextra) theme (§4.2) is a **git submodule** at
`bikesched-website/site/themes/hextra`, pinned to a release tag — not a Hugo Module (which would
need a local Go toolchain the project otherwise has no reason to require).

## 4. Design

### 4.1 Generator: Hugo via a Gradle plugin

Spiked first against [Roq](https://github.com/quarkiverse/quarkus-roq) (a Quarkus extension), the
originally proposed option in the superseded IDEAS.md entry. Rejected: Roq's one-shot site
generation only runs by starting the packaged application with a system property
(`quarkus.roq.generator.batch=true`) and letting it exit; wiring that into a single reusable
Gradle task repeatedly produced a hung live server instead of a build step (the property has to
reach a JVM Gradle's `quarkusRun` task forks, which `systemProperty(...)` on the task does not do
reliably). Jekyll (GitHub Pages' native generator) and MkDocs were not spiked: both need a Ruby or
Python toolchain, while this is otherwise a pure Java/Gradle project.

Hugo needs neither a JVM-hostile runtime model nor an extra language toolchain: the plugin
downloads a single static binary and runs it as a Gradle task
(`./gradlew :bikesched-website:hugoBuild`, cacheable, ~0.3s once Hugo is downloaded), which is
exactly the "single Gradle task" the original idea asked for.

### 4.2 Theme: Hextra

[Hextra](https://github.com/imfing/hextra) was chosen over
[PaperMod](https://github.com/adityatelange/hugo-PaperMod) (tried first): PaperMod is a
blog/personal-site theme retrofitted with a docs mode, while Hextra is built for documentation
sites from the start — automatic sidebar navigation from the content tree, breadcrumbs, a
table of contents, and native rendering of GitHub-style alerts (`> [!NOTE]`, …) matching the theme's
own design, all without custom templates. PaperMod needed several project-level template
overrides to get acceptable docs behavior (dropping its broken OpenGraph/Twitter/Schema.org
partials on the pinned release, a custom alert render hook and its CSS, docs-specific `single.html`
and `list.html`) that became unnecessary with Hextra (only the "no duplicate title" override, §4.5,
survived the switch, since both themes render the front-matter title as their own `<h1>` above the
Markdown content).

### 4.3 Reusing README.md and docs/ without copying

Hugo [module mounts](https://gohugo.io/hugo-modules/configuration/#module-mounts) map paths
outside the module into its `content/` tree at build time, unlike a file copy: `hugo.toml` mounts
`../../README.md` to `content/_index.md` (the home page) and `../../docs` to `content/docs` (the
docs section), excluding `docs/specs/**` (internal, per `AGENTS.md`) and remounting
`docs/index.md` and `docs/decisions/README.md` individually to the `_index.md` name Hugo expects
for a section's landing page. Nothing under `bikesched-website/` duplicates project content.

### 4.4 Markdown compatibility

- **Relative links** between the project's Markdown files (`jobs.md#job-history`) must resolve
  both on GitHub and on the site. A project-level `render-link.html` hook rewrites a relative link
  that resolves to a mounted page to its site URL, and a relative link that does not (e.g.
  README's module table linking to a module directory) to the equivalent GitHub tree URL — so
  `README.md`'s own links stay plain, relative, and GitHub-native; only the site's rendering is
  theme/hook-specific.
- **GitHub-only Markdown** (raw HTML, GitHub Alerts `> [!NOTE]`) needed `markup.goldmark.unsafe =
  true` (raw HTML) and is otherwise handled natively by Hextra's own alert rendering (§4.2) — no
  project-level hook needed for alerts.
- **Front matter**: `docs/*.md` and `README.md` carry a `title` (from each file's first heading,
  added once) so the site's `<title>` tag and navigation labels don't depend on template-side
  heading parsing, and a `weight` on each top-level docs page matching the reading order in
  `docs/index.md`'s "Contents" list (the sidebar is alphabetical by default otherwise). Front
  matter is invisible on GitHub.

### 4.5 Template overrides

Three small overrides on top of Hextra, kept to the minimum needed:

- `layouts/home.html`, `layouts/docs/single.html`, `layouts/docs/list.html`: copies of the theme's
  own templates with the `<h1>{{ .Title }}</h1>` block removed, because the mounted content already
  has its own Markdown heading (GitHub needs it; Hugo would otherwise render the title twice).
- `layouts/_markup/render-link.html` (§4.4).
- `[[menu.sidebar]]` entry linking back to `/docs/` (Hextra's sidebar only lists a section's
  children, not the section's own landing page).

### 4.6 Deployment: Cloudflare Workers

Narrowed from the original IDEAS.md entry down to this one remaining piece, then changed from the
originally planned GitHub Pages to static.app ([ADR 0035](../decisions/0035-website-hosted-on-static-app.md)):
GitHub Pages with a custom domain and enforced HTTPS needs a paid plan for a private repository, or
the repository going public first. Later moved from static.app to Cloudflare
([ADR 0047](../decisions/0047-website-hosted-on-cloudflare-workers.md)).

`.github/workflows/website.yml` builds `./gradlew :bikesched-website:hugoBuild` and runs
`wrangler deploy` in `bikesched-website/` (`cloudflare/wrangler-action`), authenticated with the
`CLOUDFLARE_API_KEY` repository secret. `bikesched-website/wrangler.jsonc` deploys
`build/hugo/publish/` as static assets of the Worker `green-paper-4e4f` (no Worker script), with
Hugo's `404.html` for unknown paths. It runs on push to `main` touching `docs/**`, `README.md` or
`bikesched-website/**`, plus `workflow_dispatch` for manual runs; pull requests are not deployed.

Custom domain: `bikesched.org` is attached to the Worker in the Cloudflare dashboard (a one-time
step, not automated by the workflow), matching `hugo.toml`'s `baseURL`.

## 5. Documentation

- This spec, linked from [ADR 0035](../decisions/0035-website-hosted-on-static-app.md) and
  [ADR 0047](../decisions/0047-website-hosted-on-cloudflare-workers.md).
- No `docs/*.md` page of its own: the website is infrastructure around the existing docs, not a
  feature with its own API or user-facing concept.
- `AGENTS.md`'s module table has a `bikesched-website` row.

## 6. Testing

No automated tests: there is no application logic, only configuration, mounts and templates. The
only verification is `./gradlew :bikesched-website:hugoBuild` succeeding and a manual check of the
built HTML (titles, sidebar order and links, rewritten README/cross-doc links, rendered alerts).
Once deployed, the GitHub Actions build step is the regression check (a broken mount, a broken
render hook or a missing theme partial fails the build).

## 7. Implementation steps

1. ~~Module, Hugo plugin, minimal site, mounts for README.md and docs/~~ (done).
2. ~~Theme (Hextra), overrides, markdown compatibility, front matter~~ (done).
3. ~~GitHub Actions workflow building and deploying to static.app~~ (done), later to Cloudflare
   Workers (done).
4. ~~Custom domain: attach `bikesched.org` to the Worker~~ (done).
5. ~~Remove the IDEAS.md entry once the custom domain serves the site~~ (done).

## 8. Effort

Small: step 4 was dashboard/DNS configuration, no code.

## 9. Open questions

None left: the `bikesched.org` zone is on Cloudflare and attached to the Worker.
