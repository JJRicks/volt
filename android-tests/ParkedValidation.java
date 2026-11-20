package com.jjricks.volt.tests;
import android.app.Instrumentation;
import android.app.Activity;
import android.app.NotificationManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.*;
import com.jjricks.volt.*;

/** Explicit ADB-only validation; this runner is a separate APK, never a production component. */
public final class ParkedValidation extends ValidationInstrumentation {
    private Bundle args;private volatile ScooterService service;private final JSONArray results=new JSONArray();private boolean bound;private Boolean originalBackground;
    private Activity launchedActivity;private Map<String,Object> smokePreferences;private boolean smokeMode,reconnectAfterSmoke;
    private static final String[] SMOKE_PREFS={"background_connection","auto_connect","charge_live_enabled","scooter_section","stats_section","stats_query","stats_favourites","stats_favourite_order","stats_favourites_only","more_section"};
    @Override public void onCreate(Bundle args){this.args=args==null?new Bundle():args;start();}
    private final ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){service=((ScooterService.LocalBinder)b).service();}public void onServiceDisconnected(ComponentName n){service=null;}};
    @Override public void onStart(){Bundle output=new Bundle();try{
        String mode=args.getString("mode","snapshot");
        if(args.containsKey("fixture")&&!mode.equals("navigation"))throw new IllegalArgumentException("-e fixture is for navigation mode only");
        if(args.containsKey("fixture"))emulatorFixture();
        // Before Volt's screen or service starts, and before anything is written: refused while automatic recording is on.
        if(getTargetContext().getSharedPreferences("app_settings",Context.MODE_PRIVATE).getBoolean(ChargeAutoRecordPolicy.KEY,false)){
            output.putString("refused","charge_auto_record");throw new IllegalStateException(AUTO_RECORD_ON);}
        smokeMode=mode.equals("navigation")||mode.equals("background");
        if(smokeMode)backupSmokePreferences();
        Activity activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));launchedActivity=activity;
        runMain(()->bound=getTargetContext().bindService(new Intent(getTargetContext(),ScooterService.class),connection,Context.BIND_AUTO_CREATE));
        awaitReadOnly(()->service!=null,8000,"Local service unavailable");
        // Before any mode's own work: a charge recording is never ended by a step (chargeRecordingAtStart).
        runMain(()->chargeAtStart=service.isChargeRecording());if(chargeAtStart)throw new IllegalStateException(CHARGE_RUNNING);
        if(smokeMode){
            runMain(()->{if(service.isRecording())throw new IllegalStateException("Smoke test skipped: a user recording is active; it was not interrupted");if(service.ble.pending()!=null)throw new IllegalStateException("Smoke test skipped: a scooter setting is pending");});
        }else{
            runMain(()->{originalBackground=service.settings.getBoolean("background_connection",false);service.settings.edit().putBoolean("background_connection",true).commit();service.visible(true);if(!service.isRecording())service.ble.connectLast();});waitFor(()->service.ble.ready(),35000,"Scooter connection unavailable");
            runMain(()->service.ble.refresh(true));Thread.sleep(2200);
        }
        if(mode.equals("navigation")){
            output.putString("navigation",navigationSmoke(activity,output));
        }else if(mode.equals("background")){
            backgroundSmoke();output.putString("background","Full 10s grace, cancelled old deadline, fresh second pause, hard cutoff, late hidden start and connecting cutoff checked");
        }else if(mode.equals("bgreset")){
            originalBackground=false;runMain(()->service.settings.edit().putBoolean("background_connection",false).commit());output.putString("background","Restored default background connection off");
        }else if(mode.equals("profiles")){
            Context fixture=new ContextWrapper(getTargetContext()){@Override public SharedPreferences getSharedPreferences(String name,int mode){return getBaseContext().getSharedPreferences("validation_profiles_"+name,mode);}};fixture.getSharedPreferences("profiles",0).edit().clear().commit();SharedPreferences legacy=fixture.getSharedPreferences("scooter",0);legacy.edit().clear().putString("identity","112233AABBCC").putInt("role",0).commit();
            ScooterProfiles profiles=new ScooterProfiles(fixture);if(profiles.active()==null||!profiles.active().configured())throw new IllegalStateException("Legacy profile did not migrate");String id=profiles.active().id;profiles.remove(id);if(new ScooterProfiles(fixture).active()!=null||legacy.contains("identity"))throw new IllegalStateException("Forgotten legacy identity returned");ScooterProfiles.Profile shared=profiles.create("Fixture shared","AABBCCDDEEFF",1);OwnerIdentityParser.Result roundtrip=OwnerIdentityParser.parse(ScooterProfiles.privateBackup(shared));if(roundtrip.role!=1||!roundtrip.identity.equals(shared.identity))throw new IllegalStateException("Private shared-role backup did not round trip");fixture.getSharedPreferences("profiles",0).edit().clear().commit();legacy.edit().clear().commit();output.putString("profiles","Isolated migration, forget and role backup passed; real owner setup untouched");
        }else if(mode.equals("restore")){
            String key=args.getString("feature","");int value=Integer.parseInt(args.getString("value","-1"));ScooterCommands.Feature feature=ScooterCommands.find(key);if(feature.sensitive||feature.requiresBond||feature.type==ScooterCommands.Type.TIME||!feature.allows(value))throw new IllegalArgumentException("Only an understood scalar baseline can be restored");AtomicReference<Boolean> sent=new AtomicReference<>();runMain(()->sent.set(service.ble.set(key,value)));if(!Boolean.TRUE.equals(sent.get()))throw new IllegalStateException(service.ble.message());waitFor(()->service.ble.pending()==null,12000,"Restoration did not settle");if(!Integer.valueOf(value).equals(service.ble.featureValue(key)))throw new IllegalStateException("Restoration was not confirmed");status(key+": requested baseline confirmed");
        }else if(mode.equals("lock")){
            validate(ScooterCommands.find("lock"));
        }else if(mode.equals("pin")){
            runMain(()->service.ble.readPin());waitFor(()->service.ble.pinState()!=null,8000,"PIN state unavailable");ScooterCommands.PinState pin=service.ble.pinState();output.putString("pin_state","Enabled="+pin.enabled+", configured="+pin.configured+", status="+pin.status);
        }else if(mode.equals("clock")){
            runMain(()->service.ble.syncClock());waitFor(()->service.ble.pending()==null,10000,"Clock ACK unavailable");output.putString("clock",service.ble.commandResults().get("clock_sync"));
        }else if(mode.equals("time")){
            require(!planActive(),"A charging schedule is active; the start-time check was not run so the plan is kept");
            Double stored=service.ble.telemetry.number("scheduled_charge_time_raw");if(stored==null||stored<=0)throw new IllegalStateException("No restorable stored charging timestamp");long before=stored.longValue();long now=System.currentTimeMillis();int offset=TimeZone.getDefault().getOffset(now)/1000;long target=now/1000+3600;
            try{runMain(()->service.ble.setChargeStart(target,offset));waitFor(()->service.ble.pending()==null,10000,"Charging time did not settle");if(!Double.valueOf(target+offset).equals(service.ble.telemetry.number("scheduled_charge_time_raw")))throw new IllegalStateException("Charging time did not match");status("charge_start: new timestamp confirmed");}
            finally{waitFor(()->service.ble.ready()&&service.ble.pending()==null,15000,"Cannot restore stored charging time");runMain(()->service.ble.setChargeStart(before-offset,offset));waitFor(()->service.ble.pending()==null,10000,"Charging time restoration did not settle");if(!Double.valueOf(before).equals(service.ble.telemetry.number("scheduled_charge_time_raw")))throw new IllegalStateException("Charging time restoration did not match");status("charge_start: original timestamp restored");}
        }else if(mode.equals("sweep")){
            if(service.isRecording())throw new IllegalStateException("Finish the existing recording before parked control validation");
            String only=args.getString("feature","");boolean lab=Boolean.parseBoolean(args.getString("lab","false"));
            // A test plan's exclusions (speed caps, drive modes) are recorded, never written.
            Set<String> exclude=new HashSet<>(Arrays.asList(args.getString("exclude","").split(",")));
            // Volt reads a ceiling change as a new plan and retires Charge done by; never touch it while a plan is held.
            if(planActive()){exclude.add("charge_limit");exclude.add("timed_charging");}
            for(ScooterCommands.Feature feature:ScooterCommands.catalog()){
                if(!only.isEmpty()&&!only.equals(feature.key))continue;
                if(exclude.contains(feature.key)){JSONObject row=new JSONObject();row.put("key",feature.key);row.put("outcome","Excluded by the test plan; no write");results.put(row);continue;}
                if(lab&&only.isEmpty()&&!feature.experimental)continue;
                if(feature.sensitive||feature.requiresBond||feature.type==ScooterCommands.Type.TIME||feature.key.equals("lock"))continue;
                if(feature.experimental&&!lab)continue;
                validate(feature);
            }
        }else if(mode.equals("schedule")){
            scheduleTest(output);
        }else if(mode.equals("ui")){
            String page=args.getString("page","home");java.lang.reflect.Method select=MainActivity.class.getDeclaredMethod("select",String.class);select.setAccessible(true);
            runMain(()->{try{select.invoke(activity,page);}catch(Exception e){throw new IllegalStateException(e);}});waitForIdleSync();Thread.sleep(1200);
            AtomicReference<Bitmap> rendered=new AtomicReference<>();runMain(()->{((MainActivity)activity).redraw();View view=activity.findViewById(android.R.id.content);int width=view.getWidth(),height=view.getHeight();forceLayout(view);view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));rendered.set(bitmap);});
            File dir=new File(getTargetContext().getExternalFilesDir(null),"validation");dir.mkdirs();try(FileOutputStream file=new FileOutputStream(new File(dir,"ui-"+page+".png"))){rendered.get().compress(Bitmap.CompressFormat.PNG,100,file);}TextCensus.write(this,new File(dir,"ui-"+page+".png"));output.putString("render","Native app view "+rendered.get().getWidth()+"x"+rendered.get().getHeight());java.lang.reflect.Field monitorField=MainActivity.class.getDeclaredField("fold");monitorField.setAccessible(true);FoldMonitor monitor=(FoldMonitor)monitorField.get(activity);output.putString("fold",monitor.source+"; callbacks="+monitor.layoutCallbacks+"; bounds="+monitor.hinge.toShortString());rendered.get().recycle();
        }else if(mode.equals("record")){
            if(service.isRecording())throw new IllegalStateException("A recording is already active");
            runMain(()->{service.startRide("Stationary validation — not a ridden range test");service.settings.edit().putBoolean("background_connection",originalBackground).commit();service.visible(false);});if(!service.isRecording())throw new IllegalStateException("Recording did not start");
            int seconds=Math.min(180,Math.max(10,Integer.parseInt(args.getString("seconds","45"))));
            String id=service.rides.activeId();Thread.sleep(seconds*1000L);runMain(()->service.stopRide());if(service.isRecording())throw new IllegalStateException("Recording could not stop/save");save("stationary-recording.json",service.rides.get(id).toJson());File dir=new File(getTargetContext().getExternalFilesDir(null),"validation");dir.mkdirs();service.rides.exportCsv(id,new File(dir,"stationary-recording.csv"));output.putString("recording","Completed stationary validation");
        }else if(!mode.equals("snapshot"))throw new IllegalArgumentException("Unknown validation mode");
        chargeStarted(output);
        save("latest-diagnostics.json",service.snapshot());JSONObject report=new JSONObject();report.put("format","navee-parked-control-validation-v1");report.put("checked_at_ms",System.currentTimeMillis());report.put("results",results);report.put("warning","Readback validation only. Propulsion, descent, cruise engagement, physical lighting and ridden energy behavior require rider checks.");save("control-validation.json",report.toString(2));
        // The hardware sweep reads this to decide whether a schedule test can run (it needs a charge in progress).
        if(!smokeMode){output.putString("charging_state",String.valueOf(service.ble.telemetry.number("charging_state")));output.putString("schedule_active",String.valueOf(planActive()));}
        output.putString("result","Saved local diagnostics and validation evidence");output.putInt("controls",smokeMode?0:results.length());if(smokeMode)output.putInt("checks",results.length());output.putString("original_background",String.valueOf(smokeMode&&smokePreferences!=null?smokePreferences.getOrDefault("background_connection",false):originalBackground));cleanup();finish(0,output);
    }catch(Throwable e){try{chargeStarted(output);}catch(Exception ignored){}try{save("partial-control-validation.json",results.toString(2));if(service!=null)save("failed-diagnostics.json",service.snapshot());}catch(Exception ignored){}output.putString("error",e.getClass().getSimpleName()+": "+e.getMessage());cleanup();finish(1,output);}finally{cleanup();}}
    /**
     * The step's start already restarted Volt (am instrument replaces its process), and the restarted service's
     * ChargeSessionStore finalizes any running journal as INTERRUPTED, deleting a short automatic one; so the hardware sweep
     * refuses to start a step while Volt's notification shows a charge recording (run-hardware-sweep.py). This is the
     * runner's own backstop: should one still be running when the service binds, the step stops before any mode's work.
     */
    static final String CHARGE_RUNNING="A charge recording is running. Each hardware sweep step ends with Volt restarting, which would end it (a short automatic one is deleted). Stop or finish the recording in Volt, then run the sweep again.";
    private boolean chargeAtStart;
    /**
     * At the end of every mode, passed or failed: a charge recording that started during the step (an automatic one on a
     * charging scooter) ends when Volt restarts for the next step or closes after this one, so the output says so
     * (charge_recording_started=true, which stops the hardware sweep's remaining plan) and the evidence records it.
     */
    private void chargeStarted(Bundle output)throws Exception{
        if(service==null||chargeAtStart)return;
        AtomicReference<Boolean> running=new AtomicReference<>(false);runMain(()->running.set(service!=null&&service.isChargeRecording()));
        if(!running.get())return;
        output.putString("charge_recording_started","true");
        status("A charge recording started during this step; it ends when Volt restarts or closes after this step. Stop it in Volt (or let it finish) before running the sweep again.");
        JSONObject row=new JSONObject();row.put("charge_recording_started",true);row.put("note","Started during this step; it ends when Volt restarts or closes after the step");results.put(row);
    }
    /**
     * A step never runs while Record charges automatically (ChargeAutoRecordPolicy.KEY) is on: the step's own connection
     * would start an automatic recording on a charging scooter, and the step's end (Android closing Volt's process) or the
     * next step's start would cut it short, deleting a short one. Checked before Volt's screen or service starts and before
     * any write; the owner turns it off for the sweep and back on afterwards (run-hardware-sweep.py stops the plan).
     */
    static final String AUTO_RECORD_ON="Record charges automatically is on. Each hardware sweep step restarts Volt, which would end an automatic charge recording (a short one is deleted). Turn it off in Volt for the sweep and back on afterwards.";
    private void cleanup(){if(smokeMode&&smokePreferences!=null)restoreSmokePreferences();if(service!=null&&originalBackground!=null){boolean previous=originalBackground;runMain(()->{service.settings.edit().putBoolean("background_connection",previous).commit();if(!getTargetContext().getSystemService(PowerManager.class).isInteractive())service.visible(false);});originalBackground=null;}if(bound){runMain(()->getTargetContext().unbindService(connection));bound=false;}}
    private void backupSmokePreferences(){
        smokePreferences=new LinkedHashMap<>();Map<String,?> all=getTargetContext().getSharedPreferences("app_settings",Context.MODE_PRIVATE).getAll();
        for(String key:SMOKE_PREFS)if(all.containsKey(key)){Object value=all.get(key);smokePreferences.put(key,value instanceof Set?new LinkedHashSet<>((Set<?>)value):value);}
    }
    @SuppressWarnings("unchecked") private void restoreSmokePreferences(){
        final Map<String,Object> saved=smokePreferences;
        runMain(()->{
            SharedPreferences.Editor editor=getTargetContext().getSharedPreferences("app_settings",Context.MODE_PRIVATE).edit();
            for(String key:SMOKE_PREFS){editor.remove(key);Object value=saved.get(key);if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);else if(value instanceof Integer)editor.putInt(key,(Integer)value);else if(value instanceof Long)editor.putLong(key,(Long)value);else if(value instanceof Float)editor.putFloat(key,(Float)value);else if(value instanceof String)editor.putString(key,(String)value);else if(value instanceof Set)editor.putStringSet(key,new LinkedHashSet<>((Set<String>)value));}
            if(!editor.commit())throw new IllegalStateException("Could not restore smoke-test preferences");
            if(launchedActivity!=null&&!launchedActivity.isFinishing())selectOnMain(launchedActivity,"home");
            if(service!=null){boolean interactive=getTargetContext().getSystemService(PowerManager.class).isInteractive();service.visible(interactive);if(interactive&&reconnectAfterSmoke&&!service.isRecording()&&service.ble.state()==BleController.State.DISCONNECTED)service.ble.connectLast();}
        });smokePreferences=null;reconnectAfterSmoke=false;
    }
    private Object field(Object object,String name){
        for(Class<?> type=object.getClass();type!=null;type=type.getSuperclass())try{java.lang.reflect.Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(NoSuchFieldException ignored){}catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot inspect "+name,e);}
        throw new IllegalStateException("Missing validation field: "+name);
    }
    private void selectOnMain(Activity activity,String page){
        try{java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod("select",String.class);method.setAccessible(true);method.invoke(activity,page);}
        catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot select "+page,e);}
    }
    private Object currentScreen(Activity activity){return field(activity,"screen");}
    private View screenView(Activity activity){return ((AppScreen)currentScreen(activity)).view();}
    private View findText(View view,String text){
        if(!view.isShown())return null;
        if(view instanceof TextView&&text.equals(((TextView)view).getText().toString()))return view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findText(group.getChildAt(i),text);if(found!=null)return found;}}
        return null;
    }
    private EditText findInput(View view,String description){
        if(!view.isShown())return null;
        if(view instanceof EditText&&description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return (EditText)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){EditText found=findInput(group.getChildAt(i),description);if(found!=null)return found;}}
        return null;
    }
    private Object call(Object target,String name,Class<?>[] types,Object... values){
        for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass())try{java.lang.reflect.Method m=type.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,values);}catch(NoSuchMethodException ignored){}catch(ReflectiveOperationException e){throw new IllegalStateException("Cannot call "+name,e);}
        throw new IllegalStateException("Missing validation method: "+name);
    }
    /** Section changes animate, and a Visibility transition keeps the leaving section VISIBLE until it ends; retry the check until it holds. */
    private void eventually(Runnable check)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+3000;
        for(;;){try{runMain(check);return;}catch(RuntimeException e){if(SystemClock.elapsedRealtime()>deadline)throw e;}Thread.sleep(50);}
    }
    /** As {@link #eventually(Runnable)}, for up to timeoutMs: a page whose rows follow a connection that is still settling. */
    private void eventually(long timeoutMs,Runnable check)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+timeoutMs;
        for(;;){try{runMain(check);return;}catch(RuntimeException e){if(SystemClock.elapsedRealtime()>deadline)throw e;}Thread.sleep(50);}
    }
    /** Data › a section, through its section selector (tag live-stats-sections) as a tap would, never by matching tab text. */
    @SuppressWarnings("unchecked") private void clickStatsSection(Activity activity,String section)throws Exception{
        runMain(()->{Object stats=currentScreen(activity);View selector=(View)field(stats,"sectionTabs");require("live-stats-sections".equals(selector.getTag())&&selector.isShown(),"Data section selector missing");
            List<String> order=Arrays.asList((String[])field(stats,statsWide(stats)?"WIDE_SECTIONS":"SECTIONS"));List<TextView> choices=(List<TextView>)field(field(selector,"track"),"choices");
            int index=order.indexOf(section);require(index>=0&&choices.size()==order.size(),"Data selector does not offer "+section);View choice=choices.get(index);require(choice.isShown()&&choice.isClickable(),"Data selector choice not reachable: "+section);choice.performClick();});
        waitForIdleSync();
        eventually(()->{Object stats=currentScreen(activity);require(section.equals(field(stats,"selectedSection")),"Data selector did not select "+section);assertStatsSections(stats,section);});
    }
    private void require(boolean condition,String failure){if(!condition)throw new IllegalStateException(failure);}
    private void assertNoUserRecording(){require(service!=null&&!service.isRecording(),"Smoke test stopped: a user recording is active; no stop/disconnect was requested");require(service.ble.pending()==null,"Smoke test stopped: a scooter change is pending");}
    /** Wide Data keeps Readings in its own right-hand pane, so it has no Readings tab and that section is always shown. */
    private boolean statsWide(Object screen){return Boolean.TRUE.equals(field(screen,"wide"));}
    private void assertStatsSections(Object screen,String selected){
        String[] names={"Overview","Readings","Charts","Diagnostics"};String[] fields={"overviewSection","readingsSection","chartsSection","diagnosticsSection"};boolean wide=statsWide(screen);
        for(int i=0;i<names.length;i++){boolean shown=names[i].equals(selected)||wide&&names[i].equals("Readings");require(((View)field(screen,fields[i])).getVisibility()==(shown?View.VISIBLE:View.GONE),"Unexpected Data section visibility: "+names[i]+" while "+selected+" selected");}
    }
    private long mutations(){return ((Number)field(service.ble,"mutationSequence")).longValue();}

    // ===== The navigation step: the Hub's pages, read only =====

    /** Settings' rows that open its pages, by their tags (MoreScreen), as HubValidation taps them. */
    private static final Map<String,String> SETTINGS_ROWS=new LinkedHashMap<>();
    static{
        SETTINGS_ROWS.put(HubPages.SETTINGS_HOME,"home-style-menu");SETTINGS_ROWS.put(HubPages.SETTINGS_UNITS,"preference-units-menu");
        SETTINGS_ROWS.put(HubPages.SETTINGS_SCOOTERS,"my-scooters-menu");SETTINGS_ROWS.put(HubPages.SETTINGS_ABOUT,"about-menu");
        SETTINGS_ROWS.put(HubPages.SETTINGS_DEVELOPER,"developer-options-menu");
    }
    /** The search result the walk opens: start_speed's control result (HubSearchScreen.tag: "hub-search-" kind "-" anchor). */
    private static final String KICKSTART_RESULT="hub-search-setting-start_speed";
    /** The pages below page in the Hub's tree (HubPages), in HubPages' order. */
    private static List<String> children(String page){List<String> found=new ArrayList<>();for(String id:HubPages.ALL)if(page.equals(HubPages.parent(id)))found.add(id);return found;}
    /**
     * MainActivity's own rule, by name, as its scooter pages and Hub search apply it: rowOffered, offered, labOffered and
     * pageShown take a control key or a page id; topSpeedCard, scheduleTestOffered, chargeDoneOffered and chargeAlertsOffered
     * take none. Main thread.
     */
    private boolean rule(Activity activity,String name,String... argument){
        return Boolean.TRUE.equals(argument.length==0?call(activity,name,new Class<?>[0]):call(activity,name,new Class<?>[]{String.class},argument[0]));
    }
    /**
     * Waits for page to be on top with no rebuild pending and its arrival motion over (a page slides in as it fades in:
     * HubTestPages.awaitAtRest on its back link, as HubValidation's arrived does), then requires no recording or pending
     * change. Instrumentation thread.
     */
    private void arrive(MainActivity activity,String page)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+8000;AtomicReference<String> shown=new AtomicReference<>();
        for(;;){AtomicReference<Boolean> there=new AtomicReference<>();runMain(()->{shown.set(HubTestPages.current(activity));there.set(page.equals(shown.get())&&!Boolean.TRUE.equals(field(activity,"rebuilding")));});
            if(Boolean.TRUE.equals(there.get()))break;if(SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException("Expected page "+page+", "+shown.get()+" is on top");Thread.sleep(50);}
        waitForIdleSync();
        if(!HubPages.HOME.equals(page))HubTestPages.awaitAtRest(this,()->{View root=(View)field(activity,"root");return Collections.singletonList(root==null?null:root.findViewWithTag("hub-back"));});
        runMain(this::assertNoUserRecording);
    }
    /** Home must be free for a rider's taps: no What's new sheet over it (the walk never closes the owner's one-time sheet). */
    private void requireHomeFree(MainActivity activity){
        Object sheet=field(activity,"whatsNewSheet");
        require(!(sheet instanceof android.app.Dialog&&((android.app.Dialog)sheet).isShowing()),"What's new is open over Home: close it in Volt, then run the navigation step again");
        require(activity.hasWindowFocus(),"Volt's window has no focus (a dialog or the keyboard guard is over it); keep the phone unlocked with Volt in front");
    }
    private void hideKeyboard(MainActivity activity)throws Exception{
        runMain(()->{View focus=activity.getCurrentFocus();android.view.inputmethod.InputMethodManager input=(android.view.inputmethod.InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if(focus!=null&&input!=null){input.hideSoftInputFromWindow(focus.getWindowToken(),0);focus.clearFocus();}});
        if(Build.VERSION.SDK_INT>=30)awaitReadOnly(()->{android.view.WindowInsets insets=activity.getWindow().getDecorView().getRootWindowInsets();return insets==null||!insets.isVisible(android.view.WindowInsets.Type.ime());},5000,"The keyboard stayed shown after hiding it");
        waitForIdleSync();
    }
    /**
     * A Scooter page with rows (ControlsPages): each control the page lists is placed on it and shown exactly when the
     * screen's own rule offers it on this scooter (MainActivity.rowOffered), and its Experimental entry exactly when the
     * Lab tries it instead (labOffered); no control or hand-built row another page lists is placed here; each hand-built
     * row the page lists shows behind its own rule (the clock sync, the schedule test, Charge done by and the battery
     * alerts by model, the rest always); the UT5 MAX top speed card only where the page leads with it (topSpeedCard); a
     * link to a sub-page only while that page has something to show (pageShown). Retried while a connection settles the
     * rows. Returns the keys of the controls shown, "lab:" and the key for an Experimental entry, "top_speed_card" for the card.
     */
    @SuppressWarnings("unchecked") private Set<String> assertControlsPage(MainActivity activity,String page)throws Exception{
        Set<String> shown=new LinkedHashSet<>();
        eventually(8000,()->{shown.clear();
            Object controls=HubTestPages.part(activity,"ControlsScreen");
            for(Object row:(List<Object>)field(controls,"rows")){
                String key=((ScooterCommands.Feature)field(row,"feature")).key;View card=(View)field(row,"card");
                if(!HubTestPages.pageLists(page,key)){require(!HubTestPages.shownOnPage(activity,card),"Control row "+key+" shows on "+page+", but ControlsPages puts it on "+HubTestPages.pageOf(key));continue;}
                boolean offered=rule(activity,"rowOffered",key);HubTestPages.requireOnPage(activity,card,"the "+key+" row");
                require(card.isShown()==offered,"Control row "+key+" on "+page+(offered?" is hidden, but this scooter offers it":" shows, but this scooter does not offer it"));
                if(offered)shown.add(key);
            }
            for(Map.Entry<String,View> e:((Map<String,View>)field(controls,"labEntries")).entrySet()){
                boolean lab=rule(activity,"labOffered",e.getKey());
                require(e.getValue().isShown()==lab,"The Experimental entry for "+e.getKey()+" on "+page+(lab?" is hidden, but the Lab tries it on this scooter":" shows, but the Lab does not try it here"));
                if(lab)shown.add("lab:"+e.getKey());
            }
            View clock=(View)field(field(controls,"clockLine"),"card"),test=(View)field(field(field(controls,"scheduleTest"),"row"),"card");
            for(View card:((Map<View,String[]>)field(controls,"extras")).keySet()){
                String id=(String)call(controls,"idOf",new Class<?>[]{View.class},card);
                if(!HubTestPages.pageLists(page,id)){require(!HubTestPages.shownOnPage(activity,card),"Row "+id+" shows on "+page+", but ControlsPages puts it on "+HubTestPages.pageOf(id));continue;}
                Object tag=card.getTag();
                boolean expected=card==clock?rule(activity,"offered","clock_sync"):card==test?rule(activity,"scheduleTestOffered")
                        :"charge-done-by".equals(tag)?rule(activity,"chargeDoneOffered"):"charge-alert".equals(tag)?rule(activity,"chargeAlertsOffered"):true;
                HubTestPages.requireOnPage(activity,card,"the "+id+" row");
                require(card.isShown()==expected,"Row "+id+" on "+page+(expected?" is hidden, but this scooter offers it":" shows, but this scooter does not offer it"));
            }
            View top=(View)field(field(controls,"topSpeed"),"card");boolean card=HubTestPages.pageHasTopSpeed(page)&&rule(activity,"topSpeedCard");
            require(HubTestPages.shownOnPage(activity,top)==card,card?"The top speed card is missing from "+page:"The top speed card shows on "+page);
            if(card)shown.add("top_speed_card");
            for(Map.Entry<View,String> e:((Map<View,String>)field(controls,"links")).entrySet()){
                boolean link=rule(activity,"pageShown",e.getValue());
                require(e.getKey().isShown()==link,"The link to "+e.getValue()+" on "+page+(link?" is hidden, but that page has rows for this scooter":" shows, but that page has nothing for this scooter"));
            }
            // The read-only headlight row (switched by the power button) leads Lights & sound with its Power button value.
            if(HubPages.LIGHTS.equals(page))require(findText((View)field(controls,"headlightRow"),T.mainRowHeadlightValue())!=null,"Lights & sound's headlight row lost its Power button value");
        });
        return shown;
    }
    /**
     * Data (Live Stats before the Hub): every section through its selector (wide Data keeps Readings in its own pane), a
     * Readings search for voltage with its favourite star tapped twice (back as it was; the favourites and their order
     * are in SMOKE_PREFS), the search cleared, back on Overview. Returns {wide, tabs, sections seen}.
     */
    @SuppressWarnings("unchecked") private Object[] dataWalk(MainActivity activity)throws Exception{
        AtomicReference<Boolean> wide=new AtomicReference<>();AtomicReference<Integer> tabs=new AtomicReference<>();
        runMain(()->{Object stats=currentScreen(activity);require(stats.getClass().getSimpleName().equals("StatsScreen"),"Data is not a StatsScreen ("+stats.getClass().getSimpleName()+")");
            wide.set(statsWide(stats));List<TextView> choices=(List<TextView>)field(field(field(stats,"sectionTabs"),"track"),"choices");tabs.set(choices.size());
            if(wide.get())for(TextView choice:choices)require(!T.statsTabReadings().contentEquals(choice.getText()),"Wide Data must not offer a Readings tab beside the Readings pane");});
        Set<String> seen=new LinkedHashSet<>();
        for(String section:new String[]{"Overview","Readings","Charts","Diagnostics"}){if(wide.get()&&section.equals("Readings"))continue;clickStatsSection(activity,section);seen.add(section);if(wide.get())seen.add("Readings");}
        if(!wide.get())clickStatsSection(activity,"Readings");
        runMain(()->{EditText input=findInput(screenView(activity),T.statsReadingsSearchDescription());require(input!=null,"Data search missing");input.setText("voltage");
            Map<?,?> rows=(Map<?,?>)field(currentScreen(activity),"rows");Object voltage=rows.get("voltage_v");require(voltage!=null,"Voltage favourite row missing");require(((View)field(voltage,"layout")).isShown(),"Voltage search result missing");View star=(View)field(voltage,"star");require(star.isShown(),"Voltage favourite is not reachable");star.performClick();star.performClick();});
        render(activity,"navigation-data-readings.png");runMain(()->{EditText input=findInput(screenView(activity),T.statsReadingsSearchDescription());input.setText("");require(input.getText().length()==0&&"".equals(field(currentScreen(activity),"query")),"Clearing Data search did not clear its input/query");});clickStatsSection(activity,"Overview");render(activity,"navigation-data-overview.png");
        return new Object[]{wide.get(),tabs.get(),seen.size()};
    }
    /**
     * Search, from Home's way in: "kickstart" lists start_speed's control where this scooter offers it as a row or the Lab
     * tries it (HubSearch.controls: gates.row or gates.lab), and not otherwise; its tap opens the page ControlsPages puts
     * it on, over Search (MainActivity.openSearchResult: Target.PAGE with the control as its anchor, an Experimental entry
     * included, since start_speed has a page), with the row shown there, or its Experimental entry where the Lab tries it
     * instead; that page's back link returns to Search with the query kept; "no-match" shows only the no-match line;
     * Cancel returns to Home. Returns the page the kickstart result opened (with " (Experimental)" for the Lab's entry),
     * or "not offered".
     */
    private String searchWalk(MainActivity activity)throws Exception{
        HubTestPages.reach(this,activity,HubPages.SEARCH);arrive(activity,HubPages.SEARCH);
        String holder=HubTestPages.pageOf("start_speed");require(holder!=null,"ControlsPages puts start_speed on no page");
        AtomicReference<Boolean> offered=new AtomicReference<>(),lab=new AtomicReference<>();
        runMain(()->((EditText)HubTestPages.shownTag(activity,"hub-search-field")).setText("kickstart"));
        eventually(8000,()->{lab.set(!rule(activity,"rowOffered","start_speed")&&rule(activity,"labOffered","start_speed"));offered.set(rule(activity,"rowOffered","start_speed")||lab.get());View result=screenView(activity).findViewWithTag(KICKSTART_RESULT);
            require(offered.get()?result!=null&&HubTestPages.shownOnPage(activity,result):result==null||!result.isShown(),"Search for \"kickstart\" "+(offered.get()?"does not list":"lists")+" start_speed's control, which this scooter "+(offered.get()?lab.get()?"tries in the Lab":"offers":"neither offers nor tries"));});
        hideKeyboard(activity);render(activity,"navigation-search.png");
        if(offered.get()){
            View[] result={null};runMain(()->result[0]=HubTestPages.shownTag(activity,KICKSTART_RESULT));
            HubTestPages.tapView(this,activity,result[0],"the kickstart result");arrive(activity,holder);
            eventually(8000,()->{
                if(lab.get())HubTestPages.requireShown(activity,(View)((Map<?,?>)field(HubTestPages.part(activity,"ControlsScreen"),"labEntries")).get("start_speed"),"start_speed's Experimental entry");
                else HubTestPages.shownControlRow(activity,"start_speed");
                List<?> stack=(List<?>)call(field(activity,"nav"),"pages",new Class<?>[0]);
                require(Arrays.asList(HubPages.HOME,HubPages.SEARCH,holder).equals(stack),"The kickstart result opened the stack "+stack+", not "+holder+" over Search");});
            HubTestPages.back(this,activity);arrive(activity,HubPages.SEARCH);
            eventually(()->require("kickstart".contentEquals(((EditText)HubTestPages.shownTag(activity,"hub-search-field")).getText())&&HubTestPages.shownOnPage(activity,screenView(activity).findViewWithTag(KICKSTART_RESULT)),"Back from the kickstart result lost Search's query or its results"));
        }
        runMain(()->((EditText)HubTestPages.shownTag(activity,"hub-search-field")).setText("no-match"));
        eventually(()->{TextView count=(TextView)HubTestPages.shownTag(activity,"hub-search-count");require(HubText.searchNone().render().contentEquals(count.getText()),"No-match explanation missing (\""+count.getText()+"\")");
            require(((ViewGroup)screenView(activity).findViewWithTag("hub-search-results")).getChildCount()==0,"No-match query left a result listed");});
        runMain(()->((EditText)HubTestPages.shownTag(activity,"hub-search-field")).setText(""));hideKeyboard(activity);
        View[] cancel={null};runMain(()->cancel[0]=HubTestPages.shownTag(activity,"hub-search-cancel"));
        HubTestPages.tapView(this,activity,cancel[0],"Search's Cancel");arrive(activity,HubPages.HOME);
        return offered.get()?holder+(lab.get()?" (Experimental)":""):"not offered";
    }
    /**
     * Every page of the Hub, read only, the way a rider reaches it (HubTestPages: by touch from Home, its subject rows or
     * Widgets' dock, each sub-page from its parent's row, back by the page's back link): Home; Rides' first open, timed;
     * each of the seven subjects and every sub-page whose row this scooter shows (a Scooter sub-page's link shows only
     * while it has rows here, and must be hidden otherwise; every other sub-page's row must show); on each Scooter page with
     * rows its controls (assertControlsPage); Data's sections, search and a favourite; Search; Settings and its pages.
     * Returns the summary line and records what it saw.
     */
    private String navigationSmoke(Activity launched,Bundle output)throws Exception{
        MainActivity activity=(MainActivity)launched;
        if("simulator".equals(args.getString("fixture",""))){runMain(()->require(service.setSimulation(true),"Simulation did not start"));awaitReadOnly(()->service.ble.ready(),15000,"The simulated scooter did not connect");
            String model=args.getString("sim_model","");if(!model.isEmpty()){runMain(()->service.ble.setSimulationModel(model));awaitReadOnly(()->service.ble.ready()&&model.equals(service.ble.simulationModel()),15000,"The simulated "+model+" did not connect");}}
        if("true".equals(args.getString("charge_journal",""))){require(args.containsKey("fixture")&&!"simulator".equals(args.getString("fixture","")),"-e charge_journal is for -e fixture saved only");seedChargeJournal();}
        AtomicReference<Long> initial=new AtomicReference<>();AtomicReference<String> connection=new AtomicReference<>(),style=new AtomicReference<>();
        runMain(()->{assertNoUserRecording();initial.set(mutations());connection.set(service.ble.state().name());service.settings.edit().putString("scooter_section","").putString("stats_query","").putBoolean("stats_favourites_only",false).commit();});
        runMain(()->HubTestPages.open(activity,HubPages.HOME));arrive(activity,HubPages.HOME);
        runMain(()->{requireHomeFree(activity);style.set(currentScreen(activity).getClass().getSimpleName());});
        render(activity,"navigation-home.png");status("Read-only navigation: Home ("+style.get()+")");
        JSONObject rides=timeRidesOpen(activity);output.putString("rides_first_open",rides.toString());status("Read-only navigation: rides "+rides);
        runMain(()->HubTestPages.open(activity,HubPages.HOME));arrive(activity,HubPages.HOME);
        List<String> visited=new ArrayList<>(),hidden=new ArrayList<>();Map<String,Integer> controlsShown=new LinkedHashMap<>();Map<String,JSONArray> controlKeys=new LinkedHashMap<>();Object[] data=null;
        for(String subject:HubPages.SUBJECTS){
            HubTestPages.reach(this,activity,subject);arrive(activity,subject);visited.add(subject);
            if(scooterRowPage(subject))record(subject,assertControlsPage(activity,subject),controlsShown,controlKeys);
            if(HubPages.CONTROLS.equals(subject))render(activity,"navigation-controls.png");
            if(HubPages.LIGHTS.equals(subject))render(activity,"navigation-lights.png");
            if(HubPages.DATA.equals(subject))data=dataWalk(activity);
            status("Read-only navigation: "+subject);
            for(String child:children(subject)){
                AtomicReference<Boolean> expected=new AtomicReference<>();
                // A Scooter sub-page's link shows only while that page has rows for this scooter; every other row always shows.
                eventually(8000,()->{expected.set(rule(activity,"pageShown",child));boolean row=HubTestPages.rowShown(activity,child);
                    require(row==expected.get(),subject+(row?" shows a row to "+child+", which has nothing for this scooter":" shows no row to "+child));});
                if(!expected.get()){hidden.add(child);continue;}
                HubTestPages.tapRow(this,activity,child);arrive(activity,child);visited.add(child);
                if(scooterRowPage(child))record(child,assertControlsPage(activity,child),controlsShown,controlKeys);
                status("Read-only navigation: "+child);
                HubTestPages.back(this,activity);arrive(activity,subject);
            }
            HubTestPages.back(this,activity);arrive(activity,HubPages.HOME);
        }
        // Every Scooter page with rows was checked, or its link was hidden because it has nothing for this scooter.
        for(String page:HubTestPages.scooterRowPages())require(controlsShown.containsKey(page)||hidden.contains(page),"The walk never checked the Scooter page "+page);
        String kickstart=searchWalk(activity);visited.add(HubPages.SEARCH);status("Read-only navigation: search (kickstart opened "+kickstart+")");
        openSettings(activity);visited.add(HubPages.SETTINGS);render(activity,"navigation-settings.png");
        for(String child:children(HubPages.SETTINGS)){
            String tag=SETTINGS_ROWS.get(child);require(tag!=null,"ParkedValidation names no Settings row for "+child+" (SETTINGS_ROWS)");
            View[] row={null};eventually(()->row[0]=HubTestPages.shownTag(activity,tag));
            HubTestPages.tapView(this,activity,row[0],"Settings' row "+tag);arrive(activity,child);visited.add(child);status("Read-only navigation: "+child);
            HubTestPages.back(this,activity);arrive(activity,HubPages.SETTINGS);
        }
        HubTestPages.back(this,activity);arrive(activity,HubPages.HOME);
        runMain(()->require(mutations()==initial.get(),"Navigation/search/favourite actions submitted a scooter mutation"));
        String sliderPage=offlineSliderCancel(activity);
        AtomicReference<Boolean> chargeKept=new AtomicReference<>();runMain(()->chargeKept.set(service.isChargeRecording()));
        runMain(()->require(mutations()==initial.get(),"Slider cancellation submitted a scooter mutation"));
        runMain(()->HubTestPages.open(activity,HubPages.HOME));arrive(activity,HubPages.HOME);
        int controls=0;for(int count:controlsShown.values())controls+=count;
        JSONObject evidence=new JSONObject();evidence.put("mode","navigation");evidence.put("layout","hub");evidence.put("home",style.get());evidence.put("connection_at_start",connection.get());
        evidence.put("pages_visited",visited.size());evidence.put("page_ids",new JSONArray(visited));evidence.put("pages_hidden_for_this_scooter",new JSONArray(hidden));
        evidence.put("scooter_pages_checked",controlsShown.size());evidence.put("controls_shown_by_page",new JSONObject((Map<?,?>)controlsShown));evidence.put("controls_shown",new JSONObject((Map<?,?>)controlKeys));
        evidence.put("data_wide",data[0]);evidence.put("data_tabs",data[1]);evidence.put("data_sections",data[2]);evidence.put("search_kickstart_opened",kickstart);evidence.put("search_no_match",true);
        evidence.put("scooter_mutations",0);evidence.put("slider_cancel",sliderPage);evidence.put("charge_recording_active_at_end",chargeKept.get());evidence.put("rides_first_open",rides);
        if(args.containsKey("fixture"))evidence.put("fixture",args.getString("fixture"));
        results.put(evidence);
        return "Hub walk: Home ("+style.get()+"), "+visited.size()+" pages ("+hidden.size()+" sub-pages hidden for this scooter), "+controls+" controls and Experimental entries on their "+controlsShown.size()+" Scooter pages, "
                +data[2]+" Data sections ("+data[1]+" tabs), kickstart search ("+kickstart+") and no-match, optional favourite, Settings' "+children(HubPages.SETTINGS).size()+" pages; no scooter mutations";
    }
    private static void record(String page,Set<String> shown,Map<String,Integer> counts,Map<String,JSONArray> keys){counts.put(page,shown.size());keys.put(page,new JSONArray(shown));}
    /**
     * Settings from Home, by touch: HubTestPages' way in (Clean Home's gear, Widgets' dock and its All sheet), or, on the
     * wide Clean Home (two panes, HubHomePane in the page pane), the gear in the scooter pane's header, which is outside
     * the page pane HubTestPages looks in: tapped where it rests, with a real touch at its centre, as HubValidation's tapTag does.
     */
    private void openSettings(MainActivity activity)throws Exception{
        runMain(()->HubTestPages.open(activity,HubPages.HOME));arrive(activity,HubPages.HOME);
        AtomicReference<Boolean> panes=new AtomicReference<>();runMain(()->panes.set(currentScreen(activity).getClass().getSimpleName().equals("HubHomePane")));
        if(!panes.get()){HubTestPages.reach(this,activity,HubPages.SETTINGS);arrive(activity,HubPages.SETTINGS);return;}
        View[] gear={null};runMain(()->{View root=(View)field(activity,"root");gear[0]=root==null?null:root.findViewWithTag("home-settings");require(gear[0]!=null&&gear[0].isShown()&&gear[0].isEnabled(),"The wide Home's scooter pane shows no Settings gear");});
        require(HubTestPages.awaitAtRest(this,()->Collections.singletonList(gear[0])),"The wide Home's Settings gear did not come to rest");
        int[] centre=new int[2];runMain(()->{requireHomeFree(activity);int[] at=new int[2];gear[0].getLocationOnScreen(at);centre[0]=at[0]+gear[0].getWidth()/2;centre[1]=at[1]+gear[0].getHeight()/2;
            android.graphics.Rect visible=new android.graphics.Rect();require(gear[0].getGlobalVisibleRect(visible)&&visible.width()>=gear[0].getWidth()-1&&visible.height()>=gear[0].getHeight()-1,"The wide Home's Settings gear is not fully on screen");});
        long when=SystemClock.uptimeMillis();android.view.MotionEvent down=android.view.MotionEvent.obtain(when,when,android.view.MotionEvent.ACTION_DOWN,centre[0],centre[1],0),up=android.view.MotionEvent.obtain(when,when+80,android.view.MotionEvent.ACTION_UP,centre[0],centre[1],0);
        try{sendPointerSync(down);sendPointerSync(up);}finally{down.recycle();up.recycle();}
        waitForIdleSync();arrive(activity,HubPages.SETTINGS);
    }
    /** Whether ControlsPages gives page rows of its own (a Scooter page with rows, not the search page). */
    private static boolean scooterRowPage(String page){return HubTestPages.scooterRowPages().contains(page);}
    /**
     * Rides' first open, timed read-only from the tab tap (MainActivity.select, as the tab bar calls it) to the first frame,
     * and to the first frame drawn once the history rows are built, with the longest main-thread message in that time.
     * Only timings, the saved-ride count and message class names are recorded; no ride is read or copied.
     */
    private static final long RIDES_WAIT_MS=120_000;
    /** Main thread: whether Rides is showing with its history rows built (the list or its empty card, not skeleton rows). */
    private boolean historyBuilt(Activity activity){Object screen=field(activity,"screen");return screen!=null&&screen.getClass().getSimpleName().equals("RidesScreen")&&!((String)field(screen,"historySignature")).isEmpty();}
    private JSONObject timeRidesOpen(Activity activity)throws Exception{
        List<long[]> messages=new ArrayList<>();List<String> labels=new ArrayList<>();
        long[] opened=new long[1];String[] dispatching=new String[1];
        android.util.Printer printer=line->{long now=SystemClock.uptimeMillis();
            if(line.startsWith(">>>>> Dispatching to ")){opened[0]=now;dispatching[0]=line.substring(21);}
            else if(line.startsWith("<<<<< Finished")&&dispatching[0]!=null){synchronized(messages){messages.add(new long[]{opened[0],now});labels.add(dispatching[0]);}dispatching[0]=null;}};
        // Set on main at the tap; the listener runs on main at draw time and asks then whether this frame has the rows.
        AtomicReference<RidesOpenTiming> clock=new AtomicReference<>();
        ViewTreeObserver.OnDrawListener drawn=()->{RidesOpenTiming open=clock.get();if(open!=null)open.onDraw(SystemClock.uptimeMillis(),historyBuilt(activity));};View decor=activity.getWindow().getDecorView();
        AtomicReference<Boolean> cached=new AtomicReference<>();long[] tap=new long[1];boolean rows;JSONObject timing=new JSONObject();
        try{
            // The logger is on before the tap's own message, so the tab switch itself is measured.
            runMain(()->{assertNoUserRecording();cached.set(service.rides!=null&&service.rides.listCached()!=null);decor.getViewTreeObserver().addOnDrawListener(drawn);Looper.getMainLooper().setMessageLogging(printer);});
            runMain(()->{tap[0]=SystemClock.uptimeMillis();clock.set(new RidesOpenTiming(tap[0]));selectOnMain(activity,"rides");});
            // A cold first read of long rides runs on a worker behind skeleton rows; on a busy emulator it has taken 50 s.
            // A slower read is recorded, not failed: this step checks navigation, and the timing is evidence.
            rows=clock.get().awaitRows(RIDES_WAIT_MS);
            SystemClock.sleep(Math.max(500,tap[0]+2500-SystemClock.uptimeMillis()));
        }finally{runMain(()->{Looper.getMainLooper().setMessageLogging(null);if(decor.getViewTreeObserver().isAlive())decor.getViewTreeObserver().removeOnDrawListener(drawn);});}
        long end=SystemClock.uptimeMillis(),longest=-1;String longestLabel="";Long firstFrame=clock.get().tapToFirstFrame(),rowsFrame=clock.get().tapToRows();
        synchronized(messages){for(int i=0;i<messages.size();i++){long[] m=messages.get(i);if(m[0]>=tap[0]-1&&m[0]<end&&m[1]-m[0]>longest){longest=m[1]-m[0];longestLabel=labels.get(i);}}}
        Object rides=field(activity,"screen");
        timing.put("saved_rides",(int)field(rides,"savedCount"));timing.put("list_cached_before_tap",cached.get());
        timing.put("process_age_ms",tap[0]-android.os.Process.getStartUptimeMillis());
        timing.put("tap_to_first_frame_ms",firstFrame==null?JSONObject.NULL:firstFrame);
        timing.put("tap_to_history_rows_ms",rowsFrame==null?JSONObject.NULL:rowsFrame);if(!rows)timing.put("history_rows","not drawn within "+RIDES_WAIT_MS/1000+" s");
        timing.put("longest_main_message_ms",longest);timing.put("longest_main_message",longestLabel.replaceAll("\\{[0-9a-f]+\\}|@[0-9a-f]+",""));
        runMain(()->require(HubPages.RIDE.equals(field(activity,"selectedPage")),"Navigation did not select rides"));waitForIdleSync();
        return timing;
    }
    /**
     * Disconnected, the page ControlsPages puts start_speed on (Controls › Assists) still has its kick-start row, shown
     * where this scooter offers it, with no slider of its own: the SeekBar lives in an edit sheet that opens only while
     * connected. Never while a charge recording is running: an intentional disconnect ends it (ChargeRecoveryPolicy
     * stops a capture when the intentional-disconnect count changes) and ChargeSessionStore.stop deletes an automatic
     * capture too short to keep, and the sweep runs with the scooter charging. The recording is read on the main thread
     * in the same pass as the disconnect, so it cannot start in between; with one running the check is skipped, not
     * failed. Returns the evidence line.
     */
    private String offlineSliderCancel(MainActivity activity)throws Exception{
        AtomicReference<Boolean> charging=new AtomicReference<>(false);
        runMain(()->{assertNoUserRecording();if(service.isChargeRecording()){charging.set(true);return;}reconnectAfterSmoke=service.ble.connected()||service.ble.state()==BleController.State.CONNECTING||service.ble.state()==BleController.State.AUTHENTICATING;service.ble.disconnect();service.settings.edit().putString("scooter_section","").commit();});
        if(charging.get()){status("Slider row check skipped: a charge recording is running; the disconnected check was skipped so it isn't ended (no disconnect, no control sent)");
            return "Skipped: a charge recording is running, so Volt was not disconnected and the recording was left alone; no mutation";}
        String page=HubTestPages.pageOf("start_speed");require(page!=null,"ControlsPages puts start_speed on no page");
        awaitReadOnly(()->service.ble.state()==BleController.State.DISCONNECTED&&!service.ble.connected(),8000,"Slider cancel regression requires no live GATT");
        // A page with nothing for this scooter has its link hidden (ControlsPages.linkShown), as the walk recorded it: no row to check.
        AtomicReference<Boolean> shown=new AtomicReference<>();runMain(()->shown.set(rule(activity,"pageShown",page)));
        if(!shown.get()){status("Slider row check skipped: "+page+" has nothing for this scooter (its link is hidden); disconnected, no control was sent");return "Disconnected: "+page+" hidden for this scooter, row check skipped; no mutation";}
        HubTestPages.reach(this,activity,page);arrive(activity,page);
        eventually(8000,()->{
            require(service.ble.state()==BleController.State.DISCONNECTED&&!service.ble.connected(),"Slider cancel regression requires no live GATT");
            View card=(View)field(HubTestPages.controlRow(activity,"start_speed"),"card");boolean offered=rule(activity,"rowOffered","start_speed");
            require(card.isShown()==offered,"Kick-start row on "+page+(offered?" is hidden while disconnected":" shows, but this scooter does not offer it"));
            // Rows have no inline slider; the SeekBar lives in an edit sheet that opens only while connected.
            require(!hasSeekBar(card),"The kick-start row has an inline slider");
        });status("Read-only slider row check passed while disconnected on "+page+"; no control was sent");
        return "Disconnected: kick-start row on "+page+"; slider only in the connected edit sheet; no mutation";
    }
    /**
     * -e charge_journal true with -e fixture saved (emulator only): an active charge recording journal, as
     * ChargeCaptureValidation.seedActive makes it (the developer simulator refuses to record a charge), with the service's
     * own charge update held off as that validation does, so the walk shows it skips its disconnect while one runs and
     * the recording is still active at the end.
     */
    private void seedChargeJournal()throws Exception{
        runMain(()->{try{ChargeSessionStore store=(ChargeSessionStore)field(service,"chargeStore");String id=service.profiles.active().id;long wall=System.currentTimeMillis(),mono=SystemClock.elapsedRealtime();
                store.start(id,ChargeSessionStore.Charger.HIGH_SPEED,wall,mono);
                store.sample(new ChargeSessionStore.Frame(wall,mono,new ChargeSessionStore.Value(1d,mono,0x72),new ChargeSessionStore.Value(50d,mono,0x72),new ChargeSessionStore.Value(52d,mono,0x72),new ChargeSessionStore.Value(-2d,mono,0x72),null));}
            catch(IOException e){throw new IllegalStateException(e);}
            // As ChargeCaptureValidation: postpone the service's disconnected-frame update, which would end a journal no
            // scooter is feeding, so only the walk itself could end it.
            try{java.lang.reflect.Field updating=ScooterService.class.getDeclaredField("chargeUpdating");updating.setAccessible(true);updating.setBoolean(service,true);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
            require(service.isChargeRecording(),"The synthetic charge journal is not active");call(service,"updateNotification",new Class<?>[0]);});
    }
    private static boolean hasSeekBar(View view){
        if(view instanceof android.widget.SeekBar)return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(hasSeekBar(((ViewGroup)view).getChildAt(i)))return true;
        return false;
    }
    /**
     * -e fixture saved|simulator (navigation mode only; the hardware sweep never passes it): lets the navigation step run on
     * a disposable emulator. Refuses a physical phone and any existing Volt data, then saves a synthetic UT5 MAX owner
     * profile (never connected, so it offers the UT5 MAX rows unavailable, as a saved scooter not yet read) with onboarding
     * done and -e home_style clean|widgets (default clean) chosen; simulator also connects the developer simulator's UT5 MAX
     * (no radio) before the walk, or with -e sim_model PID that model (BleController.setSimulationModel; 22131 is the N65i). Grant Nearby devices permission first, or the service never binds.
     */
    private void emulatorFixture(){
        String hardware=Build.HARDWARE.toLowerCase(Locale.ROOT),fixture=args.getString("fixture","");
        require(hardware.contains("ranchu")||hardware.contains("goldfish")||Build.PRODUCT.startsWith("sdk_gphone"),"-e fixture refuses a physical phone");
        require(fixture.equals("saved")||fixture.equals("simulator"),"Unknown -e fixture "+fixture+" (saved or simulator)");
        Context context=getTargetContext();
        for(String name:new String[]{"app_settings","profiles","scooter","pending_documents","control_evidence","onboarding"})require(context.getSharedPreferences(name,Context.MODE_PRIVATE).getAll().isEmpty(),"-e fixture refuses existing Volt preferences (clear Volt's data first)");
        // As HubValidation.admit: no private files or databases either.
        File[] files=context.getFilesDir().listFiles();
        require(files!=null&&files.length==0&&context.databaseList().length==0,"-e fixture refuses existing Volt files or databases (clear Volt's data first)");
        String style=args.getString("home_style","clean");require(style.equals("clean")||style.equals("widgets"),"Unknown -e home_style "+style);
        runMain(()->{ScooterProfiles profiles=new ScooterProfiles(context);ScooterProfiles.Profile profile=profiles.create("Synthetic navigation fixture","A1B2C3D4E5F6",0);
            profile.serial="X2538-SYNTHETIC";profile.mac="C0:FF:EE:12:34:56";profile.lastConnected=System.currentTimeMillis()-60000;profiles.update(profile);});
        require(context.getSharedPreferences("onboarding",Context.MODE_PRIVATE).edit().putBoolean("onboarding_done",true).commit()
                &&context.getSharedPreferences("app_settings",Context.MODE_PRIVATE).edit().putBoolean("auto_connect",false).putBoolean("background_connection",false)
                        .putBoolean("developer_options",true).putBoolean(ChargeAutoRecordPolicy.KEY,"true".equals(args.getString("auto_record",""))).putBoolean("auto_record_rides",false).putBoolean("riding_auto_open",false)
                        .putString("home_style",style).putBoolean("home_style_chosen",true).putString("home_style_origin","install").commit(),"Fixture settings failed");
    }
    private void dispatch(View view,long down,long stamp,int action,float x,float y){android.view.MotionEvent event=android.view.MotionEvent.obtain(down,stamp,action,x,y,0);try{view.dispatchTouchEvent(event);}finally{event.recycle();}}
    private void render(Activity activity,String filename)throws Exception{
        waitForIdleSync();AtomicReference<Bitmap> rendered=new AtomicReference<>();runMain(()->{((MainActivity)activity).redraw();View view=activity.findViewById(android.R.id.content);int width=view.getWidth(),height=view.getHeight();require(width>0&&height>0,"Render surface has no dimensions");forceLayout(view);view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));rendered.set(bitmap);});
        File dir=new File(getTargetContext().getExternalFilesDir(null),"validation");dir.mkdirs();try{try(FileOutputStream file=new FileOutputStream(new File(dir,filename))){rendered.get().compress(Bitmap.CompressFormat.PNG,100,file);}}finally{rendered.get().recycle();}TextCensus.write(this,new File(dir,filename));
    }
    private boolean foreground(){return Boolean.TRUE.equals(field(service,"foregroundStarted"));}
    private boolean hasConnectionNotification(){for(android.service.notification.StatusBarNotification notification:getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications())if(notification.getId()==71)return true;return false;}
    private void assertHardOff(String phase)throws Exception{
        runMain(()->{assertNoUserRecording();require(!service.isVisible()&&!service.workAllowed(),phase+": hidden work remained eligible");require(service.backgroundDeadlineMs()==0||service.backgroundDeadlineMs()<=SystemClock.elapsedRealtime(),phase+": deadline was extended");require(service.ble.state()==BleController.State.DISCONNECTED&&!service.ble.connected(),phase+": Bluetooth remained active");require(!foreground(),phase+": service retained foreground status");});
        awaitReadOnly(()->!hasConnectionNotification(),1500,phase+": connection notification remained");
    }
    /**
     * A charging scooter arms the live charging hold when Volt is left, and the hold skips the 10 s grace by design
     * (the 1.12.0 sweep failed here while charging). active() is only its internal state: the service also needs
     * notifications, so LiveChargingHoldCheck judges the effective hold from what it observed, and fails a hold that did not
     * arm although its conditions, read immediately before leaving, were met. Live charging then goes off so
     * the grace itself can be timed whether or not the scooter is plugged in; the switch is in SMOKE_PREFS, so cleanup restores it.
     * Returns the evidence line.
     */
    private String allowForLiveChargingHold()throws Exception{
        AtomicReference<String> evidence=new AtomicReference<>();
        runMain(()->{assertNoUserRecording();service.settings.edit().putBoolean("background_connection",false).commit();service.visible(true);
            LiveChargingHoldCheck.Observed observed;
            try{LiveChargingHoldCheck.Conditions before=LiveChargingHoldProbe.conditions(service);service.visible(false);observed=LiveChargingHoldProbe.observe(service,before);}catch(ReflectiveOperationException error){throw new IllegalStateException("Cannot read the live charging hold",error);}
            String problem=LiveChargingHoldCheck.problem(observed);
            require(problem==null,problem);
            evidence.set(LiveChargingHoldCheck.evidence(observed));
            service.setChargeLiveEnabled(false);
            require(!((ChargeLiveHold)field(service,"chargeLive")).active(),"Turning Live charging off did not end the hold");
            service.visible(true);});
        return evidence.get();
    }
    private void backgroundSmoke()throws Exception{
        String liveHold=allowForLiveChargingHold();
        AtomicReference<Long> initial=new AtomicReference<>(),first=new AtomicReference<>(),second=new AtomicReference<>();
        runMain(()->{assertNoUserRecording();initial.set(mutations());reconnectAfterSmoke=service.ble.connected()||service.ble.state()==BleController.State.CONNECTING||service.ble.state()==BleController.State.AUTHENTICATING;service.settings.edit().putBoolean("auto_connect",true).putBoolean("background_connection",false).commit();service.visible(true);long pause=SystemClock.elapsedRealtime();service.visible(false);first.set(service.backgroundDeadlineMs());require(first.get()>=pause+9950&&first.get()<=SystemClock.elapsedRealtime()+10050,"First pause did not get a full 10s grace");});
        status("Background smoke: first grace remains eligible before its deadline");sleepUntil(first.get()-8000);
        runMain(()->{assertNoUserRecording();require(!service.isVisible()&&service.workAllowed()&&service.backgroundDeadlineMs()==first.get(),"Grace stopped early or its deadline changed");service.visible(true);require(service.backgroundDeadlineMs()==0&&service.workAllowed(),"Resume did not cancel the grace deadline");});
        sleepUntil(first.get()-2000);
        runMain(()->{assertNoUserRecording();long pause=SystemClock.elapsedRealtime();service.visible(false);second.set(service.backgroundDeadlineMs());require(second.get()>=pause+9950&&second.get()>first.get()+6000,"Second pause did not get a fresh full grace");});
        sleepUntil(first.get()+1000);
        runMain(()->{assertNoUserRecording();require(service.workAllowed()&&service.backgroundDeadlineMs()==second.get(),"Old queued pause timer cancelled the second grace");require(foreground(),"Foreground service stopped during the second grace");});
        status("Background smoke: old deadline cancelled; checking hard second deadline");sleepUntil(second.get()+500);assertHardOff("Second grace deadline");
        Thread.sleep(2000);assertHardOff("Two seconds after cutoff");
        runMain(()->getTargetContext().startForegroundService(new Intent(getTargetContext(),ScooterService.class)));Thread.sleep(300);assertHardOff("Late hidden start");
        AtomicReference<Boolean> connecting=new AtomicReference<>(false);AtomicReference<Long> connectingDeadline=new AtomicReference<>();
        runMain(()->{assertNoUserRecording();service.visible(true);require(service.workAllowed()&&service.backgroundDeadlineMs()==0,"Visible return did not restore work policy");ScooterProfiles.Profile profile=service.profiles.active();
            if(profile!=null&&profile.configured()&&!profile.mac.isEmpty()&&service.ble.permissions()&&service.ble.bluetoothEnabled()){
                require(service.ble.state()==BleController.State.CONNECTING||service.ble.state()==BleController.State.AUTHENTICATING,"Visible return did not attempt the saved automatic connection");connecting.set(true);long pause=SystemClock.elapsedRealtime();service.visible(false);connectingDeadline.set(service.backgroundDeadlineMs());require(connectingDeadline.get()>=pause+9950,"Connecting pause did not receive a full grace");
                try{java.lang.reflect.Method failure=BleController.class.getDeclaredMethod("failed",String.class,boolean.class,ConnectionEventLog.Reason.class,int.class);failure.setAccessible(true);failure.invoke(service.ble,"Validation: simulated connection failure",true,ConnectionEventLog.Reason.CONNECT_TIMEOUT,-1);}
                catch(ReflectiveOperationException error){throw new IllegalStateException("Cannot simulate the transport-only failure",error);}
                require(service.backgroundDeadlineMs()==connectingDeadline.get()&&((Number)field(service.ble,"backgroundDeadlineMs")).longValue()==connectingDeadline.get(),"A failed connection extended or cleared its fixed grace deadline");
                require(service.ble.state()==BleController.State.WAITING||service.ble.state()==BleController.State.CONNECTING||service.ble.state()==BleController.State.AUTHENTICATING,"The simulated failure did not enter its retry path");
            }
        });
        if(connecting.get()){status("Background smoke: simulated transport failure/retries must stop at the original fixed deadline");sleepUntil(connectingDeadline.get()+500);assertHardOff("Connecting/failure grace deadline");Thread.sleep(2000);assertHardOff("Two seconds after simulated-failure cutoff");}
        runMain(()->require(mutations()==initial.get(),"Lifecycle smoke submitted a scooter setting mutation"));
        JSONObject evidence=new JSONObject();evidence.put("mode","background");evidence.put("live_charging_hold",liveHold);evidence.put("grace_ms",10000);evidence.put("first_resume_cancelled",true);evidence.put("second_pause_full_grace",true);evidence.put("old_deadline_did_not_disconnect",true);evidence.put("hard_cutoff",true);evidence.put("late_hidden_start_no_new_grace",true);evidence.put("connecting_case",connecting.get()?"Started while CONNECTING/AUTHENTICATING, simulated transport-only failure entered retries, original fixed deadline retained, hard cutoff and no late retry/notification after 2s":"Skipped: no verified scooter address/Bluetooth permission or enabled adapter");evidence.put("scooter_mutations",0);evidence.put("recordings_created",0);results.put(evidence);
    }
    /** Scheduled charging or Charge done by, Volt's own or set elsewhere and read back on: a plan the checks must leave alone. */
    private boolean planActive(){
        AtomicReference<Boolean> active=new AtomicReference<>();
        runMain(()->{ChargeScheduleController.View v=service.chargeSchedule();active.set(v.manualEnabled||v.doneByEnabled||Integer.valueOf(1).equals(service.ble.featureValue("timed_charging")));});
        return Boolean.TRUE.equals(active.get());
    }
    /**
     * Test scheduled charging end to end, as the owner's button runs it: about 2 minutes ahead, judged by the scooter's own
     * pause and resume. Passes only on an on-time resume with the schedule confirmed off and the scooter reading it off.
     */
    private void scheduleTest(Bundle output)throws Exception{
        require(!service.isRecording(),"Finish the existing recording before the schedule test");
        AtomicReference<Boolean> offered=new AtomicReference<>();runMain(()->offered.set(service.scheduleTestOffered()));require(Boolean.TRUE.equals(offered.get()),"Test scheduled charging is not offered for this scooter");
        AtomicReference<String> blocked=new AtomicReference<>();runMain(()->blocked.set(service.startScheduleTest()));require(blocked.get()==null,"Schedule test not started: "+blocked.get());
        status("schedule: started; the scooter should pause, then resume about 2 minutes ahead");
        awaitReadOnly(()->!service.scheduleTesting(),9*60_000L,"Schedule test did not finish within 9 minutes");
        java.lang.reflect.Method schedules=ScooterService.class.getDeclaredMethod("schedules");schedules.setAccessible(true);
        AtomicReference<ChargeScheduleController.Diagnostics> read=new AtomicReference<>();runMain(()->{try{read.set(((ChargeScheduleController)schedules.invoke(service)).diagnostics());}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}});
        ChargeScheduleController.Diagnostics d=read.get();require(d!=null,"No schedule record for this scooter");
        waitFor(()->service.ble.ready()&&service.ble.pending()==null,15000,"Connection unavailable after the schedule test");
        Integer enabled=service.ble.featureValue("timed_charging");ChargeScheduleController.View view=service.chargeSchedule();
        JSONObject row=new JSONObject();row.put("key","schedule_test");row.put("outcome",d.testOutcome.name());row.put("offset_s",d.testOffsetSeconds==null?JSONObject.NULL:d.testOffsetSeconds);
        row.put("paused",d.testPaused);row.put("schedule_off_confirmed",Integer.valueOf(1).equals(d.testScheduleOff));row.put("timed_charging_readback",enabled==null?JSONObject.NULL:enabled);
        row.put("manual_enabled",view.manualEnabled);row.put("done_by_enabled",view.doneByEnabled);results.put(row);
        output.putString("schedule_test",d.testOutcome.name()+(d.testOffsetSeconds==null?"":" offset="+d.testOffsetSeconds+"s"));
        require(Integer.valueOf(1).equals(d.testScheduleOff)&&Integer.valueOf(0).equals(enabled)&&!view.manualEnabled&&!view.doneByEnabled,"Scheduled charging was not confirmed off after the test; turn it off in Volt");
        require(d.testOutcome==ChargeScheduleTest.Outcome.PASSED,"Schedule test result: "+d.testOutcome);
    }
    private void sleepUntil(long monotonic)throws InterruptedException{for(;;){long remaining=monotonic-SystemClock.elapsedRealtime();if(remaining<=0)return;Thread.sleep(Math.min(500,remaining));}}
    /** Keep assertion failures on the instrumentation thread, never uncaught on MainLooper. */
    private void runMain(Runnable task){
        if(Looper.myLooper()==Looper.getMainLooper()){task.run();return;}
        AtomicReference<Throwable> failure=new AtomicReference<>();
        super.runOnMainSync(()->{try{task.run();}catch(Throwable error){failure.set(error);}});
        Throwable error=failure.get();if(error instanceof Error)throw (Error)error;if(error instanceof RuntimeException)throw (RuntimeException)error;if(error!=null)throw new IllegalStateException("Main-thread validation failed",error);
    }
    /** Unlike legacy waitFor, never changes controller visibility or starts connection work. */
    private void awaitReadOnly(BooleanSupplier condition,long timeout,String failure)throws Exception{long deadline=SystemClock.elapsedRealtime()+timeout;for(;;){AtomicReference<Boolean> value=new AtomicReference<>();runMain(()->value.set(condition.getAsBoolean()));if(Boolean.TRUE.equals(value.get()))return;if(SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException(failure);Thread.sleep(100);}}
    private void validate(ScooterCommands.Feature feature)throws Exception{
        if(feature.requiresParked)waitFor(()->service.ble.stationary(),10000,"No fresh stationary reading; no test write");
        Integer before=service.ble.featureValue(feature.key);JSONObject row=new JSONObject();row.put("key",feature.key);row.put("experimental",feature.experimental);
        if(before==null||!feature.allows(before)){row.put("outcome",before==null?"Unavailable readback; no write":"Current value outside understood write domain; no write");results.put(row);status(feature.key+": skipped");return;}
        int target=before;int[] choices=service.ble.featureChoices(feature.key);
        if(choices.length>0){for(int candidate:choices)if(candidate!=before){target=candidate;break;}}
        else if(feature.type==ScooterCommands.Type.TOGGLE)target=before==0?1:0;
        else for(int candidate=feature.min;candidate<=feature.max;candidate+=feature.step)if(candidate!=before&&feature.allows(candidate)){target=candidate;break;}
        if(feature.key.equals("start_speed")&&before!=5)target=5;
        if(target==before){row.put("outcome","No alternative supported value; no write");results.put(row);status(feature.key+": single option");return;}
        row.put("before",before);row.put("test",target);boolean restored=false;boolean confirmed=false;String failure=null;
        try{int desired=target;AtomicReference<Boolean> submitted=new AtomicReference<>();runMain(()->submitted.set(service.ble.set(feature.key,desired)));if(!Boolean.TRUE.equals(submitted.get()))throw new IllegalStateException("Change declined: "+service.ble.message());
            waitFor(()->service.ble.pending()==null,12000,"Change did not settle");waitFor(()->service.ble.ready(),12000,"Connection did not recover");confirmed=Integer.valueOf(target).equals(service.ble.featureValue(feature.key));row.put("after",service.ble.featureValue(feature.key));row.put("change_confirmed",confirmed);row.put("evidence",service.ble.commandResults().get(feature.key));
        }catch(Exception e){failure=e.getMessage();row.put("failure",failure);}
        finally{
            waitFor(()->service.ble.pending()==null&&service.ble.ready(),22000,"Cannot safely restore: connection unavailable");
            if(before.equals(service.ble.featureValue(feature.key)))restored=true;
            else{runMain(()->service.ble.set(feature.key,before));waitFor(()->service.ble.pending()==null,12000,"Restoration did not settle");restored=service.ble.ready()&&before.equals(service.ble.featureValue(feature.key));}
            row.put("restored",restored);row.put("outcome",confirmed&&restored?"Change and restoration confirmed":restored?"No confirmed change; baseline restored":"Restoration unconfirmed");results.put(row);status(feature.key+": "+row.getString("outcome"));
            if(!restored)throw new IllegalStateException("Stop validation: "+feature.key+" restoration is not confirmed");
        }
        if(!confirmed&&failure!=null)throw new IllegalStateException("Stop validation after declined/unconfirmed change: "+feature.key+" · "+failure);
        Thread.sleep(300);
    }
    private void waitFor(BooleanSupplier condition,long timeout,String failure)throws Exception{long deadline=SystemClock.elapsedRealtime()+timeout;for(;;){AtomicReference<Boolean> value=new AtomicReference<>();runMain(()->{if(service!=null)service.ble.visibility(true,service.isRecording());value.set(condition.getAsBoolean());});if(Boolean.TRUE.equals(value.get()))return;if(SystemClock.elapsedRealtime()>deadline)throw new IllegalStateException(failure);Thread.sleep(100);}}
    private void save(String name,String text)throws IOException{File dir=new File(getTargetContext().getExternalFilesDir(null),"validation");dir.mkdirs();try(FileOutputStream stream=new FileOutputStream(new File(dir,name))){stream.write(text.getBytes(StandardCharsets.UTF_8));}}
    private void status(String text){Bundle b=new Bundle();b.putString("progress",text);sendStatus(0,b);}
    private void forceLayout(View view){view.forceLayout();if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)forceLayout(group.getChildAt(i));}}
}
