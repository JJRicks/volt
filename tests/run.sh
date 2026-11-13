#!/usr/bin/env bash
# Pure Java tests: no Android SDK, phone, internet connection, or signing key.
set -euo pipefail
umask 077
TEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source "$TEST_DIR/../build.sh"
navee_find_java
TEST_BUILD_DIR="$NAVEE_BUILD_DIR/core-tests"
# The suites' temporary folders (Files.createTempDirectory, File.createTempFile) go in one folder
# for this run, which is removed when the run ends, pass or fail; otherwise each run leaves
# thousands behind. VOLT_KEEP_TEST_TMP=1 keeps it, to look at after a failure.
TEST_TMP="$(mktemp -d "${TMPDIR:-/tmp}/volt-core-tests.XXXXXX")"
TEST_SHM_TMP=""
remove_test_tmp() {
    if [[ "${VOLT_KEEP_TEST_TMP:-}" == 1 ]]; then
        echo "Kept the tests' temporary files: $TEST_TMP${TEST_SHM_TMP:+ and $TEST_SHM_TMP}" >&2
    else
        rm -rf -- "$TEST_TMP" ${TEST_SHM_TMP:+"$TEST_SHM_TMP"}
    fi
}
trap remove_test_tmp EXIT
java() { command java -Djava.io.tmpdir="$TEST_TMP" "$@"; }
rm -rf -- "$TEST_BUILD_DIR/classes"
mkdir -p "$TEST_BUILD_DIR/classes"
sources=()
for name in ChargeScheduleController ChargeScheduleTest ChargerDetection Protocol Telemetry ScooterCommands OwnerIdentityParser RideStore RideArchive PhoneRideStore DashboardLayout BatteryCapacityEstimate LearnedRangeEstimate ChargingEstimate OwnerCaptureEngine ScooterSimulation QuickAccessActions DisplayTemperature GaugeScaleInput RangeFollowPolicy RideChartData RegenInference ChartScale ChargeSessionStore ChargeEstimateHistory ChargeChartData StatsFavouriteOrder AppDataArchive BackupExportCache ConnectionFeedbackState ChargeRecoveryPolicy ConnectionEventLog LockSliderGeometry; do
    sources+=("$APP_DIR/src/com/jjricks/volt/$name.java" "$TEST_DIR/${name}Test.java")
