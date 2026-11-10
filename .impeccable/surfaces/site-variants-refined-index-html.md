---
version: 1
slug: "site-variants-refined-index-html"
primary_target: "site/variants/refined/index.html"
related_targets: []
---

# Surface brief: homepage, refined variant

## Scope
- Surface: volt.jjricks.com homepage, variant `refined` (`site/variants/refined/`), one of four the owner can switch between (original, refined, hybrid, app).
- Mode: **Persuade**. Kind: **refinement of the incumbent world**, not a redesign. Build: code-led (no image generation).
- Trigger: friends told the owner today's page "looks like AI slop". This variant keeps today's identity and removes what reads as generated.

## Audience, job, action
- NAVEE owners on Android deciding whether to install. Job: understand what Volt does, trust it, find out if their model works, download.
- Primary action: Download (GitHub Releases latest). Secondary: How to set up (pairscooter.jjricks.com).
- Proof: the four real app screenshots (sample data), the lightning field, honest compatibility and privacy notes.

## Kept (identity)
- Near-black ground, the app's red #ea252b, Saira (all four weights), the bolt mark and wordmark.
- The lightning/particle canvas (owner: must keep), all four screenshots, section order and content, the slide-to-lock demo and the route demo.
- Owner-approved copy: "independent, open source Android app", "Nothing leaves your phone" with its asterisk `map-data-note`, the GitHub repository link; non-affiliation in FAQ and footer; "Screens show sample data".

## Changed, and why
- Removed the template moves that read as AI-made: pill badge with pinging dot, six uppercase eyebrows, four glowing icon tiles, gradient text on every heading, bento hover spotlights and lifts, gradient 01/02/03 numerals, the radial-glow CTA card, nested promise cards.
- Removed every colored glow (15 on the original) and the blurred aura blobs; depth is now offset neutral shadows only. The canvas is the one source of light.
- Motion cut from ten loops to the lightning field, one hero entrance, the lock demo and the route demo. Plexus lines between particles removed (the generic "network" look); arcs now sometimes fork, so each strike reads as an event.
- Hero: README tagline "Your scooter. Your connection." replaces "Total control. Zero cloud."; the phone stands straight with nothing covering it (the chips hid the screenshot and cut "Midnight Runner" in half); "Which NAVEE models?" link replaces the pill.
- Features are ruled columns, not cards; screenshots sit straight in one quiet panel each.
- Type: H1 max 84px (was 124), H2 max 52px (was 72), one red accent on the H1 and on the privacy headline only; headings left-aligned to match the hero; balance/pretty wrapping.
- Panel radii unified to 12 and 24 px plus pills (device frames keep their own; the original used seven values); solid button red #dc1f26 (white text 4.9:1; the old gradient was 3.7:1 at its light end).
- Copy: specific headlines in place of slogans ("fully unlocked" could read as bypassing speed limits); FAQ languages updated to the ten that 1.13.0 ships, with the Android 13 note for the per-app switch; em dash removed.
- No-JS fix: the route line now shows fully drawn without JavaScript (it was invisible before).

## Direction contract
THESIS: The app is the hero; the page gets out of its way. Refuses the dark-SaaS template (glow, gradient text, eyebrow-over-heading, icon-tile bento) while keeping Volt's own materials.
OWN-WORLD: Near-black #0a0a0b ground, warm off-white #f5f5ef ink, the app's red used solid and sparingly, Saira bold display with tight but legal tracking, hairline rules instead of boxes, pill buttons like the app's, a live field of red charged particles with lightning arcs.
STORY: Visitor sees the real app and the tagline, reads one sentence of what it does, learns it works offline and which models, sees the screens, trusts the privacy note, downloads.
FIRST VIEWPORT: Left: two-line H1 (second line red), lede, Download (solid red) + How to set up, a meta line with the models link. Right: the home screenshot in a straight phone frame. Lightning field behind everything.
FORM: Incumbent page structure, refined (no structure roll by brief). seed: none (no roll: refinement of the incumbent)
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
(Run rule, Nov 10: DESIGN.md and .impeccable/design.json wait until the owner picks a variant; the finish review still applies.)

## Unresolved
- og.png still shows "Total control. Zero cloud."; re-render via site/tools if this variant goes live (og:image:alt left describing the image as it is).
- The owner may prefer to keep one of the old headlines; all copy changes are in index.html only.
