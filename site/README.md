# Volt homepage

The public homepage at <https://volt.jjricks.com/>. It's a single static page with no build tools, no frameworks and no third-party requests. Fonts, images and scripts are all served from the same origin.

There can be several versions of the page, called variants. One is live at `/`, and every one can be previewed at `/preview/<name>/`. The owner switches between them in the private Workbench (`/dev/`, Homepage).

## Layout

- `src/` holds the page: `index.html`, `styles.css`, `main.js`, the icons, the link-preview card (`og.png`), the web manifest, `robots.txt` and `sitemap.xml`.
- `src/img/` holds the app screenshots, exported as WebP. They were captured on an emulator in simulation mode with sample data; the developer-mode banner is omitted, and the footer says the screens show sample data. The phone image retains the three-reading arrangement shared by the current default; Capacity experiment is below its viewport. The unfolded screenshot uses a custom four-reading arrangement in Volt 1.4.5: Power, Temp, Health and Charge limit in a 2×2 grid. It includes the full Capacity experiment card below Record ride in the same taller viewport. This screenshot does not change the app defaults or the folded phone image. The sample name and existing readings are unchanged. Capacity values are simulated examples, not recorded evidence.
- `tools/` holds the templates and script that render `og.png` and the PNG icons with a local Google Chrome: `bash site/tools/render-images.sh`. The rendered files are committed, so a normal build doesn't need Chrome.
- `variants/<name>/` holds the other homepage variants (see "Variants"). Today's page is the `original` variant and stays in `src/`.
- `tools/check-variants.py` checks today's page and every variant against the variant contract and the production CSP. `tests/site-homepage-test.py` runs it in CI.
- `nginx/volt-homepage.conf` is the server configuration, with security headers, caching, previews and the ACME webroot.
- `server/` holds the root-owned live-variant switch (`volt-homepage-switch` with its systemd path and service units, and `install-switch.sh`) and `deploy-release.sh`, the server half of a homepage deploy (see "Deploy").

## Build

```sh
bash site/build.sh
```

This writes `site/dist/` and a SHA-256 manifest, `site/dist.sha256`, kept beside it rather than served:

- `dist/variants/<name>/`: one complete page per variant: `original` from `src/`, and one per folder in `variants/`.
- `dist/variants.json`: each variant's name, title and one-line description, in the order the Workbench lists them. The titles are Original, Refined, Hybrid and App look; any other name gets itself, capitalized, as its title.
- `dist/` itself: today's page again, byte for byte the same as `dist/variants/original/`. This keeps older tools working: release scripts that read `dist/index.html`, and the nginx configuration from before variants, which served the release root. The copies add about 1 MB to a release. The Saira fonts and their SIL OFL license come straight from `res/font/` and `licenses/`, so they aren't duplicated. The download buttons link to GitHub Releases; this site doesn't host an APK.

`build.sh` fills the download line's `@VERSION@` from `AndroidManifest.xml`'s `versionName`, so rebuilding after a release keeps the page current. Deploying still needs the owner's OK.

## Variants

