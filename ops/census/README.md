# Census (G7) on GitHub Actions

`.github/workflows/census.yml` runs the G7 screen text census (l10n/README.md, "Census (G7)") on
GitHub-hosted emulators instead of a local machine. It does what the local census scripts do: for a
base commit B and head commits H1..Hn it builds every tree with `VOLT_RELEASE=1 bash build.sh`, builds
the test APK from the **base** tree only, runs each census step for `B-r1`, every head and `B-r2` with
the base tree's `android-tests/run-release-sweep.py --census --only STEP`, saves each run's logcat, and
compares every head with both base runs using the base tree's `tools/l10n/census_diff.py`, per step,
with the same summary lines and total as the local `diff.sh`.

## Request a run

A run is a push to a branch named `census-run/<name>` (nothing else starts this workflow) whose
`ops/census/request.json` says what to compare. Make the branch from the branch that holds this
workflow, so it runs this copy of the scripts:

```sh
git fetch origin
git switch -c census-run/b16-vs-main origin/claude/census-cloud
cat > ops/census/request.json <<'EOF'
{"base":  {"tag": "main-f529", "sha": "f529f43ec3181e2e6bb41f812626eb945b7cf524"},
 "heads": [{"tag": "b16", "sha": "136ffe5186e3bf854ee5841816bce475e3cba010"}],
 "steps": "all"}
EOF
git add ops/census/request.json
git commit -m "census request: b16 vs main"
git push origin census-run/b16-vs-main
```

- `sha` is a full 40-character commit SHA that has been pushed to this repository (any branch, or a
  commit a branch once held). `tag` is `[a-z0-9-]+`, at most 40 characters; the base's runs are
  named `<tag>-r1` and `<tag>-r2`, so a head may not use those names. One to eight heads.
- `steps` is `"all"` (the 64 steps of `steps.txt`, a copy of the local census list) or a list of
  step names from `steps.txt`, e.g. `["backup-render", "compat-gating"]`.
- **One census or probe run at a time, across every branch.** `census.yml` and `census-probe.yml`
  share the concurrency group `census-cloud` (`cancel-in-progress: false`), so 18 run jobs at most
  are ever busy and 2 of the free plan's 20 concurrent jobs stay free for CI. A run pushed while
  another is going waits for it. GitHub keeps only the newest waiting run of the group and cancels an
  older waiting one, so push the next `census-run/*` branch only once the previous run has started
  (or finished). Pushing a new commit to a running branch no longer cancels it.

The request is validated by `plan.py` before anything is built (SHAs, tags and steps are checked and
only then handed to scripts, through environment variables).

## Follow it and get the results

```sh
gh run list --repo JJRicks/volt --workflow census.yml --branch census-run/b16-vs-main --limit 1
gh run watch <run-id> --repo JJRicks/volt
mkdir -p ../scratch/cloud-census/b16-vs-main && cd ../scratch/cloud-census/b16-vs-main
gh run download <run-id> --repo JJRicks/volt --name census-diff --dir diff
# optional, large (screenshots, census tsv, logcat, per-run logs): every shard's output
gh run download <run-id> --repo JJRicks/volt --pattern 'census-shard-*' --dir shards
git push origin --delete census-run/b16-vs-main      # once the artifacts are downloaded
```

The run's **summary page** shows each head's total (the same three blocks the local `diff.sh` prints:
`TOTAL {...}`, `VOLATILE BY STEP`, `NOT CLEAN`) and any missing runs. The `census-diff` artifact holds:

| File | What |
|---|---|
| `diff-<head>.txt` | census_diff.py's full output, per step (`== step` headers), like the local `diff-<head>.txt` |
| `diff-<head>-summary.txt` | one line per step: `rc=`, `missing=[tags without summary.json]`, the PASS/FAILED line |
| `diff-<head>-total.txt` | the total the local `diff.sh` prints |
| `results.tsv` | every (tag, step) run: sweep rc, seconds, the sweep's PASS/FAIL line, census screens and problems |
| `results.txt` | every shard's `results.txt` (the local worker's log format) |
| `missing.txt` | `tag<TAB>step` for each run with no `summary.json` (empty when all ran) |
| `not-clean.txt` | `tag<TAB>step<TAB>reason` for each run that makes its step's result untrustworthy (empty when every step is clean; see below) |
| `summary.md`, `plan.json`, `builds.txt` | the job summary, the validated plan with its shards, the APK hashes |

The `census-shard-<n>` artifacts hold `out/<tag>/<step>/` (exactly the local `OUT/TAG/STEP`, with
`census/`, `summary.json`, `logcat.txt` and the screenshots) and `logs/shard-<n>/` (each run's log,
`results.txt`, the emulator log). `census-apks` has the APKs that ran (throwaway-signed; never install
them on a phone). Artifacts are kept for 3 days.

