package com.jjricks.volt;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

import org.json.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Foreground connected-device service keeps recording alive through folds and screen-off. */
public final class ScooterService extends Service {
    private static volatile boolean uiVisible;
    private static volatile ScooterService running;
    /** Main may have lost its binder/permission while the foreground service still owns data. */
    static ScooterService currentForMaintenance() { return running; }
    /** Main calls this before a binder exists, so late service starts see current visibility. */
    public static void setUiVisible(boolean value){
        uiVisible=value;ScooterService service=running;if(service==null)return;
        if(Looper.myLooper()==Looper.getMainLooper())service.applyVisibility(value);
        else service.handler.post(()->{if(running==service)service.applyVisibility(uiVisible);});
    }
    public final class LocalBinder extends Binder {public ScooterService service(){return ScooterService.this;}}
    private final LocalBinder binder=new LocalBinder();private final Handler handler=new Handler(Looper.getMainLooper());private final NotificationPacer<java.util.function.Supplier<Notification>> notices=new NotificationPacer<>(CHARGE_ALERT_NOTICE);private final Runnable noticeDrain=this::drainNotices;private final LinkedHashSet<Runnable> listeners=new LinkedHashSet<>();
    public ScooterProfiles profiles;public BleController ble;public RideStore rides;public PhoneRideStore phoneRides;public TelemetryHistory history;public SharedPreferences settings;
    private final Map<String,TelemetryHistory.Snapshot> displayCache=new LinkedHashMap<>();
    private final Set<String> loadingHistory=new HashSet<>(),clearingHistory=new HashSet<>();
    private final Map<String,Long> observedSources=new HashMap<>(),historyClearCutoff=new HashMap<>();
    private String observedProfile = "";
    private boolean historyConnected,historyDirty;private long historySession,lastHistoryWrite;
    private long historyWall,historyMono;
    private long historySessionCounter=new java.util.Random().nextLong()&Long.MAX_VALUE;
    private final ExecutorService capacityWorker = Executors.newSingleThreadExecutor(r -> new Thread(r, "battery-capacity-analysis"));
    private final Map<String, CapacityCache> capacityCache = new LinkedHashMap<>();
    private final Map<String, List<Consumer<BatteryCapacityEstimate.Result>>> capacityPending = new HashMap<>();
    private long capacityGeneration;
    private final Map<String, LearnedRangeEstimate.Result> rangeCache = new LinkedHashMap<>();
    private final Map<String, RangeJob> rangePending = new HashMap<>();
    private static final class RangeJob {
        final String profile;
        final List<Consumer<LearnedRangeEstimate.Result>> callbacks = new ArrayList<>();
        int restarts;
        RangeJob(String profile) { this.profile = profile; }
    }
    private static final class CapacityCache {
        final long mono;
        final BatteryCapacityEstimate.Result result;
        CapacityCache(long mono, BatteryCapacityEstimate.Result result) { this.mono = mono; this.result = result; }
    }
    private ChargeScheduleController chargeScheduleController;
    /** Report diagnostics' charge evidence (no_backup/charge-evidence), per saved scooter; null until onCreate. */
    private ChargeEvidence evidence;
    /** The saved scooter whose authenticated link fed the last evidence sample, or null. */
    private String evidenceScope;
    /** The selected scooter whose evidence file was last asked to load. */
    private String evidenceSelected;
    private ChargeScheduleController.TestResult pendingScheduleTest;
    /** The start this measured arm's scooter confirmed in time, 0 until then. */
    private long measuredStartSent;
    private ChargeSessionStore chargeStore;
    private ChargeSessionStore.Snapshot chargeSnapshot;
    private final ChargeReferenceGate chargeReferenceGate = new ChargeReferenceGate();
    private final ChargerDetection chargerDetection = new ChargerDetection();
    private final ChargeProgressTracker chargeProgress = new ChargeProgressTracker();
    private final FastChargeTaper chargeTaper = new FastChargeTaper();
    /** Charge done by's bounded measuring step; at most one, for the selected scooter. */
    private final ChargeMeasureWindow chargeMeasure = new ChargeMeasureWindow();
    private boolean chargeModelLoading, chargeUpdating, chargeExporting;
    private long chargeEndingMono;
    private ChargeRecoveryPolicy chargeRecovery;
    private CharSequence chargeStatus = M.serviceChargeNone();
    /** The status finishChargeCapture last set; chargeStatusIsOutcome compares by identity, so any newer status ends it. */
    private CharSequence chargeOutcome;
    private final ExecutorService chargeWorker = Executors.newSingleThreadExecutor(r -> new Thread(r,"charge-curve-analysis"));
    private PhoneRideRecorder phoneRecorder;private boolean phoneLocationForeground;private int phoneSavingCount;private String phoneFinalStatus;
    /** The finished recorder's statusFlags(), beside phoneFinalStatus (its English status). */
    private EnumSet<PhoneRideRecorder.StatusFlag> phoneFinalFlags;
    /** The locales the notices were last labelled in; null until onCreate records them (plan section 5.4). */
    private android.os.LocaleList noticeLocales;
    /** The language the notices' text resolved to then (shownLanguage()); null until onCreate records it. */
    private java.util.List<?> noticeLanguage;
    private static final long RECORDING_WAKE_TIMEOUT_MS=10*60*1000L, RECORDING_WAKE_RENEW_MS=5*60*1000L;
    private long wakeLockRenewedMs;
    private PowerManager.WakeLock wakeLock;private boolean visible,foregroundStarted,gapMarked,workActive,destroyed;private int foregroundType;private int samples;private long lastNotification,backgroundDeadlineMs;
    private String trendProfile = "", recordingError;
    /** Forgotten-ride decision for the active ride only; rebuilt unarmed after any restart. */
    final AutoStartPolicy autoStart=new AutoStartPolicy();
    private RideAutoStopPolicy rideAutoStop;private long rideAutoStopDisconnects;
    /** Opt-in copies of finished rides to the rider's folder (RideAutoExport); null when the service never started. */
    private RideAutoExportRunner rideAutoExport;
    /** A failed automatic ride copy, posted only while Volt is hidden (rideAutoExportFinished). */
    private static final int AUTO_EXPORT_NOTICE = 78;
    /** The failure kind AUTO_EXPORT_NOTICE posted for the current stored failure (RideAutoExport.notifiedAfter); NONE if none. */
    private RideAutoExport.Problem autoExportNotified = RideAutoExport.Problem.NONE;
    private RideAutoEnds autoEnds;
    static final String RIDE_AUTO_STOP = "ride_auto_stop",RIDE_AUTO_ENDS = "ride_auto_ends",RIDE_AUTO_ENDS_COUNTED = "ride_auto_ends_counted";private static final String START_SENT_NOTICE = "charge_start_sent_notice", START_SENT_SCOPE = "charge_start_sent_scope";private static final int AUTO_STOP_NOTICE = 72, CHARGE_DONE_NOTICE = 73, MISSED_START_NOTICE = 74, SCHEDULE_TEST_NOTICE = 75, CHARGE_ALERT_NOTICE = 76, ALERT_PAUSED_NOTICE = 77;
    /** Battery alerts for the selected scooter (ChargeAlertPolicy); its settings and session state are per scooter. */
    private ChargeAlertPolicy alertPolicy;private String alertProfile="",alertNoticeKey="",alertTitle="",alertText="";
    private boolean alertUpdating,alertHeld,alertNoticeKept;
    /**
     * The last alert that fired (its notices show it), and one that fired in sight and waits for MainActivity's dialog.
     * Both render when shown (plan R2); alertTitle and alertText stay the empty text shown before any alert fired.
     */
    private AlertShown alertShown,pendingChargeAlert;
    /** What battery alert notice 76 shows (AlertPost), recorded as it is built to post; null once it is cancelled. */
    private AlertPost alertPosted;
    static final String ALERT_CHANNEL="charge_alerts";private static final long ALARM_MS=60_000L;
    /** Live charging (ChargeLiveHold, ChargeLiveState): the hold, its card's last shown key, and its one done notice. */
    private final ChargeLiveHold chargeLive=new ChargeLiveHold();
    private boolean chargeLiveHeld,chargeLiveUpdating;
    private final ChargeLiveState.Reposts chargeLiveReposts=new ChargeLiveState.Reposts();
    private final ChargeLiveDial chargeLiveDial=new ChargeLiveDial();
    /** The live card the last notification() built (null: none); what a connection notice post then carries. */
    private ChargeLiveState chargeLiveBuilt;
    /** What the connection notice last posted (NoticeRepeat), and what the last notification() set that the notice can't report back. */
    private final NoticeRepeat noticeRepeat=new NoticeRepeat();private Object noticeBuiltExtra;
    /** The connection notice's tap and its action, made once each: every rebuild used to ask Android for them again. */
    private PendingIntent noticeOpen;private final Map<String,PendingIntent> noticeActions=new HashMap<>();
    /** The labels each channel was last created with in this process: Android keeps a channel, so it's created again only when its language changes. */
    private static String scooterChannelLabel,alertChannelLabel;
    private static final int CHARGE_LIVE_DONE_NOTICE=79;
    /** The card's Disconnect (Stop before 2026-11-06): ends Volt's own hold and card, and never sends the scooter anything. */
    static final String STOP_CHARGE_LIVE="stop_charge_live";
    static final String CHARGE_LIVE_ENABLED="charge_live_enabled";
    /** More › Bluetooth › Background connection: stay connected out of sight. Stored settings and android-tests use this key. */
    static final String BACKGROUND_CONNECTION="background_connection";
    static final String CHARGE_LIVE_LOCK_DETAIL="charge_live_lock_detail";
    /**
     * The card's Stops, one per scooter whose charge it was pressed for, kept on this device so a recreated service
     * doesn't arm them again (not backed up): a JSON object of profile id to {"best": level, "wall": when pressed}.
     */
    static final String CHARGE_LIVE_STOPPED="charge_live_stopped";
    private boolean chargeLiveRestored;
    private java.util.List<Object> chargeLiveSaved;
    /** When the current hold armed (wall clock), for telling this charge's battery alert from an older one; 0 for none. */
    private long chargeLiveArmedWall;
    private final Runnable chargeLiveCheck=this::updateChargeLive;
    private android.media.MediaPlayer alarm;private android.media.AudioFocusRequest alarmFocus;private long alarmEndMono;
    private final Runnable alarmTimeout=this::stopChargeAlarm;
    private final RangeFollowPolicy rangeFollowPolicy = new RangeFollowPolicy();
    private boolean selectingRange;
    private final ArrayDeque<Ui.Point> power=new ArrayDeque<>(),voltage=new ArrayDeque<>(),speed=new ArrayDeque<>();
    private boolean dataMaintenance, guardedLifecycle;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged =
            (prefs, key) -> {
                if (key != null && key.startsWith("charge_charger_")) {
                    if (Looper.myLooper() == Looper.getMainLooper()) chargeReferenceGate.reset();
                    else handler.post(chargeReferenceGate::reset);
                    return;
                }
                if (key == null) return;
                if (key.equals(ChargeAutoRecordPolicy.KEY)) {
                    // Record charges automatically: turning it on looks at once (observeAutoCharge), so a charge already under way starts.
                    // Turning it on also releases a refusal for lack of room (Terra's review of #416, finding 1): it tries again.
                    Runnable look = () -> { if (destroyed) return; if (autoChargeEnabled()) releaseAutoChargeNoRoom(null); changed(); };
                    if (Looper.myLooper() == Looper.getMainLooper()) look.run();
                    else handler.post(look);
                    return;
                }
                if (!key.equals(BACKGROUND_CONNECTION) && !key.equals("auto_connect")) return;
                Runnable apply =
                        () -> {
                            if (!destroyed)
                                applyWorkPolicy(
                                        key.equals("auto_connect")
                                                && settings.getBoolean("auto_connect", true));
                        };
                if (Looper.myLooper() == Looper.getMainLooper()) apply.run();
                else handler.post(apply);
            };

