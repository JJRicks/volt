<!-- Keep this a draft until it's finished: review starts when you mark it ready.
     The steps are in CONTRIBUTING.md, "Hand your pull request to the maintainers". -->

## What changes

Describe the problem and resulting behavior. Link the issue. For one pull request in a series, say where it sits.

## What I ran

List the checks you ran and their results (CONTRIBUTING.md, "Build and test"), and whether `bash build.sh` succeeded.

## Tried by hand on

Device and Android version, emulator or real phone, and what you tried. Note any scooter use; never run parked validation modes on someone else's scooter.

## What moved (old → new)

Settings, rows, screens or menu paths that moved, were renamed or were removed, one per line as "old place → new place". Name any view tag, ID or content description that had to change. Write "Nothing moved" if so.

## New or changed English text

String keys added or changed, and any label that now has less room. English only.

## What's untested

Anything you couldn't check, such as other screen sizes, large text, a theme, a foldable or real scooter behavior.

## Screenshots

Before and after, for what you changed.

## Before review

- [ ] The pull request targets `main` and was branched from a current `main`.
- [ ] Private data, credentials, signing material, real ride data and device captures are excluded; fixtures are synthetic.
- [ ] Existing profiles, recordings, dashboards and backups remain compatible, or migration is explained.
- [ ] Documentation and third-party notices match the change.
- [ ] No validation was deleted or disabled.

The maintainers run the emulator validations and do the translations as part of review.