The **diff job fails** when any (tag, step) run left no `summary.json` or any step is **not clean**
(`not-clean.txt`, also on the summary page). `census_diff.py` only compares a screen that both base
runs have: a screen one base run missed is counted as volatile and never compared, and a step where
no run has screens passes with nothing compared. So `report.py` calls a run not clean when its sweep's
rc is not 0 (it stopped partway); its `summary.json` is unreadable or has no entry or no
`census_screens` for the step; it has no census screens on a step where the local census has some; for
the two base runs, when they do not hold the same census files; and, when the base is the commit
`screens.tsv` was measured on (main-f529, the local `sweeps/wave3-b16` census), when a base run's
screen count differs from the local one. A not-clean step's PASS proves nothing: rerun it. A head
with fewer screens than the base is listed as a note (its diff already fails with "only in base"). A
failed step otherwise never stops its shard, and text differences never fail the workflow (read them
as you would locally). The run job is red when its emulator did not boot (or a restart failed); its
runs are still tried. `diff.sh` itself stays the local script, so its `NOT CLEAN` block still only
looks at census_diff's rc and missing `summary.json`; `not-clean.txt` is the complete list.

Also not clean, for every run of the step: a step listed in **`cloud-gaps.tsv`** (a step the cloud
emulator cannot run, marked "NOT COVERED in the cloud": run it on the laptop census). The file
lists `report-invite-dark` and `report-invite-light`; see "Known cloud gaps" below. And every run a shard never got to: a shard **stops after 2
boot failures in a row** and records each remaining run as `not run: ... (shard stopped ...)`, so a
broken image cannot hold 18 runners for hours (the run job's timeout is 240 minutes).

**Retries.** One known emulator flake is retried once, on the same emulator: a run whose sweep failed
on one of the 8 s password-keyboard waits of `PasswordKeyboardChecks` (in the backup steps): "Enable the
emulator software keyboard for password validation", "Focused password is obscured by the keyboard",
"Password moved behind the keyboard while typing", "Password keyboard did not dismiss" or "Password
bounds did not settle after the keyboard/window transition". On the swiftshader emulator Gboard
sometimes needs longer than 8 s to show its keyboard, or the window longer to settle above it
(logcat: `ImeTracker ... onFailed at PHASE_WM_ABORT_SHOW_IME_POST_LAYOUT`, keyboard ready about 8.7 s
after the request). The retry's result is the one used; the first attempt's log is kept as
`log-<tag>-<step>-attempt1.txt` and every retry is listed in `results.txt` (`retry: ...`) and on the
summary page. A head that really breaks the keyboard or hides the field fails both attempts.

## What is different from the local census

- **Machine.** A standard `ubuntu-latest` runner per shard, each with one emulator: up to 18
  concurrent run jobs (`max-parallel: 18`, at most 18 shards), owner-authorized 2026-11-04, which
  leaves 2 of the free plan's 20 concurrent jobs for CI. Steps are spread over shards by their run
  time (`weights.tsv`: cloud seconds where measured, else local); every tag of a step runs on the
  same emulator, in the order `B-r1`, heads, `B-r2`, so the longest step (`guided`) sets the floor.
- **Image.** `system-images;android-36.1;google_apis_playstore;x86_64`: the same Android 16 QPR2 Play
  build as the local AVD (BE4B.251210.005, a user build), but x86_64 under KVM instead of arm64-v8a.
  If it can't be installed, `android-36.1;google_apis;x86_64` (the same build, userdebug, no Play) is
  used with a warning; `results.txt` and the summary page name the image of each shard. Same
  1080x2400 at 420 dpi, 2 GB RAM, hardware keyboard, 6 GB data; 4 CPU cores (the runner has 4 vCPUs;
  the local AVD has 1).
  **The AVD must be made with a new avdmanager**: `emulator.sh` installs `cmdline-tools;23.0` and
  uses its avdmanager. The runner's own (command-line tools 12.0) writes `target=android-0` for a
  Major.Minor image such as 36.1; the emulator then skips that API's GPU features and surfaceflinger
  aborts every few seconds ("Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma"), under
  every GPU mode and emulator build tried. `create` now fails unless the AVD's `target=` names the
  image's API. Until 2026-11-04 the census used `android-36;google_apis;x86_64` to dodge that crash,
  but that image's BluetoothGatt has no `mClientRegistered` field, which android-tests' synthetic GATT
  link sets by reflection, so `guided`, `guided-queue` and `charge-schedule-wire` failed for every tag
  ("Synthetic link unavailable (is hidden_api_policy relaxed?)", caused by "Missing field
  mClientRegistered") with 2 census screens instead of 132. Android 36 and 35 images are therefore not
  fallbacks.
- **GPU.** `-gpu swiftshader_indirect` (software) instead of `-gpu host`; the other boot flags are the
  local ones (`-no-window -no-audio -no-boot-anim -read-only -no-snapshot`). Screens render the same
  text, but timing differs, so compare cloud runs only with cloud runs: base and heads of one
  comparison always run in the same workflow run. After boot, `emulator.sh boot` checks that
  surfaceflinger keeps one pid for 75 s and fails the boot (and so the shard) if it doesn't.
- **Emulator.** The latest `emulator` package from sdkmanager (37.2.13 when this was set up).
- **Signing.** One throwaway key per workflow run (made with keytool in the build job, never uploaded)
  signs every APK of that run, instead of the shared local key.
- **rc.** `results.txt`'s `rc=` is the sweep's own exit code (the local worker logs logcat's).
- **Restarts.** If a shard's emulator stops answering between runs it is restarted, and
  `results.txt` says so; each sweep run is limited to 90 minutes.

## Known cloud gaps

`report-invite-dark` and `report-invite-light` (listed in `cloud-gaps.tsv`). `ReportInviteValidation`
stops the simulated model check by hand and expects "Try again", but on the cloud emulator it gets
there about 24 s after the app starts (the laptop: 11 s), after the 10 s simulated check has already
finished ("Check finished ✓" on the landscape screen), so it fails "A stopped check did not offer Try
again" for the base and every head alike (smoke-8, run 41914239618, with 4 cores; 2 cores the same).
The four census screens it takes before that still compare, but the step's later screens and
assertions never run, so the laptop census must run both. Every other step of `steps.txt` runs in
the cloud on the 36.1 image. When a step cannot (an image without a field or service a validation
needs, say), add it to `cloud-gaps.tsv` with the reason: `report.py` then marks every run of it NOT COVERED and the diff job fails, so the step's
cloud PASS is never taken for coverage, and the laptop census has to run it.

## Boot-stability probe

`.github/workflows/census-probe.yml` (with `probe.sh`) boots one emulator per configuration in
parallel and watches surfaceflinger, uiautomator and a screenshot for 180 s after boot. It is kept
for the next emulator or image update: push a `census-run/probe-<name>` branch (census.yml ignores
those) and read each job's verdict. Probe-2 (2026-11-04, run 41887703341, commit 43ee2e5; emulator
37.2.13 unless pinned; host CPUs varied and changed nothing; every AVD made by the runner's avdmanager
12.0, so the 36.1 rows had `target=android-0`, see below):

| Config | Stable | Boot |
|---|---|---|
| android-36.1 google_apis_playstore, swiftshader_indirect (-feature -Vulkan) | no: 15 surfaceflinger crashes in 180 s | 51 s |
| android-36.1 google_apis_playstore, -gpu host (Mesa llvmpipe on Xvfb) | no: 25 crashes | 55 s |
| android-36 google_apis, swiftshader_indirect (used until probe-4; lacks a BluetoothGatt field) | yes, 0 crashes | 50 s |
| android-35 google_apis, swiftshader_indirect | yes | 35 s |
| android-34 / android-33 google_apis, swiftshader_indirect | yes | 40 / 31 s |
| android-35 google_apis, -gpu host (Mesa llvmpipe) | yes | 40 s |
| `-feature -Vulkan` on any stable image | no difference | |
| pinned emulator 35.6.11 (13610412) with android-35 / android-36 | yes | 41 / 56 s |

Probe-3 and probe-4 (2026-11-04, runs 41898211252 and 41899423791) found why 36.1 crashed: with the
runner's avdmanager 12.0 (AVD `target=android-0`), android-36.1 google_apis, 36.1 Play and 37.0
google_apis all crash surfaceflinger (6 to 9 aborts in 180 s), also with the pinned 35.6.11 emulator.
With cmdline-tools 23.0's avdmanager (`target=android-36.1` / `android-37.0`) all three are stable
(0 crashes, boot 35 to 45 s) and have every BluetoothGatt field the tests set; android-36,
36-ext18 and 36-ext19 (BE2A builds) are stable but lack `mClientRegistered`. `probe.sh` reports
`avd_target` and `gatt_missing` for each configuration (`PROBE_AVDTOOLS=new` uses avdmanager 23.0).

Earlier smoke runs also tried android-36.1 Play with `swiftshader_indirect -feature
-ReadColorBufferDma`, `guest` and `swangle_indirect -feature -ReadColorBufferDma`: all unstable
(turning ReadColorBufferDma off makes it worse; the mapper aborts when that feature is absent). If a
future emulator update breaks the stable images, `probe.sh` already knows how to pin
`emulator-linux_x64-13610412.zip` (35.6.11), a proven way back.

## Cost

Nothing: this repository is public, and standard GitHub-hosted runners (`ubuntu-latest`) are free and
unlimited in minutes for public repositories. The workflow never uses larger (billed) runners. Its
logs, artifacts and screenshots are public like the repository (simulated scooters only).
