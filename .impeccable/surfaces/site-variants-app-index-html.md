---
version: 1
slug: "site-variants-app-index-html"
primary_target: "site/variants/app/index.html"
related_targets: []
---

# Surface brief: homepage variant "app" (site/variants/app/index.html)

Scope: volt.jjricks.com homepage, variant `app` (one of four; the owner picks the live one on /dev). Mode: **Persuade**.
Audience: NAVEE scooter owners on Android deciding whether to install. Job: understand what Volt is, believe it is local and honest, then download (GitHub Releases latest) or read setup.
Proof on hand: the app itself (1.13.0 sample-data screenshots, rebuilt app components), product facts in PRODUCT.md. No testimonials, counts or press.
Constraints: production CSP (no inline script/style, self-hosted only), relative URLs, both colour schemes (dark default, light via prefers-color-scheme), reduced-motion and no-JS fallbacks, non-affiliation notice in FAQ + footer, "Nothing leaves your phone" + asterisk `map-data-note`, sample data labelled.
World: established. The Volt app's own design language (work/homepage-variants-2026-11-10/app-language/APP-DESIGN.md) is inherited, not reinvented.

## Direction contract

THESIS: The homepage is Volt's riding mode, opened in a browser. It refuses the category's split hero (copy left, phone mock right, glow behind) and its feature-card grid; the page reads top to bottom like the Focus frame: status bar, one huge readout, a hairline, quieter readouts, and the stopped-state controls at the end.

OWN-WORLD: App tokens only. #111211 page, flat #1F201E cards (radius 20, padding 14, 10px gaps, no borders), Saira 400-700 with no negative tracking, big Bold numbers with small muted same-baseline units, 11px SemiBold caps labels at +0.08em, radius-14 buttons, pill chips, the red RIDE pill. Red (#E32B2F / deep #B81B22) only for actions and live state; ECO green for live/ok. Light theme #F3F3F0 / white cards. Motion (.2,0,0,1), 150-280ms, nothing bounces.

STORY: The visitor sees the app's own riding screen, learns it is an independent open source Android app for NAVEE scooters that works offline, drags the real Lock bar, can switch on night vision, then scrolls through one rebuilt Home dashboard in the app's order (battery card, Lock bar, three reading tiles, Max speed / Charge sched., Record ride) with a single annotation column, a sample route card with the ride-detail stat row beside a charging card, screenshots, models, setup, privacy and FAQ, and ends on a stopped-screen close with the version readout and Download.

FIRST VIEWPORT: Riding top bar of 48-56px discs (Volt mark, status pill "● Android · works offline", moon disc for night vision) with a red Download pill at right. Left-aligned h1 "Your scooter, no cloud." with the approved summary and two actions (red pill "Download for Android" -> releases/latest, secondary "How setup works"). Below, full width, a surface card holding the riding instrument at landscape scale: giant fitted "0.0 mph" speed (container-query sized to fill its column) with the .0 at .46x, beside "Range at this pace ≈ 27 mi", "Battery 78%" with 10 green segments, Trip / Ride time / Batt. temp, then the draggable Lock bar and red Record disc, a grey "Sample" chip. At 1440x900 the whole card, Lock bar included, sits in the first viewport. (The left-edge riding power bar from the first build is cut: a flat stripe did not read as the app's ticked track.)

FORM: Riding mode Focus frame as page structure, position 4 of my ordered list (1 Home dashboard as page, 2 floating-nav tab tour, 3 onboarding sequence, 4 riding Focus frame, 5 More-screen grouped rows, 6 charging hero stage, 7 dashboard editor). Dealt 4, 3, 2; 4 leads; owner pre-chose the dice, no decision page. seed:972a78bbbc005f94. Code-led (no image generation). Signature interactions: the draggable Lock bar (180ms snap on .2,0,0,1) and the night-vision moon toggle (the app's exact colour matrix applied to tokens and screenshots).

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

Note for this run: DESIGN.md is deliberately deferred by the owner's run plan (one world per project; the documenter runs for the owner's chosen variant later).

## Unresolved
- Night-vision muted text is lifted above the exact matrix value so captions stay at 4.5:1; the app keeps the raw matrix.
- Small red text uses --accent-ink (#FA5256 dark, #A8161C light) instead of the app's #E32B2F, which is 3.6:1 on cards; fills and the RIDE pill keep the app's accent.
- Route promise: only rides recorded with Volt open and location on have a route; copy never says every ride gets a map.
- No scooter product render outside the app screenshots (NAVEE product art is not cleared for the web), so the charging card has no scooter glow.
- Which variant goes live is the owner's call.