    @Override public void onCreate(){super.onCreate();
        // A notification or delayed Activity bind must not reopen stores during backup/restore.
        // MainActivity recovers an interrupted restore off-main before starting this service.
        // A sticky system restart cannot open a half-restored data tree in the meantime.
        if (!DataMaintenance.readyForStartup(this)) {
            guardedLifecycle = destroyed = true; stopSelf(); return;
        }
        settings = getSharedPreferences("app_settings", MODE_PRIVATE);
        autoEnds = new RideAutoEnds(new RideAutoEnds.Store(){
            public long count(){return settings.getLong(RIDE_AUTO_ENDS,0);}
            public String counted(){return settings.getString(RIDE_AUTO_ENDS_COUNTED,"");}
            public void save(long count,String counted){settings.edit().putLong(RIDE_AUTO_ENDS,count).putString(RIDE_AUTO_ENDS_COUNTED,counted).commit();}
        });
        maintenance = new MaintenanceKeeper(maintenanceStorage());
        profiles=new ScooterProfiles(this);ble=new BleController(this,profiles);ble.setListener(this::changed);
        startEvidence();
        maintenanceNotices = new MaintenanceNotices(this, profiles);
        maintenance.listen(this::maintenanceChanged);
        MaintenanceNotices.plan(this, maintenance, profiles, System.currentTimeMillis(), this::maintenanceLive);
        // A guided test interrupted by process death stays outstanding; nothing is written on restart.
        ble.recoverTests();try{
            // Only a ride that may still be recording is read now; warmRides() reads the rest after the connection starts.
            rides = new RideStore(new File(getFilesDir(), "rides"), true, new File(getNoBackupFilesDir(), "rides-index"));
            autoEnds.reconcile(rides.automaticStopsAtLoad());
        }catch(IOException e){rides=null;}
        history=new TelemetryHistory(this);ensureDisplayCache();
        try { chargeStore=new ChargeSessionStore(new File(getFilesDir(),"charge-recordings"),ChargeAutoRecordPolicy.RULES);refreshChargeCurves(); }
        catch(IOException unavailable){chargeStatus=M.serviceChargeStorageUnavailable();}

        try{
            phoneRides = new PhoneRideStore(new File(getFilesDir(), "phone-rides"));
        }catch(IOException ignored){}
        PowerManager pm=getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Volt:Recorder");
        wakeLock.setReferenceCounted(false);
        visible=uiVisible;running=this;settings.registerOnSharedPreferenceChangeListener(settingsChanged);ble.visibility(visible,isRecording()||isChargeRecording()||holding(),backgroundDeadlineMs);
        applyLocale();
        rideAutoExport=new RideAutoExportRunner(this,settings,handler,this::rideAutoExportFinished);
        // Rides an earlier pass could not copy (folder gone, storage full, process ended) are retried at start.
        startRideAutoExport();
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration){
        super.onConfigurationChanged(configuration);applyLocale();
    }
    /**
     * Re-labels the notification channels and the connection notice when the language Volt's text comes from
     * changes. A rotation, fold, night mode or font change keeps the locale list, so nothing happens then. The first
     * call (onCreate) records the list and the shown language, and re-labels an existing alerts channel. The in-app
     * language choice on API 26-32 calls this too (plan section 5.3). The service keeps running; other notices already
     * posted keep their language (plan section 5.4), though they render again whenever they post. A battery alert
     * notice still showing posts again only when the shown language itself changed (shownLanguage()), not for a
     * locale list change that keeps it, such as [en-US] to [en-US, es] with Volt still in English.
     */
    void applyLocale(){
        if(destroyed||ble==null)return;
        android.os.LocaleList locales;
        try{locales=AppLanguage.resources(this).getConfiguration().getLocales();}catch(RuntimeException unavailable){return;}
        if(locales.equals(noticeLocales))return;
        boolean first=noticeLocales==null;noticeLocales=locales;
        java.util.List<?> language=shownLanguage();boolean relabel=!first&&!language.equals(noticeLanguage);noticeLanguage=language;
        if(first){
            // A service started in a new language: the scooter channel is re-labelled by every post, but an existing alerts
            // channel only here, so Android's notification settings name it in the shown language from the start.
            try{
                NotificationManager manager=getSystemService(NotificationManager.class);
                if(manager.getNotificationChannel(ALERT_CHANNEL)!=null)alertChannel(manager);
                MaintenanceNotices.relabel(manager);
            }catch(RuntimeException unavailable){}
            return;
        }
        try{
            // Only channels that exist: the alerts channel appears once Battery alerts are first used.
            NotificationManager manager=getSystemService(NotificationManager.class);
            NotificationChannel scooter=manager.getNotificationChannel("scooter");
            if(scooter!=null){scooter.setName(T.serviceChannelScooter());manager.createNotificationChannel(scooter);}
            if(manager.getNotificationChannel(ALERT_CHANNEL)!=null)alertChannel(manager);
            MaintenanceNotices.relabel(manager);
        }catch(RuntimeException unavailable){}
        if(relabel)relabelAlertNotice();
        updateNotification();changed();
    }
    /**
     * The language Volt's text renders in now: the catalog's resolved language (T.language(), "en" for English or
     * anything unrecognised), and outside English the locale its numbers format in (Fmt.displayLocale(), else null).
     */
    private static java.util.List<?> shownLanguage(){return java.util.Arrays.asList(T.language(),Fmt.legacy()?null:Fmt.displayLocale());}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if (destroyed || DataMaintenance.active()) { stopSelf(); return START_NOT_STICKY; }
        if(!ble.permissions()){finishChargeCapture(ChargeSessionStore.Stop.PERMISSION_LOST);endChargeMeasure(T.serviceMeasureBluetoothRemoved());stopIdleWork();return START_NOT_STICKY;}
        // A queued startForegroundService still needs to fulfill Android's startup contract.
        // Current visibility is checked immediately afterwards, before any BLE work starts.
        String action=intent==null?null:intent.getAction();
        boolean stopAction="STOP_RECORDING".equals(action)||"STOP_CHARGE_RECORDING".equals(action)||"DISCONNECT".equals(action)
                ||"STOP_CHARGE_ALARM".equals(action)||"DISMISS_CHARGE_ALERT".equals(action)||"STOP_ALERT_WATCH".equals(action);
        if(STOP_CHARGE_LIVE.equals(action))stopAction=true;
        startForegroundNotice(true);applyVisibility(uiVisible,!stopAction);
        if("STOP_RECORDING".equals(action))stopRide();
        else if("STOP_CHARGE_RECORDING".equals(action))stopChargeRecording();
        else if("SCHEDULE_CHARGE_NOW".equals(action))scheduleChargeNow();
        else if("CANCEL_SCHEDULE_TEST".equals(action))cancelScheduleTest();
        else if("STOP_CHARGE_ALARM".equals(action))stopChargeAlarm();
        else if("DISMISS_CHARGE_ALERT".equals(action))dismissChargeAlert();
        else if("STOP_ALERT_WATCH".equals(action))skipChargeAlert();
        else if(STOP_CHARGE_LIVE.equals(action)){
            stopChargeLive();
            // Background connection on (owner, 2026-11-06, "Option A"): the card's Disconnect also drops the link, as the plain notice's does.
            if(backgroundConnection()&&noticeDisconnect()){stopIdleWork();return START_NOT_STICKY;}
        }
        else if("DISCONNECT".equals(action)){ble.disconnect();if(!isRecording()&&!isChargeRecording()&&!phoneSaving()&&!rideAutoExportBusy()&&!visible){stopIdleWork();return START_NOT_STICKY;}}
        applyWorkPolicy(!stopAction||isRecording());warmRides();return isRecording()?START_STICKY:START_NOT_STICKY;
    }
    private boolean ridesWarming;
    /**
     * Owner, Nov 5: launch and connect first, then load rides. Runs once, after the first start command has begun the
     * connection: a low-priority worker reads every finished ride into the store's cache, so Rides opens from memory.
     */
    private void warmRides(){
        if(ridesWarming||rides==null)return;ridesWarming=true;final RideStore store=rides;
        Thread worker=new Thread(()->{
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try{store.list();}catch(IOException|RuntimeException unavailable){}
        },"ride-cache");
        worker.setDaemon(true);worker.start();
    }
    @Override public IBinder onBind(Intent intent){return guardedLifecycle?null:binder;}
    public void listen(Runnable listener){listeners.add(listener);}public void unlisten(Runnable listener){listeners.remove(listener);}
    public boolean simulating() { return ble != null && ble.simulating(); }

    /** Profile changes must not interrupt recordings, saved sensor data or an in-flight command. */
    public String scooterChangeUnavailable() {
        if (destroyed || dataMaintenance || DataMaintenance.active()) return T.serviceWaitBackup();
        if (isRecording() || isChargeRecording()) return T.serviceStopRecordingFirst();
        if (phoneSaving()) return T.serviceWaitRideSaving();
        if ((ble != null && ble.pending() != null) || (chargeScheduleController != null && chargeScheduleController.busy()))
            return T.serviceWaitScooterChange();
        if (scheduleTesting()) return T.serviceWaitScheduleTest();
        return null;
    }

    /** A full portable backup is deliberately refused while another operation owns app data. */
    public String dataMaintenanceUnavailable() {
        if (destroyed || dataMaintenance || DataMaintenance.active()) return T.serviceMaintenanceInProgress();
        if (isRecording() || isChargeRecording()) return T.serviceStopRecordingFirst();
        if (rideAutoExportBusy()) return T.serviceWaitAutoSave();
        if (phoneSaving() || phoneRecorder != null) return T.serviceWaitPhoneSaving();
        if (chargeExporting) return T.serviceFinishChargeExport();
        if (chargeScheduleController != null && chargeScheduleController.busy()) return T.serviceWaitChargingSchedule();
        if (chargeMeasure.active()) return T.serviceWaitMeasuring();
        if (scheduleTesting()) return T.serviceWaitScheduleTest();
        if (!clearingHistory.isEmpty()) return T.serviceWaitHistoryCleanup();
        if (ble != null && ble.pending() != null) return T.serviceWaitScooterChange();
        ShizukuCaptureClient capture = ShizukuCaptureClient.get(this);
        if (capture.busy || capture.pendingCleanup()) return T.serviceFinishOwnerSetup();
        if (getSharedPreferences("pending_documents", MODE_PRIVATE).contains("path")) return T.serviceFinishExport();
        if (new File(getFilesDir(), "pending-owner-import.json").exists()
                || new File(getFilesDir(), "pending-owner-import.tmp").exists()) return T.serviceFinishOwnerImport();
        return null;
    }

    /**
     * Terminal maintenance boundary. The caller closes its screens before invoking this on main,
     * and restarts the process after maintenance even when it is cancelled or fails. A null result
     * is delivered only after all accepted durable writes have finished and SQLite is closed.
     */
    public void prepareDataMaintenance(Consumer<String> completion) {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Enter app data maintenance on the main thread");
        Objects.requireNonNull(completion);
        String unavailable = dataMaintenanceUnavailable();
        if (unavailable != null) { completion.accept(unavailable); return; }
        // Capture the last genuine values before suppressing listeners and Bluetooth callbacks.
        observeTelemetry();
        persistHistory(true);
        DataMaintenance.enter();
        dataMaintenance = true;
        stopChargeAlarm();
        if(chargeScheduleController!=null)chargeScheduleController.cancel(ChargeScheduleController.Interruption.DATA_MAINTENANCE);
        resetChargeEstimateContext();
        destroyed = true;
        workActive = false;
        uiVisible = false;
        visible = false;
        cancelBackgroundDeadline();
        handler.removeCallbacksAndMessages(null);
        listeners.clear();
        ble.setListener(null);
        ble.destroy();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        flushNotices();
        stopForeground(STOP_FOREGROUND_REMOVE);
        foregroundStarted = false;
        capacityPending.clear();
        rangePending.clear();
        chargeWorker.shutdown();
        capacityWorker.shutdown();
        if (history != null) history.close();
        new Thread(() -> {
            String error = null;
            long deadline = SystemClock.elapsedRealtime() + 30000;
            try {
                boolean drained = chargeWorker.awaitTermination(remaining(deadline), TimeUnit.MILLISECONDS);
                drained &= capacityWorker.awaitTermination(remaining(deadline), TimeUnit.MILLISECONDS);
                if (history != null) drained &= history.closeAndAwait(remaining(deadline));
                if (!drained) error = T.serviceMaintenanceFailed();
                else {
                    // commit waits for earlier apply writes for each preference file. The empty
                    // transaction changes no user setting and leaves no maintenance marker behind.
                    for (String name : new String[]{"app_settings", "profiles", "scooter", "control_evidence", OnboardingScreen.PREFS})
                        if (!getSharedPreferences(name, MODE_PRIVATE).edit().commit())
                            throw new IOException("Preferences did not finish saving");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                error = T.serviceMaintenanceFailed();
            } catch (RuntimeException | IOException failure) {
                error = T.serviceMaintenanceFailed();
            }
            final String result = error;
            handler.post(() -> completion.accept(result));
        }, "app-data-maintenance").start();
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - SystemClock.elapsedRealtime());
    }

    /** Runtime-only preview: actual caches and journals never receive sample readings. */
    public boolean setSimulation(boolean enabled) {
        if (destroyed || ble == null) return false;
        if (enabled == simulating()) return true;
        if (enabled && (isRecording() || isChargeRecording() || phoneSaving() || holding())) {
            android.widget.Toast.makeText(this, chargeMeasure.active() ? T.serviceWaitMeasuring() : scheduleTesting() ? T.serviceWaitScheduleTest() : T.serviceSimulationFinishRecording(), android.widget.Toast.LENGTH_LONG).show();
            return false;
        }
        if (enabled && profiles.active() == null) return false;
        // Flush only genuine data before the BLE layer closes its current connection.
        if (enabled) { observeTelemetry(); persistHistory(true); }
        historyConnected = false; historyDirty = false; observedSources.clear();
        power.clear(); voltage.clear(); speed.clear();
        invalidateCapacityEstimate();
        resetChargeEstimateContext();
        if(chargeScheduleController!=null)chargeScheduleController.cancel(ChargeScheduleController.Interruption.SIMULATION);
        // Range following remembers what it tried per profile. The simulator's attempts have their own key (followRangeMode)
        // and are forgotten here; the real scooter's are kept, so neither side's attempt carries into the other.
        rangeFollowPolicy.forgetSimulation();
        ble.setSimulation(enabled);
        cancelBackgroundDeadline();
        applyWorkPolicy(false);
        changed();
        return simulating() == enabled;
    }

    public boolean isChargeRecording(){return chargeStore!=null&&chargeStore.active();}
    private String chargerKey(){return "charge_charger_"+activeProfileId();}
    /** Null means Automatic. Preserve existing explicit per-scooter choices on upgrade. */
    public ChargeSessionStore.Charger chargeCharger(){
        try{return ChargeSessionStore.Charger.valueOf(settings.getString(chargerKey(),""));}catch(IllegalArgumentException invalid){return null;}
    }
    /** Why a charger family drives estimates, in precedence order. */
    public enum ChargerBasis{RECORDING,MANUAL,DETECTED,UNKNOWN}
    /**
     * A recording's manually confirmed label is temporary and never changes the preference. An automatic recording's label
     * is the charger in effect only when it was chosen or detected (ChargeAutoRecordPolicy.countsAsCharger): a guess
     * labels the file, and estimates, Charge done by and the charging view keep waiting for the choice or for detection.
     * The owner may choose during an automatic recording they did not label (chargerChoosable): the choice is saved as
     * the preference, as any choice, and relabels the recording CHOSEN, so it is the charger in effect from then on.
     */
    public ChargerBasis chargerBasis(){
        if(isChargeRecording()&&activeProfileId().equals(chargeStore.profile())&&ChargeAutoRecordPolicy.countsAsCharger(chargeStore.labelSource()))return ChargerBasis.RECORDING;
        if(chargeCharger()!=null)return ChargerBasis.MANUAL;
        return detectedCharger()!=null?ChargerBasis.DETECTED:ChargerBasis.UNKNOWN;
    }
    public ChargeSessionStore.Charger effectiveChargeCharger(){
        switch(chargerBasis()){
            case RECORDING:return chargeStore.charger();
            case MANUAL:return chargeCharger();
            case DETECTED:return detectedCharger();
            default:return null;
        }
    }
    /**
     * Automatic inference for the active scooter's current charge, already checked for age. While simulating, detection
     * never runs (updateChargerDetection): Automatic finds the charger the Charging scene has plugged in instead, so a
     * simulated UT5 MAX's Charge done by and estimates don't wait for it. Nothing is written to the saved choice.
     */
    private ChargeSessionStore.Charger detectedCharger(){
        ScooterProfiles.Profile profile=profiles==null?null:profiles.active();
        if(destroyed||profile==null)return null;
        return simulating()?ble.simulationDetectedCharger():chargerDetection.selected(profile.id,SystemClock.elapsedRealtime());
    }
    private int qualifiedSessions(ChargeSessionStore.Charger charger){
        ChargeSessionStore.Curve curve=chargeSnapshot==null?null:chargeSnapshot.curves.get(ChargeSessionStore.key(activeProfileId(),charger));
        return curve==null?0:curve.qualifiedSessions;
    }
    public boolean setChargeCharger(ChargeSessionStore.Charger charger){
        if(destroyed||activeProfileId().isEmpty()||!chargerChoosable())return false;
        // An automatic recording the owner did not label takes the choice as its label (CHOSEN; the header first, then the
        // name, so a process end between them leaves a guess or detected label that names the owner's charger).
        boolean relabels=isChargeRecording()&&charger!=null;
        if(relabels)try{if(!chargeStore.relabel(charger,ChargeSessionStore.LabelSource.CHOSEN))return false;}catch(IOException unchanged){return false;}
        chargeReferenceGate.reset();
        SharedPreferences.Editor edit=settings.edit();
        if(charger==null)edit.remove(chargerKey());else edit.putString(chargerKey(),charger.name());
        edit.apply();if(relabels)updateNotification();changed();return true;
    }
    /** Whether the charger choice is open now: no recording, or an automatic one on this scooter whose label the owner did not choose. */
    public boolean chargerChoosable(){
        if(!isChargeRecording())return true;
        return activeProfileId().equals(chargeStore.profile())&&ChargeAutoRecordPolicy.choiceAllowed(true,chargeStore.labelSource());
    }
    /** Why a charge recording can't start now, in the current language, or null when it can. */
    public String chargeCaptureUnavailable(){CharSequence why=chargeCaptureBlocked();return why==null?null:why.toString();}
    /**
     * chargeCaptureUnavailable() deferred, for the status line that keeps it (plan R2).
     */
    private CharSequence chargeCaptureBlocked(){
        if(destroyed||chargeStore==null)return M.serviceChargeStorageUnavailable();
        if(simulating())return M.serviceChargeSimulation();
        if(isRecording()||phoneSaving())return M.serviceChargeFinishRide();
        if(chargeExporting)return M.serviceChargePreparingExport();
        if(chargeModelLoading)return M.serviceChargeLoading();
        if(!ble.ready()||profiles.active()==null||ble.profile()==null||!profiles.active().id.equals(ble.profile().id))return M.serviceChargeConnectScooter();
        ChargeSessionStore.Frame frame=chargeFrame(SystemClock.elapsedRealtime(),System.currentTimeMillis());
        if(!frame.usable())return M.serviceChargeWaitReadings();
        if(frame.soc.value>=100)return M.serviceChargeBatteryFull();
        NotificationManager manager=getSystemService(NotificationManager.class);
        NotificationChannel channel=manager.getNotificationChannel("scooter");
        if(!manager.areNotificationsEnabled()||channel!=null&&channel.getImportance()==NotificationManager.IMPORTANCE_NONE)
            return M.serviceChargeAllowNotifications();
        return null;
    }
    public boolean startChargeRecording(ChargeSessionStore.Charger charger){return startChargeRecording(charger,null);}
    /**
     * source: how an automatic recording's label was chosen (observeAutoCharge), null for the owner's own start. An
     * automatic start that finds no room (every saved recording is manual) does not start and says so; its scooter is
     * marked (autoChargeNoRoom) so that clearing any scooter's recordings or turning the switch on again tries once more.
     */
    private boolean startChargeRecording(ChargeSessionStore.Charger charger,ChargeSessionStore.LabelSource source){
        if(isChargeRecording())return false;
        CharSequence unavailable=chargeCaptureBlocked();
        if(unavailable!=null||charger==null){chargeStatus=unavailable==null?M.serviceChargeChooseCharger():unavailable;changed();return false;}
        long mono=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis();
        try{
            if(source==null)chargeStore.start(activeProfileId(),charger,wall,mono);
            else if(!chargeStore.startAutomatic(activeProfileId(),charger,source,wall,mono)){
                autoChargeNoRoom.add(activeProfileId());chargeStatus=M.serviceChargeAutoNoRoom();changed();return false;}
            chargeEndingMono=0;
            chargeRecovery=new ChargeRecoveryPolicy(activeProfileId(),ble.currentTransportSerial(),mono,ble.intentionalDisconnectSequence());
            ble.recordDiagnostic(ConnectionEventLog.Reason.CHARGE_STARTED,-1);
            chargeReferenceGate.reset();
            chargeStore.sample(chargeFrame(mono,wall),chargeRecordingEstimate());chargeStatus=M.serviceChargeStarted();
            cancelBackgroundDeadline();applyWorkPolicy(false);updateNotification();changed();return isChargeRecording();
        }catch(IOException|IllegalStateException failure){chargeStatus=M.serviceChargeStartFailed();finishChargeCapture(ChargeSessionStore.Stop.ERROR);changed();return false;}
    }
    /** The owner's Stop, on the page or in the notification. It holds the scooter (holdAutoChargeAfterHandStop). */
    public void stopChargeRecording(){holdAutoChargeAfterHandStop();finishChargeCapture(ChargeSessionStore.Stop.STOPPED);}
    /** Record charges automatically: the switch, off by default. */
    private boolean autoChargeEnabled(){return settings!=null&&settings.getBoolean(ChargeAutoRecordPolicy.KEY,false);}
    /** Scooters whose automatic start failed (storage, or no room beside manual recordings), until they stop charging. */
    private final Set<String> autoChargeLatched=new HashSet<>();
    /** The latched scooters whose refusal was for lack of room: released when room appears or the switch is turned on. */
    private final Set<String> autoChargeNoRoom=new HashSet<>();
    /** Releases a lack-of-room latch, for one scooter or (null) all, so the next look tries again. */
    private void releaseAutoChargeNoRoom(String profile){
        for(String id:new ArrayList<>(autoChargeNoRoom))if(profile==null||profile.equals(id)){autoChargeNoRoom.remove(id);autoChargeLatched.remove(id);}
    }
    private boolean autoChargeBusy;
    /**
     * A hand stop holds its scooter until it stops charging (ChargeAutoRecordPolicy.holdsAfterHandStop: an automatic
     * recording, or any while the switch is on); stored, so a reconnect or restart keeps it.
     */
    private void holdAutoChargeAfterHandStop(){
        if(!isChargeRecording()||!ChargeAutoRecordPolicy.holdsAfterHandStop(autoChargeEnabled(),chargeStore.automatic()))return;
        String profile=chargeStore.profile();
        Set<String> holds=ChargeAutoRecordPolicy.holds(settings.getString(ChargeAutoRecordPolicy.HOLD_KEY,""));
        // commit(): the hold must outlive a process death right after the stop.
        if(profile!=null&&holds.add(profile))settings.edit().putString(ChargeAutoRecordPolicy.HOLD_KEY,ChargeAutoRecordPolicy.store(holds)).commit();
    }
    private void clearAutoChargeHold(String profile){
        autoChargeLatched.remove(profile);autoChargeNoRoom.remove(profile);
        Set<String> holds=ChargeAutoRecordPolicy.holds(settings.getString(ChargeAutoRecordPolicy.HOLD_KEY,""));
        if(holds.remove(profile))settings.edit().putString(ChargeAutoRecordPolicy.HOLD_KEY,ChargeAutoRecordPolicy.store(holds)).apply();
    }
    /**
     * Record charges automatically (ChargeAutoRecordPolicy), on every Bluetooth change and service tick, on screen or
     * not: it acts whenever a connection exists and never keeps or opens one. Starts silently when the connected,
     * selected scooter reports charging and a manual start would be allowed; ends an automatic recording at once
     * when the scooter moves off, before auto-record rides looks in the same change; follows charger detection for a
     * detected or guessed label. A fresh "not charging" reading ends the scooter's hold and latch.
     *
     * Out of sight it sees the battery through the reads #418 makes with every 15 s status poll; it asks for nothing
     * itself. The link's state (linked(), connected()) only says the connection is kept: every decision here rests on
     * a reading that is live by its own rule, so a reading from before a sleep or a gap never starts, ends, clears a
     * hold or discards anything.
     * - Start and join mid-charge: a "charging" reading at most ChargeSessionStore.MAX_FRAME_AGE_MS (5 s) old, then the
     *   manual start's own checks (chargeCaptureBlocked: ready(), the 7 s status, and a usable frame, every value 5 s).
     * - Clear a hold or latch: a "not charging" reading at most 5 s old.
     * - End on moving off: a speed reading at most AutoStartPolicy.SPEED_FRESH_MS (3 s) old, the rule auto-record rides
     *   starts on, so the two agree in one change.
     * - End after charging stops, and keep or discard: updateChargeCapture and ChargeSessionStore, on 5 s fresh frames
     *   and saved samples only.
     */
    private void observeAutoCharge(long mono){
        if(destroyed||chargeStore==null||dataMaintenance)return;
        boolean enabled=autoChargeEnabled();
        if(autoChargeBusy)return;autoChargeBusy=true;
        try{
            ScooterProfiles.Profile active=profiles.active(),bound=ble.profile();
            boolean connected=ble.connected()&&!simulating();
            // linked() (#418): the connection is kept and answering on its cadence, judged in awake time. It decides nothing alone.
            boolean selected=connected&&ble.linked()&&active!=null&&bound!=null&&active.id.equals(bound.id);
            // Live by the recording's own 5 s frame rule, else null: a stale reading neither starts nor clears anything.
            Boolean live=selected?ChargeAutoRecordPolicy.liveCharging(chargeFrame(mono,System.currentTimeMillis()).state,mono):null;
            // A pause Volt holds itself (schedule, Charge done by, a schedule test) reads like an unplug but is not one.
            if(selected&&ChargeAutoRecordPolicy.clearsHold(live!=null,live==null?-1:live?1:0,scheduledChargePause()))clearAutoChargeHold(active.id);
            boolean movedAway=ChargeAutoRecordPolicy.movedAway(connected,ble.speedRaw(),ble.motionSampleMs(),mono);
            if(isChargeRecording()){
                if(!chargeStore.automatic())return;
                // Ended by itself: no hold, and auto-record rides may start a ride in this same change. The work policy waits
                // for that look (changed()): applied now, it could release the link the ride start needs (Stay connected off).
                if(movedAway){workPolicyAfterAutoStart=true;finishChargeCapture(ChargeSessionStore.Stop.CHARGING_ENDED);return;}
                ChargeSessionStore.Charger relabel=ChargeAutoRecordPolicy.relabel(chargeStore.labelSource(),chargeStore.charger(),detectedCharger());
                // A guess that detection confirms becomes DETECTED too (same family: the store renames only).
                if(relabel!=null)try{
                    boolean familyChanges=relabel!=chargeStore.charger();
                    if(chargeStore.relabel(relabel,ChargeSessionStore.LabelSource.DETECTED)){if(familyChanges)chargeReferenceGate.reset();updateNotification();}
                }catch(IOException keepFirstLabel){/* the recording keeps its first label */}
                return;
            }
            boolean held=active!=null&&(autoChargeLatched.contains(active.id)
                    ||ChargeAutoRecordPolicy.holds(settings.getString(ChargeAutoRecordPolicy.HOLD_KEY,"")).contains(active.id));
            boolean charging=Boolean.TRUE.equals(live);
            // The manual start's own checks last: they cost the most.
            if(!ChargeAutoRecordPolicy.shouldStart(enabled,selected,charging,simulating(),false,isChargeRecording(),held,movedAway))return;
            // Never in the middle of a guided test (or its write-back) or a compatibility check; no latch, so it starts after.
            if(ChargeAutoRecordPolicy.waits(ble.testing(),ble.restoreOutstanding(),ble.discovering()))return;
            if(!ChargeAutoRecordPolicy.shouldStart(enabled,selected,charging,simulating(),chargeCaptureBlocked()!=null,isChargeRecording(),held,movedAway))return;
            ChargeAutoRecordPolicy.Label label=ChargeAutoRecordPolicy.label(chargeCharger(),detectedCharger(),
                    qualifiedSessions(ChargeSessionStore.Charger.STOCK),qualifiedSessions(ChargeSessionStore.Charger.HIGH_SPEED));
            if(!startChargeRecording(label.charger,label.source)&&!isChargeRecording())autoChargeLatched.add(active.id);
        }finally{autoChargeBusy=false;}
    }
    /**
     * The last charge status as its message (a Msg), not rendered: what a finished export just set when it failed ("Charge
     * recordings could not be exported", or "No saved charge recordings for this scooter"). Email to Volt holds it in
     * ChargeSend.MAILBOX until a screen shows it, so it renders then, in the language current then (#419 review, P3 1).
     */
    public CharSequence chargeStatusMessage(){return chargeStatus;}
    /**
     * The status is still the outcome of the recording that just ended (saved, not kept, stopped): the page shows it in
     * place of the reason a new one can't start, until any newer status replaces it (Terra's review of #416, finding 2).
     */
    public boolean chargeStatusIsOutcome(){return chargeOutcome!=null&&chargeOutcome==chargeStatus;}
    /**
     * The page shows that outcome in place of why a new recording can't start only when the reason follows from the charge
     * having ended (waiting for charging readings, battery full); any other reason, such as a ride, is shown (Terra's
     * review of #416 at 39f813dd).
     */
    public boolean chargeOutcomeOverReason(){
        if(!chargeStatusIsOutcome())return false;
        CharSequence why=chargeCaptureBlocked();
        return why==null||M.serviceChargeWaitReadings().equals(why)||M.serviceChargeBatteryFull().equals(why);
    }
    public String chargeCaptureStatus(){
        if(!isChargeRecording())return chargeStatus.toString();
        if(chargeRecovery!=null&&chargeRecovery.recovering())
            return T.serviceChargeReconnecting(Fmt.integer(Math.max(1,(chargeRecovery.remainingMs(SystemClock.elapsedRealtime())+999)/1000)));
        String status=(chargeStore.charger()==ChargeSessionStore.Charger.STOCK?(chargeStore.chargerPaused()?T.serviceChargeStockChargerPaused()
                        :chargeStore.paused()?T.serviceChargeStockSchedulePaused():T.serviceChargeStockSamplesCount(chargeStore.sampleCount(),Fmt.integer(chargeStore.sampleCount())))
                :(chargeStore.chargerPaused()?T.serviceChargeFastChargerPaused()
                        :chargeStore.paused()?T.serviceChargeFastSchedulePaused():T.serviceChargeFastSamplesCount(chargeStore.sampleCount(),Fmt.integer(chargeStore.sampleCount()))));
        // A guessed label names a charger nobody chose and detection has not settled: say so.
        return chargeStore.labelSource()==ChargeSessionStore.LabelSource.GUESS?T.serviceChargeChargerGuessed(status):status;
    }
    /** Qualified sessions behind the curve estimates actually use; 0 while the charger is unknown, -1 while loading. */
    public int qualifiedChargeCurves(){
        if(chargeModelLoading)return -1;
        ChargeSessionStore.Charger charger=effectiveChargeCharger();
        return charger==null?0:qualifiedSessions(charger);
    }
    public String chargeLearningSummary(){
        if(chargeModelLoading)return T.serviceChargeLoading();
        ChargeSessionStore.Charger charger=effectiveChargeCharger();
        int qualified=qualifiedChargeCurves(),sessions=chargeSnapshot==null?0:chargeSnapshot.sessions;
        return charger==null?T.serviceChargeLearningUnknownCount(sessions,Fmt.integer(sessions))
                :charger==ChargeSessionStore.Charger.HIGH_SPEED?T.serviceChargeLearningFastCount(qualified,Fmt.integer(qualified),T.serviceChargeRecordingsStored(sessions,Fmt.integer(sessions)))
                :T.serviceChargeLearningStockCount(qualified,Fmt.integer(qualified),T.serviceChargeRecordingsStored(sessions,Fmt.integer(sessions)));
    }
    public boolean clearChargeRecordings(Runnable completion){
        if(destroyed||chargeStore==null||isChargeRecording()||chargeModelLoading)return false;
        final String profile=activeProfileId();if(profile.isEmpty())return false;chargeModelLoading=true;
        chargeWorker.execute(()->{CharSequence error=null;ChargeSessionStore.Snapshot snapshot=null;
            try{chargeStore.clear(profile);snapshot=chargeStore.snapshot();}catch(IOException failure){error=M.serviceChargeClearFailed();}
            final CharSequence message=error;final ChargeSessionStore.Snapshot result=snapshot;
            handler.post(()->{if(destroyed)return;chargeModelLoading=false;if(result!=null)chargeSnapshot=result;
                // Room now: a charge refused for lack of it records after all (Terra's review of #416, finding 1). The
                // twenty are shared by every scooter, so every scooter's refusal goes (its review at 39f813dd).
                if(message==null)releaseAutoChargeNoRoom(null);
                chargeStatus=message==null?M.serviceChargeCleared():message;changed();if(completion!=null)completion.run();});});return true;
    }
    public boolean chargeExporting(){return chargeExporting;}
    /** Read only this scooter's closed journals off-main; never re-run today's estimator on history. */
    public boolean loadChargeRecordings(Consumer<List<ChargeSessionStore.HistorySession>> completion){
        if(destroyed||dataMaintenance||chargeStore==null||activeProfileId().isEmpty())return false;
        final String profile=activeProfileId();
        chargeWorker.execute(()->{
            List<ChargeSessionStore.HistorySession> recordings=null;
            try{recordings=chargeStore.recordings(profile);}catch(IOException unavailable){}
            final List<ChargeSessionStore.HistorySession> result=recordings;
            handler.post(()->{
                if(destroyed)return;
                completion.accept(!dataMaintenance&&profile.equals(activeProfileId())?result:null);
            });
        });return true;
    }
    /** The profile is captured before work starts so changing screens cannot export a different scooter. */
    /** "Charge recordings could not be exported": the export failed, or Email to Volt couldn't prepare its attachment. */
    static CharSequence chargeExportFailed(){return M.serviceChargeExportFailed();}
    public boolean exportChargeRecordings(Consumer<File> completion){return exportChargeRecordings(false,completion);}
    /**
     * attachment: Email to Volt's export, which ends ready to attach (ChargeSend.prepare: over 25 MB, gzip-compressed).
     * That compression is part of the export (#412 review, P3 1 and 2): it runs here on the worker, under chargeExporting,
     * so the row stays busy, a second tap stays quiet, and the completion (the mailbox's hand-over, which survives an
     * activity recreation) gets only a final file; the activity then just moves it into place.
     */
    public boolean exportChargeRecordings(boolean attachment,Consumer<File> completion){
        if(destroyed||chargeStore==null||isChargeRecording()||chargeExporting||activeProfileId().isEmpty())return false;
        final String profile=activeProfileId();chargeExporting=true;
        chargeWorker.execute(()->{
            File output=null;CharSequence error=null;
            try{
                File directory=new File(getCacheDir(),"charge-exports");
                if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException();
                // No export runs but this one: what an interrupted compression left goes now; finished ones keep their day.
                ChargeSend.clearLeftovers(directory);
                File[] old=directory.listFiles();long cutoff=System.currentTimeMillis()-24*60*60*1000L;
                if(old!=null)for(File entry:old)if(entry.lastModified()<cutoff)entry.delete();
                output=File.createTempFile("charges-",".json",directory);
                int count;
                try(Writer writer=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(output),java.nio.charset.StandardCharsets.UTF_8))){
                    count=chargeStore.exportJson(profile,writer);
                }
                if(count==0){output.delete();output=null;error=M.serviceChargeExportEmpty();}
                else if(attachment)output=ChargeSend.prepare(output);
            }catch(IOException|IllegalStateException failure){if(output!=null)output.delete();output=null;error=chargeExportFailed();}
            final File result=output;final CharSequence message=error;
            handler.post(()->{chargeExporting=false;if(destroyed){if(result!=null)result.delete();return;}
                if(message!=null)chargeStatus=message;changed();completion.accept(result);});
        });changed();return true;
    }
    private void refreshChargeCurves(){
        if(destroyed||chargeStore==null||chargeModelLoading)return;chargeModelLoading=true;
        chargeWorker.execute(()->{ChargeSessionStore.Snapshot snapshot=null;
            try{snapshot=chargeStore.snapshot();}catch(IOException ignored){}
            final ChargeSessionStore.Snapshot result=snapshot;handler.post(()->{if(destroyed)return;chargeModelLoading=false;
                if(result!=null)chargeSnapshot=result;changed();});});
    }
    private ChargeSessionStore.Frame chargeFrame(long mono,long wall){
        Map<String,ChargeSessionStore.Value> values=new HashMap<>();
        for(Telemetry.Metric metric:ble.telemetry.metrics())values.put(metric.key,new ChargeSessionStore.Value(metric.numericValue,metric.updatedMs,metric.sourceCmd));
        return new ChargeSessionStore.Frame(wall,mono,values.get("charging_state"),values.get("battery_percent"),values.get("voltage_v"),values.get("current_a"),values.get("temperature_c"));
    }
    private void updateChargeCapture(long mono,long wall){
        if(chargeUpdating||!isChargeRecording())return;chargeUpdating=true;
        try{
            ChargeSessionStore.Stop stop=null;
            if(simulating())stop=ChargeSessionStore.Stop.STOPPED;
            else if(!Objects.equals(chargeStore.profile(),activeProfileId()))stop=ChargeSessionStore.Stop.PROFILE_CHANGED;
            else if(ble.connected()&&(ble.profile()==null||!Objects.equals(chargeStore.profile(),ble.profile().id)))stop=ChargeSessionStore.Stop.PROFILE_CHANGED;
            else if(chargeRecovery==null)stop=ChargeSessionStore.Stop.CONNECTION_FAILED;
            else{
                ChargeSessionStore.Frame frame=chargeFrame(mono,wall);
                ScooterProfiles.Profile active=profiles.active();
                boolean pause=chargeStore.holdsSchedulePause(scheduledChargePause(),mono);
                ChargeRecoveryPolicy.Result recovery=chargeRecovery.evaluate(mono,activeProfileId(),active==null?"":active.serial,
                        ble.intentionalDisconnectSequence(),ble.permissions(),ble.bluetoothEnabled(),ble.connected(),ble.ready(),
                        ble.recoverableConnectionLoss(),ble.currentTransportSerial(),frame);
                if(!applyChargeRecoveryResult(recovery)){
                    if(isChargeRecording()&&!chargeRecovery.recovering()&&(mono<chargeStore.lastFreshMono()||mono-chargeStore.lastFreshMono()>30000))
                        finishChargeCapture(ChargeSessionStore.Stop.READINGS_STOPPED);
                    return;
                }
                ChargeSessionStore.Estimate estimate=chargeRecordingEstimate();
                boolean completed=frame.usableCompletion()&&chargeStore.sampleCompletion(frame,estimate);
                if(!isChargeRecording()){chargeStatus=M.serviceChargeLimitReached();refreshChargeCurves();applyWorkPolicy(false);return;}
                if(completed)stop=ChargeSessionStore.Stop.COMPLETE;
                else if(pause&&ChargeSessionStore.fresh(frame.state,mono)&&frame.state.value==0){
                    // Volt's own schedule holds current off until its start: wait and mark it.
                    chargeEndingMono=0;
                    if(chargeStore.paused())chargeStore.heartbeat(mono);
                    else{chargeStore.pause(wall,mono);updateNotification();}
                    if(!isChargeRecording()){chargeStatus=M.serviceChargeLimitReached();refreshChargeCurves();applyWorkPolicy(false);return;}
                }
                else if(ChargeSessionStore.fresh(frame.state,mono)&&frame.state.value!=1){
                    if(frame.state.value==0&&ChargeSessionStore.fresh(frame.soc,mono)&&frame.soc.value>=99){
                        // Some firmware sends charging=off before the last SOC/electrical packet.
                        // Give exactly one bounded poll window to preserve the observed 99→100 bin.
                        if(chargeEndingMono==0){chargeEndingMono=mono;ble.refresh(false);}
                        else if(mono-chargeEndingMono>=5000)stop=ChargeSessionStore.Stop.CHARGING_ENDED;
                    }else if(frame.state.value==0){
                        // The charger stopped short of full with no schedule holding it: an overheating charger
                        // often cuts out and comes back. Wait, marked as a charger pause, before ending.
                        chargeEndingMono=0;
                        // An automatic recording waits two minutes, a manual one 30 (ChargeAutoRecordPolicy); a schedule hold is handled above.
                        ChargeSessionStore.ChargerOff off=chargeStore.chargerStopped(wall,mono,ChargeAutoRecordPolicy.chargerOffWaitMs(chargeStore.automatic()));
                        if(!isChargeRecording()){chargeStatus=M.serviceChargeLimitReached();refreshChargeCurves();applyWorkPolicy(false);return;}
                        if(off==ChargeSessionStore.ChargerOff.PAUSED)updateNotification();
                        else if(off==ChargeSessionStore.ChargerOff.ENDED)stop=ChargeSessionStore.Stop.CHARGING_ENDED;
                    }else stop=ChargeSessionStore.Stop.CHARGING_ENDED;
                }
                else{
                    chargeEndingMono=0;
                    if(chargeStore.paused()&&frame.usable()){chargeStore.resume(wall,mono);updateNotification();}
                    boolean sampled=chargeStore.sample(frame,estimate);
                    if(!isChargeRecording()){chargeStatus=M.serviceChargeLimitReached();refreshChargeCurves();applyWorkPolicy(false);return;}
                    if(sampled&&frame.soc.value>=100)stop=ChargeSessionStore.Stop.COMPLETE;
                    else if(mono<chargeStore.lastFreshMono()||mono-chargeStore.lastFreshMono()>30000)stop=ChargeSessionStore.Stop.READINGS_STOPPED;
                }
            }
            if(stop!=null)finishChargeCapture(stop);
        }catch(IOException failure){finishChargeCapture(ChargeSessionStore.Stop.ERROR);}
        finally{chargeUpdating=false;}
    }
    /** Apply a policy transition without changing ownership of the one active journal. */
    private boolean applyChargeRecoveryResult(ChargeRecoveryPolicy.Result recovery){
        if(recovery.stop!=null){finishChargeCapture(recovery.stop);return false;}
        if(recovery.beganRecovery){
            chargeStore.markGap();chargeEndingMono=0;
            ble.recordDiagnostic(ConnectionEventLog.Reason.CHARGE_RECOVERY_STARTED,-1);
            updateRecordingWakeLock();updateNotification();
        }
        if(recovery.resumed){
            ble.recordDiagnostic(ConnectionEventLog.Reason.CHARGE_RECOVERY_RESUMED,-1);
            updateNotification();
        }
        return recovery.record;
    }
    private void finishChargeCapture(ChargeSessionStore.Stop reason){
        if(!isChargeRecording())return;
        boolean recovering=chargeRecovery!=null&&chargeRecovery.recovering();
        chargeEndingMono=0;chargeRecovery=null;
        ble.recordDiagnostic(reason==ChargeSessionStore.Stop.RECOVERY_TIMEOUT?ConnectionEventLog.Reason.CHARGE_RECOVERY_TIMEOUT
                :reason==ChargeSessionStore.Stop.IDENTITY_CHANGED||reason==ChargeSessionStore.Stop.PROFILE_CHANGED?ConnectionEventLog.Reason.CHARGE_WRONG_SCOOTER
                :recovering?ConnectionEventLog.Reason.CHARGE_RECOVERY_CANCELLED:ConnectionEventLog.Reason.CHARGE_STOPPED,-1);
        // An automatic recording too short to keep is deleted (ChargeAutoRecordPolicy.keep): "not kept", never "saved".
        try{boolean kept=chargeStore.stop(reason);chargeStatus=!kept?M.serviceChargeNotKept():reason==ChargeSessionStore.Stop.COMPLETE?M.serviceChargeSavedFull()
                :reason==ChargeSessionStore.Stop.STOPPED?M.serviceChargeSaved():reason==ChargeSessionStore.Stop.ERROR?M.serviceChargeStorageError()
                :M.serviceChargeStoppedReason(stopLabel(reason,reason.name().toLowerCase(Locale.ROOT).replace('_',' ')));}
        catch(IOException failure){chargeStatus=M.serviceChargeNotFinalized();}
        chargeOutcome=chargeStatus;
        // Recovery owns the reconnect attempt. Stop/timeout cancels it even when the UI or
        // ordinary background connection preference would otherwise keep retrying.
        if(recovering)ble.suspend();
        if(wakeLock!=null&&wakeLock.isHeld()&&!isRecording()&&!phoneSaving())wakeLock.release();
        if(!destroyed){refreshChargeCurves();if(!workPolicyAfterAutoStart)applyWorkPolicy(false);updateNotification();}
    }
    /** Set while an automatic recording ends on moving off: changed() applies the work policy after auto-record rides looked. */
    private boolean workPolicyAfterAutoStart;
    /**
     * Why a charge recording stopped, as the status line shows it: a label for each stop code, whose English is the
     * code's name in lower case with spaces (what the line always printed). The code itself stays the journal's id.
     * A code added later without a label shows english, that same lower-cased name.
     */
    private static CharSequence stopLabel(ChargeSessionStore.Stop reason,String english){
        switch(reason){
            case CHARGING_ENDED:return M.serviceChargeStopChargingEnded();
            case DISCONNECTED:return M.serviceChargeStopDisconnected();
            case PROFILE_CHANGED:return M.serviceChargeStopProfileChanged();
            case READINGS_STOPPED:return M.serviceChargeStopReadingsStopped();
            case LIMIT:return M.serviceChargeStopLimit();
            case PROCESS_ENDED:return M.serviceChargeStopProcessEnded();
            case INTERRUPTED:return M.serviceChargeStopInterrupted();
            case RECOVERY_TIMEOUT:return M.serviceChargeStopRecoveryTimeout();
            case CONNECTION_FAILED:return M.serviceChargeStopConnectionFailed();
            case BLUETOOTH_OFF:return M.serviceChargeStopBluetoothOff();
            case PERMISSION_LOST:return M.serviceChargeStopPermissionLost();
            case IDENTITY_CHANGED:return M.serviceChargeStopIdentityChanged();
            default:return english;
        }
    }
    private ChargingEstimate.Value chargingValue(Map<String,TelemetryHistory.Reading> readings,String key){
        TelemetryHistory.Reading reading=readings.get(key);
        return new ChargingEstimate.Value(reading==null?null:reading.number,reading!=null&&reading.live,reading==null?0:reading.capturedWallMs);
    }
    /** Same service calculation used by the dashboard, also evaluated with the screen off. */
    private ChargeSessionStore.Estimate chargeRecordingEstimate(){
        return ChargingEstimate.recordingSnapshot(chargingEstimate(displayReadings()));
    }
    /** Fresh paired data first; recognized models get defaults without requiring capture setup. */
    public ChargingEstimate.Result chargingEstimate(Map<String,TelemetryHistory.Reading> readings){
        return chargingEstimate(readings,chargingValue(readings,"charge_limit_percent"));
    }
    /**
     * The charging hero's countdown to a charge alert's level, or null when it has none (ChargeAlertPolicy.countdownLevel).
     * The alert's level is only this estimate's target: charge_limit_percent, charge limits, Charge done by, the live
     * card and the widgets never see it. Reading the same frame twice is harmless (ChargeReferenceGate).
     */
    public ChargingEstimate.Result chargeAlertEstimate(Map<String,TelemetryHistory.Reading> readings){
        ChargingEstimate.Value soc=chargingValue(readings,"battery_percent");
        Integer level=ChargeAlertPolicy.countdownLevel(chargeAlertsOffered(),chargeAlerts(),soc.fresh?soc.number:null);
        return level==null?null:chargingEstimate(readings,new ChargingEstimate.Value(level.doubleValue(),true,soc.observedMs));
    }
    private ChargingEstimate.Result chargingEstimate(Map<String,TelemetryHistory.Reading> readings,ChargingEstimate.Value target){
        ChargingEstimate.Value soc=chargingValue(readings,"battery_percent"),rated=chargingValue(readings,"rated_capacity_ah");
        ChargingEstimate.Result baseline=ChargingEstimate.calculate(chargingValue(readings,"charging_state"),chargingValue(readings,"voltage_v"),
                chargingValue(readings,"current_a"),soc,rated,target);
        ChargeSessionStore.Charger charger=effectiveChargeCharger();
        ChargeSessionStore.Curve curve=simulating()||chargeSnapshot==null||charger==null?null:chargeSnapshot.curves.get(ChargeSessionStore.key(activeProfileId(),charger));
        ScooterProfiles.Profile profile=profiles.active();
        if(!canRetainStockReference()){
            chargeReferenceGate.reset();
            return ChargingEstimate.forScooter(baseline,soc,rated,ModelRegistry.chargeReferenceProduct(modelDescriptor(profile)),charger,curve);
        }
        // UT5 MAX reference curves only for the UT5 MAX layer; other models get live measured estimates.
        long now=SystemClock.elapsedRealtime();
        return ChargingEstimate.forScooter(baseline,soc,rated,ModelRegistry.chargeReferenceProduct(modelDescriptor(profile)),charger,curve,chargeReferenceGate,
                profile.id+":"+ble.currentTransportSerial(),chargingValue(readings,"current_a").observedMs,now,chargeProgress.progress(profile.id,now),
                chargeTaper.tracking(profile.id)?chargeTaper:null);
    }

    private ChargeScheduleController schedules(){
        if(chargeScheduleController==null)attachEvidence(chargeScheduleController=new ChargeScheduleController(new ChargeScheduleController.Store(){
            public String read(String scope){return scope.startsWith("simulation:")?null:settings.getString("charge_schedule_owned_"+scope,null);}
            public void write(String scope,String value){if(!scope.startsWith("simulation:"))settings.edit().putString("charge_schedule_owned_"+scope,value).apply();}
        },new ChargeScheduleController.Driver(){
            public ChargeScheduleController.Context context(){return chargeScheduleContext();}
            public void clock(Consumer<Boolean> completion){ble.syncClock(completion);}
            public void time(long wallMs,int offsetSeconds,Consumer<Boolean> completion){ble.setChargeStart(wallMs/1000,offsetSeconds,completion);}
            // sent runs when the frame really goes out: a time write refused at the gate (a status from before a sleep, say) leaves no marker.
            @Override public void time(long wallMs,int offsetSeconds,Runnable sent,Consumer<Boolean> completion){ble.setChargeStart(wallMs/1000,offsetSeconds,completion,sent);}
            public void enabled(boolean enabled,Consumer<Boolean> completion){ble.setChargeSchedule(enabled,completion);}
        }));
        return chargeScheduleController;
    }
    /** Remembered intentions are displayed separately from raw firmware readings. */
    public ChargeScheduleController.View chargeSchedule(){return schedules().view();}
    private Consumer<String> scheduleCompletion(Consumer<String> completion){return result->{if(!destroyed){changed();if(completion!=null)completion.accept(result);}};}
    public void setChargeDoneByEnabled(boolean enabled,Consumer<String> completion){schedules().setDoneEnabled(enabled,scheduleCompletion(completion),this::doneByStartSent);changed();}
    public void setChargeDoneByTime(int hour,int minute,Consumer<String> completion){
        schedules().setDoneTime(hour,minute,scheduleCompletion(result->{if(result==null)retargetChargeMeasure(hour*60+minute);if(completion!=null)completion.accept(result);}),this::doneByStartSent);changed();
    }
    public void setManualChargeEnabled(boolean enabled,Consumer<String> completion){schedules().setManualEnabled(enabled,scheduleCompletion(completion),this::manualStartSent);changed();}
    public void setManualChargeStart(long wallMs,Consumer<String> completion){schedules().setManualStart(wallMs,scheduleCompletion(completion),this::manualStartSent);changed();}
    /** The missed-start dialog is on screen: no notification follows for that intention. */
    public void missedStartShown(){schedules().missedShown();cancelMissedStartNotice();}
    public void acknowledgeMissedStart(){schedules().acknowledgeMissed();cancelMissedStartNotice();changed();}
    /** Sends OFF through the schedule controller; a confirmed OFF clears the alert. */
    public void turnOffMissedStart(Consumer<String> completion){
        schedules().turnOffMissed(scheduleCompletion(result->{if(result==null)cancelMissedStartNotice();if(completion!=null)completion.accept(result);}));changed();
    }
    public String missedStartTitle(ChargeScheduleController.Missed missed){return missed.doneBy?T.serviceMissedTitleDoneBy():T.serviceMissedTitleSchedule();}
    public String missedStartText(ChargeScheduleController.Missed missed,boolean inApp){
        String time=DisplayTime.clock(this,settings,missed.startWallMs);
        return missed.doneBy?(missed.test?(inApp?T.serviceMissedDoneByTestDialog(time,Fmt.integer(missed.soc),Fmt.integer(missed.armedSoc))
                                :T.serviceMissedDoneByTestNotice(time,Fmt.integer(missed.soc),Fmt.integer(missed.armedSoc)))
                        :(inApp?T.serviceMissedDoneByDialog(time,Fmt.integer(missed.soc),Fmt.integer(missed.armedSoc))
                                :T.serviceMissedDoneByNotice(time,Fmt.integer(missed.soc),Fmt.integer(missed.armedSoc))))
                :missed.test?(inApp?T.serviceMissedTestDialog(time,Fmt.integer(missed.soc),Fmt.integer(missed.armedSoc))
                                :T.serviceMissedTestNotice(time,Fmt.integer(missed.soc),Fmt.integer(missed.armedSoc)))
                        :(inApp?T.serviceMissedSetDialog(time,Fmt.integer(missed.soc),Fmt.integer(missed.armedSoc))
                                :T.serviceMissedSetNotice(time,Fmt.integer(missed.soc),Fmt.integer(missed.armedSoc)));
    }
    /** Out of sight, a missed start is posted once per intention; in sight, MainActivity shows its dialog instead. */
    private void noticeMissedStart(){
        // A simulation's schedule is the simulator's (a simulation: scope): never a notice in the shade.
        if(visible||chargeScheduleController==null||simulating())return;
        ChargeScheduleController.Missed missed=chargeScheduleController.takeUnshownMissed();if(missed==null)return;
        String text=missedStartText(missed,false);
        try{
            notification();// Ensures the channel exists even if the foreground notice never posted.
            PendingIntent open=PendingIntent.getActivity(this,4,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            post(MISSED_START_NOTICE,new Notification.Builder(this,"scooter").setSmallIcon(R.drawable.ic_volt_notification)
                    .setContentTitle(missedStartTitle(missed)).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE).build());
        }catch(RuntimeException unavailable){}
    }
    private void cancelMissedStartNotice(){cancelNotice(MISSED_START_NOTICE);}

    /**
     * Charge done by may first measure for up to about ten minutes, only when the pack model would
     * otherwise plan for a fresh charge because Volt did not see this one begin.
     */
    public boolean chargeMeasureOffered(){
        if(destroyed||simulating()||chargeMeasure.active()||profiles==null||profiles.active()==null||!notificationsAllowed())return false;
        ChargeScheduleController.View plan=chargeSchedule();
        if(plan.busy||plan.doneByEnabled||plan.doneByBlockedReason!=null)return false;
        ChargeProgressTracker.Progress progress=chargeProgress.progress(profiles.active().id,SystemClock.elapsedRealtime());
        if(progress==null||progress.startObserved||progress.ageInferred||!chargingEstimate(displayReadings()).stockPackModel)return false;
        Long start=bufferedChargeStart(plan.doneByMinuteOfDay);
        return start!=null&&ChargeMeasureWindow.useful(System.currentTimeMillis(),start);
    }
    /** Start of today's buffered plan; null when it would charge now or has no estimate. */
    private Long bufferedChargeStart(int minute){
        ChargeScheduleController.Context c=chargeScheduleContext();
        // A plan computed from live readings (the estimate is fresh-only): the live connection is enough; arming asks ready.
        if(!c.linked||c.estimateMinutes==null||!Double.isFinite(c.estimateMinutes)||c.estimateMinutes<=0)return null;
        try{ChargeScheduleController.Plan plan=ChargeScheduleController.plan(c.now,minute,c.estimateMinutes,c.zone,c.wholeHours);return plan.startNow?null:plan.startWallMs;}
        catch(IllegalArgumentException outside){return null;}
    }
    public boolean isChargeMeasuring(){return chargeMeasure.active();}
    public boolean chargeMeasureArming(){return chargeMeasure.arming();}
    /** Whole minutes left, rounded up. */
    public int chargeMeasureMinutesLeft(){return (int)((chargeMeasure.remainingMs(SystemClock.elapsedRealtime())+59_999)/60_000);}
    public boolean startChargeMeasure(){
        if(!chargeMeasureOffered())return false;
        int minute=chargeSchedule().doneByMinuteOfDay;Long start=bufferedChargeStart(minute);
        return start!=null&&beginChargeMeasure(minute,start);
    }
    private boolean beginChargeMeasure(int minute,long cautiousStartWall){
        if(destroyed||chargeMeasure.active()||activeProfileId().isEmpty()||!ChargeMeasureWindow.useful(System.currentTimeMillis(),cautiousStartWall))return false;
        chargeMeasure.start(activeProfileId(),minute,SystemClock.elapsedRealtime(),System.currentTimeMillis(),cautiousStartWall);
        measureOp(ChargeScheduleController.Op.MEASURE_START);
        cancelChargeDoneNotice();cancelBackgroundDeadline();applyWorkPolicy(false);updateNotification();changed();return true;
    }
    /** The owner's Stop measuring: an explicit choice, so nothing is armed and no notice is posted. */
    public void cancelChargeMeasure(){if(!chargeMeasure.active()||chargeMeasure.arming())return;measureOp(ChargeScheduleController.Op.MEASURE_CANCEL);chargeMeasure.finish();endChargeMeasureWork();}
    /** The owner's Schedule now: arm with what is known, the cautious fresh-charge plan if age is not. */
    public void scheduleChargeNow(){
        if(!chargeMeasure.active()||chargeMeasure.arming())return;
        ChargeProgressTracker.Progress progress=chargeProgress.progress(chargeMeasure.profile(),SystemClock.elapsedRealtime());
        armMeasured(progress!=null&&(progress.startObserved||progress.ageInferred));
    }
    private void updateChargeMeasure(long mono){
        if(!chargeMeasure.active()||chargeMeasure.arming())return;
        ChargeScheduleController.Context c=chargeScheduleContext();
        ChargeProgressTracker.Progress progress=chargeProgress.progress(chargeMeasure.profile(),mono);
        boolean known=progress!=null&&(progress.startObserved||progress.ageInferred)&&chargeSchedule().doneByBlockedReason==null;
        ChargeMeasureWindow.Decision decision=chargeMeasure.evaluate(mono,activeProfileId(),c.linked,c.ready,c.charging,known);
        if(decision.step==ChargeMeasureWindow.Step.ARM)armMeasured(decision.measured);
        else if(decision.step==ChargeMeasureWindow.Step.NOT_SET)endChargeMeasure(decision.message);
    }
    /**
     * A finish time edited while measuring: the window can only end sooner, and a plan that must
     * already start arms at once instead of running on into the next day's deadline.
     */
    private void retargetChargeMeasure(int minute){
        if(!chargeMeasure.active()||chargeMeasure.arming())return;
        long nowMono=SystemClock.elapsedRealtime(),nowWall=System.currentTimeMillis();Long start=bufferedChargeStart(minute);
        if(start!=null&&ChargeMeasureWindow.useful(nowWall,start)){chargeMeasure.retarget(minute,nowMono,nowWall,start);updateNotification();changed();return;}
        ChargeProgressTracker.Progress progress=chargeProgress.progress(chargeMeasure.profile(),nowMono);
        armMeasured(progress!=null&&(progress.startObserved||progress.ageInferred));
    }
    /** Keeps the bounded connection until the one-shot arming has finished its scooter writes. */
    private void armMeasured(boolean measured){
        chargeMeasure.arm();measureOp(ChargeScheduleController.Op.MEASURE_ARM);updateNotification();changed();measuredStartSent=0;
        schedules().setDoneEnabled(true,result->{
            if(!chargeMeasure.active())return;
            ChargeScheduleController.View plan=chargeSchedule();boolean armed=result==null&&plan.doneByEnabled;
            chargeMeasure.finish();
            // On screen, the short start-sent toast; the background notice below keeps its plan detail and gains the safe-to-disconnect line.
            if(armed&&visible&&measuredStartSent>0){doneByStartSent(plan.startWallMs);endChargeMeasureWork();return;}
            chargeDoneNotice(armed,armed?(measured?T.serviceMeasureMeasured(DisplayTime.clock(this,settings,plan.startWallMs),DisplayTime.clock(this,settings,plan.doneByWallMs))
                    :T.serviceMeasurePlanned(DisplayTime.clock(this,settings,plan.startWallMs),DisplayTime.clock(this,settings,plan.doneByWallMs)))
                    :result==null?T.serviceMeasureOff():T.serviceMeasureResult(ChargeScheduleText.shown(result)));
            endChargeMeasureWork();
        },start->measuredStartSent=start);
    }
    /** The scooter confirmed this Charge done by start, so it no longer needs Volt: say so. armMeasured has its own message. */
    private void doneByStartSent(long startWallMs){if(!chargeMeasure.active())startSent(startWallMs,true);}
    private void manualStartSent(long startWallMs){startSent(startWallMs,false);}
    private void startSent(long startWallMs,boolean doneBy){
        if(destroyed)return;
        String text=T.serviceDoneStartSent(DisplayTime.clock(this,settings,startWallMs));
        if(visible){android.widget.Toast.makeText(this,text,android.widget.Toast.LENGTH_LONG).show();return;}
        // Off screen, the same one notice a measured arm posts, with the short line. Kept beside chargeDoneNotice: that one's text statements are frozen in a catalog change.
        try{
            notification();// Ensures the channel exists even if the foreground notice never posted.
            PendingIntent open=PendingIntent.getActivity(this,3,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            noteStartSentNotice(startWallMs);
            // Manual Scheduled charging has no title of its own; the line says what happened either way.
            post(CHARGE_DONE_NOTICE,new Notification.Builder(this,"scooter").setSmallIcon(R.drawable.ic_volt_notification)
                    .setContentTitle(doneBy?T.serviceDoneTitleSet():null).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE).build());
        }catch(RuntimeException unavailable){}
    }
    /** A window that cannot finish is never silent: the owner hears why nothing was armed, in the shown language. */
    private void endChargeMeasure(CharSequence reason){
        if(!chargeMeasure.active())return;
        measureOp(ChargeScheduleController.Op.MEASURE_NOT_SET);chargeMeasure.finish();chargeDoneNotice(false,T.serviceMeasureNotSet(reason));
        if(!destroyed)endChargeMeasureWork();
    }
    /** Charge done by's measuring window or a Scheduled charging test keeps the connection, each for a bounded few minutes. */
    private boolean holding(){return chargeMeasure.active()||scheduleTesting();}
    public boolean scheduleTesting(){return chargeScheduleController!=null&&chargeScheduleController.testing();}
    /** UT5 MAX only: other models' whole-hour starts are too coarse to time a two-minute resume. */
    public boolean scheduleTestOffered(){
        return ble!=null&&ble.controlOffered("timed_charging")&&ble.controlOffered("charge_start")&&ble.decision("charge_start").layer==ModelRegistry.Layer.UT5_MAX;
    }
    /** Starts the owner's test; null when started, else why not. */
    public String startScheduleTest(){
        if(destroyed)return T.serviceTestReopen();
        if(chargeMeasure.active())return T.serviceWaitMeasuring();
        String blocked=schedules().startTest(this::scheduleTestFinished);
        if(blocked!=null)return blocked;
        cancelScheduleTestNotice();pendingScheduleTest=null;cancelBackgroundDeadline();applyWorkPolicy(false);updateNotification();changed();return null;
    }
    public void cancelScheduleTest(){if(chargeScheduleController!=null)chargeScheduleController.cancelTest();changed();}
    /** The finished test not yet shown in a dialog; null when none. */
    public ChargeScheduleController.TestResult pendingScheduleTest(){return pendingScheduleTest;}
    public void scheduleTestShown(){pendingScheduleTest=null;cancelScheduleTestNotice();}
    private void scheduleTestFinished(ChargeScheduleController.TestResult result){
        // A clean cancel is the owner's own choice and gets a toast; anything else is a dialog in sight, a notification out of it.
        if(result.outcome==ChargeScheduleTest.Outcome.CANCELLED&&result.scheduleOff&&result.cleanup==null){
            if(visible&&!destroyed)android.widget.Toast.makeText(this,T.serviceTestCancelledToast(),android.widget.Toast.LENGTH_LONG).show();
        }else pendingScheduleTest=result;
        if(pendingScheduleTest!=null&&(!visible||destroyed))scheduleTestNotice(result);
        if(!destroyed){applyWorkPolicy(false);updateNotification();changed();}
    }
    /** Row and notification progress while a test runs. */
    public String scheduleTestStatus(){
        ChargeScheduleController.View v=chargeSchedule();if(v.testPhase==null)return null;
        long left=v.testStartWallMs-System.currentTimeMillis();
        switch(v.testPhase){
            case ARMING:return T.serviceTestArming();
            case PAUSING:return T.serviceTestPausing();
            case ENDING:return T.serviceTestEnding();
            default:long seconds=(left+999)/1000;return left>0?T.serviceTestResumesIn(Fmt.integer(seconds/60),String.format(Locale.ROOT,"%02d",seconds%60)):T.serviceTestWaiting();
        }
    }
    public String scheduleTestTitle(ChargeScheduleController.TestResult r){
        switch(r.outcome){
            case PASSED:return T.serviceTestTitlePassed();
            case RESUMED_EARLY:return T.serviceTestTitleEarly();
            case RESUMED_LATE:return T.serviceTestTitleLate();
            case NO_PAUSE:return T.serviceTestTitleNoPause();
            case NO_RESUME:return T.serviceTestTitleNoResume();
            case INTERRUPTED:return T.serviceTestTitleInterrupted();
            case CANCELLED:return T.serviceTestTitleCancelled();
            default:return T.serviceTestTitleFailed();
        }
    }
    public String scheduleTestText(ChargeScheduleController.TestResult r){
        int seconds=r.offsetSeconds==null?0:Math.abs(r.offsetSeconds),minutes=Math.max(1,Math.round(seconds/60f));
        String about=T.serviceTestAbout(minutes,minutes);
        String text;
        switch(r.outcome){
            case PASSED:text=seconds==0?T.serviceTestPassedOnTime():(r.offsetSeconds<0?T.serviceTestPassedBeforeCount(seconds,Fmt.integer(seconds)):T.serviceTestPassedAfterCount(seconds,Fmt.integer(seconds)));break;
            case RESUMED_EARLY:text=T.serviceTestEarly(about,about,T.serviceTestSetClock());break;
            case RESUMED_LATE:text=T.serviceTestLate(about,about,T.serviceTestSetClock());break;
            case NO_PAUSE:text=T.serviceTestNoPause(T.serviceTestSetClock());break;
            case NO_RESUME:text=T.serviceTestNoResume(T.serviceTestSetClock());break;
            case LIMIT_REACHED:text=T.serviceTestLimit();break;
            case INTERRUPTED:return T.serviceTestInterrupted();
            case CANCELLED:text="";break;
            default:text=r.failure==null?T.serviceTestNotStarted():T.serviceTestNotStartedReason(ChargeScheduleText.shown(r.failure));
        }
        // Cleanup is reported plainly; an unconfirmed OFF leaves the missed-start alert to follow up.
        if(!r.scheduleOff)text+=T.serviceTestOffUnconfirmed();
        else if(r.cleanup!=null)text+=T.serviceTestCleanup(ChargeScheduleText.shown(r.cleanup));
        return text.trim();
    }
    private void scheduleTestNotice(ChargeScheduleController.TestResult result){
        String text=scheduleTestText(result);
        try{
            notification();// Ensures the channel exists even if the foreground notice never posted.
            PendingIntent open=PendingIntent.getActivity(this,5,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            post(SCHEDULE_TEST_NOTICE,new Notification.Builder(this,"scooter").setSmallIcon(R.drawable.ic_volt_notification)
                    .setContentTitle(scheduleTestTitle(result)).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE).build());
        }catch(RuntimeException unavailable){}
    }
    private void cancelScheduleTestNotice(){cancelNotice(SCHEDULE_TEST_NOTICE);}
    private void endChargeMeasureWork(){if(destroyed)return;applyWorkPolicy(false);updateNotification();changed();}
    private boolean notificationsAllowed(){return channelAllowed("scooter");}
    private boolean channelAllowed(String id){
        NotificationManager manager=getSystemService(NotificationManager.class);NotificationChannel channel=manager.getNotificationChannel(id);
        return manager.areNotificationsEnabled()&&(channel==null||channel.getImportance()!=NotificationManager.IMPORTANCE_NONE);
    }
    /** Separate from the foreground notice, which Android removes once measuring ends. */
    private void chargeDoneNotice(boolean set,String text){
        // A posted notice is a measured arm's: its text already says when charging resumes; the scooter no longer needs Volt,
        // unless that start passed while its confirmation was in flight. Consumed here: this arm's answer, for this notice only.
        long safeStart=set?measuredStartSent:0;measuredStartSent=0;
        if(safeStart>0&&!visible)text=T.serviceDoneSafeToDisconnect(text);
        if(visible){android.widget.Toast.makeText(this,set?T.serviceDoneToastSet(text):T.serviceDoneToastNotSet(text),android.widget.Toast.LENGTH_LONG).show();return;}
        try{
            notification();// Ensures the channel exists even if the foreground notice never posted.
            PendingIntent open=PendingIntent.getActivity(this,3,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            noteStartSentNotice(safeStart);
            post(CHARGE_DONE_NOTICE,new Notification.Builder(this,"scooter").setSmallIcon(R.drawable.ic_volt_notification)
                    .setContentTitle(set?T.serviceDoneTitleSet():T.serviceDoneTitleNotSet()).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE).build());
        }catch(RuntimeException unavailable){}
    }
    private void cancelChargeDoneNotice(){cancelNotice(CHARGE_DONE_NOTICE);}
    /** The start a posted or queued "safe to disconnect" notice speaks for, 0 for none. Saved: the notice outlives this service, whose connection it says can end. */
    private void noteStartSentNotice(long start){
        if(start>0)settings.edit().putLong(START_SENT_NOTICE,start).putString(START_SENT_SCOPE,chargeScheduleContext().scope).apply();
        else if(settings.contains(START_SENT_NOTICE))settings.edit().remove(START_SENT_NOTICE).remove(START_SENT_SCOPE).apply();
    }
    /** Takes back a "safe to disconnect" notice, posted or still queued, once its plan was turned off or replaced. */
    private void retireStartSentNotice(){
        long start=settings.getLong(START_SENT_NOTICE,0);if(start==0||chargeScheduleController.startStillPlanned(settings.getString(START_SENT_SCOPE,""),start))return;
        noteStartSentNotice(0);cancelChargeDoneNotice();
    }
    // ---- Battery alerts (ChargeAlertPolicy) -------------------------------------------------------

    /** Loads the selected scooter's alert settings and session; another scooter never inherits them. */
    private void ensureAlertPolicy(){
        String id=activeProfileId();if(id.equals(alertProfile)&&(alertPolicy!=null||id.isEmpty()))return;
        alertProfile=id;alertNoticeKey="";
        alertPolicy=id.isEmpty()?null:new ChargeAlertPolicy(ChargeAlertPolicy.Settings.decode(settings.getString("charge_alerts_"+id,null)),
                ChargeAlertPolicy.State.decode(settings.getString("charge_alert_state_"+id,null)));
    }
    /**
     * The model that decides what a screen shows for this profile (drive-mode names, photo, rated energy, maintenance
     * defaults, charge reference): the simulated model while simulating, else the profile's saved model.
     */
    public DeviceDescriptor modelDescriptor(ScooterProfiles.Profile p){return simulating()?ble.descriptor():p==null?null:p.savedDescriptor();}
    /** The live model while connected to this scooter (or simulating), the saved profile's model offline. */
    private DeviceDescriptor alertDescriptor(ScooterProfiles.Profile p){
        ScooterProfiles.Profile bound=ble.profile();
        return simulating()||ble.ready()&&bound!=null&&bound.id.equals(p.id)?ble.descriptor():p.savedDescriptor();
    }
    /** Only models without a native charge limit: never where the charge limit control is offered (UT5 MAX and the official app's charge family). */
    public boolean chargeAlertsOffered(){
        ScooterProfiles.Profile p=profiles==null?null:profiles.active();
        return !destroyed&&p!=null&&ble!=null&&ChargeAlertPolicy.offered(alertDescriptor(p));
    }
    /** Why chargeAlertsOffered() is false (ChargeAlertPolicy.missing), or null when it is true. */
    public CapabilityResolver.Reason chargeAlertsMissing(){
        ScooterProfiles.Profile p=profiles==null?null:profiles.active();
        if(destroyed||p==null||ble==null)return CapabilityResolver.Reason.NOT_CONNECTED;
        return ChargeAlertPolicy.missing(alertDescriptor(p));
    }
    /**
     * The Battery page's Charge done by rule (timed_charging and charge_start offered) for the selected scooter, judged
     * like chargeAlertsOffered (the live model while connected to it, its saved model offline): why it can't schedule
     * charging, or null when it can or no scooter is selected (ShortcutPicker.scheduleMissing).
     */
    public CapabilityResolver.Reason chargeScheduleMissing(){
        ScooterProfiles.Profile p=profiles==null?null:profiles.active();
        if(destroyed||p==null||ble==null)return null;
        return ShortcutPicker.scheduleMissing(alertDescriptor(p));
    }
    public ChargeAlertPolicy.Settings chargeAlerts(){
        if(destroyed||settings==null)return ChargeAlertPolicy.Settings.OFF;
        ensureAlertPolicy();return alertPolicy==null?ChargeAlertPolicy.Settings.OFF:alertPolicy.settings();
    }
    /** Saved per scooter at once and kept for every later charge; a level already passed this charge never alerts on saving. */
    public boolean setChargeAlerts(ChargeAlertPolicy.Settings next){
        if(destroyed||dataMaintenance||next==null||activeProfileId().isEmpty())return false;
        ensureAlertPolicy();
        settings.edit().putString("charge_alerts_"+alertProfile,next.encode()).apply();
        alertPolicy.settings(next);saveAlertState();
        if(!next.alarm)stopChargeAlarm();
        try{alertChannel(getSystemService(NotificationManager.class));}catch(RuntimeException unavailable){}
        changed();return true;
    }
    /** A charge alert is armed for this charge (whether or not Volt may hold the connection for it). */
    public boolean chargeAlertWatching(){return !destroyed&&!simulating()&&alertPolicy!=null&&alertPolicy.watching();}
    /** Android shows Volt's battery alerts: app notifications on and the Battery alerts channel not blocked. */
    public boolean alertNotificationsAllowed(){return channelAllowed(ALERT_CHANNEL);}
    /** Background watching also needs the connection notice: app notifications on and its channel not blocked. */
    public boolean alertWatchAllowed(){return notificationsAllowed();}
    public boolean chargeAlarmPlaying(){return alarmPlaying();}
    private boolean alarmPlaying(){return alarm!=null;}
    /** An alert that fired in sight, for MainActivity's dialog (out of sight it is a notification). */
    public String[] pendingChargeAlert(){AlertShown alert=pendingChargeAlert;return alert==null?null:new String[]{alert.title(),alert.text()};}
    public void chargeAlertShown(){pendingChargeAlert=null;}
    /** Stop watching: no charge alert for the rest of this charge; the alert stays on for the next one. */
    public void skipChargeAlert(){
        ensureAlertPolicy();if(alertPolicy==null)return;
        alertPolicy.skip();saveAlertState();updateChargeAlerts();changed();
    }
    /**
     * Holds the connection while a charge alert is armed for this charge (bounded by the policy's limit) or
     * its alarm sounds. A charge recording already holds it, so the alert rides along instead of doubling up.
     * Without the connection notice nothing holds: the owner would have no visible way to stop it.
     */
    private boolean chargeAlertHold(){
        if(destroyed||simulating()||alertPolicy==null)return false;
        return alarmPlaying()||alertPolicy.watching()&&!isChargeRecording()&&alertWatchAllowed();
    }
    private long chargeAlertDeadline(){return Math.max(alertPolicy==null?0:alertPolicy.watchDeadline(),alarmEndMono);}
    /** The connection notice is showing the watched charge (nothing higher-ranked is running). */
    private boolean alertWatchShown(){
        return chargeAlertHold()&&alertPolicy.watching()&&!alarmPlaying()&&!holding()&&!isChargeRecording()&&!isRecording()&&!phoneSaving();
    }
    // ---- Live charging (ChargeLiveHold, ChargeLiveState) -------------------------------------------

    /** On by default (owner, Q45 A); Scooter › Battery › Live charging turns it off. */
    public boolean chargeLiveEnabled(){return settings!=null&&settings.getBoolean(CHARGE_LIVE_ENABLED,true);}
    public void setChargeLiveEnabled(boolean enabled){
        if(destroyed||dataMaintenance||settings==null)return;
        settings.edit().putBoolean(CHARGE_LIVE_ENABLED,enabled).apply();
        updateChargeLive();changed();
    }
    /** How much of the card the lock screen shows; Details unless the owner chose otherwise (Q46 B). */
    public ChargeLiveState.Detail chargeLiveDetail(){
        return ChargeLiveState.Detail.of(settings==null?null:settings.getString(CHARGE_LIVE_LOCK_DETAIL,null));
    }
    public void setChargeLiveDetail(ChargeLiveState.Detail detail){
        if(destroyed||dataMaintenance||settings==null||detail==null)return;
        settings.edit().putString(CHARGE_LIVE_LOCK_DETAIL,detail.id).apply();
        chargeLiveReposts.reset();updateNotification();changed();
    }
    /** The card can show: app notifications on and the connection channel not blocked. */
    public boolean chargeLiveAllowed(){return notificationsAllowed();}
    /** Live charging holds the connection now. Without the connection notice nothing holds: the owner would have no Stop. */
    private boolean chargeLiveHold(){return !destroyed&&!simulating()&&chargeLive.active()&&notificationsAllowed();}
    /** The battery reads Volt already makes, as the hold sees them: fresh and from the selected scooter, else null. */
    private ChargeLiveHold.Facts chargeLiveFacts(){
        ScooterProfiles.Profile profile=profiles==null?null:profiles.active(),bound=ble==null?null:ble.profile();
        // linked(), not ready(): out of sight status comes every 15 s, and ready()'s 7 s window would read that as out of reach.
        boolean connected=!destroyed&&ble!=null&&!simulating()&&profile!=null&&ble.linked()&&bound!=null&&bound.id.equals(profile.id);
        Telemetry.Metric state=null,soc=null,limit=null;
        if(connected)for(Telemetry.Metric m:ble.telemetry.metrics()){
            if(m.key.equals("charging_state"))state=m;
            else if(m.key.equals("battery_percent"))soc=m;
            else if(m.key.equals("charge_limit_percent"))limit=m;
        }
        long now=SystemClock.elapsedRealtime();
        Double charging=liveReading(state,now),percent=liveReading(soc,now),limitNow=liveReading(limit,now);
        // A model without a native charge limit charges to full; its charge limit reading, if any, isn't one. The limit is
        // taken only while live, as the other two (#418): the link outlives a sleep, and the limit may have changed in it.
        Double target=limitNow==null||ChargeAlertPolicy.offered(alertDescriptor(profile))?null:limitNow;
        Boolean on=charging==null||charging!=0&&charging!=1?null:charging==1;
        Integer pct=percent==null||percent<0||percent>100?null:(int)Math.round(percent);
        Integer cap=target==null||target<1||target>100?null:(int)Math.round(target);
        return new ChargeLiveHold.Facts(chargeLiveEnabled(),connected,on,chargeLiveScheduled(),pct,cap,profile==null?null:profile.id);
    }
    private static Double liveReading(Telemetry.Metric metric,long now){
        return metric!=null&&metric.numericValue!=null&&Double.isFinite(metric.numericValue)&&metric.updatedMs>0
                &&now>=metric.updatedMs&&now-metric.updatedMs<=ChargeAlertPolicy.FRESH_MS?metric.numericValue:null;
    }
    /** A Volt charge schedule (Charge done by or Scheduled charging) starts within the hold limit, or just started. */
    private boolean chargeLiveScheduled(){
        if(chargeScheduleController==null)return false;
        ChargeScheduleController.View plan=chargeSchedule();long now=System.currentTimeMillis();
        return (plan.doneByEnabled||plan.manualEnabled)&&plan.startWallMs>0&&now<plan.startWallMs+ChargeScheduleController.START_GRACE_MS
                &&plan.startWallMs-now<=ChargeLiveHold.HOLD_MS;
    }
    /** The owner left Volt: the hold arms only now, and only from what is true now (owner, Q45 A and Q47). */
    private void armChargeLive(){
        if(destroyed||simulating()||settings==null||profiles==null||ble==null){chargeLive.shown();return;}
        restoreChargeLiveStopped();
        if(chargeLive.leave(SystemClock.elapsedRealtime(),chargeLiveFacts()))chargeLiveArmedWall=System.currentTimeMillis();
        chargeLiveReposts.reset();saveChargeLiveStopped();
    }
    /**
     * Volt's screen is in front (uiVisible; a service recreated in the background never gets here): the hold ends,
     * and the selected scooter's Disconnect is over, so leaving again arms the card as usual (owner, 2026-11-06).
     */
    private void chargeLiveOpened(){
        if(destroyed||simulating()||settings==null||profiles==null){chargeLive.shown();return;}
        restoreChargeLiveStopped();
        ScooterProfiles.Profile profile=profiles.active();
        chargeLive.opened(profile==null?null:profile.id);saveChargeLiveStopped();
    }
    /** Once per service: takes back the Stops the last service stored for this device (ChargeLiveHold bounds their age and number). */
    private void restoreChargeLiveStopped(){
        if(chargeLiveRestored||settings==null)return;
        chargeLiveRestored=true;
        long nowWall=System.currentTimeMillis(),nowMono=SystemClock.elapsedRealtime();
        String saved=settings.getString(CHARGE_LIVE_STOPPED,null);
        if(saved!=null)try{
            JSONObject stored=new JSONObject(saved);
            for(Iterator<String> ids=stored.keys();ids.hasNext();){
                String id=ids.next();JSONObject stop=stored.optJSONObject(id);
                if(stop!=null&&stop.has("wall"))chargeLive.restoreStopped(id,stop.optInt("best",-1),nowWall-stop.optLong("wall",0),nowMono);
            }
        }catch(JSONException damaged){}// Nothing usable: no Stop to keep.
        saveChargeLiveStopped();
    }
    /** Stores the Stops that still count, or removes the entry once none does; writes only when they changed. */
    private void saveChargeLiveStopped(){
        if(settings==null||!chargeLiveRestored)return;
        List<ChargeLiveHold.Stopped> stopped=chargeLive.stopped(SystemClock.elapsedRealtime());
        List<Object> key=new ArrayList<>();
        for(ChargeLiveHold.Stopped stop:stopped){key.add(stop.profile);key.add(stop.best);}
        if(key.equals(chargeLiveSaved))return;
        chargeLiveSaved=key;
        if(stopped.isEmpty()){settings.edit().remove(CHARGE_LIVE_STOPPED).apply();return;}
        JSONObject out=new JSONObject();long nowWall=System.currentTimeMillis();
        try{for(ChargeLiveHold.Stopped stop:stopped)out.put(stop.profile,new JSONObject().put("best",stop.best).put("wall",nowWall-stop.ageMs));}
        catch(JSONException impossible){return;}
        settings.edit().putString(CHARGE_LIVE_STOPPED,out.toString()).apply();
    }
    /**
     * The card's Disconnect with Background connection on, word for word what the plain notice's DISCONNECT does: drops the link (no scooter command; BleController.disconnect stops reconnecting) and says
     * whether nothing else needs the service, which then stops until something starts it again, such as opening Volt.
     */
    private boolean noticeDisconnect(){
        ble.disconnect();
        return !isRecording()&&!isChargeRecording()&&!phoneSaving()&&!rideAutoExportBusy()&&!visible;
    }
    /**
     * Disconnect on the card: Volt's own hold and card end. No scooter command is sent. With Background connection off the
     * connection then goes as after leaving Volt; with it on, onStartCommand drops it at once (noticeDisconnect).
     */
    private void stopChargeLive(){
        restoreChargeLiveStopped();
        ChargeLiveHold.Facts facts=chargeLiveFacts();
        Integer pct=facts.percent!=null?facts.percent:facts.profile.equals(chargeLiveCacheProfile)?chargeLivePercent:null;
        chargeLive.stop(pct,facts.profile,SystemClock.elapsedRealtime());saveChargeLiveStopped();updateChargeLive();changed();
    }
    /** The last level and limit the card saw, for "Last seen" while the scooter is out of reach. */
    private Integer chargeLivePercent,chargeLiveLimit;
    /** The scooter those last values are from; another scooter's are never shown. */
    private String chargeLiveCacheProfile;
    /** Every BLE change and service tick: steps the hold, reposts the card only when what it shows changed. */
    private void updateChargeLive(){
        if(chargeLiveUpdating||destroyed||dataMaintenance||settings==null||profiles==null||ble==null)return;
        chargeLiveUpdating=true;
        try{
            long now=SystemClock.elapsedRealtime();
            restoreChargeLiveStopped();
            ChargeLiveHold.Facts facts=chargeLiveFacts();
            if(!facts.profile.equals(chargeLiveCacheProfile)){chargeLiveCacheProfile=facts.profile;chargeLivePercent=chargeLiveLimit=null;}
            if(facts.percent!=null)chargeLivePercent=facts.percent;
            if(facts.connected)chargeLiveLimit=facts.limit;
            ChargeLiveHold.End end=chargeLive.step(now,facts);saveChargeLiveStopped();
            if(end==ChargeLiveHold.End.FINISHED)chargeLiveDoneNotice(facts);
            // The reconnect window and the hold limit pass without a new reading.
            handler.removeCallbacks(chargeLiveCheck);long next=chargeLive.nextCheck();
            if(next>0)handler.postDelayed(chargeLiveCheck,Math.max(250,next-now));
            boolean held=chargeLiveHold();
            if(held!=chargeLiveHeld){
                if(!held)captureLiveUpdate();// the card's last moment: keep Android's verdict for the developer row
                chargeLiveHeld=held;chargeLiveReposts.reset();chargeLiveLook.reset();
                // Out of sight, the end of the hold is like leaving Volt: the usual ten seconds, then sleep.
                if(!held&&!visible)stopAfterChargeLive();
                applyWorkPolicy(false);updateNotification();
            }else if(chargeLiveShown()){
                ChargeLiveState live=chargeLiveState();
                if(chargeLiveReposts.due(live,SystemClock.elapsedRealtime()))updateNotification();
            }
        }finally{chargeLiveUpdating=false;}
    }
    private void stopAfterChargeLive(){
        if(keepsWorking()||backgroundConnection())return;
        backgroundDeadlineMs=SystemClock.elapsedRealtime()+10000;handler.removeCallbacks(backgroundStop);handler.postDelayed(backgroundStop,10000);
    }
    /**
     * The connection notice is the live card: during a charge recording (owner, Q48 A), or while the hold runs and
     * nothing ranked above it (a ride, saving a ride, Charge done by's measuring, a schedule test, a sounding alarm
     * or a watched battery alert) is showing.
     */
    private boolean chargeLiveShown(){
        if(destroyed||simulating())return false;
        // A schedule test's status and Cancel come first, even over a charge recording (now often an automatic one).
        if(isChargeRecording())return scheduleTestStatus()==null||isRecording()||phoneSaving();
        if(isRecording()||phoneSaving()||chargeMeasure.active()||scheduleTestStatus()!=null||alarmPlaying()||alertWatchShown())return false;
        return chargeLiveHold();
    }
    private ChargeLiveState chargeLiveState(){
        ChargeLiveHold.Facts facts=chargeLiveFacts();
        ScooterProfiles.Profile profile=profiles.active();
        ChargeLiveState.Input in=new ChargeLiveState.Input();
        in.scooter=profile==null?null:profile.displayName();in.detail=chargeLiveDetail();in.nowWall=System.currentTimeMillis();
        boolean cached=facts.profile.equals(chargeLiveCacheProfile);
        in.percent=facts.percent!=null?facts.percent:cached?chargeLivePercent:null;in.percentStale=facts.percent==null;in.limit=facts.connected?facts.limit:cached?chargeLiveLimit:null;
        in.reconnecting=isChargeRecording()?chargeRecovery!=null&&chargeRecovery.recovering():!facts.connected;
        in.waiting=facts.scheduled&&!Boolean.TRUE.equals(facts.charging);
        if(in.waiting){
            ChargeScheduleController.View plan=chargeSchedule();
            in.startWallMs=plan.startWallMs;in.doneByWallMs=plan.doneByEnabled?plan.doneByWallMs:0;
        }else if(facts.connected){
            ChargingEstimate.Result estimate=chargingEstimate(displayReadings());
            boolean capped=in.limit!=null&&in.limit<100;
            in.minutesLeft=estimate.charging?(capped?estimate.remainingMinutesToLimit:estimate.remainingMinutesToFull):null;
            in.watts=estimate.charging?estimate.inputWatts:null;
        }
        return ChargeLiveState.of(in,wall->DisplayTime.clock(this,settings,wall));
    }
    /** The bar, scooter name and lock screen version of the live card (Android 8 and later alike); no countdown (owner, 2026-11-06). */
    private void chargeLiveCard(Notification.Builder builder,ChargeLiveState live,CharSequence capture){
        int red=android.graphics.Color.rgb(0xE3,0x2B,0x2F);
        builder.setColor(red);
        if(live.subText!=null)builder.setSubText(live.subText);
        // Android 16 and later, where allowed: the same card asked to be a Live Update, with ProgressStyle's bar.
        boolean asked=promoteChargeLive(builder,live,capture);
        if(!asked&&live.progress>=0)builder.setProgress(live.max,live.progress,false);
        builder.setShowWhen(false);
        Notification.BigTextStyle big=new Notification.BigTextStyle().bigText(live.text);
        if(capture!=null)big.setSummaryText(capture);
        if(!asked)builder.setStyle(big);
        // Only a card Android really promoted takes the dial and the shorter title (ChargeLiveUpdate.Look): asking isn't enough, Android can decline. Everything else stays the plain card, the level in its title.
        android.graphics.drawable.Icon dial=asked&&chargeLiveLook.dial()&&live.dialPercent!=null?chargeLiveDial.icon(live.dialPercent):null;
        if(dial!=null)builder.setLargeIcon(dial).setContentTitle(live.dialTitle);
        if(live.publicTitle==null)return;
        Notification.Builder lock=new Notification.Builder(this,"scooter").setSmallIcon(R.drawable.ic_volt_notification).setColor(red)
                .setContentTitle(live.publicTitle).setContentText(live.publicText).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE);
        if(live.publicSubText!=null)lock.setSubText(live.publicSubText);
        // The dial, but not the shorter title: the lock screen's title says the level in words. Android gives the dial no description, and the bar's own value is a share of the limit.
        if(dial!=null)lock.setLargeIcon(dial);
        if(live.progress>=0)lock.setProgress(live.max,live.progress,false);
        lock.setShowWhen(false);
        builder.setPublicVersion(lock.build());
    }
    // ---- Live Update promotion (ChargeLiveUpdate; Android 16 and later) ----------------------------------

    /** The last connection notice asked to be promoted (reset for each build); the last card's verdict for the developer row. */
    private boolean liveUpdateAsked;
    private final ChargeLiveUpdate.Last liveUpdateLast=new ChargeLiveUpdate.Last();
    private boolean liveUpdateRestored;
    /** What Android did with this hold's card: the dial waits for it. */
    private final ChargeLiveUpdate.Look chargeLiveLook=new ChargeLiveUpdate.Look();
    static final String CHARGE_LIVE_UPDATE_LAST="charge_live_update_last";
    /** A moment after each post of the live card: Android decides promotion as it posts, so read it back then. */
    private final Runnable liveUpdateProbe=this::captureLiveUpdate;
    private static boolean liveUpdateLooked;
    private static String liveUpdateExtra;
    private static java.lang.reflect.Method liveUpdateMethod;
    /**
     * Android's own promotion request, where this Android has one: the Notification.EXTRA_REQUEST_PROMOTED_ONGOING
     * key and Builder.setRequestPromotedOngoing(boolean), both newer than the SDK Volt builds with, so they are
     * found by name (compared in lower case) and read from the platform rather than spelled out here.
     */
    private static synchronized void lookUpLiveUpdate(){
        if(liveUpdateLooked)return;
        liveUpdateLooked=true;
        if(Build.VERSION.SDK_INT<ChargeLiveUpdate.SDK)return;
        try{
            for(java.lang.reflect.Field field:Notification.class.getFields())
                if(field.getType()==String.class&&java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        &&field.getName().toLowerCase(Locale.ROOT).equals("extra_request_promoted_ongoing"))liveUpdateExtra=(String)field.get(null);
            for(java.lang.reflect.Method method:Notification.Builder.class.getMethods())
                if(method.getName().toLowerCase(Locale.ROOT).equals("setrequestpromotedongoing")&&method.getParameterCount()==1
                        &&method.getParameterTypes()[0]==boolean.class)liveUpdateMethod=method;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable){liveUpdateExtra=null;liveUpdateMethod=null;}
    }
    /** This Android can be asked to promote a notification. */
    static boolean liveUpdateRequestAvailable(){lookUpLiveUpdate();return liveUpdateExtra!=null||liveUpdateMethod!=null;}
    /** Live Updates are allowed for Volt in Android's settings (always false before Android 16). */
    boolean liveUpdateAllowed(){
        if(Build.VERSION.SDK_INT<ChargeLiveUpdate.SDK)return false;
        try{return getSystemService(NotificationManager.class).canPostPromotedNotifications();}
        catch(RuntimeException|LinkageError unknown){return false;}
    }
    /**
     * Asks Android to promote the live card: ProgressStyle's bar, the chip's short text and the request. Returns false,
     * with the builder as it was, when Volt doesn't ask (ChargeLiveUpdate.promote) or anything fails; the card is then
     * the plain one, exactly as before.
     */
    private boolean promoteChargeLive(Notification.Builder builder,ChargeLiveState live,CharSequence capture){
        if(!ChargeLiveUpdate.promote(Build.VERSION.SDK_INT,liveUpdateRequestAvailable(),liveUpdateAllowed(),capture!=null))return false;
        ChargeAlertPolicy.Settings alerts=chargeAlertsOffered()?chargeAlerts():ChargeAlertPolicy.Settings.OFF;
        ChargeLiveUpdate bar=ChargeLiveUpdate.of(live,alerts.charge?alerts.chargeAt:null);
        try{
            List<Notification.ProgressStyle.Segment> segments=new ArrayList<>();
            for(ChargeLiveUpdate.Segment part:bar.segments)segments.add(new Notification.ProgressStyle.Segment(part.length).setColor(liveUpdateColor(part.role)));
            List<Notification.ProgressStyle.Point> points=new ArrayList<>();
            for(ChargeLiveUpdate.Point mark:bar.points)points.add(new Notification.ProgressStyle.Point(mark.position).setColor(liveUpdateColor(mark.role)));
            Notification.ProgressStyle style=new Notification.ProgressStyle().setProgressSegments(segments).setProgressPoints(points)
                    .setProgress(bar.progress).setProgressIndeterminate(bar.indeterminate)
                    .setProgressTrackerIcon(android.graphics.drawable.Icon.createWithResource(this,R.drawable.ic_charge_bar_bolt));
            builder.setStyle(style);
            if(bar.shortCritical!=null)builder.setShortCriticalText(bar.shortCritical);
            if(liveUpdateMethod!=null)liveUpdateMethod.invoke(builder,true);
            if(liveUpdateExtra!=null){Bundle request=new Bundle();request.putBoolean(liveUpdateExtra,true);builder.addExtras(request);}
            liveUpdateAsked=true;
            return true;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failed){
            try{
                builder.setStyle(null);builder.setShortCriticalText(null);
                if(liveUpdateExtra!=null)builder.getExtras().remove(liveUpdateExtra);
                if(liveUpdateMethod!=null)liveUpdateMethod.invoke(builder,false);
            }catch(ReflectiveOperationException|RuntimeException|LinkageError ignored){}
            liveUpdateAsked=false;
            return false;
        }
    }
    /** Volt's palette on the bar: red to the target (as the Battery cell), orange while held, greys past the limit or out of reach. */
    private static int liveUpdateColor(ChargeLiveUpdate.Role role){
        switch(role){
            case HELD:case ALERT:return android.graphics.Color.rgb(0xE8,0xA2,0x3A);
            case STALE:return android.graphics.Color.rgb(0x87,0x89,0x82);
            case PAST_LIMIT:return android.graphics.Color.rgb(0x9A,0x9C,0x95);
            case LIMIT:return android.graphics.Color.rgb(0x5F,0xCB,0x7E);
            default:return android.graphics.Color.rgb(0xE3,0x2B,0x2F);
        }
    }
    /** After each connection notice is built: Android's own verdict on whether the live card could be promoted. */
    private void liveUpdateBuilt(Notification notice){
        boolean promotable=false;
        if(liveUpdateAsked&&Build.VERSION.SDK_INT>=ChargeLiveUpdate.SDK)
            try{promotable=notice.hasPromotableCharacteristics();}catch(RuntimeException|LinkageError unknown){}
        liveUpdateLast.built(liveUpdateAsked,promotable);
    }
    /**
     * Reads the posted live card back and keeps Android's verdict (device-only), so the developer row still has it once
     * opening Volt ended the card. Runs a moment after each post, just before the hold ends, and from the row.
     */
    private void captureLiveUpdate(){
        if(destroyed||!liveUpdateLast.asked()||Build.VERSION.SDK_INT<ChargeLiveUpdate.SDK)return;
        restoreLiveUpdateLast();
        Boolean promoted=null;
        try{
            for(android.service.notification.StatusBarNotification n:getSystemService(NotificationManager.class).getActiveNotifications())
                if(n.getId()==71)promoted=(n.getNotification().flags&Notification.FLAG_PROMOTED_ONGOING)!=0;
        }catch(RuntimeException|LinkageError unknown){}
        if(promoted==null)return;
        if(chargeLiveLook.observed(promoted)&&chargeLiveShown())updateNotification();
        if(!liveUpdateLast.observed(promoted,System.currentTimeMillis())||settings==null)return;
        try{
            settings.edit().putString(CHARGE_LIVE_UPDATE_LAST,new JSONObject().put("status",liveUpdateLast.status().name())
                    .put("wall",liveUpdateLast.wallMs()).toString()).apply();
        }catch(JSONException impossible){}
    }
    /** Once per service: the verdict an earlier service stored on this device. */
    private void restoreLiveUpdateLast(){
        if(liveUpdateRestored||settings==null)return;
        liveUpdateRestored=true;
        String saved=settings.getString(CHARGE_LIVE_UPDATE_LAST,null);
        if(saved!=null&&liveUpdateLast.status()==null)try{
            JSONObject stored=new JSONObject(saved);
            liveUpdateLast.restore(ChargeLiveUpdate.Status.valueOf(stored.optString("status")),stored.optLong("wall",0));
        }catch(JSONException|IllegalArgumentException damaged){}// Nothing usable: no verdict yet.
    }
    /** For More › Developer options: whether the last live card was a Live Update. */
    public ChargeLiveUpdate.Status liveUpdateStatus(){
        if(destroyed)return ChargeLiveUpdate.status(Build.VERSION.SDK_INT,liveUpdateRequestAvailable(),liveUpdateAllowed(),false,false,false,null);
        restoreLiveUpdateLast();
        if(chargeLiveShown())captureLiveUpdate();
        return liveUpdateLast.row(Build.VERSION.SDK_INT,liveUpdateRequestAvailable(),liveUpdateAllowed());
    }
    /** Charging finished at its target out of sight: one quiet notice, then the hold ends like any other. */
    private void chargeLiveDoneNotice(ChargeLiveHold.Facts facts){
        // A battery alert already said so for this charge; in sight, the app shows it.
        int level=facts.limit!=null&&facts.limit<100?facts.limit:100;
        if(visible||doneAnnounced(level))return;
        ScooterProfiles.Profile profile=profiles.active();
        String title=level>=100?T.chargingAlertTitleFull():T.chargingAlertTitleCharged(Fmt.integer(level));
        String text=profile==null?T.serviceNoticeScooter():profile.displayName();
        try{
            notification();// Ensures the channel exists even if the foreground notice never posted.
            PendingIntent open=PendingIntent.getActivity(this,11,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            post(CHARGE_LIVE_DONE_NOTICE,new Notification.Builder(this,"scooter").setSmallIcon(R.drawable.ic_volt_notification)
                    .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE).build());
        }catch(RuntimeException unavailable){}
    }
    /** A battery alert for this scooter's finish, posted during this hold and still showing (ChargeLiveHold.alreadyAnnounced). */
    private boolean doneAnnounced(int level){
        AlertPost posted=alertPosted;AlertShown shown=posted==null?null:posted.shown;
        if(shown==null)return false;
        ScooterProfiles.Profile profile=profiles.active();
        boolean showing=false;
        try{for(android.service.notification.StatusBarNotification n:getSystemService(NotificationManager.class).getActiveNotifications())if(n.getId()==CHARGE_ALERT_NOTICE)showing=true;}
        catch(RuntimeException unknown){showing=false;}// Unknown: post the done notice; a rare repeat beats a missed finish.
        return ChargeLiveHold.alreadyAnnounced(shown.event==ChargeAlertPolicy.Event.FULL,shown.event==ChargeAlertPolicy.Event.CHARGE,shown.level,level,
                profile!=null&&profile.id.equals(shown.profileId),chargeLiveArmedWall>0&&shown.firedWall>=chargeLiveArmedWall,showing);
    }
    /** Work that keeps the service, the CPU and the connection notice going out of sight (WorkReasons holds the list). */
    private boolean keepsWorking(){return reasons().keepsWorking();}
    private boolean backgroundConnection(){return settings.getBoolean(BACKGROUND_CONNECTION,false);}
    private WorkReasons reasons(){
        return new WorkReasons(isRecording(),isChargeRecording(),holding(),chargeAlertHold(),chargeLiveHold(),phoneSaving());
    }
    /** How long Bluetooth keeps the battery reads going: a watched battery alert's limit or the live hold's, whichever is later. */
    private long batteryWatchUntil(){
        return Math.max(chargeAlertHold()?chargeAlertDeadline():0,chargeLiveHold()?chargeLive.deadline():0);
    }
    private void saveAlertState(){
        if(alertPolicy!=null&&alertPolicy.takeDirty()&&!alertProfile.isEmpty()&&!dataMaintenance)
            settings.edit().putString("charge_alert_state_"+alertProfile,alertPolicy.state().encode()).apply();
    }
    /** Every BLE change and service tick, screen on or off. Simulated readings never alert or persist. */
    private void updateChargeAlerts(){
        if(alertUpdating||destroyed||dataMaintenance||settings==null||profiles==null||ble==null)return;
        alertUpdating=true;
        try{
            ensureAlertPolicy();ScooterProfiles.Profile profile=profiles.active();
            // Nothing is tracked or saved with both alerts off, for a model with a native charge limit, or from sample data.
            if(alertPolicy!=null&&(profile==null||simulating()||!alertPolicy.settings().any()||!ChargeAlertPolicy.offered(alertDescriptor(profile))))alertPolicy.idle();
            else if(alertPolicy!=null){
                ScooterProfiles.Profile bound=ble.profile();
                ChargeAlertPolicy.Link link=ble.linked()&&bound!=null&&bound.id.equals(profile.id)?ChargeAlertPolicy.Link.CONNECTED
                        :ble.recoverableConnectionLoss()&&ble.permissions()&&ble.bluetoothEnabled()?ChargeAlertPolicy.Link.RECONNECTING:ChargeAlertPolicy.Link.LOST;
                Telemetry.Metric state=null,soc=null;
                for(Telemetry.Metric m:ble.telemetry.metrics()){if(m.key.equals("charging_state"))state=m;else if(m.key.equals("battery_percent"))soc=m;}
                ChargeAlertPolicy.Event event=alertPolicy.step(SystemClock.elapsedRealtime(),System.currentTimeMillis(),true,
                        visible||alertNotificationsAllowed(),link,state==null?null:state.numericValue,state==null?0:state.updatedMs,soc==null?null:soc.numericValue,soc==null?0:soc.updatedMs);
                saveAlertState();
                if(event!=ChargeAlertPolicy.Event.NONE)deliverChargeAlert(event,profile);
            }
            boolean held=chargeAlertHold();
            if(held!=alertHeld){
                alertHeld=held;alertNoticeKey="";if(held)cancelAlertPausedNotice();
                applyWorkPolicy(false);updateNotification();
            }else if(alertWatchShown()){
                String key=alertPolicy.settings().chargeAt+"|"+alertPolicy.percent()+"|"+alertPolicy.percentFresh();
                if(!key.equals(alertNoticeKey)){alertNoticeKey=key;updateNotification();}
            }
        }finally{alertUpdating=false;}
    }
    /** In sight: a dialog (and the alarm, if chosen). Out of sight: the alert notification and/or the alarm. */
    private void deliverChargeAlert(ChargeAlertPolicy.Event event,ScooterProfiles.Profile profile){
        if(event==ChargeAlertPolicy.Event.PAUSED||event==ChargeAlertPolicy.Event.TIME_LIMIT){if(!visible)alertPausedNotice(event,profile.displayName());return;}
        ChargeAlertPolicy.Settings choice=alertPolicy.settings();Integer pct=alertPolicy.percent();
        int level=event==ChargeAlertPolicy.Event.LOW?choice.lowBelow:event==ChargeAlertPolicy.Event.FULL?100:choice.chargeAt;
        alertShown=new AlertShown(event,level,pct==null?level:pct,profile);
        if(choice.alarm)startChargeAlarm();
        if(visible){pendingChargeAlert=alertShown;return;}
        if(choice.notify||alarmPlaying())alertNotice(choice.notify);
    }
    /**
     * A battery alert as it fired: what ChargeAlertPolicy builds its title and text from, so they render whenever they are
     * shown, in the language shown then (plan R2), where the service once kept the text rendered when the alert fired. The
     * scooter keeps the name it had then, as that text did; a default name renders as Profile.displayName() renders it.
     */
    private static final class AlertShown{
        final ChargeAlertPolicy.Event event;final int level,percent;final String scooter;final boolean defaultName;
        /** Which scooter and when (wall clock) it fired, for live charging's done notice. */
        final String profileId;final long firedWall;
        AlertShown(ChargeAlertPolicy.Event event,int level,int percent,ScooterProfiles.Profile profile){
            profileId=profile.id;firedWall=System.currentTimeMillis();
            this.event=event;this.level=level;this.percent=percent;scooter=profile.name;
            defaultName=profile.nameDefault&&ScooterProfiles.DEFAULT_NAME.equals(profile.name);
        }
        String title(){return ChargeAlertPolicy.alertTitle(event,level);}
        String text(){return ChargeAlertPolicy.alertText(event,defaultName?T.ownerProfileDefaultName():scooter,percent);}
    }
    /**
     * What one post of the battery alert notice showed: its alert (null before any fired), whether it carried the
     * sounding alarm's Stop and the swipe's DISMISS_CHARGE_ALERT, and its time. A language change renders this again.
     */
    private static final class AlertPost{
        final AlertShown shown;final boolean sounding;final long when;
        AlertPost(AlertShown shown,boolean sounding,long when){this.shown=shown;this.sounding=sounding;this.when=when;}
    }
    /** The last alert's title and text in the current language; before any alert fired, the empty alertTitle and alertText. */
    private String alertTitle(){return alertTitle(alertShown);}
    private String alertText(){return alertText(alertShown);}
    /** An alert's title and text in the current language; before any alert fired, the empty alertTitle and alertText. */
    private String alertTitle(AlertShown shown){return shown==null?alertTitle:shown.title();}
    private String alertText(AlertShown shown){return shown==null?alertText:shown.text();}
    private void alertChannel(NotificationManager manager){
        String name=T.serviceChannelAlerts(),description=T.serviceChannelAlertsDescription(),label=name+"\n"+description;
        if(label.equals(alertChannelLabel))return;
        NotificationChannel channel=new NotificationChannel(ALERT_CHANNEL,name,NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(description);
        manager.createNotificationChannel(channel);alertChannelLabel=label;
    }
    /**
     * High importance, on its own channel. While Volt's alarm sounds the notice is silent, so the two never
     * overlap, and carries Stop; with Notify me off it is only that Stop and goes when the alarm ends.
     */
    private void alertNotice(boolean keep){
        alertNoticeKept=keep;
        // Built when it posts: with Notify me off it's only the alarm's Stop, so once the alarm ends there's nothing to post.
        post(CHARGE_ALERT_NOTICE,()->keep||alarmPlaying()?alertNoticeContent():null);
    }
    private Notification alertNoticeContent(){return alertNoticeContent(alertShown,alarmPlaying(),0);}
    /**
     * The notice for an alert, with Stop and the swipe's DISMISS_CHARGE_ALERT while sounding; when is the time it
     * shows, 0 for the builder's own (now). Recorded in alertPosted as what notice 76 shows.
     */
    private Notification alertNoticeContent(AlertShown shown,boolean sounding,long when){
        NotificationManager manager=getSystemService(NotificationManager.class);alertChannel(manager);
        PendingIntent open=PendingIntent.getActivity(this,7,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=new Notification.Builder(this,ALERT_CHANNEL).setSmallIcon(R.drawable.ic_volt_notification)
                .setContentTitle(alertTitle(shown)).setContentText(alertText(shown)).setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
                .setCategory(sounding?Notification.CATEGORY_ALARM:Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE);
        if(sounding){
            PendingIntent stop=PendingIntent.getService(this,6,new Intent(this,ScooterService.class).setAction("STOP_CHARGE_ALARM"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            // Swiping it away also ends the alarm, but means "gone": Stop alone keeps the Notify me notice.
            PendingIntent dismiss=PendingIntent.getService(this,9,new Intent(this,ScooterService.class).setAction("DISMISS_CHARGE_ALERT"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null,T.serviceActionStop(),stop).build()).setDeleteIntent(dismiss);
            // NotificationCompat.setSilent's method: a group child that alerts only for its (absent) summary stays quiet.
            builder.setGroup("volt-charge-alarm").setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY);
        }
        if(when!=0)builder.setWhen(when);
        Notification notice=builder.build();alertPosted=new AlertPost(shown,sounding,notice.when);
        return notice;
    }
    /**
     * The owner swiped the sounding notice away: end the alarm and post nothing more for it. A kept notice queued when
     * the alarm ended just before the swipe (NotificationPacer spaces posts) is dropped too.
     */
    private void dismissChargeAlert(){alertNoticeKept=false;cancelNotice(CHARGE_ALERT_NOTICE);stopChargeAlarm();}
    /** After the alarm: Notify me keeps the notice (without Stop) if the owner hasn't cleared it; alarm only removes it. */
    private void finishAlertNotice(){
        try{
            if(!alertNoticeShown())return;
            if(alertNoticeKept)alertNotice(true);else cancelNotice(CHARGE_ALERT_NOTICE);
        }catch(RuntimeException unavailable){}
    }
    /** Whether Android shows the battery alert notice now; false when the owner cleared it or it never posted. */
    private boolean alertNoticeShown(){
        NotificationManager manager=getSystemService(NotificationManager.class);boolean shown=false;
        for(android.service.notification.StatusBarNotification n:manager.getActiveNotifications())shown|=n.getId()==CHARGE_ALERT_NOTICE;
        return shown;
    }
    /**
     * The shown language changed: a battery alert notice still showing posts again in place under its id, with what it
     * showed (alertPosted: the same alert, Stop and the swipe's DISMISS_CHARGE_ALERT or neither, and its time) in the
     * new language. A newer alert that fired in sight never takes its place, and Stop is never added or removed.
     * setOnlyAlertOnce keeps that update silent (no sound, vibration or heads-up); the alarm, its timeout and
     * alertNoticeKept are left as they are. It is skipped when that post no longer matches the state behind it (a
     * kept notice no longer kept, Stop with the alarm ended), when another post of it waits in the pacer (that one
     * renders when taken, in the new language), and for a notice the owner cleared or one that never posted, all
     * checked again when the post is taken.
     */
    private void relabelAlertNotice(){
        try{
            AlertPost posted=alertPosted;
            if(!relabelDue(posted))return;
            post(CHARGE_ALERT_NOTICE,()->posted==alertPosted&&relabelDue(posted)?alertNoticeContent(posted.shown,posted.sounding,posted.when):null);
        }catch(RuntimeException unavailable){}
    }
    /** Whether that post of the alert notice still shows, unchanged, and the state behind it holds. */
    private boolean relabelDue(AlertPost posted){
        return posted!=null&&(posted.sounding?alarmPlaying():alertNoticeKept)&&!notices.waiting(CHARGE_ALERT_NOTICE)&&alertNoticeShown();
    }
    /** One per lost watch, on the connection channel like Volt's other status notices. */
    private void alertPausedNotice(ChargeAlertPolicy.Event event,String scooter){
        String text=ChargeAlertPolicy.alertText(event,scooter,0);
        try{
            notification();// Ensures the channel exists even if the foreground notice never posted.
            PendingIntent open=PendingIntent.getActivity(this,8,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            post(ALERT_PAUSED_NOTICE,new Notification.Builder(this,"scooter").setSmallIcon(R.drawable.ic_volt_notification)
                    .setContentTitle(ChargeAlertPolicy.alertTitle(event,0)).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open).setAutoCancel(true).setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE).build());
        }catch(RuntimeException unavailable){}
    }
    private void cancelAlertPausedNotice(){cancelNotice(ALERT_PAUSED_NOTICE);}
    /**
     * The phone's alarm sound on the alarm stream (USAGE_ALARM), so it plays at alarm volume with notifications
     * silenced; Do Not Disturb still applies its own alarm rule. Loops for at most ALARM_MS.
     */
    private void startChargeAlarm(){
        if(alarm!=null||destroyed)return;
        android.media.AudioAttributes attributes=new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        android.net.Uri tone=android.media.RingtoneManager.getActualDefaultRingtoneUri(this,android.media.RingtoneManager.TYPE_ALARM);
        if(tone==null)tone=android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM);
        if(tone==null)tone=android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE);
        android.media.MediaPlayer player=new android.media.MediaPlayer();
        try{player.setAudioAttributes(attributes);player.setDataSource(this,tone);player.setLooping(true);player.prepare();}
        catch(IOException|RuntimeException unavailable){player.release();return;}
        alarmFocus=new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).build();
        try{getSystemService(android.media.AudioManager.class).requestAudioFocus(alarmFocus);}catch(RuntimeException ignored){}
        player.start();alarm=player;alarmEndMono=SystemClock.elapsedRealtime()+ALARM_MS;handler.postDelayed(alarmTimeout,ALARM_MS);
    }
    /** Stop, timeout, swipe, opening Volt, the in-app dialog or turning the alarm off all end it here. */
    public void stopChargeAlarm(){
        handler.removeCallbacks(alarmTimeout);
        android.media.MediaPlayer player=alarm;alarm=null;alarmEndMono=0;if(player==null)return;
        try{player.stop();}catch(RuntimeException ignored){}
        player.release();
        if(alarmFocus!=null){try{getSystemService(android.media.AudioManager.class).abandonAudioFocusRequest(alarmFocus);}catch(RuntimeException ignored){}alarmFocus=null;}
        finishAlertNotice();
        if(!destroyed&&!dataMaintenance){updateChargeAlerts();updateNotification();}
    }

    /** Volt-programmed or native charge time ahead: current is held off on purpose, not finished. */
    private boolean scheduledChargePause(){
        if(chargeScheduleController==null)return false;
        // A test's pause is Volt's own, up to its end, which sends OFF if charging never resumed.
        if(chargeScheduleController.busy()||chargeScheduleController.testing())return true;
        ChargeScheduleController.View plan=chargeSchedule();
        return (plan.doneByEnabled||plan.manualEnabled)&&plan.startWallMs>0
                &&System.currentTimeMillis()<plan.startWallMs+ChargeScheduleController.START_GRACE_MS;
    }
    /**
     * Two answers (#418). linked: the same live connection, BleController.linked(), which outlives a stale status (out of
     * sight between 15 s polls, after a sleep, the first seconds back in sight); the controller uses it only to keep work
     * in flight going and to look at readings, each of which carries its own freshness. ready: the scooter can take a write
     * now, BleController.ready(), the write gate's own rule (CapabilityResolver, which also fills toggleReason and
     * timeReason below); the controller uses it for every choice to send and every blocked reason.
     */
    private ChargeScheduleController.Context chargeScheduleContext(){
        ChargeScheduleController.Context c=new ChargeScheduleController.Context();c.now=System.currentTimeMillis();c.mono=SystemClock.elapsedRealtime();
        if(ble==null||profiles==null)return c;
        ScooterProfiles.Profile active=profiles.active(),transport=ble.profile();
        boolean simulation=simulating();
        c.scope=active==null?"":simulation?"simulation:"+active.id+":"+ble.simulationModel()+":"+ble.simulationRegion():active.id;
        boolean same=!destroyed&&!dataMaintenance&&active!=null&&transport!=null&&active.id.equals(transport.id)
                &&(simulation||active.serial.equals(ble.currentTransportSerial()));
        c.linked=same&&ble.linked();c.ready=c.linked&&ble.ready();
        c.generation=ble.connectionGeneration();
        CapabilityResolver.Decision toggle=ble.decision("timed_charging"),time=ble.decision("charge_start");
        // English ids, whatever the shown language: the controller keeps them in its saved status and its other text is English too (plan R2).
        c.toggleReason=toggle.available()?null:ConnectionText.english(toggle.reasonMessage());c.timeReason=time.available()?null:ConnectionText.english(time.reasonMessage());
        c.wholeHours=time.layer!=ModelRegistry.Layer.UT5_MAX;
        if(!c.linked)return c;
        // The switch is read with the status: only while that status is live by the readings' rule, never from before a sleep.
        c.enabled=simulation||ble.statusAge()<=ble.readingFreshMs(10_000)?ble.featureValue("timed_charging"):null;c.statusStale=!simulation&&ble.statusAge()>ble.readingFreshMs(10_000);
        long mono=SystemClock.elapsedRealtime();
        Map<String,Telemetry.Metric> readings=new HashMap<>();
        for(Telemetry.Metric metric:ble.telemetry.metrics())readings.put(metric.key,metric);
        Telemetry.Metric charging=readings.get("charging_state");
        Double chargingValue=freshScheduleNumber(charging,mono);
        c.charging=chargingValue!=null&&(chargingValue==0||chargingValue==1)?chargingValue.intValue():null;
        c.sampleMs=charging==null?0:charging.updatedMs;
        c.soc=freshScheduleNumber(readings.get("battery_percent"),mono);
        c.target=freshScheduleNumber(readings.get("charge_limit_percent"),mono);
        // Settings live (#418): the status, and the limit reading itself, unless this connection has reported no limit (the
        // readings are cleared at each connect). A recent status that did not carry the limit never vouches for it.
        Telemetry.Metric limit=readings.get("charge_limit_percent");
        c.settingsLive=simulation||ble.statusAge()<=ble.readingFreshMs(10_000)&&(c.target!=null||limit==null||limit.updatedMs<=0);
        Double raw=freshScheduleNumber(readings.get("scheduled_charge_time_raw"),mono);
        if(raw!=null&&raw>=0&&raw<=0xffffffffL)c.wireStart=raw.longValue();
        if(Integer.valueOf(1).equals(c.charging)){
            ChargingEstimate.Result estimate=chargingEstimate(displayReadings());
            // Use the planning estimate: the displayed one, or a longer personal time while the
            // own taper sets the display. ChargeScheduleController adds the safety buffer once.
            c.estimateMinutes=estimate.planningMinutesToLimit;
            // A raw current-rate estimate remains useful on the dashboard while automatic
            // identification gathers its first frames, but must not freeze a finish plan yet.
            // The model the estimate itself follows (modelDescriptor: the simulated one while simulating).
            if(ModelRegistry.chargeReferenceProduct(modelDescriptor(active))==Ut5MaxStockChargeReference.PRODUCT_ID
                    &&effectiveChargeCharger()==null)
                c.estimateUnavailableReason=T.serviceChargerDetection();
        }
        return c;
    }
    private Double freshScheduleNumber(Telemetry.Metric metric,long now){
        return metric!=null&&metric.numericValue!=null&&Double.isFinite(metric.numericValue)
                &&metric.updatedMs>0&&now>=metric.updatedMs&&now-metric.updatedMs<=ble.readingFreshMs(10_000)?metric.numericValue:null;
    }

    // ---- Report diagnostics: charge evidence (ChargeEvidence). Typed values only; it never sends or changes anything. ----

    private void startEvidence(){
        try{
            evidence=new ChargeEvidence(new File(getNoBackupFilesDir(),"charge-evidence"),ble::commandEvents,System::currentTimeMillis,SystemClock::elapsedRealtime);
            List<String> saved=new ArrayList<>();for(ScooterProfiles.Profile p:profiles.all())saved.add(p.id);
            // Files of scooters forgotten while the service was not running go now. Every saved scooter's file loads on the
            // worker, so a report shows whichever is selected; each one's schedule record (as saved, possibly by an older
            // Volt) is handed over too, so a plan armed before this file existed has its intention and start window.
            final SharedPreferences records=settings;
            evidence.open(saved,id->records.getString("charge_schedule_owned_"+id,null));
        }catch(RuntimeException unavailable){evidence=null;}
    }
    /** The controller's journal, for saved scooters only: a simulated scope never reaches the evidence files. */
    private void attachEvidence(ChargeScheduleController controller){
        final ChargeEvidence e=evidence;if(e==null)return;
        controller.journal(new ChargeScheduleController.Journal(){
            public void saved(String scope,ChargeScheduleController.Diagnostics record){if(ChargeEvidence.scope(scope))e.saved(scope,record);}
            public void operation(String scope,ChargeScheduleController.Op op,ChargeScheduleController.Result result,ChargeScheduleController.Interruption why,
                    long durationMs,long testWallMs,ChargeScheduleTest.Outcome testOutcome){if(ChargeEvidence.scope(scope))e.operation(scope,op,result,why,durationMs,testWallMs,testOutcome);}
        });
    }
    private void measureOp(ChargeScheduleController.Op op){
        String scope=chargeMeasure.active()?chargeMeasure.profile():activeProfileId();
        if(evidence!=null&&!simulating()&&ChargeEvidence.scope(scope))evidence.operation(scope,op,ChargeScheduleController.Result.OK,null,0,0,null);
    }
    /**
     * One typed sample per Bluetooth change, from the real authenticated link to the selected scooter only. Each value is a
     * fresh reading of this connection (newer than its start and within the freshness the schedule uses), else unknown.
     */
    private void observeEvidence(){
        if(evidence==null||profiles==null||ble==null)return;
        ScooterProfiles.Profile active=profiles.active(),transport=ble.profile();
        boolean live=!destroyed&&!dataMaintenance&&!simulating()&&ble.linked()&&active!=null&&transport!=null&&active.id.equals(transport.id)
                &&active.serial.equals(ble.currentTransportSerial());
        String scope=live?active.id:null;
        // A scooter chosen while offline (or added since the start) loads ahead of a report.
        String selected=active==null?null:active.id;
        if(selected!=null&&!selected.equals(evidenceSelected)){evidenceSelected=selected;evidence.prefetch(selected);}
        if(evidenceScope!=null&&!evidenceScope.equals(scope))evidence.unlinked(evidenceScope);
        evidenceScope=scope;
        if(scope==null)return;
        long mono=SystemClock.elapsedRealtime(),since=ble.connectionStartMs(),fresh=ble.readingFreshMs(10_000);
        long[] s=new long[ChargeEvidence.S_COLUMNS];Arrays.fill(s,ChargeEvidence.NONE);
        s[ChargeEvidence.S_WALL]=System.currentTimeMillis();s[ChargeEvidence.S_ELAPSED]=mono;s[ChargeEvidence.S_GENERATION]=ble.connectionGeneration();
        for(Telemetry.Metric m:ble.telemetry.metrics()){
            if(m.numericValue==null||!Double.isFinite(m.numericValue)||m.updatedMs<=0||m.updatedMs<since||mono<m.updatedMs||mono-m.updatedMs>fresh)continue;
            double v=m.numericValue;
            switch(m.key){
                case "charging_state":if(v==0||v==1)s[ChargeEvidence.S_CHARGING]=(long)v;break;
                case "battery_percent":s[ChargeEvidence.S_SOC]=Math.round(v);break;
                case "voltage_v":s[ChargeEvidence.S_MV]=Math.round(v*1000);break;
                case "current_a":s[ChargeEvidence.S_MA]=Math.round(v*1000);break;
                case "power_w":s[ChargeEvidence.S_W]=Math.round(v);break;
                case "temperature_c":s[ChargeEvidence.S_TEMP]=Math.round(v);break;
                case "charge_limit_percent":s[ChargeEvidence.S_LIMIT]=Math.round(v);break;
                case "scheduled_charge_time_raw":if(v>=0&&v<=0xffffffffL&&v==Math.rint(v))s[ChargeEvidence.S_WIRE]=(long)v;break;
                default:break;
            }
        }
        // The schedule switch and the lock come from the status frame: only while it is fresh on this connection.
        if(ble.statusAge()<=fresh){
            Integer on=ble.featureValue("timed_charging"),lock=ble.featureValue("lock");
            if(on!=null&&(on==0||on==1))s[ChargeEvidence.S_ENABLED]=on;
            if(lock!=null)s[ChargeEvidence.S_LOCK]=lock!=0?1:0;
        }
        s[ChargeEvidence.S_VISIBLE]=visible?1:0;
        evidence.offer(scope,s,ble.pending(),ble.pendingValue());
    }
    /** Forgetting a scooter removes its evidence (MoreScreen's forget flow). */
    void forgetEvidence(String profileId){if(profileId!=null&&profileId.equals(evidenceScope))evidenceScope=null;if(evidence!=null)evidence.forget(profileId);}
    /** At service end: the last reading closes the connection, and pending writes go to disk on the worker, without waiting here. */
    private void closeEvidence(){
        if(evidence==null)return;
        if(evidenceScope!=null){evidence.unlinked(evidenceScope);evidenceScope=null;}
        evidence.close();
    }

    private void resetChargeEstimateContext(){
        chargeReferenceGate.reset();
        chargerDetection.reset();
        chargeProgress.reset();
        chargeTaper.reset();
    }

    /**
     * Observe real electrical frames once per update, even when no widget is open. A disconnect
     * or unusable frame leaves the remembered family to expire by age; renders only read it.
     * A lost link is noted so a near-full reconnect cannot keep a possibly swapped charger.
     */
    private void updateChargerDetection(){
        ScooterProfiles.Profile profile=profiles==null?null:profiles.active();
        if(destroyed||simulating()||profile==null){chargerDetection.reset();chargeProgress.reset();chargeTaper.reset();return;}
        if(ble!=null&&ble.currentTransportSerial().isEmpty())chargerDetection.disconnected();
        if(ble==null||!ble.ready()||ble.profile()==null||!profile.id.equals(ble.profile().id))return;
        long now=SystemClock.elapsedRealtime();
        ChargeSessionStore.Frame frame=chargeFrame(now,System.currentTimeMillis());
        if(!liveCharge(frame.state,"charging_state",now))return;
        boolean paired=liveCharge(frame.soc,"battery_percent",now)&&liveCharge(frame.voltage,"voltage_v",now)
                &&liveCharge(frame.current,"current_a",now)&&frame.voltage.mono==frame.current.mono;
        // Never reached while simulating (above); the same model reader as the estimate all the same.
        chargerDetection.observe(profile.id,ModelRegistry.chargeReferenceProduct(modelDescriptor(profile)),frame.state.value==1d,paired?frame.soc.value:null,
                paired?frame.voltage.value:null,paired?frame.current.value:null,paired?frame.current.mono:0,now);
        chargeProgress.observe(profile.id,frame.state.value==1d,paired?frame.soc.value:null,paired?frame.current.value:null,now);
        chargeTaper.observe(profile.id,frame.state.value==1d,paired?frame.voltage.value:null,paired?frame.current.value:null,now);
    }
    private static boolean liveCharge(ChargeSessionStore.Value value,String key,long now){
        return value!=null&&value.value!=null&&Double.isFinite(value.value)&&value.mono>0&&now>=value.mono
                &&now-value.mono<=TelemetryHistory.freshnessMs(key);
    }

    /**
     * Losing readiness or charging freshness invalidates grace even when no widget is open. linked(), so a background
     * link between its 15 s status reads keeps the gate: the live card's time left must not switch estimates every poll.
     */
    private boolean canRetainStockReference(){
        if(destroyed||simulating()||ble==null||!ble.linked()||profiles.active()==null||ble.profile()==null
                ||!profiles.active().id.equals(ble.profile().id))return false;
        long now=SystemClock.elapsedRealtime();
        Map<String,Telemetry.Metric> values=new HashMap<>();
        for(Telemetry.Metric metric:ble.telemetry.metrics())values.put(metric.key,metric);
        for(String key:new String[]{"charging_state","battery_percent","voltage_v","current_a","rated_capacity_ah"}){
            Telemetry.Metric metric=values.get(key);
            if(metric==null||metric.numericValue==null||!Double.isFinite(metric.numericValue)||metric.updatedMs<=0
                    ||now<metric.updatedMs||now-metric.updatedMs>ble.readingFreshMs(TelemetryHistory.freshnessMs(key)))return false;
        }
        return values.get("charging_state").numericValue==1d
                &&values.get("voltage_v").updatedMs==values.get("current_a").updatedMs;
    }

    public boolean isRecording(){return rides!=null&&rides.activeId()!=null;}
    public String recordingError(){return recordingError;}
    public boolean phoneSaving(){return phoneSavingCount>0;}
    public String phoneStatus(){
        return phoneSaving()
                ? "Saving phone sensors"
                : phoneRecorder == null
                        ? (phoneFinalStatus != null
                                ? phoneFinalStatus
                                : phoneRides == null
                                        ? "Phone sensor storage unavailable"
                                        : "Phone sensors idle")
                        : phoneRecorder.status();
    }
    /** phoneStatus() as flags, for a screen to label in any language; phoneStatus() stays the English id. */
    public EnumSet<PhoneRideRecorder.StatusFlag> phoneStatusFlags(){
        if(phoneSaving())return EnumSet.of(PhoneRideRecorder.StatusFlag.SAVING);
        if(phoneRecorder!=null)return phoneRecorder.statusFlags();
        if(phoneFinalStatus!=null&&phoneFinalFlags!=null)return EnumSet.copyOf(phoneFinalFlags);
        return EnumSet.of(phoneRides==null?PhoneRideRecorder.StatusFlag.STORAGE_ERROR:PhoneRideRecorder.StatusFlag.IDLE);
    }
    /**
     * Riding mode's GPS accuracy reading now (nowMono: SystemClock.elapsedRealtime). It only reads the phone recorder,
     * so it never starts GPS or keeps GPS or the screen awake; with no phone recorder (no recording, simulation, phone
     * sensors unavailable) GPS reads as off.
     */
    public GpsAccuracy.Reading gpsAccuracy(long nowMono){
        PhoneRideRecorder recorder=phoneRecorder;
        return GpsAccuracy.reading(isRecording(),recorder==null?GpsAccuracy.Phase.OFF:recorder.gpsAccuracyPhase(),recorder==null?null:recorder.latestFix(),nowMono);
    }
    public String phoneLiveReadings(){return phoneRecorder==null?"":phoneRecorder.liveMotion();}
    public boolean isVisible(){return visible;}
    public long backgroundDeadlineMs(){return backgroundDeadlineMs;}
    public boolean workAllowed(){
        if (destroyed || DataMaintenance.active()) return false;
        if (simulating()) return visible;
        return reasons().keepsServiceUp(visible, backgroundConnection())
                || rideAutoExportBusy()
                || backgroundDeadlineMs > SystemClock.elapsedRealtime();
    }
    public void visible(boolean value){setUiVisible(value);if(running!=this)applyVisibility(value);}
    private void applyVisibility(boolean value){applyVisibility(value,true);}
    private void applyVisibility(boolean value,boolean connectOnShow){
        if(destroyed)return;boolean wasVisible=visible;visible=value;
        // Opening Volt ends the live card: keep Android's verdict on it for the developer row first.
        if(value&&chargeLiveHeld)captureLiveUpdate();
        if(!value&&wasVisible)armChargeLive();else if(value)chargeLiveOpened();
        if(value)cancelBackgroundDeadline();
        // Opening Volt stops a sounding alarm and lets a paused or timed-out charge watch hold again.
        if(value&&!wasVisible){stopChargeAlarm();if(alertPolicy!=null)alertPolicy.resume();cancelAlertPausedNotice();}
        else if(wasVisible){
            // Leaving with the live charging hold armed counts too.
            if(!reasons().skipsTenSecondStop(!value,backgroundConnection())){
                backgroundDeadlineMs=SystemClock.elapsedRealtime()+10000;handler.removeCallbacks(backgroundStop);handler.postDelayed(backgroundStop,10000);
            }else cancelBackgroundDeadline();
        }
        applyWorkPolicy(connectOnShow&&value&&!wasVisible);
    }
    private void cancelBackgroundDeadline(){backgroundDeadlineMs=0;handler.removeCallbacks(backgroundStop);}

    private final Runnable backgroundStop =
            new Runnable() {
                @Override
                public void run() {
                    if (destroyed) return;
                    if (reasons().keepsServiceUp(visible, backgroundConnection(), simulating())) {
                        cancelBackgroundDeadline();
                        return;
                    }
                    if (backgroundDeadlineMs == 0) return;
                    long remaining = backgroundDeadlineMs - SystemClock.elapsedRealtime();
                    if (remaining > 0) {
                        handler.postDelayed(this, remaining);
                        return;
                    }
                    // An automatic ride copy finishes first; the deadline itself is neither moved nor cancelled.
                    if (rideAutoExportBusy()) {
                        handler.postDelayed(this, 1000);
                        return;
                    }
                    stopIdleWork();
                }
            };

    private void applyWorkPolicy(boolean connect){
        if(destroyed)return;
        if (simulating()) {
            // Out of sight a simulation has no work, so the service stops as an idle one does: the usual ten seconds
            // (an activity recreation unbinds and binds again well inside them), then backgroundStop's stopSelf. While
            // the activity lives its binding keeps the service, and the simulation, until Volt is back.
            if (visible) cancelBackgroundDeadline();
            else if (backgroundDeadlineMs == 0) {
                backgroundDeadlineMs = SystemClock.elapsedRealtime() + 10000; handler.removeCallbacks(backgroundStop); handler.postDelayed(backgroundStop, 10000);
            }
            ble.watchBattery(0);
            ble.visibility(visible, false, 0);
            handler.removeCallbacks(tick);
            workActive = visible;
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            // A visual preview has no reason to keep a foreground service or sensors alive.
            notices.cancel(71); stopForeground(STOP_FOREGROUND_REMOVE); foregroundStarted = false;
            if (visible) handler.post(tick);
            changed();
            return;
        }
        if (reasons().keepsServiceUp(visible, backgroundConnection())) cancelBackgroundDeadline();
        // A charge alert's or live charging's hold keeps the link and battery reads without the recording flag BLE gives rides and charge recordings.
        ble.watchBattery(batteryWatchUntil());
        ble.visibility(visible,isRecording()||isChargeRecording()||holding(),backgroundDeadlineMs);
        if(!workAllowed()){stopIdleWork();return;}
        workActive=true;startForegroundNotice(false);updateRecordingWakeLock();if(isRecording()&&phoneRecorder==null)startPhoneRecording();handler.removeCallbacks(tick);handler.post(tick);
        if (isRecording() && !ble.connected()) ble.connectLast();
        else if (connect
                && (visible || backgroundConnection())
                && settings.getBoolean("auto_connect", true)
                && ble.state() == BleController.State.DISCONNECTED) ble.connectLast();
        changed();
    }
    private void stopIdleWork(){
        workActive=false;cancelBackgroundDeadline();handler.removeCallbacks(tick);ble.watchBattery(0);ble.visibility(visible,isRecording()||isChargeRecording()||holding(),0);ble.suspend();
        if(wakeLock!=null&&wakeLock.isHeld()&&!isRecording()&&!isChargeRecording()&&!phoneSaving())wakeLock.release();
        notices.cancel(71);stopForeground(STOP_FOREGROUND_REMOVE);foregroundStarted=false;stopSelf();
    }
    public void startRide(String label){if(destroyed)return;
        // A ride started by hand ends an automatic charge recording (and holds it, as a Stop would), but only when the ride
        // can then start: otherwise the charge keeps recording and nothing changes. A manual one still refuses.
        if(isChargeRecording()&&ChargeAutoRecordPolicy.rideEndsRecording(chargeStore.automatic())
                &&!simulating()&&!phoneSaving()&&rides!=null&&ble.ready()&&!isRecording())stopChargeRecording();
        if(isChargeRecording()){android.widget.Toast.makeText(this,T.serviceRideChargeRecording(),android.widget.Toast.LENGTH_SHORT).show();return;}if(simulating()) {
            android.widget.Toast.makeText(this, T.serviceRideExitSimulation(), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }if(phoneSaving()){
            android.widget.Toast.makeText(
                            this, T.serviceRideFinishing(), android.widget.Toast.LENGTH_SHORT)
                    .show();
            return;}if(rides==null||!ble.ready()||isRecording())return;try{beginRide(label);
            // Re-evaluate START_STICKY after the user starts a ride, rather than retaining
            // the idle service's earlier START_NOT_STICKY result.
            startService(new Intent(this,ScooterService.class).setAction("RECORDING_STARTED"));ble.refresh(false);}catch(IOException|IllegalStateException e){android.widget.Toast.makeText(this,e.getMessage(),android.widget.Toast.LENGTH_LONG).show();}}
    /** Starts the recording: startRide and auto-record both come through here, the caller having done every check. */
    private void beginRide(String label)throws IOException{rides.setCurrentDirection(RideStore.CurrentDirection.POSITIVE_IS_DRAW);
            rides.start(profiles.active().id,label,System.currentTimeMillis(),SystemClock.elapsedRealtime(),TimeZone.getDefault().getID());invalidateCapacityEstimate();recordingError=null;gapMarked=false;samples=0;rideAutoStop=null;cancelAutoStopNotice();applyWorkPolicy(false);}

    public void stopRide(){stopRide("");}
    /**
     * How many recordings have ended by themselves. A count rather than a time, so it means the same
     * across a reboot or a clock change: a riding mode that remembers a lower count is over.
     * RideAutoEnds keeps it recoverable together with the journal's STOP row.
     */
    public long rideAutoEnds(){return autoEnds==null?0:autoEnds.count();}
    /** Every way this process's links have ended. Static, so a new service in the same process carries it on. */
    private static final RidingLink.Log LINK_LOG=new RidingLink.Log(new java.util.Random().nextLong());
    /**
     * What riding mode compares across a leave. Observed here too, so a service destroyed with the link up reads
     * as a loss on the next look: only Volt's own pause ends a link without counting as one.
     */
    public RidingLink.Now rideLink(){observeLink();return LINK_LOG.now();}
    private void observeLink(){LINK_LOG.observe(ble.connected(),ble.ownPause());}
    /** Fresh motion from the real scooter: never a simulated, stale or absent reading. */
    private boolean autoStartMoving(long mono){return !simulating()&&AutoStartPolicy.moving(ble.connected(),ble.speedRaw(),ble.motionSampleMs(),mono);}
    /**
     * Auto-record, on every Bluetooth change and service tick, in the background too (where it starts a ride only with
     * the background switch on; AutoStartPolicy.recordsNow). It starts a ride as startRide does
     * but says nothing; one that fails is held until the next connection, not retried on the next speed sample.
     */
    private void observeAutoStart(long mono){
        autoStart.observeLink(ble.connected()&&!simulating(),ble.connectionGeneration());
        if(dataMaintenance||rides==null)return;
        ScooterProfiles.Profile active=profiles.active(),bound=ble.profile();
        // linked(), not ready(): out of sight the status poll runs every 15 s, so a healthy authenticated link is past
        // ready()'s 7 s window for half of each cycle. The speed sample's own freshness is checked by autoStartMoving.
        boolean canStart=ble.linked()&&active!=null&&bound!=null&&active.id.equals(bound.id);
        boolean enabled=AutoStartPolicy.recordsNow(settings.getBoolean(AutoStartPolicy.AUTO_RECORD_KEY,false),settings.getBoolean(AutoStartPolicy.AUTO_RECORD_BACKGROUND_KEY,false),visible);
        if(!autoStart.shouldRecord(enabled,autoStartMoving(mono),isRecording(),isChargeRecording(),simulating(),phoneSaving(),canStart))return;
        try{
            beginRide(RidesScreen.defaultRideName(this));
            // Any action but a stop re-evaluates START_STICKY, like the rider's start.
            startService(new Intent(this,ScooterService.class).setAction("auto_record_started"));ble.refresh(false);
        }catch(IOException|IllegalStateException ignored){/* said nothing, on purpose; held below */}
        if(!isRecording())autoStart.recordingStartFailed();
    }
    /** Recording, or fresh motion from the real scooter: no moment for a location prompt. */
    boolean ridingNow(){return isRecording()||autoStartMoving(SystemClock.elapsedRealtime());}
    /** Auto-record's location offer when the permission is missing and the rider hasn't declined, for the activity to show while it is on screen. */
    boolean offersAutoRecordLocation(boolean screenClear){
        if(destroyed||dataMaintenance)return false;
        boolean location=PhoneRideRecorder.hasLocationPermission(this);
        updateLocationDecline(false,location); // A grant made in Android settings ends a decline too.
        return AutoStartPolicy.offersLocationOnScreen(settings.getBoolean(AutoStartPolicy.AUTO_RECORD_KEY,false),settings.getBoolean(AutoStartPolicy.PHONE_GPS_KEY,true),location,settings.getBoolean(AutoStartPolicy.LOCATION_ASKED_KEY,false),simulating(),ridingNow(),screenClear);
    }
    /** A location request made for auto-record has finished. answered is false when Android cancelled it with no answer. */
    void autoRecordLocationResult(boolean answered){updateLocationDecline(answered,PhoneRideRecorder.hasLocationPermission(this));}
    private void updateLocationDecline(boolean answered,boolean location){
        boolean note=settings.getBoolean(AutoStartPolicy.LOCATION_ASKED_KEY,false),next=AutoStartPolicy.declineNote(note,answered,location);
        if(next!=note)settings.edit().putBoolean(AutoStartPolicy.LOCATION_ASKED_KEY,next).apply();
    }
    /** Auto ride mode's decision for the activity, which knows what is on screen; a true answer counts as this trigger's open. */
    boolean takeAutoRideMode(boolean screenClear){
        if(destroyed||!autoStart.shouldOpenRidingMode(settings.getBoolean(AutoStartPolicy.AUTO_RIDE_MODE_KEY,false),autoStartMoving(SystemClock.elapsedRealtime()),screenClear))return false;
        autoStart.rideModeOpened();return true;
    }
    /** The one stop/save path: rider, notification and automatic stops share error handling, sensor drain and wake-lock release. */
    private boolean stopRide(String reason){if(!isRecording())return false;rideAutoStop=null;
        // An empty reason is the rider's own stop, the only one that holds auto-record. Set before this stop's change
        // notice, which would otherwise start a new recording while the scooter is still moving.
        if(reason.isEmpty())autoStart.riderStoppedRecording();
        // rides.stop clears the active id: keep it for the automatic copy.
        String stopping=rides.activeId();
        try{rides.stop(System.currentTimeMillis(),SystemClock.elapsedRealtime(),reason);invalidateCapacityEstimate();recordingError=null;}catch(IOException e){
            recordingError =
                    "Storage full · free space, then Retry save";
            android.widget.Toast.makeText(this,recordingError,android.widget.Toast.LENGTH_LONG).show();updateNotification();changed();return false;}
        // Saved: the ride waits for its automatic copy until its phone journal is complete (rideFullySaved).
        RideAutoExportFolder.queue(settings,stopping);
        if(rides.lastStopAutomatic())autoEnds.stopped(stopping);
        stopPhoneRecording();if(wakeLock.isHeld()&&!phoneSaving())wakeLock.release();applyWorkPolicy(false);changed();return true;}
    /**
     * Feeds the forgotten-ride policy from the current authenticated transport, never from cached
     * readings. Runs on every BLE change (so a loss is seen as it happens) and every service tick,
     * screen on or off. Charges have their own recovery policy and never pass through here.
     */
    private void observeRideAutoStop(long mono){
        if(destroyed||simulating()||!isRecording()){rideAutoStop=null;return;}
        String id=rides.activeId();
        if(rideAutoStop==null||!rideAutoStop.owns(id)){
            RideStore.Summary ride=rides.activeSummary();ScooterProfiles.Profile active=profiles.active(),bound=ble.profile();
            String serial=ride!=null&&active!=null&&active.id.equals(ride.scooterId)?active.serial:"";
            if((serial==null||serial.isEmpty())&&ride!=null&&bound!=null&&bound.id.equals(ride.scooterId))serial=ble.currentTransportSerial();
            if(ride==null||serial==null||serial.isEmpty()||ride.scooterId.isEmpty()){rideAutoStop=null;return;}
            rideAutoStop=new RideAutoStopPolicy(id,ride.scooterId,serial);rideAutoStopDisconnects=ble.intentionalDisconnectSequence();
        }
        if(!settings.getBoolean(RIDE_AUTO_STOP,true)||recordingError!=null||!rideAutoStop.profile.equals(activeProfileId())
                ||ble.intentionalDisconnectSequence()!=rideAutoStopDisconnects){
            rideAutoStop.reset();rideAutoStopDisconnects=ble.intentionalDisconnectSequence();return;
        }
        if(ble.connected()){ScooterProfiles.Profile bound=ble.profile();
            rideAutoStop.connected(mono,bound==null?"":bound.id,ble.currentTransportSerial(),ble.connectionGeneration(),ble.lastFrameMs(),ble.motionSampleMs(),ble.speedRaw(),ble.drivingStatus());}
        else rideAutoStop.disconnected(mono,RideAutoStopPolicy.loss(ble.permissions(),ble.bluetoothEnabled(),ble.recoverableConnectionLoss(),ConnectionEventLog.adapterOffLast(ble.diagnosticEvents())));
        // Rule 3 also counts the odometer or trip going up as motion.
        if(ble.connected())rideAutoStop.distance(mono,rideDistance(RideStore.ODOMETER_KEY,mono),rideDistance(RideStore.TRIP_KEY,mono));
    }
    /** The transport's fresh odometer or trip reading in km for rule 3, NaN when missing or stale. */
    private double rideDistance(String key,long mono){
        Telemetry.Metric m=null;for(Telemetry.Metric metric:ble.telemetry.metrics())if(metric.key.equals(key)){m=metric;break;}
        if(m==null||m.numericValue==null||m.updatedMs<=0||mono<m.updatedMs||mono-m.updatedMs>RideStore.MAX_SENSOR_AGE_MS)return Double.NaN;
        return m.numericValue;
    }
    /**
     * Called from the tick only, after the other rules: ends a ride whose connected scooter showed no
     * motion for 10 minutes (rule 3). The policy saw this tick's connected observation, so a link lost
     * since then has already stopped its countdown. Volt sends the scooter nothing.
     */
    private void autoStopNoMotion(RideAutoStopPolicy policy,long now){
        if(policy==null||!policy.dueNoMotion(now))return;
        if(destroyed||policy!=rideAutoStop||!isRecording()||!policy.owns(rides.activeId()))return;
        String id=policy.rideId;
        if(stopRide(RideStore.STOP_AUTO_NO_MOTION)){
            if(visible)android.widget.Toast.makeText(this,T.serviceRideSavedAuto(),android.widget.Toast.LENGTH_SHORT).show();
            showAutoStopNotice(id,RideStore.STOP_AUTO_NO_MOTION);
        }
    }
    /** Called from the tick only. The policy instance and ride id must both still be current. */
    private void autoStopRide(RideAutoStopPolicy policy){autoStopRide(policy,RideStore.STOP_AUTO_PARKED);}
    /**
     * Called from the tick only, after the parked check: ends the ride once the went-quiet countdown
     * runs out (the scooter switched off or out of range, or the phone's Bluetooth off).
     */
    private void autoStopQuiet(RideAutoStopPolicy policy,long now){
        if(policy==null)return;
        boolean phoneOff=policy.quiet()==RideAutoStopPolicy.Quiet.PHONE_BLUETOOTH_OFF;
        long since=policy.quiet()==RideAutoStopPolicy.Quiet.RESTART?0:policy.quietSince();
        if(!policy.dueQuiet(now))return;
        autoStopRide(policy,RideStore.quietStopReason(ConnectionEventLog.firstLossCode(ble.diagnosticEvents(),since),phoneOff));
    }
    /** Ends the ride through the same stop path as the rider's Stop, so the save and its follow-ups match. */
    private void autoStopRide(RideAutoStopPolicy policy,String reason){
        if(destroyed||policy==null||policy!=rideAutoStop||!isRecording()||!policy.owns(rides.activeId())||ble.connected())return;
        String id=policy.rideId;
        if(stopRide(reason)){
            if(visible)android.widget.Toast.makeText(this,T.serviceRideSavedAuto(),android.widget.Toast.LENGTH_SHORT).show();
            showAutoStopNotice(id,reason);
        }
    }
    /** Separate from the foreground notice, which Android removes once nothing is recording. */
    private void showAutoStopNotice(String id){showAutoStopNotice(id,RideStore.STOP_AUTO_PARKED);}
    private void showAutoStopNotice(String id,String reason){
        try{
            notification();// Ensures the channel exists even if the foreground notice never posted.
            PendingIntent open=PendingIntent.getActivity(this,2,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            RideStore.Summary ride=null;try{ride=rides.get(id);}catch(IOException ignored){}
            String text=RideAutoStopText.notice(reason,ride,ride==null?null:DisplayTime.clock(this,settings,ride.shownEndWallMs));
            post(AUTO_STOP_NOTICE,new Notification.Builder(this,"scooter").setSmallIcon(R.drawable.ic_volt_notification)
                    .setContentTitle(T.serviceRideSavedAuto()).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE).build());
        }catch(RuntimeException unavailable){}
    }
    private void cancelAutoStopNotice(){cancelNotice(AUTO_STOP_NOTICE);}
    /**
     * A stopped ride's files are complete: its phone journal finished saving (stopPhoneRecording's completion),
     * or it had no phone recorder (then synchronously, from stopRide, before its applyWorkPolicy). Main thread.
     * A destroyed service skips it; the ride is still in the stored waiting list, so the next start retries it.
     */
    private void rideFullySaved(){if(destroyed)return;startRideAutoExport();}
    /** The keep-alive for an automatic ride copy: like phoneSaving(), and separate from the idle timeout. */
    private boolean rideAutoExportBusy(){return rideAutoExport!=null&&rideAutoExport.busy();}
    /** The rider chose the folder again (RideAutoExportPickerActivity): retry what is waiting. Main thread. */
    void rideAutoExportFolderChosen(){startRideAutoExport();}
    /**
     * Starts a pass over the waiting rides. Never during backup or restore, and not while a phone journal is
     * still saving: its completion (rideFullySaved) calls here again.
     */
    private void startRideAutoExport(){
        if(destroyed||dataMaintenance||DataMaintenance.active()||rideAutoExport==null||phoneSaving())return;
        rideAutoExport.start(rides,phoneRides);
    }
    /** Main thread, after each pass. A failure is the stored id; the saved ride is never touched. */
    private void rideAutoExportFinished(RideAutoExport.Problem before,RideAutoExport.Problem after,boolean again){
        if(destroyed)return;
        if(again)startRideAutoExport();
        // A success is silent and clears an earlier failure notice. A failure shows on the settings row from the stored
        // id; while Volt is hidden it also posts once per failure kind, until the kind changes (also while Volt is open)
        // or a copy succeeds. The kind posted is tracked, not the kind stored: a failure first seen while Volt was open
        // still posts at the next hidden failure of the same kind (RideAutoExport.notifies and notifiedAfter).
        boolean post=RideAutoExport.notifies(before,after,autoExportNotified,visible);
        autoExportNotified=RideAutoExport.notifiedAfter(before,after,autoExportNotified,visible);
        if(after==RideAutoExport.Problem.NONE)cancelNotice(AUTO_EXPORT_NOTICE);
        else if(post)showAutoExportNotice(after);
        applyWorkPolicy(false);
        changed();
    }
    /** Rendered at the post, on the main thread, from the failure id. */
    private void showAutoExportNotice(RideAutoExport.Problem problem){
        try{
            notification();// Ensures the channel exists even if the foreground notice never posted.
            PendingIntent open=PendingIntent.getActivity(this,10,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            String text=problem==RideAutoExport.Problem.FOLDER?T.serviceAutoSaveFolder():T.serviceAutoSaveWrite();
            post(AUTO_EXPORT_NOTICE,new Notification.Builder(this,"scooter").setSmallIcon(R.drawable.ic_volt_notification)
                    .setContentTitle(T.serviceAutoSaveFailed()).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_STATUS).setVisibility(Notification.VISIBILITY_PRIVATE).build());
        }catch(RuntimeException unavailable){}
    }
    private void startPhoneRecording(){
        if (simulating()) return;
        if(phoneRides==null||!isRecording()||phoneRecorder!=null)return;
        boolean gps =
                settings.getBoolean("phone_gps", true)
                        && visible
                        && PhoneRideRecorder.hasLocationPermission(this);
        if(gps){phoneLocationForeground=true;try{startForegroundNotice(false);}catch(SecurityException|IllegalStateException e){phoneLocationForeground=false;gps=false;startForegroundNotice(false);}}
        phoneFinalStatus=null;phoneRecorder=new PhoneRideRecorder(this,phoneRides);
        phoneFinalFlags=null;
        phoneRecorder.start(
                rides.activeId(),
                gps,
                settings.getBoolean("phone_motion", true));
    }
    private void stopPhoneRecording(){if(phoneRecorder!=null){PhoneRideRecorder finishing=phoneRecorder;phoneRecorder=null;phoneSavingCount++;finishing.stop(()->handler.post(()->{phoneSavingCount=Math.max(0,phoneSavingCount-1);phoneFinalStatus=finishing.status();phoneFinalFlags=finishing.statusFlags();if(!destroyed){rideFullySaved();if(!isRecording()&&!phoneSaving()&&wakeLock.isHeld())wakeLock.release();applyWorkPolicy(false);changed();}}));}
        // No phone recorder (simulation, no phone storage): the ride's files are already complete. Start the copy
        // now, not posted: its busy() hold must exist before stopRide's applyWorkPolicy, or a hidden idle service
        // drops the foreground and stops itself before the copy starts.
        else rideFullySaved();
        phoneLocationForeground=false;}
    private void recordPhoneTelemetry(long now){
        if (simulating()) return;
        if(phoneRecorder==null)return;Telemetry.Metric speedMetric=null,volts=null,amps=null,battery=null;
        if(ble.connected())for(Telemetry.Metric m:ble.telemetry.metrics()){
            if(m.numericValue==null||m.updatedMs<0||now<m.updatedMs||now-m.updatedMs>3000)continue;
                if (m.key.equals("speed_kmh")) speedMetric = m;
                else if (m.key.equals("voltage_v")) volts = m;
                else if (m.key.equals("current_a")) amps = m;
                else if (m.key.equals("battery_percent")) battery = m;
        }
        boolean pair=volts!=null&&amps!=null&&volts.numericValue>0&&volts.numericValue<=200&&Math.abs(amps.numericValue)<=500&&volts.updatedMs==amps.updatedMs&&volts.sourceCmd==amps.sourceCmd;
        phoneRecorder.telemetry(now,speedMetric==null?null:speedMetric.numericValue,pair?volts.numericValue*amps.numericValue:null,battery==null?null:battery.numericValue,speedMetric==null?-1:speedMetric.updatedMs,pair?volts.updatedMs:-1,battery==null?-1:battery.updatedMs);
    }
    public List<Ui.Point> trend(String key){
        if (simulating()) {
            long wall = System.currentTimeMillis(), mono = SystemClock.elapsedRealtime();
            List<Ui.Point> points = new ArrayList<>();
            for (TelemetryHistory.Point p : simulationHistory(key, wall - 120000, wall))
                points.add(new Ui.Point(p.wallMs - wall + mono, p.value, p.gapBefore));
            return points;
        }
        ArrayDeque<Ui.Point> source =
                key.equals("voltage_v") ? voltage : key.equals("speed_kmh") ? speed : power;
        return new ArrayList<>(source);}

    private final Runnable tick =
            new Runnable() {
                public void run() {
                    if (destroyed || !workActive) return;
                    if (!workAllowed()) {
                        stopIdleWork();
                        return;
                    }
                    if (simulating()) {
                        ble.tickSimulation();
                        followRangeMode();
                        if (visible) changed();
                        if (visible && workActive) handler.postDelayed(this, 1000);
                        return;
                    }
                    followRangeMode();
                    long mono = SystemClock.elapsedRealtime(), wall = System.currentTimeMillis();
                    ScooterProfiles.Profile selected = profiles.active();
                    String selectedId = selected == null ? "" : selected.id;
                    if (!selectedId.equals(trendProfile)) {
                        trendProfile = selectedId;
                        power.clear();
                        voltage.clear();
                        speed.clear();
                    }
                    if (ble.connected()) {
                        addTrend(power, "power_w", mono);
                        addTrend(voltage, "voltage_v", mono);
                        addTrend(speed, "speed_kmh", mono);
                    }
                    updateChargeCapture(mono,wall);
                    updateChargeMeasure(mono);
                    // A test's deadlines pass without any new frame; out of sight nothing else calls observe().
                    if(scheduleTesting()&&!visible)chargeScheduleController.observe();
                    updateRecordingWakeLock();
                    if (isRecording()) {
                        recordPhoneTelemetry(mono);
                        try {
                            if (recordingError == null) {
                                if (!ble.connected()) {
                                    if (!gapMarked) {
                                        rides.markGap(wall, mono, "Bluetooth disconnected");
                                        gapMarked = true;
                                    }
                                    rides.heartbeat(wall, mono);
                                } else {
                                    gapMarked = false;
                                    Map<String, RideStore.Sample> values = new LinkedHashMap<>();
                                    for (Telemetry.Metric m : ble.telemetry.metrics())
                                        values.put(
                                                m.key,
                                                new RideStore.Sample(
                                                        m.numericValue,
                                                        m.value,
                                                        "",
                                                        m.sourceCmd,
                                                        m.updatedMs));
                                    rides.sample(wall, mono, values);
                                    if (++samples % 2 == 0) ble.refresh(false);
                                }
                            }
                        } catch (IOException e) {
                            recordingError =
                                    "Paused: storage full";
                            android.widget.Toast.makeText(
                                            ScooterService.this,
                                            recordingError,
                                            android.widget.Toast.LENGTH_LONG)
                                    .show();
                        }
                    } else if (wakeLock.isHeld() && !keepsWorking()) wakeLock.release();
                    // Deadlines (reconnect window, hold limit) pass without a new frame; out of sight nothing else steps the alerts.
                    updateChargeAlerts();
                    updateChargeLive();
                    // Re-read the clock: a loss seen inside this tick (a failed refresh write) was fed a later time.
                    long autoNow = SystemClock.elapsedRealtime();
                    observeRideAutoStop(autoNow);
                    RideAutoStopPolicy autoStop = rideAutoStop;
                    if (autoStop != null && autoStop.due(autoNow)) autoStopRide(autoStop);
                    else autoStopQuiet(autoStop, autoNow);
                    if (autoStop != null && autoStop == rideAutoStop) autoStopNoMotion(autoStop, autoNow);
                    // The charge watch re-posts only when its whole-number battery level or wording changes.
                    if (mono - lastNotification > 5000 && !alertWatchShown() && !(chargeLiveShown() && !isChargeRecording())) {
                        lastNotification = mono;
                        updateNotification();
                    }
                    if (visible || isRecording() || isChargeRecording()) changed();
                    if (workActive && workAllowed())
                        handler.postDelayed(this, visible || isRecording() || isChargeRecording() || holding() ? 1000 : 15000);
                }
            };

    private void addTrend(ArrayDeque<Ui.Point> values,String key,long now){for(Telemetry.Metric m:ble.telemetry.metrics())if(m.key.equals(key)&&m.numericValue!=null&&m.updatedMs>=0&&now>=m.updatedMs&&now-m.updatedMs<2500){if(values.isEmpty()||values.peekLast().millis<m.updatedMs)values.add(new Ui.Point(m.updatedMs,m.numericValue));break;}while(!values.isEmpty()&&now-values.peek().millis>120000)values.remove();while(values.size()>240)values.remove();}
    private void changed(){if(destroyed)return;if(simulating())ble.setSimulationFastCharger(chargeCharger()==ChargeSessionStore.Charger.HIGH_SPEED);observeLink();if(chargeScheduleController!=null){chargeScheduleController.observe();noticeMissedStart();retireStartSentNotice();}observeEvidence();updateChargerDetection();updateChargeAlerts();updateChargeLive();if(!canRetainStockReference())chargeReferenceGate.reset();updateChargeCapture(SystemClock.elapsedRealtime(),System.currentTimeMillis());observeAutoCharge(SystemClock.elapsedRealtime());observeRideAutoStop(SystemClock.elapsedRealtime());observeAutoStart(SystemClock.elapsedRealtime());if(workPolicyAfterAutoStart){workPolicyAfterAutoStart=false;applyWorkPolicy(false);}observeTelemetry();observeMaintenance();followRangeMode();for(Runnable r:new ArrayList<>(listeners))r.run();}
    public boolean followsRidingMode() {
        return settings.getBoolean("range_follow_mode_" + activeProfileId(), true);
    }
    public void followRidingMode(boolean enabled) {
        if (destroyed) return;
        settings.edit().putBoolean("range_follow_mode_" + activeProfileId(), enabled).apply();
        rangeFollowPolicy.reset();
        changed();
    }
    private void followRangeMode() {
        if(chargeScheduleController!=null&&chargeScheduleController.busy())return;
        // A running compatibility check or guided test holds every automatic write (MutationGate refuses them too).
        if (destroyed || ble == null || selectingRange || ble.discovering() || ble.testing()) return;
        ScooterProfiles.Profile profile = profiles.active();
        Telemetry.Metric mode = null;
        for (Telemetry.Metric metric : ble.telemetry.metrics()) if (metric.key.equals("driving_mode")) { mode = metric; break; }
        long now = SystemClock.elapsedRealtime();
        ScooterProfiles.Profile bound = ble.profile();
        boolean sameProfile = profile != null && bound != null && profile.id.equals(bound.id);
        boolean fresh = sameProfile && mode != null && mode.numericValue != null && mode.updatedMs > 0
                && now >= mode.updatedMs && now - mode.updatedMs <= 7000 && ble.statusAge() <= 7000;
        String attempts = profile == null ? "" : simulating() ? RangeFollowPolicy.simulationKey(profile.id) : profile.id;
        Integer target = rangeFollowPolicy.next(attempts,
                ModelRegistry.rangeFollowProduct(modelDescriptor(profile)), mode == null ? null : mode.numericValue,
                ble.featureChoices("range_algorithm"), ble.featureValue("range_algorithm"),
                ble.connected(), ble.ready() && ble.stationary(), ble.pending() != null, fresh, followsRidingMode());
        if (target != null) {
            selectingRange = true;
            try { ble.set("range_algorithm", target, MutationGate.Origin.AUTOMATIC_RANGE_FOLLOW); } finally { selectingRange = false; }
        }
    }
    // ---- Maintenance reminders (MaintenanceKeeper) ----

    /** Each scooter's maintenance reminders; null until the service starts. */
    public MaintenanceKeeper maintenance;
    private String maintenanceFed = "";
    private long maintenanceReadingMs = -1;
    private MaintenanceNotices maintenanceNotices;
    private boolean maintenanceRecording;

    private MaintenanceKeeper.Storage maintenanceStorage() {
        return new MaintenanceKeeper.Storage() {
            @Override public String read(String key) { return settings.getString(key, null); }
            @Override public void write(String key, String value) { settings.edit().putString(key, value).apply(); }
            @Override public void remove(String key) { settings.edit().remove(key).apply(); }
        };
    }

    /**
     * The scope the Maintenance page and the Scooter tab's dot use: the active scooter's profile id, or its
     * memory-only simulation scope while simulating (the simulator's odometer never reaches real reminders).
     * Empty without an active scooter.
     */
    public String maintenanceScope() {
        String id = activeProfileId();
        if (id.isEmpty()) return "";
        return simulating() ? MaintenanceKeeper.simulationScope(id) : id;
    }

    /**
     * Feeds the connected scooter's odometer into its maintenance count, once per new reading. Only the
     * scooter Volt is connected to counts, and only readings from this connection (Telemetry clears on
     * connect). Read only: no command is sent. When the connection ends, distance not yet written is.
     */
    private void observeMaintenance() {
        if (maintenance == null || destroyed || dataMaintenance || ble == null) return;
        String scope = maintenanceScope();
        ScooterProfiles.Profile connected = ble.profile();
        boolean valid = !scope.isEmpty() && ble.connected()
                && (simulating() || connected != null && connected.id.equals(activeProfileId()));
        boolean recording = isRecording();
        // A ride that just ended: what it rode may have made a check due.
        if (maintenanceRecording && !recording && valid) checkMaintenance(scope);
        maintenanceRecording = recording;
        if (!valid || !scope.equals(maintenanceFed)) {
            if (!maintenanceFed.isEmpty()) maintenance.flush(maintenanceFed);
            // The connection ended: one check with the distance it counted. A simulation's notice goes with the simulation.
            if (!maintenanceFed.isEmpty() && MaintenanceKeeper.simulation(maintenanceFed)) { if (maintenanceNotices != null) maintenanceNotices.cancel(maintenanceFed); }
            else if (!maintenanceFed.isEmpty()) checkMaintenance(maintenanceFed);
            maintenanceFed = valid ? scope : "";
            maintenanceReadingMs = -1;
            if (!valid) return;
        }
        Telemetry.Metric odometer = null;
        for (Telemetry.Metric metric : ble.telemetry.metrics()) if (metric.key.equals(MaintenanceView.ODOMETER)) odometer = metric;
        if (odometer == null || odometer.numericValue == null || odometer.updatedMs < 0 || odometer.updatedMs == maintenanceReadingMs) return;
        boolean first = maintenanceReadingMs < 0;
        maintenanceReadingMs = odometer.updatedMs;
        int bits = simulating() ? MaintenanceDistance.BITS_32 : ble.telemetry.odometerBits();
        MaintenanceDistance.Outcome outcome = maintenance.observe(scope, odometer.numericValue, bits, System.currentTimeMillis());
        // Connected (the first reading brings rides counted since the last connection), or a new reading while reminders are on.
        if (first || outcome != null) checkMaintenance(scope);
    }

    /** One maintenance check for a scope (MaintenanceNotify): posts what newly became due. */
    private void checkMaintenance(String scope) {
        if (maintenance == null || maintenanceNotices == null) return;
        // A simulation scope is checked quietly: what the simulated ride made due shows on the Maintenance page, never in the shade.
        MaintenanceNotify.check(maintenance, scope, System.currentTimeMillis(), java.time.ZoneId.systemDefault(), MaintenanceKeeper.simulation(scope), this::maintenanceLive, maintenanceNotices);
    }

    private boolean maintenanceLive() { return !destroyed && !dataMaintenance && !DataMaintenance.active(); }

    /**
     * The Maintenance page changed a scope's reminders, or a scooter was forgotten: a quiet check (what is due
     * counts as seen, the notice goes), then the daily job is planned or cancelled.
     */
    private void maintenanceChanged(String scope, boolean forgotten) {
        if (maintenance == null || maintenanceNotices == null) return;
        if (forgotten) {
            maintenanceNotices.cancel(scope);
            maintenanceNotices.cancel(MaintenanceKeeper.simulationScope(scope));
        } else {
            MaintenanceNotify.check(maintenance, scope, System.currentTimeMillis(), java.time.ZoneId.systemDefault(), true, this::maintenanceLive, maintenanceNotices);
        }
        MaintenanceNotices.plan(this, maintenance, profiles, System.currentTimeMillis(), this::maintenanceLive);
    }

    private String activeProfileId(){ScooterProfiles.Profile profile=profiles==null?null:profiles.active();
        return profile == null ? "" : profile.id;
    }

    private void ensureDisplayCache() {
        if (history == null) return;
        String id = activeProfileId();
        if (id.isEmpty()
                || clearingHistory.contains(id)
                || displayCache.containsKey(id)
                || loadingHistory.contains(id)) return;
        displayCache.put(id, TelemetryHistory.Snapshot.empty());
        loadingHistory.add(id);
        history.load(
                id,
                loaded -> {
                    loadingHistory.remove(id);
                    if (destroyed || clearingHistory.contains(id)) return;
                    TelemetryHistory.Snapshot newer = displayCache.get(id);
                    Map<String, TelemetryHistory.Reading> readings =
                            new LinkedHashMap<>(loaded.readings);
                    Map<String, Integer> features = new LinkedHashMap<>(loaded.features);
                    if (newer != null) {
                        readings.putAll(newer.readings);
                        features.putAll(newer.features);
                    }
                    displayCache.put(
                            id,
                            new TelemetryHistory.Snapshot(
                                    readings,
                                    features,
                                    Math.max(
                                            loaded.capturedWallMs,
                                            newer == null ? 0 : newer.capturedWallMs)));
                    changed();
                });
    }
    /**
     * Current values and grey last-known values are display data only. BLE decisions never consult
     * this cache.
     */
    public Map<String, TelemetryHistory.Reading> displayReadings() {
        if (simulating()) {
            Map<String, TelemetryHistory.Reading> samples = new LinkedHashMap<>();
            long wall = System.currentTimeMillis(), mono = SystemClock.elapsedRealtime();
            for (Telemetry.Metric m : ble.telemetry.metrics())
                samples.put(m.key, new TelemetryHistory.Reading(m.key, m.label, m.group,
                        m.value, TelemetryHistory.unitFor(m.key), m.numericValue,
                        Math.max(1, wall - Math.max(0, mono - m.updatedMs)), ble.connected()));
            return Collections.unmodifiableMap(samples);
        }
        observeTelemetry();
        String id = activeProfileId();
        TelemetryHistory.Snapshot snapshot = displayCache.get(id);
        Map<String, TelemetryHistory.Reading> result = new LinkedHashMap<>();
        long now = SystemClock.elapsedRealtime();
        for (Telemetry.Metric metric : ble.telemetry.metrics()) {
            TelemetryHistory.Reading saved =
                    snapshot == null ? null : snapshot.readings.get(metric.key);
            Long source = observedSources.get(metric.key);
            boolean live =
                    historyConnected
                            && id.equals(observedProfile)
                            && source != null
                            && now >= source
                            && now - source <= ble.readingFreshMs(TelemetryHistory.freshnessMs(metric.key));
            result.put(
                    metric.key,
                    new TelemetryHistory.Reading(
                            metric.key,
                            metric.label,
                            metric.group,
                            saved == null ? null : saved.value,
                            TelemetryHistory.unitFor(metric.key),
                            saved == null ? null : saved.number,
                            saved == null ? 0 : saved.capturedWallMs,
                            live && saved != null));
        }
        return Collections.unmodifiableMap(result);
    }

    /** Local choice: never writes the scooter's firmware range mode. */
    public boolean usesLearnedRange() {
        return !activeProfileId().isEmpty() && "learned".equals(settings.getString("range_source_" + activeProfileId(), "scooter"));
    }
    public void useLearnedRange(boolean enabled) {
        if (destroyed || activeProfileId().isEmpty()) return;
        settings.edit().putString("range_source_" + activeProfileId(), enabled ? "learned" : "scooter").apply();
        changed();
    }
    /** Current forecast for glanceable UI. Recorded charts and explicit scooter readings stay raw. */
    public Map<String, TelemetryHistory.Reading> dashboardReadings() {
        Map<String, TelemetryHistory.Reading> raw = displayReadings();
        if (!usesLearnedRange() || simulating()) return raw;
        String profile = activeProfileId();
        LearnedRangeEstimate.Result estimate = currentRangeCache(profile);
        if (estimate == null && !rangePending.containsKey(profile)) {
            requestLearnedRangeEstimate(result -> { if (!destroyed && profile.equals(activeProfileId())) changed(); });
        }
        TelemetryHistory.Reading soc = raw.get("battery_percent");
        Double km = estimate == null || soc == null || soc.value == null ? null : estimate.remainingKm(soc.number);
        if (km == null) return raw;
        Map<String, TelemetryHistory.Reading> result = new LinkedHashMap<>(raw);
        result.put("remaining_range_km", new TelemetryHistory.Reading("remaining_range_km", "Learned remaining range",
                "Ride", String.format(Locale.US, "%.2f km", km), "km", km, soc.capturedWallMs, soc.live));
        return Collections.unmodifiableMap(result);
    }
    private LearnedRangeEstimate.Result currentRangeCache(String profile) {
        LearnedRangeEstimate.Result result = rangeCache.get(profile);
        // Recompute at least daily so old rides age out even without new recordings.
        long now = System.currentTimeMillis();
        // Retain failures for Home's fallback, avoiding a retry storm on every telemetry update.
        // An explicit picker request bypasses UNAVAILABLE below, and history changes clear it.
        long lifetime = 86400000L;
        return result != null && now >= result.computedWallMs && now - result.computedWallMs < lifetime ? result : null;
    }
    /** UI availability must refresh when completed or restored ride evidence changes. */
    public long learnedRangeRevision() { return capacityGeneration; }
    public void requestLearnedRangeEstimate(Consumer<LearnedRangeEstimate.Result> callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post(() -> requestLearnedRangeEstimate(callback)); return; }
        String profile = activeProfileId();
        if (destroyed || dataMaintenance || simulating() || rides == null || profile.isEmpty()) {
            callback.accept(LearnedRangeEstimate.unavailable(profile, simulating() ? "Sample data" : profile.isEmpty() ? "Choose a scooter first" : "Ride storage unavailable"));
            return;
        }
        LearnedRangeEstimate.Result cached = currentRangeCache(profile);
        if (cached != null && cached.status != LearnedRangeEstimate.Status.UNAVAILABLE) { callback.accept(cached); return; }
        RangeJob waiting = rangePending.get(profile);
        if (waiting != null) { waiting.callbacks.add(callback); return; }
        RangeJob job = new RangeJob(profile); job.callbacks.add(callback); rangePending.put(profile, job);
        runRangeAnalysis(job);
    }
    private void runRangeAnalysis(RangeJob job) {
        long generation = capacityGeneration; RideStore store = rides;
        capacityWorker.execute(() -> {
            LearnedRangeEstimate.Result calculated;
            try {
                try { calculated = store.learnedRange(job.profile, System.currentTimeMillis()); }
                catch (RideStore.JournalChangedException changed) {
                    // Rename/delete/import can replace a file during replay. Retry the coherent
                    // snapshot once; a persistent error is reported, never silently called empty.
                    calculated = store.learnedRange(job.profile, System.currentTimeMillis());
                }
            } catch (IOException | RuntimeException error) {
                calculated = LearnedRangeEstimate.unavailable(job.profile, "Recorded rides could not be analyzed");
            }
            LearnedRangeEstimate.Result result = calculated;
            handler.post(() -> finishRangeAnalysis(job, generation, result));
        });
    }
    private void finishRangeAnalysis(RangeJob job, long generation, LearnedRangeEstimate.Result result) {
        // A late completion must never remove a newer request or repopulate an invalidated cache.
        if (destroyed || rangePending.get(job.profile) != job) return;
        if (generation != capacityGeneration) {
            if (!dataMaintenance && !simulating() && job.profile.equals(activeProfileId()) && job.restarts++ == 0) {
                runRangeAnalysis(job); return;
            }
            result = LearnedRangeEstimate.unavailable(job.profile, "Ride history changed; try again");
        } else {
            rangeCache.put(job.profile, result);
            while (rangeCache.size() > 16) rangeCache.remove(rangeCache.keySet().iterator().next());
        }
        rangePending.remove(job.profile);
        for (Consumer<LearnedRangeEstimate.Result> done : job.callbacks) done.accept(result);
    }

    /** Display-only analysis. It does not start recording, connect BLE, or request sensors. */
    public void requestCapacityEstimate(Double advertisedWh, Consumer<BatteryCapacityEstimate.Result> callback) {
        requestCapacityEstimate(advertisedWh, BatteryCapacityEstimate.DEFAULT_WINDOW_DAYS, callback);
    }

    public void invalidateCapacityEstimate() {
        capacityCache.clear();
        rangeCache.clear();
        capacityGeneration++;
    }

    public void requestCapacityEstimate(Double advertisedWh, int windowDays,
            Consumer<BatteryCapacityEstimate.Result> callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> requestCapacityEstimate(advertisedWh, windowDays, callback));
            return;
        }
        String profile = activeProfileId();
        if (simulating()) {
            callback.accept(BatteryCapacityEstimate.unavailable(profile, advertisedWh, windowDays,
                    "Sample data"));
            return;
        }
        int days = Math.max(1, Math.min(3650, windowDays));
        Double reference = advertisedWh != null && Double.isFinite(advertisedWh) && advertisedWh > 0 ? advertisedWh : null;
        if (destroyed || rides == null || profile.isEmpty()) {
            callback.accept(BatteryCapacityEstimate.unavailable(profile, reference, days,
                    profile.isEmpty() ? "No scooter set up" : "Ride storage unavailable"));
            return;
        }
        String key = profile + ":" + days + ":" + reference;
        CapacityCache cached = capacityCache.get(key);
        long now = SystemClock.elapsedRealtime();
        if (cached != null && now >= cached.mono && now - cached.mono < 10000) {
            callback.accept(cached.result);
            return;
        }
        List<Consumer<BatteryCapacityEstimate.Result>> waiting = capacityPending.get(key);
        if (waiting != null) { waiting.add(callback); return; }
        waiting = new ArrayList<>(); waiting.add(callback); capacityPending.put(key, waiting);
        long generation = capacityGeneration;
        RideStore store = rides;
        // Read on the main thread: completed charges independently check the ride-based charge.
        BatteryCapacityEstimate.ChargeEvidence charging = chargeSnapshot == null ? null : chargeSnapshot.gaugeCharge.get(profile);
        capacityWorker.execute(() -> {
            BatteryCapacityEstimate.Result calculated;
            try {
                long wall = System.currentTimeMillis();
                List<BatteryCapacityEstimate.Ride> input = new ArrayList<>();
                for (RideStore.AnalysisSnapshot snapshot : store.analysisSnapshots(profile, wall - days * 86400000L)) {
                    input.add(new BatteryCapacityEstimate.Ride(snapshot.summary, snapshot.points));
                }
                calculated = BatteryCapacityEstimate.calculate(profile, input, reference, wall, days, charging);
            } catch (IOException | RuntimeException e) {
                calculated = BatteryCapacityEstimate.unavailable(profile, reference, days, "Recorded rides could not be analyzed");
            }
            BatteryCapacityEstimate.Result result = calculated;
            handler.post(() -> {
                List<Consumer<BatteryCapacityEstimate.Result>> callbacks = capacityPending.remove(key);
                if (destroyed) return;
                if (generation != capacityGeneration && profile.equals(activeProfileId())) {
                    if (callbacks != null) for (Consumer<BatteryCapacityEstimate.Result> done : callbacks)
                        requestCapacityEstimate(reference, days, done);
                    return;
                }
                if (generation == capacityGeneration) {
                    capacityCache.put(key, new CapacityCache(SystemClock.elapsedRealtime(), result));
                    while (capacityCache.size() > 16) capacityCache.remove(capacityCache.keySet().iterator().next());
                }
                if (callbacks != null) for (Consumer<BatteryCapacityEstimate.Result> done : callbacks) done.accept(result);
            });
        });
    }

    /** Bounded, generated chart previews; no database writes or real history queries. */
    public List<TelemetryHistory.Point> simulationHistory(String key, long from, long to) {
        if (!simulating() || to <= from) return Collections.emptyList();
        Double base = ble.telemetry.number(key);
        if (base == null || !Double.isFinite(base)) return Collections.emptyList();
        List<TelemetryHistory.Point> points = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            double fraction = i / 119d;
            // The scene's shape (ScooterSimulation.historyValue): Parked's gentle wave, the riding loop, or a charge climbing.
            double value = ble.simulationHistoryValue(key, base, fraction, (to - from) * (1 - fraction) / 1000d);
            points.add(new TelemetryHistory.Point(from + (long) ((to - (double) from) * fraction), value, false));
        }
        return points;
    }

    public Map<String, Integer> displayFeatures() {
        if (simulating()) {
            Map<String, Integer> samples = new LinkedHashMap<>();
            for (ScooterCommands.Feature f : ScooterCommands.catalog()) {
                Integer value = ble.featureValue(f.key);
                if (value != null) samples.put(f.key, value);
            }
            return Collections.unmodifiableMap(samples);
        }
        observeTelemetry();
        TelemetryHistory.Snapshot snapshot = displayCache.get(activeProfileId());
        return snapshot == null ? Collections.emptyMap() : snapshot.features;
    }

    public boolean readingFresh(String key) {
        TelemetryHistory.Reading reading = displayReadings().get(key);
        return reading != null && reading.live;
    }

    public boolean featureFresh(String key) {
        ScooterProfiles.Profile profile = ble.profile();
        return ble.ready()
                && profile != null
                && profile.id.equals(activeProfileId())
                && ble.featureValue(key) != null;
    }

    public void clearHistory(String profile, Runnable completion) {
        if (destroyed) return;
        if (history == null) {
            completion.run();
            return;
        }
        long cutoff = SystemClock.elapsedRealtime();
        Set<String> clearing = new HashSet<>();
        if (profile == null) {
            clearing.addAll(displayCache.keySet());
            for (ScooterProfiles.Profile p : profiles.all()) clearing.add(p.id);
        } else clearing.add(profile);
        for (String id : clearing) {
            historyClearCutoff.put(id, cutoff);
            clearingHistory.add(id);
            displayCache.remove(id);
        }
        if (profile == null || profile.equals(observedProfile)) {
            observedSources.clear();
            historyDirty = false;
        }
        history.clear(
                profile,
                () -> {
                    for (String id : clearing) {
                        clearingHistory.remove(id);
                        displayCache.remove(id);
                    }
                    ensureDisplayCache();
                    if (!destroyed) changed();
                    completion.run();
                });
    }

    private void observeTelemetry() {
        if (history == null || destroyed || ble == null || simulating()) return;
        ensureDisplayCache();
        String id = activeProfileId();
        ScooterProfiles.Profile connected = ble.profile();
        boolean valid =
                ble.connected() && connected != null && connected.id.equals(id) && !id.isEmpty();
        if (clearingHistory.contains(id)) return;
        if (!valid) {
            if (historyConnected) persistHistory(true);
            historyConnected = false;
            observedSources.clear();
            return;
        }
        if (!historyConnected || !id.equals(observedProfile)) {
            persistHistory(true);
            observedProfile = id;
            historyConnected = true;
            observedSources.clear();
            historySession = ++historySessionCounter;
            lastHistoryWrite = 0;
        }
        TelemetryHistory.Snapshot before = displayCache.get(id);
        Map<String, TelemetryHistory.Reading> readings = new LinkedHashMap<>();
        Map<String, Integer> features = new LinkedHashMap<>();
        if (before != null) {
            readings.putAll(before.readings);
            features.putAll(before.features);
        }
        long now = SystemClock.elapsedRealtime(),
                wall = System.currentTimeMillis(),
                cutoff = historyClearCutoff.containsKey(id) ? historyClearCutoff.get(id) : -1;
        boolean updated = false;
        if (historyMono > 0 && Math.abs((wall - historyWall) - (now - historyMono)) > 2000) {
            persistHistory(true);
            historySession = ++historySessionCounter;
        }
        historyWall = wall;
        historyMono = now;
        for (Telemetry.Metric metric : ble.telemetry.metrics()) {
            if (metric.updatedMs < 0
                    || metric.updatedMs <= cutoff
                    || metric.updatedMs > now
                    || metric.value == null) continue;
            Long seen = observedSources.get(metric.key);
            TelemetryHistory.Reading prior = readings.get(metric.key);
            if (seen != null
                    && seen == metric.updatedMs
                    && prior != null
                    && Objects.equals(prior.value, metric.value)
                    && Objects.equals(prior.number, metric.numericValue)) continue;
            observedSources.put(metric.key, metric.updatedMs);
            readings.put(
                    metric.key,
                    new TelemetryHistory.Reading(
                            metric.key,
                            metric.label,
                            metric.group,
                            metric.value,
                            TelemetryHistory.unitFor(metric.key),
                            metric.numericValue,
                            Math.max(1, wall - (now - metric.updatedMs)),
                            true));
            updated = true;
        }
        long statusAge = ble.statusAge();
        if (statusAge >= 0 && statusAge < 60000 && now - statusAge > cutoff)
            for (ScooterCommands.Feature feature : ScooterCommands.catalog())
                if (!feature.key.equals("pin")) {
                    Integer value = ble.featureValue(feature.key);
                    if (value != null && !value.equals(features.get(feature.key))) {
                        features.put(feature.key, value);
                        updated = true;
                    }
                }
        if (updated) {
            displayCache.put(id, new TelemetryHistory.Snapshot(readings, features, wall));
            historyDirty = true;
        }
        persistHistory(false);
    }

    private void persistHistory(boolean force) {
        if (simulating() || history == null || !historyDirty || observedProfile.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastHistoryWrite < 5000) return;
        TelemetryHistory.Snapshot snapshot = displayCache.get(observedProfile);
        if (snapshot != null) {
            Map<String, TelemetryHistory.Reading> readings = new LinkedHashMap<>();
            for (TelemetryHistory.Reading r : snapshot.readings.values()) {
                Long source = observedSources.get(r.key);
                boolean observed =
                        source != null
                                && now >= source
                                && now - source <= TelemetryHistory.freshnessMs(r.key);
                readings.put(
                        r.key,
                        new TelemetryHistory.Reading(
                                r.key,
                                r.label,
                                r.group,
                                r.value,
                                r.unit,
                                r.number,
                                r.capturedWallMs,
                                observed));
            }
            history.record(
                    observedProfile,
                    new TelemetryHistory.Snapshot(
                            readings, snapshot.features, snapshot.capturedWallMs),
                    historySession,
                    settings.getBoolean("history_enabled", true));
        }
        historyDirty = false;
        lastHistoryWrite = now;
    }

    /** Hold CPU work only for active recording/finalization, including before the first tick.
     * Renew before the timeout: an hours-long charge must not first release its lock while asleep.
     * A stalled service still has a bounded lock rather than an indefinite battery drain. */
    private void updateRecordingWakeLock(){
        if(wakeLock==null)return;
        if(!destroyed&&!simulating()&&keepsWorking()){
            long now=SystemClock.elapsedRealtime();
            if(!wakeLock.isHeld()||now<wakeLockRenewedMs||now-wakeLockRenewedMs>=RECORDING_WAKE_RENEW_MS){
                wakeLock.acquire(RECORDING_WAKE_TIMEOUT_MS);wakeLockRenewedMs=now;
            }
        }else if(wakeLock.isHeld())wakeLock.release();
    }

    private void startForegroundNotice(boolean startup) {
        if ((!startup && simulating()) || !ble.permissions() || (!startup && !workAllowed())) return;
        int type = Build.VERSION.SDK_INT >= 29
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | (phoneLocationForeground ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0)
                : 0;
        // Already in the foreground as this type: only the notice may change, and that update is spaced like the rest.
        // Every startForeground counts toward Android's notification rate and can't wait, so it's kept for real changes.
        if (!startup && foregroundStarted && type == foregroundType) { updateNotification(); return; }
        Notification notice = notification();
        if (Build.VERSION.SDK_INT >= 29) startForeground(71, notice, type);
        else startForeground(71, notice);
        chargeLiveReposts.posted(chargeLiveBuilt, SystemClock.elapsedRealtime());
        noticeRepeat.posted(noticeKey(notice),SystemClock.elapsedRealtime());
        liveUpdatePosted();
        foregroundStarted = true; foregroundType = type;
        notices.posted(71, SystemClock.elapsedRealtime());
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        String label = T.serviceChannelScooter();
        if (!label.equals(scooterChannelLabel)) {
            NotificationChannel channel = new NotificationChannel("scooter", label, NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
            scooterChannelLabel = label;
        }
        if (noticeOpen == null)
            noticeOpen = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent content = noticeOpen;
        boolean measuring = chargeMeasure.active() && !isChargeRecording() && !isRecording() && !phoneSaving();
        // A schedule test ranks above a charge recording (its Cancel matters more; Stop charge recording stays in the app).
        String testStatus = measuring || isRecording() || phoneSaving() ? null : scheduleTestStatus();boolean testing = testStatus != null;
        // A sounding battery alarm, then a watched charge, rank below every recording, measure and test.
        boolean alarming = !measuring && !testing && alarmPlaying() && !isChargeRecording() && !isRecording() && !phoneSaving();
        boolean watching = !measuring && !testing && alertWatchShown();
        int target = alertPolicy == null ? 100 : alertPolicy.settings().chargeAt;
        String title =
                alarming ? alertTitle() : watching ? ChargeAlertPolicy.watchTitle(target) :
                measuring ? T.serviceNoticeMeasuring() : testing ? T.serviceNoticeTesting() :
                isChargeRecording() ? (chargeRecovery!=null&&chargeRecovery.recovering()?T.serviceNoticeChargeReconnecting():T.serviceNoticeChargeActive()) : phoneSaving()
                        ? T.serviceNoticeSavingRide()
                        : isRecording()
                                ? (recordingError == null
                                        ? T.serviceNoticeRideActive()
                                        : T.serviceNoticeRideAttention())
                                : ble.linked() ? T.serviceNoticeConnected() : "Volt";
        // linked(), not ready(): out of sight status comes every 15 s, and the notice must not read the gaps as lost (StatusCadence).
        // Bodies carry live facts, not a repeat of the title: name and battery when connected,
        // distance beside a running clock while recording, a progress bar while a ride is saved.
        RideStore.Summary ride = isRecording() && recordingError == null && !isChargeRecording() && !phoneSaving() ? rides.activeSummary() : null;
        CharSequence text =
                alarming ? alertText() : watching ? ChargeAlertPolicy.watchText(alertPolicy.percent(), alertPolicy.percentFresh(), target) :
                measuring ? (chargeMeasure.arming() ? T.serviceTestArming() : T.serviceNoticeMeasuringText(Fmt.integer(chargeMeasureMinutesLeft()))) :
                testing ? T.serviceNoticeTestingText(testStatus) :
                isChargeRecording() ? chargeCaptureStatus() : phoneSaving()
                        ? null
                        : isRecording()
                                ? (recordingError == null ? rideDistanceText(ride) : recordingError)
                                : ble.linked() ? connectedText() : ble.displayMessage();
        if (isRecording() && phoneStatus().startsWith("Phone sensor storage")) {
            title = T.serviceNoticeSensorsAttention();
            text = T.serviceNoticeSensorsNotSaving();
        }
        // The live card takes the charge recording's place, or the plain connected notice's while the hold runs.
        ChargeLiveState live = chargeLiveShown() ? chargeLiveState() : null;
        chargeLiveBuilt = live;
        if (live != null) {
            title = live.title;
            text = live.text;
        }
        boolean liveStop = live != null && !isChargeRecording();
        Notification.Builder builder =
                new Notification.Builder(this, "scooter")
                        .setSmallIcon(R.drawable.ic_volt_notification)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setContentIntent(content)
                        .setOngoing(reasons().ongoing(ble.connected()))
                        .setOnlyAlertOnce(true)
                        .setCategory(Notification.CATEGORY_SERVICE)
                        .setVisibility(Notification.VISIBILITY_PRIVATE);
        if (phoneSaving()) builder.setProgress(0, 0, true);
        // 0 to the alert level: the same bar after a reconnect or restart, with nothing extra to remember.
        int progress = watching ? ChargeAlertPolicy.watchProgress(alertPolicy.percent(), target) : -1;
        if (progress >= 0) builder.setProgress(target, progress, false);
        if (ride != null && ride.startWallMs > 0)
            builder.setWhen(ride.startWallMs).setShowWhen(true).setUsesChronometer(true);
        liveUpdateAsked = false;
        if (live != null) chargeLiveCard(builder, live, isChargeRecording() ? chargeCaptureStatus() : null);
        boolean immediate = Build.VERSION.SDK_INT >= 31 && keepsWorking();
        if(immediate)
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        boolean testEnding = testing && chargeSchedule().testPhase == ChargeScheduleTest.Phase.ENDING;
        String action = alarming ? "STOP_CHARGE_ALARM" : watching ? "STOP_ALERT_WATCH" : measuring ? "SCHEDULE_CHARGE_NOW" : testing ? "CANCEL_SCHEDULE_TEST" : isChargeRecording() ? "STOP_CHARGE_RECORDING" : isRecording() ? "STOP_RECORDING" : "DISCONNECT";
        if (liveStop) action = STOP_CHARGE_LIVE;
        PendingIntent stop=noticeActions.get(action);
        if(stop==null){stop=PendingIntent.getService(this,1,new Intent(this,ScooterService.class).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);noticeActions.put(action,stop);}
        // Neither can be read back from the notice: how soon Android shows it, and which action its intent sends.
        noticeBuiltExtra = Arrays.asList(immediate, action);
        if (!phoneSaving() && !(measuring && chargeMeasure.arming()) && !testEnding)
            builder.addAction(
                    new Notification.Action.Builder(
                                    null, liveStop ? ChargeLiveState.actionLabel() : alarming ? T.serviceActionStop() : watching ? T.serviceActionStopWatching() : measuring ? T.serviceActionScheduleNow() : testing ? T.serviceActionCancelTest() : isChargeRecording() ? T.serviceActionStopCharge() : isRecording() ? T.serviceActionStopRecording() : T.serviceActionDisconnect(), stop)
                            .build());
        Notification notice = builder.build();
        liveUpdateBuilt(notice);
        return notice;
    }
    /**
     * "UT5 MAX · 82%": the connected notification names the scooter and its charge, the charge only while that reading is
     * live by its own rule (BleController.liveNumber). Out of sight the battery is read with every 15 s status poll, so
     * the level stays current (owner, 2026-11-12); the link can outlive a sleep, and a read the scooter ignores is
     * suppressed (ReadPolicy), so an older level would read as current: then the notice names the scooter alone.
     */
    private String connectedText() {
        ScooterProfiles.Profile profile = profiles.active();
        Double battery = ble.liveNumber("battery_percent");
        String name = profile == null ? T.serviceNoticeScooter() : profile.displayName();
        return battery == null ? name : T.serviceNoticeScooterBattery(name, Fmt.integer(Math.round(battery)));
    }
    /** Recorded distance in the rider's units, or null before the odometer has moved. */
    private String rideDistanceText(RideStore.Summary ride) {
        if (ride == null || ride.distanceKm == null) return null;
        String units = settings.getString("units", "mi");
        String km = T.fmtQuantity(Fmt.decimal(Locale.US, ride.distanceKm, 1), T.unitsKm()), mi = T.fmtQuantity(Fmt.decimal(Locale.US, ride.distanceKm / 1.609344, 1), T.unitsMi());
        return "km".equals(units) ? km : "dual".equals(units) ? T.fmtDual(mi, km) : mi;
    }
    private void updateNotification(){if(foregroundStarted&&workAllowed())post(71,()->foregroundStarted&&workAllowed()?notification():null);}
    /** Every notification Volt posts goes through here, spaced so Android never sheds one (NotificationPacer). */
    private void post(int id,Notification notice){post(id,()->notice);}
    private void post(int id,java.util.function.Supplier<Notification> build){notices.offer(id,build);drainNotices();}
    private void cancelNotice(int id){if(id==CHARGE_ALERT_NOTICE)alertPosted=null;notices.cancel(id);try{getSystemService(NotificationManager.class).cancel(id);}catch(RuntimeException ignored){}}
    private void drainNotices(){
        handler.removeCallbacks(noticeDrain);
        long now=SystemClock.elapsedRealtime();
        Map.Entry<Integer,java.util.function.Supplier<Notification>> next=notices.take(now);
        if(next!=null)notifyNow(next.getKey(),next.getValue());
        long wait=notices.delay(now);
        if(wait>=0&&!destroyed)handler.postDelayed(noticeDrain,wait);
    }
    /** Ending: the connection notice goes with the foreground; any other queued notice posts now rather than never. */
    private void flushNotices(){
        handler.removeCallbacks(noticeDrain);notices.cancel(71);
        for(Map.Entry<Integer,java.util.function.Supplier<Notification>> next:notices.takeAll())notifyNow(next.getKey(),next.getValue());
    }
    /** After each connection notice posts: read a promotion request's verdict back once Android has handled it. */
    private void liveUpdatePosted(){
        handler.removeCallbacks(liveUpdateProbe);
        if(liveUpdateLast.asked()&&!destroyed)handler.postDelayed(liveUpdateProbe,1500);
    }
    private void notifyNow(int id,java.util.function.Supplier<Notification> build){
        boolean sent=true;
        try{Notification notice=build.get();if(notice!=null){
            // The connection notice is rebuilt on every tick and change; it posts only when it differs from what shows (NoticeRepeat).
            Object key=id==71?noticeKey(notice):null;
            long now=SystemClock.elapsedRealtime();
            sent=key==null||noticeRepeat.due(key,showsPostTime(notice),now,this::connectionNoticeShowing);
            // A skipped notice is the one showing, so the live card's baseline is what it carries either way.
            if(sent)getSystemService(NotificationManager.class).notify(id,notice);if(id==71)chargeLiveReposts.posted(chargeLiveBuilt,SystemClock.elapsedRealtime());
            if(sent&&key!=null)noticeRepeat.posted(key,now);}}
        catch(RuntimeException unavailable){}
        if(id==71&&sent)liveUpdatePosted();
    }
    /** Android still shows the connection notice: not swiped away (Android 14 and later allow that) or removed. */
    private boolean connectionNoticeShowing(){
        try{
            for(android.service.notification.StatusBarNotification n:getSystemService(NotificationManager.class).getActiveNotifications())
                if(n.getId()==71&&n.getTag()==null)return true;
        }catch(RuntimeException unknown){}
        return false;
    }
    /** Everything the connection notice carries, for NoticeRepeat; noticeBuiltExtra is from the notification() that built it. */
    private Object noticeKey(Notification notice){return NoticeRepeat.key(Arrays.asList(noticeBuiltExtra,noticeParts(notice)));}
    /** The header shows when the notice was posted ("now"), not a running clock: the builder stamps that time on every rebuild. */
    private static boolean showsPostTime(Notification n){
        Bundle extras=n.extras;
        return extras!=null&&extras.getBoolean(Notification.EXTRA_SHOW_WHEN)&&!extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER);
    }
    /** A notice's parts, its time only for a running clock (a post time is stamped on every rebuild; see NoticeRepeat), its lock screen version too. */
    private static List<Object> noticeParts(Notification n){
        if(n==null)return null;
        Bundle extras=n.extras;
        boolean timed=extras!=null&&extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER);
        List<Object> actions=new ArrayList<>();
        if(n.actions!=null)for(Notification.Action a:n.actions)
            actions.add(Arrays.asList(noticePart(a.title),noticePart(a.actionIntent),noticePart(a.getIcon()),noticePart(a.getExtras()),noticePart(a.getRemoteInputs()),a.getAllowGeneratedReplies()));
        return Arrays.asList(n.getChannelId(),noticePart(n.getSmallIcon()),noticePart(n.getLargeIcon()),n.flags,n.category,n.visibility,n.color,n.number,
                timed?n.when:null,noticePart(n.contentIntent),noticePart(n.deleteIntent),noticePart(n.fullScreenIntent),n.getGroup(),n.getSortKey(),
                n.getGroupAlertBehavior(),n.getTimeoutAfter(),n.getBadgeIconType(),n.getShortcutId(),noticePart(n.getSettingsText()),actions,
                noticePart(extras),noticeParts(n.publicVersion));
    }
    /** Bundles as maps, resource icons by resource, styled text never equal, the app's own info by package; anything else as it is. */
    private static Object noticePart(Object value){
        if(value instanceof Bundle){
            Bundle bundle=(Bundle)value;Map<String,Object> parts=new HashMap<>();
            for(String key:bundle.keySet())parts.put(key,noticePart(bundle.get(key)));
            return parts;
        }
        if(value instanceof android.text.Spanned&&((android.text.Spanned)value).getSpans(0,((android.text.Spanned)value).length(),Object.class).length>0)return new Object();
        if(value instanceof android.graphics.drawable.Icon&&((android.graphics.drawable.Icon)value).getType()==android.graphics.drawable.Icon.TYPE_RESOURCE){
            android.graphics.drawable.Icon icon=(android.graphics.drawable.Icon)value;
            return Arrays.asList("icon",icon.getResPackage(),icon.getResId());
        }
        if(value instanceof Notification)return noticeParts((Notification)value);
        if(value instanceof android.content.pm.ApplicationInfo)return Arrays.asList("app",((android.content.pm.ApplicationInfo)value).packageName);
        if(value instanceof Object[]){List<Object> parts=new ArrayList<>();for(Object item:(Object[])value)parts.add(noticePart(item));return parts;}
        if(value instanceof List){List<Object> parts=new ArrayList<>();for(Object item:(List<?>)value)parts.add(noticePart(item));return parts;}
        return value;
    }
    /**
     * The volt-diagnostics-v1 file a report email may carry (DiagnosticsBundle), or null when it would contain a saved
     * scooter's serial, MAC, owner key or chosen name. Built on demand; it leaves the phone only when the owner sends it.
     */
    String diagnosticsBundle(CompatibilityReport report){
        DiagnosticsBundle bundle=new DiagnosticsBundle(AppVersion.name(this),System.currentTimeMillis(),simulating())
                .phone(Build.VERSION.RELEASE,Build.VERSION.SDK_INT,Build.MANUFACTURER+" "+Build.MODEL).report(report)
                .connections(ble.diagnosticEvents(),ble.diagnosticEventsLoaded(),ble.diagnosticStorageHealthy())
                .commands(ble.commandEvents(),ble.commandEventsLoaded(),ble.commandStorageHealthy())
                .readings(ble.telemetry.metrics(),SystemClock.elapsedRealtime()).chargeSchedule(schedules().diagnostics());
        // The selected scooter's evidence only; none in a simulation (its file is the real scooter's).
        String selected=activeProfileId();
        if(evidence!=null&&!simulating()&&ChargeEvidence.scope(selected))bundle.evidence(evidence.snapshot(selected));
        List<String> secrets=new ArrayList<>();
        for(ScooterProfiles.Profile p:profiles.all()){
            secrets.add(p.id);secrets.add(p.serial);secrets.add(p.identity);secrets.add(p.mac);secrets.add(p.mac==null?null:p.mac.replace(":",""));
            if(!p.nameDefault)secrets.add(p.name);
        }
        return bundle.json(secrets);
    }
    public String snapshot(){try{JSONObject o=new JSONObject();
            o.put("format", "navee-offline-diagnostics-v1");
            o.put("app_version", AppVersion.name(this));
            o.put("captured_at_ms", System.currentTimeMillis());
            o.put("simulated", simulating());
            o.put("connected", ble.ready());
            o.put("connection", ble.state().name());
            o.put("mtu", ble.mtu());
            o.put("connection_events_loaded", ble.diagnosticEventsLoaded());
            o.put("connection_events_storage_healthy", ble.diagnosticStorageHealthy());
            JSONArray connectionEvents = new JSONArray();
            for (ConnectionEventLog.Record event : ble.diagnosticEvents()) {
                JSONObject item = new JSONObject();
                item.put("wall_ms", event.wallMs);
                item.put("elapsed_ms", event.elapsedMs);
                item.put("reason", event.reason.name());
                item.put("status_code", event.statusCode < 0 ? JSONObject.NULL : event.statusCode);
                item.put("phase", event.phase.name());
                item.put("ui_visible", event.visible);
                item.put("recording", event.recording);
                item.put("status_age_ms", event.statusAgeMs < 0 ? JSONObject.NULL : event.statusAgeMs);
                item.put("frame_age_ms", event.frameAgeMs < 0 ? JSONObject.NULL : event.frameAgeMs);
                connectionEvents.put(item);
            }
            o.put("connection_events", connectionEvents);
            JSONArray fields=new JSONArray();long now=SystemClock.elapsedRealtime();for(Telemetry.Metric m:ble.telemetry.metrics()){JSONObject f=new JSONObject();
                f.put("group", m.group);
                f.put("key", m.key);
                f.put("label", m.label);
                f.put("value", m.value == null ? JSONObject.NULL : m.value);
                f.put("number", m.numericValue == null ? JSONObject.NULL : m.numericValue);
                f.put("age_ms", m.updatedMs < 0 ? JSONObject.NULL : now - m.updatedMs);
                f.put(
                        "source",
                        m.sourceCmd < 0
                                ? JSONObject.NULL
                                : String.format(Locale.ROOT, "0x%02X", m.sourceCmd));
                fields.put(f);}
            o.put("fields", fields);
            JSONObject raw=new JSONObject();
            for (Map.Entry<Integer, byte[]> e : ble.telemetry.frames().entrySet())
                if (!ScooterCommands.sensitiveCommand(e.getKey()))
                    raw.put(
                            String.format(Locale.ROOT, "0x%02X", e.getKey()),
                            Protocol.hex(e.getValue()));
            o.put("raw_diagnostic_payloads", raw);
            JSONObject results=new JSONObject();
            for (Map.Entry<String, String> e : ble.commandResults().entrySet())
                if (!e.getKey().equals("pin")) results.put(e.getKey(), e.getValue());
            o.put("control_results", results);
            return o.toString(2);}catch(JSONException e){
            return "{}";
        }}
    @Override public void onDestroy(){
        // No screen can show a test's result once the service ends: cancel() posts its notification instead.
        if(chargeScheduleController!=null){destroyed=true;chargeScheduleController.cancel(ChargeScheduleController.Interruption.SERVICE_ENDED);}
        closeEvidence();
        resetChargeEstimateContext();
        if (guardedLifecycle) { chargeWorker.shutdown(); capacityWorker.shutdown(); super.onDestroy(); return; }
        if(rideAutoExport!=null)rideAutoExport.shutdown();
        if(maintenance!=null&&!maintenanceFed.isEmpty()&&!dataMaintenance)maintenance.flush(maintenanceFed);
        if(chargeMeasure.active()){chargeMeasure.finish();chargeDoneNotice(false,T.serviceMeasureClosed());}destroyed=true;stopChargeAlarm();finishChargeCapture(ChargeSessionStore.Stop.PROCESS_ENDED);chargeWorker.shutdown();workActive=false;if(running==this)running=null;if(settings!=null)settings.unregisterOnSharedPreferenceChangeListener(settingsChanged);handler.removeCallbacksAndMessages(null);capacityWorker.shutdown();capacityPending.clear();rangePending.clear();persistHistory(true);if(history!=null)history.close();stopPhoneRecording();if(isRecording())try{
                rides.markGap(
                        System.currentTimeMillis(), SystemClock.elapsedRealtime(), "Service ended");
            }catch(IOException ignored){}if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();if(ble!=null)ble.destroy();flushNotices();stopForeground(STOP_FOREGROUND_REMOVE);foregroundStarted=false;listeners.clear();super.onDestroy();}
}
