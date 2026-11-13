package com.jjricks.volt;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.ScanResult;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import org.json.JSONObject;

/** Native adaptive shell. The foreground service owns Bluetooth and recordings. */
public final class MainActivity extends Activity implements ScreenHost {
    private static final int BLUETOOTH_PERMISSION=101, NOTIFICATION_PERMISSION=102, ENABLE_BLUETOOTH=103,
            RIDE_LOCATION_PERMISSION=104, IMPORT_OWNER=201, SAVE_DOCUMENT=202, IMPORT_LAYOUTS=203;
    private static final long BODY_REFRESH_MS=1000;
    /** When Volt last left the screen (elapsed realtime) and the boot it was in; read once on return (HubNav.awayExpired). */
    private static final String HUB_AWAY_AT="hub_away_elapsed", HUB_AWAY_BOOT="hub_away_boot";
    private static final java.util.Set<String> preparingExports=java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ConnectionFeedbackState connectionFeedback=new ConnectionFeedbackState(SystemClock::elapsedRealtime);
    private final ConnectionHapticPlayer connectionHaptics=new ConnectionHapticPlayer();
    private LiveStatsTitle liveStatsTitle;
    private static final String STATS_AWAY_AT="stats_away_elapsed", STATS_AWAY_BOOT="stats_away_boot";
    private static final long STATS_RETENTION_MS=10000L;
    /** The Widgets Home dock (HubDock), where the tab bar was; null on every other page. */
    private HubDock dock;
    /**
     * The wide Clean Home (hub layout plan 1.11, PR 13): the scooter pane (homeScreen, built once per rebuild and kept while
     * pages change beside it) and the page pane's column (rightPage: the shown page's header and screen); both null on every
     * other layout. paneLocks counts the lock views the scooter pane registered; a page's own (Security's) come after them.
     */
    private CleanHomeScreen homeScreen;
    private LinearLayout rightPage;
    private int paneLocks;
    /** True while createScreen builds a page for the page pane: a page that splits into panes of its own keeps one. */
    private boolean paneHosted;
    /** The wide Home's panes: the scooter pane's and the page pane's minimum widths (dp). */
    private static final int PANE_LEFT_DP=320,PANE_RIGHT_DP=260;
    /** The window size, text size and language at which a subject's name could not fit the page pane: Home takes one pane there. */
    private static String onePaneHomeAt="";
    private final LinkedHashMap<String,Integer> scrollPositions=new LinkedHashMap<>();
    private ScooterService scooterService;
    private SharedPreferences preferences,documents;
    private Ui design;
    private FoldMonitor fold;
    private FrameLayout root,pageFrame;
    private ScrollView scroll;
    private AppScreen screen;
    /** First-run setup; while it is showing there is no header or tab bar. */
    private OnboardingScreen onboarding;
    /** Open riding mode (Home's RIDE button): kept across rotations and rebuilds; null when closed. */
    private RidingSession riding;
    /** Riding mode was open when the activity was recreated; it reopens once the service is bound. */
    private boolean ridingRestore;
    /** The open riding mode's ride time and distance when the activity was recreated; a reopen after Back starts from zero. */
    private RidingSession.Resume ridingResume;
    private TextView simulationNotice;
    private boolean displayedSimulation;
    private TextView connectionTitle,connectionAction,lockTitle,profileTitle,offlineTitle,offlineDetail;
    private View lockReady,lockOffline;
    private LockControl lockControl;
    /** One rider lock tap shared by every lock switch: shown at once, sent when the scooter takes writes. */
    private final LockTapQueue lockQueue=new LockTapQueue();
    /** The stopped gate of the riding-mode switch that made the waiting tap. */
    private java.util.function.BooleanSupplier lockStoppedGate;
    private boolean showDashboardDisconnect=true,compactLock;
    /**
     * Every lock card on the current dashboard (normally one; the All widgets example and older
     * saved dashboards can hold the full lock and the lock bar together). The activity's lock
     * fields always point at the first card; updates load each card's views in turn.
     */
    private final java.util.ArrayList<DashboardLockViews> dashboardLocks=new java.util.ArrayList<>();
    private static final class DashboardLockViews{
        TextView connectionAction,lockTitle,offlineTitle,offlineDetail;View lockReady,lockOffline;LockControl lockControl;boolean showDisconnect,compact;
    }
    private DashboardLockViews currentLockViews(){
        DashboardLockViews v=new DashboardLockViews();
        v.connectionAction=connectionAction;v.lockTitle=lockTitle;v.offlineTitle=offlineTitle;v.offlineDetail=offlineDetail;
        v.lockReady=lockReady;v.lockOffline=lockOffline;v.lockControl=lockControl;v.showDisconnect=showDashboardDisconnect;v.compact=compactLock;return v;
    }
    private void loadLockViews(DashboardLockViews v){
        connectionAction=v.connectionAction;lockTitle=v.lockTitle;offlineTitle=v.offlineTitle;offlineDetail=v.offlineDetail;
        lockReady=v.lockReady;lockOffline=v.lockOffline;lockControl=v.lockControl;showDashboardDisconnect=v.showDisconnect;compactLock=v.compact;
    }
    /** Adds the card just built, points the fields back at the first card, then refreshes all of them. */
    private View registerDashboardLock(View content){
        dashboardLocks.add(currentLockViews());
        loadLockViews(dashboardLocks.get(0));
        updateHeader();return content;
    }
    /** The page stack (Hub). selectedPage is always nav.current(): android-tests read it by reflection. */
    private HubNav nav=new HubNav();
    private String selectedPage=HubPages.HOME,scrollPage=HubPages.HOME;
    /** One-shot arguments for the next page built (ScreenHost.open); taken by rebuild, never saved. */
    private HubNav.Args pageArgs;
    /** Review's recovery-only Compatibility page: pageArgs is one-shot, so this keeps it through rebuilds while the page is in the stack. */
    private boolean compatibilityRecovery;
    /** The search page's query: kept across recreation and while a result's page is open above it; a fresh search starts empty. */
    private String searchQuery="";
    private boolean bound,binding,resumed,destroyed,pendingConnect,pendingScan,expanded,rebuilding,applyingOwnerImport,importWaitNotified,bodyRefreshScheduled;
    private long lastBodyRefreshMs;
    private boolean startupRedirect, enteringMaintenance;
    private int topInset,bottomInset,leftInset,rightInset;
    private AlertDialog scanDialog, controlDialog, missedStartDialog, scheduleTestDialog, chargeAlertDialog;
    /** The missed start already offered during this visit; a later visit offers it again until answered. */
    private long missedStartOffered;
    /** A quick-control sheet over Home (HubRoutes.sheetFor): its control rows and charge-schedule rows refresh with the body. */
    private AlertDialog quickMenuDialog;
    private final ArrayList<ControlRow> quickMenuRows = new ArrayList<>();
    private final ArrayList<ChargeDoneRow> quickChargeRows = new ArrayList<>();
    private Runnable pendingRideLocation;
    private boolean rideLocationResolved,rideLocationCancelled,autoRecordLocationCancelled;
    private android.window.OnBackInvokedCallback systemBack;
    private LinearLayout scanList;
    private TextView scanStatus,scanConnect;
    private View scanSpinner;
    private String scanSignature="",scanSelected;
    private final ArrayList<ScanResult> scanResults=new ArrayList<>();
    private final Runnable serviceChanged=this::automaticRefresh;
    private final Runnable deferredBodyRefresh=()->{
        bodyRefreshScheduled=false;
        if(resumed&&!destroyed&&design!=null)refreshBodyNow();
    };
    private final Runnable settingsRebuild = () -> transitionRebuild(0);
    /** Tab order of the page being left, so the next rebuild can slide its nav dot from there. */
    private View pageHeader;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged=(p,key)->{
        if(key.equals("theme")||key.equals("units")||key.equals("temperature_unit")||key.equals("stats_density")||key.equals(HomeStyle.KEY)) {
            handler.removeCallbacks(settingsRebuild);
            handler.postDelayed(settingsRebuild, Ui.motionEnabled() ? 210 : 0);
        } else if(key.equals("text_scale")) { handler.removeCallbacks(settingsRebuild); handler.post(settingsRebuild); }
        else redraw();
    };
    private final ServiceConnection serviceConnection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder binder){
            if(destroyed || enteringMaintenance || startupRedirect)return;
            scooterService=((ScooterService.LocalBinder)binder).service();bound=true;binding=false;
            observeConnectionFeedback(true);
            scooterService.listen(serviceChanged);scooterService.visible(resumed);
            // Email to Volt's finished exports come here, whichever page or earlier activity asked (#387 review).
            ChargeSend.MAILBOX.register(chargeMail,System.currentTimeMillis());
            if(resumed){maybeApplyOwnerImport();if(pendingScan){pendingScan=false;showScanner(true);}else if(pendingConnect){pendingConnect=false;connect();}}
            rebuild();
        }
        @Override public void onServiceDisconnected(ComponentName name){
            scooterService=null;bound=false;binding=false;observeConnectionFeedback(true);rebuild();
        }
    };
    /** System default wraps nothing; an in-app language on API 26-32 reaches this activity's resources here (plan 5.3). */
    @Override protected void attachBaseContext(Context base){super.attachBaseContext(AppLanguage.wrap(base));}
    @Override public void onCreate(Bundle saved){
        if (DataMaintenance.active()) {
            startupRedirect = true; super.onCreate(saved);
            TextView preparing = new TextView(this); preparing.setText(T.mainPreparingData());
            preparing.setGravity(Gravity.CENTER); setContentView(preparing);
            handler.post(new Runnable(){public void run(){
                if(isDestroyed()||isFinishing())return;
                Intent transfer=DataMaintenance.preparedTransfer();
                if(transfer==null){handler.postDelayed(this,100);return;}
                startActivity(transfer.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));finish();
            }});
            return;
        }
        if (!DataMaintenance.readyForStartup(this)) {
            startupRedirect = true; super.onCreate(saved);
            TextView opening = new TextView(this); opening.setText(T.mainOpeningData());
            opening.setGravity(Gravity.CENTER); setContentView(opening);
            new Thread(() -> {
                try { DataMaintenance.ensureRecovered(this); runOnUiThread(() -> {if(!isDestroyed())recreate();}); }
                catch (java.io.IOException failure) { runOnUiThread(() -> {
                    if(isDestroyed())return;
                    startActivity(new Intent(this,BackupActivity.class).putExtra("recovery",true));finish();
                }); }
            },"Volt-data-recovery").start();
            return;
        }
        preferences=getSharedPreferences("app_settings",MODE_PRIVATE);
        setTheme(darkTheme()?R.style.VoltDark:R.style.VoltLight);
        super.onCreate(saved);
        if(Build.VERSION.SDK_INT>=33){systemBack=this::navigateBack;getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,systemBack);}
        documents=getSharedPreferences("pending_documents",MODE_PRIVATE);
        String stagedExport=documents.getString("path",null);
        if(stagedExport!=null&&!documents.getBoolean("ready",false)&&!preparingExports.contains(stagedExport)){
            File orphan=new File(stagedExport);
            if(getFilesDir().equals(orphan.getParentFile())&&orphan.getName().startsWith("pending-export-"))orphan.delete();
            documents.edit().clear().commit();
            toast(T.mainExportCleared());
        }
        if(saved==null&&documents.getBoolean("ready",false)&&documents.getBoolean("picker",false))documents.edit().putBoolean("picker",false).apply();
        if(saved!=null){nav=HubNav.restore(saved.getStringArray("page_stack"),saved.getString("page",HubPages.HOME));for(String page:HubPages.ALL)scrollPositions.put(page,saved.getInt("scroll_"+page,0));pendingConnect=saved.getBoolean("pending_connect",false);pendingScan=saved.getBoolean("pending_scan",false);maintenancePending=saved.getBoolean("maintenance_pending",false);compatibilityRecovery=saved.getBoolean("compat_recovery",false);searchQuery=saved.getString("search_query","");}
        if(saved!=null)ridingRestore=saved.getBoolean("riding",false);
        else nav=new HubNav();
        // A notice tap still waiting for riding mode or setup to close survives a cold start (into unfinished setup, say), as on main.
        maintenancePending|=MaintenanceScreen.pending(preferences);
        selectedPage=nav.current();
        if(saved!=null&&saved.containsKey("riding_opened_ms"))ridingResume=RidingSession.Resume.of(saved.getString("riding_profile"),saved.getLong("riding_opened_ms"),saved.getDoubleArray("riding_distance"),saved.getDoubleArray("riding_speed"),saved.getLong("riding_auto_ends",-1),saved.getBoolean("riding_link_seen",false),saved.getLongArray("riding_leave"));
        // A fresh start has no stack to keep, so a clock left by the last run never cuts a notice's route short.
        if(saved!=null)expireAway();else clearAway();
        // A maintenance notice's tap opens Service › Maintenance reminders, for the active scooter only.
        MaintenanceNotices.Tap tap=saved==null?MaintenanceNotices.tapped(getIntent(),preferences,new ScooterProfiles(this)):null;
        // Also kept as pending until a page is built outside setup: setup shown first replaces the stack when it finishes.
        if(tap!=null&&tap.route==MaintenanceNotify.Route.OPEN_PAGE){showMaintenanceFromNotice();pendMaintenance(true);}
        expireStatsSection(saved!=null);
        fold=new FoldMonitor(this,()->{if(!destroyed)rebuild();});
        preferences.registerOnSharedPreferenceChangeListener(settingsChanged);
        if(saved==null&&UpdateCheckJob.opened(getIntent()))openUpdateCheck(false);
        rebuild();
        maintenanceTapOther(tap);
    }
    /** A notice for a saved scooter that isn't the active one: say which, and never switch scooters. */
    private void maintenanceTapOther(MaintenanceNotices.Tap tap){
        if(tap!=null&&tap.route==MaintenanceNotify.Route.OTHER)toast(T.maintenanceNoticeOther(tap.name));
    }
    /** A notice was tapped while Volt was open: the automatic update check's, or a maintenance notice. */
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);
        if(startupRedirect||preferences==null)return;
        if(UpdateCheckJob.opened(intent)){openUpdateCheck(true);return;}
        MaintenanceNotices.Tap tap=MaintenanceNotices.tapped(intent,preferences,scooterService!=null&&scooterService.profiles!=null?scooterService.profiles:new ScooterProfiles(this));
        if(tap==null)return;
        if(tap.route!=MaintenanceNotify.Route.OPEN_PAGE){maintenanceTapOther(tap);return;}
        // Focus mode stays up; the page opens as soon as riding mode or setup closes (riding mode still waiting to be
        // restored counts: the page would open under it and be lost).
        if(riding!=null||onboarding!=null||ridingRestore){pendMaintenance(true);return;}
        captureScroll();showMaintenanceFromNotice();preferences.edit().putString(MaintenanceScreen.LAST_PAGE,selectedPage).apply();rebuild();
    }
    /** Settings, then the manual update check's dialog once Settings is on screen (showUpdateCheck). */
    private void openUpdateCheck(boolean now){
        updateCheckPending=true;clearAway();
        if(!now){showPage(HubPages.SETTINGS);return;}
        if(selectedPage.equals(HubPages.SETTINGS))showUpdateCheck();else open(HubPages.SETTINGS);
    }
    private void showUpdateCheck(){
        if(!updateCheckPending||!(screen instanceof MoreScreen))return;
        updateCheckPending=false;((MoreScreen)screen).checkForUpdates();
    }
    private boolean updateCheckPending;
    /**
     * A maintenance notice was tapped while riding mode or setup was up (onNewIntent), or at a cold start: its page opens
     * as soon as they close. Kept across recreation and, in MaintenanceScreen.PENDING_PREF, across a restart.
     */
    private boolean maintenancePending;
    private void pendMaintenance(boolean on){maintenancePending=on;MaintenanceScreen.pending(preferences,on);}
    /** A maintenance notice's place: Service › Maintenance reminders, so Back steps through Service to Home. */
    private void showMaintenanceFromNotice(){clearAway();pageArgs=null;compatibilityRecovery=false;nav.reset(MaintenanceScreen.PAGE);selectedPage=nav.current();}
    @Override public void onResume(){
        super.onResume();if(startupRedirect || enteringMaintenance)return;resumed=true;missedStartOffered=0;ScooterService.setUiVisible(true);fold.start();
        observeConnectionFeedback(true);
        // A long time away closes sub-pages back to their subject; Data keeps Live Stats' own shorter section reset.
        boolean expired=expireAway();
        if(inData()){expired|=expireStatsSection(true);markStatsActive();}
        if(expired)rebuild();
        if(hasBluetoothPermission()){ensureService();if(bound&&scooterService!=null)startService(new Intent(this,ScooterService.class));}
        if(scooterService!=null){maybeApplyOwnerImport();scooterService.visible(true);if(pendingScan){pendingScan=false;showScanner(true);}else if(pendingConnect){pendingConnect=false;connect();}if(scooterService.ble.connected())scooterService.ble.refresh(false);}
        handler.removeCallbacks(pulse);handler.post(pulse);
        finishRidePermission();
    }
    /** Off screen is onStop to onStart: a permission dialog only pauses. A close happens before anything is drawn, so no transition. */
    @Override protected void onStart(){
        super.onStart();
        if(startupRedirect||enteringMaintenance)return;
        if(riding!=null&&scooterService!=null&&riding.lostOnReturn(scooterService.rideLink(),scooterService.isRecording(),SystemClock.elapsedRealtime())){dropRiding();rebuild();}
    }
    @Override protected void onStop(){
        if(!startupRedirect&&!enteringMaintenance)noteRidingLeft();
        super.onStop();
    }
    /** A configuration change recreates the activity on screen: not a leave. */
    private void noteRidingLeft(){
        if(riding!=null&&scooterService!=null&&!isChangingConfigurations())riding.left(scooterService.rideLink(),SystemClock.elapsedRealtime());
    }
    @Override public void onPause(){
        connectionHaptics.cancel();
        if(startupRedirect || enteringMaintenance){super.onPause();return;}
        markAway();
        if(inData())markStatsAway();
        resumed=false;for(DashboardLockViews lock:dashboardLocks)if(lock.lockControl!=null)lock.lockControl.stopMotion();if(lockControl!=null)lockControl.stopMotion();ScooterService.setUiVisible(false);captureScroll();fold.stop();handler.removeCallbacks(pulse);cancelDeferredBodyRefresh();
        if(scooterService!=null)scooterService.visible(false);
        super.onPause();
    }
    @Override public void onDestroy(){
        connectionHaptics.cancel();
        if(startupRedirect){destroyed=true;super.onDestroy();return;}
        if(Build.VERSION.SDK_INT>=33&&systemBack!=null)getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(systemBack);
        destroyed=true;closeQuickMenu();pendingRideLocation=null;if(dock!=null){dock.close();dock=null;}if(controlDialog!=null)controlDialog.dismiss();if(missedStartDialog!=null)missedStartDialog.dismiss();if(scheduleTestDialog!=null)scheduleTestDialog.dismiss();if(chargeAlertDialog!=null)chargeAlertDialog.dismiss();if(reportChecking&&scooterService!=null)scooterService.ble.stopDiscovery();reportChecking=false;if(reportDialog!=null)reportDialog.dismiss();if(whatsNewSheet!=null)whatsNewSheet.dismiss();bodyRefreshScheduled=false;handler.removeCallbacksAndMessages(null);fold.stop();preferences.unregisterOnSharedPreferenceChangeListener(settingsChanged);
        if(screen!=null)screen.close();closeHomePane();if(scanDialog!=null)scanDialog.dismiss();
        if(scooterService!=null&&isFinishing())scooterService.ble.telemetry.followSpeed(null);
        if(scooterService!=null)scooterService.unlisten(serviceChanged);
        ChargeSend.MAILBOX.unregister(chargeMail,isChangingConfigurations());
        if(bound||binding)try{unbindService(serviceConnection);}catch(IllegalArgumentException ignored){}
        scooterService=null;bound=binding=false;super.onDestroy();
    }
    @Override public void onSaveInstanceState(Bundle saved){
        if(startupRedirect || enteringMaintenance){super.onSaveInstanceState(saved);return;}
        captureScroll();saved.putString("page",selectedPage);saved.putStringArray("page_stack",nav.toArray());saved.putBoolean("pending_connect",pendingConnect);saved.putBoolean("pending_scan",pendingScan);saved.putBoolean("maintenance_pending",maintenancePending);saved.putBoolean("compat_recovery",compatibilityRecovery);saved.putString("search_query",searchQuery);
        noteRidingLeft();saved.putBoolean("riding",riding!=null||ridingRestore);
        RidingSession.Resume ridingState=riding!=null?riding.resume():ridingResume;
        if(ridingState!=null){saved.putLong("riding_opened_ms",ridingState.openedMs());saved.putString("riding_profile",ridingState.profileId());saved.putDoubleArray("riding_distance",ridingState.distance());saved.putDoubleArray("riding_speed",ridingState.speed());saved.putLong("riding_auto_ends",ridingState.autoEnds());saved.putBoolean("riding_link_seen",ridingState.linkSeen());saved.putLongArray("riding_leave",ridingState.leave());}
        for(String page:HubPages.ALL)saved.putInt("scroll_"+page,scrollPositions.containsKey(page)?scrollPositions.get(page):0);
        super.onSaveInstanceState(saved);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && screen instanceof DashboardScreen)
            ((DashboardScreen) screen).onUserTouch();
        return super.dispatchTouchEvent(event);
    }
    @Override public void onBackPressed(){navigateBack();}
    private void navigateBack(){
        if(startupRedirect || enteringMaintenance)return;
        if(screen instanceof OnboardingScreen){if(!((OnboardingScreen)screen).back())finish();return;}
        if(riding!=null){riderClosedRiding();return;}
        if(screen!=null&&screen.back())return;
        if(nav.size()>1){goBack();return;}
        finish();
    }
    @Override public void onConfigurationChanged(Configuration next){super.onConfigurationChanged(next);rebuild();fitTallSheet();}
    @Override public void onWindowFocusChanged(boolean focused){
        super.onWindowFocusChanged(focused);
        // Returning to the app (or dismissing a dialog) is not a new scooter connection.
        observeConnectionFeedback(true);
    }
    private void observeConnectionFeedback(boolean baseline){
        if(Looper.myLooper()!=Looper.getMainLooper()){
            handler.post(()->observeConnectionFeedback(baseline));return;
        }
        BleController ble=scooterService==null||scooterService.simulating()?null:scooterService.ble;
        // READY means authentication, serial and first status succeeded. Unlike ready(),
        // it does not flicker when a telemetry reading becomes stale.
        boolean connected=ble!=null&&ble.state()==BleController.State.READY&&ble.connected();
        boolean focused=resumed&&!destroyed&&!startupRedirect&&!enteringMaintenance&&hasWindowFocus();
        if(baseline){connectionFeedback.baseline(ble,connected);connectionHaptics.cancel();return;}
        ConnectionFeedbackState.Event event=connectionFeedback.observe(ble,connected,focused);
        if(!focused||ble==null)connectionHaptics.cancel();
        else if(event!=ConnectionFeedbackState.Event.NONE)
            connectionHaptics.play(getWindow().getDecorView(),event==ConnectionFeedbackState.Event.CONNECTED);
    }
    private final Runnable pulse=new Runnable(){@Override public void run(){
        if(!resumed||destroyed||enteringMaintenance)return;maybeApplyOwnerImport();automaticRefresh();maybeLaunchSave();handler.postDelayed(this,1000);
    }};
    private boolean darkTheme(){String theme=preferences.getString("theme","dark");return theme.equals("dark")||(theme.equals("system")&&(getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);}
    /** Whether the shown page is Data or one of its pages (Live Stats' section memory covers them all). */
    private boolean inData(){return inData(selectedPage);}
    private static boolean inData(String page){return HubPages.DATA.equals(HubPages.subjectOf(HubPages.canonical(page)));}
    /** Shows a page with only its parents below it, with no transition (startup, notifications, leaving riding mode or setup). */
    private void showPage(String page){pageArgs=null;compatibilityRecovery=false;nav.reset(page);selectedPage=nav.current();}
    private Context dialogContext(){return new ContextThemeWrapper(this,design.dark?R.style.VoltDialog:R.style.VoltDialogLight);}
    private void ensureService(){
        if(bound||binding||!hasBluetoothPermission()||destroyed||DataMaintenance.active()||enteringMaintenance)return;
        try{
            Intent intent=new Intent(this,ScooterService.class);
            if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent);
            binding=bindService(intent,serviceConnection,Context.BIND_AUTO_CREATE);
            if(!binding)toast(T.mainServiceFailed());
        }catch(Exception e){binding=false;toast(T.mainServiceFailed());}
    }
    @Override public Activity activity(){return this;}
    @Override public boolean resumed(){return resumed;}
    @Override public Ui ui(){return design;}
    /**
     * A page's width: the window's, or in the wide Home's page pane that pane's (as HubPanes will split it, gutter on the
     * window's centre line), plus the 18 dp a side a phone page pads itself by, which the window's width includes too.
     */
    @Override public int contentWidthDp(){
        int width=getResources().getConfiguration().screenWidthDp;
        if(!paneHosted&&rightPage==null)return width;
        int row=width-2*24;
        return HubSplit.split(row,20,PANE_LEFT_DP,PANE_RIGHT_DP,0.5f,HubSplit.centreLeft(width,20,24),0,0,8).right(row)+2*18;
    }
    @Override public ScooterService service(){return scooterService;}
    @Override public void toast(String text){if(text!=null&&!text.isEmpty())Toast.makeText(this,ChargeScheduleText.shown(text),Toast.LENGTH_LONG).show();}
    /** A display form that may be a Msg or plain text; null shows nothing. */
    private void toast(CharSequence text){if(text!=null)toast(text.toString());}
    @Override public void redraw(){
        if(destroyed||design==null||enteringMaintenance||startupRedirect)return;
        if(Looper.myLooper()!=Looper.getMainLooper()){handler.post(this::redraw);return;}
        cancelDeferredBodyRefresh();updateHeader();refreshBodyNow();
        refreshMaintenanceDot();
    }
    /**
     * The dock's Service dot while a maintenance check is due (owner answer Q39 B; orange, as Clean Home's Service row).
     * Home's subject list and Service's own rows draw theirs on every refresh.
     */
    private void refreshMaintenanceDot(){
        if(dock!=null)dock.refresh();
    }
    /** BLE packets update lock/pending state immediately; heavy screen refreshes share the 1 Hz pulse. */
    private void automaticRefresh(){
        if(destroyed||design==null)return;
        if(Looper.myLooper()!=Looper.getMainLooper()){handler.post(this::automaticRefresh);return;}
        if(!rebuilding&&dropAutoEndedRiding()){transitionRebuild(0);return;}
        if(riding!=null&&scooterService!=null)riding.observeLink(scooterService.ble.connected());
        observeConnectionFeedback(false);maybeAutoOpenRiding();maybeShowScheduleTestResult();maybeShowMissedStart();maybeShowChargeAlert();maybeInviteReport();maybeOfferConnectionReport();
        if (displayedSimulation != (scooterService != null && scooterService.simulating())) {
            handler.removeCallbacks(settingsRebuild); handler.post(settingsRebuild); return;
        }
        updateHeader();if(!resumed)return;
        long delay=Math.max(0,BODY_REFRESH_MS-(SystemClock.elapsedRealtime()-lastBodyRefreshMs));
        if(delay==0){cancelDeferredBodyRefresh();refreshBodyNow();}
        else if(!bodyRefreshScheduled){bodyRefreshScheduled=true;handler.postDelayed(deferredBodyRefresh,delay);}
    }
    private void cancelDeferredBodyRefresh(){handler.removeCallbacks(deferredBodyRefresh);bodyRefreshScheduled=false;}
    private void refreshBodyNow(){
        lastBodyRefreshMs=SystemClock.elapsedRealtime();if(homeScreen!=null)homeScreen.refresh();if(screen!=null)screen.refresh();if(dock!=null)dock.refresh();updateScan();
        for (ControlRow row : quickMenuRows) row.refresh();
        for (ChargeDoneRow row : quickChargeRows) row.refresh();
        if (choiceSheet != null) choiceSheet.refresh();
        if (rangeSourceSheet != null) rangeSourceSheet.refresh();
        if (chargeAlertSheet != null) chargeAlertSheet.refresh();
        if (chargeLiveSheet != null) chargeLiveSheet.refresh();
        if(resumed&&scooterService!=null&&scooterService.isRecording()&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!preferences.getBoolean("notification_prompted",false)){preferences.edit().putBoolean("notification_prompted",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_PERMISSION);}
        boolean awake=resumed&&preferences.getBoolean("keep_screen_awake",true)&&scooterService!=null&&scooterService.isRecording();
        // Riding mode always keeps the screen on: it's a handlebar display (riding-mode plan).
        awake|=resumed&&riding!=null&&screen instanceof RidingScreen;
        if(awake)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    /** Rebuild under a crossfade from the current window; a direction (+1 deeper, -1 back) brings the new page in from that side. */
    private void transitionRebuild(int direction){
        FrameLayout before=root;
        android.graphics.Bitmap previous=!destroyed&&!rebuilding&&before!=null&&before.isAttachedToWindow()?Motion.capture(before):null;
        rebuild();
        if(previous!=null){if(root!=before)Motion.crossfade(root,previous,direction,pageHeader,scroll);else previous.recycle();}
    }
    @Override public void rebuild(){
        if(enteringMaintenance||startupRedirect||DataMaintenance.active())return;
        if(destroyed||rebuilding)return;handler.removeCallbacks(settingsRebuild);rebuilding=true;
        displayedSimulation = scooterService != null && scooterService.simulating();
        closeQuickMenu();
        if(controlDialog!=null){controlDialog.dismiss();controlDialog=null;}
        captureScroll();if(screen!=null)screen.close();closeHomePane();if(dock!=null){dock.close();dock=null;}
        design=new Ui(this,darkTheme(),preferences.getFloat("text_scale",1f));
        setTheme(design.dark?R.style.VoltDark:R.style.VoltLight);
        expanded=design.wideLayout();
        root=new FrameLayout(this);root.setBackgroundColor(design.bg);
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|(design.dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        getWindow().setStatusBarColor(design.bg);getWindow().setNavigationBarColor(design.bg);
        root.setPadding(leftInset,topInset,rightInset,bottomInset);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int l,t,r,b;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());l=i.left;t=i.top;r=i.right;b=i.bottom;}
            else{l=insets.getSystemWindowInsetLeft();t=insets.getSystemWindowInsetTop();r=insets.getSystemWindowInsetRight();b=insets.getSystemWindowInsetBottom();}
            boolean changed=l!=leftInset||t!=topInset||r!=rightInset||b!=bottomInset;leftInset=l;topInset=t;rightInset=r;bottomInset=b;v.setPadding(l,t,r,b);
            if(changed&&horizontalHinge())handler.post(this::rebuild);return insets;
        });
        if(OnboardingScreen.needed(this,scooterService)){
            clearDashboardLock();profileTitle=null;scroll=null;pageFrame=null;pageHeader=null;
            onboarding=new OnboardingScreen(this,new OnboardingScreen.Listener(){public void onFinished(){onboarding=null;showPage(HubPages.HOME);rebuild();}});
            screen=onboarding;root.addView(onboarding.view(),new FrameLayout.LayoutParams(-1,-1));
            Motion.quietScrollbars(root);setContentView(root);root.requestApplyInsets();styleSystemBars();
            rebuilding=false;redraw();return;
        }
        onboarding=null;
        if(ridingRestore&&riding==null&&scooterService!=null){ridingRestore=false;if(RidingSession.Resume.restoresHome(ridingResume,scooterService.rideAutoEnds(),scooterService.rideLink(),scooterService.isRecording(),SystemClock.elapsedRealtime())){ridingResume=null;showPage(HubPages.HOME);scooterService.ble.telemetry.followSpeed(null);}else openRidingSession();}
        dropAutoEndedRiding();
        if(riding!=null&&scooterService!=null){
            // Riding mode fills the window: no header, tab bar or scroller (like onboarding).
            clearDashboardLock();profileTitle=null;scroll=null;pageFrame=null;pageHeader=null;
            screen=new RidingScreen(this,riding,this::riderClosedRiding);root.addView(screen.view(),new FrameLayout.LayoutParams(-1,-1));
            setContentView(root);root.requestApplyInsets();styleSystemBars();
            rebuilding=false;redraw();return;
        }
        // A maintenance tap deferred while riding mode or setup was up opens as soon as they close: before the header, so the
        // header is built for Maintenance reminders (back row and title), not for the Home they closed onto.
        if(maintenancePending&&!ridingRestore){pendMaintenance(false);showMaintenanceFromNotice();preferences.edit().putString("last_page",selectedPage).apply();}
        LinearLayout shell=column();root.addView(shell,new FrameLayout.LayoutParams(-1,-1));
        simulationNotice = design.text(T.mainSimulationBanner(), 12, true);
        simulationNotice.setTag("simulation-banner");
        simulationNotice.setTextColor(design.onAccent);
        simulationNotice.setBackgroundColor(design.accent);
        simulationNotice.setGravity(Gravity.CENTER);
        simulationNotice.setMinHeight(design.dp(48));
        simulationNotice.setPadding(design.dp(12), design.dp(6), design.dp(12), design.dp(6));
        simulationNotice.setContentDescription(T.mainSimulationDescription());
        simulationNotice.setOnClickListener(v -> {
            if (scooterService != null) { scooterService.setSimulation(false); transitionRebuild(0); }
        });
        simulationNotice.setVisibility(displayedSimulation ? View.VISIBLE : View.GONE);
        shell.addView(simulationNotice, new LinearLayout.LayoutParams(-1, -2));
        // The Hub has no tab bar or side rail (PR 11): the page has the window's full width; Home is the root of every stack.
        LinearLayout content=column();shell.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        HubNav.Args args=pageArgs;pageArgs=null;
        // Wide Clean Home: the scooter pane beside the page pane, which shows every page (hub layout plan 1.11, PR 13).
        if(twoPaneHome())buildPanes(content,args);
        else{
        LinearLayout header=makeHeader();
        if(horizontalHinge()){
            ScrollView upper=new ScrollView(this);upper.setFillViewport(false);upper.setClipToPadding(false);upper.addView(header);pageHeader=upper;
            int upperHeight=Math.max(1,fold.hinge.top-topInset);
            content.addView(upper,new LinearLayout.LayoutParams(-1,upperHeight));
            View spacer=new View(this);spacer.setBackgroundColor(design.bg);content.addView(spacer,new LinearLayout.LayoutParams(-1,Math.max(design.dp(12),fold.hinge.height())));
        }else{content.addView(header);pageHeader=header;}
        pageFrame=new FrameLayout(this);content.addView(pageFrame,new LinearLayout.LayoutParams(-1,0,1));
        screen=createScreen(selectedPage,args);
        if(horizontalHinge()&&(screen instanceof DashboardScreen||screen instanceof CleanHomeScreen)){
            // Tabletop posture: Home's header sits in the upper pane, the widgets (or Clean's lock, actions and subjects) below the hinge.
            header.setPadding(design.dp(expanded?24:18),design.dp(8),design.dp(expanded?24:18),design.dp(8));
            header.addView(screen instanceof CleanHomeScreen?((CleanHomeScreen)screen).takeHeader():((DashboardScreen)screen).takeHeader(),new LinearLayout.LayoutParams(-1,-2));
        }
        View screenView=screen.view();if(screenView.getParent() instanceof ViewGroup)((ViewGroup)screenView.getParent()).removeView(screenView);
        if(screenView instanceof ScrollView){scroll=(ScrollView)screenView;}
        else{scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setPadding(design.dp(expanded?24:18),design.dp(6),design.dp(expanded?24:18),design.dp(16));scroll.addView(screenView,new ScrollView.LayoutParams(-1,-2));}
        scrollPage=selectedPage;pageFrame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        if(screen instanceof DashboardScreen){
            // Widgets Home: the dock floats over the page where the tab bar was; pad the scroller so the last card scrolls clear of it.
            DashboardScreen dashboard=(DashboardScreen)screen;
            dock=new HubDock(this,expanded,dashboard::editing);
            pageFrame.addView(dock.view(),dock.layoutParams());
            scroll.setClipToPadding(false);
            scroll.setPadding(scroll.getPaddingLeft(),scroll.getPaddingTop(),scroll.getPaddingRight(),scroll.getPaddingBottom()+dock.clearance());
        }
        }
        Motion.quietScrollbars(root);setContentView(root);root.requestApplyInsets();
        styleSystemBars();
        int restore=scrollPositions.containsKey(selectedPage)?scrollPositions.get(selectedPage):0;Motion.scrollBeforeDraw(scroll,restore);
        // A search result names the row its page brings on screen (after the restored scroll) and flashes once.
        if(args!=null&&args.anchor!=null)revealAnchor(screen,scroll,args.anchor);
        rebuilding=false;redraw();
        showUpdateCheck();
        // The one-time What's new sheet about Home styles: checked once per rebuild, never on a telemetry refresh.
        handler.post(this::maybeShowWhatsNew);
    }
    /** The What's new sheet while it shows; closed with the activity. */
    private AlertDialog whatsNewSheet;
    /**
     * Shows the one-time What's new sheet (Joel 6, 10) when HomeStyle.showWhatsNew says so: an updated installation
     * that never saw it, on Home (Clean or the widget dashboard), resumed, with neither setup nor riding mode open,
     * whether or not the owner already chose a style (that only hides the offer to switch: HomeStyle.offerSwitch).
     * The sheet records itself as shown (committed) before it appears, so a crash or rotation never shows it again.
     */
    private void maybeShowWhatsNew(){
        if(destroyed||rebuilding||design==null||whatsNewSheet!=null&&whatsNewSheet.isShowing())return;
        java.util.Map<String,?> all;
        try{all=preferences.getAll();}catch(RuntimeException e){return;}
        Object origin=all.get(HomeStyle.ORIGIN_KEY);
        // On Home, either style, and not while it is being edited.
        boolean onHome=HubPages.HOME.equals(selectedPage)&&(screen instanceof DashboardScreen||screen instanceof CleanHomeScreen)&&!screen.busy();
        if(!HomeStyle.showWhatsNew(origin instanceof String?(String)origin:null,Boolean.TRUE.equals(all.get(HomeStyle.WHATS_NEW_SHOWN_KEY)),onboarding!=null,riding!=null,onHome,resumed))return;
        whatsNewSheet=WhatsNewSheet.show(this,preferences);
    }
    /**
     * The wide Clean Home (hub layout plan 1.11, PR 13) on this window: the Clean style on a wide window (Ui.wideLayout, so
     * large text that keeps the phone layout keeps it) with room for both panes, not in tabletop posture (Home's header
     * then sits above the hinge, as on one pane), and not where a subject's name once failed to fit the page pane.
     */
    private boolean twoPaneHome(){
        if(!expanded||horizontalHinge()||!HomeStyle.CLEAN.equals(HomeStylePrefs.of(preferences)))return false;
        Configuration c=getResources().getConfiguration();
        return HubSplit.fits(c.screenWidthDp-2*24,20,PANE_LEFT_DP,PANE_RIGHT_DP)&&!paneKey().equals(onePaneHomeAt);
    }
    /** This window's size, text size and language, as MoreScreen keys its one-pane fallback. */
    private String paneKey(){
        Configuration c=getResources().getConfiguration();
        return c.screenWidthDp+"x"+c.screenHeightDp+"@"+design.textScale*c.fontScale+"/"+Fmt.displayLocale().toLanguageTag();
    }
    /**
     * The wide Clean Home's two panes: the scooter pane at the left (Clean Home in pane mode: header, top area, Lock bar,
     * quick actions and the Service card), which page changes never rebuild, and the page pane at the right (Home's search
     * field and subjects, or the open page with a back link). The gutter sits on the window's centre line or the hinge.
     */
    private void buildPanes(LinearLayout content,HubNav.Args args){
        clearDashboardLock();profileTitle=null;connectionTitle=null;liveStatsTitle=null;
        homeScreen=new CleanHomeScreen(this,homeActions(),true);
        paneLocks=dashboardLocks.size();
        ScrollView left=new ScrollView(this);left.setTag(TAG_SCOOTER_PANE);left.setFillViewport(true);left.setClipToPadding(false);
        left.setPadding(0,design.dp(6),0,design.dp(16));
        View home=homeScreen.view();if(home.getParent() instanceof ViewGroup)((ViewGroup)home.getParent()).removeView(home);
        left.addView(home,new ScrollView.LayoutParams(-1,-2));
        FrameLayout right=new FrameLayout(this);right.setTag(TAG_PAGE_PANE);
        rightPage=column();right.addView(rightPage,new FrameLayout.LayoutParams(-1,-1));
        // The last rung of the subjects' narrow-label ladder: one pane at this size, text size and language from now on.
        // The key is this layout's, taken now; the posted callback is dropped once these panes are gone or the window
        // changed (a fold, a resize, a text-size change), so it never marks a size that did not fail.
        final String key=paneKey();
        final HubPanes[] built=new HubPanes[1];
        HubPanes panes=new HubPanes(this,design,left,right,PANE_LEFT_DP,PANE_RIGHT_DP,0.5f,verticalHinge()?fold.hinge:null,
                ()->screen instanceof HubHomePane&&((HubHomePane)screen).wordsBreak(),
                ()->{
                    if(destroyed||rebuilding||built[0]==null||!built[0].isAttachedToWindow()||!key.equals(paneKey()))return;
                    onePaneHomeAt=key;rebuild();
                });
        built[0]=panes;
        panes.setPadding(design.dp(24),0,design.dp(24),0);
        content.addView(panes,new LinearLayout.LayoutParams(-1,0,1));
        pageFrame=right;
        fillPagePane(args);
    }
    /** View tags of the wide Home's panes. */
    static final String TAG_SCOOTER_PANE="hub-scooter-pane",TAG_PAGE_PANE="hub-page-pane";
    /** Builds the shown page into the page pane: its header (not on Home), then its screen in the pane's scroller. */
    private void fillPagePane(HubNav.Args args){
        rightPage.removeAllViews();
        pageHeader=null;liveStatsTitle=null;
        LinearLayout header=HubPages.HOME.equals(selectedPage)?null:makePaneHeader();
        if(header!=null){rightPage.addView(header,new LinearLayout.LayoutParams(-1,-2));pageHeader=header;}
        paneHosted=true;
        try{screen=createScreen(selectedPage,args);}finally{paneHosted=false;}
        View screenView=screen.view();if(screenView.getParent() instanceof ViewGroup)((ViewGroup)screenView.getParent()).removeView(screenView);
        if(screenView instanceof ScrollView){
            scroll=(ScrollView)screenView;
            // A page with its own scroller pads its content as on a phone: the header lines up with it.
            if(header!=null)header.setPaddingRelative(design.dp(18),header.getPaddingTop(),design.dp(18),header.getPaddingBottom());
        }else{scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setPadding(0,design.dp(6),0,design.dp(16));scroll.addView(screenView,new ScrollView.LayoutParams(-1,-2));}
        scrollPage=selectedPage;
        rightPage.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    }
    /**
     * Another page in the wide Home's page pane, sliding in by depth; the scooter pane stays as it is. The page's own lock
     * (Security's) goes with the page; the scooter pane's stays registered.
     */
    private void swapPagePane(int direction){
        if(controlDialog!=null){controlDialog.dismiss();controlDialog=null;}
        if(screen!=null)screen.close();
        while(dashboardLocks.size()>paneLocks){DashboardLockViews lock=dashboardLocks.remove(dashboardLocks.size()-1);if(lock.lockControl!=null)lock.lockControl.stopMotion();}
        if(dashboardLocks.isEmpty())clearDashboardLock();else loadLockViews(dashboardLocks.get(0));
        HubNav.Args args=pageArgs;pageArgs=null;
        LinearLayout pane=rightPage;
        Motion.page(pane,direction,()->fillPagePane(args));
        Motion.quietScrollbars(pane);
        int restore=scrollPositions.containsKey(selectedPage)?scrollPositions.get(selectedPage):0;Motion.scrollBeforeDraw(scroll,restore);
        if(args!=null&&args.anchor!=null)revealAnchor(screen,scroll,args.anchor);
        redraw();
        showUpdateCheck();
    }
    /** Closes the wide Home's scooter pane, if this layout has one. */
    private void closeHomePane(){
        if(homeScreen!=null){homeScreen.close();homeScreen=null;}
        rightPage=null;
    }
    /**
     * The screen for a page. Home shows Clean Home or the widget dashboard (home_style); the scooter subjects (Battery &
     * charging, Controls, Lights & sound, Security, Service) and their pages, the search page, the Ride pages, Data and its
     * storage page, Settings and its sub-pages each have a screen. args are the page's one-shot arguments (ScreenHost.open), or null.
     */
    private AppScreen createScreen(String page,HubNav.Args args){
        if(HubPages.SETTINGS_HOME.equals(page))return new HomeStyleScreen(this);
        // Clean Home or the widget dashboard: the owner's saved style; absent or unknown is Widgets.
        // The wide Home's page pane at Home: the search field and the subjects (the scooter pane is beside it).
        if(HubPages.HOME.equals(page)&&paneHosted)return new HubHomePane(this,()->{open(HubPages.SEARCH);focusPageSearch();});
        if(HubPages.HOME.equals(page))return HomeStyle.CLEAN.equals(HomeStylePrefs.of(preferences))?new CleanHomeScreen(this,homeActions()):new DashboardScreen(this,homeActions());
        if(HubPages.SEARCH.equals(page))return searchPage();
        switch(HubPages.subjectOf(page)){
            case HubPages.BATTERY:case HubPages.CONTROLS:case HubPages.LIGHTS:case HubPages.SECURITY:case HubPages.SERVICE:return scooterPage(page,args);
            default:break;
        }
        if(scooterService==null)return new LoadingScreen();
        switch(page){
            // In the wide Home's page pane, Live Stats and All rides take one pane (paneHosted).
            case HubPages.DATA:return new StatsScreen(this,paneHosted);
            case HubPages.RIDE:return new RidesScreen(this,RidesScreen.SUMMARY,paneHosted);
            case HubPages.RIDE_RIDES:{
                RidesScreen rides=new RidesScreen(this,RidesScreen.ALL,paneHosted);
                // A page argument names a ride to show (Home's last ride); it opens once the page is on screen.
                String ride=args==null?null:args.ride;
                if(ride!=null)handler.post(()->{if(screen==rides)rides.openRide(ride);});
                return rides;
            }
            case HubPages.RIDE_RECORDING:return new HubPageScreen(this,new MoreScreen(this,MoreScreen.RECORDING_SECTION,true),new RidesScreen(this,RidesScreen.SENSORS));
            case HubPages.RIDE_RIDING:return new HubRidingSettings(this);
            case HubPages.SETTINGS:return new MoreScreen(this);
            default:break;
        }
        // Settings' sub-pages and Data & history: More's section as the page's body.
        String section=HubRoutes.moreSectionOf(page);
        return section!=null?new MoreScreen(this,section,true):new MoreScreen(this);
    }
    /** A scooter subject's page (hub layout plan, PRs 3 and 4): Battery & charging, Controls, Lights & sound, Security, Service. */
    private AppScreen scooterPage(String page,HubNav.Args args){
        ControlsPages.Spec spec=ControlsPages.spec(page);
        // Pages with More's sections need the service (as the More tab does); their rows read its settings.
        boolean moreHosted=page.equals(HubPages.SECURITY)||page.equals(HubPages.BATTERY_HISTORY)||page.startsWith(HubPages.SERVICE+".")&&!page.equals(HubPages.SERVICE_MAINTENANCE);
        if(moreHosted&&scooterService==null)return new LoadingScreen();
        switch(page){
            case HubPages.BATTERY:return new HubPageScreen(this,new HubBatterySummary(this),new ControlsScreen(spec));
            // Usable ride energy (Experimental) below the page's rows: Clean Home has no widgets to hold it (part 8).
            case HubPages.BATTERY_ESTIMATES:return new HubPageScreen(this,new ControlsScreen(spec),new UsableEnergyPanel(this,homeActions()));
            case HubPages.SECURITY:return new HubPageScreen(this,securityLock(),new ControlsScreen(spec),new MoreScreen(this,"Security",true));
            case HubPages.BATTERY_HISTORY:return new MoreScreen(this,MoreScreen.CHARGING_SECTION,true);
            case HubPages.SERVICE_REPORTS:return new MoreScreen(this,MoreScreen.REPORTS_SECTION,true);
            case HubPages.SERVICE_COMPATIBILITY:{
                // The one-shot args choose the route; a rebuild (rotation, theme, text size) keeps it.
                if(args!=null)compatibilityRecovery=args.recovery;
                return new HubCompatibilityPage(this,compatibilityRecovery);
            }
            case HubPages.SERVICE_EXPERIMENTAL:{
                MoreScreen lab=new MoreScreen(this,"Lab",true);
                // A shortcut or an Experimental entry names its control: its try opens once the page is on screen.
                ScooterCommands.Feature inspect=args==null||args.inspect==null?null:ScooterCommands.find(args.inspect);
                if(inspect!=null)handler.post(()->{if(screen==lab)lab.inspectQuickFeature(inspect);});
                return lab;
            }
            default:break;
        }
        if(page.equals(HubPages.SERVICE))return new HubListScreen(this,HubSubjects.service(this,this::openDiagnostics));
        if(page.equals(HubPages.SERVICE_MAINTENANCE)){
            // The page header shows its title and back link, so the screen leaves out its own.
            MaintenanceScreen maintenance=new MaintenanceScreen(this,null,expanded&&!paneHosted);
            return new AppScreen(){
                @Override public View view(){return maintenance.view();}
                @Override public void refresh(){maintenance.refresh();}
                @Override public void close(){maintenance.close();}
            };
        }
        // Every other scooter page has its rows in ControlsPages; a page without would show the search over every row.
        return new ControlsScreen(spec!=null?spec:ControlsPages.spec(HubPages.SEARCH));
    }
    /**
     * The search page (Home's search button; hub layout plan 1.12, PR 12): pages, settings and controls, live readings
     * and rides, each labelled with its subject; Home's seven subjects while the field is empty. Controls follow the same
     * rules as their pages (searchGates), so search never lists one the model doesn't offer.
     */
    private AppScreen searchPage(){
        return new HubSearchScreen(this,searchGates(),searchQuery,query->searchQuery=query,this::openSearchResult);
    }
    /** The scooter pages' rules for search: a row shows where its page would show it, a Lab try as Experimental. */
    private HubSearch.Gates searchGates(){
        return new HubSearch.Gates(){
            @Override public boolean row(String id){return searchRowOffered(id);}
            @Override public boolean lab(String key){return labOffered(key);}
            @Override public boolean page(String page){return pageShown(page);}
            // As the scooter pages' search: a hand-built row's main_search_* words, a control's short label.
            @Override public CharSequence[] words(String id){
                String row=rowSearchWords(id);if(row!=null)return new CharSequence[]{row};
                if(HubSearchTables.rowWords(id)!=null)return null;
                return new CharSequence[]{shortLabel(ScooterCommands.find(id))};
            }
        };
    }
    /** A feature row or a hand-built row (HubSearchTables.ROWS) shows on its page now (ControlsScreen.applyFilter's rules). */
    private boolean searchRowOffered(String id){
        if(id.equals(ScooterMenuLayout.CLOCK_SYNC))return offered(KEY_CLOCK_SYNC);
        if(id.equals(ScooterMenuLayout.SCHEDULE_TEST))return scheduleTestOffered();
        if(id.equals(ScooterMenuLayout.TOP_SPEED))return topSpeedCard();
        if(id.equals(ScooterMenuLayout.CHARGE_ALERT)||id.equals(ScooterMenuLayout.LOW_ALERT))return chargeAlertsOffered();
        if(id.equals(HubSearchTables.ROW_CHARGE_DONE_BY))return chargeDoneOffered();
        if(HubSearchTables.rowWords(id)!=null)return true;
        // The lock bar always shows; the handlebar PIN and proximity unlock where the model has them.
        if(ControlsPages.NEVER.contains(id))return id.equals("lock")||offered(id);
        return rowOffered(id);
    }
    /**
     * A scooter sub-page has a row or an Experimental entry to show on this model: its link on the subject page shows
     * (ControlsPages.linkShown, as ControlsScreen.rowShown decides it). Pages the scooter rows don't build always show.
     */
    private boolean pageShown(String page){
        java.util.List<String> lab=modelLab();
        return ControlsPages.linkShown(page,id->!isFeature(id)||rowOffered(id)||id.equals("maximum_speed")&&topSpeedCard()||labOffered(id,lab));
    }
    private static boolean isFeature(String key){
        for(ScooterCommands.Feature f:ScooterCommands.catalog())if(f.key.equals(key))return true;
        return ScooterCommands.oemOnly(key);
    }
    /**
     * A search result's tap: a reading opens Data's Readings filtered to it, a ride opens it in All rides, a control the
     * Lab tries where no page lists it opens its try, and everything else opens its page with the row brought on screen.
     */
    private void openSearchResult(HubSearch.Entry entry){
        switch(entry.target){
            case READING:{
                // As Service's Diagnostics row: a deliberate route wins over Live Stats' expired return state.
                expireStatsSection(true);markStatsActive();
                // The reading's key, not its shown name: a stored query stays locale-independent, and Live Stats' filter matches row keys.
                preferences.edit().putString("stats_section","Readings").putString("stats_query",entry.anchor).apply();
                scrollPositions.put(HubPages.DATA,0);StatsScreen.clearSavedScroll();
                open(HubPages.DATA);return;
            }
            case RIDE:open(entry.page,HubNav.Args.ride(entry.anchor));return;
            case INSPECT:open(entry.page,HubNav.Args.inspect(entry.anchor));return;
            default:open(entry.page,entry.anchor==null?null:HubNav.Args.anchor(entry.anchor));
        }
    }
    /**
     * Brings a search result's row on screen once the page is laid out: the page names it (AppScreen.anchor), or its view
     * tag finds it; then it flashes once (not with reduced motion). Nothing happens when the row isn't shown.
     */
    private void revealAnchor(AppScreen page,ScrollView pageScroll,String anchor){
        Motion.beforeNextDraw(pageScroll,()->{
            if(destroyed||screen!=page||scroll!=pageScroll)return;
            View target=page.anchor(anchor);
            if(target==null)target=page.view().findViewWithTag(anchor);
            if(target==null||!target.isShown())return;
            int y=0;
            for(View at=target;at!=null&&at!=pageScroll;at=at.getParent() instanceof View?(View)at.getParent():null)y+=at.getTop();
            int visible=pageScroll.getHeight()-pageScroll.getPaddingTop()-pageScroll.getPaddingBottom();
            int top=Math.max(0,y-pageScroll.getPaddingTop()-Math.max(design.dp(24),(visible-target.getHeight())/3));
            pageScroll.scrollTo(0,top);
            HubSearchScreen.flash(target,design);
        },null);
    }
    /** Security's lock bar: Home's compact lock (Connect while offline), registered so updateHeader drives it. */
    private AppScreen securityLock(){
        // makeCompactHeader dropped any earlier lock before this page was built.
        View lock=makeDashboardLock(false,true);lock.setTag("hub-security-lock");
        LinearLayout part=column();
        float scale=Math.max(1f,design.textScale*getResources().getConfiguration().fontScale);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,Math.round(design.dp(64)*scale)-design.dp(10));params.bottomMargin=design.dp(12);
        part.addView(lock,params);
        return new AppScreen(){
            @Override public View view(){return part;}
            @Override public void refresh(){}
        };
    }
    /** Service's Diagnostics & connection activity row: Data is their one home, so it opens Live Stats on Diagnostics. */
    private void openDiagnostics(){
        // A deliberate shortcut wins over Live Stats' expired return state:
        // with the away timer cleared, leaving() keeps Diagnostics instead of resetting to Overview.
        expireStatsSection(true);markStatsActive();
        preferences.edit().putString("stats_section","Diagnostics").apply();
        scrollPositions.put(HubPages.DATA,0);StatsScreen.clearSavedScroll();
        open(HubPages.DATA);
    }
    /** What either Home asks of the activity: the lock, navigation, quick actions and the header's buttons. */
    private DashboardScreen.Actions homeActions(){
        return new DashboardScreen.Actions(){public View lock(boolean showDisconnect){return makeDashboardLock(showDisconnect,false);}public View lock(boolean showDisconnect,boolean compact){return makeDashboardLock(showDisconnect,compact);}public void clearLock(){clearDashboardLock();}public void navigate(String page){open(page);}public void quickAction(String id){MainActivity.this.quickAction(id);}public void rename(){renameScooter();}public void connectionDetails(){showConnectionDetails();}public void startRide(){RideRecordingApp.of(MainActivity.this).start();}
            // The search page (HubSearchScreen) with its field focused; Clean's and the Widgets header's search button.
            public void openSearch(){open(HubPages.SEARCH);focusPageSearch();}
            public void openSettings(){open(HubPages.SETTINGS);}
            // Switch scooter: the My scooters page (its list has Use and Connect), as Home's other routes there do.
            public void switchScooter(){open(HubPages.SETTINGS_SCOOTERS);}};
    }
    /** Puts the cursor in the page's first search field and shows the keyboard (Home's search button). */
    private void focusPageSearch(){
        View page=screen==null?null:screen.view();
        EditText field=page==null?null:firstEditText(page);
        if(field==null)return;
        field.requestFocus();
        field.post(()->{android.view.inputmethod.InputMethodManager input=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(input!=null&&field.isAttachedToWindow())input.showSoftInput(field,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);});
    }
    private static EditText firstEditText(View view){
        if(view instanceof EditText)return (EditText)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){EditText found=firstEditText(group.getChildAt(i));if(found!=null)return found;}}
        return null;
    }
    private void styleSystemBars(){
        // Samsung's Window getter can dereference a missing decor during onCreate.
        // Post through this root so styling runs only after it has been attached.
        if(Build.VERSION.SDK_INT>=30){
            FrameLayout currentRoot=root;boolean dark=design.dark;
            currentRoot.post(()->{
                if(destroyed||root!=currentRoot)return;
                WindowInsetsController controller=currentRoot.getWindowInsetsController();
                if(controller!=null){int mask=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;controller.setSystemBarsAppearance(dark?0:mask,mask);}
                // Riding mode hides the navigation bar (a swipe shows it for a moment); the status bar stays for the clock.
                if(controller!=null&&riding!=null){controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);controller.hide(WindowInsets.Type.navigationBars());}
                else if(controller!=null)controller.show(WindowInsets.Type.navigationBars());
            });
        }else if(riding!=null){
            View decor=getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
    /** Opens riding mode. Stopped, it can lock and record through the same paths as Home (owner, Nov 8); opening it does neither. */
    @Override public void openRiding(){
        if(scooterService==null||riding!=null||onboarding!=null)return;
        captureScroll();openRidingSession();transitionRebuild(0);
    }
    /** Creates the session, makes it the open one, then asks for its prior: a cached answer arrives at once. */
    private void openRidingSession(){
        String id=ridingProfileId();
        RidingSession session=new RidingSession(id,new RidingConfig(preferences,id).powerScale(),SystemClock.elapsedRealtime(),ridingResume,scooterService.rideAutoEnds(),scooterService.ble.telemetry.followedSpeed());
        ridingResume=null;
        riding=session;
        session.observeLink(scooterService.ble.connected());
        if(scooterService.simulating()){
            // Sample data has no ride history: a synthetic battery model, so Range at this pace shows (never persisted).
            session.setPrior(LiveRangeEstimator.Prior.synthetic(18,1000,null));
        }else session.requestPrior(answer->scooterService.requestLearnedRangeEstimate(result->answer.accept(LiveRangeEstimator.Prior.of(result))),
                ()->destroyed||scooterService==null||riding!=session?null:ridingProfileId());
    }
    private String ridingProfileId(){
        ScooterProfiles.Profile profile=scooterService.profiles==null?null:scooterService.profiles.active();
        return profile==null?"unconfigured":profile.id;
    }
    /** Auto ride mode: opens at most once per connection, only on what's safe to cover. */
    private void maybeAutoOpenRiding(){
        if(scooterService==null||startupRedirect||enteringMaintenance||rebuilding)return;
        boolean clear=AutoStartPolicy.screenClear(resumed,hasWindowFocus(),riding!=null||ridingRestore,onboarding!=null,inSetupOrEditor(),getCurrentFocus() instanceof EditText);
        if(scooterService.takeAutoRideMode(clear))openRiding();
        else if(!autoRecordLocationCancelled&&scooterService.offersAutoRecordLocation(clear))requestAutoRecordLocation(null);
    }
    /** Pages with setup flows, tests and text fields (More), and screens with an editor or sub-page open (AppScreen.busy). */
    private boolean inSetupOrEditor(){
        return !HubPages.clearForRiding(nav.current())||(screen!=null&&screen.busy());
    }
    /** The rider's own exit (the back gesture or riding mode's back button) holds auto ride mode. */
    private void riderClosedRiding(){if(riding!=null&&scooterService!=null)scooterService.autoStart.riderLeftRidingMode();closeRiding();}
    private void closeRiding(){
        if(riding==null)return;
        riding.close();riding=null;ridingRestore=false;showPage(HubPages.HOME);if(scooterService!=null)scooterService.ble.telemetry.followSpeed(null);transitionRebuild(0);
    }
    /** A recording that ended by itself closes the riding mode that was open for it, with no auto-open hold. True when it did. */
    private boolean dropAutoEndedRiding(){
        if(riding==null||scooterService==null||!riding.autoEnded(scooterService.rideAutoEnds()))return false;
        dropRiding();return true;
    }
    /** Closes to Home with no auto-open hold: Volt's decision, not the rider's. */
    private void dropRiding(){riding.close();riding=null;ridingRestore=false;ridingResume=null;showPage(HubPages.HOME);scooterService.ble.telemetry.followSpeed(null);}
    private void clearDashboardLock(){for(DashboardLockViews lock:dashboardLocks)if(lock.lockControl!=null)lock.lockControl.stopMotion();dashboardLocks.clear();if(lockControl!=null)lockControl.stopMotion();lockTitle=null;lockControl=null;connectionTitle=null;connectionAction=null;offlineTitle=null;offlineDetail=null;lockReady=null;lockOffline=null;compactLock=false;}
    private LinearLayout makeHeader(){
        if(!selectedPage.equals(HubPages.HOME))return makeCompactHeader();
        // Home's header (status line, scooter name and edit pencil) belongs to the dashboard toolbar.
        clearDashboardLock();profileTitle=null;return column();
    }
    /** Volt lock card: a full-width bar you tap anywhere on, or slide, to lock and unlock. */
    private View makeDashboardLock(boolean showDisconnect,boolean compact){
        if(compact)return makeCompactLock(showDisconnect);
        showDashboardDisconnect=showDisconnect;compactLock=false;
        LinearLayout content=column();
        lockReady=column();
        lockTitle=design.text(T.mainLockTitle(),15,true);
        // Keep the state on one line; the slider and lock glyph already identify the control.
        lockTitle.setMaxLines(1);lockTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lockTitle.setAutoSizeTextTypeUniformWithConfiguration(Math.round(design.sp(12)),Math.round(design.sp(15)),1,android.util.TypedValue.COMPLEX_UNIT_PX);
        LinearLayout head=new LockHeading(lockTitle);
        ((LinearLayout)lockReady).addView(head);
        lockControl=new LockControl();
        LinearLayout.LayoutParams bar=new LinearLayout.LayoutParams(-1,design.dp(58));bar.topMargin=design.dp(10);
        ((LinearLayout)lockReady).addView(lockControl,bar);
        content.addView(lockReady,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout offline=column();lockOffline=offline;
        View square=design.iconSquare("lock",design.dim);
        LinearLayout words=column();
        offlineTitle=design.text(T.mainLockStateUnverified(),14,true);words.addView(offlineTitle);
        offlineDetail=design.caption("");offlineDetail.setPadding(0,design.dp(2),0,0);offlineDetail.setVisibility(View.GONE);words.addView(offlineDetail);
        LinearLayout line=new LockOfflineStatus(square,words,offlineTitle,design.icon("chevron",15,design.muted));
        line.setMinimumHeight(design.dp(48));
        line.setOnClickListener(v->showConnectionDetails());
        offline.addView(line);
        content.addView(offline,new LinearLayout.LayoutParams(-1,-2));
        // Connect/Cancel are always available; Disconnect is a per-widget choice.
        connectionAction=design.button(T.mainActionConnect(),this::connect);
        connectionAction.setMaxLines(1);connectionAction.setEllipsize(android.text.TextUtils.TruncateAt.END);
        connectionAction.setPadding(design.dp(6),connectionAction.getPaddingTop(),design.dp(6),connectionAction.getPaddingBottom());
        connectionAction.setAutoSizeTextTypeUniformWithConfiguration(Math.round(design.sp(12)),Math.round(design.sp(14)),1,android.util.TypedValue.COMPLEX_UNIT_PX);
        content.addView(connectionAction);
        return registerDashboardLock(content);
    }
    /**
     * Lock bar: one 48 dp row. Offline it shows the last known state with Connect (or Cancel);
     * connected, the same tap-or-slide switch fills the row with its state drawn inside.
     */
    private View makeCompactLock(boolean showDisconnect){
        showDashboardDisconnect=showDisconnect;compactLock=true;
        // The switch draws Locked/Unlocked itself; this detached label only keeps updateHeader uniform.
        lockTitle=design.text(T.mainLockTitle(),15,true);
        lockControl=new LockControl();lockControl.compact=true;lockReady=lockControl;
        View square=design.iconSquare("lock",design.dim);
        LinearLayout words=column();
        offlineTitle=design.text(T.mainLockStateUnknown(),16,true);offlineTitle.setTypeface(design.semibold());
        offlineDetail=design.text("",13,false);offlineDetail.setTextColor(design.muted);offlineDetail.setVisibility(View.GONE);
        for(TextView line:new TextView[]{offlineTitle,offlineDetail}){
            // Saira's tall metrics need no extra spacing. The detail stays one line under a one-word state.
            line.setLineSpacing(0,1);line.setMaxLines(1);line.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.addView(line,new LinearLayout.LayoutParams(-1,-2));
        }
        // The state alone (Lock state unknown) is never cut: it wraps between whole words onto a second line,
        // at the same size, which the 54 dp lock bar cell still holds (volt:max="220dp@16sp*2"). The ellipsis stays a last resort.
        offlineTitle.setMaxLines(2);offlineTitle.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
        offlineTitle.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        LinearLayout offline=new CompactLockStatus(square,words,offlineTitle);lockOffline=offline;
        offline.setOnClickListener(v->showConnectionDetails());
        connectionAction=design.button(T.mainActionConnect(),this::connect);
        connectionAction.setMaxLines(1);
        connectionAction.setPadding(design.dp(16),0,design.dp(16),0);
        connectionAction.setMinWidth(design.dp(48));
        setConnectionActionBackground(connectionAction.getBackground());
        LinearLayout content=new CompactLockRow(offline,offlineTitle,connectionAction);
        // The row fills its frameless cell: connected, the slider is the whole card; offline,
        // the row draws its own card. Every control keeps the full cell height as its touch target.
        content.addView(offline,new LinearLayout.LayoutParams(0,-2,1));
        content.addView(lockControl,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout.LayoutParams action=new LinearLayout.LayoutParams(-2,-1);action.setMarginStart(design.dp(10));
        content.addView(connectionAction,action);
        return registerDashboardLock(content);
    }
    /**
     * Lock bar row. When the status (its state or its detail line) and the worded action do not both
     * fit, the action becomes a 48 dp icon button (its text and description stay for accessibility);
     * after that the state wraps onto a second line. The status drops its own icon square first, so
     * that one goes before the words.
     */
    private final class CompactLockRow extends LinearLayout {
        private final View status;private final TextView title,action;
        private final android.text.method.TransformationMethod words;
        private final android.graphics.drawable.Drawable glyph;
        private boolean iconOnly;
        private final Paint card=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds=new RectF();
        CompactLockRow(View status,TextView title,TextView action){
            super(MainActivity.this);this.status=status;this.title=title;this.action=action;
            setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setMinimumHeight(design.dp(48));
            words=action.getTransformationMethod();
            glyph=new android.graphics.drawable.Drawable(){
                @Override public void draw(Canvas canvas){
                    android.graphics.Rect b=getBounds();
                    // Cancel and Disconnect close the link; Connect and Allow Nearby devices start it.
                    String key=LINK_CANCEL.equals(linkActions.get(action))||LINK_DISCONNECT.equals(linkActions.get(action))?"close":"bluetooth";
                    design.drawIcon(canvas,key,action.getCurrentTextColor(),b.exactCenterX(),b.exactCenterY(),b.width());
                }
                @Override public void setAlpha(int alpha){}
                @Override public void setColorFilter(android.graphics.ColorFilter filter){}
                @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
            };
            glyph.setBounds(0,0,design.dp(20),design.dp(20));
        }
        /** Offline or connecting, the row is its own surface card; connected, the slider is. */
        @Override protected void dispatchDraw(Canvas canvas){
            if(status.getVisibility()!=GONE){
                card.setColor(design.surface);bounds.set(0,0,getWidth(),getHeight());
                float r=Math.min(design.dp(20),getHeight()/2f);canvas.drawRoundRect(bounds,r,r,card);
            }
            super.dispatchDraw(canvas);
        }
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            // Offline content sits the same inset from the sides as the 40 dp square and pill from top and bottom.
            int side=status.getVisibility()!=GONE?design.dp(COMPACT_LOCK_INSET):0;
            if(getPaddingLeft()!=side||getPaddingRight()!=side)setPadding(side,0,side,0);
            int width=MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight();
            boolean icon=false;
            if(MeasureSpec.getMode(widthSpec)!=MeasureSpec.UNSPECIFIED&&action.getVisibility()!=GONE){
                // Natural worded width from the text itself: toggling and re-measuring would request layout every pass.
                float worded=Math.max(design.dp(48),action.getPaint().measureText(action.getText().toString())+design.dp(32));
                TextView detail=(TextView)((ViewGroup)title.getParent()).getChildAt(1);
                float need=status.getVisibility()!=GONE?Math.max(title.getPaint().measureText(title.getText().toString()),
                        detail.getVisibility()!=GONE?detail.getPaint().measureText(detail.getText().toString()):0):design.dp(96);
                icon=width-worded-design.dp(10)<need;
            }
            if(icon!=iconOnly){
                iconOnly=icon;
                action.setTransformationMethod(icon?new android.text.method.TransformationMethod(){
                    @Override public CharSequence getTransformation(CharSequence source,View view){return "";}
                    @Override public void onFocusChanged(View view,CharSequence source,boolean focused,int direction,android.graphics.Rect previous){}
                }:words);
                action.setPadding(design.dp(icon?14:16),action.getPaddingTop(),design.dp(icon?14:16),action.getPaddingBottom());
                action.setCompoundDrawablesRelative(icon?glyph:null,null,null,null);
                action.setCompoundDrawablePadding(0);
                LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)action.getLayoutParams();
                p.setMarginStart(design.dp(icon?4:10));p.resolveLayoutDirection(getLayoutDirection());
                if(!icon)action.setTooltipText(null);
            }
            // The icon names its action on long press; the description follows Connect/Cancel/Disconnect.
            if(icon&&!android.text.TextUtils.equals(action.getTooltipText(),action.getContentDescription()))
                action.setTooltipText(action.getContentDescription());
            super.onMeasure(widthSpec,heightSpec);
        }
    }
    /** Lock bar inset (dp) between its cell edge and the visible offline content or button pill. */
    private static final int COMPACT_LOCK_INSET=7;
    /** A Lock bar button's pill, inset top and bottom inside its full-height touch target. */
    private android.graphics.drawable.Drawable compactPill(android.graphics.drawable.Drawable fill){
        return new android.graphics.drawable.InsetDrawable(fill,0,design.dp(COMPACT_LOCK_INSET),0,design.dp(COMPACT_LOCK_INSET));
    }
    private void setConnectionActionBackground(android.graphics.drawable.Drawable fill){
        int left=connectionAction.getPaddingLeft(),right=connectionAction.getPaddingRight();
        connectionAction.setBackground(compactLock?compactPill(fill):fill);
        // InsetDrawable supplies vertical padding but also resets both horizontal sides to zero.
        if(compactLock)connectionAction.setPadding(left,connectionAction.getPaddingTop(),right,connectionAction.getPaddingBottom());
    }
    /** Offline lock bar status: drops its icon square before it would cut the state short. */
    private final class CompactLockStatus extends LinearLayout {
        private final View symbol;private final TextView title,detail;
        CompactLockStatus(View symbol,LinearLayout words,TextView title){
            super(MainActivity.this);this.symbol=symbol;this.title=title;this.detail=(TextView)words.getChildAt(1);
            setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setMinimumHeight(design.dp(48));
            setClickable(true);setFocusable(true);
            setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(design.accent,30)),null,design.shape(android.graphics.Color.WHITE,12,0)));
            addView(symbol,new LinearLayout.LayoutParams(design.dp(40),design.dp(40)));
            LinearLayout.LayoutParams space=new LinearLayout.LayoutParams(0,-2,1);space.setMarginStart(design.dp(12));
            addView(words,space);
        }
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int width=MeasureSpec.getSize(widthSpec);
            // Both lines decide: the square goes before either the state or its time is cut short.
            float needed=Math.max(title.getPaint().measureText(title.getText().toString()),
                    detail.getPaint().measureText(detail.getText().toString()));
            boolean room=MeasureSpec.getMode(widthSpec)==MeasureSpec.UNSPECIFIED||width-design.dp(52)>=needed;
            symbol.setVisibility(room?VISIBLE:GONE);
            LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)getChildAt(1).getLayoutParams();
            int start=room?design.dp(12):design.dp(2);
            if(p.getMarginStart()!=start){p.setMarginStart(start);p.resolveLayoutDirection(getLayoutDirection());}
            super.onMeasure(widthSpec,heightSpec);
        }
    }
    /** The lock state heading; its glyph gives way before the state word on a very narrow card. */
    private final class LockHeading extends LinearLayout {
        private final TextView state;
        LockHeading(TextView state){
            super(MainActivity.this);this.state=state;
            setGravity(Gravity.CENTER_VERTICAL);
            addView(state,new LinearLayout.LayoutParams(-1,-2));
        }
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int width=MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight();
            // On a narrow five-column card the slider already identifies the lock.
            // Keep the state readable before spending width on a second lock glyph.
            android.graphics.drawable.Drawable glyph=state.getCompoundDrawablesRelative()[0];
            if(glyph!=null){
                int size=width<design.dp(112)?0:design.dp(20);
                if(glyph.getBounds().width()!=size){glyph.setBounds(0,0,size,size);state.setCompoundDrawablesRelative(glyph,null,null,null);}
                state.setCompoundDrawablePadding(size==0?0:design.dp(8));
            }
            super.onMeasure(widthSpec,heightSpec);
        }
    }
    /** Let narrow offline cards use their full width for status instead of a thin text column. */
    private final class LockOfflineStatus extends LinearLayout {
        private final View symbol,chevron;
        private final LinearLayout words;
        private final TextView title;
        LockOfflineStatus(View symbol,LinearLayout words,TextView title,View chevron){
            super(MainActivity.this);this.symbol=symbol;this.words=words;this.title=title;this.chevron=chevron;
            setGravity(Gravity.CENTER_VERTICAL);
            addView(symbol,new LinearLayout.LayoutParams(design.dp(34),design.dp(34)));
            addView(words,new LinearLayout.LayoutParams(0,-2,1));
            // The row opens the connection details; a chevron says so without a caption.
            LinearLayout.LayoutParams more=new LinearLayout.LayoutParams(design.dp(15),design.dp(15));more.setMarginStart(design.dp(6));
            chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);addView(chevron,more);
        }
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            int width=MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight();
            // Allow the title two lines beside the icon. Below that, give the text a
            // full-width row so timestamps and the unverified state remain readable.
            float titleWidth=title.getPaint().measureText(title.getText().toString());
            boolean stack=MeasureSpec.getMode(widthSpec)!=MeasureSpec.UNSPECIFIED&&width-design.dp(44)<Math.max(design.dp(90),titleWidth/2);
            setOrientation(stack?VERTICAL:HORIZONTAL);
            // The status already names the lock. A separate decorative icon row would
            // consume the room needed by the timestamp and Connect in a short card.
            symbol.setVisibility(stack?GONE:VISIBLE);chevron.setVisibility(stack?GONE:VISIBLE);
            LinearLayout.LayoutParams wordsSize=(LinearLayout.LayoutParams)words.getLayoutParams();
            int wordsWidth=stack?-1:0,wordsTop=0,wordsStart=stack?0:design.dp(10);float wordsWeight=stack?0:1;
            wordsSize.width=wordsWidth;wordsSize.weight=wordsWeight;wordsSize.topMargin=wordsTop;wordsSize.setMarginStart(wordsStart);
            wordsSize.resolveLayoutDirection(words.getLayoutDirection());
            super.onMeasure(widthSpec,heightSpec);
        }
    }
    private TextView connectionLabel(){
        TextView label=design.text(T.bleStatusDisconnected(),13,true);
        design.fitStatusLine(label);
        label.setOnClickListener(v->showConnectionDetails());
        label.setTooltipText(T.mainConnectionDetails());
        return label;
    }
    /**
     * The connection line's narrow-label ladder when not connecting, full line first (Ui.statusRungs shows the first that
     * fits): the scooter's name drops before the state word does, and the words last seen before the time does.
     */
    private String[] connectionRungs(boolean named,boolean seen,boolean ready,String state,String shown,long last){
        if(named)return new String[]{shown,state};
        if(ready)return new String[]{shown};
        if(!seen)return new String[]{shown,T.bleStatusDisconnectedShort()};
        return new String[]{shown,T.mainStatusLastSeenShort(state,DisplayTime.recent(this,preferences,last,System.currentTimeMillis())),state,T.bleStatusDisconnectedShort()};
    }
    /** States that need no extra line in the connection dialog: the state row already says them. */
    private static final java.util.Set<String> PLAIN_CONNECTION_STATES=new java.util.HashSet<>(java.util.Arrays.asList(
            "","Not connected","Disconnected","Connected","Connecting…","Checking owner…","Searching…","Paused in background",
            "Simulation on","Simulation off","Simulated connection","Simulation disconnected","Choose your scooter.",
            BleController.NEARBY_NEEDED,BleController.BLUETOOTH_OFF,BleController.NO_PROFILE));
    /**
     * Connection dialog: a state row (icon and one word) and the last connection time. A line appears only for an
     * error; missing permission, Bluetooth or owner setup turn the main button into the action that fixes it.
     */
    private void showConnectionDetails(){
        BleController ble=scooterService==null?null:scooterService.ble;
        ScooterProfiles.Profile profile=scooterService==null?null:scooterService.profiles.active();
        boolean permission=hasBluetoothPermission();
        android.bluetooth.BluetoothManager manager=getSystemService(android.bluetooth.BluetoothManager.class);
        BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        boolean bluetoothOff=permission&&adapter!=null&&!adapter.isEnabled();
        boolean simulated=ble!=null&&ble.simulating();
        BleController.State state=ble==null?BleController.State.DISCONNECTED:ble.state();
        boolean ready=ble!=null&&ble.ready();
        boolean active=ble!=null&&(ble.connected()||ready||state==BleController.State.CONNECTING||state==BleController.State.AUTHENTICATING||state==BleController.State.SCANNING||state==BleController.State.WAITING);
        String word;int tone;
        if(!permission){word=T.mainConnectionNoPermission();tone=design.dim;}
        else if(ble==null){word=T.mainConnectionStarting();tone=design.muted;}
        else if(ready){word=simulated?T.mainConnectionSimulated():T.bleStatusConnected();tone=design.ok;}
        else if(state==BleController.State.WAITING){word=T.mainConnectionRetrying();tone=design.orange;}
        else if(state==BleController.State.SCANNING){word=T.bleStatusSearching();tone=design.orange;}
        else if(state==BleController.State.AUTHENTICATING){word=T.bleStatusCheckingOwner();tone=design.orange;}
        else if(active){word=T.bleStatusConnecting();tone=design.orange;}
        else if(bluetoothOff){word=T.bleBluetoothOff();tone=design.dim;}
        else{word=T.bleStatusNotConnected();tone=design.dim;}
        String message=ble==null?"":ble.message();
        // Errors only, in their short form (a dropped change keeps its "change cancelled").
        String error=!active&&ble!=null&&!PLAIN_CONNECTION_STATES.contains(message)?message:null;
        if(state==BleController.State.WAITING&&ble!=null&&!PLAIN_CONNECTION_STATES.contains(message))error=message;
        LinearLayout body=column();body.setPadding(design.dp(24),design.dp(4),design.dp(24),design.dp(4));
        TextView stateValue=connectionRow(body,T.mainConnectionState(),word);
        View dot=design.icon(ready?"check":"bluetooth",16,tone);
        stateValue.setTextColor(ready?design.text:design.muted);
        ((LinearLayout)stateValue.getParent()).addView(dot,((LinearLayout)stateValue.getParent()).indexOfChild(stateValue),connectionIcon());
        if(profile!=null&&profile.lastConnected>0)connectionRow(body,T.mainConnectionLastConnected(),DisplayTime.dateTime(this,preferences,profile.lastConnected));
        if(error!=null){
            TextView line=design.text(ble.displayMessage(),14,false);line.setTextColor(design.danger);line.setPadding(0,design.dp(10),0,design.dp(2));body.addView(line);
            if(BleController.NOTHING_FOUND.equals(error)){
                // "Why?" opens the two likely causes instead of a sentence on every search.
                TextView why=connectionLink(T.mainConnectionWhy());TextView reasons=design.caption(T.mainConnectionWhyDetail());
                reasons.setVisibility(View.GONE);why.setOnClickListener(v->{reasons.setVisibility(View.VISIBLE);why.setVisibility(View.GONE);});
                body.addView(why);body.addView(reasons);
            }else if(BleController.OWNER_REJECTED.equals(error)){
                TextView check=connectionLink(T.mainConnectionCheckOwner());
                check.setOnClickListener(v->{if(controlDialog!=null)controlDialog.dismiss();openRoute(HubPages.SETTINGS_SCOOTERS,null);});body.addView(check);
            }
        }
        boolean noProfile=profile==null||!profile.configured();
        String primary=active?T.mainActionDisconnect():!permission?T.mainActionAllowNearby():bluetoothOff?T.mainConnectionTurnOnBluetooth():noProfile&&!simulated?T.mainConnectionAddScooter():T.mainActionConnect();
        controlDialog=new AlertDialog.Builder(dialogContext()).setCustomTitle(dialogTitle(T.mainConnectionTitle())).setView(body).setPositiveButton(T.commonClose(),null)
                .setNegativeButton(primary,(d,which)->{
                    String primaryAction=permission&&!bluetoothOff&&noProfile&&!simulated?ADD_SCOOTER:null;
                    if(active){if(ble!=null)ble.disconnect();redraw();}
                    else if(ADD_SCOOTER.equals(primaryAction))openRoute(HubPages.SETTINGS_SCOOTERS,null);
                    // Connect asks for Nearby devices or turns on Bluetooth first, then connects.
                    else connect();
                }).create();
        controlDialog.show();styleDialog(controlDialog,active?0:AlertDialog.BUTTON_NEGATIVE);
    }
    /** A label/value row in the connection dialog; returns the value view. */
    private TextView connectionRow(LinearLayout parent,String label,String value){
        LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);line.setMinimumHeight(design.dp(40));
        TextView name=design.text(label,14,false);name.setTextColor(design.muted);line.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        TextView shown=design.text(value,14,true);shown.setGravity(Gravity.END);line.addView(shown,new LinearLayout.LayoutParams(-2,-2));
        line.setFocusable(true);line.setContentDescription(T.mainLabelValue(label,value));
        parent.addView(line,new LinearLayout.LayoutParams(-1,-2));return shown;
    }
    private LinearLayout.LayoutParams connectionIcon(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(design.dp(16),design.dp(16));p.setMarginEnd(design.dp(8));return p;}
    private TextView connectionLink(String label){
        TextView link=design.text(label,14,true);link.setTextColor(design.accent);link.setMinHeight(design.dp(48));link.setGravity(Gravity.CENTER_VERTICAL);
        link.setClickable(true);link.setFocusable(true);link.setContentDescription(label);return link;
    }
    /** An animated span keeps the spinner next to its word, without adding a separate row. */
    private CharSequence connectingLabel(TextView owner){
        android.text.SpannableString text=new android.text.SpannableString("Connecting  \uFFFC");
        // English keeps the line above; U+FFFC, the spinner's place, cannot be catalog text (GlyphCoverageTest).
        if(!Fmt.legacy())text=new android.text.SpannableString(T.mainLockConnecting()+new String(new char[]{32,32,0xFFFC}));
        android.graphics.drawable.Drawable spinner=new android.graphics.drawable.Drawable(){
            private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override public void draw(Canvas canvas){
                paint.setColor(design.muted);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(design.dp(2));paint.setStrokeCap(Paint.Cap.ROUND);
                RectF arc=new RectF(getBounds());arc.inset(design.dp(2),design.dp(2));
                canvas.drawArc(arc,Ui.motionEnabled()?(SystemClock.uptimeMillis()%900)*.4f:0,250,false,paint);
                if(resumed&&owner.isShown()&&Ui.motionEnabled())owner.postInvalidateDelayed(32);
            }
            @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
            @Override public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);}
            @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
        };
        int size=Math.round(owner.getTextSize());spinner.setBounds(0,0,size,size);
        text.setSpan(new android.text.style.ImageSpan(spinner,android.text.style.DynamicDrawableSpan.ALIGN_BASELINE),text.length()-1,text.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }
    private void renameScooter(){
        if(scooterService==null||scooterService.profiles.active()==null){toast(T.mainRenameFirst());return;}ScooterProfiles.Profile profile=scooterService.profiles.active();EditText input=new EditText(this);input.setSingleLine(true);input.setText(profile.name);input.setSelectAllOnFocus(true);input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(48)});
        design.styleSearch(input);input.setCompoundDrawablesRelative(null,null,null,null);input.setBackground(design.shape(design.raised,14,design.outline));input.setContentDescription(T.mainRenameTitle());
        FrameLayout field=new FrameLayout(this);field.setPadding(design.dp(24),design.dp(8),design.dp(24),design.dp(4));field.addView(input,new FrameLayout.LayoutParams(-1,-2));
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setCustomTitle(dialogTitle(T.mainRenameTitle())).setView(field).setNegativeButton(T.commonCancel(),null).setPositiveButton(T.mainRenameSave(),null).create();controlDialog=dialog;dialog.show();styleDialog(dialog,AlertDialog.BUTTON_POSITIVE);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String name=input.getText().toString().trim();if(name.isEmpty()){input.setError(T.mainRenameEmpty());return;}profile.name=name;profile.nameDefault=false;scooterService.profiles.update(profile);dialog.dismiss();rebuild();});
    }
    /** Every page but Home: its back link, a tappable connection line and the page's title. The lock lives on Home. */
    private LinearLayout makeCompactHeader(){
        // A page other than Home starts with no lock registered; Security's page part registers its own after this.
        clearDashboardLock();
        LinearLayout header=column();header.setPadding(design.dp(expanded?24:18),design.dp(10),design.dp(expanded?24:18),design.dp(6));
        // Home is the root of every stack, so every other page has a back link to the page under it (Home for a subject).
        if(nav.size()>1)header.addView(backLink(nav.pages().get(nav.size()-2)),new LinearLayout.LayoutParams(-2,-2));
        connectionTitle=connectionLabel();connectionTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,12.5f*design.textScale);
        connectionTitle.setTypeface(design.regular());header.addView(connectionTitle);
        addPageTitle(header);
        profileTitle=null;
        return header;
    }
    /** The page's title under its back link and connection line: Data keeps Live Stats' title with its live dot. */
    private void addPageTitle(LinearLayout header){
        liveStatsTitle=null;
        // Data's own page keeps Live Stats' title with its live dot; every other page shows its own title (HubText).
        if(HubPages.DATA.equals(selectedPage)){
            liveStatsTitle=new LiveStatsTitle(design);header.addView(liveStatsTitle);
        }else{
            TextView title=design.text(HubText.title(selectedPage),24,true);title.setPadding(0,design.dp(2),0,0);
            if(Build.VERSION.SDK_INT>=28)title.setAccessibilityHeading(true);header.addView(title);
        }
    }
    /**
     * A page's header in the wide Home's page pane: its back link and title (the scooter pane beside it has the connection
     * line). A subject's page goes back to the pane's list of subjects, so its link says "All" (TalkBack: "Back to All
     * subjects"); a deeper page names its parent, as on one pane. The scooter pane's lock stays registered.
     */
    private LinearLayout makePaneHeader(){
        LinearLayout header=column();header.setPadding(0,design.dp(2),0,design.dp(6));
        String parent=nav.size()>1?nav.pages().get(nav.size()-2):HubPages.HOME;
        header.addView(HubPages.HOME.equals(parent)?backLink(HubText.all(),HubText.backTo(HubText.dockAll())):backLink(parent),new LinearLayout.LayoutParams(-2,-2));
        addPageTitle(header);
        profileTitle=null;
        return header;
    }
    /** A Hub page's back link: the page it returns to, with Back's arrow; TalkBack reads "Back to <page>". */
    private View backLink(String to){
        CharSequence name=HubText.title(to);
        return backLink(name,HubText.backTo(name));
    }
    /** A back link showing name, read as spoken. */
    private View backLink(CharSequence name,CharSequence spoken){
        LinearLayout backRow=row();backRow.setGravity(Gravity.CENTER_VERTICAL);backRow.setMinimumHeight(design.dp(48));
        backRow.addView(design.icon("back",16,design.accent),new LinearLayout.LayoutParams(design.dp(16),design.dp(16)));
        TextView backText=design.text(name,13.5f,false);backText.setTypeface(design.medium());backText.setTextColor(design.accent);backText.setPadding(design.dp(4),0,design.dp(10),0);backRow.addView(backText);
        backRow.setClickable(true);backRow.setFocusable(true);backRow.setContentDescription(spoken);backRow.setBackground(rowRipple());
        backRow.setTag("hub-back");backRow.setOnClickListener(v->navigateBack());
        return backRow;
    }
    /**
     * A page by id, legacy tab ids accepted (validations call it by reflection): since the tabs went (PR 11) it is open(page),
     * so every route uses Home's page stack. Home closes every page above it.
     */
    private void select(String page){open(page,null);}
    // The floating tab bar and the side rail the Hub replaced (parts 1-10). Not shown since part 11, but kept, unused, until
    // the owner has used the Hub and says they can go (owner's answer 8 on #383): Hub layout part 14 leaves them in place too.
    // Volt order: Scooter was the raised centre button of the floating tab bar. Each tab opened its root page (HubPages.TABS).
    private final String[] pages=HubPages.TABS.toArray(new String[0]);
    private final String[] names={"Home","Live Stats","Scooter","Rides","More"};
    /** The tab names as shown; names above are ids, in the same order. */
    private String[] navNames(){return new String[]{T.mainNavHome(),T.mainNavStats(),T.mainNavScooter(),T.mainNavRides(),T.mainNavMore()};}
    /** The rail's one-line tab captions when a full name does not fit (Ui.nav); the screen reader keeps the full name. */
    private String[] navShortNames(){return new String[]{T.mainNavHomeShort(),T.mainNavStatsShort(),T.mainNavScooterShort(),T.mainNavRidesShort(),T.mainNavMoreShort()};}
    private final String[] icons={"home","stats","scooter","rides","more"};
    private static final int NAV_CENTER=2;
    /** The Scooter tab (the phone's raised button, or the rail item) for the maintenance dot, and whether it is the raised button. */
    private View scooterTab;private boolean scooterTabCorner;
    private int navFrom = -1;
    /** Whether the shown page belongs to this tab (HubPages.tabOf). */
    private boolean onTab(String tab){return HubPages.tabOf(selectedPage).equals(tab);}
    private int tabIndex(String page){return java.util.Arrays.asList(pages).indexOf(HubPages.tabOf(page));}
    /** The phone's floating tab bar as it was added over the page (unused since part 11; HubDock took its place). */
    private View floatingTabBar(){
        View bar=design.floatingNav(navNames(),icons,tabIndex(selectedPage),navFrom,NAV_CENTER,i->select(pages[i]));
        scooterTab=((ViewGroup)bar).getChildCount()>1?((ViewGroup)bar).getChildAt(1):null;scooterTabCorner=true;
        return bar;
    }
    /** The wide window's side rail (92 dp wide) as it was (unused since part 11), or the plain bar when rail is false. */
    private View navigation(boolean rail){
        LinearLayout nav=rail?column():row();nav.setGravity(rail?Gravity.TOP|Gravity.CENTER_HORIZONTAL:Gravity.CENTER);nav.setBackgroundColor(design.bg);nav.setPadding(design.dp(rail?8:4),design.dp(rail?20:6),design.dp(rail?8:4),design.dp(rail?12:6));
        View selected=null;
        for(int i=0;i<pages.length;i++){final String page=pages[i];View item=design.nav(navNames()[i],navShortNames()[i],icons[i],onTab(page),()->select(page));LinearLayout.LayoutParams p=rail?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1);if(rail){item.setMinimumHeight(design.dp(78));p.bottomMargin=design.dp(8);}nav.addView(item,p);if(onTab(page))selected=item;}
        if(rail){scooterTab=nav.getChildAt(java.util.Arrays.asList(pages).indexOf(HubPages.SCOOTER));scooterTabCorner=false;}
        if(!rail)return nav;
        // App text and Android font size compound. Let two-line captions determine each
        // item's height, and keep every destination reachable in a short or large-text rail.
        ScrollView scroller=new ScrollView(this);scroller.setFillViewport(false);scroller.setVerticalScrollBarEnabled(false);scroller.addView(nav,new ScrollView.LayoutParams(-1,-2));
        View current=selected;if(current!=null)scroller.post(()->current.requestRectangleOnScreen(new Rect(0,0,current.getWidth(),current.getHeight()),true));
        return scroller;
    }
    /** ScreenHost: opens a page above the current one; the same page scrolls to its top. */
    @Override public void open(String page){open(page,null);}
    @Override public void open(String page,HubNav.Args args){
        String target=HubPages.canonical(page);
        if(target.equals(selectedPage)){pageArgs=null;if(scroll!=null)scroll.smoothScrollTo(0,0);return;}
        String from=selectedPage;
        captureScroll();leaving(from,target);
        // Search opened afresh (Home's button) starts empty; Back to it from a result keeps the query.
        if(HubPages.SEARCH.equals(target)){searchQuery="";scrollPositions.put(HubPages.SEARCH,0);}
        nav.open(target);pageArgs=args;
        arrived(from);
    }
    /** System Back past the screen's own Back: the page below in the stack. */
    private void goBack(){
        String from=selectedPage,target=nav.pages().get(nav.size()-2);
        captureScroll();leaving(from,target);
        nav.back();pageArgs=null;
        arrived(from);
    }
    /** Live Stats' return timer when a page change leaves or enters Data's pages. */
    private void leaving(String from,String to){
        boolean fromData=inData(from),toData=inData(to);
        if(fromData==toData)return;
        if(fromData)markStatsAway();
        if(toData){expireStatsSection(true);if(resumed)markStatsActive();}
    }
    /** The stack changed from page from: show its top, sliding in by depth (deeper from the end side, back from the start). */
    private void arrived(String from){
        String page=nav.current();selectedPage=page;preferences.edit().putString("last_page",page).apply();
        if(!nav.pages().contains(HubPages.SERVICE_COMPATIBILITY))compatibilityRecovery=false;
        // The wide Clean Home changes only its page pane; the scooter pane never changes while browsing.
        if(rightPage!=null&&homeScreen!=null&&!rebuilding&&twoPaneHome())swapPagePane(HubNav.direction(from,page));
        else transitionRebuild(HubNav.direction(from,page));
    }
    /** Brief returns preserve the Live Stats section; a longer absence starts at Overview. */
    private void markStatsAway(){
        if(preferences.contains(STATS_AWAY_AT))return;
        preferences.edit().putLong(STATS_AWAY_AT,SystemClock.elapsedRealtime()).putInt(STATS_AWAY_BOOT,bootCount()).apply();
    }
    private void markStatsActive(){
        preferences.edit().remove(STATS_AWAY_AT).remove(STATS_AWAY_BOOT).apply();
    }
    private boolean expireStatsSection(boolean retainActive){return expireStatsSection(retainActive,SystemClock.elapsedRealtime());}
    private boolean expireStatsSection(boolean retainActive,long now){
        long away=preferences.getLong(STATS_AWAY_AT,-1);
        boolean expired=away<0?!retainActive:now<away||now-away>=STATS_RETENTION_MS
                ||preferences.getInt(STATS_AWAY_BOOT,-1)!=bootCount();
        if(!expired)return false;
        boolean changed=!"Overview".equals(preferences.getString("stats_section","Overview"));
        preferences.edit().putString("stats_section","Overview").remove(STATS_AWAY_AT).remove(STATS_AWAY_BOOT).apply();
        scrollPositions.put(HubPages.DATA,0);StatsScreen.clearSavedScroll();
        if(screen instanceof StatsScreen)((StatsScreen)screen).resetScroll();
        if(scroll!=null&&HubPages.DATA.equals(scrollPage))scroll.scrollTo(0,0);
        return changed;
    }
    /** Volt left the screen: the first time since it was last on screen starts the away clock (HubNav.awayExpired). */
    private void markAway(){
        if(preferences.contains(HUB_AWAY_AT))return;
        preferences.edit().putLong(HUB_AWAY_AT,SystemClock.elapsedRealtime()).putInt(HUB_AWAY_BOOT,bootCount()).apply();
    }
    /**
     * Back on screen: after a minute or more away, or a reboot, the open sub-pages close back to their subject (Home is
     * never forced; generalizes More's old one-minute submenu rule). The away clock stops either way. True when the stack changed.
     */
    /** A deliberate route (a fresh start, a notice's tap, the update check) beats an expired return: stop the away clock. */
    private void clearAway(){
        if(preferences.contains(HUB_AWAY_AT)||preferences.contains(HUB_AWAY_BOOT))preferences.edit().remove(HUB_AWAY_AT).remove(HUB_AWAY_BOOT).apply();
    }
    private boolean expireAway(){
        long away=preferences.getLong(HUB_AWAY_AT,-1);
        if(away<0)return false;
        boolean expired=HubNav.awayExpired(away,preferences.getInt(HUB_AWAY_BOOT,-1),SystemClock.elapsedRealtime(),bootCount());
        preferences.edit().remove(HUB_AWAY_AT).remove(HUB_AWAY_BOOT).apply();
        if(!expired||!nav.closeSubPages())return false;
        pageArgs=null;if(!nav.pages().contains(HubPages.SERVICE_COMPATIBILITY))compatibilityRecovery=false;
        selectedPage=nav.current();preferences.edit().putString("last_page",selectedPage).apply();
        return true;
    }
    private int bootCount(){
        return android.provider.Settings.Global.getInt(getContentResolver(),android.provider.Settings.Global.BOOT_COUNT,-1);
    }
    /**
     * The lock bar's switch alone for riding mode (RidingScreen), registered like any Home lock so updates and the queue
     * reach it. There is no offline card to swap to, so no lockReady: a dropped link greys the switch and it keeps its place.
     * stopped is the screen's own gate: its taps are stopped-only, unlock included, unlike Home's unlock recovery.
     */
    @Override public View ridingLock(java.util.function.BooleanSupplier stopped){
        compactLock=true;lockTitle=design.text(T.mainLockTitle(),15,true);
        lockControl=new LockControl();lockControl.compact=true;lockControl.stoppedGate=stopped;
        return registerDashboardLock(lockControl);
    }
    /**
     * A Quick access shortcut's page (HubRoutes), and the scanner's and Review's routes: opened on Home's page stack like
     * every other route (PR 11), so Back returns to where the shortcut was used.
     */
    private void openRoute(String page,HubNav.Args args){open(page,args);}
    private void captureScroll(){if(scroll!=null)scrollPositions.put(scrollPage,scroll.getScrollY());}
    private boolean horizontalHinge(){return fold!=null&&fold.isSeparating()&&fold.hinge.width()>fold.hinge.height()&&fold.hinge.top>0&&fold.hinge.width()>0;}
    private boolean verticalHinge(){return fold!=null&&fold.isSeparating()&&fold.hinge.height()>fold.hinge.width()&&fold.hinge.left>0&&fold.hinge.height()>0;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);return v;}
    private void heading(LinearLayout parent,String title,String detail){TextView h=design.text(title,22,true);h.setPadding(0,design.dp(12),0,design.dp(6));parent.addView(h);if(detail!=null){TextView d=design.caption(detail);d.setPadding(0,0,0,design.dp(16));parent.addView(d);}}
    private void updateHeader(){
        if(liveStatsTitle!=null)liveStatsTitle.setLive(scooterService!=null&&scooterService.ble.connected());
        BleController ble=scooterService==null?null:scooterService.ble;Boolean locked=ble==null?null:ble.locked();String pending=ble==null?null:ble.pending();boolean ready=ble!=null&&ble.ready(),home=selectedPage.equals(HubPages.HOME);Boolean shownLock=locked;if(shownLock==null&&scooterService!=null){Integer saved=scooterService.displayFeatures().get("lock");if(saved!=null)shownLock=saved!=0;}
        boolean connecting=ble!=null&&(ble.state()==BleController.State.CONNECTING||ble.state()==BleController.State.AUTHENTICATING
                ||ble.state()==BleController.State.SCANNING||ble.state()==BleController.State.WAITING||ble.connected())&&!ready;
        ScooterProfiles.Profile active=scooterService==null?null:scooterService.profiles.active();
        long last=active==null?0:active.lastConnected;
        String scooterName=active==null?T.mainLockYourScooter():active.name;
        pumpLockQueue(ble,ready,shownLock,pending);
        if(active!=null)scooterName=active.shownName();
        for(int lockIndex=dashboardLocks.isEmpty()?-1:0;lockIndex<dashboardLocks.size();lockIndex++){
        if(lockIndex>=0)loadLockViews(dashboardLocks.get(lockIndex));
        if(lockTitle!=null){
            boolean editing=screen instanceof DashboardScreen&&((DashboardScreen)screen).isEditing();
            boolean showBar=ready;
            if(lockReady!=null)lockReady.setVisibility(showBar?View.VISIBLE:View.GONE);
            if(lockOffline!=null)lockOffline.setVisibility(showBar?View.GONE:View.VISIBLE);
            lockTitle.setText("lock".equals(pending)||lockQueue.waiting()?T.mainLockUpdating():shownLock==null?T.mainLockTitle():shownLock?T.mainLockLocked():T.mainLockUnlocked());
            lockTitle.setTextColor(Boolean.FALSE.equals(shownLock)&&ready?design.danger:design.text);
            design.decorate(lockTitle,Boolean.FALSE.equals(shownLock)?"unlock":"lock");
            android.graphics.drawable.Drawable lockGlyph=lockTitle.getCompoundDrawablesRelative()[0];
            if(lockGlyph!=null){lockGlyph.setBounds(0,0,design.dp(20),design.dp(20));lockTitle.setCompoundDrawablesRelative(lockGlyph,null,null,null);lockTitle.setCompoundDrawablePadding(design.dp(8));}
            String unknown=compactLock?T.mainLockStateUnknown():T.mainLockStateUnverified();
            String known=shownLock==null?null:shownLock?"Locked":"Unlocked";
            String description=known==null?unknown:"Locked".equals(known)?T.mainLockLastKnownFullLocked():"Unlocked".equals(known)?T.mainLockLastKnownFullUnlocked():T.mainLockLastKnownFull(known.toLowerCase(java.util.Locale.ROOT));
            // Another write in flight (an automatic one right after connecting) no longer disables the switch: the tap waits for it.
            // Not ready, a state shown is the saved one: riding mode's switch, which stays on screen, says it's the last known.
            lockControl.update(shownLock,ready&&!editing&&lockOffered(ble,shownLock),ready?null:description);
            if(offlineTitle!=null){
                // The scooter name is already in the Home header; repeating it can fill
                // a narrow card and push Cancel out of view at larger system font sizes.
                offlineTitle.setText(connecting?(compactLock?T.bleStatusConnecting():T.mainLockConnecting()):known==null?unknown:known);
                offlineTitle.setTextColor(design.muted);
                // known is the state id; the title shows it as a word, in the same pass, so the id is never drawn.
                if(!connecting&&known!=null)offlineTitle.setText(shownLock?T.mainLockLocked():T.mainLockUnlocked());
                offlineTitle.setContentDescription(connecting?T.mainLockConnectingTo(scooterName,description):description);
                // The feature cache contains scooter readbacks, never a requested lock change.
                // Keep its stale label visible even during reconnect; it does not enable the switch.
                // A past connection alone cannot verify the lock; its age is already in the header.
                String detail=known==null?"":connecting?"Locked".equals(known)?T.mainLockLastKnownWordLocked():"Unlocked".equals(known)?T.mainLockLastKnownWordUnlocked():T.mainLockLastKnownWord(known.toLowerCase(java.util.Locale.ROOT)):T.mainLockLastKnown();
                offlineDetail.setText(detail);offlineDetail.setVisibility(detail.isEmpty()?View.GONE:View.VISIBLE);
            }
        }
        }
        if(!dashboardLocks.isEmpty())loadLockViews(dashboardLocks.get(0));
        if(connectionTitle!=null){
            String state=ready?T.bleStatusConnected():connecting?T.mainLockConnecting():T.bleStatusDisconnected();
            String shown=ready&&active!=null?T.mainStatusConnectedNamed(state,active.shownName()):!ready&&!connecting&&last>0?T.mainStatusLastSeen(state,DisplayTime.recent(this,preferences,last,System.currentTimeMillis())):state;
            if(connecting)design.statusRungs(connectionTitle,connectingLabel(connectionTitle));else design.statusRungs(connectionTitle,connectionRungs(ready&&active!=null,!ready&&last>0,ready,state,shown,last));
            connectionTitle.setContentDescription(!ready&&!connecting&&last>0?T.mainStatusDescriptionLast(state,DisplayTime.dateTime(this,preferences,last)):T.mainStatusDescription(state));
            connectionTitle.setTextColor(design.muted);
        }
        updateConnectionAction(ready,connecting);
        ScooterProfiles.Profile p=scooterService==null?null:scooterService.profiles.active();if(profileTitle!=null){profileTitle.setText(p==null?T.mainYourScooter():p.name);profileTitle.setVisibility(home?View.VISIBLE:View.GONE);}
    }
    /**
     * Sends a waiting lock tap once the scooter takes writes, or puts the switch back with the reason. The gate is the
     * same one every write passes; WAIT covers only the short post-connect holds, so riding is still refused, never queued.
     */
    private void pumpLockQueue(BleController ble,boolean ready,Boolean shownLock,String pending){
        if(!lockQueue.waiting())return;
        // Before the gate below: it lets an unlock through while moving, for Home's recovery, which riding mode doesn't offer.
        if(ready&&ble!=null&&lockQueue.stoppedGateClosed(lockStoppedGate!=null&&lockStoppedGate.getAsBoolean(),movingReason())==LockTapQueue.Action.REVERT){Haptics.reject(lockControl);toast(lockQueue.takeFailureMessage());return;}
        LockTapQueue.Gate gate=LockTapQueue.Gate.WAIT;
        if(ble!=null){CapabilityResolver.Decision d=ble.decisionOnceIdle("lock",Boolean.TRUE.equals(lockQueue.target())?1:0);
            boolean otherWrite=pending!=null&&!"lock".equals(pending);gate=LockTapQueue.gate(d.available(),d.reason,otherWrite);
            // Behind another write the BLE gate would only say "busy"; give the real refusal now instead of holding the tap.
            if(gate==LockTapQueue.Gate.REFUSE&&otherWrite&&lockQueue.reject(d.reasonMessage())==LockTapQueue.Action.REVERT){Haptics.reject(lockControl);toast(lockQueue.takeFailureMessage());return;}}
        LockTapQueue.Action action=lockQueue.step(SystemClock.elapsedRealtime(),ready&&ble!=null,shownLock,"lock".equals(pending),gate);
        if(action==LockTapQueue.Action.SEND){
            boolean target=Boolean.TRUE.equals(lockQueue.target());
            // Marked first: a refused send redraws the header from inside set(), which must neither send again nor call it unconfirmed.
            lockQueue.sending(true);
            lockQueue.sent();if(!ble.set("lock",target?1:0)){lockQueue.refused();Haptics.reject(lockControl);}
            // A riding-mode tap stays stopped-only until its frame goes out: a read in flight can leave it queued past this point.
            if(lockQueue.stoppedOnly())ble.holdUnsent("lock",lockStoppedGate,this::stoppedLockCancelled);
            lockQueue.sending(false);
            if(!lockQueue.waiting())toast(ble.displayMessage());
        }else if(action==LockTapQueue.Action.REVERT){Haptics.reject(lockControl);toast(lockQueue.takeFailureMessage());}
    }
    /** A stopped-only tap whose frame hadn't left the phone when the scooter started moving: nothing went out. */
    private void stoppedLockCancelled(){lockQueue.refused();Haptics.reject(lockControl);toast(movingReason());}
    /** Why a stopped-only tap went back: locking's own refusal, the one unlocking is excused from. */
    private static CharSequence movingReason(){return CapabilityResolver.message(CapabilityResolver.Reason.MOVING);}
    /**
     * Whether the lock switch can be used in the shown direction on this model. Riding, a missing
     * motion report or a warning keep it tappable so the tap says why; the gate still refuses.
     */
    private static boolean lockOffered(BleController ble,Boolean shownLock){
        if(ble==null)return false;CapabilityResolver.Decision d=ble.decision("lock",shownLock==null||!shownLock?1:0);
        return d.available()||d.blockedByRide()||d.reason==CapabilityResolver.Reason.BUSY||d.reason==CapabilityResolver.Reason.STATUS_STALE;
    }
    /** Ids the text below sits beside: keys and tags, never shown. */
    private static final String KEY_CLOCK_SYNC="clock_sync",KEY_DISPLAY_UNITS="display_units",KEY_LOCK_EXPIRY="lock_expiry",PREF_UNITS="units",UNIT_KM="km",
            GROUP_LIGHTS="Lights",SECTION_SETTINGS="Settings",SECTION_COMPATIBILITY="Compatibility",TAG_IDENTIFY="scooter-identify-model",PAGE_HOME="home",NO_TEXT="",TAG_DIAGNOSTICS_NOTE="diagnostics-note";
    /** What a connection action does, never its shown text: its icon follows this. */
    private static final String LINK_CANCEL="Cancel",LINK_DISCONNECT="Disconnect",ADD_SCOOTER="Add scooter",WATCHING="Watching";
    private final java.util.WeakHashMap<TextView,String> linkActions=new java.util.WeakHashMap<>();
    /** Updates the action presentation without changing the Bluetooth transport. */
    private void updateConnectionAction(boolean ready,boolean connecting){
        BleController ble=scooterService==null?null:scooterService.ble;
        for(int lockIndex=dashboardLocks.isEmpty()?-1:0;lockIndex<dashboardLocks.size();lockIndex++){
        if(lockIndex>=0)loadLockViews(dashboardLocks.get(lockIndex));
        if(connectionAction!=null){
            boolean busy=ble!=null&&(ble.connected()||connecting||ready);
            connectionAction.setVisibility(!ready||showDashboardDisconnect?View.VISIBLE:View.GONE);
            boolean allow=!ready&&!busy&&!hasBluetoothPermission();
            String action=ready?T.mainActionDisconnect():busy?T.commonCancel():allow?T.mainActionAllowNearby():T.mainActionConnect();
            connectionAction.setContentDescription(ready?T.mainActionDisconnectDescription():busy?T.mainActionCancelDescription():allow?T.mainActionAllowNearby():T.mainActionConnectDescription());
            // Only restyle on a real state change, not on every incoming BLE packet.
            if(!action.contentEquals(connectionAction.getText())){
                linkActions.put(connectionAction,ready?LINK_DISCONNECT:busy?LINK_CANCEL:null);
                connectionAction.setText(action);
                connectionAction.setTextColor(busy?design.text:design.onAccent);
                android.graphics.drawable.Drawable fill=new android.graphics.drawable.RippleDrawable(
                        ColorStateList.valueOf(Ui.withAlpha(busy?design.accent:android.graphics.Color.WHITE,35)),
                        design.shape(busy?design.raised:design.accent,14,busy?design.outline:0),null);
                setConnectionActionBackground(fill);
            }
            final BleController bleRef=ble;
            connectionAction.setOnClickListener(v->{if(busy){if(bleRef!=null)bleRef.disconnect();redraw();}else connect();});
            connectionAction.setEnabled(!(screen instanceof DashboardScreen&&((DashboardScreen)screen).isEditing()));
        }
        }
        if(!dashboardLocks.isEmpty())loadLockViews(dashboardLocks.get(0));
    }
    private final class LockControl extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint words=new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint.FontMetrics wordMetrics=new Paint.FontMetrics();
        private final RectF track=new RectF();
        private Boolean value;
        private boolean available,dragging,dragSide;
        /** Lock bar: draw only the state word, vertically centred inside the switch. */
        boolean compact;
        /** Riding mode's stopped gate; null on Home, where unlock stays available as recovery. */
        java.util.function.BooleanSupplier stoppedGate;
        /** When the rider last asked for a lock change; the scooter's confirmation then gets a haptic. */
        private long requestedAt;
        private float downX,downY,previewX,position;
        private android.animation.ValueAnimator motion;
        /** Recent thumb positions for a short speed trail; visual only, never the lock value. */
        private final LockSliderTrail trail=new LockSliderTrail();
        LockControl(){super(MainActivity.this);setMinimumWidth(design.dp(48));setMinimumHeight(design.dp(48));setClickable(true);setFocusable(true);setContentDescription(T.mainLockSwitch());setOnClickListener(v->apply(!Boolean.TRUE.equals(shown())));}
        /** The side drawn: a waiting tap's side until the scooter's readback settles it. Accessibility keeps the confirmed state. */
        private Boolean shown(){return lockQueue.shown(value);}
        private Boolean drawn;
        private void apply(boolean target){
            if(!available||value==null||scooterService==null)return;
            if(stoppedGate!=null&&!stoppedGate.getAsBoolean()){Haptics.reject(this);toast(movingReason());return;} // A tap that lands as the scooter starts moving.
            if(lockQueue.request(value,target,SystemClock.elapsedRealtime(),stoppedGate!=null)){lockStoppedGate=stoppedGate;requestedAt=SystemClock.elapsedRealtime();redraw();}
        }
        /** @param lastKnown the words for a saved, unconfirmed state (no live link), or null while the state is live */
        void update(Boolean locked,boolean enabled,String lastKnown){
            boolean changed=value==null?locked!=null:!value.equals(locked);
            boolean hadState=value!=null;
            if(changed&&hadState&&locked!=null&&requestedAt>0&&SystemClock.elapsedRealtime()-requestedAt<15000){requestedAt=0;Haptics.confirm(this);}
            value=locked;available=enabled;setEnabled(enabled);if(!enabled)trail.clear();
            if(!enabled&&dragging){dragging=false;setPressed(false);releaseParent();settle(hadState&&locked!=null);}
            else if((changed||!java.util.Objects.equals(drawn,shown()))&&!dragging)settle(hadState&&locked!=null);
            drawn=shown();
            // A saved state isn't offered as a double-tap: the switch says it's the last known one, and unavailable.
            boolean saved=locked!=null&&lastKnown!=null;
            setContentDescription(locked==null?T.mainLockSwitchUnavailable():saved?lastKnown:locked?T.mainLockSwitchLocked():T.mainLockSwitchUnlocked());
            if(Build.VERSION.SDK_INT>=30)setStateDescription(locked==null||saved?T.mainLockUnavailable():locked?T.mainLockLocked():T.mainLockUnlocked());
            invalidate();
        }
        private void cancelMotion(){if(motion!=null){motion.cancel();motion=null;}}
        private void releaseParent(){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);}
        /** Position is visual only. Value/accessibility always reflect the last confirmed report. */
        private void settle(boolean animate){
            cancelMotion();
            final float target=Boolean.TRUE.equals(shown())?1:0;
            if(!animate||!resumed||!isAttachedToWindow()||!isShown()||!Ui.motionEnabled()||Math.abs(target-position)<.001f){position=target;trail.clear();invalidate();return;}
            motion=android.animation.ValueAnimator.ofFloat(position,target);
            motion.setDuration(180);motion.setInterpolator(new android.view.animation.PathInterpolator(.2f,0,0,1));
            motion.addUpdateListener(animation->move((Float)animation.getAnimatedValue()));
            motion.start();
        }
        /** Moves the drawn thumb only; the trail samples it while motion is allowed. */
        private void move(float next){position=next;if(available&&Ui.motionEnabled())trail.record(next,SystemClock.uptimeMillis());else trail.clear();invalidate();}
        void stopMotion(){cancelMotion();dragging=false;setPressed(false);releaseParent();position=Boolean.TRUE.equals(shown())?1:0;trail.clear();invalidate();}
        @Override protected void onDetachedFromWindow(){stopMotion();super.onDetachedFromWindow();}
        @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE)stopMotion();}
        @Override public CharSequence getAccessibilityClassName(){return Switch.class.getName();}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setCheckable(value!=null);info.setChecked(Boolean.TRUE.equals(value));info.setEnabled(available);}
        @Override public boolean onTouchEvent(MotionEvent event){
            if(!available)return false;
            if(event.getAction()==MotionEvent.ACTION_DOWN){cancelMotion();downX=event.getX();downY=event.getY();previewX=downX;dragging=false;dragSide=downX>getWidth()/2f;setPressed(true);return true;}
            if(event.getAction()==MotionEvent.ACTION_MOVE){float delta=event.getX()-downX;if(Math.abs(delta)>ViewConfiguration.get(MainActivity.this).getScaledTouchSlop()&&Math.abs(delta)>Math.abs(event.getY()-downY)){dragging=true;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);}if(dragging){boolean side=event.getX()>getWidth()/2f;if(side!=dragSide)Haptics.tick(this);dragSide=side;previewX=event.getX();move(sliderGeometry().positionFor(previewX));}return true;}
            if(event.getAction()==MotionEvent.ACTION_UP){boolean dragged=dragging;boolean target=previewX>getWidth()/2f;dragging=false;setPressed(false);releaseParent();if(dragged)apply(target);else performClick();settle(true);return true;}
            if(event.getAction()==MotionEvent.ACTION_CANCEL){dragging=false;setPressed(false);releaseParent();settle(true);return true;}return true;
        }
        private LockSliderGeometry sliderGeometry(){
            return new LockSliderGeometry(getWidth(),getHeight(),design.dp(58),design.dp(5));
        }
        /** The icons a switch too narrow for any word shows: the action's, or a cross while the lock is unavailable. */
        private static final String LOCK_ICON="lock",UNLOCK_ICON="unlock",UNAVAILABLE_ICON="close";
        /** The words the switch can draw, full first: the lock bar's state, else what sliding does (or Unavailable). */
        private String[] lockRungs(Boolean value,boolean locked){
            if(compact&&value!=null)return locked?new String[]{T.mainLockLocked(),T.mainLockLockedShort()}:new String[]{T.mainLockUnlocked(),T.mainLockUnlockedShort()};
            if(value==null)return new String[]{T.mainLockUnavailable(),T.mainLockUnavailableShort()};
            return locked?new String[]{T.mainLockActionUnlock(),T.mainLockActionUnlockShort()}:new String[]{T.mainLockActionLock(),T.mainLockActionLockShort()};
        }
        /** The word lane onDraw gives the text: the width less the far padding, the thumb and the arrow's lane (or a gap). */
        private float lockRoom(boolean arrow,float w,LockSliderGeometry geometry){return Math.max(0,w-design.dp(18)-geometry.diameter-(arrow?design.dp(18):design.dp(4)));}
        /** The first rung that fits room at the switch's word size, or -1 (TileRung.first). */
        private int lockRung(String[] rungs,float room){
            float[] widths=new float[rungs.length];
            for(int i=0;i<rungs.length;i++)widths[i]=words.measureText(rungs[i]);
            return TileRung.first(widths,room);
        }
        /** The shorter rung that fits the lane, else the icon alone. */
        private void drawLockWord(Canvas canvas,String[] rungs,String icon,float left,float right,float h){
            int rung=lockRung(rungs,right-left);
            if(rung>=0){canvas.drawText(rungs[rung],(left+right)/2,h/2-(wordMetrics.ascent+wordMetrics.descent)/2,words);return;}
            drawLockIcon(canvas,icon,words.getColor(),left,right,h);
        }
        /** The worst case: the icon alone, centred in the lane, at the arrow's alpha. */
        private void drawLockIcon(Canvas canvas,String icon,int color,float left,float right,float h){
            float size=Math.min(design.dp(16),h*.6f);
            design.drawIcon(canvas,icon,Ui.withAlpha(color,words.getAlpha()),(left+right)/2,h/2,size);
        }
        /** The state word alone, centred on the bar's height; false when it cannot fit the lane. */
        private boolean drawState(Canvas canvas,String state,float left,float right,float h){
            int alpha=words.getAlpha();
            android.text.TextPaint head=new android.text.TextPaint(words);
            head.setTypeface(design.semibold());head.setTextSize(design.sp(15));
            if(head.measureText(state)>right-left)head.setTextSize(design.sp(13));
            Paint.FontMetrics metrics=head.getFontMetrics();
            if(metrics.descent-metrics.ascent>h||head.measureText(state)>right-left)return false;
            head.setColor(!available?design.dim:Boolean.FALSE.equals(shown())?design.danger:design.text);head.setAlpha(alpha);
            canvas.drawText(state,(left+right)/2,h/2-(metrics.ascent+metrics.descent)/2,head);
            return true;
        }
        /** Locked: white knob on the right. Unlocked: red knob on the left. Tap anywhere or slide to change. */
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);float x=0,y=0,w=getWidth(),h=getHeight();if(w<=0||h<=0)return;Boolean value=shown();
            boolean locked=Boolean.TRUE.equals(value),unlocked=Boolean.FALSE.equals(value);
            // Lock bar: the slider is the card, so it reaches the cell edges with the cards' 20 dp corners.
            float edge=compact?design.dp(1)/2f:design.dp(1);
            track.set(x+edge,y+edge,x+w-edge,y+h-edge);float r=compact?Math.min(design.dp(20),track.height()/2):track.height()/2;
            paint.setShader(null);paint.setStyle(Paint.Style.FILL);
            if(compact){paint.setColor(design.surface);canvas.drawRoundRect(track,r,r,paint);}
            if(unlocked&&available){paint.setShader(new android.graphics.LinearGradient(track.left,0,track.right,0,Ui.withAlpha(design.accent,82),Ui.withAlpha(design.accent,20),android.graphics.Shader.TileMode.CLAMP));}
            else paint.setColor(compact?design.surface:design.dark?design.bg:design.raised);
            canvas.drawRoundRect(track,r,r,paint);paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(design.dp(1));paint.setColor(unlocked&&available?Ui.withAlpha(design.accent,110):design.raised);canvas.drawRoundRect(track,r,r,paint);paint.setStyle(Paint.Style.FILL);
            // A tall card is still a horizontal switch. Keep the thumb and its travel
            // independent of card height, and share that geometry with touch tracking.
            LockSliderGeometry geometry=sliderGeometry();
            float knob=geometry.radius;
            float cx=geometry.centerFor(position);
            int thumb=!available?design.dim:locked?android.graphics.Color.WHITE:design.accent;
            // Speed trail: a few faint, shrinking discs back to where the thumb was a moment ago,
            // inside the track and under the crisp thumb. It empties within ~60 ms of stopping.
            if(!trail.isEmpty()&&available&&Ui.motionEnabled()){
                float lag=cx-geometry.centerFor(trail.tail(SystemClock.uptimeMillis(),position));
                lag=Math.max(-geometry.diameter*1.5f,Math.min(geometry.diameter*1.5f,lag));
                if(Math.abs(lag)>.5f){
                    float strength=Math.min(1,Math.abs(lag)/Math.max(1,geometry.diameter));
                    int save=canvas.save();canvas.clipRect(track);
                    // Discs at most ~a third of a radius apart, so a fast thumb blurs instead of stacking visible coins.
                    int steps=Math.max(5,Math.min(12,(int)Math.ceil(Math.abs(lag)/Math.max(1,knob*.35f))));
                    for(int i=steps;i>=1;i--){float f=i/(steps+1f);paint.setColor(Ui.withAlpha(thumb,Math.round(56*strength*(1-f)*5f/steps)));canvas.drawCircle(cx-lag*f,h/2,knob*(1-.15f*f),paint);}
                    canvas.restoreToCount(save);
                    postInvalidateOnAnimation();
                }
            }else if(!trail.isEmpty()&&!Ui.motionEnabled())trail.clear();
            paint.setColor(thumb);canvas.drawCircle(cx,h/2,knob,paint);
            design.drawIcon(canvas,locked?"lock":"unlock",!available?design.surface:locked?design.bg:design.onAccent,cx,h/2,knob*0.95f);
            // The accessibility label keeps the full tap-or-slide action; the arrow shows the slide direction.
            String label=value==null?T.mainLockUnavailable():locked?T.mainLockActionUnlock():T.mainLockActionLock();
            words.setTypeface(design.semibold());
            words.setTextSize(design.sp(13));words.setColor(!available?design.dim:unlocked?Ui.withAlpha(design.danger,235):design.muted);
            // Let the knob pass through the label's space without obscuring its words.
            words.setAlpha(Math.round(255*Math.max(0,1-4*Math.abs(position-(locked?1:0)))));
            boolean arrow=available&&value!=null&&!dragging;
            // The narrow-label ladder: the word, its short rung, then the icon alone, never a blank lane and never a
            // smaller word. The arrow is decorative, so it goes before the word does.
            String[] rungs=lockRungs(value,locked);
            if(arrow&&lockRung(rungs,lockRoom(true,w,geometry))<0)arrow=false;
            float lane=arrow?design.dp(18):design.dp(4);
            float textLeft=locked||value==null?x+design.dp(18):x+geometry.diameter+lane,textRight=locked||value==null?x+w-geometry.diameter-lane:x+w-design.dp(18);
            float textWidth=Math.max(0,textRight-textLeft);
            if(arrow){
                // Points the way the knob slides: left to unlock, right to lock.
                float size=design.dp(12),ax=locked?cx-knob-design.dp(10):cx+knob+design.dp(10);
                design.drawIcon(canvas,locked?"back":"chevron",Ui.withAlpha(words.getColor(),words.getAlpha()),ax,h/2,size);
            }
            // The switch remains a full-height touch target even in a very narrow card.
            // Its accessibility label always keeps the full tap/slide action text.
            String fitted=words.measureText(label)<=textWidth?label:"";
            words.setTextAlign(Paint.Align.CENTER);words.getFontMetrics(wordMetrics);
            if(compact&&value!=null){if(!drawState(canvas,rungs[0],textLeft,textRight,h)&&!drawState(canvas,rungs[1],textLeft,textRight,h))drawLockIcon(canvas,locked?LOCK_ICON:UNLOCK_ICON,!available?design.dim:Boolean.FALSE.equals(shown())?design.danger:design.text,textLeft,textRight,h);return;}
            if(!fitted.isEmpty())canvas.drawText(fitted,(textLeft+textRight)/2,h/2-(wordMetrics.ascent+wordMetrics.descent)/2,words);
            else drawLockWord(canvas,rungs,value==null?UNAVAILABLE_ICON:locked?UNLOCK_ICON:LOCK_ICON,textLeft,textRight,h);
        }
    }
    @Override public void connect(){
        if (scooterService != null && scooterService.simulating()) {
            scooterService.ble.connectLast(); redraw(); return;
        }
        if(!resumed){pendingConnect=true;return;}
        if(!hasBluetoothPermission()){pendingConnect=true;requestBluetooth();return;}
        android.bluetooth.BluetoothManager manager=getSystemService(android.bluetooth.BluetoothManager.class);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        if(adapter==null){toast(T.mainScanNoBluetooth());return;}if(!adapter.isEnabled()){pendingConnect=true;startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),ENABLE_BLUETOOTH);return;}
        if(scooterService==null){pendingConnect=true;ensureService();return;}
        ScooterProfiles.Profile profile=scooterService.profiles.active();
        // No scooter: My scooters has Add scooter. A scooter not yet set up: Settings, whose checklist imports the owner setup.
        if(profile==null){open(HubPages.SETTINGS_SCOOTERS);return;}
        if(!profile.configured()){open(HubPages.SETTINGS);return;}
        BleController.State state=scooterService.ble.state();if(state==BleController.State.CONNECTING||state==BleController.State.AUTHENTICATING||scooterService.ble.ready())return;
        scooterService.ble.connectLast();if(profile.mac.isEmpty())showScanner(false);redraw();
    }
    private boolean hasBluetoothPermission(){return Build.VERSION.SDK_INT>=31?checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED:checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    @Override public void requestBluetooth(){if(!resumed)return;if(hasBluetoothPermission()){ensureService();if(scooterService!=null){if(pendingScan){pendingScan=false;showScanner(true);}else if(pendingConnect){pendingConnect=false;connect();}}return;}requestPermissions(Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION},BLUETOOTH_PERMISSION);}
    @Override public void requestRideLocation(Runnable ready){
        if (scooterService != null && scooterService.simulating()) { toast(T.mainScanExitSimulation()); return; }
        if(destroyed||!resumed||ready==null)return;
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
                ||checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED){ready.run();return;}
        if(pendingRideLocation!=null)return;
        pendingRideLocation=ready;rideLocationResolved=rideLocationCancelled=false;
        requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION},RIDE_LOCATION_PERMISSION);
    }
    /** The ride location request on auto-record's behalf: notes a decline, and an Android cancellation (no answer) isn't one. This activity won't retry after a cancellation, so an empty result can't repeat on the refresh. */
    @Override public void requestAutoRecordLocation(Runnable ready){
        requestRideLocation(()->{
            if(rideLocationCancelled)autoRecordLocationCancelled=true;
            if(scooterService!=null)scooterService.autoRecordLocationResult(!rideLocationCancelled);
            if(ready!=null)ready.run();
        });
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==RIDE_LOCATION_PERMISSION){
            // A denied optional GPS permission still permits a scooter-only recording.
            // Android can deliver this before onResume. Wait until visible before
            // promoting the recorder to a location foreground service.
            rideLocationResolved=true;rideLocationCancelled=results.length==0;finishRidePermission();
            return;
        }
        if(request==BLUETOOTH_PERMISSION){if(hasBluetoothPermission())requestBluetooth();else{pendingConnect=pendingScan=false;toast(T.bleNearbyNeeded());}redraw();}
    }
    private void finishRidePermission(){
        if(destroyed||!resumed||!rideLocationResolved||pendingRideLocation==null)return;
        Runnable ready=pendingRideLocation;pendingRideLocation=null;rideLocationResolved=false;ready.run();rideLocationCancelled=false;
    }

    private void showScanner(boolean start){
        if (scooterService != null && scooterService.simulating()) { toast(T.mainScanExitSimulationFind()); return; }
        if(!resumed){pendingScan=true;return;}
        if(!hasBluetoothPermission()){pendingScan=true;requestBluetooth();return;}if(scooterService==null){pendingScan=true;ensureService();return;}
        if(scooterService.isRecording()){toast(T.mainScanStopRecording());return;}
        if(scooterService.ble.pending()!=null){toast(T.mainScanBusy());return;}
        android.bluetooth.BluetoothManager manager=getSystemService(android.bluetooth.BluetoothManager.class);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();if(adapter==null){toast(T.mainScanNoBluetooth());return;}if(!adapter.isEnabled()){pendingScan=true;startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),ENABLE_BLUETOOTH);return;}
        if(scooterService.profiles.active()==null){open(HubPages.SETTINGS_SCOOTERS);return;}
        if(start){scooterService.ble.disconnect();scooterService.ble.scan();}
        if(scanDialog!=null&&scanDialog.isShowing()){updateScan();return;}
        final AlertDialog[] sheet=new AlertDialog[1];
        LinearLayout content=sheetContent(T.mainScanTitle(),null,()->{if(sheet[0]!=null)sheet[0].dismiss();});
        LinearLayout status=row();status.setGravity(Gravity.CENTER_VERTICAL);status.setPadding(design.dp(2),0,0,design.dp(12));
        scanSpinner=new Spinner();status.addView(scanSpinner,new LinearLayout.LayoutParams(design.dp(14),design.dp(14)));
        scanStatus=design.caption("");scanStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,13*design.textScale);
        LinearLayout.LayoutParams statusText=new LinearLayout.LayoutParams(0,-2,1);statusText.leftMargin=design.dp(10);status.addView(scanStatus,statusText);
        content.addView(status);
        scanList=column();content.addView(scanList);
        LinearLayout actions=row();actions.setPadding(0,design.dp(6),0,0);
        TextView again=design.secondaryButton(T.mainScanAgain(),()->{if(scooterService!=null){scooterService.ble.scan();scanSignature="";updateScan();}});
        scanConnect=design.button(T.mainActionConnect(),this::connectSelectedScan);
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1);left.rightMargin=design.dp(5);actions.addView(again,left);
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,-2,1);right.leftMargin=design.dp(5);actions.addView(scanConnect,right);
        content.addView(actions);
        scanSignature="";scanSelected=null;
        scanDialog=showSheet(content);sheet[0]=scanDialog;
        scanDialog.setOnDismissListener(d->{scanDialog=null;scanList=null;scanStatus=null;scanSpinner=null;scanConnect=null;scanSelected=null;if(scooterService!=null&&scooterService.ble.state()==BleController.State.SCANNING)scooterService.ble.disconnect();});
        updateScan();
    }
    private void connectSelectedScan(){
        if(scooterService==null||scanSelected==null)return;
        for(ScanResult result:scanResults)if(result.getDevice().getAddress().equals(scanSelected)){scooterService.ble.connectDiscovered(result);if(scanDialog!=null)scanDialog.dismiss();redraw();return;}
    }
    private static int signalRank(int rssi){return rssi>=-60?4:rssi>=-70?3:rssi>=-80?2:1;}
    private void updateScan(){
        if(scanList==null||scooterService==null)return;scanResults.clear();scanResults.addAll(scooterService.ble.discoveries());
        boolean scanning=scooterService.ble.state()==BleController.State.SCANNING;
        ScooterProfiles.Profile profile=scooterService.profiles.active();String mine=profile==null?"":profile.mac;
        boolean present=false;for(ScanResult result:scanResults)if(result.getDevice().getAddress().equals(scanSelected))present=true;
        // The profile's own scooter starts selected; other scooters need a deliberate tap before Connect.
        if(!present){scanSelected=null;for(ScanResult result:scanResults)if(!mine.isEmpty()&&result.getDevice().getAddress().equalsIgnoreCase(mine))scanSelected=result.getDevice().getAddress();}
        scanStatus.setText(scanning?T.bleStatusSearching():scanResults.isEmpty()?T.mainScanNone():scanResults.size()==1?T.mainScanFoundOne():T.mainScanFoundMany(scanResults.size(),scanResults.size()));
        scanSpinner.setVisibility(scanning?View.VISIBLE:View.GONE);
        StringBuilder signature=new StringBuilder(String.valueOf(scanSelected)).append(':').append(profile==null?"":profile.id);
        for(ScanResult result:scanResults){String name=result.getScanRecord()==null?null:result.getScanRecord().getDeviceName();ScooterProfiles.Profile saved=scooterService.profiles.hardwareMatch(null,result.getDevice().getAddress(),null);signature.append('|').append(result.getDevice().getAddress()).append(':').append(signalRank(result.getRssi())).append(':').append(name).append(':').append(saved==null?"":saved.id+":"+saved.name);}
        if(!signature.toString().equals(scanSignature)){
            scanSignature=signature.toString();scanList.removeAllViews();
            for(ScanResult result:scanResults){
                String address=result.getDevice().getAddress();ScooterProfiles.Profile saved=scooterService.profiles.hardwareMatch(null,address,null);
                scanList.addView(scanOption(result,saved,address.equals(scanSelected)));
            }
        }
        scanConnect.setEnabled(scanSelected!=null);
    }
    /** Nearby scooter row (ph/10): scooter icon, name, signal bars; the selected scooter has a red outline. */
    private View scanOption(ScanResult result,ScooterProfiles.Profile saved,boolean selected){
        String name=result.getScanRecord()==null?null:result.getScanRecord().getDeviceName();if(name==null||name.isEmpty())name=T.mainScanNavee();
        if(saved!=null)name=saved.name;
        ScooterProfiles.Profile active=scooterService.profiles.active();boolean match=saved!=null&&active!=null&&saved.id.equals(active.id);
        if(saved!=null)name=saved.shownName();
        String address=result.getDevice().getAddress(),suffix=address.substring(Math.max(0,address.length()-5));
        int rank=signalRank(result.getRssi());String strength=rank==4?T.mainScanStrong():rank==3?T.mainScanGood():rank==2?T.mainScanFair():T.mainScanWeak();
        LinearLayout option=row();option.setGravity(Gravity.CENTER_VERTICAL);option.setPadding(design.dp(12),design.dp(10),design.dp(14),design.dp(10));option.setMinimumHeight(design.dp(64));
        android.graphics.drawable.GradientDrawable face=new android.graphics.drawable.GradientDrawable();face.setCornerRadius(design.dp(16));
        face.setColor(selected?Ui.withAlpha(design.accent,design.dark?26:18):design.surface);face.setStroke(design.dp(1.5f),selected?design.accent:design.surface);
        option.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(design.accent,30)),face,null));
        option.addView(design.iconSquare("scooter",design.accent),new LinearLayout.LayoutParams(design.dp(40),design.dp(40)));
        LinearLayout words=column();
        TextView title=design.text(name,15,true);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(title);
        // Every known address uses its saved name and label, including another profile's scooter.
        LinearLayout detail=row();detail.setGravity(Gravity.CENTER_VERTICAL);detail.setPadding(0,design.dp(3),0,0);
        if(saved!=null){
            TextView yours=design.text(match?T.mainScanYours():T.mainScanSaved(),11,true);yours.setTextColor(design.ok);yours.setPadding(design.dp(8),design.dp(1),design.dp(8),design.dp(2));
            yours.setBackground(design.shape(Ui.withAlpha(design.ok,design.dark?40:30),100,0));
            LinearLayout.LayoutParams chip=new LinearLayout.LayoutParams(-2,-2);chip.setMarginEnd(design.dp(8));detail.addView(yours,chip);
        }
        detail.addView(design.caption(suffix));words.addView(detail);
        LinearLayout.LayoutParams wordsParams=new LinearLayout.LayoutParams(0,-2,1);wordsParams.leftMargin=design.dp(12);wordsParams.rightMargin=design.dp(10);option.addView(words,wordsParams);
        SignalBars bars=new SignalBars(rank);int color=rank>=3?design.ok:design.orange;bars.set(rank,color,design.raised);
        option.addView(bars,new LinearLayout.LayoutParams(design.dp(18),design.dp(14)));
        option.setClickable(true);option.setFocusable(true);option.setSelected(selected);
        option.setContentDescription(T.mainScanOption(name,strength,saved==null?"":match?T.mainScanStateYours():T.mainScanStateSaved(),suffix));
        if(Build.VERSION.SDK_INT>=30)option.setStateDescription(selected?T.commonSelected():"");
        option.setOnClickListener(v->{scanSelected=address;scanSignature="";updateScan();});
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=design.dp(8);option.setLayoutParams(params);
        return option;
    }
    private Map<String,Telemetry.Metric> metrics(){LinkedHashMap<String,Telemetry.Metric> values=new LinkedHashMap<>();if(scooterService!=null)for(Telemetry.Metric metric:scooterService.ble.telemetry.metrics())values.put(metric.key,metric);return values;}
    private Double fresh(Map<String,Telemetry.Metric> metrics,String key,long age){Telemetry.Metric metric=metrics.get(key);return scooterService!=null&&scooterService.ble.connected()&&metric!=null&&metric.numericValue!=null&&metric.updatedMs>=0&&SystemClock.elapsedRealtime()-metric.updatedMs<=age?metric.numericValue:null;}
    private String speed(Double kmh){return kmh==null?"—":DisplayUnits.speed(kmh,preferences.getString("units","mi"));}
    private String featureLabel(ScooterCommands.Feature feature,int value){
        if(feature.key.equals("start_speed")&&value==0)return T.mainValueStandingStart();
        // Another model's variant has its own labels; speeds keep the rider's units.
        Msg oem=scooterService==null?null:scooterService.ble.decision(feature.key).oemText(value);
        if(oem!=null)return oem.render();
        return DisplayUnits.featureValueText(feature,value,preferences.getString(PREF_UNITS,"mi"));
    }
    /** A control's name inside a sentence: English keeps its lower-cased form; another language shows the name as it is. */
    private static CharSequence inSentence(ScooterCommands.Feature f){return Fmt.legacy()?f.label.toLowerCase(Locale.ROOT):f.label();}
    /** A short state word inside a sentence, on the same terms as inSentence. */
    private static String lowerInEnglish(String word){return Fmt.legacy()?word.toLowerCase(Locale.ROOT):word;}
    /** The decided variant when it is another model's (not the UT5 MAX catalogue), else null. */
    private FeatureVariant oemVariant(String key){
        if(scooterService==null)return null;CapabilityResolver.Decision d=scooterService.ble.decision(key);
        return d.variant!=null&&d.variant.decode!=FeatureVariant.Decode.UT5_FEATURE?d.variant:null;
    }
    /** A control that is Lab on the UT5 MAX but mapped normally (not Lab) for the connected model. */
    private boolean ordinaryOemRow(String key){
        FeatureVariant variant=oemVariant(key);
        return variant!=null&&!variant.lab&&scooterService.ble.decision(key).support==CapabilityResolver.Support.KNOWN_ENCODING;
    }
    /**
     * Sends at once, or first asks where the official app asks on that model (an OEM-mapped control off the
     * UT5 MAX). The UT5 MAX keeps today's direct controls.
     */
    private void confirmChange(ScooterCommands.Feature feature,int value,Runnable send){
        String caution=scooterService==null?null:ModelRegistry.caution(scooterService.ble.decision(feature.key),value);
        if(caution==null){send.run();return;}
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle(shortLabel(feature)).setMessage(caution)
                .setNegativeButton(T.commonCancel(),null).setPositiveButton(T.mainConfirmContinue(),(d,n)->{if(scooterService!=null)send.run();}).create();
        dialog.show();styleDialog(dialog,AlertDialog.BUTTON_POSITIVE);
    }
    private TextView metricLine(LinearLayout parent,String label){LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);TextView labelView=design.caption(label);line.addView(labelView,new LinearLayout.LayoutParams(0,-2,1));TextView value=design.text("—",16,true);value.setGravity(Gravity.RIGHT);line.addView(value);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=design.dp(8);p.bottomMargin=design.dp(8);parent.addView(line,p);return value;}
    private final class LoadingScreen implements AppScreen {
        private final LinearLayout view=column();
        /** With permission: a spinner while the service opens. Without it: the one button that unblocks it. */
        LoadingScreen(){
            LinearLayout card=design.card();card.setGravity(Gravity.CENTER_HORIZONTAL);card.setPadding(design.dp(18),design.dp(22),design.dp(18),design.dp(18));
            if(hasBluetoothPermission()){
                Spinner spinner=new Spinner();card.addView(spinner,new LinearLayout.LayoutParams(design.dp(28),design.dp(28)));
            }else{
                TextView allow=design.button(T.mainActionAllowNearby(),MainActivity.this::requestBluetooth);
                android.graphics.drawable.Drawable glyph=new android.graphics.drawable.Drawable(){
                    @Override public void draw(Canvas canvas){android.graphics.Rect b=getBounds();design.drawIcon(canvas,"bluetooth",design.onAccent,b.exactCenterX(),b.exactCenterY(),b.width());}
                    @Override public void setAlpha(int alpha){}
                    @Override public void setColorFilter(android.graphics.ColorFilter filter){}
                    @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
                };
                glyph.setBounds(0,0,design.dp(18),design.dp(18));allow.setCompoundDrawablesRelative(glyph,null,null,null);allow.setCompoundDrawablePadding(design.dp(8));
                // Wrapped width keeps the glyph beside its words.
                LinearLayout.LayoutParams allowSize=new LinearLayout.LayoutParams(-2,-2);allowSize.bottomMargin=design.dp(6);card.addView(allow,allowSize);
                TextView needed=design.caption(T.mainLoadingNeeded());needed.setGravity(Gravity.CENTER);card.addView(needed,new LinearLayout.LayoutParams(-1,-2));
            }
            LinearLayout.LayoutParams top=new LinearLayout.LayoutParams(-1,-2);top.topMargin=design.dp(12);view.addView(card,top);
        }
        @Override public View view(){return view;}
        @Override public void refresh(){}
    }
    /**
     * The scooter's settings rows on one Hub page (hub layout plan, PR 3). ControlsPages.Spec says which rows the page
     * shows and in what order. Every row is built on every page, so refresh() and search treat them alike; rows the
     * page doesn't show stay in their (detached) section bodies. The search page (spec.search; the Scooter tab's index
     * before PR 11) shows only the search field: typing finds any row on any page, grouped by section as before, and
     * the subject list hides.
     * Every page is one column, wide screens included, and so in the wide Home's page pane (HubPanes).
     */
    private final class ControlsScreen implements AppScreen {
        private final ControlsPages.Spec spec;
        private final LinearLayout view=column();
        private final ArrayList<ControlRow> rows=new ArrayList<>();
        private final ArrayList<ChargeDoneRow> chargeRows=new ArrayList<>();
        private final LinkedHashMap<String,LinearLayout> sections=new LinkedHashMap<>(), bodies=new LinkedHashMap<>();
        private final LinkedHashMap<String,TextView> labels=new LinkedHashMap<>();
        private final LinkedHashMap<View,String[]> extras=new LinkedHashMap<>();
        private final java.util.HashMap<View,String[]> shownExtras=new java.util.HashMap<>();
        private final TextView results,bannerText,bannerAction,clockButton,nameValue;
        private final EditText search;
        private final LinearLayout banner,readOnlyBanner,testBanner;private TextView identifyAction,testBannerText,testReviewAction;private String offeredKeys="";
        private final View headlightRow,clockSpinner;
        private final SettingLine clockLine,chargerLine;
        private final ScheduleTestRow scheduleTest;
        private final ChargeAlertRow chargeAlert,lowAlert;
        private final ChargeLiveRow chargeLive;
        private final TopSpeedCard topSpeed;
        /** Each row's id (a feature key, a ScooterMenuLayout hand-built id or ControlsPages.CHARGE_LIVE) and the "Done by" row glued under its parent. */
        private final java.util.HashMap<String,View> rowViews=new java.util.HashMap<>();
        private final java.util.HashMap<View,String> rowIds=new java.util.HashMap<>();
        private final java.util.HashMap<View,View> gluedBelow=new java.util.HashMap<>(),gluedTo=new java.util.HashMap<>();
        /** The page's own card, its rows in the spec's order; null on the index. */
        private final LinearLayout pageBody;
        /** Rows that open another page (card -> page id), and the "Experimental" entries of controls Lab tries on this model. */
        private final LinkedHashMap<View,String> links=new LinkedHashMap<>();
        private final LinkedHashMap<String,View> labEntries=new LinkedHashMap<>();
        /** The index's search results that open a light family's page (card -> page id): they carry the old zones row's words. */
        private final LinkedHashMap<View,String> searchLinks=new LinkedHashMap<>();
        /** The index's Maintenance row (search finds it; it opens Service › Maintenance reminders) and its value. */
        private TextView maintenanceValue;
        /** Shown under the index's search while nothing is typed (the subject list); null elsewhere. */
        private View indexList;
        private String query="";
        ControlsScreen(ControlsPages.Spec spec){
            this.spec=spec;
            // Disconnected: a dark-red banner explains that the grey values are the last seen settings.
            banner=row();banner.setGravity(Gravity.CENTER_VERTICAL);banner.setPadding(design.dp(14),design.dp(4),design.dp(8),design.dp(4));banner.setMinimumHeight(design.dp(60));
            banner.setBackground(design.shape(Ui.withAlpha(design.accent,design.dark?36:22),16,Ui.withAlpha(design.accent,design.dark?100:80)));
            banner.addView(design.icon("bluetooth",18,design.text),new LinearLayout.LayoutParams(design.dp(18),design.dp(18)));
            bannerText=design.text(T.mainControlsLastSeen(),13,false);
            LinearLayout.LayoutParams bannerWords=new LinearLayout.LayoutParams(0,-2,1);bannerWords.leftMargin=design.dp(10);bannerWords.rightMargin=design.dp(8);banner.addView(bannerText,bannerWords);
            bannerAction=pill(T.mainActionConnect(),MainActivity.this::connect);bannerAction.setContentDescription(T.mainActionConnectDescription());banner.addView(bannerAction);
            LinearLayout.LayoutParams bannerParams=new LinearLayout.LayoutParams(-1,-2);bannerParams.bottomMargin=design.dp(10);view.addView(banner,bannerParams);
            // Connected to a model outside the UT5 MAX layer: one banner; unmapped controls are hidden.
            readOnlyBanner=row();readOnlyBanner.setGravity(Gravity.CENTER_VERTICAL);readOnlyBanner.setPadding(design.dp(14),design.dp(4),design.dp(14),design.dp(4));readOnlyBanner.setMinimumHeight(design.dp(52));
            readOnlyBanner.setBackground(design.shape(Ui.withAlpha(design.accent,design.dark?36:22),16,Ui.withAlpha(design.accent,design.dark?100:80)));
            readOnlyBanner.addView(design.icon("lock",18,design.text),new LinearLayout.LayoutParams(design.dp(18),design.dp(18)));
            TextView readOnlyText=design.text(T.bleLimitedModel(),13,false);LinearLayout.LayoutParams readOnlyWords=new LinearLayout.LayoutParams(0,-2,1);readOnlyWords.leftMargin=design.dp(10);readOnlyWords.rightMargin=design.dp(8);readOnlyBanner.addView(readOnlyText,readOnlyWords);
            identifyAction=pill(T.mainControlsIdentify(),()->{if(scooterService!=null){scooterService.ble.identifyModel();toast(scooterService.ble.displayText());redraw();}});identifyAction.setContentDescription(T.mainControlsIdentifyDescription());identifyAction.setTag(TAG_IDENTIFY);readOnlyBanner.addView(identifyAction);
            readOnlyBanner.setVisibility(View.GONE);view.addView(readOnlyBanner,new LinearLayout.LayoutParams(-1,-2));
            ((LinearLayout.LayoutParams)readOnlyBanner.getLayoutParams()).bottomMargin=design.dp(10);
            // An unfinished guided test (restoration outstanding) stays visible until the owner reviews it.
            testBanner=row();testBanner.setGravity(Gravity.CENTER_VERTICAL);testBanner.setPadding(design.dp(14),design.dp(4),design.dp(14),design.dp(4));testBanner.setMinimumHeight(design.dp(52));
            testBanner.setBackground(design.shape(Ui.withAlpha(design.accent,design.dark?36:22),16,Ui.withAlpha(design.accent,design.dark?100:80)));
            testBanner.addView(design.icon("lab",18,design.text),new LinearLayout.LayoutParams(design.dp(18),design.dp(18)));
            testBannerText=design.text(T.mainControlsUnfinishedTest(),13,false);LinearLayout.LayoutParams testWords=new LinearLayout.LayoutParams(0,-2,1);testWords.leftMargin=design.dp(10);testWords.rightMargin=design.dp(8);testBanner.addView(testBannerText,testWords);
            testReviewAction=pill(T.mainControlsReview(),()->{GuidedTest running=scooterService==null?null:scooterService.ble.guidedTest();if(running!=null&&!running.terminal()&&running.keeps())open(HubPages.SERVICE_EXPERIMENTAL);else open(HubPages.SERVICE_COMPATIBILITY,HubNav.Args.recovery());});testReviewAction.setContentDescription(T.mainControlsReviewUnfinished());testReviewAction.setTag("scooter-review-test");testBanner.addView(testReviewAction);
            testBanner.setVisibility(View.GONE);view.addView(testBanner,new LinearLayout.LayoutParams(-1,-2));
            ((LinearLayout.LayoutParams)testBanner.getLayoutParams()).bottomMargin=design.dp(10);
            // The index: search over every page's rows. Other pages have no field (the index's search finds their rows).
            search=new EditText(MainActivity.this);search.setSingleLine(true);search.setHint(T.mainControlsSearch());design.styleSearch(search);search.setContentDescription(T.mainControlsSearchDescription());
            results=design.caption("");results.setPadding(design.dp(3),0,0,design.dp(8));
            if(spec.search){view.addView(search,design.searchLayout());SearchFocus.bind(search, getWindow());view.addView(results);}
            topSpeed=new TopSpeedCard();if(spec.search||spec.topSpeed)view.addView(topSpeed.card);
            for(String name:new String[]{"Ride","Battery","Lights","Line lights","Star lights","Dashboard & sound","Security","Profile"}){
                LinearLayout section=column();TextView label=design.sectionLabel(sectionTitle(name));section.addView(label);
                LinearLayout body=design.groupCard();section.addView(body);
                sections.put(name,section);bodies.put(name,body);labels.put(name,label);if(spec.search)view.addView(section);
            }
            ArrayList<ScooterCommands.Feature> ordered=new ArrayList<>();
            String[] priority={"cruise_control","energy_recovery","traction_control","hill_start_assist","parking_assist","hill_descent_control","start_speed",
                    "charge_limit","low_power","timed_charging","charge_start","auto_sensor","headlight","display_units","range_algorithm","turn_sound","lock_expiry"};
            for(String key:priority)ordered.add(ScooterCommands.find(key));
            for(ScooterCommands.Feature f:ScooterCommands.catalog())if(!java.util.Arrays.asList(priority).contains(f.key))ordered.add(f);
            // Light controls only other models have; hidden unless the connected model maps them.
            ordered.addAll(ScooterCommands.oemOnly());
            for(ScooterCommands.Feature f:ordered){
                // Lock, PIN and proximity stay out of this list. Lab (experimental) controls get a row that shows only
                // where another model maps them normally; maximum_speed's row replaces the top speed card the same way.
                if(f.key.equals("lock")||f.key.equals("pin")||f.key.equals("proximity_key"))continue;
                ControlRow control=new ControlRow(f);rows.add(control);addRow(groupOf(f),control.card);rowId(f.key,control.card);
                if(f.key.equals("timed_charging")||f.key.equals("charge_start")){
                    ChargeDoneRow done=new ChargeDoneRow(f.key.equals("charge_start"));chargeRows.add(done);
                    addRow("Battery",done.row.card);gluedBelow.put(control.card,done.row.card);gluedTo.put(done.row.card,control.card);
                    extras.put(done.row.card,new String[]{"Battery","charge done by finish charging deadline time"});
                    shownExtras.put(done.row.card,new String[]{rowSearchWords(HubSearchTables.ROW_CHARGE_DONE_BY),T.mainScheduleDoneBy()});
                }
            }
            // Lights page: a read-only first row says the front headlight is switched on the scooter itself.
            SettingLine headlight=new SettingLine(design.featureIcon("headlight"),T.mainRowHeadlight(),null);headlight.value.setText(T.mainRowHeadlightValue());
            headlight.line.setFocusable(true);headlight.line.setContentDescription(T.mainRowHeadlightDescription());
            headlightRow=headlight.card;LinearLayout lightsBody=bodies.get("Lights");
            if(lightsBody.getChildCount()>0){View line=new View(MainActivity.this);line.setBackgroundColor(design.raised);line.setTag(lightsBody.getChildAt(0));lightsBody.addView(line,0,new LinearLayout.LayoutParams(-1,design.dp(1)));}
            lightsBody.addView(headlightRow,0);extras.put(headlightRow,new String[]{"Lights","headlight front lamp power button"});
            clockLine=new SettingLine("history",M.mainRowClock(),null);
            clockSpinner=new Spinner();clockSpinner.setVisibility(View.GONE);clockLine.line.addView(clockSpinner,rowSpinnerSize());
            clockButton=pill(T.mainRowSync(),()->{if(scooterService!=null){scooterService.ble.syncClock();redraw();}});clockButton.setContentDescription(T.mainRowSyncDescription());
            LinearLayout.LayoutParams clockParams=new LinearLayout.LayoutParams(-2,-2);clockParams.leftMargin=design.dp(10);clockLine.line.addView(clockButton,clockParams);
            addRow("Battery",clockLine.card);extras.put(clockLine.card,new String[]{"Battery","sync scooter clock scheduled charging time timezone"});
            shownExtras.put(clockLine.card,new String[]{rowSearchWords(HubSearchTables.ROW_CLOCK_SYNC),T.mainRowClock()});
            scheduleTest=new ScheduleTestRow();addRow("Battery",scheduleTest.row.card);
            extras.put(scheduleTest.row.card,new String[]{"Battery","test scheduled charging check scooter clock schedule resume"});
            chargerLine=new SettingLine("battery",M.mainRowCharger(),null);chargerLine.chevron(MainActivity.this::showChargerEstimateChoice);
            chargerLine.line.setTag("estimate-charger-setting");chargerLine.value.setTag("estimate-charger-value");
            addRow("Battery",chargerLine.card);extras.put(chargerLine.card,new String[]{"Battery","charger for estimates automatic auto stock high speed charging time remaining"});
            shownExtras.put(chargerLine.card,new String[]{rowSearchWords(HubSearchTables.ROW_CHARGER),T.mainRowCharger()});
            chargeAlert=new ChargeAlertRow(false);lowAlert=new ChargeAlertRow(true);
            addRow("Battery",chargeAlert.row.card);extras.put(chargeAlert.row.card,new String[]{"Battery","charge alert battery alerts level reached full charged notify notification alarm sound"});
            shownExtras.put(chargeAlert.row.card,new String[]{rowSearchWords(HubSearchTables.ROW_CHARGE_ALERT),T.mainAlertCharge()});
            addRow("Battery",lowAlert.row.card);extras.put(lowAlert.row.card,new String[]{"Battery","low battery alert battery alerts notify notification alarm sound"});
            // Live charging: every model, shown under the Battery section; search finds it by its shown name.
            chargeLive=new ChargeLiveRow();
            String batteryGroup=groupOf(ScooterCommands.find("charge_limit"));
            addRow(batteryGroup,chargeLive.row.card);extras.put(chargeLive.row.card,new String[]{batteryGroup,M.chargeLiveRow().english()});
            shownExtras.put(chargeLive.row.card,new String[]{T.chargeLiveRow()});
            SettingLine security=new SettingLine("shield",T.mainRowSecurity(),null);security.chevron(()->open(HubPages.SECURITY));
            addRow("Security",security.card);extras.put(security.card,new String[]{"Security","pin password proximity unlock pairing bluetooth bond security"});
            // Rename stays in More › My scooters (and Home's edit mode); the index's search still finds it here.
            SettingLine identity=new SettingLine("pencil",T.mainRenameTitle(),null);identity.chevron(MainActivity.this::renameScooter);nameValue=identity.value;
            addRow("Profile",identity.card);extras.put(identity.card,new String[]{"Profile","scooter name rename profile"});
            shownExtras.put(identity.card,new String[]{rowSearchWords(HubSearchTables.ROW_SCOOTER_NAME),T.mainRenameTitle()});
            shownExtras.put(headlightRow,new String[]{rowSearchWords(HubSearchTables.ROW_HEADLIGHT_INFO),T.mainRowHeadlight()});
            shownExtras.put(scheduleTest.row.card,new String[]{rowSearchWords(HubSearchTables.ROW_SCHEDULE_TEST),T.mainScheduleTestRow()});
            shownExtras.put(lowAlert.row.card,new String[]{rowSearchWords(HubSearchTables.ROW_LOW_ALERT),T.mainAlertLow()});
            shownExtras.put(security.card,new String[]{rowSearchWords(HubSearchTables.ROW_SECURITY_LINK),T.mainRowSecurity()});
            rowId(ScooterMenuLayout.HEADLIGHT_INFO,headlightRow);rowId(ScooterMenuLayout.CLOCK_SYNC,clockLine.card);
            rowId(ScooterMenuLayout.SCHEDULE_TEST,scheduleTest.row.card);rowId(ScooterMenuLayout.CHARGER,chargerLine.card);rowId(ScooterMenuLayout.CHARGE_ALERT,chargeAlert.row.card);
            rowId(ScooterMenuLayout.LOW_ALERT,lowAlert.row.card);rowId(ScooterMenuLayout.SECURITY_LINK,security.card);rowId(ScooterMenuLayout.SCOOTER_NAME,identity.card);
            rowId(ScooterMenuLayout.TOP_SPEED,topSpeed.card);rowId(ControlsPages.CHARGE_LIVE,chargeLive.row.card);
            // Maintenance: a page of its own (Service › Maintenance reminders); the index's search still finds its row.
            LinearLayout maintenanceSection=column();TextView maintenanceLabel=design.sectionLabel(T.maintenanceTitle());maintenanceSection.addView(maintenanceLabel);
            LinearLayout maintenanceBody=design.groupCard();maintenanceSection.addView(maintenanceBody);
            sections.put(MaintenanceScreen.SECTION,maintenanceSection);bodies.put(MaintenanceScreen.SECTION,maintenanceBody);labels.put(MaintenanceScreen.SECTION,maintenanceLabel);if(spec.search)view.addView(maintenanceSection);
            SettingLine maintenanceLine=new SettingLine("gear",T.maintenanceRow(),null);maintenanceLine.chevron(()->open(HubPages.SERVICE_MAINTENANCE));maintenanceValue=maintenanceLine.value;
            maintenanceLine.line.setTag(MaintenanceScreen.ROW);
            addRow(MaintenanceScreen.SECTION,maintenanceLine.card);extras.put(maintenanceLine.card,new String[]{MaintenanceScreen.SECTION,MaintenanceScreen.NO_WORDS});
            shownExtras.put(maintenanceLine.card,new String[]{rowSearchWords(HubSearchTables.ROW_MAINTENANCE),T.maintenanceRow()});
            rowId(MaintenanceScreen.ROW,maintenanceLine.card);
            // Line lights and Star lights replaced the zones row: the index's search finds their links by its words.
            if(spec.search)for(String target:ControlsPages.SEARCHED_LINKS){
                String section=groupOf(ScooterCommands.find(ControlsPages.spec(target).rows.get(0)));View card=linkCard(target);
                // Each family's own words, its title among them (#404 review): "star" doesn't list Line lights.
                addRow(section,card);extras.put(card,new String[]{section,HubSearchTables.pageWords(target)});
                // And the zones row's translated words (main_search_zones) in another language, as Hub search (#404 review, P3 3); shownWords drops them for a query naming only the other family.
                Msg zones=HubText.lightZonesSearchWords();String zonesShown=zones.render();
                shownExtras.put(card,zonesShown.equals(zones.english())?new String[]{HubText.rowTitle(target).render()}:new String[]{HubText.rowTitle(target).render(),zonesShown});searchLinks.put(card,target);
            }
            pageBody=spec.search?null:design.groupCard();
            if(pageBody!=null){
                for(String id:spec.rows){
                    String target=ControlsPages.linkTarget(id);
                    if(target!=null){place(linkRow(target));continue;}
                    View card=rowViews.get(id);if(card==null)continue;
                    place(card);View done=gluedBelow.get(card);if(done!=null)place(done);
                    // Any control may be a Lab try on some model (ModelRegistry.experimental): each gets an entry, shown by labOffered.
                    for(ControlRow control:rows)if(control.card==card){View entry=labEntry(control.feature);labEntries.put(id,entry);place(entry);}
                }
                view.addView(pageBody,new LinearLayout.LayoutParams(-1,-2));
            }
            search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){query=s.toString();applyFilter();}public void afterTextChanged(Editable text){}});
            applyFilter();
        }
        /** A row's shown search words; a light link's shared zones words drop out when the query names only the other family (HubSearch.otherFamilyOnly, #404 review). */
        private String[] shownWords(View card,String query){
            String[] shown=shownExtras.get(card);String linked=searchLinks.get(card);
            return shown!=null&&shown.length>1&&linked!=null&&HubSearch.otherFamilyOnly(linked,query)?new String[]{shown[0]}:shown;
        }
        /** Moves a row into the page's card (out of its section body, whose divider stays behind), with a divider before it. */
        private void place(View card){
            if(card.getParent() instanceof ViewGroup)((ViewGroup)card.getParent()).removeView(card);
            if(pageBody.getChildCount()>0){View line=new View(MainActivity.this);line.setBackgroundColor(design.raised);line.setTag(card);pageBody.addView(line,new LinearLayout.LayoutParams(-1,design.dp(1)));}
            pageBody.addView(card);
        }
        /** A row that opens one of this page's sub-pages. */
        private View linkRow(String target){View card=linkCard(target);links.put(card,target);return card;}
        private View linkCard(String target){
            SettingLine link=new SettingLine(linkIcon(target),HubText.rowTitle(target),null);link.chevron(()->open(target));
            link.line.setTag("hub-link-"+target);return link.card;
        }
        private String linkIcon(String page){
            switch(page){
                case HubPages.CONTROLS_ASSISTS:return design.featureIcon("hill_start_assist");
                case HubPages.CONTROLS_LIMITS:return "speed";
                case HubPages.BATTERY_MODES:case HubPages.BATTERY_ESTIMATES:return "battery";
                // The chart its Charge recordings row draws, not the light pages' star (#387 review).
                case HubPages.BATTERY_HISTORY:return "chart";
                default:return "star";
            }
        }
        /**
         * A control the Lab tries on this model, where it is not mapped as an ordinary row: an "Experimental" entry that
         * opens its test (none becomes a one-tap toggle here).
         */
        private View labEntry(ScooterCommands.Feature feature){
            SettingLine entry=new SettingLine(design.featureIcon(feature.key),feature.label(),null);
            // The badge sits under the name on every entry (beside a short name it moved to the row's end).
            entry.setSub(HubText.experimentalBadge().toString());entry.chevron(()->open(HubPages.SERVICE_EXPERIMENTAL,HubNav.Args.inspect(feature.key)));
            entry.line.setContentDescription(HubText.experimentalRowDescription(feature.label()));
            entry.line.setTag("hub-experimental-"+feature.key);return entry.card;
        }
        /** A row id this page would show now (a link's page counts it): an ordinary row or its Experimental entry; hand-built rows count as shown. */
        private boolean rowShown(String id,java.util.List<String> lab){
            View card=rowViews.get(id);
            for(ControlRow row:rows)if(row.card==card)return rowOffered(id)||id.equals("maximum_speed")&&topSpeedCard()||labOffered(id,lab);
            return true;
        }
        private void refreshMaintenance(){
            MaintenanceView.Summary summary=MaintenanceScreen.summary(scooterService);
            String value=MaintenanceScreen.rowValue(summary);
            if(!value.contentEquals(maintenanceValue.getText()))maintenanceValue.setText(value);
            maintenanceValue.setTextColor(summary!=null&&summary.dot()?design.accent:design.muted);
        }
        private void rowId(String id,View card){rowViews.put(id,card);rowIds.put(card,id);}
        /** The row's id, a "Done by" row answering for its parent's. */
        private String idOf(View card){return rowIds.get(gluedTo.containsKey(card)?gluedTo.get(card):card);}
        /** The index's subject list: hidden while search shows results. */
        void indexList(View list){indexList=list;applyFilter();}
        private String groupOf(ScooterCommands.Feature f){
            if(f.key.startsWith("line_"))return "Line lights";if(f.key.startsWith("star_"))return "Star lights";
            if(f.key.equals("headlight")||f.key.equals("auto_sensor")||ScooterCommands.oemOnly(f.key))return "Lights";
            return f.group.equals("Ride")?"Ride":f.group.equals("Battery")?"Battery":f.group.equals("Security")?"Security":"Dashboard & sound";
        }
        private void addRow(String group,View child){LinearLayout body=bodies.get(group);if(body.getChildCount()>0){View line=new View(MainActivity.this);line.setBackgroundColor(design.raised);line.setTag(child);body.addView(line,new LinearLayout.LayoutParams(-1,design.dp(1)));}body.addView(child);}
        private void applyFilter(){
            String needle=normalize(query);boolean searching=spec.search&&!needle.isEmpty();int count=0;
            java.util.List<String> lab=modelLab();
            java.util.HashSet<String> visible=new java.util.HashSet<>();
            for(ControlRow row:rows){String group=groupOf(row.feature);boolean match=rowOffered(row.feature.key)&&(searching?row.haystack.contains(needle)||row.matchesShown(query):spec.includes(row.feature.key));row.card.setVisibility(match?View.VISIBLE:View.GONE);if(match){count++;visible.add(group);}}
            for(Map.Entry<View,String[]> e:extras.entrySet()){
                String[] metadata=e.getValue();
                boolean match=e.getKey()==clockLine.card&&!offered(KEY_CLOCK_SYNC)||e.getKey()==scheduleTest.row.card&&!scheduleTestOffered()?false:searching?normalize(metadata[1]).contains(needle)||StatsLabels.matchesShown(query,shownWords(e.getKey(),query)):spec.includes(idOf(e.getKey()));
                if("charge-done-by".equals(e.getKey().getTag())&&!chargeDoneOffered()||"charge-alert".equals(e.getKey().getTag())&&!chargeAlertsOffered())match=false;
                String linked=searchLinks.get(e.getKey());if(linked!=null&&!ControlsPages.linkShown(linked,id->rowShown(id,lab)))match=false;
                e.getKey().setVisibility(match?View.VISIBLE:View.GONE);if(match){count++;visible.add(metadata[0]);}
            }
            boolean speed=topSpeedCard()&&(searching?TOP_SPEED_WORDS.contains(needle)||StatsLabels.matchesShown(query,rowSearchWords(HubSearchTables.ROW_TOP_SPEED),T.mainSpeedTitle()):spec.topSpeed);
            topSpeed.card.setVisibility(speed?View.VISIBLE:View.GONE);if(speed)count++;
            for(Map.Entry<String,View> e:labEntries.entrySet())e.getValue().setVisibility(labOffered(e.getKey(),lab)?View.VISIBLE:View.GONE);
            // A sub-page's link shows only while that page has a row or an Experimental entry to show.
            for(Map.Entry<View,String> e:links.entrySet())e.getKey().setVisibility(ControlsPages.linkShown(e.getValue(),id->rowShown(id,lab))?View.VISIBLE:View.GONE);
            for(Map.Entry<String,LinearLayout> e:sections.entrySet()){
                e.getValue().setVisibility(visible.contains(e.getKey())?View.VISIBLE:View.GONE);
                hideDividers(bodies.get(e.getKey()));
            }
            // A page's card shows only while a row in it does: no empty strip on a model without any of its rows (#387 review).
            if(pageBody!=null)pageBody.setVisibility(hideDividers(pageBody)?View.VISIBLE:View.GONE);
            results.setVisibility(searching?View.VISIBLE:View.GONE);results.setText(count==0?T.mainSearchNone():T.mainSearchResults(count,count));
            if(indexList!=null)indexList.setVisibility(searching?View.GONE:View.VISIBLE);
        }
        /** A divider shows only between two shown rows (each divider's tag is the row after it). True when a row shows. */
        private boolean hideDividers(LinearLayout body){
            boolean hasVisible=false;
            for(int i=0;i<body.getChildCount();i++){View child=body.getChildAt(i);if(child.getTag() instanceof View)child.setVisibility(hasVisible&&((View)child.getTag()).getVisibility()==View.VISIBLE?View.VISIBLE:View.GONE);else if(child.getVisibility()==View.VISIBLE)hasVisible=true;}
            return hasVisible;
        }
        @Override public boolean busy(){return !query.isEmpty();}
        // The controls rules are MainActivity's (Hub search shares them); these keep the names validations call on this screen.
        private boolean offered(String key){return MainActivity.this.offered(key);}
        private boolean topSpeedCard(){return MainActivity.this.topSpeedCard();}
        private boolean rowOffered(String key){return MainActivity.this.rowOffered(key);}
        private boolean labOffered(String key,java.util.List<String> lab){return MainActivity.this.labOffered(key,lab);}
        private java.util.List<String> modelLab(){return MainActivity.this.modelLab();}
        /** A row by its id for search's result (a "Done by" row: the one shown); a Lab try's Experimental entry when that shows instead. */
        @Override public View anchor(String id){
            if(HubSearchTables.ROW_CHARGE_DONE_BY.equals(id)){for(ChargeDoneRow row:chargeRows)if(row.row.card.getVisibility()==View.VISIBLE)return row.row.card;return null;}
            View card=rowViews.get(id),lab=labEntries.get(id);
            if(lab!=null&&lab.getVisibility()==View.VISIBLE&&(card==null||card.getVisibility()!=View.VISIBLE))return lab;
            return card;
        }
        @Override public boolean back(){
            if(!query.isEmpty()){search.setText("");return true;}
            return false;
        }
        @Override public View view(){return view;}
        @Override public void close(){if(controlDialog!=null){controlDialog.dismiss();controlDialog=null;}choiceSheet=null;}
        @Override public void refresh(){
            refreshMaintenance();
            for(ControlRow row:rows)row.refresh();
            for(ChargeDoneRow row:chargeRows)row.refresh();
            scheduleTest.refresh();topSpeed.refresh();chargeAlert.refresh();lowAlert.refresh();chargeLive.refresh();
            BleController ble=scooterService==null?null:scooterService.ble;boolean ready=ble!=null&&ble.ready();
            boolean connecting=ble!=null&&!ready&&(ble.connected()||ble.state()==BleController.State.CONNECTING||ble.state()==BleController.State.AUTHENTICATING
                    ||ble.state()==BleController.State.SCANNING||ble.state()==BleController.State.WAITING);
            banner.setVisibility(ready?View.GONE:View.VISIBLE);
            // Two words keep it clear the grey values are not live; the Connect pill says the rest.
            bannerText.setText(connecting?T.bleStatusConnecting():T.mainControlsLastSeen());
            bannerAction.setVisibility(connecting||ControlsPages.CONNECT_ABOVE.contains(spec.page)?View.GONE:View.VISIBLE);
            readOnlyBanner.setVisibility(ble!=null&&ble.limitedModel()?View.VISIBLE:View.GONE);
            java.util.List<TestJournal.Entry> unfinished=ble==null?java.util.Collections.<TestJournal.Entry>emptyList():ble.unresolvedTests();
            boolean recordsLost=ble!=null&&!ble.testRecordsHealthy();
            int elsewhere=ble==null?0:ble.unresolvedTestsElsewhere(),orphaned=ble==null?0:ble.orphanedTests().size();
            boolean testing=ble!=null&&ble.testing();
            boolean reviewNeeded=recordsLost||!unfinished.isEmpty()||elsewhere>0||orphaned>0||testing;
            testBanner.setVisibility(reviewNeeded?View.VISIBLE:View.GONE);
            if(reviewNeeded){
                String text=recordsLost?T.mainTestRecordsUnavailable()
                        :!unfinished.isEmpty()?(unfinished.get(0).isTry()
                                ?(unfinished.size()>1?T.mainTestTryMore(ScooterCommands.find(unfinished.get(0).feature).label(),Fmt.integer(unfinished.size()-1)):T.mainTestTry(ScooterCommands.find(unfinished.get(0).feature).label()))
                                :(unfinished.size()>1?T.mainTestTestMore(ScooterCommands.find(unfinished.get(0).feature).label(),Fmt.integer(unfinished.size()-1)):T.mainTestTest(ScooterCommands.find(unfinished.get(0).feature).label())))
                        :orphaned>0?T.mainTestRemoved()
                        :elsewhere>0?T.mainTestAnother()
                        :ble.guidedTest()!=null&&ble.guidedTest().keeps()?T.mainTestTrying(inSentence(ScooterCommands.find(ble.guidedTest().feature))):T.mainTestInProgress();
                if(!text.contentEquals(testBannerText.getText()))testBannerText.setText(text);
                testReviewAction.setContentDescription(recordsLost?T.mainTestReviewRecords():testing?T.mainTestReviewSetting():!unfinished.isEmpty()&&unfinished.get(0).isTry()?T.mainTestReviewTry():T.mainControlsReviewUnfinished());
            }
            identifyAction.setVisibility(ble!=null&&ble.modelUnidentified()?View.VISIBLE:View.GONE);
            // Rows follow the model's decisions; re-filter when the set of mapped controls changes.
            StringBuilder offered=new StringBuilder();for(ControlRow row:rows)offered.append(rowOffered(row.feature.key)?'1':'0');offered.append(offered("clock_sync")?'1':'0').append(topSpeedCard()?'1':'0').append(scheduleTestOffered()?'1':'0').append(chargeAlertsOffered()?'1':'0');
            java.util.List<String> lab=modelLab();for(String key:labEntries.keySet())offered.append(labOffered(key,lab)?'1':'0');
            for(String target:searchLinks.values())offered.append(ControlsPages.linkShown(target,id->rowShown(id,lab))?'1':'0');
            for(String target:links.values())offered.append(ControlsPages.linkShown(target,id->rowShown(id,lab))?'1':'0');
            if(!offered.toString().equals(offeredKeys)){offeredKeys=offered.toString();applyFilter();}
            boolean clockPending=ble!=null&&"clock_sync".equals(ble.pending());
            CapabilityResolver.Decision clockDecision=ble==null?null:ble.decision("clock_sync");
            boolean clockReady=clockDecision!=null&&(clockDecision.available()||clockDecision.blockedByRide());clockButton.setEnabled(clockReady);
            clockLine.name.setTextColor(clockReady?design.text:design.muted);
            // Results are row state: a spinner while sending, then "Sent 14:02" (no clock readback exists) or a short failure.
            clockSpinner.setVisibility(clockPending?View.VISIBLE:View.GONE);
            String clock=ble==null?null:ble.commandResults().get("clock_sync");String chip=clockPending?null:resultChip(ble,"clock_sync");
            long sent=ble==null?0:ble.commandResultTime("clock_sync");
            boolean accepted=!clockPending&&clock!=null&&clock.startsWith("Accepted")&&sent>0;
            clockLine.value.setText(accepted?T.mainRowClockValue(DisplayTime.clock(MainActivity.this,preferences,sent)):chip!=null?chip:"");
            clockLine.value.setTextColor(chip!=null&&!accepted?design.danger:design.muted);
            clockLine.line.setContentDescription(clockPending?T.mainRowClockSending():accepted?T.mainRowClockSent(DisplayTime.clock(MainActivity.this,preferences,sent)):chip!=null?T.mainRowClockChip(chip):T.mainRowClock());
            ScooterProfiles.Profile profile=scooterService==null?null:scooterService.profiles.active();nameValue.setText(profile==null?T.mainRowNameNotSet():profile.name);
            boolean chargerEditable=profile!=null&&!scooterService.isChargeRecording();
            if(profile!=null)nameValue.setText(profile.shownName());
            chargerLine.line.setEnabled(chargerEditable);chargerLine.name.setTextColor(chargerEditable?design.text:design.muted);
            // Name the family estimates actually use, and why when it is not the saved choice.
            ScooterService.ChargerBasis basis=scooterService==null?ScooterService.ChargerBasis.UNKNOWN:scooterService.chargerBasis();
            ChargeSessionStore.Charger effective=scooterService==null?null:scooterService.effectiveChargeCharger();
            String family=effective==ChargeSessionStore.Charger.HIGH_SPEED?T.mainRowHighSpeed():T.mainRowStock();
            chargerLine.value.setText(effective==null?T.mainRowAutomatic():basis==ScooterService.ChargerBasis.DETECTED?T.mainRowAutoFamily(family):family);
            chargerLine.setSub(basis==ScooterService.ChargerBasis.RECORDING?T.mainRowRecordingLabel()
                    :basis==ScooterService.ChargerBasis.DETECTED?T.mainRowDetected()
                    :scooterService!=null&&scooterService.isChargeRecording()?T.mainRowStopRecording():null);
            if(choiceSheet!=null)choiceSheet.refresh();
        }
    }
    /**
     * Settings rows show only controls this model has a known encoding for (UT5 MAX: all; a profile that
     * has never connected: all, unavailable). The range
     * estimate row is Volt's own source choice, so it always shows. (The scooter pages and search share these rules.)
     */
    private boolean offered(String key){
        BleController ble=scooterService==null?null:scooterService.ble;
        if(!key.equals("clock_sync")&&ScooterCommands.find(key).experimental)return ordinaryOemRow(key);
        return ble==null||key.equals("range_algorithm")||ble.controlOffered(key);
    }
    /** The UT5 MAX top speed card (four fixed choices); another model's maximum speed table is an ordinary row. */
    private boolean topSpeedCard(){return offered("maximum_speed")&&oemVariant("maximum_speed")==null;}
    private boolean rowOffered(String key){return key.equals("maximum_speed")?offered(key)&&oemVariant(key)!=null:offered(key);}
    /**
     * More's Experimental controls lists the control for this model (its Lab list and experimentalDecision, exactly as
     * MoreScreen.refreshLab builds it), and it is not an ordinary row on its page (ControlsPages.labEntryShown). The
     * scooter pages' Experimental entries and Hub search share this rule.
     */
    private boolean labOffered(String key){return labOffered(key,modelLab());}
    private boolean labOffered(String key,java.util.List<String> lab){
        if(scooterService==null)return false;
        boolean ordinary=rowOffered(key)||key.equals("maximum_speed")&&topSpeedCard();
        return ControlsPages.labEntryShown(ordinary,lab.contains(key),!ordinary&&lab.contains(key)&&scooterService.ble.experimentalDecision(key).experimental());
    }
    private String labModel="";private java.util.List<String> labKeys=java.util.Collections.emptyList();
    /** The model's Lab list (ModelRegistry.experimental), rebuilt when the model changes, as MoreScreen keeps it. */
    private java.util.List<String> modelLab(){
        if(scooterService==null)return java.util.Collections.emptyList();
        DeviceDescriptor d=scooterService.ble.descriptor();ModelRegistry.Resolution r=ModelRegistry.resolve(d);
        String model=r.layer+"|"+r.matchedPrefix+"|"+r.conflict+"|"+d.rulePid()+"|"+d.pidSource();
        if(!model.equals(labModel)){labModel=model;labKeys=ModelRegistry.experimental(d);}
        return labKeys;
    }
    /**
     * A hand-built row's search words in the current language (main_search_*, volt:purpose "search keywords"): the
     * scooter pages' search and Hub search both match them. Null for a row with none (Live charging: its name only).
     */
    private static String rowSearchWords(String row){
        switch(row){
            case HubSearchTables.ROW_CHARGE_DONE_BY:return T.mainSearchDoneBy();
            case HubSearchTables.ROW_CLOCK_SYNC:return T.mainSearchClock();
            case HubSearchTables.ROW_CHARGER:return T.mainSearchCharger();
            case HubSearchTables.ROW_CHARGE_ALERT:return T.mainSearchChargeAlert();
            case HubSearchTables.ROW_SCOOTER_NAME:return T.mainSearchName();
            case HubSearchTables.ROW_HEADLIGHT_INFO:return T.mainSearchHeadlight();
            case HubSearchTables.ROW_SCHEDULE_TEST:return T.mainSearchTest();
            case HubSearchTables.ROW_LOW_ALERT:return T.mainSearchLowAlert();
            case HubSearchTables.ROW_SECURITY_LINK:return T.mainSearchSecurity();
            case HubSearchTables.ROW_MAINTENANCE:return T.maintenanceSearch();
            case HubSearchTables.ROW_TOP_SPEED:return T.mainSearchTopSpeed();
            default:return null;
        }
    }
    /** The scooter pages' row groups, in order. A group's English is its id (maps, stored section: actions); its render() is shown. */
    private static Msg[] settingsGroups(){return new Msg[]{M.mainGroupRide(),M.mainGroupBattery(),M.mainGroupLights(),M.mainGroupLineLights(),M.mainGroupStarLights(),M.mainGroupDashboardSound(),M.mainGroupSecurity(),M.mainGroupProfile()};}
    /** The shown name of a settings group id; an id with no group of its own is shown as it is. */
    private static String sectionTitle(String section){for(Msg group:settingsGroups())if(group.english().equals(section))return group.render();return section;}
    private static final String TOP_SPEED_WORDS=normalize("Top speed maximum speed sport mode limit mph km/h");
    private static String normalize(String text){return text==null?"":text.toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+","");}
    /** Dashboard actions reuse the normal control dispatch and its live-state safety checks. */
    private void quickAction(String id) {
        QuickAccessActions.Entry entry = QuickAccessActions.find(id);
        // Clean Home's own (HomeQuickActions; never in the Quick access catalog). Recording has Home's meaning:
        // it stops a recording ride or starts one, never leaves simulation, and says why a start is refused.
        if (HomeQuickActions.RECORD.equals(id)) { RideRecordingControl rec = RideRecordingApp.of(this); if (rec.recording()) rec.stop(); else rec.start(); return; }
        // Battery alerts, in place of Charge done by on models without scheduled charging (owner's answer 11).
        if (HomeQuickActions.CHARGE_ALERTS.equals(id)) {
            // A stored or restored four can keep charge alerts after the model turns out not to offer them:
            // open the Battery page (which shows what this scooter has) instead of ignoring the tap.
            if (scooterService != null && scooterService.profiles.active() != null && !chargeAlertsOffered()) { openRoute(HubPages.BATTERY, null); return; }
            showChargeAlerts(); return;
        }
        boolean ridingMode = HomeQuickActions.RIDING.equals(id);
        if (entry == null && !ridingMode) return;
        if (id.equals("stats") || id.equals("rides")) { openRoute(HubRoutes.pageFor(id), null); return; }
        if (id.equals("charge_done_by")) { toggleChargeDoneBy(); return; }
        if (id.equals("charge_done_time")) { showChargeDoneTime(); return; }
        if (id.equals("feature:timed_charging")) { toggleManualCharge(); return; }
        if (id.equals("feature:charge_start")) { showChargingTime(); return; }
        if (scooterService == null) { toast(T.mainQuickConnectFirst()); return; }
        if (ridingMode) { openRiding(); return; }
        // A quick-control group keeps its pop-up sheet; a shortcut to a whole settings page opens that page (HubRoutes).
        String sheet = HubRoutes.sheetFor(id);
        if (sheet != null) { showQuickSettings(sheet); return; }
        String page = HubRoutes.pageFor(id);
        if (page != null) { closeQuickMenu(); openRoute(page, null); return; }
        if (id.equals("section:Profile")) { renameScooter(); return; }
        ScooterCommands.Feature feature = entry.feature();
        if (feature == null) return;
        if (feature.key.equals("range_algorithm")) { closeQuickMenu(); showRangeSource(); return; }
        // The same resolver as the settings rows and the write gate: an unmapped control says why.
        CapabilityResolver.Decision decision = scooterService.ble.decision(feature.key);
        if (decision.support != CapabilityResolver.Support.KNOWN_ENCODING) { toast(decision.reasonMessage()); return; }
        if (decision.variant != null && decision.variant.lab) { openRoute(HubRoutes.forFeature(feature, true), HubNav.Args.inspect(feature.key)); return; }
        // Another model's region, firmware or full-ID rule refuses it: say why, as its Scooter row does (never on the UT5 MAX).
        if (decision.reason == CapabilityResolver.Reason.REGION_RULE || decision.reason == CapabilityResolver.Reason.FIRMWARE_UNKNOWN
                || decision.reason == CapabilityResolver.Reason.FIRMWARE_OLD || decision.reason == CapabilityResolver.Reason.FULL_PID_NEEDED) {
            toast(decision.reasonMessage()); return;
        }
        String securityPage = HubRoutes.forFeature(feature, false);
        if (securityPage != null) { openRoute(securityPage, null); return; }
        BleController ble = scooterService.ble;
        if (!ble.ready()) { toast(T.mainQuickConnectChange()); return; }
        if (ble.pending() != null) { toast(T.mainQuickWaitChange()); return; }
        boolean fresh = feature.type == ScooterCommands.Type.TIME
                ? scooterService.readingFresh("scheduled_charge_time_raw")
                : scooterService.featureFresh(feature.key);
        Integer actual = ble.featureValue(feature.key);
        if (!fresh || (feature.type != ScooterCommands.Type.TIME && actual == null)) {
            toast(T.mainQuickNoFresh()); return;
        }
        if (entry.directToggle(decision)) {
            int target = actual == 1 ? 0 : 1;
            confirmChange(feature, target, () -> {
                if (!ble.set(feature.key, target)) toast(ble.displayText());
                // The stable window survives a synchronous readback/redraw. Answer the
                // accepted tap once, using its requested direction, before rebuilding Home.
                else Haptics.toggle(getWindow().getDecorView(), target == 1);
                redraw();
            });
        } else {
            closeQuickMenu();
            if (controlDialog != null) { controlDialog.dismiss(); controlDialog = null; }
            editFeature(feature);
        }
    }

    private void closeQuickMenu() {
        AlertDialog old = quickMenuDialog; quickMenuDialog = null;
        quickMenuRows.clear();
        quickChargeRows.clear();
        if (old != null) old.dismiss();
    }

    /**
     * A quick-control group shown above Home, keeping the underlying layout untouched: Lights, and the Ride, Battery,
     * Line lights, Star lights and Dashboard & sound groups (HubRoutes.sheetFor). A single setting changed in a moment
     * keeps its pop-up sheet (owner's answer 3, #383); whole settings pages open as pages.
     */
    private void showQuickSettings(String section) {
        closeQuickMenu();
        if (controlDialog != null) { controlDialog.dismiss(); controlDialog = null; }
        final AlertDialog[] self = new AlertDialog[1];
        LinearLayout body = sheetContent(sectionTitle(section), null, () -> { if (self[0] != null) self[0].dismiss(); });
        body.setTag("quick-settings-sheet");
        LinearLayout settings = design.groupCard(); body.addView(settings);
        ArrayList<ScooterCommands.Feature> features = new ArrayList<>(ScooterCommands.catalog());
        // Another model's own light controls join the Lights sheet only where it maps them.
        for (ScooterCommands.Feature f : ScooterCommands.oemOnly()) if (scooterService.ble.controlOffered(f.key)) features.add(f);
        for (ScooterCommands.Feature f : features) {
            if (f.experimental && !ordinaryOemRow(f.key) || f.sensitive || f.key.equals("lock") || f.key.equals("proximity_key")) continue;
            boolean match = section.equals("Lights") && (f.key.equals("headlight") || f.key.equals("auto_sensor") || ScooterCommands.oemOnly(f.key)
                            || f.key.startsWith("line_") || f.key.startsWith("star_"))
                    || section.equals("Line lights") && f.key.startsWith("line_")
                    || section.equals("Star lights") && f.key.startsWith("star_")
                    || section.equals("Dashboard & sound") && (f.group.equals("Preferences")
                            || f.key.equals("turn_sound"))
                    || section.equals(f.group);
            if (!match) continue;
            if (settings.getChildCount() > 0) design.divider(settings);
            ControlRow row = new ControlRow(f); quickMenuRows.add(row);
            settings.addView(row.card); row.refresh();
            if((f.key.equals("timed_charging")||f.key.equals("charge_start"))&&chargeDoneOffered()){
                design.divider(settings);ChargeDoneRow done=new ChargeDoneRow(f.key.equals("charge_start"));
                quickChargeRows.add(done);settings.addView(done.row.card);done.refresh();
            }
        }
        AlertDialog dialog = showSheet(body); self[0] = dialog; quickMenuDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (quickMenuDialog == dialog) { quickMenuDialog = null; quickMenuRows.clear(); quickChargeRows.clear(); }
        });
    }

    /** How long a failure chip outlives the first fresh readback that follows it. */
    private static final long RESULT_CHIP_MS=4000;
    /**
     * Short-lived row state for a command result: "Rejected" or "Not confirmed", or null. It clears once a fresh
     * readback arrives a few seconds after the result, so the row returns to showing the scooter's own value.
     */
    private String resultChip(BleController ble,String key){
        String evidence=ble==null?null:ble.commandResults().get(key);
        if(evidence==null||evidence.equals("Confirmed")||evidence.startsWith("Setting confirmed")||evidence.startsWith("Accepted; no clock"))return null;
        long at=ble.commandResultTime(key);
        // Saved results from earlier launches feed Command evidence only; chips are for this session's changes.
        if(at<=0)return null;
        if(ble.ready()&&System.currentTimeMillis()-ble.statusAge()>at+RESULT_CHIP_MS)return null;
        return evidence.startsWith("Rejected")?T.mainChipRejected():T.mainChipNotConfirmed();
    }
    private LinearLayout.LayoutParams rowSpinnerSize(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(design.dp(20),design.dp(20));p.setMarginStart(design.dp(10));return p;}
    /** A small red chip for a failed change; it sits on the row just before the value or switch. */
    private TextView failureChip(){
        TextView chip=design.text("",11.5f,true);chip.setTextColor(design.danger);chip.setMaxLines(1);
        chip.setPadding(design.dp(8),design.dp(2),design.dp(8),design.dp(3));chip.setBackground(design.shape(Ui.withAlpha(design.danger,design.dark?40:28),100,0));
        chip.setVisibility(View.GONE);return chip;
    }
    /** Phone-width labels; search still matches the catalog's full names and descriptions. */
    private String shortLabel(ScooterCommands.Feature f){
        if(f.key.equals("charge_start"))return T.mainShortChargeStart();
        if(f.key.equals("charge_limit"))return T.mainShortChargeLimit();
        // "Dashboard" also names the Home tab; this setting is the scooter's own display.
        if(f.key.equals(KEY_DISPLAY_UNITS))return T.mainShortDisplayUnits();
        if(f.key.endsWith("_boost_color"))return T.mainShortBoostColor();
        if(f.key.endsWith("_pattern"))return T.mainShortPattern();
        if(f.key.endsWith("_color"))return T.mainShortColor();
        return f.label().render();
    }
    private String rowDetail(ScooterCommands.Feature f){
        if(f.key.equals("parking_assist"))return T.mainDetailParking();
        if(f.key.equals("low_power"))return T.mainDetailLowPower();
        return null;
    }
    private String rowIcon(ScooterCommands.Feature f){
        if(f.key.equals("hill_start_assist"))return "walk";
        if(f.key.equals("charge_start")||f.key.equals("timed_charging"))return "clock";
        return design.featureIcon(f.key);
    }
    /** Picker sheets have no explanation line, except Lock duration's lock-out warning. */
    private String explanation(ScooterCommands.Feature f){
        return f.key.equals(KEY_LOCK_EXPIRY)?f.description().toString():null;
    }
    private boolean inlineChoice(ScooterCommands.Feature f){return f.type==ScooterCommands.Type.CHOICE&&(f.key.startsWith("line_")||f.key.startsWith("star_"));}
    /** Short value forms for the right edge of a row; pickers still list the full labels. */
    private String valueText(ScooterCommands.Feature feature,int value){
        if(feature.key.equals("range_algorithm") && scooterService != null && scooterService.followsRidingMode()) return T.mainAutoRange();
        if(feature.key.equals(KEY_DISPLAY_UNITS)&&(value==0||value==1))return value==0?T.mainValueMiles():T.mainValueKilometers();
        String label=featureLabel(feature,value);
        if(lockHours(feature,value)>0)return T.mainValueHoursShort(Fmt.integer(lockHours(feature,value)));
        if(feature.key.equals("lock_expiry"))return label.replace(" hours"," h");
        return label;
    }
    /** Lock duration's hours for an option value (the same table on every model), or 0 for a value that has none. */
    private static int lockHours(ScooterCommands.Feature feature,int value){return feature.key.equals("lock_expiry")?DisplayUnits.lockHours(value):0;}
    private android.graphics.drawable.RippleDrawable rowRipple(){
        return new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(design.accent,26)),null,design.shape(android.graphics.Color.WHITE,10,0));
    }
    /** Small red pill action; the inset keeps a 48dp touch target around the visible pill. */
    private TextView pill(String label,Runnable action){
        TextView pill=design.text(label,12.5f,true);pill.setGravity(Gravity.CENTER);
        pill.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{design.dim,design.onAccent}));
        pill.setPadding(design.dp(16),0,design.dp(16),0);pill.setMinHeight(design.dp(48));pill.setMinWidth(design.dp(64));
        android.graphics.drawable.StateListDrawable fill=new android.graphics.drawable.StateListDrawable();
        fill.addState(new int[]{-android.R.attr.state_enabled},design.shape(design.raised,100,0));
        fill.addState(new int[]{},design.shape(design.accent,100,0));
        pill.setBackground(new android.graphics.drawable.InsetDrawable(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(android.graphics.Color.WHITE,50)),fill,null),0,design.dp(7),0,design.dp(7)));
        pill.setContentDescription(label);pill.setClickable(true);pill.setFocusable(true);
        pill.setOnClickListener(v->{if(action!=null)action.run();});
        design.pressFeedback(pill);
        return pill;
    }
    /** One divided row inside a group card: boxed icon, label with optional sub-text, then a value, switch or action. */
    private final class SettingLine {
        final LinearLayout card=column(),line=row(),words=column();
        final TextView name,sub,value;
        SettingLine(String icon,CharSequence label,CharSequence detail){
            line.setGravity(Gravity.CENTER_VERTICAL);line.setBaselineAligned(false);line.setMinimumHeight(design.dp(60));line.setPadding(0,design.dp(8),0,design.dp(8));
            name=design.text(label,15,false);name.setMaxLines(2);design.decorate(name,icon);
            value=design.text("",14,false);value.setTextColor(design.muted);value.setGravity(Gravity.END);value.setMaxLines(1);
            value.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout heading=new LinearLayout(MainActivity.this){
                @Override protected void onMeasure(int widthSpec,int heightSpec){
                    int available=Math.max(0,MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight());
                    boolean hasValue=value.getVisibility()!=View.GONE&&value.length()>0;
                    float titleWidth=android.text.Layout.getDesiredWidth(name.getText(),name.getPaint())+name.getCompoundPaddingLeft()+name.getCompoundPaddingRight();
                    float valueWidth=android.text.Layout.getDesiredWidth(value.getText(),value.getPaint())+value.getCompoundPaddingLeft()+value.getCompoundPaddingRight();
                    // The outer row has already reserved room for its switch, swatches,
                    // status and action. Decide from intrinsic text widths, independent
                    // of our previous orientation, and measure the chosen layout once.
                    boolean stack=hasValue&&MeasureSpec.getMode(widthSpec)!=MeasureSpec.UNSPECIFIED
                            &&Math.ceil(titleWidth)+Math.ceil(valueWidth)+design.dp(10)>available;
                    setOrientation(stack?VERTICAL:HORIZONTAL);
                    LinearLayout.LayoutParams np=(LinearLayout.LayoutParams)name.getLayoutParams();
                    np.width=stack?-1:0;np.weight=stack?0:1;
                    LinearLayout.LayoutParams vp=(LinearLayout.LayoutParams)value.getLayoutParams();
                    vp.width=stack?-1:-2;vp.setMarginStart(stack?name.getCompoundPaddingStart():hasValue?design.dp(10):0);
                    vp.topMargin=stack?design.dp(3):0;vp.resolveLayoutDirection(getLayoutDirection());
                    int gravity=(stack?Gravity.START:Gravity.END)|Gravity.TOP;if(value.getGravity()!=gravity)value.setGravity(gravity);
                    super.onMeasure(widthSpec,heightSpec);
                }
            };
            heading.setGravity(Gravity.CENTER_VERTICAL);heading.setBaselineAligned(false);
            heading.addView(name,new LinearLayout.LayoutParams(0,-2,1));heading.addView(value,new LinearLayout.LayoutParams(-2,-2));
            words.addView(heading,new LinearLayout.LayoutParams(-1,-2));
            sub=design.caption("");sub.setPaddingRelative(icon==null||icon.isEmpty()?0:design.dp(42),design.dp(3),0,0);words.addView(sub);setSub(detail);
            line.addView(words,new LinearLayout.LayoutParams(0,-2,1));
            card.addView(line);
        }
        void chevron(Runnable action){
            View chevron=design.icon("chevron",15,design.muted);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(design.dp(15),design.dp(15));cp.leftMargin=design.dp(6);line.addView(chevron,cp);
            line.setClickable(true);line.setFocusable(true);line.setBackground(rowRipple());line.setOnClickListener(v->action.run());
        }
        void setSub(String text){sub.setText(text==null?"":text);sub.setVisibility(text==null||text.isEmpty()?View.GONE:View.VISIBLE);}
        void setSub(CharSequence text){setSub(text==null?null:text.toString());}
    }
    private boolean chargeAlertsOffered(){return scooterService!=null&&scooterService.chargeAlertsOffered();}
    /** Why an alert can't reach the owner now, or null; shared by the rows and the sheet. */
    private String chargeAlertBlocked(ChargeAlertPolicy.Settings a){
        if(!a.charge&&!a.low)return null;
        if(!a.notify&&!a.alarm)return T.mainAlertChooseDelivery();
        if(scooterService!=null&&(!scooterService.alertWatchAllowed()||a.notify&&!a.alarm&&!scooterService.alertNotificationsAllowed()))return T.mainAlertNotificationsOff();
        return null;
    }
    /** Scooter › Battery, models without a native charge limit: one row per alert; both open the same sheet. */
    private final class ChargeAlertRow {
        final SettingLine row;final boolean low;
        ChargeAlertRow(boolean low){
            this.low=low;row=new SettingLine(low?"battery":"bolt",low?M.mainAlertLow():M.mainAlertCharge(),null);
            row.card.setTag("charge-alert");row.line.setTag(low?"charge-alert-low-row":"charge-alert-charge-row");row.value.setTag(low?"charge-alert-low-value":"charge-alert-charge-value");
            row.chevron(MainActivity.this::showChargeAlerts);
        }
        void refresh(){
            ChargeAlertPolicy.Settings a=scooterService==null?ChargeAlertPolicy.Settings.OFF:scooterService.chargeAlerts();
            boolean on=low?a.low:a.charge;
            String value=!on?T.commonOff():low?T.mainAlertBelow(Fmt.integer(a.lowBelow)):ChargeAlertPolicy.levelText(a.chargeAt);
            String sub=!on?null:chargeAlertBlocked(a);
            if(sub==null&&on&&!low&&scooterService!=null&&scooterService.chargeAlertWatching())sub=T.mainAlertWatching();
            if(!value.contentEquals(row.value.getText()))row.value.setText(value);
            String subKind=sub!=null&&on&&chargeAlertBlocked(a)==null?WATCHING:null;
            row.setSub(sub);row.sub.setTextColor(sub!=null&&!WATCHING.equals(subKind)?design.danger:design.muted);
            row.line.setContentDescription(sub==null?T.mainLabelValue(low?T.mainAlertLow():T.mainAlertCharge(),value):T.mainLabelValueDetail(low?T.mainAlertLow():T.mainAlertCharge(),value,sub));
        }
    }
    /** Scooter › Battery › Live charging: On or Off, and why the card can't show when notifications are off. */
    private final class ChargeLiveRow {
        final SettingLine row;
        ChargeLiveRow(){
            row=new SettingLine("bolt",M.chargeLiveRow(),null);
            row.card.setTag("charge_live");row.line.setTag("charge_live_row");row.value.setTag("charge_live_value");
            row.chevron(MainActivity.this::showChargeLive);
        }
        void refresh(){
            boolean on=scooterService==null||scooterService.chargeLiveEnabled();
            String value=on?T.commonOn():T.commonOff();
            String sub=on&&scooterService!=null&&!scooterService.chargeLiveAllowed()?T.mainAlertNotificationsOff():null;
            if(!value.contentEquals(row.value.getText()))row.value.setText(value);
            row.setSub(sub);row.sub.setTextColor(design.danger);
            row.line.setContentDescription(sub==null?T.mainLabelValue(T.chargeLiveRow(),value):T.mainLabelValueDetail(T.chargeLiveRow(),value,sub));
        }
    }
    private ChargeLiveSheet chargeLiveSheet;
    private void showChargeLive(){
        if(scooterService==null)return;
        if(controlDialog!=null)controlDialog.dismiss();
        chargeLiveSheet=new ChargeLiveSheet();
    }
    /** Volt's own choices for live charging, saved as they change; nothing here sends a scooter command. */
    private final class ChargeLiveSheet {
        final AlertDialog dialog;
        final Switch toggle;
        final SegmentedSelector lock;
        final LinearLayout permission;
        ChargeLiveSheet(){
            final AlertDialog[] self=new AlertDialog[1];
            LinearLayout body=sheetContent(T.chargeLiveRow(),T.chargeLiveIntro(),
                    ()->{if(self[0]!=null)self[0].dismiss();});
            body.setTag("charge_live_sheet");
            LinearLayout card=design.groupCard();body.addView(card,new LinearLayout.LayoutParams(-1,-2));
            SettingLine line=new SettingLine("bolt",M.chargeLiveRow(),M.chargeLiveSwitchDetail());
            toggle=new Haptics.FeedbackSwitch(MainActivity.this);toggle.setShowText(false);toggle.setMinHeight(design.dp(48));toggle.setMinimumWidth(design.dp(54));
            design.styleSwitch(toggle);toggle.setTag("charge_live_switch");toggle.setContentDescription(line.name.getText());
            toggle.setOnClickListener(v->save());
            LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,-2);tp.leftMargin=design.dp(10);line.line.addView(toggle,tp);
            line.line.setClickable(true);line.line.setFocusable(true);line.line.setBackground(rowRipple());line.line.setOnClickListener(v->toggle.performClick());
            card.addView(line.card);
            body.addView(design.sectionLabel(T.chargeLiveLockTitle()));
            lock=new SegmentedSelector(design,T.chargeLiveLockTitle(),new String[]{T.chargeLiveLockDetails(),T.chargeLiveLockLevel(),T.chargeLiveLockHidden()},choice->{
                if(scooterService==null){dialog().dismiss();return;}
                scooterService.setChargeLiveDetail(ChargeLiveState.Detail.values()[choice]);
            });
            lock.setTag("charge_live_lock");
            body.addView(lock,new LinearLayout.LayoutParams(-1,-2));
            // Notifications off: the card can't show, so say so and take the owner to the one place that fixes it.
            permission=row();permission.setGravity(Gravity.CENTER_VERTICAL);permission.setTag("charge_live_permission");
            permission.setPadding(design.dp(14),design.dp(4),design.dp(8),design.dp(4));permission.setMinimumHeight(design.dp(56));
            permission.setBackground(design.shape(Ui.withAlpha(design.accent,design.dark?36:22),16,Ui.withAlpha(design.accent,design.dark?100:80)));
            TextView permissionText=design.text(T.chargeLivePerm(),13,false);
            LinearLayout.LayoutParams pw=new LinearLayout.LayoutParams(0,-2,1);pw.rightMargin=design.dp(8);permission.addView(permissionText,pw);
            TextView allow=pill(T.mainAlertAllow(),MainActivity.this::allowChargeAlertNotifications);allow.setContentDescription(T.mainAlertAllowDescription());
            permission.addView(allow);
            LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,-2);pp.topMargin=design.dp(12);body.addView(permission,pp);
            TextView when=design.caption(T.chargeLiveWhen());
            when.setPadding(design.dp(4),design.dp(12),design.dp(4),0);body.addView(when);
            dialog=showSheet(body);self[0]=dialog;controlDialog=dialog;
            dialog.setOnDismissListener(d->{if(chargeLiveSheet==this)chargeLiveSheet=null;if(controlDialog==dialog)controlDialog=null;redraw();});
            refresh();
        }
        AlertDialog dialog(){return dialog;}
        void save(){
            if(destroyed||scooterService==null){dialog.dismiss();return;}
            boolean on=toggle.isChecked();
            scooterService.setChargeLiveEnabled(on);
            // Turning it on with notifications off asks for them once, as the battery alerts do.
            if(on&&!scooterService.chargeLiveAllowed())allowChargeAlertNotifications();
            refresh();redraw();
        }
        void refresh(){
            if(destroyed||scooterService==null){dialog.dismiss();return;}
            boolean on=scooterService.chargeLiveEnabled();
            toggle.setChecked(on);
            lock.select(scooterService.chargeLiveDetail().ordinal(),false);
            lock.setAlpha(on?1f:0.5f);
            permission.setVisibility(on&&!scooterService.chargeLiveAllowed()?View.VISIBLE:View.GONE);
        }
    }
    private ChargeAlertSheet chargeAlertSheet;
    private void showChargeAlerts(){
        if(scooterService==null||scooterService.profiles.active()==null){toast(T.mainAlertChooseScooter());return;}
        if(!chargeAlertsOffered())return;
        if(controlDialog!=null)controlDialog.dismiss();
        chargeAlertSheet=new ChargeAlertSheet();
    }
    /** Volt's own per-scooter choices, saved as they change; nothing here sends a scooter command. */
    private final class ChargeAlertSheet {
        final String profile=scooterService.profiles.active().id;
        final AlertDialog dialog;
        final SettingLine chargeLine,lowLine,notifyLine,alarmLine;
        final Switch charge,low,notify,alarm;
        final SeekBar chargeLevel,lowLevel;
        final View chargeLevels,lowLevels;
        final TextView deliveryNote,permissionText,permissionAction;
        final LinearLayout permission;
        boolean dragging;
        ChargeAlertSheet(){
            final AlertDialog[] self=new AlertDialog[1];
            LinearLayout body=sheetContent(T.mainAlertTitle(),T.mainAlertIntro(),
                    ()->{if(self[0]!=null)self[0].dismiss();});
            body.setTag("charge-alert-sheet");
            LinearLayout levels=design.groupCard();body.addView(levels,new LinearLayout.LayoutParams(-1,-2));
            chargeLine=new SettingLine("bolt",M.mainAlertCharge(),null);charge=sheetSwitch(levels,chargeLine,"charge-alert-charge");
            chargeLevel=levelSlider((ChargeAlertPolicy.CHARGE_MAX-ChargeAlertPolicy.CHARGE_MIN)/ChargeAlertPolicy.STEP,T.mainAlertChargeLevel(),"charge-alert-charge-level");
            chargeLevels=levelRow(levels,chargeLevel,T.fmtPercent(Fmt.integer(ChargeAlertPolicy.CHARGE_MIN)),T.mainAlertFull());
            design.divider(levels);
            lowLine=new SettingLine("battery",M.mainAlertLow(),null);low=sheetSwitch(levels,lowLine,"charge-alert-low");
            lowLevel=levelSlider((ChargeAlertPolicy.LOW_MAX-ChargeAlertPolicy.LOW_MIN)/ChargeAlertPolicy.STEP,T.mainAlertLowLevel(),"charge-alert-low-level");
            lowLevels=levelRow(levels,lowLevel,T.fmtPercent(Fmt.integer(ChargeAlertPolicy.LOW_MIN)),T.fmtPercent(Fmt.integer(ChargeAlertPolicy.LOW_MAX)));
            body.addView(design.sectionLabel(T.mainAlertWith()));
            LinearLayout delivery=design.groupCard();body.addView(delivery,new LinearLayout.LayoutParams(-1,-2));
            notifyLine=new SettingLine("screen",M.mainAlertNotify(),null);notify=sheetSwitch(delivery,notifyLine,"charge-alert-notify");
            design.divider(delivery);
            alarmLine=new SettingLine("sound",M.mainAlertAlarm(),M.mainAlertAlarmDetail());alarm=sheetSwitch(delivery,alarmLine,"charge-alert-alarm");
            deliveryNote=design.caption(T.mainAlertNeedDelivery());deliveryNote.setTextColor(design.danger);deliveryNote.setTag("charge-alert-delivery-note");
            deliveryNote.setPadding(design.dp(4),design.dp(8),design.dp(4),0);body.addView(deliveryNote);
            // Notifications off: say what that means and take the owner to the one place that fixes it.
            permission=row();permission.setGravity(Gravity.CENTER_VERTICAL);permission.setTag("charge-alert-permission");
            permission.setPadding(design.dp(14),design.dp(4),design.dp(8),design.dp(4));permission.setMinimumHeight(design.dp(56));
            permission.setBackground(design.shape(Ui.withAlpha(design.accent,design.dark?36:22),16,Ui.withAlpha(design.accent,design.dark?100:80)));
            permissionText=design.text("",13,false);LinearLayout.LayoutParams pw=new LinearLayout.LayoutParams(0,-2,1);pw.rightMargin=design.dp(8);permission.addView(permissionText,pw);
            permissionAction=pill(T.mainAlertAllow(),MainActivity.this::allowChargeAlertNotifications);permissionAction.setTag("charge-alert-allow");permission.addView(permissionAction);
            LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,-2);pp.topMargin=design.dp(12);body.addView(permission,pp);
            TextView background=design.caption(T.mainAlertBackground());
            background.setPadding(design.dp(4),design.dp(12),design.dp(4),0);background.setTag("charge-alert-background");body.addView(background);
            TextView saver=design.caption(T.mainAlertSaver());
            saver.setPadding(design.dp(4),design.dp(8),design.dp(4),0);saver.setTag("charge-alert-battery-hint");body.addView(saver);
            TextView settings=design.text(T.mainAlertAppSettings(),14,false);settings.setTypeface(design.medium());settings.setTextColor(design.accent);
            settings.setMinHeight(design.dp(48));settings.setGravity(Gravity.CENTER_VERTICAL);settings.setPadding(design.dp(4),0,design.dp(4),0);
            settings.setClickable(true);settings.setFocusable(true);settings.setContentDescription(T.mainAlertAppSettingsDescription());settings.setTag("charge-alert-app-settings");
            settings.setOnClickListener(v->openAppSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS));
            body.addView(settings,new LinearLayout.LayoutParams(-2,-2));
            dialog=showSheet(body);self[0]=dialog;controlDialog=dialog;
            dialog.setOnDismissListener(d->{if(chargeAlertSheet==this)chargeAlertSheet=null;if(controlDialog==dialog)controlDialog=null;redraw();});
            refresh();
        }
        Switch sheetSwitch(LinearLayout card,SettingLine line,String tag){
            Switch toggle=new Haptics.FeedbackSwitch(MainActivity.this);toggle.setShowText(false);toggle.setMinHeight(design.dp(48));toggle.setMinimumWidth(design.dp(54));
            design.styleSwitch(toggle);toggle.setTag(tag);toggle.setContentDescription(line.name.getText());
            toggle.setOnClickListener(v->save());
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.leftMargin=design.dp(10);line.line.addView(toggle,p);
            line.line.setClickable(true);line.line.setFocusable(true);line.line.setBackground(rowRipple());line.line.setOnClickListener(v->toggle.performClick());
            card.addView(line.card);return toggle;
        }
        SeekBar levelSlider(int steps,String label,String tag){
            SeekBar slider=new SeekBar(MainActivity.this);slider.setMax(steps);slider.setTag(tag);slider.setContentDescription(label);
            slider.setProgressTintList(ColorStateList.valueOf(design.accent));slider.setThumbTintList(ColorStateList.valueOf(design.accent));slider.setProgressBackgroundTintList(ColorStateList.valueOf(design.outline));
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onStartTrackingTouch(SeekBar s){dragging=true;}
                public void onProgressChanged(SeekBar s,int n,boolean user){if(!user)return;if(dragging)labels(chosen());else save();}
                public void onStopTrackingTouch(SeekBar s){dragging=false;save();}
            });
            return slider;
        }
        View levelRow(LinearLayout card,SeekBar slider,String low,String high){
            LinearLayout box=column();box.addView(slider,new LinearLayout.LayoutParams(-1,design.dp(48)));
            LinearLayout ends=row();ends.setPadding(design.dp(4),0,design.dp(4),design.dp(8));ends.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            ends.addView(design.caption(low),new LinearLayout.LayoutParams(0,-2,1));TextView end=design.caption(high);end.setGravity(Gravity.END);ends.addView(end,new LinearLayout.LayoutParams(-2,-2));
            box.addView(ends,new LinearLayout.LayoutParams(-1,-2));card.addView(box,new LinearLayout.LayoutParams(-1,-2));return box;
        }
        ChargeAlertPolicy.Settings chosen(){
            return new ChargeAlertPolicy.Settings(charge.isChecked(),ChargeAlertPolicy.CHARGE_MIN+chargeLevel.getProgress()*ChargeAlertPolicy.STEP,
                    low.isChecked(),ChargeAlertPolicy.LOW_MIN+lowLevel.getProgress()*ChargeAlertPolicy.STEP,notify.isChecked(),alarm.isChecked());
        }
        boolean current(){return !destroyed&&scooterService!=null&&scooterService.profiles.active()!=null&&profile.equals(scooterService.profiles.active().id)&&scooterService.chargeAlertsOffered();}
        void save(){
            if(!current()){dialog.dismiss();return;}
            scooterService.setChargeAlerts(chosen());refresh();redraw();
        }
        void labels(ChargeAlertPolicy.Settings a){
            chargeLine.value.setText(ChargeAlertPolicy.levelText(a.chargeAt));lowLine.value.setText(T.mainAlertBelow(Fmt.integer(a.lowBelow)));
            if(Build.VERSION.SDK_INT>=30){chargeLevel.setStateDescription(ChargeAlertPolicy.levelText(a.chargeAt));lowLevel.setStateDescription(T.mainAlertBelow(Fmt.integer(a.lowBelow)));}
        }
        void refresh(){
            if(!current()){dialog.dismiss();return;}
            ChargeAlertPolicy.Settings a=scooterService.chargeAlerts();
            charge.setChecked(a.charge);low.setChecked(a.low);notify.setChecked(a.notify);alarm.setChecked(a.alarm);
            if(!dragging){chargeLevel.setProgress((a.chargeAt-ChargeAlertPolicy.CHARGE_MIN)/ChargeAlertPolicy.STEP);lowLevel.setProgress((a.lowBelow-ChargeAlertPolicy.LOW_MIN)/ChargeAlertPolicy.STEP);labels(a);}
            chargeLevels.setVisibility(a.charge?View.VISIBLE:View.GONE);lowLevels.setVisibility(a.low?View.VISIBLE:View.GONE);
            chargeLine.value.setVisibility(a.charge?View.VISIBLE:View.GONE);lowLine.value.setVisibility(a.low?View.VISIBLE:View.GONE);
            deliveryNote.setVisibility((a.charge||a.low)&&!a.notify&&!a.alarm?View.VISIBLE:View.GONE);
            boolean app=notificationsOn(),alerts=scooterService.alertNotificationsAllowed(),watch=scooterService.alertWatchAllowed();
            String text=!app?T.mainAlertPermApp()
                    :!watch?T.mainAlertPermWatch()
                    :a.notify&&!alerts?T.mainAlertPermAlerts():null;
            permission.setVisibility(text==null?View.GONE:View.VISIBLE);if(text!=null&&!text.contentEquals(permissionText.getText()))permissionText.setText(text);
            permissionAction.setContentDescription(T.mainAlertAllowDescription());
        }
    }
    private boolean notificationsOn(){
        android.app.NotificationManager manager=getSystemService(android.app.NotificationManager.class);return manager!=null&&manager.areNotificationsEnabled();
    }
    /** Android 13+ asks once; after that (or when a channel is off) only Android's notification settings can change it. */
    private void allowChargeAlertNotifications(){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
                &&(!preferences.getBoolean("notification_prompted",false)||shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))){
            preferences.edit().putBoolean("notification_prompted",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_PERMISSION);return;
        }
        openAppSettings(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
    }
    private void openAppSettings(String action){
        Intent intent=new Intent(action);
        if(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS.equals(action))intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName());
        else intent.setData(Uri.parse("package:"+getPackageName()));
        try{startActivity(intent);}catch(android.content.ActivityNotFoundException|SecurityException unavailable){
            try{startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}
            catch(android.content.ActivityNotFoundException|SecurityException missing){toast(T.mainAlertOpenSettings());}
        }
    }
    /** An alert that fired while Volt was open: a dialog instead of a notification; closing it stops the alarm. */
    private void maybeShowChargeAlert(){
        if(scooterService==null||!resumed||onboarding!=null||chargeAlertDialog!=null&&chargeAlertDialog.isShowing())return;
        String[] alert=scooterService.pendingChargeAlert();if(alert==null)return;
        boolean sounding=scooterService.chargeAlarmPlaying();
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle(alert[0]).setMessage(alert[1])
                .setPositiveButton(sounding?T.mainAlertStopAlarm():T.commonOk(),null).create();
        dialog.setOnDismissListener(d->{if(scooterService!=null)scooterService.stopChargeAlarm();if(chargeAlertDialog==dialog)chargeAlertDialog=null;});
        chargeAlertDialog=dialog;dialog.show();styleDialog(dialog,AlertDialog.BUTTON_POSITIVE);
        scooterService.chargeAlertShown();
    }
    private boolean chargeDoneOffered(){
        return scooterService==null||scooterService.ble==null
                ||scooterService.ble.controlOffered("timed_charging")&&scooterService.ble.controlOffered("charge_start");
    }
    private void scheduleResult(String message){
        if(destroyed)return;
        if(message!=null)toast(scooterService==null?message:ChargeScheduleText.shown(message));
        redraw();
    }
    private String doneByActionBlocked(ChargeScheduleController.View charge){
        if(!charge.doneByEnabled)return charge.doneByBlockedReason;
        if(scooterService==null||!scooterService.ble.ready())return T.mainScheduleConnectYourScooter();
        return charge.busy?T.mainScheduleWaitScooterChange():null;
    }
    private void toggleChargeDoneBy(){toggleChargeDoneBy(true);}
    private void toggleChargeDoneBy(boolean feedback){
        if(scooterService==null){toast(T.commonConnectChargerFirst());return;}
        if(scooterService.isChargeMeasuring()){showChargeMeasure();return;}
        ChargeScheduleController.View charge=scooterService.chargeSchedule();
        String blocked=doneByActionBlocked(charge);if(blocked!=null){toast(blocked);return;}
        boolean enable=!charge.doneByEnabled;
        if(enable&&scooterService.chargeMeasureOffered()){offerChargeMeasure();return;}
        scooterService.setChargeDoneByEnabled(enable,this::scheduleResult);
        if(feedback)Haptics.toggle(getWindow().getDecorView(),enable);redraw();
    }
    /** A bounded connection is the owner's choice: ask, and say what it does, before measuring. */
    private void offerChargeMeasure(){
        boolean background=preferences.getBoolean("background_connection",false);
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle(T.mainScheduleMeasureTitle())
                .setMessage(background?T.mainScheduleMeasureBody():T.mainScheduleMeasureBodyForeground())
                .setPositiveButton(T.mainScheduleMeasureFirst(),(d,which)->{
                    if(scooterService==null)return;
                    // Conditions can change while the dialog is open; never leave the request unanswered.
                    if(scooterService.startChargeMeasure())toast(T.mainScheduleMeasuringToast(Fmt.integer(scooterService.chargeMeasureMinutesLeft())));
                    else scooterService.setChargeDoneByEnabled(true,this::scheduleResult);
                    redraw();
                })
                .setNegativeButton(T.mainScheduleScheduleNow(),(d,which)->{if(scooterService!=null)scooterService.setChargeDoneByEnabled(true,this::scheduleResult);redraw();})
                .create();
        controlDialog=dialog;dialog.show();styleDialog(dialog,AlertDialog.BUTTON_POSITIVE);
    }
    private void showChargeMeasure(){
        if(scooterService.chargeMeasureArming()){toast(T.mainScheduleSettingSchedule());return;}
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle(T.mainScheduleMeasuringTitle())
                .setMessage(T.mainScheduleMeasuringBody(Fmt.integer(scooterService.chargeMeasureMinutesLeft())))
                .setPositiveButton(T.mainScheduleKeepMeasuring(),null)
                .setNeutralButton(T.mainScheduleScheduleNow(),(d,which)->{if(scooterService!=null)scooterService.scheduleChargeNow();redraw();})
                .setNegativeButton(T.mainScheduleStopMeasuring(),(d,which)->{if(scooterService!=null)scooterService.cancelChargeMeasure();redraw();})
                .create();
        controlDialog=dialog;dialog.show();styleDialog(dialog,AlertDialog.BUTTON_POSITIVE);
    }
    /** A scheduled start that passed without charging: say so plainly and offer to turn the schedule off. */
    private void maybeShowMissedStart(){
        if(scooterService==null||!resumed||onboarding!=null||missedStartDialog!=null&&missedStartDialog.isShowing()||scheduleTestDialog!=null&&scheduleTestDialog.isShowing())return;
        ChargeScheduleController.View charge=scooterService.chargeSchedule();ChargeScheduleController.Missed missed=charge.missed;
        if(missed==null||charge.busy||missedStartOffered==missed.startWallMs)return;
        missedStartOffered=missed.startWallMs;
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle(scooterService.missedStartTitle(missed)).setMessage(scooterService.missedStartText(missed,true))
                .setPositiveButton(T.mainScheduleTurnOffSchedule(),(d,which)->{
                    if(scooterService==null)return;
                    // Failure keeps the alert, so the next visit offers it again.
                    scooterService.turnOffMissedStart(result->{if(destroyed)return;toast(result!=null?result:T.mainScheduleScheduleOff());redraw();});redraw();
                })
                .setNegativeButton(T.commonOk(),(d,which)->{if(scooterService!=null)scooterService.acknowledgeMissedStart();})
                .setOnCancelListener(d->{if(scooterService!=null)scooterService.acknowledgeMissedStart();})
                .create();
        missedStartDialog=dialog;dialog.show();styleDialog(dialog,AlertDialog.BUTTON_POSITIVE);
        scooterService.missedStartShown();
    }
    /** Scheduled charging test: confirm first, since it pauses charging for a few minutes. */
    private void scheduleTestAction(){
        if(scooterService==null){toast(T.mainScheduleConnectTheScooter());return;}
        if(scooterService.scheduleTesting()){showScheduleTest();return;}
        String blocked=scooterService.chargeSchedule().testBlockedReason;if(blocked!=null){toast(blocked);return;}
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle(T.mainScheduleTestTitle())
                .setMessage(T.mainScheduleTestIntro())
                .setPositiveButton(T.mainScheduleStartTest(),(d,which)->{if(scooterService==null)return;toast(scooterService.startScheduleTest());redraw();})
                .setNegativeButton(T.commonCancel(),null)
                .create();
        controlDialog=dialog;dialog.show();styleDialog(dialog,AlertDialog.BUTTON_POSITIVE);
    }
    private void showScheduleTest(){
        ChargeScheduleController.View charge=scooterService.chargeSchedule();
        if(charge.testPhase==null||charge.testPhase==ChargeScheduleTest.Phase.ENDING){toast(scooterService.scheduleTestStatus());return;}
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle(T.mainScheduleTestingTitle())
                .setMessage(T.mainScheduleTestBody(DisplayTime.clock(this,preferences,charge.testStartWallMs)))
                .setPositiveButton(T.mainScheduleKeepTesting(),null)
                .setNegativeButton(T.mainScheduleCancelTest(),(d,which)->{if(scooterService!=null)scooterService.cancelScheduleTest();redraw();})
                .create();
        controlDialog=dialog;dialog.show();styleDialog(dialog,AlertDialog.BUTTON_POSITIVE);
    }
    /** A finished test's result, once; out of sight the service posted it as a notification, which this replaces. */
    private void maybeShowScheduleTestResult(){
        if(scooterService==null||!resumed||onboarding!=null||scheduleTestDialog!=null&&scheduleTestDialog.isShowing()||missedStartDialog!=null&&missedStartDialog.isShowing())return;
        ChargeScheduleController.TestResult result=scooterService.pendingScheduleTest();if(result==null)return;
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle(scooterService.scheduleTestTitle(result)).setMessage(scooterService.scheduleTestText(result))
                .setPositiveButton(T.commonOk(),null).create();
        scheduleTestDialog=dialog;dialog.show();styleDialog(dialog,AlertDialog.BUTTON_POSITIVE);
        scooterService.scheduleTestShown();
    }
    private boolean scheduleTestOffered(){return scooterService!=null&&scooterService.scheduleTestOffered();}
    private static String lastTestLabel(ChargeScheduleTest.Outcome outcome){
        switch(outcome){
            case PASSED:return T.mainScheduleResultPassed();case RESUMED_EARLY:return T.mainScheduleResultAhead();case RESUMED_LATE:return T.mainScheduleResultBehind();case NO_PAUSE:return T.mainScheduleResultNoPause();
            case NO_RESUME:return T.mainScheduleResultNoResume();case LIMIT_REACHED:return T.mainScheduleResultLimit();case CANCELLED:return T.mainScheduleResultCancelled();case INTERRUPTED:return T.mainScheduleResultInterrupted();
            case NOT_STARTED:return T.mainScheduleResultNotStarted();default:return "";
        }
    }
    /** UT5 MAX: shows a running test's progress, else the last result; a blocked row says why, as other rows do. */
    private final class ScheduleTestRow {
        final SettingLine row=new SettingLine("clock",M.mainScheduleTestRow(),null);final View spinner=new Spinner();
        ScheduleTestRow(){
            row.card.setTag("schedule-test");row.line.setTag("schedule-test-row");
            spinner.setVisibility(View.GONE);row.line.addView(spinner,rowSpinnerSize());row.chevron(MainActivity.this::scheduleTestAction);
        }
        void refresh(){
            ChargeScheduleController.View charge=scooterService==null?null:scooterService.chargeSchedule();
            String status=scooterService==null?null:scooterService.scheduleTestStatus();
            String blocked=status!=null?null:charge==null?T.mainScheduleConnectTheScooter():charge.testBlockedReason;
            String value=status!=null?status:charge==null?"":lastTestLabel(charge.lastTest);
            blocked=ChargeScheduleText.shown(blocked);
            boolean ready=scooterService!=null&&scooterService.ble.ready();
            row.name.setTextColor(blocked==null?design.text:design.muted);row.value.setText(value);row.line.setAlpha(blocked==null?1f:.5f);
            row.setSub(ready?blocked:null);spinner.setVisibility(status!=null?View.VISIBLE:View.GONE);
            row.line.setContentDescription(value.isEmpty()&&blocked==null?T.mainScheduleTestRow():value.isEmpty()?T.mainLabelValue(T.mainScheduleTestRow(),blocked):blocked==null?T.mainLabelValue(T.mainScheduleTestRow(),value):T.mainScheduleTestRowBoth(value,blocked));
        }
    }
    private void toggleManualCharge(){toggleManualCharge(true);}
    private void toggleManualCharge(boolean feedback){
        if(scooterService==null){toast(T.mainScheduleConnectYourScooter());return;}
        ChargeScheduleController.View charge=scooterService.chargeSchedule();
        if(charge.manualBlockedReason!=null){toast(ChargeScheduleText.shown(charge.manualBlockedReason));return;}
        boolean enable=!charge.manualEnabled;
        scooterService.setManualChargeEnabled(enable,this::scheduleResult);
        if(feedback)Haptics.toggle(getWindow().getDecorView(),enable);redraw();
    }
    private void showChargeDoneTime(){
        if(scooterService==null){toast(T.mainScheduleSelectScooter());return;}
        ChargeScheduleController.View charge=scooterService.chargeSchedule();
        if(charge.timeBlockedReason!=null){toast(ChargeScheduleText.shown(charge.timeBlockedReason));return;}
        final String profile=scooterService.profiles.active()==null?"":scooterService.profiles.active().id;
        TimePickerDialog picker=new TimePickerDialog(dialogContext(),(view,hour,minute)->{
            if(scooterService==null||scooterService.profiles.active()==null
                    ||!profile.equals(scooterService.profiles.active().id)){toast(T.mainScheduleScooterChanged());return;}
            boolean deferred=scooterService.chargeSchedule().doneByEnabled&&!scooterService.ble.ready();
            scooterService.setChargeDoneByTime(hour,minute,result->{
                if(result==null&&deferred)toast(T.mainScheduleSavedNext());
                scheduleResult(result);
            });redraw();
        },charge.doneByMinuteOfDay/60,charge.doneByMinuteOfDay%60,DisplayTime.is24Hour(this,preferences));
        picker.setTitle(T.mainScheduleDoneBy());controlDialog=picker;picker.show();
        styleDialogButton(picker.getButton(AlertDialog.BUTTON_POSITIVE),design.accent);
        styleDialogButton(picker.getButton(AlertDialog.BUTTON_NEGATIVE),design.muted);
    }
    /** Local charge planning uses the same row and picker language as scooter-owned settings. */
    private final class ChargeDoneRow {
        final SettingLine row;final Switch toggle;final boolean time;final View spinner;
        ChargeDoneRow(boolean time){
            this.time=time;row=new SettingLine("clock",M.mainScheduleDoneBy(),null);row.card.setTag("charge-done-by");
            row.line.setTag(time?"charge-done-time":"charge-done-toggle");
            spinner=new Spinner();spinner.setVisibility(View.GONE);row.line.addView(spinner,rowSpinnerSize());
            if(time){toggle=null;row.chevron(MainActivity.this::showChargeDoneTime);}
            else{
                row.value.setVisibility(View.GONE);
                toggle=new Haptics.FeedbackSwitch(MainActivity.this);toggle.setShowText(false);toggle.setMinHeight(design.dp(48));
                toggle.setMinimumWidth(design.dp(54));design.styleSwitch(toggle);toggle.setContentDescription(T.mainScheduleDoneBy());
                toggle.setOnClickListener(v->{toggle.setChecked(scooterService!=null&&scooterService.chargeSchedule().doneByEnabled);toggleChargeDoneBy(false);});
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.leftMargin=design.dp(10);row.line.addView(toggle,p);
                row.line.setClickable(true);row.line.setFocusable(true);row.line.setBackground(rowRipple());
                row.line.setOnClickListener(v->toggleChargeDoneBy());
            }
        }
        void refresh(){
            ChargeScheduleController.View charge=scooterService==null?null:scooterService.chargeSchedule();
            String blocked=charge==null?(time?T.mainScheduleSelectScooter():T.commonConnectChargerFirst()):time?charge.timeBlockedReason:doneByActionBlocked(charge);
            boolean measuring=!time&&scooterService!=null&&scooterService.isChargeMeasuring();if(measuring)blocked=null;
            blocked=ChargeScheduleText.shown(blocked);
            boolean on=charge!=null&&charge.doneByEnabled;int minute=charge==null?8*60:charge.doneByMinuteOfDay;
            Calendar at=Calendar.getInstance();at.set(Calendar.HOUR_OF_DAY,minute/60);at.set(Calendar.MINUTE,minute%60);
            String value=time?DisplayTime.clock(MainActivity.this,preferences,at.getTimeInMillis())
                    :measuring?T.mainScheduleMeasuringValue(Fmt.integer(scooterService.chargeMeasureMinutesLeft())):on?T.commonOn():T.commonOff();
            if(!time)row.value.setVisibility(measuring?View.VISIBLE:View.GONE);
            row.name.setTextColor(blocked==null?design.text:design.muted);row.value.setText(value);
            row.line.setAlpha(blocked==null?1f:.5f);row.line.setEnabled(true);
            row.line.setContentDescription(blocked==null?T.mainLabelValue(time?T.mainScheduleDoneByTime():T.mainScheduleDoneBy(),value):T.mainLabelValueDetail(time?T.mainScheduleDoneByTime():T.mainScheduleDoneBy(),value,blocked));
            boolean pending=charge!=null&&charge.busy||measuring;spinner.setVisibility(pending?View.VISIBLE:View.GONE);
            if(toggle!=null){toggle.setChecked(on);toggle.setEnabled(blocked==null&&!pending);toggle.setClickable(blocked==null&&!pending);toggle.setFocusable(blocked==null&&!pending);toggle.setVisibility(pending?View.GONE:View.VISIBLE);}
        }
    }
    private final class ControlRow {
        final ScooterCommands.Feature feature;final LinearLayout card;final SettingLine row;final TextView value,name,result;final Switch toggle;final View action,spinner;
        final String haystack;final boolean inline;SegmentedSelector patternSelector;
        final LinkedHashMap<Integer,View> options=new LinkedHashMap<>();
        /** Colour swatches; another model with more colours adds its swatches on their own full-width line. */
        private LinearLayout swatchRow;private boolean wideSwatches;
        /** Moving while this setting needs the scooter parked: the row greys out and a tap says why. */
        private boolean parkedBlocked;
        ControlRow(ScooterCommands.Feature feature){
            this.feature=feature;inline=inlineChoice(feature);
            String detail=rowDetail(feature);
            row=new SettingLine(inline?null:rowIcon(feature),shortLabel(feature),detail);card=row.card;name=row.name;value=row.value;action=row.line;
            haystack=normalize(feature.searchText()+" "+shortLabel(feature)+" "+feature.key+" "+(detail==null?"":detail));
            if(feature.key.equals("range_algorithm"))action.setTag("scooter-range-estimate");
            // Result state lives on the row line: a red chip for a failed change, a spinner while one is pending.
            result=failureChip();LinearLayout.LayoutParams chipSize=new LinearLayout.LayoutParams(-2,-2);chipSize.setMarginStart(design.dp(8));row.line.addView(result,1,chipSize);
            spinner=new Spinner();spinner.setVisibility(View.GONE);row.line.addView(spinner,2,rowSpinnerSize());
            if(feature.type==ScooterCommands.Type.TOGGLE){
                value.setVisibility(View.GONE);
                toggle=new Haptics.FeedbackSwitch(MainActivity.this);toggle.setShowText(false);toggle.setTextOn("");toggle.setTextOff("");toggle.setMinHeight(design.dp(48));toggle.setMinimumWidth(design.dp(54));design.styleSwitch(toggle);
                toggle.setContentDescription(shortLabel(feature));
                toggle.setOnClickListener(v->{if(scooterService!=null){if(feature.key.equals("timed_charging")){toggle.setChecked(scooterService.chargeSchedule().manualEnabled);toggleManualCharge(false);return;}int target=toggle.isChecked()?1:0;Integer old=scooterService.ble.featureValue(feature.key);toggle.setChecked(old!=null&&old==1);confirmChange(feature,target,()->{scooterService.ble.set(feature.key,target);redraw();});}});
                LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,-2);tp.leftMargin=design.dp(10);row.line.addView(toggle,tp);
                row.line.setFocusable(true);row.line.setBackground(rowRipple());row.line.setOnClickListener(v->{if(feature.key.equals("timed_charging"))toggleManualCharge();else if(parkedBlocked)toast(T.mainRowStopToChange());else if(toggle.isEnabled())toggle.performClick();});
            }else if(inline){
                toggle=null;value.setVisibility(View.GONE);
                if(feature.key.endsWith("_pattern")){
                    // Firmware readback owns the selected value, including while a write is pending.
                    row.line.setMinimumHeight(design.dp(40));row.line.setPadding(0,design.dp(10),0,0);
                    patternSelector=new SegmentedSelector(design,feature.label().toString(),optionTexts(feature),index->{
                        restorePatternSelection(false);
                        chooseInline(feature,feature.optionValues[index]);
                    });
                    patternSelector.setWrapChoices(true);
                    patternSelector.setTag("scooter-selector-"+feature.key);
                    LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,-2);pp.topMargin=design.dp(6);pp.bottomMargin=design.dp(8);card.addView(patternSelector,pp);
                }else{
                    // Colours are swatches on the right of the row.
                    LinearLayout swatches=row();swatches.setGravity(Gravity.CENTER_VERTICAL);swatchRow=swatches;
                    for(int i=0;i<feature.optionValues.length;i++){
                        final int choice=feature.optionValues[i];String label=feature.options[i];
                        Swatch swatch=new Swatch(label.equals("Red")?design.accent:label.equals("Ice blue")?design.blue:design.muted);
                        swatch.setContentDescription(T.mainSwatchDescription(shortLabel(feature),feature.optionLabel(i)));swatch.setOnClickListener(v->chooseInline(feature,choice));
                        options.put(choice,swatch);swatches.addView(swatch,new LinearLayout.LayoutParams(design.dp(48),design.dp(48)));
                    }
                    row.line.addView(swatches);
                }
            }else{toggle=null;row.chevron(()->{if(feature.key.equals("charge_start"))showChargingTime();else if(parkedBlocked)toast(T.mainRowStopToChange());else editFeature(feature);});}
        }
        /**
         * Search also matches what the row shows in the current language. In English the haystack is what it always was;
         * in another language it holds searchText()'s English and the shown short label and detail, not their English.
         */
        boolean matchesShown(String query){return StatsLabels.matchesShown(query,feature.label().toString(),feature.description().toString(),shortLabel(feature));}
        void refresh(){
            if(feature.key.equals("timed_charging")||feature.key.equals("charge_start")){refreshChargeSchedule();return;}
            if(feature.key.equals("range_algorithm")){
                boolean editable=scooterService!=null&&scooterService.profiles.active()!=null;
                String source=editable&&scooterService.usesLearnedRange()?T.mainRowRangeLearned():T.mainRowRangeScooter();
                parkedBlocked=false;name.setTextColor(editable?design.text:design.muted);action.setEnabled(editable);
                row.line.setAlpha(1f);value.setText(source);value.setTextColor(design.muted);row.setSub(null);
                spinner.setVisibility(View.GONE);result.setVisibility(View.GONE);
                action.setContentDescription(T.mainRowRangeDescription(source));return;
            }
            BleController ble=scooterService==null?null:scooterService.ble;Integer liveActual=ble==null?null:ble.featureValue(feature.key);Integer actual=liveActual!=null?liveActual:scooterService==null?null:scooterService.displayFeatures().get(feature.key);
            // The same decision admits the write; riding keeps a parked-only row tappable to say "Stop to change".
            CapabilityResolver.Decision decision=ble==null?null:ble.decision(feature.key);boolean parked=decision!=null&&decision.blockedByRide();
            boolean decided=decision!=null&&(decision.available()||parked);
            boolean available=decided&&liveActual!=null&&scooterService.featureFresh(feature.key);
            String shown;
            if(feature.type==ScooterCommands.Type.TIME){Double seconds=ble==null?null:ble.telemetry.number("scheduled_charge_time_raw");if(seconds==null&&scooterService!=null){TelemetryHistory.Reading cached=scooterService.displayReadings().get("scheduled_charge_time_raw");if(cached!=null)seconds=cached.number;}available=decided&&seconds!=null&&scooterService.readingFresh("scheduled_charge_time_raw");shown=seconds==null?T.commonNotReported():seconds<=0?T.mainRowNotScheduled():DisplayTime.clock(MainActivity.this,preferences,(long)(seconds*1000)-TimeZone.getDefault().getOffset(System.currentTimeMillis()));}
            else shown=actual==null?T.commonNotReported():valueText(feature,actual);
            boolean choices=feature.type!=ScooterCommands.Type.CHOICE||(ble!=null&&ble.featureChoices(feature.key).length>0);
            // Parked-only settings grey out while the scooter moves; the row stays tappable to say "Stop to change".
            parkedBlocked=available&&choices&&parked;
            CharSequence noteMessage=rowNote(ble,decision,parked);String note=noteMessage==null?null:noteMessage.toString();
            boolean usable=available&&choices&&!parkedBlocked;
            name.setTextColor(usable?design.text:design.muted);action.setEnabled(available&&choices);
            row.line.setAlpha(parkedBlocked?.45f:1f);
            if(toggle==null&&!inline){
                // A long value moves under the label so the row never wraps beside the chevron.
                // Another model's caption joins a long time value under the label too, so neither is squeezed (never on the UT5 MAX).
                boolean captioned=note!=null&&decision!=null&&decision.layer!=ModelRegistry.Layer.UT5_MAX;
                boolean wide=(feature.type!=ScooterCommands.Type.TIME||captioned)&&textOver(shown,10,5.12,value);value.setText(wide?NO_TEXT:shown);row.setSub(joinNote(wide?shown:rowDetail(feature),note));
            }else if(inline)row.setSub(actual==null?T.commonNotReported():note);
            else row.setSub(joinNote(rowDetail(feature),note));
            if(patternSelector!=null){restorePatternSelection(true);patternSelector.setEnabled(usable);patternSelector.setAlpha(usable?1f:.5f);}
            if(swatchRow!=null)extraSwatches(decision);
            for(Map.Entry<Integer,View> option:options.entrySet()){
                boolean on=actual!=null&&actual.intValue()==option.getKey();View view=option.getValue();
                ((Swatch)view).set(on);
                view.setEnabled(usable);view.setAlpha(usable?1f:.5f);
                if(Build.VERSION.SDK_INT>=30)view.setStateDescription(on?T.commonSelected():"");
            }
            // Short-lived result state: a spinner in the switch slot while pending, then a red chip if it failed.
            boolean pending=ble!=null&&feature.key.equals(ble.pending());
            String chip=pending?null:resultChip(ble,feature.key);
            spinner.setVisibility(pending?View.VISIBLE:View.GONE);
            result.setText(chip==null?"":chip);result.setVisibility(chip==null?View.GONE:View.VISIBLE);
            // TalkBack reads the name and value, plus any short state.
            action.setContentDescription(T.mainLabelValueState(shortLabel(feature),shown,pending?T.mainRowConfirmingPart():chip!=null?T.mainRowStateNote(lowerInEnglish(chip)):parkedBlocked?T.mainRowStopPart():note!=null?T.mainRowStateNote(lowerInEnglish(note)):""));
            if(toggle!=null){toggle.setVisibility(pending?View.GONE:View.VISIBLE);toggle.setEnabled(usable);toggle.setChecked(actual!=null&&actual==1);if(Build.VERSION.SDK_INT>=30)toggle.setStateDescription(actual==null?T.commonNotReported():actual==1?T.commonOn():T.commonOff());}
        }
        private void refreshChargeSchedule(){
            ChargeScheduleController.View charge=scooterService==null?null:scooterService.chargeSchedule();
            BleController ble=scooterService==null?null:scooterService.ble;
            CapabilityResolver.Decision decision=ble==null?null:ble.decision(feature.key);
            String note=rowNote(ble,decision,decision!=null&&decision.blockedByRide());
            String blocked=charge==null?T.mainScheduleConnectYourScooter():toggle==null?charge.manualTimeBlockedReason:charge.manualBlockedReason;
            boolean pending=charge!=null&&charge.busy,on=charge!=null&&charge.manualEnabled;
            String shown=toggle!=null?(on?T.commonOn():T.commonOff()):charge==null||charge.manualStartWallMs<=0?T.mainRowNotScheduled()
                    :DisplayTime.clock(MainActivity.this,preferences,charge.manualStartWallMs);
            blocked=ChargeScheduleText.shown(blocked);
            // As in refresh(): another model's caption takes a long time value under the label with it (never on the UT5 MAX).
            boolean wide=toggle==null&&note!=null&&decision!=null&&decision.layer!=ModelRegistry.Layer.UT5_MAX&&textOver(shown,10,5.12,value);
            name.setTextColor(blocked==null?design.text:design.muted);value.setText(wide?"":shown);row.setSub(wide?joinNote(shown,note):note);
            action.setEnabled(true);row.line.setAlpha(blocked==null?1f:.5f);parkedBlocked=false;
            spinner.setVisibility(pending?View.VISIBLE:View.GONE);result.setVisibility(View.GONE);
            action.setContentDescription(note==null||note.equals(blocked)?(blocked==null?T.mainLabelValue(shortLabel(feature),shown):T.mainLabelValueDetail(shortLabel(feature),shown,blocked))
                    :blocked==null?T.mainLabelValueDetail(shortLabel(feature),shown,note):T.mainLabelValueDetails(shortLabel(feature),shown,blocked,note));
            if(toggle!=null){toggle.setVisibility(pending?View.GONE:View.VISIBLE);toggle.setChecked(on);toggle.setEnabled(blocked==null&&!pending);toggle.setClickable(blocked==null&&!pending);toggle.setFocusable(blocked==null&&!pending);}
        }
        /**
         * Short caption for a connected row: why the control is refused (not the transient states the row
         * already shows), else "Unconfirmed here" where an owner saw this model keep its old value, else
         * "Untested here" for an OEM-mapped control on another model. None on the UT5 MAX.
         */
        private String rowNote(BleController ble,CapabilityResolver.Decision d,boolean parked){
            if(ble==null||d==null||!ble.ready())return null;
            if(!d.available()&&!parked&&d.reason!=CapabilityResolver.Reason.BUSY&&d.reason!=CapabilityResolver.Reason.FIELD_ABSENT
                    &&d.reason!=CapabilityResolver.Reason.NOT_CONNECTED&&d.reason!=CapabilityResolver.Reason.STATUS_STALE)return d.reasonText();
            if(ModelRegistry.unconfirmed(ble.descriptor(),feature.key))return T.mainRowUnconfirmed();
            return d.layer!=ModelRegistry.Layer.UT5_MAX&&d.evidence==CapabilityResolver.Evidence.OEM_MAPPED?T.mainRowUntested():null;
        }
        /** UT5 MAX swatches never change; colours beyond them (another model's table) show only while that model offers them. */
        private void extraSwatches(CapabilityResolver.Decision decision){
            int[] offered=decision==null||decision.variant==null||decision.variant.decode==FeatureVariant.Decode.UT5_FEATURE?new int[0]:decision.values();
            java.util.HashSet<Integer> base=new java.util.HashSet<>();for(int v:feature.optionValues)base.add(v);
            for(int v:offered)if(!options.containsKey(v)){
                if(!wideSwatches){
                    // Six colours do not fit beside the label: the swatches take their own line, sharing its width.
                    wideSwatches=true;row.line.removeView(swatchRow);
                    for(int i=0;i<swatchRow.getChildCount();i++)swatchRow.getChildAt(i).setLayoutParams(new LinearLayout.LayoutParams(0,design.dp(48),1));
                    LinearLayout.LayoutParams wide=new LinearLayout.LayoutParams(-1,-2);wide.bottomMargin=design.dp(6);card.addView(swatchRow,wide);
                }
                final int choice=v;String label=decision.label(v);
                Swatch swatch=new Swatch(swatchColor(label));swatch.rainbow=label.equals("Rainbow");
                swatch.setContentDescription(T.mainSwatchDescription(shortLabel(feature),decision.labelText(v)));swatch.setOnClickListener(x->chooseInline(feature,choice));
                options.put(choice,swatch);swatchRow.addView(swatch,new LinearLayout.LayoutParams(0,design.dp(48),1));
            }
            for(Map.Entry<Integer,View> option:options.entrySet())if(!base.contains(option.getKey())){
                boolean shown=false;for(int v:offered)shown|=v==option.getKey();option.getValue().setVisibility(shown?View.VISIBLE:View.GONE);
            }
        }
        private String joinNote(String text,String note){return note==null?text:text==null||text.isEmpty()?note:T.mainJoinNote(text,note);}
        private void restorePatternSelection(boolean animate){
            Integer actual=scooterService==null?null:scooterService.ble.featureValue(feature.key);
            if(actual==null&&scooterService!=null)actual=scooterService.displayFeatures().get(feature.key);
            int selected=-1;for(int i=0;i<feature.optionValues.length;i++)if(actual!=null&&actual==feature.optionValues[i])selected=i;
            patternSelector.select(selected,animate);
        }
    }
    private void chooseInline(ScooterCommands.Feature feature,int target){
        if(scooterService==null)return;Integer actual=scooterService.ble.featureValue(feature.key);if(actual!=null&&actual==target)return;
        confirmChange(feature,target,()->{if(!scooterService.ble.set(feature.key,target))toast(scooterService.ble.displayMessage());redraw();});
    }
    /** A row's option names in the current language; the ids in feature.options stay what code compares. */
    private static String[] optionTexts(ScooterCommands.Feature feature){
        String[] out=new String[feature.options.length];for(int i=0;i<out.length;i++)out[i]=feature.optionLabel(i).render();return out;
    }
    /** TextFit.over for this text on this view: the layout's width choice, a character count in English. */
    private static boolean textOver(String text,int chars,double ems,TextView view){return TextFit.over(text.length(),chars,ems,view.getPaint().measureText(text),view.getTextSize());}
    /** Swatch colour for a lamp colour label (UT5 MAX: ice blue and red). */
    private int swatchColor(String label){
        switch(label){
            case "Red": return design.accent;
            case "Ice blue": return design.blue;
            case "Blue": return android.graphics.Color.parseColor("#3D6BE0");
            case "Green": return design.ok;
            case "Purple": return android.graphics.Color.parseColor("#8E5BD0");
            default: return design.muted;
        }
    }
    /** Lamp colour swatch; the selected colour gains a ring. */
    private final class Swatch extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final int color;private boolean on;
        /** A multicolour lamp mode: drawn as a colour wheel. */
        boolean rainbow;
        Swatch(int color){super(MainActivity.this);this.color=color;setClickable(true);setFocusable(true);
            setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(design.accent,40)),null,null));}
        void set(boolean selected){if(on!=selected){on=selected;invalidate();}setSelected(selected);}
        @Override public CharSequence getAccessibilityClassName(){return android.widget.RadioButton.class.getName();}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setCheckable(true);info.setChecked(on);}
        @Override protected void onDraw(Canvas canvas){
            float cx=getWidth()/2f,cy=getHeight()/2f;paint.setStyle(Paint.Style.FILL);paint.setColor(color);
            if(rainbow)paint.setShader(new android.graphics.SweepGradient(cx,cy,new int[]{0xFFE5484D,0xFFF5B83D,0xFF3DBE6B,0xFF3D6BE0,0xFF8E5BD0,0xFFE5484D},null));
            canvas.drawCircle(cx,cy,design.dp(10),paint);paint.setShader(null);
            if(on){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(design.dp(2));paint.setColor(design.text);canvas.drawCircle(cx,cy,design.dp(14),paint);}
        }
    }
    /** Four signal bars, filled up to a rank. Used by the top speed options and the scooter picker. */
    private final class SignalBars extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private int rank,color,off;
        SignalBars(int rank){super(MainActivity.this);this.rank=rank;setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void set(int rank,int color,int off){this.rank=rank;this.color=color;this.off=off;invalidate();}
        @Override protected void onDraw(Canvas canvas){
            float bar=design.dp(3),gap=design.dp(2),h=getHeight(),x=(getWidth()-(4*bar+3*gap))/2;float[] heights={.34f,.58f,.82f,1f};
            for(int i=0;i<4;i++){paint.setColor(i<rank?color:off);canvas.drawRoundRect(new RectF(x,h-h*heights[i],x+bar,h),design.dp(1),design.dp(1),paint);x+=bar+gap;}
        }
    }
    /** Small rotating arc for waiting states. */
    private final class Spinner extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Spinner(){super(MainActivity.this);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas canvas){
            float stroke=design.dp(2);RectF arc=new RectF(stroke,stroke,getWidth()-stroke,getHeight()-stroke);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(design.raised);canvas.drawOval(arc,paint);
            paint.setColor(design.accent);canvas.drawArc(arc,Ui.motionEnabled()?(SystemClock.uptimeMillis()%1000)*.36f:0,100,false,paint);
            if(resumed&&isShown()&&Ui.motionEnabled())postInvalidateDelayed(32);
        }
    }
    /** Volt top speed card (ph/24): the current maximum in the rider's units and four raised choices. Commands stay in km/h. */
    private final class TopSpeedCard {
        final ScooterCommands.Feature feature=ScooterCommands.find("maximum_speed");
        final LinearLayout card=column(),options=row();
        final TextView title,amount,unit,status;
        final ArrayList<LinearLayout> buttons=new ArrayList<>();final ArrayList<TextView> numbers=new ArrayList<>();final ArrayList<SignalBars> bars=new ArrayList<>();
        private String styled="";
        /** The speed the rider tapped; its button pulses until the scooter confirms or the change ends. */
        private Integer requested;private View pulsing;private android.animation.ValueAnimator pulse;
        TopSpeedCard(){
            card.setPadding(design.dp(16),design.dp(14),design.dp(16),design.dp(16));
            LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,-2);cardParams.bottomMargin=design.dp(2);card.setLayoutParams(cardParams);
            // Naming Sport here states the "Sport mode only" fact without a separate line.
            title=design.text(T.mainSpeedTitle(),12.5f,false);title.setTypeface(design.medium());card.addView(title);
            LinearLayout reading=row();reading.setPadding(0,design.dp(8),0,design.dp(14));
            amount=design.text("—",42,true);amount.setIncludeFontPadding(false);reading.addView(amount);
            unit=design.text("",14,false);unit.setPadding(design.dp(8),0,0,0);reading.addView(unit);
            card.addView(reading);unit.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            for(int i=0;i<feature.optionValues.length;i++){
                final int kmh=feature.optionValues[i];
                LinearLayout button=column();button.setGravity(Gravity.CENTER);button.setMinimumHeight(design.dp(60));button.setPadding(0,design.dp(8),0,design.dp(10));
                SignalBars signal=new SignalBars(i+1);button.addView(signal,new LinearLayout.LayoutParams(design.dp(18),design.dp(12)));
                TextView number=design.text("",16,true);number.setGravity(Gravity.CENTER);number.setPadding(0,design.dp(6),0,0);button.addView(number);
                button.setClickable(true);button.setFocusable(true);button.setOnClickListener(v->choose(v,kmh));
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);if(i>0)p.leftMargin=design.dp(7);options.addView(button,p);
                buttons.add(button);numbers.add(number);bars.add(signal);
            }
            card.addView(options,new LinearLayout.LayoutParams(-1,-2));
            status=design.text("",12,false);status.setPadding(0,design.dp(10),0,0);status.setVisibility(View.GONE);card.addView(status);
        }
        private String number(int kmh,String units){return UNIT_KM.equals(units)?Fmt.integer(kmh):Fmt.integer(Math.round(kmh/1.609344));}
        /** Spoken form: the rounded figure the rider sees, plus the exact OEM km/h value it sends. */
        private String spoken(int kmh,String units){return "km".equals(units)?T.mainSpeedSpokenKm(Fmt.integer(kmh)):T.mainSpeedSpokenMi(number(kmh,units),Fmt.integer(kmh));}
        private void choose(View source,int kmh){
            if(scooterService==null)return;BleController ble=scooterService.ble;
            Integer actual=ble.featureValue(feature.key);if(actual!=null&&actual==kmh)return;
            if(!ble.set(feature.key,kmh))toast(ble.displayMessage());else{requested=kmh;Haptics.tick(source);}redraw();
        }
        void refresh(){
            BleController ble=scooterService==null?null:scooterService.ble;
            Integer live=ble==null?null:ble.featureValue(feature.key);
            Integer actual=live!=null?live:scooterService==null?null:scooterService.displayFeatures().get(feature.key);
            boolean ready=ble!=null&&ble.ready();
            CapabilityResolver.Decision decision=ble==null?null:ble.decision(feature.key);
            boolean available=decision!=null&&(decision.available()||decision.blockedByRide())&&live!=null&&scooterService.featureFresh(feature.key)&&ble.featureChoices(feature.key).length>0;
            String units=preferences.getString("units","mi");
            amount.setText(actual==null?"—":number(actual,units));
            unit.setText("km".equals(units)?T.unitsKmh():"dual".equals(units)&&actual!=null?T.mainSpeedUnitDual(Fmt.integer(actual)):T.unitsMph());
            amount.setContentDescription(actual==null?(ready?T.mainSpeedNotReported():T.mainSpeedNotReportedSeen()):ready?T.mainSpeedValue(spoken(actual,units)):T.mainSpeedValueSeen(spoken(actual,units)));
            for(int i=0;i<buttons.size();i++){
                int kmh=feature.optionValues[i];boolean on=actual!=null&&actual==kmh;
                numbers.get(i).setText(number(kmh,units));
                LinearLayout button=buttons.get(i);button.setEnabled(available);button.setSelected(on);
                button.setContentDescription(T.mainSpeedSet(spoken(kmh,units)));
                if(Build.VERSION.SDK_INT>=30)button.setStateDescription(on?T.commonSelected():"");
            }
            boolean pending=ble!=null&&feature.key.equals(ble.pending());
            if(!pending)requested=null;
            int index=-1;for(int i=0;i<buttons.size();i++)if(requested!=null&&requested==feature.optionValues[i])index=i;
            pulse(pending&&index>=0?buttons.get(index):null);
            if(pending&&index>=0&&Build.VERSION.SDK_INT>=30)buttons.get(index).setStateDescription(T.mainSpeedConfirming());
            // Without motion the pulse cannot show, so a one-word status stands in for it.
            String feedback=pending?(Ui.motionEnabled()&&index>=0?null:T.mainSpeedConfirmingVisible()):resultChip(ble,feature.key);
            // An owner saw this model keep its old top speed after a change: say so until a report shows it kept.
            if(feedback==null&&ready&&ModelRegistry.unconfirmed(ble.descriptor(),feature.key))feedback=T.mainRowUnconfirmed();
            status.setText(feedback==null?"":feedback);status.setVisibility(feedback==null?View.GONE:View.VISIBLE);
            String state=ready+"|"+available+"|"+actual;
            if(!state.equals(styled)){styled=state;style(!ready,available,actual);}
        }
        /** Breathes the tapped speed button while the scooter confirms it; null stops and restores. */
        private void pulse(View target){
            if(target==pulsing)return;
            if(pulse!=null){pulse.cancel();pulse=null;}
            if(pulsing!=null)pulsing.setAlpha(1f);
            pulsing=target;
            if(target==null||!Ui.motionEnabled())return;
            pulse=android.animation.ValueAnimator.ofFloat(1f,.45f);pulse.setDuration(520);
            pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            pulse.setInterpolator(new android.view.animation.PathInterpolator(.4f,0,.2f,1));
            pulse.addUpdateListener(a->{if(!target.isAttachedToWindow()){a.cancel();return;}target.setAlpha((Float)a.getAnimatedValue());});
            pulse.start();
        }
        /** Red gradient while live; a grey card when only last-seen values are known. */
        private void style(boolean grey,boolean available,Integer actual){
            int fg=grey?(design.dark?Ui.withAlpha(design.text,200):design.text):design.onAccent;
            if(grey)card.setBackground(design.shape(design.outline,22,0));
            else{android.graphics.drawable.GradientDrawable gradient=new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,new int[]{design.accent,design.accentDeep});gradient.setCornerRadius(design.dp(22));card.setBackground(gradient);}
            title.setTextColor(Ui.withAlpha(fg,220));amount.setTextColor(fg);unit.setTextColor(Ui.withAlpha(fg,220));status.setTextColor(Ui.withAlpha(fg,235));
            int selectedFill=design.dark?design.bg:design.text,selectedText=design.dark?design.text:design.bg;
            for(int i=0;i<buttons.size();i++){
                boolean on=actual!=null&&actual==feature.optionValues[i];
                int fill=on?selectedFill:Ui.withAlpha(android.graphics.Color.BLACK,grey?30:40);
                int ring=on?Ui.withAlpha(android.graphics.Color.WHITE,grey?70:150):Ui.withAlpha(fg,60);
                buttons.get(i).setBackground(raisedOption(fill,ring,on?2:1));
                int ink=on?selectedText:fg;numbers.get(i).setTextColor(ink);
                int barColor=on&&!grey?design.accent:ink;bars.get(i).set(i+1,barColor,Ui.withAlpha(barColor,90));
                if(buttons.get(i)!=pulsing)buttons.get(i).setAlpha(available||grey?1f:.6f);
            }
        }
        private android.graphics.drawable.Drawable raisedOption(int fill,int ring,int ringDp){
            android.graphics.drawable.GradientDrawable shadow=design.shape(Ui.withAlpha(android.graphics.Color.BLACK,40),12,0);
            android.graphics.drawable.GradientDrawable face=new android.graphics.drawable.GradientDrawable();face.setColor(fill);face.setCornerRadius(design.dp(12));face.setStroke(design.dp(ringDp),ring);
            android.graphics.drawable.LayerDrawable layers=new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{shadow,face});
            layers.setLayerInset(0,0,design.dp(2),0,0);layers.setLayerInset(1,0,0,0,design.dp(2));
            return new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(android.graphics.Color.WHITE,50)),layers,null);
        }
    }
    // ---- Volt dialogs ------------------------------------------------------------------------------
    /** Rounded Volt surface and Saira buttons for the small AlertDialogs; only the primary action (if any) is red. */
    /** One offer per Model ID per app session (it survives Activity recreation), a few seconds after a connection settles; answers persist under ReportInvite.PREFIX. */
    private static final java.util.Set<String> reportInvited=java.util.Collections.synchronizedSet(new java.util.HashSet<>());private Dialog reportDialog;private long reportReadySinceMs;
    /** The offer's read-only check is running; onDestroy stops it (a dialog's dismiss listener runs too late). */
    private boolean reportChecking;
    private void maybeInviteReport(){
        BleController ble=scooterService==null?null:scooterService.ble;
        if(ble==null||!ble.ready()){reportReadySinceMs=0;return;}
        long now=SystemClock.elapsedRealtime();if(reportReadySinceMs==0)reportReadySinceMs=now;
        // The same guard as the check itself: never offer what Check & share would refuse (rides, charge capture or measurement, tests, pending changes).
        if(now-reportReadySinceMs<2500||onboarding!=null||!resumed||reportDialog!=null&&reportDialog.isShowing()
                ||controlDialog!=null&&controlDialog.isShowing()||!ble.discoveryAllowed())return;
        String model=ReportInvite.model(ble.descriptor());
        if(model.isEmpty()||reportInvited.contains(model)||!ReportInvite.due(preferences.getString(ReportInvite.PREFIX+model,""),System.currentTimeMillis()))return;
        reportInvited.add(model);showReportInvite(model);
    }
    /** Help Volt support this scooter: one tap starts the read-only check and opens "Which scooter is this?"; the email app then opens with the report to read before sending. */
    private void showReportInvite(String model){
        LinearLayout box=column();box.setPadding(design.dp(24),design.dp(4),design.dp(24),design.dp(4));
        box.addView(design.text(T.mainReportInviteBody(model),15,false));
        addressLine(box);
        TextView privacy=design.caption(T.mainReportInvitePrivacy());privacy.setPadding(0,design.dp(6),0,0);box.addView(privacy);
        TextView status=design.caption("");status.setPadding(0,design.dp(10),0,0);status.setVisibility(View.GONE);status.setTag("report-invite-status");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);box.addView(status);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setCustomTitle(dialogTitle(T.mainReportInviteTitle())).setView(scroll)
                .setPositiveButton(T.mainReportCheckShare(),null).setNegativeButton(T.mainReportNotNow(),null).setNeutralButton(T.mainReportDontAsk(),null).create();
        reportDialog=dialog;dialog.show();styleDialog(dialog,AlertDialog.BUTTON_POSITIVE);
        reportChecking=false;
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{preferences.edit().putString(ReportInvite.PREFIX+model,ReportInvite.later(System.currentTimeMillis())).apply();dialog.dismiss();});
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{preferences.edit().putString(ReportInvite.PREFIX+model,"never").apply();dialog.dismiss();});
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String[] scooter={""};String problem=startReportCheck(scooter);
            if(problem!=null){status.setText(problem);status.setVisibility(View.VISIBLE);return;}
            dialog.dismiss();showReportPicker(model,scooter,null);
        });
        dialog.setOnDismissListener(d->{if(reportDialog==dialog)reportDialog=null;});
    }
    /** Starts the offer's read-only check on the active scooter (its id goes in scooter[0]); what stopped it, or null once it runs. */
    private String startReportCheck(String[] scooter){
        BleController ble=scooterService==null?null:scooterService.ble;
        if(ble==null||!ble.ready())return T.mainReportConnectFirst();
        ScooterProfiles.Profile checked=scooterService.profiles.active();scooter[0]=checked==null?"":checked.id;
        ble.startDiscovery();
        if(!ble.discovering())return ble.displayText();
        reportChecking=true;return null;
    }
    /** The compatibility checker's Email to Volt: the same sheet and email as the offer, for the check already finished. */
    @Override public void emailReport(CompatibilityReport report){
        if(report==null||scooterService==null||reportDialog!=null&&reportDialog.isShowing())return;
        ScooterProfiles.Profile p=scooterService.profiles.active();
        showReportPicker(scooterService.ble.descriptor().rulePid(),new String[]{p==null?"":p.id},report);
    }
    /**
     * The check runs while the owner says which scooter this is. Next waits for both; a check that stops offers Try again whatever the answer.
     * With finished (from the checker), there is no check to wait for and closing stores no answer to the offer.
     */
    private void showReportPicker(String model,String[] scooter,CompatibilityReport finished){
        CompatibilityReport[] ready={finished};Dialog[] self={null};ModelPicker[] picker={null};
        Runnable phase=()->{
            ModelPicker p=picker[0];
            if(reportChecking){p.primary(T.mainReportNext(),false);p.status(T.mainReportChecking(),true);}
            else if(ready[0]==null)p.primary(T.commonTryAgain(),true);
            else{p.primary(T.mainReportNext(),p.choice()!=null);p.status(p.choice()==null?T.mainReportCheckChoose():T.mainReportCheckFinished(),false);}
        };
        Runnable watch=new Runnable(){@Override public void run(){
            Dialog dialog=self[0];
            if(destroyed||dialog==null||!dialog.isShowing()||!reportChecking)return;
            BleController ble=scooterService==null?null:scooterService.ble;
            // A check that finishes while Volt is in the background waits: the email app opens only in front of Volt.
            if(ble!=null&&ble.discovering()||!resumed){handler.postDelayed(this,400);return;}
            reportChecking=false;
            ScooterProfiles.Profile now=scooterService==null?null:scooterService.profiles.active();
            DiscoveryCoordinator finished=ble==null||now==null||!now.id.equals(scooter[0])?null:ble.discovery();
            ready[0]=finished==null||finished.interrupted()?null:ble.compatibilityReport(AppVersion.name(MainActivity.this));
            if(ready[0]==null)picker[0].status(T.mainReportCheckStopped(),false);
            phase.run();
        }};
        Runnable next=()->{
            if(reportChecking)return;
            if(ready[0]!=null){if(picker[0].choice()!=null)finishReport(model,ready[0],picker[0].choice(),scooter[0],self[0]);return;}
            String problem=startReportCheck(scooter);
            if(problem!=null){picker[0].status(problem,false);return;}
            phase.run();handler.postDelayed(watch,400);
        };
        Runnable close=()->{if(finished==null)preferences.edit().putString(ReportInvite.PREFIX+model,ReportInvite.later(System.currentTimeMillis())).apply();if(self[0]!=null)self[0].dismiss();};
        picker[0]=new ModelPicker(this,design,c->phase.run());
        LinearLayout content=sheetContent(T.mainReportWhich(),null,close);
        picker[0].build(content,new Spinner(),T.mainReportNext(),next);
        Dialog dialog=showTallSheet(content);self[0]=dialog;reportDialog=dialog;
        dialog.setOnCancelListener(d->close.run());
        dialog.setOnDismissListener(d->{if(reportChecking&&scooterService!=null)scooterService.ble.stopDiscovery();reportChecking=false;if(reportDialog==dialog)reportDialog=null;});
        phase.run();if(finished==null)handler.postDelayed(watch,400);
    }
    /** After the check and the owner's answer: optionally name the drive modes, then open the email, still for the same scooter. */
    private void finishReport(String model,CompatibilityReport report,NaveeModels.Choice choice,String scooter,Dialog dialog){
        // A check on the developer simulator says nothing about the saved scooter it stands in for.
        dialog.dismiss();if(scooterService!=null&&!scooterService.simulating())rememberModel(scooter,choice,false);
        java.util.function.Consumer<DriveModeSurvey> send=modes->{
            if(destroyed)return;
            java.util.function.BooleanSupplier same=()->{
                ScooterProfiles.Profile current=scooterService==null?null:scooterService.profiles.active();
                if(current==null||!current.id.equals(scooter)){toast(T.mainReportChanged());return false;}
                return true;
            };
            if(!same.getAsBoolean())return;
            showEmailSheet(ReportInvite.subject(model),ReportInvite.body(report,choice,modes,model),report,same,()->{
                // Its tries now travel with a report the owner is sending: they keep their results but may be pruned.
                if(scooterService!=null)scooterService.ble.markReported(report);
                if(!model.isEmpty())preferences.edit().putString(ReportInvite.PREFIX+model,"sent").apply();toast(T.mainReportThanksReport());
            });
        };
        BleController ble=scooterService==null?null:scooterService.ble;
        // Optional: the owner names each drive mode the scooter reports (DriveModeStep); skipping still sends the report.
        if(ble!=null&&ble.ready()&&ble.telemetry.number("driving_mode")!=null)
            reportDialog=new DriveModeStep(this,design,ble,modes->{if(!destroyed)reportDialog=null;send.accept(modes);}).show();
        else send.accept(null);
    }
    /**
     * Keeps the owner's answer on that scooter, so Volt names and pictures it until Volt can identify it itself (ModelRegistry.ownerModel).
     * A listed model replaces any earlier answer; Not sure clears it only when the owner chose it in Edit scooter (cleared).
     */
    private void rememberModel(String profileId,NaveeModels.Choice choice,boolean cleared){
        ScooterProfiles.Profile p=null;if(scooterService!=null)for(ScooterProfiles.Profile item:scooterService.profiles.all())if(item.id.equals(profileId))p=item;
        if(p==null||choice==null)return;
        String model=choice.source==NaveeModels.Choice.Source.LIST?choice.name:cleared&&!choice.known()?"":null;
        if(model==null||model.equals(p.ownerModel))return;
        p.ownerModel=model;
        // A name Volt chose itself follows the answer, as it follows an identified model on connecting.
        if(p.nameDefault){String name=ModelRegistry.modelName(p.savedDescriptor());p.name=name.isEmpty()?"NAVEE scooter":name;}
        scooterService.profiles.update(p);redraw();
    }
    /** More › My scooters › Edit scooter › Which model is it?: the same sheet as the report flows, saving the owner's answer for that scooter. */
    @Override public void chooseScooterModel(String profileId){
        if(scooterService==null||reportDialog!=null&&reportDialog.isShowing())return;
        ScooterProfiles.Profile p=null;for(ScooterProfiles.Profile item:scooterService.profiles.all())if(item.id.equals(profileId))p=item;
        if(p==null)return;
        Dialog[] self={null};ModelPicker[] picker={null};
        String saved=p.ownerModel;
        Runnable save=()->{
            NaveeModels.Choice choice=picker[0].choice();
            if(choice==null||choice.source==NaveeModels.Choice.Source.TYPED)return; // listed-only below; never a save that keeps nothing
            rememberModel(profileId,choice,true);if(self[0]!=null)self[0].dismiss();
        };
        // Save keeps exactly the row shown as chosen: never an answer the search has hidden.
        picker[0]=new ModelPicker(this,design,c->picker[0].primary(T.mainReportSave(),picker[0].choice()!=null&&picker[0].choiceShown())).listedOnly();
        LinearLayout content=sheetContent(T.mainReportWhich(),null,()->{if(self[0]!=null)self[0].dismiss();});
        picker[0].build(content,null,T.mainReportSave(),save);
        if(!saved.isEmpty()&&saved.equals(NaveeModels.exact(saved)))picker[0].select(NaveeModels.Choice.listed(saved));
        picker[0].primary(T.mainReportSave(),picker[0].choice()!=null&&picker[0].choiceShown());
        picker[0].status(T.mainReportModelNote(),false);
        Dialog dialog=showTallSheet(content);self[0]=dialog;reportDialog=dialog;
        dialog.setOnDismissListener(d->{if(reportDialog==dialog)reportDialog=null;});
    }
    /** After repeated failures on a scooter that has never connected, offer once per app session to email its connection details. */
    private static final java.util.Set<String> connectOffered=java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private void maybeOfferConnectionReport(){
        BleController ble=scooterService==null?null:scooterService.ble;
        if(ble==null||ble.simulating()||!resumed||ble.state()!=BleController.State.DISCONNECTED)return;
        if(reportDialog!=null&&reportDialog.isShowing()||controlDialog!=null&&controlDialog.isShowing())return;
        ScooterProfiles.Profile p=scooterService.profiles.active();
        if(p==null||!p.configured()||connectOffered.contains(p.id))return;
        if(!ConnectionReport.due(p.lastConnected==0,ble.connectionAttempts(p.id),preferences.getString(ConnectionReport.PREFIX+p.id,""),System.currentTimeMillis()))return;
        connectOffered.add(p.id);showConnectionReportOffer(p);
    }
    /** Volt couldn't connect: Send details asks which scooter this is, then the email app opens with the redacted connection details to read before sending. */
    private void showConnectionReportOffer(ScooterProfiles.Profile p){
        LinearLayout box=column();box.setPadding(design.dp(24),design.dp(4),design.dp(24),design.dp(4));
        box.addView(design.text(T.mainReportFailureBody(),15,false));
        addressLine(box);
        TextView privacy=design.caption(T.mainReportFailurePrivacy());
        privacy.setPadding(0,design.dp(6),0,0);box.addView(privacy);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(box);
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setCustomTitle(dialogTitle(T.mainReportFailureTitle())).setView(scroll)
                .setPositiveButton(T.mainReportSendDetails(),null).setNegativeButton(T.mainReportNotNow(),null).setNeutralButton(T.mainReportDontAsk(),null).create();
        reportDialog=dialog;dialog.show();styleDialog(dialog,AlertDialog.BUTTON_POSITIVE);
        String key=ConnectionReport.PREFIX+p.id;
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{preferences.edit().putString(key,ReportInvite.later(System.currentTimeMillis())).apply();dialog.dismiss();});
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{preferences.edit().putString(key,"never").apply();dialog.dismiss();});
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{dialog.dismiss();showConnectionPicker(p,key);});
        dialog.setOnDismissListener(d->{if(reportDialog==dialog)reportDialog=null;});
    }
    private void showConnectionPicker(ScooterProfiles.Profile p,String key){
        Dialog[] self={null};ModelPicker[] picker={null};
        Runnable send=()->{
            if(picker[0].choice()==null||scooterService==null)return;
            rememberModel(p.id,picker[0].choice(),false);
            BleController ble=scooterService.ble;String model=ble.advertisedModelId(p);
            String json=ConnectionReport.json(AppVersion.name(this),Build.VERSION.SDK_INT,Build.MANUFACTURER+" "+Build.MODEL,model,ble.advertisedTransport(p),ble.mtu(),
                    ble.connectionAttempts(p.id),ble.connectionActivity(p.id));
            if(email(ConnectionReport.subject(model),ConnectionReport.body(json,picker[0].choice(),model))){preferences.edit().putString(key,"sent").apply();self[0].dismiss();toast(T.mainReportThanksDetails());}
            else toast(T.mainReportNoEmailDetails());
        };
        Runnable close=()->{preferences.edit().putString(key,ReportInvite.later(System.currentTimeMillis())).apply();if(self[0]!=null)self[0].dismiss();};
        picker[0]=new ModelPicker(this,design,c->{picker[0].primary(T.mainReportSend(),true);picker[0].status(T.mainReportEmailNext(),false);});
        LinearLayout content=sheetContent(T.mainReportWhich(),null,close);
        picker[0].build(content,null,T.mainReportSend(),send);
        picker[0].primary(T.mainReportSend(),false);picker[0].status(T.mainReportChooseSend(),false);
        Dialog dialog=showTallSheet(content);self[0]=dialog;reportDialog=dialog;
        dialog.setOnCancelListener(d->close.run());
        dialog.setOnDismissListener(d->{if(reportDialog==dialog)reportDialog=null;});
    }
    /** The report address on its own line, so it never wraps mid-address. */
    private void addressLine(LinearLayout box){
        TextView to=design.caption(T.mainReportTo(ReportInvite.ADDRESS));to.setPadding(0,design.dp(10),0,0);to.setTextIsSelectable(true);box.addView(to);
    }
    /** More › Report an issue: the email sheet, with the finished compatibility check's report in the file when there is one. */
    @Override public boolean emailVolt(String subject,String body){
        if(reportDialog!=null&&reportDialog.isShowing())return true;
        CompatibilityReport report=scooterService==null?null:scooterService.ble.compatibilityReport(AppVersion.name(this));
        showEmailSheet(subject,body,report,null,null);return true;
    }
    /**
     * Battery & charging › Charge history & recordings › Email to Volt: the exported recordings ride the report emails'
     * attachment (DiagnosticsProvider: a read-only snapshot only the receiving app may read), under their own name,
     * volt-charge-recordings.json (ChargeSend.attachment). The export can be tens of MB, so it is moved into place off
     * the main thread, never read into memory; over 25 MB it arrives already gzip-compressed, as
     * volt-charge-recordings.json.gz (the owner's decisions on #383): the service compresses it as the last step of
     * the export, under its exporting flag and before the mailbox hand-over (ChargeSend.prepare; #412 review), so this
     * step stays a rename and a recreation during the compression can't lose the email. The subject is fixed English for the
     * developer's inbox (IssueReport.chargeRecordingsSubject) and names the model; the body carries only Volt's version
     * and that model, as Report an issue does. The model line comes with the file, taken when the row was tapped
     * (ChargeSend.Export), so a scooter selected meanwhile can't relabel it. Toasts when the attachment can't be
     * prepared, or no email app can take it.
     */
    /**
     * ChargeSend.MAILBOX's receiver while this activity shows Volt: it emails a finished export, or toasts a failed one
     * ("Charge recordings could not be exported", or the service's other status text), once (#412 round-2 review).
     */
    private final java.util.function.Consumer<ChargeSend.Export> chargeMail=export->{
        if(export.failed()){if(!destroyed)toast(export.failure.isEmpty()?ScooterService.chargeExportFailed():export.failure);}
        else if(destroyed)export.discard();else emailChargeRecordings(export.file,export.model);};
    @Override public void emailChargeRecordings(File recordings,String model){
        String version=AppVersion.name(this);
        String subject=IssueReport.chargeRecordingsSubject(version,model);
        String body="Volt "+version+(model.isEmpty()?"":"\n"+model)+"\n";
        new Thread(()->{
            Uri uri;try{uri=DiagnosticsProvider.adopt(this,recordings);}catch(java.io.IOException|RuntimeException failed){uri=null;}
            final Uri attached=uri;
            handler.post(()->{
                if(destroyed)return;
                // An attachment that couldn't be prepared says so, not "no email app" (#387 review).
                ChargeSend.Outcome outcome=ChargeSend.outcome(attached!=null,attached!=null&&sendWithUri(subject,body,attached));
                if(outcome==ChargeSend.Outcome.EXPORT_FAILED)toast(ScooterService.chargeExportFailed());
                else if(outcome==ChargeSend.Outcome.NO_EMAIL_APP)toast(HubText.chargeSendNoEmail());
            });
        },"Volt-email-export").start();
    }
    /** The Language sheet's drafts: straight to the email app, never the diagnostics sheet. */
    @Override public boolean emailDraft(String subject,String body){return email(subject,body);}
    /** app_settings: whether report emails carry the diagnostics file; on until the owner unticks it, then remembered. */
    static final String REPORT_DIAGNOSTICS="report_diagnostics";
    /** The open diagnostics preview, over the email sheet. */
    private Dialog diagnosticsPreview;
    /**
     * The last step of both report emails, before the email app opens: Include diagnostics, what the file holds, and
     * Preview, which shows it exactly. The file is built once, here, so what the owner previews is what is attached.
     * ready is asked again on Open email (false closes the sheet, sending nothing); sent runs once the email app opened.
     */
    private void showEmailSheet(String subject,String body,CompatibilityReport report,java.util.function.BooleanSupplier ready,Runnable sent){
        final String bundle=scooterService==null?null:scooterService.diagnosticsBundle(report);
        Dialog[] self={null};
        LinearLayout content=sheetContent(T.mainReportEmailTitle(),null,()->{if(self[0]!=null)self[0].dismiss();});
        TextView to=design.caption(T.mainReportTo(ReportInvite.ADDRESS));to.setTextIsSelectable(true);to.setPadding(design.dp(4),0,design.dp(4),design.dp(12));content.addView(to);
        LinearLayout card=design.card();card.setTag("diagnostics-card");
        LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);line.setMinimumHeight(design.dp(48));line.setTag("diagnostics-row");
        android.widget.CheckBox include=new android.widget.CheckBox(dialogContext());include.setTag("diagnostics-include");
        include.setButtonTintList(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{android.R.attr.state_checked},new int[]{}},new int[]{design.dim,design.accent,design.muted}));
        include.setChecked(bundle!=null&&preferences.getBoolean(REPORT_DIAGNOSTICS,true));include.setEnabled(bundle!=null);
        include.setClickable(false);include.setFocusable(false);include.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        line.addView(include,new LinearLayout.LayoutParams(design.dp(36),design.dp(36)));
        // The narrow-label ladder: the full label, then the short one; never a word broken or the text shrunk.
        TextView label=new TextView(this){@Override protected void onMeasure(int width,int height){
            int room=MeasureSpec.getSize(width)-getTotalPaddingLeft()-getTotalPaddingRight();
            String full=T.mainReportInclude();
            String want=MeasureSpec.getMode(width)==MeasureSpec.UNSPECIFIED||getPaint().measureText(full)<=room?full:T.mainReportIncludeShort();
            if(!want.contentEquals(getText()))setText(want);
            super.onMeasure(width,height);
        }};
        label.setText(T.mainReportInclude());label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,15*design.textScale);label.setTypeface(design.bold());
        label.setTextColor(bundle==null?design.muted:design.text);label.setIncludeFontPadding(false);label.setMaxLines(1);label.setPadding(design.dp(6),0,design.dp(6),0);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);label.setTag("diagnostics-label");
        line.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        line.setClickable(bundle!=null);line.setFocusable(bundle!=null);line.setBackground(rowRipple());line.setContentDescription(T.mainReportInclude());
        line.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View v,AccessibilityNodeInfo info){
            super.onInitializeAccessibilityNodeInfo(v,info);info.setClassName(android.widget.CheckBox.class.getName());info.setCheckable(true);info.setChecked(include.isChecked());info.setEnabled(include.isEnabled());}});
        line.setOnClickListener(v->{if(!include.isEnabled())return;include.setChecked(!include.isChecked());preferences.edit().putBoolean(REPORT_DIAGNOSTICS,include.isChecked()).apply();});
        TextView preview=design.text(T.mainReportPreview(),14,false);preview.setTypeface(design.medium());preview.setTextColor(design.accent);preview.setGravity(Gravity.CENTER);
        preview.setMinHeight(design.dp(48));preview.setMinWidth(design.dp(48));preview.setPadding(design.dp(12),0,design.dp(4),0);preview.setMaxLines(1);
        preview.setClickable(true);preview.setFocusable(true);preview.setTag("diagnostics-preview");preview.setContentDescription(T.mainReportPreviewDescription());
        preview.setOnClickListener(v->{if(bundle!=null)showDiagnosticsPreview(bundle);});preview.setVisibility(bundle==null?View.GONE:View.VISIBLE);
        line.addView(preview,new LinearLayout.LayoutParams(-2,-2));
        card.addView(line);
        TextView note=design.caption(bundle==null?T.mainReportNoBundle():T.mainReportDiagnosticsNote());
        note.setPadding(design.dp(4),design.dp(6),design.dp(4),0);note.setTag(TAG_DIAGNOSTICS_NOTE);card.addView(note);
        content.addView(card);
        TextView open=design.button(T.mainReportOpenEmail(),()->{
            if(ready!=null&&!ready.getAsBoolean()){if(self[0]!=null)self[0].dismiss();return;}
            boolean attach=bundle!=null&&include.isChecked(),opened=attach&&emailWithFile(subject,body,bundle);
            if(!opened){opened=email(subject,body);if(opened&&attach)toast(T.mainReportNoAttach());}
            if(!opened){toast(T.mainReportNoEmail());return;}
            if(self[0]!=null)self[0].dismiss();if(sent!=null)sent.run();
        });
        open.setTag("email-open");content.addView(open);
        AlertDialog dialog=showSheet(content);self[0]=dialog;reportDialog=dialog;
        dialog.setOnDismissListener(d->{if(diagnosticsPreview!=null)diagnosticsPreview.dismiss();if(reportDialog==dialog)reportDialog=null;});
    }
    /** The exact diagnostics file, readable and scrollable, over the email sheet. */
    private void showDiagnosticsPreview(String bundle){
        if(diagnosticsPreview!=null&&diagnosticsPreview.isShowing())return;
        Dialog[] self={null};
        int bytes=bundle.getBytes(StandardCharsets.UTF_8).length;
        String size=bytes<1024?T.mainReportSizeBytes(bytes,bytes):T.mainReportSizeKb(Fmt.decimal(Locale.ROOT, bytes / 1024.0, 1));
        LinearLayout content=sheetContent(T.mainReportDiagnosticsTitle(),T.mainReportPreviewBody(DiagnosticsProvider.NAME,size),()->{if(self[0]!=null)self[0].dismiss();});
        ScrollView scroll=new ScrollView(this);scroll.setTag("diagnostics-preview-scroll");scroll.setBackground(design.shape(design.surface,16,0));
        TextView text=design.text(bundle,12,false);text.setTypeface(android.graphics.Typeface.MONOSPACE);text.setTextIsSelectable(true);
        text.setPadding(design.dp(12),design.dp(12),design.dp(12),design.dp(12));text.setTag("diagnostics-preview-text");
        scroll.addView(text,new ScrollView.LayoutParams(-1,-2));
        content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        Dialog dialog=showTallSheet(content);self[0]=dialog;diagnosticsPreview=dialog;
        dialog.setOnDismissListener(d->{if(diagnosticsPreview==dialog)diagnosticsPreview=null;});
    }
    /**
     * As email(), with the diagnostics file attached (DiagnosticsProvider). Only the app that receives this intent may
     * read it, through the intent's own read grant; false when the file can't be written or no app can take it.
     */
    private boolean emailWithFile(String subject,String body,String bundle){
        Uri uri;try{uri=DiagnosticsProvider.write(this,bundle);}catch(java.io.IOException|RuntimeException failed){return false;}
        return sendWithUri(subject,body,uri);
    }
    /**
     * emailWithFile's send, for a snapshot DiagnosticsProvider already holds, typed and named as the provider serves it
     * (volt-diagnostics.json, or the charge recordings' own name, gzip-compressed over 25 MB); false when no app can take it.
     */
    private boolean sendWithUri(String subject,String body,Uri uri){
        String name=uri.getLastPathSegment(),type=DiagnosticsProvider.type(name);
        Intent send=new Intent(Intent.ACTION_SEND);send.setType(type==null?DiagnosticsProvider.TYPE:type);
        send.putExtra(Intent.EXTRA_EMAIL,new String[]{ReportInvite.ADDRESS});send.putExtra(Intent.EXTRA_SUBJECT,subject);send.putExtra(Intent.EXTRA_TEXT,body);
        send.putExtra(Intent.EXTRA_STREAM,uri);send.setClipData(android.content.ClipData.newRawUri(name,uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // Email apps only, as the plain email opens them (a mailto selector); else the chooser of apps that take an email.
        send.setSelector(new Intent(Intent.ACTION_SENDTO,Uri.parse("mailto:")));
        try{startActivity(send);return true;}catch(android.content.ActivityNotFoundException none){}
        send.setSelector(null);send.setType("message/rfc822");
        try{startActivity(Intent.createChooser(send,T.mainReportChooser()));return true;}catch(RuntimeException none){return false;}
    }
    /** Opens the owner's email app addressed to Volt's report alias and filled in; false when no app can. */
    private boolean email(String subject,String body){
        Intent mail=new Intent(Intent.ACTION_SENDTO,Uri.parse("mailto:"));
        mail.putExtra(Intent.EXTRA_EMAIL,new String[]{ReportInvite.ADDRESS});mail.putExtra(Intent.EXTRA_SUBJECT,subject);
        mail.putExtra(Intent.EXTRA_TEXT,body);
        try{startActivity(mail);return true;}catch(android.content.ActivityNotFoundException none){}
        Intent send=new Intent(Intent.ACTION_SEND);send.setType("message/rfc822");send.putExtras(mail);
        try{startActivity(Intent.createChooser(send,T.mainReportChooser()));return true;}catch(RuntimeException none){return false;}
    }
    private void styleDialog(AlertDialog dialog,int primaryButton){
        android.view.Window window=dialog.getWindow();
        if(window!=null)window.setBackgroundDrawable(new android.graphics.drawable.InsetDrawable(design.shape(design.surface,24,0),design.dp(16)));
        TextView message=dialog.findViewById(android.R.id.message);
        if(message!=null){message.setTypeface(design.regular());message.setTextColor(design.muted);message.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,14*design.textScale);message.setLineSpacing(design.dp(3),1);}
        for(int which:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL})
            styleDialogButton(dialog.getButton(which),which==primaryButton?design.accent:design.text);
    }
    private void styleDialogButton(android.widget.Button button,int color){
        if(button==null)return;button.setTextColor(color);button.setTypeface(design.semibold());button.setAllCaps(false);
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,14*design.textScale);
    }
    private TextView dialogTitle(String title){
        TextView view=design.text(title,20,true);view.setPadding(design.dp(24),design.dp(22),design.dp(24),design.dp(6));
        if(Build.VERSION.SDK_INT>=28)view.setAccessibilityHeading(true);return view;
    }
    /** Bottom sheet frame (ph/27): grab handle, title, close button and an optional one-line explanation. */
    @Override public LinearLayout sheetContent(String title,String explanation,Runnable close){
        LinearLayout content=column();content.setPadding(design.dp(18),design.dp(8),design.dp(18),design.dp(18));
        View grab=new View(this);grab.setBackground(design.shape(design.outline,2,0));
        LinearLayout.LayoutParams grabParams=new LinearLayout.LayoutParams(design.dp(36),design.dp(4));grabParams.gravity=Gravity.CENTER_HORIZONTAL;grabParams.bottomMargin=design.dp(8);content.addView(grab,grabParams);
        LinearLayout head=row();head.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading=design.text(title,20,true);if(Build.VERSION.SDK_INT>=28)heading.setAccessibilityHeading(true);head.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        FrameLayout dismiss=new FrameLayout(this);
        android.graphics.drawable.GradientDrawable disc=new android.graphics.drawable.GradientDrawable();disc.setShape(android.graphics.drawable.GradientDrawable.OVAL);disc.setColor(design.surface);
        dismiss.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(Ui.withAlpha(design.accent,40)),disc,null));
        dismiss.addView(design.icon("close",16,design.text),new FrameLayout.LayoutParams(design.dp(16),design.dp(16),Gravity.CENTER));
        dismiss.setContentDescription(T.commonClose());dismiss.setClickable(true);dismiss.setFocusable(true);dismiss.setOnClickListener(v->close.run());
        LinearLayout.LayoutParams dismissParams=new LinearLayout.LayoutParams(design.dp(48),design.dp(48));dismissParams.leftMargin=design.dp(10);head.addView(dismiss,dismissParams);
        content.addView(head);
        if(explanation!=null&&!explanation.isEmpty()){TextView line=design.caption(explanation);line.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,13*design.textScale);line.setPadding(0,design.dp(6),0,design.dp(14));content.addView(line);}
        else{View gap=new View(this);content.addView(gap,new LinearLayout.LayoutParams(1,design.dp(10)));}
        return content;
    }
    @Override public AlertDialog showSheet(View content){
        ScrollView holder=new ScrollView(this);holder.addView(content,new ScrollView.LayoutParams(-1,-2));
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setView(holder).create();
        android.view.Window window=dialog.getWindow();
        Motion.present(dialog,Motion.SHEET);
        if(window!=null){
            android.graphics.drawable.GradientDrawable sheet=new android.graphics.drawable.GradientDrawable();sheet.setColor(design.bg);
            float r=design.dp(24);sheet.setCornerRadii(new float[]{r,r,r,r,0,0,0,0});sheet.setStroke(design.dp(1),design.raised);
            window.setBackgroundDrawable(sheet);window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
            window.getDecorView().setPadding(0,0,0,0);
        }
        dialog.show();
        if(window!=null)window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        return dialog;
    }
    /** The open tall sheet, kept nine-tenths of Volt's window tall through rotation, folding and window resizing. */
    private Dialog tallSheet;private boolean tallSheetFollows;
    private int tallSheetHeight(){int h=getWindow().getDecorView().getHeight();return Math.round((h>0?h:getResources().getDisplayMetrics().heightPixels)*.9f);}
    private void fitTallSheet(){
        android.view.Window window=tallSheet==null||!tallSheet.isShowing()?null:tallSheet.getWindow();
        if(window!=null&&window.getAttributes().height!=tallSheetHeight())window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,tallSheetHeight());
    }
    /** A bottom sheet of fixed height whose content fills it, so a long list scrolls between a fixed title and footer. */
    private Dialog showTallSheet(LinearLayout content){
        Dialog dialog=new Dialog(dialogContext());dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(content,new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        content.setFocusableInTouchMode(true);content.requestFocus();
        android.view.Window window=dialog.getWindow();
        Motion.present(dialog,Motion.SHEET);
        if(window!=null){
            android.graphics.drawable.GradientDrawable sheet=new android.graphics.drawable.GradientDrawable();sheet.setColor(design.bg);
            float r=design.dp(24);sheet.setCornerRadii(new float[]{r,r,r,r,0,0,0,0});sheet.setStroke(design.dp(1),design.raised);
            window.setBackgroundDrawable(sheet);window.setGravity(Gravity.BOTTOM);window.getDecorView().setPadding(0,0,0,0);
            // The keyboard waits for a tap on the search box; while it is up, the sheet rises so the results above it stay in view.
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
        if(window!=null)window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,tallSheetHeight());
        tallSheet=dialog;
        // Volt's window changes size without being recreated (configChanges): follow it, keeping the sheet's answer and check.
        if(!tallSheetFollows){tallSheetFollows=true;getWindow().getDecorView().addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(b-t!=ob-ot)fitTallSheet();});}
        return dialog;
    }
    private static final String RANGE_INTRO_SEEN="range_learned_intro_seen";
    private RangeSourceSheet rangeSourceSheet;
    private void showRangeSource(){
        if(scooterService==null||scooterService.profiles.active()==null){toast(T.mainAlertChooseScooter());return;}
        if(controlDialog!=null)controlDialog.dismiss();
        rangeSourceSheet=new RangeSourceSheet();
    }
    /** Local source choice; the scooter option retains the guarded drive-mode follower. */
    private final class RangeSourceSheet {
        final String profile=scooterService.profiles.active().id;
        final AlertDialog dialog;
        final ArrayList<LinearLayout> options=new ArrayList<>();
        final ArrayList<Ui.RadioDot> dots=new ArrayList<>();
        final ArrayList<TextView> names=new ArrayList<>();
        final TextView status;
        final View progress;
        final LinearLayout statusRow;
        AlertDialog intro;
        boolean pending;
        int requestId;
        long requestedRevision=-1;
        LearnedRangeEstimate.Result result;
        RangeSourceSheet(){
            final AlertDialog[] self=new AlertDialog[1];
            LinearLayout body=sheetContent(T.mainRangeTitle(),null,()->{if(self[0]!=null)self[0].dismiss();});
            body.setTag("range-source-sheet");
            LinearLayout list=design.groupCard();body.addView(list,new LinearLayout.LayoutParams(-1,-2));
            String[] labels={T.mainRangeScooter(),T.mainRangeVolt()};
            for(int i=0;i<labels.length;i++){
                if(i>0)design.divider(list);
                final boolean learned=i==1;
                LinearLayout option=row();option.setGravity(Gravity.CENTER_VERTICAL);
                option.setMinimumHeight(design.dp(64));option.setPadding(design.dp(4),design.dp(12),design.dp(4),design.dp(12));
                TextView name=design.text(labels[i],16,false);name.setMaxLines(3);
                option.addView(name,new LinearLayout.LayoutParams(0,-2,1));
                Ui.RadioDot dot=design.radioDot();LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(design.dp(24),design.dp(24));dp.leftMargin=design.dp(14);option.addView(dot,dp);
                option.setTag(learned?"range-source-volt":"range-source-scooter");
                option.setClickable(true);option.setFocusable(true);option.setBackground(rowRipple());option.setContentDescription(labels[i]);
                name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                option.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View v,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(v,info);info.setClassName(android.widget.RadioButton.class.getName());info.setCheckable(true);info.setChecked(dot.checked);}});
                option.setOnClickListener(v->choose(learned));list.addView(option);options.add(option);dots.add(dot);names.add(name);
            }
            statusRow=row();statusRow.setGravity(Gravity.CENTER_VERTICAL);statusRow.setPadding(design.dp(4),design.dp(14),design.dp(4),0);
            progress=new Spinner();LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(design.dp(16),design.dp(16));sp.rightMargin=design.dp(10);statusRow.addView(progress,sp);
            status=design.caption("");status.setTag("range-learning-status");status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            statusRow.addView(status,new LinearLayout.LayoutParams(0,-2,1));body.addView(statusRow);
            dialog=showSheet(body);self[0]=dialog;controlDialog=dialog;
            dialog.setOnDismissListener(d->{requestId++;if(intro!=null)intro.dismiss();if(rangeSourceSheet==this)rangeSourceSheet=null;if(controlDialog==dialog)controlDialog=null;});
            refresh();
            request();
        }
        boolean current(){return !destroyed&&scooterService!=null&&scooterService.profiles.active()!=null&&profile.equals(scooterService.profiles.active().id);}
        void choose(boolean learned){
            if(!current()){dialog.dismiss();return;}
            Haptics.tick(options.get(learned?1:0));
            if(learned&&scooterService.simulating()){toast(T.mainRangeExitSimulation());return;}
            if(learned&&requestedRevision!=scooterService.learnedRangeRevision())request();
            if(learned&&(pending||result==null)){toast(T.mainRangeChecking());return;}
            if(learned&&result.status==LearnedRangeEstimate.Status.UNAVAILABLE){request();return;}
            if(learned&&result.status==LearnedRangeEstimate.Status.INSUFFICIENT){
                showExplanation(false);return;
            }
            scooterService.useLearnedRange(learned);
            // An explicit scooter choice means following drive mode. Opening the picker or
            // choosing Volt preserves an existing fixed firmware preference without writes.
            if(!learned&&!scooterService.followsRidingMode())scooterService.followRidingMode(true);
            if(learned&&!preferences.getBoolean(RANGE_INTRO_SEEN,false))showExplanation(true);
            refresh();
        }
        void showExplanation(boolean firstUse){
            if(intro!=null&&intro.isShowing())return;
            final AlertDialog[] shown=new AlertDialog[1];
            LinearLayout body=sheetContent(firstUse?T.mainRangeIntroTitle():T.mainRangeMoreTitle(),null,()->{if(shown[0]!=null)shown[0].dismiss();});
            body.setTag(firstUse?"range-learned-intro":"range-more-rides");
            LinearLayout message=row();message.setGravity(Gravity.TOP);message.setPadding(design.dp(16),design.dp(16),design.dp(16),design.dp(16));
            message.setBackground(design.shape(Ui.withAlpha(design.accent,design.dark?24:14),18,Ui.withAlpha(design.accent,50)));
            message.addView(design.icon("route",26,design.accent),new LinearLayout.LayoutParams(design.dp(26),design.dp(26)));
            TextView words=design.text(firstUse?T.mainRangeIntroBody():T.mainRangeMoreBody(),15,false);
            LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1);wp.leftMargin=design.dp(14);message.addView(words,wp);body.addView(message);
            TextView gotIt=design.button(T.mainRangeGotIt(),()->{if(shown[0]!=null)shown[0].dismiss();});gotIt.setTag("range-intro-done");
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.topMargin=design.dp(16);body.addView(gotIt,bp);
            intro=showSheet(body);shown[0]=intro;if(firstUse)preferences.edit().putBoolean(RANGE_INTRO_SEEN,true).apply();
            intro.setOnDismissListener(d->intro=null);
        }
        void request(){
            if(pending||!current()||!dialog.isShowing())return;
            pending=true;result=null;requestedRevision=scooterService.learnedRangeRevision();int id=++requestId;refresh();
            java.util.function.Consumer<LearnedRangeEstimate.Result> done=next->{
                if(id!=requestId||!current()||!dialog.isShowing())return;
                result=next;pending=false;requestedRevision=scooterService.learnedRangeRevision();refresh();
            };
            scooterService.requestLearnedRangeEstimate(done);
        }
        void refresh(){
            if(!current()){dialog.dismiss();return;}
            if(!pending&&requestedRevision>=0&&requestedRevision!=scooterService.learnedRangeRevision()){request();return;}
            boolean learned=scooterService.usesLearnedRange();
            boolean ready=!pending&&result!=null&&result.status==LearnedRangeEstimate.Status.READY;
            for(int i=0;i<options.size();i++){
                boolean on=learned==(i==1);dots.get(i).set(on);names.get(i).setTypeface(on?design.semibold():design.regular());
                boolean unavailable=i==1&&!ready;
                names.get(i).setTextColor(unavailable?design.muted:design.text);
                dots.get(i).setAlpha(unavailable?.45f:1f);
                String state=on?T.commonSelected():T.mainRangeNotSelected();
                if(unavailable)state+=scooterService.simulating()?T.mainRangeStateSimulation():pending||result==null?T.mainRangeStateChecking():result.status==LearnedRangeEstimate.Status.INSUFFICIENT?T.mainRangeStateMore():T.mainRangeStateRetry();
                if(Build.VERSION.SDK_INT>=30)options.get(i).setStateDescription(state);
            }
            String message=scooterService.simulating()?T.mainRangeExitSimulation():pending?T.mainRangeChecking():result==null?"":result.status==LearnedRangeEstimate.Status.UNAVAILABLE?T.mainRangeReadFailed()
                    :learned&&result.status==LearnedRangeEstimate.Status.INSUFFICIENT?T.mainRangeUsingScooter()
                    :learned&&ready?learnedSummary():"";
            status.setText(message);progress.setVisibility(pending?View.VISIBLE:View.GONE);
            statusRow.setVisibility(!message.isEmpty()?View.VISIBLE:View.GONE);
        }
        /** "About 10 km at 50% · likely 8–12 km": the learned forecast at the last battery reading. */
        String learnedSummary(){
            TelemetryHistory.Reading soc=scooterService.displayReadings().get("battery_percent");
            Double percent=soc==null||soc.value==null?null:soc.number,km=result.remainingKm(percent);
            if(km==null)return "";
            Double low=result.likelyLowKm(percent),high=result.likelyHighKm(percent);
            return low==null||high==null?T.mainRangeSummary(wholeDistance(km,km),Fmt.integer(Math.round(percent))):T.mainRangeSummaryLikely(wholeDistance(km,km),Fmt.integer(Math.round(percent)),wholeDistance(low,high));
        }
        String wholeDistance(double lowKm,double highKm){
            String units=preferences.getString("units","mi");
            String km=span(lowKm,highKm,T.unitsKm()),mi=span(lowKm/1.609344,highKm/1.609344,T.unitsMi());
            return units.equals("km")?km:units.equals("dual")?T.fmtDual(mi,km):mi;
        }
        String span(double low,double high,String unit){
            long a=Math.round(low),b=Math.round(high);
            return a==b?T.fmtQuantity(Fmt.integer(a),unit):T.fmtRange(Fmt.integer(a),Fmt.integer(b),unit);
        }
    }

    /** Local per-scooter estimate preference: never sends a charger command or starts capture. */
    private void showChargerEstimateChoice(){
        if(scooterService==null||scooterService.profiles.active()==null||scooterService.isChargeRecording())return;
        String profile=scooterService.profiles.active().id;
        final AlertDialog[] sheet=new AlertDialog[1];
        LinearLayout content=sheetContent(T.mainRowCharger(),
                T.mainChargerIntro(),
                ()->{if(sheet[0]!=null)sheet[0].dismiss();});
        SegmentedSelector selector=new SegmentedSelector(design,T.mainRowCharger(),new String[]{T.mainChargerAuto(),T.mainRowStock(),T.mainRowHighSpeed()},choice->{
            if(scooterService==null||scooterService.profiles.active()==null||!profile.equals(scooterService.profiles.active().id)){
                if(sheet[0]!=null)sheet[0].dismiss();return;
            }
            if(scooterService.setChargeCharger(choice==0?null:choice==1?ChargeSessionStore.Charger.STOCK:ChargeSessionStore.Charger.HIGH_SPEED)){
                if(sheet[0]!=null)sheet[0].dismiss();redraw();
            }else toast(T.mainChargerStopRecording());
        });
        selector.setTag("estimate-charger-selector");
        ChargeSessionStore.Charger selected=scooterService.chargeCharger();
        selector.select(selected==null?0:selected==ChargeSessionStore.Charger.STOCK?1:2,false);
        content.addView(selector,new LinearLayout.LayoutParams(-1,-2));
        TextView explanation=design.caption(T.mainChargerNote());
        explanation.setPadding(0,design.dp(12),0,design.dp(4));content.addView(explanation);
        controlDialog=showSheet(content);sheet[0]=controlDialog;
    }
    private void editFeature(ScooterCommands.Feature feature){
        if(feature.key.equals("range_algorithm")){showRangeSource();return;}
        if(feature.type==ScooterCommands.Type.TIME){showChargingTime();return;}
        // A slider on the UT5 MAX can be an explicit value list: another model's OEM table, or a non-US UT5 MAX's 3–5 km/h kick-start.
        FeatureVariant variant=scooterService==null?null:scooterService.ble.decision(feature.key).variant;
        if(feature.type!=ScooterCommands.Type.SLIDER||variant!=null&&variant.values.length>0){showChoice(feature);return;}
        if(scooterService==null||!scooterService.ble.ready()||scooterService.ble.pending()!=null)return;
        Integer current=scooterService.ble.featureValue(feature.key);if(current==null)return;
        final AlertDialog[] sheet=new AlertDialog[1];Runnable close=()->{if(sheet[0]!=null)sheet[0].dismiss();};
        LinearLayout body=sheetContent(shortLabel(feature),explanation(feature),close);
        TextView amount=design.text(featureLabel(feature,current),28,true);amount.setPadding(design.dp(2),design.dp(4),0,design.dp(10));body.addView(amount);
        SeekBar slider=new SeekBar(this);slider.setMax((feature.max-feature.min)/feature.step);slider.setProgress(Math.max(0,(current-feature.min)/feature.step));slider.setProgressTintList(ColorStateList.valueOf(design.accent));slider.setThumbTintList(ColorStateList.valueOf(design.accent));slider.setProgressBackgroundTintList(ColorStateList.valueOf(design.outline));slider.setContentDescription(shortLabel(feature));body.addView(slider,new LinearLayout.LayoutParams(-1,design.dp(48)));
        // Built from the numbers: the low end is a bare number where the high end names the unit (DisplayUnits.sliderEnds).
        String[] range=DisplayUnits.sliderEnds(feature,preferences.getString("units","mi"));String low=range[0],high=range[1];
        // The range ends sit under the slider instead of a sentence about them.
        LinearLayout ends=row();ends.setPadding(design.dp(4),0,design.dp(4),design.dp(6));ends.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        TextView lowText=design.caption(low);ends.addView(lowText,new LinearLayout.LayoutParams(0,-2,1));
        TextView highText=design.caption(high);highText.setGravity(Gravity.END);ends.addView(highText,new LinearLayout.LayoutParams(-2,-2));
        body.addView(ends,new LinearLayout.LayoutParams(-1,-2));
        final int[] selected={current},base={current};slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int n,boolean user){if(user){selected[0]=feature.min+n*feature.step;amount.setText(featureLabel(feature,selected[0]));}}public void onStopTrackingTouch(SeekBar s){}});
        TextView error=design.caption("");error.setTextColor(design.danger);error.setPadding(design.dp(2),design.dp(4),0,design.dp(4));body.addView(error);
        LinearLayout actions=row();
        TextView cancel=design.secondaryButton(T.commonCancel(),close);
        final ApplyCheck[] check=new ApplyCheck[1];
        TextView apply=design.button(T.mainEditApply(),()->{
            Integer latest=scooterService.ble.featureValue(feature.key);
            if(!scooterService.ble.ready()||latest==null){error.setText(T.mainEditNotConnected());return;}
            if(!latest.equals(base[0])){
                // The scooter changed underneath: reload the slider to its readback and say so with a brief checkmark.
                base[0]=latest;selected[0]=latest;error.setText("");
                slider.setProgress(Math.max(0,(latest-feature.min)/feature.step));amount.setText(featureLabel(feature,latest));
                check[0].show();return;
            }
            int value=selected[0];
            confirmChange(feature,value,()->{if(scooterService.ble.set(feature.key,value)){if(sheet[0]!=null)sheet[0].dismiss();}else error.setText(scooterService.ble.displayMessage());redraw();});});
        check[0]=new ApplyCheck(apply,"Apply");
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,-2,1);left.rightMargin=design.dp(5);actions.addView(cancel,left);
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,-2,1);right.leftMargin=design.dp(5);actions.addView(apply,right);
        body.addView(actions);
        AlertDialog dialog=showSheet(body);sheet[0]=dialog;controlDialog=dialog;
    }
    /**
     * Apply button feedback: the label gives way to a checkmark that springs in, holds a few seconds, then the
     * label returns. Spoken as "Updated on scooter".
     */
    private final class ApplyCheck {
        private final TextView button;private final Msg label;private final ColorStateList colors;
        private float progress;private android.animation.ValueAnimator motion;
        private final Runnable hide=()->animateTo(0);
        private final android.graphics.drawable.Drawable mark=new android.graphics.drawable.Drawable(){
            @Override public void draw(Canvas canvas){
                if(progress<=0)return;android.graphics.Rect b=getBounds();
                float grow=progress<1?(float)(1+.18*Math.sin(Math.PI*progress))*progress:1;
                design.drawIcon(canvas,"check",colors.getColorForState(button.getDrawableState(),design.onAccent),b.exactCenterX(),b.exactCenterY(),design.dp(24)*grow);
            }
            @Override public void setAlpha(int alpha){}
            @Override public void setColorFilter(android.graphics.ColorFilter filter){}
            @Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
        };
        ApplyCheck(TextView button,String action){this.button=button;this.label=M.mainEditApply();this.colors=button.getTextColors();}
        void show(){
            button.removeCallbacks(hide);
            mark.setBounds(0,0,button.getWidth(),button.getHeight());button.getOverlay().remove(mark);button.getOverlay().add(mark);
            button.setContentDescription(T.mainEditUpdated());button.announceForAccessibility(T.mainEditUpdated());
            animateTo(1);button.postDelayed(hide,2600);
        }
        private void animateTo(float target){
            if(motion!=null){motion.cancel();motion=null;}
            if(!Ui.motionEnabled()||!button.isAttachedToWindow()){apply(target);finish(target);return;}
            motion=android.animation.ValueAnimator.ofFloat(progress,target);motion.setDuration(target>0?260:200);
            motion.setInterpolator(new android.view.animation.PathInterpolator(.2f,0,0,1));
            motion.addUpdateListener(a->apply((Float)a.getAnimatedValue()));
            motion.addListener(new android.animation.AnimatorListenerAdapter(){
                private boolean cancelled;
                @Override public void onAnimationCancel(android.animation.Animator a){cancelled=true;}
                @Override public void onAnimationEnd(android.animation.Animator a){if(!cancelled)finish(target);}
            });
            motion.start();
        }
        /** The label fades out as the mark grows in, and back. */
        private void apply(float value){progress=value;button.setTextColor(colors.withAlpha(Math.round(255*Math.max(0,1-value*1.6f))));button.invalidate();}
        private void finish(float target){
            if(target>0)return;
            button.getOverlay().remove(mark);button.setTextColor(colors);button.setText(label);button.setContentDescription(label);
        }
    }
    private ChoiceSheet choiceSheet;
    private void showChoice(ScooterCommands.Feature feature){
        if(scooterService==null||!scooterService.ble.ready()||scooterService.ble.pending()!=null)return;
        int[] choices=scooterService.ble.featureChoices(feature.key);if(choices.length==0){toast(T.mainEditNotOnFirmware());return;}
        if(feature.key.equals("range_algorithm")) {
            int[] withFollow=new int[choices.length+1]; withFollow[0]=-1;
            System.arraycopy(choices,0,withFollow,1,choices.length); choices=withFollow;
        }
        choiceSheet=new ChoiceSheet(feature,choices);
    }
    /** Choice picker (ph/27). It stays open until the scooter confirms the new value by readback. */
    private final class ChoiceSheet {
        final ScooterCommands.Feature feature;final int[] choices;final AlertDialog dialog;
        final ArrayList<LinearLayout> options=new ArrayList<>();final ArrayList<Ui.RadioDot> dots=new ArrayList<>();final ArrayList<TextView> names=new ArrayList<>();
        final SegmentedSelector selector;
        final LinearLayout wait;final TextView status;
        Integer target;boolean sent;CharSequence failure;
        ChoiceSheet(ScooterCommands.Feature feature,int[] choices){
            this.feature=feature;this.choices=choices;
            final AlertDialog[] self=new AlertDialog[1];
            LinearLayout content=sheetContent(shortLabel(feature),explanation(feature),()->{if(self[0]!=null)self[0].dismiss();});
            String[] labels=new String[choices.length];boolean compact=choices.length>=2&&choices.length<=5&&!feature.key.equals("range_algorithm");
            for(int i=0;i<choices.length;i++){labels[i]=featureLabel(feature,choices[i]);compact&=!textOver(labels[i],18,9.2,design.text(labels[i],15,false));}
            selector=compact?new SegmentedSelector(design,shortLabel(feature),labels,this::choose):null;
            // Another model's longer tables (five speeds, five sounds) wrap onto rows instead of scrolling past the edge.
            if(selector!=null&&oemVariant(feature.key)!=null)selector.setWrapChoices(true);
            if(selector!=null){selector.setTag("scooter-selector-"+feature.key);content.addView(selector,new LinearLayout.LayoutParams(-1,-2));}
            else{
            LinearLayout list=design.groupCard();
            for(int i=0;i<choices.length;i++){
                if(i>0)design.divider(list);
                final int index=i;
                LinearLayout option=row();option.setGravity(Gravity.CENTER_VERTICAL);option.setMinimumHeight(design.dp(54));option.setPadding(design.dp(4),design.dp(6),design.dp(2),design.dp(6));
                boolean auto=feature.key.equals("range_algorithm")&&choices[i]==-1;
                TextView label=design.text(auto?T.mainAutoRange():featureLabel(feature,choices[i]),15,false);option.addView(label,new LinearLayout.LayoutParams(0,-2,1));
                if(auto){
                    // The Auto chip marks the one choice that follows the riding mode by itself.
                    TextView chip=design.text(T.mainEditAuto(),11,true);chip.setTextColor(design.blue);chip.setPadding(design.dp(8),design.dp(2),design.dp(8),design.dp(3));
                    chip.setBackground(design.shape(Ui.withAlpha(design.blue,design.dark?40:28),100,0));chip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    LinearLayout.LayoutParams chipSize=new LinearLayout.LayoutParams(-2,-2);chipSize.setMarginStart(design.dp(8));option.addView(chip,chipSize);
                }
                Ui.RadioDot dot=design.radioDot();LinearLayout.LayoutParams dotParams=new LinearLayout.LayoutParams(design.dp(22),design.dp(22));dotParams.leftMargin=design.dp(10);option.addView(dot,dotParams);
                option.setClickable(true);option.setFocusable(true);option.setBackground(rowRipple());option.setContentDescription(label.getText());
                option.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(android.widget.RadioButton.class.getName());info.setCheckable(true);info.setChecked(dot.checked);}});
                option.setOnClickListener(v->choose(index));
                list.addView(option);options.add(option);dots.add(dot);names.add(label);
            }
            content.addView(list);
            }
            wait=row();wait.setGravity(Gravity.CENTER_VERTICAL);wait.setPadding(design.dp(4),design.dp(4),0,design.dp(4));
            wait.addView(new Spinner(),new LinearLayout.LayoutParams(design.dp(14),design.dp(14)));
            TextView waiting=design.caption(T.mainSpeedConfirmingVisible());waiting.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,13*design.textScale);
            LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(-2,-2);wp.leftMargin=design.dp(10);wait.addView(waiting,wp);wait.setVisibility(View.GONE);content.addView(wait);
            status=design.caption("");status.setPadding(design.dp(4),design.dp(4),0,0);status.setVisibility(View.GONE);content.addView(status);
            dialog=showSheet(content);self[0]=dialog;controlDialog=dialog;
            dialog.setOnDismissListener(d->{if(choiceSheet==ChoiceSheet.this)choiceSheet=null;});
            refresh();
        }
        void choose(int index){
            if(selector!=null)restoreSelection(false);
            if(scooterService==null||sent)return;BleController ble=scooterService.ble;
            if(feature.key.equals("range_algorithm")) {
                if(choices[index]==-1) { scooterService.followRidingMode(true); dialog.dismiss(); return; }
                scooterService.followRidingMode(false);
            }
            Integer actual=ble.featureValue(feature.key);if(actual!=null&&actual==choices[index]){dialog.dismiss();return;}
            int choice=choices[index];
            confirmChange(feature,choice,()->{
                if(sent)return;failure=null;
                if(ble.set(feature.key,choice)){target=choice;sent=true;}else failure=ble.displayMessage();
                refresh();redraw();
            });
        }
        void refresh(){
            BleController ble=scooterService==null?null:scooterService.ble;boolean ready=ble!=null&&ble.ready();String pending=ble==null?null:ble.pending();
            Integer actual=ble==null?null:ble.featureValue(feature.key);
            boolean waiting=sent&&feature.key.equals(pending);
            if(sent&&!waiting){
                sent=false;
                if(target!=null&&target.equals(actual)){dialog.dismiss();return;}
                String feedback=ble==null?null:ble.commandResults().get(feature.key);
                failure=feedback==null||feedback.equals("Confirmed")||feedback.startsWith("Setting confirmed")||!feedback.startsWith("Rejected")?M.mainChipNotConfirmed():M.mainChipRejected();target=null;
            }
            Integer shown=waiting?target:feature.key.equals("range_algorithm") && scooterService != null && scooterService.followsRidingMode() ? Integer.valueOf(-1) : actual;boolean enabled=ready&&pending==null&&!sent;
            if(selector!=null){restoreSelection(true);boolean fresh=enabled&&actual!=null&&scooterService.featureFresh(feature.key);selector.setEnabled(fresh);selector.setAlpha(fresh?1f:.5f);}
            else for(int i=0;i<choices.length;i++){
                boolean on=shown!=null&&shown==choices[i];dots.get(i).set(on);dots.get(i).setEnabled(enabled);
                names.get(i).setTypeface(on?design.bold():design.regular());names.get(i).setTextColor(enabled||on?design.text:design.muted);
                options.get(i).setEnabled(enabled);if(Build.VERSION.SDK_INT>=30)options.get(i).setStateDescription(on?T.commonSelected():T.mainEditNotSelected());
            }
            String failure=this.failure==null?null:this.failure.toString();
            wait.setVisibility(waiting?View.VISIBLE:View.GONE);
            String message=failure!=null?failure:!ready&&!waiting?T.mainEditNotConnected():null;
            status.setText(message==null?"":message);status.setTextColor(failure!=null?design.danger:design.muted);status.setVisibility(message==null?View.GONE:View.VISIBLE);
        }
        private void restoreSelection(boolean animate){
            Integer actual=scooterService==null?null:scooterService.ble.featureValue(feature.key);int selected=-1;
            for(int i=0;i<choices.length;i++)if(actual!=null&&actual==choices[i])selected=i;
            selector.select(selected,animate);
        }
    }
    private void showChargingTime(){
        if(scooterService==null){toast(T.mainScheduleConnectYourScooter());return;}
        ChargeScheduleController.View charge=scooterService.chargeSchedule();
        if(charge.manualTimeBlockedReason!=null){toast(charge.manualTimeBlockedReason);return;}
        Calendar initial=Calendar.getInstance();if(charge.manualStartWallMs>0)initial.setTimeInMillis(charge.manualStartWallMs);
        final String profile=scooterService.profiles.active()==null?"":scooterService.profiles.active().id;
        // Other models retain their official whole-hour start-time domain.
        boolean wholeHours=scooterService.ble.decision("charge_start").layer!=ModelRegistry.Layer.UT5_MAX;
        if(wholeHours)initial.set(Calendar.MINUTE,0);
        TimePickerDialog picker=new TimePickerDialog(dialogContext(),(view,hour,picked)->{
            if(scooterService==null||scooterService.profiles.active()==null
                    ||!profile.equals(scooterService.profiles.active().id)){toast(T.mainScheduleScooterChanged());return;}
            long chosen=ChargeScheduleController.nextOccurrence(System.currentTimeMillis(),hour*60+(wholeHours?0:picked),TimeZone.getDefault());
            scooterService.setManualChargeStart(chosen,this::scheduleResult);redraw();
        },initial.get(Calendar.HOUR_OF_DAY),initial.get(Calendar.MINUTE),DisplayTime.is24Hour(this,preferences));
        picker.setTitle(wholeHours?T.mainEditNextStartHour():T.mainEditNextStart());controlDialog=picker;picker.show();
        styleDialogButton(picker.getButton(AlertDialog.BUTTON_POSITIVE),design.accent);
        styleDialogButton(picker.getButton(AlertDialog.BUTTON_NEGATIVE),design.muted);
    }
    /** The gutter follows the actual fold bounds within this content pane. */
    private final class TwoPane extends LinearLayout {
        final View first,second,gutter;
        TwoPane(View left,View right){super(MainActivity.this);setOrientation(HORIZONTAL);first=left;second=right;gutter=new View(MainActivity.this);addView(first,new LinearLayout.LayoutParams(0,-2,1));addView(gutter,new LinearLayout.LayoutParams(design.dp(20),1));addView(second,new LinearLayout.LayoutParams(0,-2,1));setBaselineAligned(false);}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();post(this::requestLayout);}
        @Override protected void onMeasure(int width,int height){
            int size=View.MeasureSpec.getSize(width);int gap=design.dp(20);int firstWidth=(size-gap)/2;
            if(verticalHinge()){int relative=fold.hinge.left-Ui.layoutLeftInWindow(this);int hingeGap=Math.max(design.dp(20),fold.hinge.width()+design.dp(16));int proposed=relative-design.dp(8);if(proposed>=design.dp(220)&&size-proposed-hingeGap>=design.dp(220)){firstWidth=proposed;gap=hingeGap;}}
            LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)first.getLayoutParams();p.width=Math.max(0,firstWidth);p.weight=0;LinearLayout.LayoutParams g=(LinearLayout.LayoutParams)gutter.getLayoutParams();g.width=gap;LinearLayout.LayoutParams s=(LinearLayout.LayoutParams)second.getLayoutParams();s.width=Math.max(0,size-firstWidth-gap);s.weight=0;super.onMeasure(width,height);
        }
    }
    @Override public void exportDashboardLayouts(){
        if(scooterService==null||scooterService.profiles.active()==null){toast(T.mainImportChooseScooter());return;}
        // Flush edits before creating a separate dashboard reader.
        if(screen instanceof DashboardScreen){screen.close();screen=null;rebuild();}
        try{DashboardConfig config=new DashboardConfig(preferences,scooterService.profiles.active().id);
            saveText("volt-dashboards.json","application/json",config.exportLayouts());
        }catch(Exception error){toast(T.mainImportExportFailed());}
    }
    @Override public void importDashboardLayouts(){
        if(scooterService==null||scooterService.profiles.active()==null){toast(T.mainImportChooseScooter());return;}
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        picker.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/plain","application/octet-stream"});
        try{startActivityForResult(picker,IMPORT_LAYOUTS);}catch(Exception missing){toast(T.mainImportNoPicker());}
    }
    private void confirmDashboardImport(String text,DashboardConfig.LayoutTransfer info){
        if(destroyed||enteringMaintenance||scooterService==null||scooterService.profiles.active()==null)return;
        String profileId=scooterService.profiles.active().id;
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle(T.mainImportReplaceTitle())
                .setMessage(T.mainImportReplaceBody(scooterService.profiles.active().shownName()))
                .setNegativeButton(T.commonCancel(),null).setPositiveButton(T.mainImportImportLayouts(),(d,n)->{
                    if(destroyed||enteringMaintenance||DataMaintenance.active())return;
                    if(scooterService==null||scooterService.profiles.active()==null||!profileId.equals(scooterService.profiles.active().id)){toast(T.mainImportScooterChanged());return;}
                    if(screen!=null){screen.close();screen=null;}
                    boolean restored=new DashboardConfig(preferences,profileId).importLayouts(text);
                    rebuild();toast(restored?T.mainImportImported():T.mainImportNotImported());
                }).create();controlDialog=dialog;dialog.show();styleDialog(dialog,AlertDialog.BUTTON_POSITIVE);
    }
    @Override public void dataBackup(Boolean restore,String preselect){
        if(enteringMaintenance||destroyed)return;
        if(restore==null){
            LinearLayout box=new LinearLayout(dialogContext());box.setOrientation(LinearLayout.VERTICAL);box.setPadding(design.dp(22),design.dp(18),design.dp(22),design.dp(4));
            TextView summary=design.caption(preselect!=null&&preselect.equals("rides")
                    ?T.mainImportRidesSummary()
                    :T.mainImportAllSummary());
            summary.setPadding(0,0,0,design.dp(12));box.addView(summary);
            TextView backUp=design.button(T.mainImportBackUp(),null),restoreButton=design.secondaryButton(T.mainImportRestore(),null);
            backUp.setTag("data-backup-create");restoreButton.setTag("data-backup-restore");box.addView(backUp);box.addView(restoreButton);
            AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle(preselect!=null&&preselect.equals("rides")?T.mainImportRidesTitle():T.mainImportAllTitle()).setView(box).setNegativeButton(T.commonCancel(),null).create();
            backUp.setOnClickListener(v->{dialog.dismiss();dataBackup(false,preselect);});restoreButton.setOnClickListener(v->{dialog.dismiss();dataBackup(true,preselect);});
            controlDialog=dialog;dialog.show();styleDialog(dialog,AlertDialog.BUTTON_NEGATIVE);return;
        }
        AlertDialog dialog=new AlertDialog.Builder(dialogContext()).setTitle(restore?T.mainImportRestoreTitle():T.mainImportBackUpTitle())
                .setMessage(restore?T.mainImportRestoreBody():T.mainImportPauseBody())
                .setNegativeButton(T.commonCancel(),null).setPositiveButton(T.mainConfirmContinue(),(d,n)->beginBackup(restore,preselect)).create();
        controlDialog=dialog;dialog.show();styleDialog(dialog,AlertDialog.BUTTON_POSITIVE);
    }
    private void beginBackup(boolean restore,String preselect){
        if(enteringMaintenance||destroyed)return;
        if(binding&&!bound){toast(T.mainImportStillOpening());return;}
        if(!preparingExports.isEmpty()||documents.contains("path")||applyingOwnerImport||pendingOwnerFile().exists()||new File(getFilesDir(),"pending-owner-import.tmp").exists()){
            toast(M.mainImportFinishFirst());return;
        }
        ShizukuCaptureClient capture=ShizukuCaptureClient.get(this);
        if(capture.busy||capture.pendingCleanup()){toast(T.mainImportFinishShizuku());return;}
        if(scooterService==null)scooterService=ScooterService.currentForMaintenance();
        String unavailable=scooterService==null?null:scooterService.dataMaintenanceUnavailable();
        if(unavailable!=null){toast(unavailable);return;}
        enteringMaintenance=true;resumed=false;connectionHaptics.cancel();pendingConnect=pendingScan=false;
        closeQuickMenu();if(scanDialog!=null)scanDialog.dismiss();if(controlDialog!=null)controlDialog.dismiss();
        if(screen!=null){screen.close();screen=null;}closeHomePane();onboarding=null;
        preferences.unregisterOnSharedPreferenceChangeListener(settingsChanged);
        handler.removeCallbacksAndMessages(null);fold.stop();
        if(scooterService!=null)scooterService.unlisten(serviceChanged);
        TextView wait=design.text(T.mainImportPreparing(),20,true);wait.setGravity(Gravity.CENTER);wait.setBackgroundColor(design.bg);setContentView(wait);
        java.util.function.Consumer<String> prepared=error->{
            if(!DataMaintenance.active())DataMaintenance.enter();
            Intent transfer=new Intent(this,BackupActivity.class).putExtra("restore",restore);
            if(preselect!=null)transfer.putExtra("preselect",preselect);
            if(error!=null)transfer.putExtra("preparation_error",error);
            DataMaintenance.preparedTransfer(transfer);
            startActivity(transfer);stopService(new Intent(this,ScooterService.class));finish();
        };
        if(scooterService==null){DataMaintenance.enter();prepared.accept(null);}
        else scooterService.prepareDataMaintenance(prepared);
    }
    @Override public void importOwner(){
        if(pendingOwnerFile().exists()){maybeApplyOwnerImport();if(pendingOwnerFile().exists())toast(T.mainImportOwnerWaiting());return;}
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/plain","application/octet-stream"});startActivityForResult(intent,IMPORT_OWNER);
    }
    @Override public void saveText(String name,String mime,String content){prepareSave(name,mime,null,content==null?"":content,"",null);}
    @Override public void saveFile(String name,String mime,File content){saveFile(name,mime,content,null);}
    /** copied runs once the pending copy of content is written or failed (on the copy thread), or now when the save is refused. */
    @Override public void saveFile(String name,String mime,File content,Runnable copied){if(content==null||!content.isFile()){toast(T.mainImportExportMissing());if(copied!=null)copied.run();return;}prepareSave(name,mime,content,null,"",copied);}
    /** Like saveText; the report's tries count as reported only once the file is written (a refused, cancelled or failed save leaves them unreported). */
    @Override public void saveReport(CompatibilityReport report){prepareSave("volt-compat-report.json","application/json",null,report.json(),android.text.TextUtils.join(",",report.included()),null);}
    private void prepareSave(String name,String mime,File source,String text,String reportTests,Runnable copied){
        if(documents.contains("path")){toast(T.mainImportFinishSave());if(copied!=null)copied.run();return;}
        File staging=new File(getFilesDir(),"pending-export-"+System.currentTimeMillis());preparingExports.add(staging.getAbsolutePath());documents.edit().putString("path",staging.getAbsolutePath()).putString("name",name).putString("mime",mime).putString("report_tests",reportTests).putBoolean("ready",false).putBoolean("picker",false).commit();
        new Thread(()->{try(OutputStream out=new FileOutputStream(staging)){if(source==null)out.write(text.getBytes(StandardCharsets.UTF_8));else try(InputStream in=new FileInputStream(source)){copy(in,out);}documents.edit().putBoolean("ready",true).commit();handler.post(this::maybeLaunchSave);}catch(Exception e){staging.delete();documents.edit().clear().commit();handler.post(()->toast(T.mainImportPrepareFailed()));}finally{preparingExports.remove(staging.getAbsolutePath());if(copied!=null)copied.run();}},"Volt-export").start();
    }
    private void maybeLaunchSave(){
        if(!resumed||destroyed||!documents.getBoolean("ready",false)||documents.getBoolean("picker",false))return;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(documents.getString("mime","application/octet-stream"));intent.putExtra(Intent.EXTRA_TITLE,documents.getString("name","navee-export"));documents.edit().putBoolean("picker",true).commit();
        try{startActivityForResult(intent,SAVE_DOCUMENT);}catch(Exception e){documents.edit().putBoolean("picker",false).apply();toast(T.mainImportNoPicker());}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==ShizukuSetupActivity.REQUEST){
            if(result==ShizukuSetupActivity.RESULT_IMPORT_BACKUP&&onboarding!=null){onboarding.showImport();return;}
            OwnerIdentityParser.Result owner=ShizukuCaptureClient.get(this).takeApproved();
            if(result==RESULT_OK&&owner!=null){try{
                if(pendingOwnerFile().exists())throw new IllegalStateException();
                JSONObject setup=new JSONObject();setup.put("owner_identity",owner.identity);setup.put("role",owner.role);setup.put("name",IMPORTED_NAME);
                stageOwnerImport(setup.toString());
                if(onboarding!=null)onboarding.onOwnerImportStaged();
                maybeApplyOwnerImport();
            }catch(Exception ignored){toast(T.mainImportRetrySetup());}}
            return;
        }
        if(request==ENABLE_BLUETOOTH){if(result==RESULT_OK){if(pendingScan){pendingScan=false;showScanner(true);}else if(pendingConnect){pendingConnect=false;connect();}}else pendingConnect=pendingScan=false;return;}
        if(request==SAVE_DOCUMENT){File staging=new File(documents.getString("path",new File(getFilesDir(),"missing-export").getAbsolutePath()));String reportTests=documents.getString("report_tests","");if(result!=RESULT_OK||data==null||data.getData()==null){staging.delete();documents.edit().clear().apply();return;}Uri destination=data.getData();new Thread(()->{try(InputStream in=new FileInputStream(staging);OutputStream out=getContentResolver().openOutputStream(destination,"wt")){if(out==null)throw new java.io.IOException();copy(in,out);handler.post(()->{toast(T.mainImportSaved());if(!reportTests.isEmpty()&&scooterService!=null)scooterService.ble.markReported(java.util.Arrays.asList(reportTests.split(",")));});}catch(Exception e){handler.post(()->toast(T.mainImportSaveFailed()));}finally{staging.delete();documents.edit().clear().commit();}},"Volt-save").start();return;}
        if(request==IMPORT_LAYOUTS&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            Uri uri=data.getData();
            new Thread(()->{try(InputStream in=getContentResolver().openInputStream(uri)){
                if(in==null)throw new java.io.IOException();
                String text=readLimited(in); DashboardConfig.LayoutTransfer info=DashboardConfig.inspectLayouts(text);
                handler.post(()->confirmDashboardImport(text,info));
            }catch(Exception error){handler.post(()->toast(T.mainImportNotLayout()));}},"Volt-layout-import").start();
            return;
        }
        if(request==IMPORT_OWNER&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();pickedOwnerUri=null;try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new java.io.IOException();stageOwnerImport(readLimited(in));pickedOwnerUri=uri;maybeApplyOwnerImport();}catch(Exception e){pickedOwnerUri=null;
            if(onboarding==null&&e instanceof IllegalArgumentException){toast(SetupException.shown((IllegalArgumentException)e));return;}
            // First-run setup shows its own inline "Not an owner file."; elsewhere the parser's reason is a toast.
            if(onboarding==null)toast(e instanceof IllegalArgumentException?e.getMessage():T.mainImportSetupUnreadable());else onboarding.onOwnerImported(false);}}
    }
    /** Only the normalized owner setup is staged, never raw Bluetooth capture logs. */
    private void stageOwnerImport(String text)throws Exception{
        OwnerIdentityParser.Result parsed=OwnerIdentityParser.parse(text);String name=IMPORTED_NAME,mac="",serial="";
        try{JSONObject json=new JSONObject(text);name=json.optString("name",name);mac=json.optString("scooter_address","");serial=json.optString("scooter_serial","");}catch(Exception ignored){}
        name=name.trim();if(name.isEmpty())name=IMPORTED_NAME;if(name.length()>80)name=name.substring(0,80);
        if(!mac.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))mac="";else mac=mac.toUpperCase(Locale.ROOT);if(!serial.matches("[A-Za-z][A-Za-z0-9]{16}"))serial="";
        JSONObject normalized=new JSONObject();normalized.put("format","navee-private-pending-owner-v1");normalized.put("import_id",UUID.randomUUID().toString());normalized.put("owner_identity",parsed.identity);normalized.put("role",parsed.role);normalized.put("name",name);normalized.put("scooter_address",mac);normalized.put("scooter_serial",serial);
        writePendingOwnerImport(normalized);importWaitNotified=false;
    }
    private void writePendingOwnerImport(JSONObject normalized)throws java.io.IOException{
        File temporary=new File(getFilesDir(),"pending-owner-import.tmp");try(FileOutputStream out=new FileOutputStream(temporary)){out.write(normalized.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        if(!temporary.renameTo(pendingOwnerFile())){temporary.delete();throw new java.io.IOException("Could not stage owner setup");}
    }
    /** The document the rider picked for the last owner import, so its Downloads copy can be removed. */
    private Uri pickedOwnerUri;
    /** True while the owner-setup document picked during this session can still be offered for deletion. */
    public boolean hasPickedOwnerFile(){return downloadsDocument(pickedOwnerUri);}
    /** Only a Downloads (or on-device Download folder) document is called a "Downloads copy". */
    private static boolean downloadsDocument(Uri uri){
        if(uri==null)return false;String authority=uri.getAuthority();
        if("com.android.providers.downloads.documents".equals(authority))return true;
        if(!"com.android.externalstorage.documents".equals(authority))return false;
        try{String id=android.provider.DocumentsContract.getDocumentId(uri);int colon=id.indexOf(':');String path=colon<0?id:id.substring(colon+1);
            return path.equals("Download")||path.startsWith("Download/");}catch(RuntimeException invalid){return false;}
    }
    /** Deletes the picked owner-setup document; false when nothing was picked or the provider cannot delete it. */
    public boolean deletePickedOwnerFile(){
        Uri uri=pickedOwnerUri;if(!downloadsDocument(uri))return false;
        try{if(!android.provider.DocumentsContract.deleteDocument(getContentResolver(),uri))return false;pickedOwnerUri=null;return true;}
        catch(Exception unsupported){return false;}
    }
    /** Name given to an imported profile whose setup file carries none. */
    static final String IMPORTED_NAME="Imported scooter";
    private File pendingOwnerFile(){return new File(getFilesDir(),"pending-owner-import.json");}
    private void maybeApplyOwnerImport(){
        File pending=pendingOwnerFile();if(destroyed||!resumed||applyingOwnerImport||!pending.isFile())return;
        if(scooterService==null){if(hasBluetoothPermission())ensureService();return;}
        String unavailable=scooterService.scooterChangeUnavailable();
        if(unavailable!=null){if(!importWaitNotified){toast(unavailable);importWaitNotified=true;}return;}
        applyingOwnerImport=true;
        try(InputStream in=new FileInputStream(pending)){
            String text=readLimited(in);OwnerIdentityParser.Result parsed=OwnerIdentityParser.parse(text);JSONObject json=new JSONObject(text);String importId=json.getString("import_id");
            if(!"navee-private-pending-owner-v1".equals(json.optString("format")))throw new SetupException(M.mainImportPickAgain());
            String newProfileId=ScooterProfiles.ownerImportProfileId(importId),targetId=json.optString("target_profile_id","");
            boolean applied=importId.equals(preferences.getString("owner_import_applied",""));
            String name=json.optString("name",IMPORTED_NAME),mac=json.optString("scooter_address",""),serial=json.optString("scooter_serial","");
            // NAVEE's owner identity belongs to the account, not the physical scooter. An earlier
            // unconnected scooter with the same identity must not absorb a fresh Add a scooter flow.
            ScooterProfiles.Profile match=null;
            for(ScooterProfiles.Profile profile:scooterService.profiles.all()){
                if(profile.id.equals(targetId.isEmpty()?newProfileId:targetId)){match=profile;break;}
            }
            if(match!=null&&!ownerImportCompatible(match,parsed,mac,serial))throw new SetupException(M.mainImportNoLongerMatches());
            if(match==null&&!targetId.isEmpty()&&!targetId.equals(newProfileId))throw new SetupException(M.mainImportRemoved());
            if(match==null&&targetId.isEmpty()){
                for(ScooterProfiles.Profile profile:scooterService.profiles.all()){
                    if(!ownerImportCompatible(profile,parsed,mac,serial))continue;
                    if((!serial.isEmpty()&&serial.equals(profile.serial))||(!mac.isEmpty()&&mac.equalsIgnoreCase(profile.mac))){match=profile;break;}
                }
                if(match==null&&onboarding!=null){
                    ScooterProfiles.Profile earlier=onboarding.ownerImportProfile();
                    if(ownerImportCompatible(earlier,parsed,mac,serial))match=earlier;
                }
            }
            if(applied&&match==null)throw new SetupException(M.mainImportUnavailable());
            if(applied&&onboarding==null){pending.delete();return;}
            // Bind an existing target in the private recovery file before changing it. Newly
            // created targets use the request's deterministic ID from their very first save.
            String selectedId=match==null?newProfileId:match.id;
            if(!selectedId.equals(targetId)){json.put("target_profile_id",selectedId);writePendingOwnerImport(json);}
            // A picker return can trigger automatic reconnect. Cancel that session before
            // selecting a profile, after checking recording and pending-command state.
            scooterService.ble.disconnect();
            // Volt's own fallback stays a default name, so a later model name can replace it.
            if(match==null)match=scooterService.profiles.createForOwnerImport(importId,name,parsed.identity,parsed.role);
            else{scooterService.profiles.select(match.id);scooterService.ble.profileSelected();}
            if(match.id.equals(newProfileId)&&(match.name.trim().isEmpty()||IMPORTED_NAME.equals(match.name.trim())))match.nameDefault=true;
            if(!mac.isEmpty())match.mac=mac;if(!serial.isEmpty())match.serial=serial;scooterService.profiles.update(match);
            // Commit the existing profile snapshot before deleting its recovery staging file.
            SharedPreferences profilePrefs=getSharedPreferences("profiles",MODE_PRIVATE);String savedProfiles=profilePrefs.getString("items","[]");if(!profilePrefs.edit().putString("items",savedProfiles).commit())throw new java.io.IOException();
            if(!preferences.edit().putString("owner_import_applied",importId).commit())throw new java.io.IOException();
            // During first-run setup the flow connects at its own step, after Nearby devices.
            if(onboarding!=null){onboarding.onOwnerImported(true);pending.delete();importWaitNotified=false;return;}
            pending.delete();importWaitNotified=false;
            showPage(HubPages.HOME);scrollPositions.put(HubPages.HOME,0);scrollPage=PAGE_HOME;if(scroll!=null)scroll.scrollTo(0,0);rebuild();toast(M.mainImportImportedOwner());scooterService.ble.connectLast();if(match.mac.isEmpty())showScanner(false);
        }catch(IllegalArgumentException e){pending.delete();if(onboarding==null)toast(SetupException.shown(e));else onboarding.onOwnerImported(false);}
        catch(Exception e){if(!importWaitNotified){toast(T.mainImportPaused());importWaitNotified=true;}}
        finally{applyingOwnerImport=false;}
    }
    /** Missing hardware identifiers are not matches, but known contradictions forbid reuse. */
    private static boolean ownerImportCompatible(ScooterProfiles.Profile profile,OwnerIdentityParser.Result parsed,String mac,String serial){
        return profile!=null&&profile.identity.equalsIgnoreCase(parsed.identity)&&profile.role==parsed.role
                &&(serial.isEmpty()||profile.serial.isEmpty()||serial.equals(profile.serial))
                &&(mac.isEmpty()||profile.mac.isEmpty()||mac.equalsIgnoreCase(profile.mac));
    }
    private static String readLimited(InputStream in)throws java.io.IOException{ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int count;while((count=in.read(chunk))!=-1){if(buffer.size()+count>1048576)throw new SetupException(M.mainImportTooBig());buffer.write(chunk,0,count);}return new String(buffer.toByteArray(),StandardCharsets.UTF_8);}
    private static void copy(InputStream in,OutputStream out)throws java.io.IOException{byte[] buffer=new byte[65536];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);}
}
