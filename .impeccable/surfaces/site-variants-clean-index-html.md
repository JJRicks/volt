---
version: 1
slug: "site-variants-clean-index-html"
primary_target: "site/variants/clean/index.html"
related_targets: []
---

# Surface brief: homepage variant "clean" (site/variants/clean/index.html)

Scope: volt.jjricks.com homepage, variant `clean` (selectable on /dev beside original, refined, hybrid and app). Mode: **Persuade**, ahead of a Play Store launch.
Audience: NAVEE scooter owners on Android deciding whether to install. Job: see the Home new installs start on, believe Volt is local and honest, then download (GitHub Releases latest) or read setup.
Proof on hand: the app itself (Clean Home rebuilt in HTML with sample data, sample-data screenshots), product facts in PRODUCT.md, README.md and FEATURES.txt. No testimonials, counts or press.
Constraints: production CSP (no inline script/style, self-hosted only), relative URLs, dark default + light via prefers-color-scheme, reduced-motion and no-JS fallbacks, non-affiliation notice in FAQ + footer, "Nothing leaves your phone" + asterisk `map-data-note` naming map backgrounds and update checks, sample data labelled, no manufacturer product photos or scooter drawings.
World: established. The Volt app's Clean Home (CleanHomeScreen, CleanSubjects, QuickActionLayout, JustBackView, Ui.java tokens) is inherited, not reinvented.

## Direction contract

THESIS: The homepage is Clean Home on an unfolded phone. It refuses the copy-left, phone-in-a-bezel-right hero and the feature-card grid: a real, working Clean Home pane stands at true scale on the left, and the page pane beside it reads like the app's subject pages.

OWN-WORLD: Ui.java tokens only: #111211 page, flat #1F201E cards radius 20, no borders, 10-16px gaps; Saira 400-700, no negative tracking; 24 Bold names, 15.5 Medium row names over 13 muted summaries; 33px raised icon tiles with #FF8A8C glyphs; 54px round quick actions (raised, red when on) with one 13.5 word under each; the Lock bar; big Bold numbers with small same-baseline units. Red only for actions and live state. Light #F3F3F0 / white.

STORY: The visitor reads the promise and Download, sees Clean Home with sample data, watches its fixed-height top area follow three moments (Parked, Charging, Just back) while the Lock bar and quick actions never move, then reads the seven subjects, screens, models, setup, privacy and FAQ, and ends on the release readout.

FIRST VIEWPORT: Clean header across the top (muted status line, "Volt" 24 Bold, round GitHub and Questions discs, red Download button). Left pane (~420px, sticky): the sample Clean Home (status "Connected · Sport", name, search and gear discs, top area, Lock bar, four round actions Ride / Record / Charge by / Cruise) with a segmented Parked / Charging / Just back control under it. Right pane: h1 "Your scooter, no cloud.", the approved summary, Download for Android + How setup works, status line. Phone: one column, header, hero, then the Home.

FORM: Clean Home's top-area moments as the page spine (position 3 of my list: 1 two-pane unfolded Home, 2 one-column Home as page, 3 moments as spine, 4 subjects as in-page pages, 5 Home style picker hero, 6 search-led page, 7 Edit sheet picker). Dealt 3, 6, 1; 3 leads, fused with 1 (two panes) as the frame. seed:766ca7bd736d30e1. Code-led. Signature interaction: on wide screens the sticky Home's top area crossfades (220 ms, the app's CROSSFADE and FADE curve) to the moment the right pane is reading; on any screen the segmented control switches it. The draggable Lock bar from the app variant stays.

FINISH: renders at 1440 and 390, light and dark, compared with the live app variant for the owner; DESIGN.md deferred (one world per project; the owner picks the live variant first).

## Unresolved
- Fresh Clean Home captures from the store-assets run replace the placeholder screenshots in img/ when they land.
- Which variant goes live, and whether the switch learns "clean", is the owner's call.
