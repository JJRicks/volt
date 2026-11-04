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
- Pushing the same branch again cancels the run in progress and starts over. A new comparison wants a
  new branch name (or a new commit on the old one).

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
| `summary.md`, `plan.json`, `builds.txt` | the job summary, the validated plan with its shards, the APK hashes |

The `census-shard-<n>` artifacts hold `out/<tag>/<step>/` (exactly the local `OUT/TAG/STEP`, with
`census/`, `summary.json`, `logcat.txt` and the screenshots) and `logs/shard-<n>/` (each run's log,
`results.txt`, the emulator log). `census-apks` has the APKs that ran (throwaway-signed; never install
them on a phone). Artifacts are kept for 3 days.

The **diff job fails** when any (tag, step) run left no `summary.json`; a failed step otherwise never
stops its shard, and text differences never fail the workflow (read them as you would locally). The
run job is red when its emulator did not boot (or a restart failed); its runs are still tried.

## What is different from the local census

- **Machine.** A standard `ubuntu-latest` runner per shard, each with one emulator: up to 18
  concurrent run jobs (`max-parallel: 18`, at most 18 shards), owner-authorized 2026-11-04, which
  leaves 2 of the free plan's 20 concurrent jobs for CI. Steps are spread over shards by their run
  time (`weights.tsv`: cloud seconds where measured, else local); every tag of a step runs on the
  same emulator, in the order `B-r1`, heads, `B-r2`, so the longest step (`guided`) sets the floor.
- **Image.** `system-images;android-36;google_apis;x86_64` (Android 16, API 36, a userdebug build
  without Play) under KVM, instead of the local `android-36.1;google_apis_playstore;arm64-v8a`. If it
  can't be installed, `android-35;google_apis;x86_64` is used, with a warning; `results.txt` names the
  image of each shard. Same 1080x2400 at 420 dpi, 2 GB RAM, hardware keyboard, 6 GB data; 2 CPU
  cores (the local AVD has 1). Why not 36.1: on `ubuntu-latest` its x86_64 Play image (BE4B.251210.005)
  aborts surfaceflinger every few seconds ("Assertion failed:
  !rcEnc->featureInfo()->hasReadColorBufferDma" in the goldfish mapper) under every GPU mode tried
  (swiftshader, swangle, guest, host Mesa), so System UI never settles and every run hangs. A likely
  cause is the runner's old avdmanager (command-line tools 12.0) writing `target=android-0` for
  Major.Minor platforms, so the emulator skips its per-API GPU features; `create` prints the AVD's
  root ini so this can be checked. The census only compares cloud runs with cloud runs of the same
  workflow run, so the image needn't match the local one; per-app languages need API 33 or newer.
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

## Boot-stability probe

`.github/workflows/census-probe.yml` (with `probe.sh`) boots one emulator per configuration in
parallel and watches surfaceflinger, uiautomator and a screenshot for 180 s after boot. It is kept
for the next emulator or image update: push a `census-run/probe-<name>` branch (census.yml ignores
those) and read each job's verdict. Its 2026-11-04 result (run 41887703341, commit 43ee2e5;
emulator 37.2.13 unless pinned; host CPUs varied and changed nothing):

| Config | Stable | Boot |
|---|---|---|
| android-36.1 google_apis_playstore, swiftshader_indirect (-feature -Vulkan) | no: 15 surfaceflinger crashes in 180 s | 51 s |
| android-36.1 google_apis_playstore, -gpu host (Mesa llvmpipe on Xvfb) | no: 25 crashes | 55 s |
| **android-36 google_apis, swiftshader_indirect** (used) | yes, 0 crashes | 50 s |
| android-35 google_apis, swiftshader_indirect (fallback) | yes | 35 s |
| android-34 / android-33 google_apis, swiftshader_indirect | yes | 40 / 31 s |
| android-35 google_apis, -gpu host (Mesa llvmpipe) | yes | 40 s |
| `-feature -Vulkan` on any stable image | no difference | |
| pinned emulator 35.6.11 (13610412) with android-35 / android-36 | yes | 41 / 56 s |

Earlier smoke runs also tried android-36.1 Play with `swiftshader_indirect -feature
-ReadColorBufferDma`, `guest` and `swangle_indirect -feature -ReadColorBufferDma`: all unstable
(turning ReadColorBufferDma off makes it worse; the mapper aborts when that feature is absent). If a
future emulator update breaks the stable images, `probe.sh` already knows how to pin
`emulator-linux_x64-13610412.zip` (35.6.11), a proven way back.

## Cost

Nothing: this repository is public, and standard GitHub-hosted runners (`ubuntu-latest`) are free and
unlimited in minutes for public repositories. The workflow never uses larger (billed) runners. Its
logs, artifacts and screenshots are public like the repository (simulated scooters only).