A variant is a folder `variants/<name>/` (lowercase letters, digits and hyphens). The names the switch accepts are fixed: `original` (today's page, `src/`), `refined`, `hybrid` and `app`. Others build, but can't go live.

- `index.html` is required and must contain the `@VERSION@` placeholder; the build fails otherwise.
- Every other regular file is copied, keeping subpaths, except `*.md` files and dotfiles. Symbolic links stop the build.
- The build gives every variant these shared files, which a variant may replace by shipping the same path: `favicon.svg`, `favicon-32.png`, `apple-touch-icon.png`, `icon-192.png`, `icon-512.png`, `og.png`, `site.webmanifest`, `sitemap.xml`, `img/*.webp` (today's screenshots), `fonts/saira-{regular,medium,semibold,bold}.ttf` and `THIRD-PARTY-NOTICES.txt`. Every variant also gets `robots.txt`, which it may not replace (see below).
- A page must work at `/` and at `/preview/<name>/`, so its URLs are relative, with no leading slash. The canonical link and the `og:`/`twitter:` URLs may be absolute `https://volt.jjricks.com/` URLs. Links to other sites are fine.
- The production CSP applies to every variant: no inline `<script>` or `<style>`, no `style=""` attributes or `on*=` handlers, no `data:` URLs and nothing from other hosts. Setting `element.style` from a script file is fine.
- Link-preview images must not depend on which variant is live, and must change URL when the image does (Slack, X and the like cache them by URL for a long time). In every variant except original, each `og:image` / `twitter:image` (and its `:url`, `:secure_url`, `:src` forms) is exactly
  `https://volt.jjricks.com/preview/<name>/<path>?v=<hash12>`,
  where `<path>` is the image's path in the variant (usually `og.png`) and `<hash12>` is the first 12 hex digits of the SHA-256 of the file the variant serves there: its own file, or the shared `src/<path>` when it ships none. For example `shasum -a 256 site/variants/refined/og.png | cut -c1-12` (or `site/src/og.png`). nginx serves `/preview/<name>/` from the current release whatever is live, so a link to a preview unfurls with that variant's card before, during and after it is live. `<name>` must be one nginx serves under `/preview/` (`original`, `refined`, `hybrid`, `app`). If the build fails, its message gives the exact URL.
- Original is today's page byte for byte, so it keeps `https://volt.jjricks.com/og.png`. That URL serves the live variant's `og.png`, so while a variant with its own card is live, a fresh unfurl of the original's links shows that card. Link-preview caches keep what they fetched before.
- Robots rules come from nginx and the one shared `src/robots.txt` only. A variant may not ship `robots.txt` (the build stops), the shared one has no `Disallow` lines, and no page may have a `<meta name="robots">`, a crawler-specific one such as `<meta name="googlebot">`, or `<meta http-equiv="X-Robots-Tag">`. A crawler that may not fetch a preview never sees its noindex (see below), and a variant's own robots rules would follow it to `/` when it goes live.
- Nothing else about caching: every file is revalidated on each visit (see "Caching"), so CSS, scripts and images may keep the same names in every variant.

`python3 site/tools/check-variants.py` checks all of this for `src/` and every variant, and `build.sh` runs it on every built variant: any problem stops the build. The content policy and animation rules below apply to every variant.

Previews are kept out of search by the `X-Robots-Tag: noindex, nofollow` header nginx sends on every `/preview/` response. `robots.txt` deliberately does not disallow `/preview/`: a crawler that is not allowed to fetch a page never sees its noindex, and may list the bare URL anyway.

## Caching

Switching variants changes the files behind the same URLs (`/styles.css`, `/img/…`), so no browser may reuse a file from the variant that was live before:

- nginx sends `Cache-Control: no-cache` (`expires epoch`) for every homepage file, at `/` and under `/preview/`. Browsers keep their copies but ask before using them.
- `deploy-release.sh` sets each file's modification time from its SHA-256 (one of 600 million seconds between 2001 and 2020). nginx's `ETag` (modification time and size) and `Last-Modified` therefore change when the content does. A changed file is sent again; an unchanged one, such as a shared font, comes back as `304 Not Modified`, across switches and releases alike.
- Limit: 600 million values is not collision-proof. Two files of the same size whose SHA-256 values map to the same second share a validator, so a browser holding one would keep it when served the other. Ordinary files essentially never do (for a handful of versions of one URL the chance is around one in 10^8), but they can be crafted: `body{color:red}/*00015ba8*/` and `body{color:tan}/*0000062d*/` collide. Variant files arrive through reviewed pull requests, and a collision would only show a visitor the other variant's copy of that file until it next changes. A collision-proof validator needs a hash-based ETag, which stock nginx can't serve from static files.

## Live variant switch

`/` serves `/var/www/volt-home/live`, a symbolic link to `releases/<commit>/variants/<name>`. Only the root-owned switch changes it:

1. The Workbench (the unprivileged `volt-dev` service) writes the requested name to `/var/lib/volt-dev/homepage/request`, which it owns, and records the change in its history.
2. `volt-homepage-switch.path` notices and starts `volt-homepage-switch.service`, a oneshot that runs `/usr/local/sbin/volt-homepage-switch` as root. It:
   - accepts only an exact `original`, `refined`, `hybrid` or `app` (one trailing newline allowed). A missing request means `original`. A request that is a symbolic link, too large or anything else is refused, and its content is never copied anywhere;
   - checks that the variant is a real folder, with a real `index.html`, in the release `current` points at;
   - replaces `live` atomically: a new link under a temporary name, renamed over the old one. nginx resolves the link on every request, so no reload is needed;
   - writes `/var/lib/volt-homepage-switch/status.json`, readable by everyone: the live name and its release, the requested name, the time (UTC, ISO 8601), the result (`ok`, `error` or `fallback`) and a message.
3. An invalid request, or one for a variant this release lacks, leaves the live variant as it is and records the error.

The choice survives homepage deploys. A deploy points `current` at the new release and then runs the switch, which moves `live` to the same variant in the new release. Until then `/` keeps serving the previous release's copy, so nothing dangles. If the new release lacks the chosen variant, original goes live and the status says `fallback`. The request still names the chosen variant, so a later release that has it brings it back.

Install or update the switch with `bash site/server/install-switch.sh` as root on the VPS. It backs up replaced files to `/var/backups/volt-homepage-switch/`, creates the request folder and runs the switch once. To remove it: `systemctl disable --now volt-homepage-switch.path`, then delete the two units in `/etc/systemd/system/` and `/usr/local/sbin/volt-homepage-switch`, and run `systemctl daemon-reload`. Before that, put back an nginx configuration whose root is `/var/www/volt-home/current`, because nothing moves `live` afterwards.

To switch by hand on the server, as root: `printf 'refined\n' > /var/lib/volt-dev/homepage/request && chown volt-dev:volt-dev /var/lib/volt-dev/homepage/request`, then read the status file.

## Deploy

The site is served from the jjricks.com VPS by its own nginx site, `volt-homepage`. Every other site's configuration is left untouched.

- Releases are immutable: `/var/www/volt-home/releases/<commit>/`, with `/var/www/volt-home/current` pointing at one of them, and `live` at one variant of a release (see "Live variant switch").
- Homepage deploys, including the one after each app release, run `server/deploy-release.sh` on the VPS. It replaces the `update.sh` used up to Volt 1.13.0 and takes the same arguments: the commit, the archive and manifest SHA-256, the previous release's commit and the nginx configuration's SHA-256. It checks everything `update.sh` checked, and also that the release has `variants.json` and `variants/original/`, and it sets the content-derived modification times (see "Caching"). It refuses to start when nginx serves `live` but the switch is missing.
- Before moving `current`, it saves where `live` points. After moving `current`, it runs the switch, so the live choice carries over. It prints `UPDATE_OK` with the live variant only when the switch service succeeded in that run and `live` really points inside the new release. Otherwise it puts both links back itself, without the switch: `current` to the previous release and `live` to the saved target, each with one atomic rename. Then it checks both. When both are back, it prints `DEPLOY_FAILED` and exits 1. When either is not, it prints `DEPLOY_INCOMPLETE` with the exact commands that finish the rollback and exits 2: run them as root before anything else. The new release folder stays for a look. A status file from an earlier run is never taken as proof.
- Afterwards, check each file in the manifest at its public URL: `variants/<live>/<file>` at `/<file>`, `variants/<name>/<file>` at `/preview/<name>/<file>`. The release-root copies and `variants.json` are not served.
- The certificate is a Let's Encrypt ECDSA certificate for `volt.jjricks.com`, issued and renewed with the webroot `/var/www/volt-home-acme`. The renew hook runs `nginx -t` before reloading nginx.
- Each deployment backs up `sites-available/` and `sites-enabled/` under `/etc/nginx/volt-deployment-backups/`.

Rollback:
- **To a previous release:** point `current` back at an earlier directory under `releases/`, then run `systemctl start volt-homepage-switch.service` so `live` follows. No reload is needed.
- **To today's page:** request `original` in the Workbench, or by hand as above.
- **To remove the site entirely:** delete `/etc/nginx/sites-enabled/volt-homepage`, then run `nginx -t && systemctl reload nginx`.

## Content policy

Volt is an independent project and must not present itself as NAVEE. Keep the non-affiliation notice in the FAQ and footer, and don't use NAVEE logos or branding. Feature claims should match `README.md` and `FEATURES.txt`.

Keep the page short enough to skim: what Volt does, model compatibility, download, setup, privacy and a short FAQ. Put details (counts, settings lists, implementation notes) in the repository docs, not on the page.

- Compatibility: describe Volt as an app for NAVEE scooters, with features varying by model and firmware. Keep the distinction between hardware-tested UT5 MAX support and other model mappings in the FAQ; don't position Volt as UT5-only or imply that every NAVEE scooter has been tested.
- Source: keep the GitHub repository link at the owner's request, including while it is private. The owner explicitly approved the prelaunch summary wording "independent, open source Android app"; don't replace it with "release planned." This copy approval does not authorize changing repository visibility. Setup links go to <https://pairscooter.jjricks.com/>. The public-release checklist remains in `docs/public-release.md`.
- Privacy: retain the owner's "Nothing leaves your phone" headline with a brief, legible asterisk note at the bottom-left inside the privacy box. Explain that map backgrounds are off by default and enabling them exposes the IP address and viewed map area to the provider. The Bluetooth core works offline, and neither exports nor backups are uploaded automatically.

The map footnote uses `map-data-note` for its ID and class. Avoid the generic `privacy-note` ID: EasyList Cookie hides it as a cookie-banner selector, even on ordinary page text.

The slide-to-lock demo is decorative (`aria-hidden`). Its knob, glyphs, labels and track tint run on one shared CSS cycle (`--lock-cycle`), started together by `main.js`. With reduced motion or without JavaScript it rests in the unlocked "Slide to lock" state.


## Animation efficiency

Decorative animations pause when the page is hidden, its window loses focus, or the page is suspended. Whole sections also pause when offscreen, preserving the shared lock-demo cycle. Visible animations resume from their paused position; reduced-motion and no-JavaScript fallbacks remain available.

The ambient particle canvas paints at most 60 times per second, with time-based movement on faster displays. Lightning paths are built once per strike; distant particle pairs skip expensive distance calculations. The route demo stops completely offscreen, retains its text nodes, and refreshes its numeric readout at most eight times per second while keeping the moving dot smooth.

When changing CSS or JavaScript, update their content-hash query strings in `src/index.html` so returning visitors receive the matching assets immediately. For lifecycle checks, include a real window focus change: browser automation can emulate focus or keep background pages reported as visible. CPU timings measure browser work, not power consumption.
