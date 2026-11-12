# Contributing to Volt

Thanks for helping make Volt useful across phones and scooters. It is an independent community project, not affiliated with or endorsed by NAVEE.

## Hand your pull request to the maintainers

This is the whole path from idea to merge. Each step is explained further down.

1. **Agree on the plan first.** For a feature, new scooter support or a change to how screens are organized, open an issue describing the intended behavior and wait for a reply. Removing a setting, changing a default or changing safety wording needs the project owner's yes in that issue before any code. See [Propose a change](#propose-a-change).
2. **Branch from the current `main`, and open the pull request against `main` from the start.** A branch in your fork or in this repository both work. Changing the base branch later does not re-run CI, and the localization gates compare your work with the base branch as it was when CI ran, so a retargeted pull request can keep a stale result.
3. **Keep it a draft until it's finished.** Drafts aren't reviewed, and each push to a ready pull request means another round of review. Push finished work, not work in progress.
4. **Before you mark it ready:**
   - run the checks CI runs, and build the app ([Build and test](#build-and-test));
   - install your build on your own phone or emulator and try the change by hand;
   - take screenshots of what you changed.
5. **Fill in the pull request description** (the template asks for each of these):
   - what changed, and the related issue;
   - what you ran, and the results;
   - what you tried by hand, and on which device and Android version (emulator or real phone);
   - what moved, was renamed or was removed, as "old place → new place";
   - new or changed English text, by string key;
   - what's untested;
   - screenshots.
6. **Ask for a review.** Once the pull request is ready and its checks are green, ask in a comment: with the review-request line for its head commit if the automated review is on for you, otherwise by saying it's ready ([Ask for a review](#ask-for-a-review)).
7. **Review.** The review is posted as a comment from the maintainer's account: the independent automated review, headed as an independent review, or the maintainer's own. It usually arrives the same day; if nothing has arrived after a day, comment on the pull request to say so. The maintainer may comment too. Findings marked **[P1]** or **[P2]** need a fix or a reasoned reply. When you've pushed fixes, ask again the same way; with the automated review, that's a new comment naming the new commit. CI must be green on the final commit.
8. **The maintainers' part.** They run the emulator validations and the standard screenshot set, update any validation that fails only because something moved or was renamed, translate new text, merge, and cut releases. You never need the release signing key, [RELEASING.md](RELEASING.md), translations, validation code or a release installed on a phone to finish a pull request.

If this is your first pull request from a fork, GitHub holds its CI run until a maintainer approves it. That's normal.

## Ask for a review

The maintainer switches the automated review on for each contributor, and tells you when it's on for you. Until then, mark the pull request ready and say so in a comment; the maintainer reviews it by hand or switches the automated review on for you.

Once it's on for you, and a pull request is ready for review (not a draft, checks green), post an ordinary comment on it that ends with this line, on a line of its own:

```
<!-- council-review-request: head=<full 40-character SHA of the PR's head commit> -->
```

- The request is picked up when the maintainers' review is next available, and that commit is reviewed. There is no fixed time; a review usually arrives the same day. If nothing has arrived after a day, comment on the pull request.
- **After fixes, or any other push,** post a new comment with the new SHA. A line that names an older commit doesn't count.
- It must be an ordinary pull request comment written by the pull request's author. The description, a review body or an inline comment won't be seen. Use the lowercase SHA from `git rev-parse HEAD`, with nothing else on the line. GitHub hides the line when it shows the comment; that's expected.
- **Without that line,** a ready pull request may still be picked up, but later and with no set timing. Don't rely on it: ask.
- **Between asking and the review arriving,** don't push to the pull request. A push throws away any review in progress, and the new commit is reviewed only after you ask again with a new comment naming it. If something has to go in, push it and ask again.
- Drafts aren't reviewed, with or without the line.
- The maintainers merge only content that was reviewed, so a push after an approval means another round.

## Report a bug or compatibility result

Include the Volt version, phone model, Android version, scooter model/region/firmware where known, steps to reproduce, expected behavior and what happened. For layout problems, include the window size or folded/unfolded state and any custom font/display scaling.

Redact screenshots and logs before sharing. Do not attach owner setup identities, Bluetooth addresses, serial numbers, PINs, account tokens, private backups, full Android bug reports or precise ride routes. A short sanitized exception is more useful than an entire phone capture. Use [SECURITY.md](SECURITY.md) for vulnerabilities and exposed secrets.

## Propose a change

For substantial features, new scooter support or a reorganization of screens, open an issue describing the intended behavior first. Keep a pull request focused enough to review, explain the user-visible change and relevant validation, and identify anything requiring physical hardware testing. Preserve compatibility with existing profiles, recordings and saved layouts.

A large change, such as moving settings between screens or an alternative dashboard, goes in as a series of small pull requests, not one. Describe the series in the issue. Order it so `main` could ship after each one, say in each pull request where it sits in the series, and ask for each one's review separately ([Ask for a review](#ask-for-a-review)). The localization gates shape that order; [Working on the UI](docs/development-notes.md#working-on-the-ui) explains how.

New or changed visible text must follow the [localization guide](docs/localization-for-contributors.md), which also explains how CI classifies your pull request. Add English only; the maintainers translate before each release.

Include documentation changes where behavior changes, including docs that spell out a menu path. Do not describe a control as verified merely because it compiles or the scooter acknowledges a write.

## Build and test

Follow the [build guide](docs/building.md). The app uses Java, native Android views and a shell build; the optional USB helper is a separate static Node/npm project.

GitHub CI runs these on every pull request. They need JDK 17 or newer and Python 3, but no Android SDK, device or signing key. Run them from the repository root before you mark a pull request ready:

```sh
bash tests/run.sh
python3 docs/validation/data/stock-pack-v6/fit_pack_model.py --check
python3 tests/owner-capture-guard-test.py
python3 tests/capture-owner-test.py
python3 tests/hardware-sweep-test.py
python3 tests/rides-timing-gate-test.py
python3 tests/release-sweep-summary-test.py
python3 -m unittest discover -s ops/ci -p 'test_*.py' -q
python3 -m unittest discover -s tools/l10n
python3 tools/l10n/gen_catalog.py --check
python3 tools/l10n/ship_check.py
bash tests/l10n/golden.sh
python3 tools/l10n/ci_gates.py --base origin/main --pr-head "$(git rev-parse HEAD)"
```

`tests/run.sh` and the `tools/l10n` unit tests take a few minutes each; the rest take seconds. `ci_gates.py` judges your last commit against the base, so commit first and fetch the base. `--pr-head` makes it run the same checks CI runs on a pull request, including that characterization tests come first (`G5`); without it, that check is skipped. In a fork, `origin/main` is your fork's copy, so compare with this repository instead:

```sh
git remote add upstream https://github.com/JJRicks/volt.git
git fetch upstream
python3 tools/l10n/ci_gates.py --base upstream/main --pr-head "$(git rev-parse HEAD)"
```

Then build the app (this needs the Android SDK; see the [build guide](docs/building.md)):

```sh
bash build.sh
```

CI does not build the APK and does not run the Android validations under `android-tests/`. You are not required to run those validations. The maintainers run the affected ones on every UI pull request, with the standard screenshot set: 344 dp and 725 dp wide, large text, both themes, and a foldable cover screen where it applies. That pass is the check that catches a moved or renamed row. If a validation fails only because something moved or was renamed, the maintainers update it in a follow-up pull request. If it fails because behavior changed or broke, that comes back to you as a review finding. To make that easy:

- list what moved, was renamed or was removed, as "old place → new place";
- keep existing view tags, IDs and content descriptions where you can, because validations and TalkBack find rows by them, and say so when one had to change;
- never delete or disable a validation to get past a failure.

If you'd like to run an emulator validation yourself, the build guide has an optional section, [Running an emulator check yourself](docs/building.md#running-an-emulator-check-yourself). Some parked validation modes change scooter settings; never run them on someone else's device or an unattended scooter.

For website changes, use the commands in [setup/web/README.md](setup/web/README.md). Keep dependencies pinned and preserve the explicit consent, local processing and cleanup boundaries.

## What never goes in a pull request

- Signing keys, keystores, passwords, `local-build.env` or any other credential.
- Real ride data, routes or coordinates, real backups, owner setup files, serial numbers, Bluetooth addresses and PINs.
- Captures, logs or bug reports from your own or anyone else's phone or scooter.
- Generated binaries (APKs, build output).

Test fixtures are synthetic: made-up identities, serials and coordinates.

## Signing and private files

Contributor builds use your own local signing key. They cannot update an installed project release signed with a different key; export anything you need before uninstalling an app. The project release key is not required to contribute and must never be shared in a PR.

The ignored `local-build.env` contains trusted local shell assignments. You can override build paths with `NAVEE_BUILD_DIR`, `NAVEE_KEY_DIR` and `NAVEE_APK_PATH`, or privately configure `NAVEE_SIGNING_KEYSTORE`, `NAVEE_SIGNING_ALIAS` and `NAVEE_SIGNING_PASSWORD`. Do not commit this file or signing material.

Review staged changes before pushing. Keep captures, credentials, recordings, private deployment details and generated binaries out of Git. Use clearly synthetic test identities and coordinates. `.gitignore` helps prevent accidents; it does not remove files already committed or erase history.

## Behavior to preserve

- Controls require fresh authenticated state; cached values never authorize an action.
- Reconnection does not replay pending commands or automatically change the lock.
- Scooter commands use canonical units; display preferences do not alter raw values.
- Missing telemetry stays unavailable, with gaps excluded from energy integration.
- Recording lifecycle and the idle background timeout remain separate.
- Simulation cannot send real commands or contaminate recorded history.
- Layout changes preserve saved configurations and accessible controls.

More detailed constraints are in [development notes](docs/development-notes.md), including [Working on the UI](docs/development-notes.md#working-on-the-ui). [RELEASING.md](RELEASING.md) is the maintainers' release process; contributors don't need it.

## Attribution

Only contribute code and assets you have the right to share. Preserve third-party notices and identify the source and license of new dependencies or artwork. Application contributions use the repository's [MIT License](LICENSE); separately licensed assets retain their own terms in [NOTICE](NOTICE).