done
sources+=("$APP_DIR/src/com/jjricks/volt/Ut5MaxStockVoltageReference.java" "$TEST_DIR/StockStartupReferenceTest.java" "$TEST_DIR/StockVoltageReferenceTest.java" "$TEST_DIR/StockVoltageReplayTrace.java" "$TEST_DIR/StockVoltageReplayTest.java" "$TEST_DIR/DualChargerEstimateTest.java" "$APP_DIR/src/com/jjricks/volt/Ut5MaxFastChargeReference.java" "$TEST_DIR/Ut5MaxFastChargeReferenceTest.java" "$APP_DIR/src/com/jjricks/volt/ChargeTaperMatcher.java" "$TEST_DIR/StockChargeHoldoutTrace.java" "$TEST_DIR/ChargeTaperMatcherTest.java" "$APP_DIR/src/com/jjricks/volt/OwnerCaptureProcess.java" "$APP_DIR/src/com/jjricks/volt/Ut5MaxStockChargeReference.java" "$APP_DIR/src/com/jjricks/volt/ChargeReferenceGate.java" "$TEST_DIR/CompletedChargeEstimateTest.java" "$TEST_DIR/StockChargeReferenceTest.java" "$TEST_DIR/ChargeReferenceGateTest.java")
sources+=( )
# Compile the actual Android coordinator against test-only binding and preferences APIs.
# These stubs are confined to this test output and never enter the Android build.
for source in "$TEST_DIR"/shizuku-stubs/android/content/*.java \
              "$TEST_DIR"/shizuku-stubs/android/content/pm/*.java \
              "$TEST_DIR"/shizuku-stubs/android/os/*.java \
              "$TEST_DIR"/shizuku-stubs/rikka/shizuku/*.java \
              "$TEST_DIR"/shizuku-stubs/com/jjricks/volt/*.java; do
    sources+=("$source")
done
sources+=("$APP_DIR/src/com/jjricks/volt/ShizukuCaptureClient.java" "$TEST_DIR/ShizukuCaptureClientTest.java")
# Route speed colours (depends on PhoneRideStore and RideStore above).
sources+=("$APP_DIR/src/com/jjricks/volt/RouteSpeedScale.java" "$TEST_DIR/RouteSpeedScaleTest.java")
# Lock slider motion trail.
sources+=("$APP_DIR/src/com/jjricks/volt/LockSliderTrail.java" "$TEST_DIR/LockSliderTrailTest.java")
# Lock taps held from the connection haptic until the scooter takes writes.
sources+=("$APP_DIR/src/com/jjricks/volt/LockTapQueue.java" "$TEST_DIR/LockTapQueueTest.java")
# Riding mode core: layouts, live values, gauge smoothing, storage and burn-in drift (riding-mode plan, PR 1).
for name in RidingLayout RidingValues GaugeSmoother RidingConfig PowerSign RidingPowerCue; do sources+=("$APP_DIR/src/com/jjricks/volt/$name.java" "$TEST_DIR/${name}Test.java"); done
sources+=("$APP_DIR/src/com/jjricks/volt/RidingBurnIn.java")
# Riding mode's chip row: which chips fit, and at which compact rung.
sources+=("$APP_DIR/src/com/jjricks/volt/RidingChipFit.java" "$TEST_DIR/RidingChipFitTest.java")
# Riding mode's reading names in a group: icons for all or names for all, by one rule (Halo's strip and readings).
sources+=("$APP_DIR/src/com/jjricks/volt/RidingNameGroup.java" "$TEST_DIR/RidingNameGroupTest.java")
# Wide layout or phone layout: window width over text scale (WideLayout).
sources+=("$APP_DIR/src/com/jjricks/volt/WideLayout.java" "$TEST_DIR/WideLayoutTest.java")
# Hub pages: the page tree, legacy ids, and MainActivity's page stack (hub layout plan, PR 1).
sources+=("$APP_DIR/src/com/jjricks/volt/HubPages.java" "$TEST_DIR/HubPagesTest.java" "$APP_DIR/src/com/jjricks/volt/HubNav.java" "$TEST_DIR/HubNavTest.java")
# Home model: Home style and its seed, the top area's height, moments and Clean's quick actions (hub layout plan, PR 6),
# and the What's new sheet's bundled release notes (part 9).
for name in HomeStyle TopArea HomeMoment HomeQuickActions WhatsNewNotes; do sources+=("$APP_DIR/src/com/jjricks/volt/$name.java" "$TEST_DIR/${name}Test.java"); done
# Clean Home's charging view: what shows in its slot, the readings last to give way (council review 5886927849).
sources+=("$APP_DIR/src/com/jjricks/volt/ChargingFit.java" "$TEST_DIR/ChargingFitTest.java")
# Reading tiles: the last tile spans a part-filled row (ReadingSpan).
sources+=("$APP_DIR/src/com/jjricks/volt/ReadingSpan.java" "$TEST_DIR/ReadingSpanTest.java")
# Scooter tab menu layout: order, hiding and its backup rule (scooter-menu-edit plan, PR 1).
sources+=("$APP_DIR/src/com/jjricks/volt/ScooterMenuLayout.java" "$TEST_DIR/ScooterMenuLayoutTest.java")
# Hub subjects: which scooter rows each page shows, and the subjects' summaries (hub layout plan, PR 3).
sources+=("$APP_DIR/src/com/jjricks/volt/ControlsPages.java" "$TEST_DIR/ControlsPagesTest.java" "$APP_DIR/src/com/jjricks/volt/HubSummaries.java" "$TEST_DIR/HubSummariesTest.java")
# Hub routes: where each saved Quick access id leads once the quick-access sheets are gone (hub layout plan, PR 4).
sources+=("$APP_DIR/src/com/jjricks/volt/HubRoutes.java" "$TEST_DIR/HubRoutesTest.java")
# The shortcut picker's sections, first open section, search and availability; the Charge alerts shortcut's value.
sources+=("$APP_DIR/src/com/jjricks/volt/ShortcutPicker.java" "$TEST_DIR/ShortcutPickerTest.java")
# Ride and Data as subjects: the moved switches keep their keys and defaults (hub layout plan, PR 5).
sources+=("$TEST_DIR/HubRideDataTest.java")
# Email to Volt: which refusal of the charge export is worth a toast (council review of #396).
sources+=("$APP_DIR/src/com/jjricks/volt/ChargeSend.java" "$TEST_DIR/ChargeSendTest.java")
# A drag in ReorderList counts from where the finger is when the row lifts, past the touch slop.
sources+=("$APP_DIR/src/com/jjricks/volt/ReorderDrag.java" "$TEST_DIR/ReorderDragTest.java")
# Ride auto-end policy and its journal/learned-range effects.
for name in RideAutoStopPolicy; do
    sources+=("$APP_DIR/src/com/jjricks/volt/$name.java" "$TEST_DIR/${name}Test.java")
done
sources+=("$TEST_DIR/RideAutoStopStoreTest.java")
# Riding mode's Max and Avg, fed from telemetry as readings arrive; and the auto-end count, recoverable with the journal.
sources+=("$APP_DIR/src/com/jjricks/volt/RidingSpeed.java" "$APP_DIR/src/com/jjricks/volt/RideAutoEnds.java" "$TEST_DIR/RideAutoEndsTest.java")
# Whether riding mode shows Home after a lost connection, and which ends of a link count.
sources+=("$APP_DIR/src/com/jjricks/volt/RidingLink.java" "$TEST_DIR/RidingLinkTest.java")
# Saved ride summaries for a fast first Rides open (RideSummaryStore; RideStore and RideArchive are above).
sources+=("$APP_DIR/src/com/jjricks/volt/RideSummaryStore.java" "$TEST_DIR/RideSummaryStoreTest.java")
# The hardware check's Rides first-open timing helper (android-tests, no Android types).
sources+=("$APP_DIR/android-tests/RidesOpenTiming.java" "$TEST_DIR/RidesOpenTimingTest.java")
# Unified backup: category registry, archive, plan and transaction (pure Java, temporary files only).
for name in BackupRegistry BackupJson BackupProfiles BackupArchive BackupTransaction; do sources+=("$APP_DIR/src/com/jjricks/volt/$name.java"); done
for name in BackupRegistryTest BackupTestStore BackupArchiveTest BackupPlanTest BackupTransactionTest; do sources+=("$TEST_DIR/$name.java"); done
# Model compatibility suites (separate block to limit merge conflicts with sibling branches).
COMPAT_TESTS=(DeviceDescriptor AdvertisedIdentity StatusFields WireCommands ModelRegistry CapabilityResolver MutationGate ReadPolicy DiscoveryCoordinator CompatibilityReport TestJournal GuidedTest ReportInvite ConnectionReport UpdateCheck VersionName DriveModeSurvey NaveeModels IssueReport ModelTiles CommandLog DiagnosticsBundle ProximityFollowUp)
for name in "${COMPAT_TESTS[@]}"; do sources+=("$APP_DIR/src/com/jjricks/volt/$name.java" "$TEST_DIR/${name}Test.java"); done
sources+=("$APP_DIR/src/com/jjricks/volt/FeatureVariant.java" "$APP_DIR/src/com/jjricks/volt/KickStartTry.java" "$TEST_DIR/OemControlsTest.java" "$APP_DIR/src/com/jjricks/volt/OwnerNotes.java" "$TEST_DIR/ExperimentalControlsTest.java")
# Standard-charger pack model (ut5-stock-pack-v6), its generated parity table and charge-age tracking.
for name in Ut5MaxStockPackModel ChargeProgressTracker ChargeMeasureWindow; do sources+=("$APP_DIR/src/com/jjricks/volt/$name.java" "$TEST_DIR/${name}Test.java"); done
sources+=("$TEST_DIR/StockPackParity.java")
# High-speed voltage/current taper (ut5-fast-taper-v8) and its owner-charge replay.
sources+=("$APP_DIR/src/com/jjricks/volt/FastChargeTaper.java" "$TEST_DIR/FastChargeTaperTest.java" "$TEST_DIR/FastChargeTaperReplay.java")
# Battery alerts (charge level, full, low) for models without a native charge limit; uses the compat suites' resolver.
sources+=("$APP_DIR/src/com/jjricks/volt/ChargeAlertPolicy.java" "$TEST_DIR/ChargeAlertPolicyTest.java")
# Notification spacing under Android's per-app enqueue rate limit.
sources+=("$APP_DIR/src/com/jjricks/volt/NotificationPacer.java" "$TEST_DIR/NotificationPacerTest.java")
# Riding mode's live range: estimator, calm readout and synthetic rides (docs/validation/live-range.md).
sources+=("$APP_DIR/src/com/jjricks/volt/LiveRangeEstimator.java" "$APP_DIR/src/com/jjricks/volt/RangeReadout.java" "$TEST_DIR/LiveRangeScenarios.java" "$TEST_DIR/LiveRangeEstimatorTest.java")
# Maintenance reminders core: manual intervals, the forward-only odometer, due-ness and storage (maintenance plan, PR 1).
for name in MaintenanceDistance MaintenancePolicy MaintenanceStore; do sources+=("$APP_DIR/src/com/jjricks/volt/$name.java" "$TEST_DIR/${name}Test.java"); done
sources+=("$APP_DIR/src/com/jjricks/volt/MaintenanceDefaults.java" "$TEST_DIR/MaintenanceBackupTest.java")
# Localization (l10n/README.md): the generated text catalog (T, M, EnglishText, AppLanguages), the Msg
# that M needs, and every tests/l10n/*.sources list. Each line of a list is a repository path to compile
# ("#" starts a comment); every listed tests/l10n/**Test.java then runs from the repository root as
# com.jjricks.volt.<Name>. Like build.sh, all of it waits for the first res/values/strings_*.xml, and a
# batch adds its own .sources file instead of editing this script.
l10n_tests=()
if navee_l10n_active; then
    L10N_GENERATED="$TEST_BUILD_DIR/generated"
    rm -rf -- "$L10N_GENERATED"
    python3 "$APP_DIR/tools/l10n/gen_catalog.py" --res "$APP_DIR/res" --lang "$APP_DIR/l10n/lang" --out "$L10N_GENERATED"
    for name in T M EnglishText AppLanguages; do sources+=("$L10N_GENERATED/com/jjricks/volt/$name.java"); done
    sources+=("$APP_DIR/src/com/jjricks/volt/Msg.java")
    for list in "$TEST_DIR"/l10n/*.sources; do
        [[ -f "$list" ]] || continue
        while IFS= read -r line || [[ -n "$line" ]]; do
            line="${line%%#*}"; line="${line#"${line%%[![:space:]]*}"}"; line="${line%"${line##*[![:space:]]}"}"
            [[ -n "$line" ]] || continue
            [[ -f "$APP_DIR/$line" && "$line" == *.java ]] || { echo "$list: not a Java file in this repository: $line" >&2; exit 1; }
            sources+=("$APP_DIR/$line")
            if [[ "$line" == tests/l10n/*Test.java ]]; then l10n_tests+=("$(basename -- "$line" .java)"); fi
        done < "$list"
    done
fi
# A list may name a source the lines above already compile; javac rejects a file given twice.
unique_sources=(); seen_sources=$'\n'
for source in "${sources[@]}"; do
    case "$seen_sources" in *$'\n'"$source"$'\n'*) continue ;; esac
    unique_sources+=("$source"); seen_sources+="$source"$'\n'
done
javac --release 8 -encoding UTF-8 -d "$TEST_BUILD_DIR/classes" "${unique_sources[@]}"
for name in ChargeScheduleController ChargeScheduleTest ChargerDetection Protocol Telemetry ScooterCommands OwnerIdentityParser RideStore RideArchive PhoneRideStore DashboardLayout BatteryCapacityEstimate LearnedRangeEstimate ChargingEstimate OwnerCaptureEngine ScooterSimulation QuickAccessActions DisplayTemperature GaugeScaleInput RangeFollowPolicy RideChartData RegenInference ChartScale ChargeSessionStore ChargeEstimateHistory ChargeChartData StatsFavouriteOrder AppDataArchive BackupExportCache ConnectionFeedbackState ChargeRecoveryPolicy ConnectionEventLog LockSliderGeometry; do
    java -cp "$TEST_BUILD_DIR/classes" "com.jjricks.volt.${name}Test"
done

java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.Ut5MaxStockPackModelTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.ChargeProgressTrackerTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.ChargeMeasureWindowTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.ChargeAlertPolicyTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.NotificationPacerTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.LiveRangeEstimatorTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.FastChargeTaperTest "$APP_DIR/docs/validation/data/fast-charge-taper-v7"
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.CompletedChargeEstimateTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.StockStartupReferenceTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.StockChargeReferenceTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.StockVoltageReplayTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.ChargeTaperMatcherTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.ChargeReferenceGateTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.ShizukuCaptureClientTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.RouteSpeedScaleTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.LockSliderTrailTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.LockTapQueueTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.WideLayoutTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.HubPagesTest "$APP_DIR/src/com/jjricks/volt/MoreScreen.java" "$APP_DIR/src/com/jjricks/volt/Ui.java"
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.HubNavTest
for name in HomeStyle TopArea HomeMoment HomeQuickActions WhatsNewNotes; do java -cp "$TEST_BUILD_DIR/classes" "com.jjricks.volt.${name}Test"; done
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.ChargingFitTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.ReadingSpanTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.ScooterMenuLayoutTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.ControlsPagesTest "$APP_DIR/src/com/jjricks/volt/MainActivity.java"
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.HubSummariesTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.HubRoutesTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.ShortcutPickerTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.HubRideDataTest "$APP_DIR/src/com/jjricks/volt/MoreScreen.java" "$APP_DIR/src/com/jjricks/volt/RidesScreen.java"
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.ChargeSendTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.ReorderDragTest
for name in RidingLayout RidingValues GaugeSmoother RidingConfig PowerSign RidingPowerCue; do java -cp "$TEST_BUILD_DIR/classes" "com.jjricks.volt.${name}Test"; done
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.RidingChipFitTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.RidingNameGroupTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.RideAutoStopPolicyTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.RideAutoStopStoreTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.RideAutoEndsTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.RidingLinkTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.RideSummaryStoreTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.RidesOpenTimingTest

# Unified backup suites.
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.BackupRegistryTest "$APP_DIR/src/com/jjricks/volt"
# These suites run hundreds of synthetic restores; a RAM-backed temporary folder keeps their
# fsyncs fast. AppDataArchiveTest and RideArchiveTest still exercise the normal folder.
# Bash 3.2 treats an empty array as unset under nounset. Keep the command array nonempty.
backup_java=(java)
if [[ -d /dev/shm && -w /dev/shm ]]; then
    TEST_SHM_TMP="$(mktemp -d /dev/shm/volt-core-tests.XXXXXX)"
    # The java function puts its own -Djava.io.tmpdir first, and the later one on a command line wins.
    backup_java+=(-Djava.io.tmpdir="$TEST_SHM_TMP")
fi
for name in BackupArchiveTest BackupPlanTest BackupTransactionTest; do
    (cd "$APP_DIR" && "${backup_java[@]}" -cp "$TEST_BUILD_DIR/classes" "com.jjricks.volt.$name")
done

# Maintenance reminders (MaintenanceStoreTest uses the compat suites' NaveeModels; the backup round trip uses the backup stores).
for name in MaintenanceDistanceTest MaintenancePolicyTest MaintenanceStoreTest; do java -cp "$TEST_BUILD_DIR/classes" "com.jjricks.volt.$name"; done
(cd "$APP_DIR" && "${backup_java[@]}" -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.MaintenanceBackupTest)

# From the repo root: ModelRegistryTest checks the bundled model photos.
for name in "${COMPAT_TESTS[@]}" OemControls ExperimentalControls; do (cd "$APP_DIR" && java -cp "$TEST_BUILD_DIR/classes" "com.jjricks.volt.${name}Test"); done

java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.DualChargerEstimateTest
java -cp "$TEST_BUILD_DIR/classes" com.jjricks.volt.Ut5MaxFastChargeReferenceTest "$TEST_DIR/fixtures/ut5-max-fast-charge-calibration.csv"

for name in StockVoltageReferenceTest; do java -cp "$TEST_BUILD_DIR/classes" "com.jjricks.volt.$name"; done

# build.sh versionName: dev marker, release flag, trees that aren't their own checkout.
bash "$TEST_DIR/build-version-test.sh"
# Homepage variants: site/build.sh contract, variant markup and CSP check, nginx headers, live switch (site/README.md).
python3 -I "$TEST_DIR/site-homepage-test.py"
# Localization suites from tests/l10n/*.sources (see above), from the repository root.
for name in ${l10n_tests[@]+"${l10n_tests[@]}"}; do (cd "$APP_DIR" && java -cp "$TEST_BUILD_DIR/classes" "com.jjricks.volt.$name"); done
