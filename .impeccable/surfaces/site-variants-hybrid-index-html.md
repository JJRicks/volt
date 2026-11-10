---
version: 1
slug: "site-variants-hybrid-index-html"
primary_target: "site/variants/hybrid/index.html"
related_targets: []
---

# Surface brief: homepage, hybrid variant

## Scope
- Surface: volt.jjricks.com homepage, variant `hybrid` (`site/variants/hybrid/`), one of four the owner can switch between (original, refined, hybrid, app).
- Mode: **Persuade**. Kind: **established world, translated**: today's page structure and section order, rebuilt in the Volt app's own design language (work/homepage-variants-2026-11-10/app-language/APP-DESIGN.md, from Ui.java at 1.13.0). Build: code-led (no image generation).
- Test the owner set: someone who uses Volt should recognise the app the moment the page loads.

## Audience, job, action
- NAVEE owners on Android deciding whether to install. Job: understand what Volt does, trust it, find out if their model works, download.
- Primary action: Download (GitHub Releases latest), on every download button including the nav. Secondary: How to set up (#setup, then pairscooter.jjricks.com).
- Proof: 1.13.0 app screenshots (sample data, labelled in the footer), the working Lock bar demo, the route demo, honest compatibility and privacy notes.

## Kept
- Section order: nav, hero, features (4), rides, foldables, privacy, setup, FAQ, close, footer.
- Owner-protected: the app screenshots (refreshed to 1.13.0 captures, shipped in the variant's img/) and the lightning/particle canvas (recoloured to the app accent #E32B2F, arcs every 1.8–4.4 s instead of 1.4–4 s; same density and link reach, slower drift; links kept; 60 fps cap and pause rules kept).
- Owner-approved copy, the FAQ and footer non-affiliation notice, `map-data-note`, "Screens show sample data".

## Direction contract
THESIS: The page is built from the app's own parts, so the homepage reads as Volt before a word is read. Refuses the dark-SaaS template (glows, gradient text, eyebrows over headings, glass) that today's page wore.
OWN-WORLD: Page #111211, solid #1F201E cards with no border at radius 20, raised #2A2B28 chips and secondary buttons at radius 14, one accent #E32B2F for actions and live state only, accentDeep #B81B22 setting tile, green #5FCB7E only for "connected" and charging power. Saira 400/600/700, no negative tracking, big bold numbers with small muted units on the same baseline, 11–12 px caps section labels used only on lists. Flat depth; motion on cubic-bezier(.2,0,0,1) at 150–280 ms.
STORY: Visitor sees the real home screen beside the README tagline, recognises the riding-mode SPORT · Connected pill attached to the phone, reads what Volt does, slides a working Lock bar, sees rides, charging, foldables, privacy rows, three setup steps, FAQ, and ends on riding mode with Download.
FIRST VIEWPORT: Floating nav pill (the app's nav: surface, 1 px raised stroke, red dot under the section in view, red Download pill). Left: two-line H1 "Your scooter. / Your connection.", lede, "No account. Works offline.", red pill Download for Android + raised How to set up, four chips. Right: the 1.13.0 home screenshot in a plain bezel, the SPORT · Connected pill straddling the bezel's top edge (covering no screen text); the four chips sit under the copy (under the phone on phones). Lightning field behind.
FORM: Today's page structure, translated (no structure roll: hybrid keeps today's layout by the owner's brief). seed: none (waived: owner brief keeps today's layout)
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
(Run rule, Nov 10: DESIGN.md and .impeccable/design.json wait until the owner picks a variant; the finish review still applies.)

## Signature interaction
- The Lock bar demo in the first feature card: drag the thumb (or tap, Space/Enter, arrow keys) and it snaps in 180 ms to the app's locked look (surface fill, white thumb, "Locked", back chevron). role="switch", labelled as a demo; sends nothing. Without JavaScript it is a static, aria-hidden picture of the unlocked bar.

## Rasters (provenance)
- img/home.webp, scooter.webp, stats.webp, riding.webp, unfolded.webp: cwebp q82 resizes of app-language/screens/{phone-home-dark, phone-scooter-dark, phone-livestats-dark, riding-dark, unfolded-home-dark}.png (Volt 1.13.0 release APK on an emulator, simulation with sample data, developer banner removed).
- img/charging.webp: charging-hero-dark.png cropped to rows 360–1650 so the test profile title ("Battery layout fixture") and status bar are gone.
- og.png: rendered by site/tools/variants/render-hybrid-og.sh from hybrid-og.html (Saira, app palette, home.webp).

## Unresolved
- Charging card and route readouts show sample numbers ("Sample" chips on both cards; footer note covers the screenshots).
- Section labels: the craft floor bans kickers over headings, so the app's caps label appears only over the "You'll need" chips, not over section titles.
