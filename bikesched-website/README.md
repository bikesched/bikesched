# bikesched website

The [bikesched.org](https://bikesched.org) project site: a [Hugo](https://gohugo.io) site built by
the [`gradle-hugo-plugin`](https://github.com/fstaudt/gradle-hugo-plugin), using the
[Hextra](https://github.com/imfing/hextra) theme. The home page is the repository's
[`README.md`](../README.md) and the docs section is [`docs/`](../docs), both mounted directly (no
copies, see [`hugo.toml`](site/hugo.toml) and
[the spec](../docs/specs/website-project-site.md)) so the site always reflects the repository.

## Running

The Hextra theme is a Git submodule (`site/themes/hextra`). Without it the site builds but is
empty, so check it out first (once per clone, or use `git clone --recurse-submodules`):

```shell
git submodule update --init
```

```shell
./gradlew :bikesched-website:hugoServer   # runs a local dev server with live reload
./gradlew :bikesched-website:hugoBuild    # builds the static site into build/hugo/publish/
```
