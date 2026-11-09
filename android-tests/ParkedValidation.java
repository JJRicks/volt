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
public final class ParkedValidation extends Instrumentation {
    private Bundle args;private volatile ScooterService service;private final JSONArray results=new JSONArray();private boolean bound;private Boolean originalBackground;
    private Activity launchedActivity;private Map<String,Object> smokePreferences;private boolean smokeMode,reconnectAfterSmoke;
    private static final String[] SMOKE_PREFS={"background_connection","auto_connect","charge_live_enabled","scooter_section","stats_section","stats_query","stats_favourites","stats_favourites_only","more_section"};
    private static final Set<String> LINE_KEYS=new HashSet<>(Arrays.asList("line_light","line_pattern","line_color","line_boost_color"));
    private static final Set<String> STAR_KEYS=new HashSet<>(Arrays.asList("star_light","star_pattern","star_color","star_boost_color"));
    @Override public void onCreate(Bundle args){this.args=args==null?new Bundle():args;start();}
    private final ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){service=((ScooterService.LocalBinder)b).service();}public void onServiceDisconnected(ComponentName n){service=null;}};
    @Override public void onStart(){Bundle output=new Bundle();try{
        String mode=args.getString("mode","snapshot");smokeMode=mode.equals("navigation")||mode.equals("background");
        if(smokeMode)backupSmokePreferences();
        Activity activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));launchedActivity=activity;
        runMain(()->bound=getTargetContext().bindService(new Intent(getTargetContext(),ScooterService.class),connection,Context.BIND_AUTO_CREATE));
        awaitReadOnly(()->service!=null,8000,"Local service unavailable");
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
        save("latest-diagnostics.json",service.snapshot());JSONObject report=new JSONObject();report.put("format","navee-parked-control-validation-v1");report.put("checked_at_ms",System.currentTimeMillis());report.put("results",results);report.put("warning","Readback validation only. Propulsion, descent, cruise engagement, physical lighting and ridden energy behavior require rider checks.");save("control-validation.json",report.toString(2));
        // The hardware sweep reads this to decide whether a schedule test can run (it needs a charge in progress).
        if(!smokeMode){output.putString("charging_state",String.valueOf(service.ble.telemetry.number("charging_state")));output.putString("schedule_active",String.valueOf(planActive()));}
        output.putString("result","Saved local diagnostics and validation evidence");output.putInt("controls",smokeMode?0:results.length());if(smokeMode)output.putInt("checks",results.length());output.putString("original_background",String.valueOf(smokeMode&&smokePreferences!=null?smokePreferences.getOrDefault("background_connection",false):originalBackground));cleanup();finish(0,output);
    }catch(Throwable e){try{save("partial-control-validation.json",results.toString(2));if(service!=null)save("failed-diagnostics.json",service.snapshot());}catch(Exception ignored){}output.putString("error",e.getClass().getSimpleName()+": "+e.getMessage());cleanup();finish(1,output);}finally{cleanup();}}
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
    private void navigate(Activity activity,String page)throws Exception{
        runMain(()->{assertNoUserRecording();selectOnMain(activity,page);});waitForIdleSync();
        runMain(()->{ScrollView view=(ScrollView)field(activity,"scroll");view.scrollTo(0,0);require(page.equals(field(activity,"selectedPage")),"Navigation did not select "+page);});
        waitForIdleSync();
    }
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
    /** Stats › a section, through its section selector (tag live-stats-sections) as a tap would, never by matching tab text. */
    @SuppressWarnings("unchecked") private void clickStatsSection(Activity activity,String section)throws Exception{
        runMain(()->{Object stats=currentScreen(activity);View selector=(View)field(stats,"sectionTabs");require("live-stats-sections".equals(selector.getTag())&&selector.isShown(),"Stats section selector missing");
            List<String> order=Arrays.asList((String[])field(stats,statsWide(stats)?"WIDE_SECTIONS":"SECTIONS"));List<TextView> choices=(List<TextView>)field(field(selector,"track"),"choices");
            int index=order.indexOf(section);require(index>=0&&choices.size()==order.size(),"Stats selector does not offer "+section);View choice=choices.get(index);require(choice.isShown()&&choice.isClickable(),"Stats selector choice not reachable: "+section);choice.performClick();});
        waitForIdleSync();
        eventually(()->{Object stats=currentScreen(activity);require(section.equals(field(stats,"selectedSection")),"Stats selector did not select "+section);assertStatsSections(stats,section);});
    }
    /** Back to the More menu (Units and Compatibility first return to their parent), then opens a submenu from its menu row; the wide layout keeps the menu beside the submenu. */
    private void clickMoreSection(Activity activity,String label)throws Exception{
        runMain(()->{Object more=currentScreen(activity);
            for(int i=0;i<3&&Boolean.TRUE.equals(call(more,"back",new Class<?>[0]));i++){}
            View row=findText(screenView(activity),label);require(row!=null,"Visible More menu row missing: "+label);
            while(!row.isClickable()&&row.getParent() instanceof View)row=(View)row.getParent();
            require(row.isClickable(),"More menu row is not clickable: "+label);row.performClick();});
        waitForIdleSync();
    }
    /** A row inside the open More submenu, by its tag. */
    private void clickMoreTagged(Activity activity,String tag)throws Exception{
        runMain(()->{View row=screenView(activity).findViewWithTag(tag);require(row!=null&&row.isShown()&&row.isClickable(),"More row missing: "+tag);row.performClick();});
        waitForIdleSync();
    }
    private void require(boolean condition,String failure){if(!condition)throw new IllegalStateException(failure);}
    private void assertNoUserRecording(){require(service!=null&&!service.isRecording(),"Smoke test stopped: a user recording is active; no stop/disconnect was requested");require(service.ble.pending()==null,"Smoke test stopped: a scooter change is pending");}
    @SuppressWarnings("unchecked") private void assertSectionMap(Object screen,String selected,Set<String> expected){
        Map<String,View> sections=(Map<String,View>)field(screen,"sections");require(sections.keySet().equals(expected),"More sections "+sections.keySet()+" do not match the screen's own rules "+expected);
        require(selected.equals(field(screen,"selectedSection")),"More did not open "+selected);
        for(Map.Entry<String,View> section:sections.entrySet())require(section.getValue().getVisibility()==(section.getKey().equals(selected)?View.VISIBLE:View.GONE),"Wrong section visibility: "+section.getKey()+" while "+selected+" selected");
    }
    /** The screen's own rule for whether this scooter offers a row (an Experimental controls row such as the UT5 MAX's acceleration is not). */
    private boolean rowOffered(Object screen,String key){return Boolean.TRUE.equals(call(screen,"rowOffered",new Class<?>[]{String.class},key));}
    /** Scooter tab: null is the main page, "lights" the Lights page, otherwise a search query. */
    @SuppressWarnings("unchecked") private void assertControls(Activity activity,String mode){
        Object screen=currentScreen(activity);List<Object> rows=(List<Object>)field(screen,"rows");int shown=0,offeredLights=0;boolean zones=false;
        // The UT5 MAX keeps a maximum_speed row (not offered) behind the top speed card; another model's speed table is an ordinary row.
        boolean topSpeedCard=Boolean.TRUE.equals(call(screen,"topSpeedCard",new Class<?>[0]));
        for(Object row:rows){
            ScooterCommands.Feature feature=(ScooterCommands.Feature)field(row,"feature");View card=(View)field(row,"card");
            if(topSpeedCard&&feature.key.equals("maximum_speed"))require(!card.isShown(),"Maximum speed belongs to the top speed card, not a settings row");
            boolean zone=LINE_KEYS.contains(feature.key)||STAR_KEYS.contains(feature.key),light=zone||feature.key.equals("headlight")||feature.key.equals("auto_sensor");
            boolean offered=rowOffered(screen,feature.key);if(offered&&light)offeredLights++;if(zone&&offered)zones=true;
            boolean expected=offered&&(mode==null?!zone:mode.equals("lights")?light:mode.equals("kickstart")&&feature.key.equals("start_speed"));
            require(card.isShown()==expected,"Unexpected control row visibility: "+feature.key+(offered?"":" (not offered here)"));if(card.isShown())shown++;
        }
        View topSpeed=(View)field(field(screen,"topSpeed"),"card");require(topSpeed.isShown()==(mode==null&&topSpeedCard),"Unexpected top speed card visibility");
        if(mode==null)require(shown>0,"Scooter page has no settings rows");
        else if(mode.equals("lights"))require(shown==offeredLights,"Lights page must show every offered headlight and zone control");
        else if(mode.equals("kickstart"))require(shown==1,"Kickstart search must show exactly the start-speed control");
        else require(shown==0,"No-match query left a control visible");
        Map<View,String[]> extras=(Map<View,String[]>)field(screen,"extras");View headlightRow=(View)field(screen,"headlightRow");
        // The read-only headlight row (switched by the power button) belongs to the Lights page only. The other extra rows
        // are main-page rows, each behind the screen's own rule for this scooter: the zones link only with line or star
        // lights, clock sync and the schedule test where offered, Charge done by and the battery alerts by model.
        View zonesRow=(View)field(screen,"zonesRow"),clockRow=(View)field(field(screen,"clockLine"),"card"),testRow=(View)field(field(field(screen,"scheduleTest"),"row"),"card");
        boolean clock=Boolean.TRUE.equals(call(screen,"offered",new Class<?>[]{String.class},"clock_sync")),test=Boolean.TRUE.equals(call(activity,"scheduleTestOffered",new Class<?>[0]));
        boolean doneBy=Boolean.TRUE.equals(call(activity,"chargeDoneOffered",new Class<?>[0])),alerts=Boolean.TRUE.equals(call(activity,"chargeAlertsOffered",new Class<?>[0]));
        for(Map.Entry<View,String[]> e:extras.entrySet()){View row=e.getKey();Object tag=row.getTag();
            boolean expected=row==headlightRow?"lights".equals(mode):mode!=null?false:row==zonesRow?zones:row==clockRow?clock:row==testRow?test
                    :"charge-done-by".equals(tag)?doneBy:"charge-alert".equals(tag)?alerts:true;
            require(row.isShown()==expected,"Unexpected extra settings row visibility: "+e.getValue()[1]+(mode==null?" on the main page":" in "+mode));}
        if("lights".equals(mode))require(findText(headlightRow,T.mainRowHeadlightValue())!=null,"Lights page headlight row lost its Power button value");
    }
    /** Setting groups the Scooter main page shows (Ride, Battery, ...): only the ones with a row for this scooter. */
    @SuppressWarnings("unchecked") private int shownControlGroups(Object screen){int shown=0;for(View group:((Map<String,View>)field(screen,"sections")).values())if(group.isShown())shown++;return shown;}
    /** Wide Stats keeps Readings in its own right-hand pane, so it has no Readings tab and that section is always shown. */
    private boolean statsWide(Object screen){return Boolean.TRUE.equals(field(screen,"wide"));}
    private void assertStatsSections(Object screen,String selected){
        String[] names={"Overview","Readings","Charts","Diagnostics"};String[] fields={"overviewSection","readingsSection","chartsSection","diagnosticsSection"};boolean wide=statsWide(screen);
        for(int i=0;i<names.length;i++){boolean shown=names[i].equals(selected)||wide&&names[i].equals("Readings");require(((View)field(screen,fields[i])).getVisibility()==(shown?View.VISIBLE:View.GONE),"Unexpected Stats section visibility: "+names[i]+" while "+selected+" selected");}
    }
    private long mutations(){return ((Number)field(service.ble,"mutationSequence")).longValue();}
    /** Every tab and section, read only; returns the summary line and records the counts actually seen. */
    @SuppressWarnings("unchecked") private String navigationSmoke(Activity activity,Bundle output)throws Exception{
        AtomicReference<Long> initial=new AtomicReference<>();runMain(()->{assertNoUserRecording();initial.set(mutations());service.settings.edit().putString("scooter_section","").putString("stats_query","").putBoolean("stats_favourites_only",false).commit();});
        JSONObject rides=null;
        for(String page:new String[]{"home","controls","stats","rides","more"}){
            if(page.equals("rides")){rides=timeRidesOpen(activity);output.putString("rides_first_open",rides.toString());status("Read-only navigation: rides "+rides);continue;}
            navigate(activity,page);status("Read-only navigation: "+page);
        }
        navigate(activity,"controls");
        AtomicReference<Integer> groups=new AtomicReference<>();
        runMain(()->{assertControls(activity,null);groups.set(shownControlGroups(currentScreen(activity)));});
        runMain(()->{EditText input=findInput(screenView(activity),T.mainControlsSearchDescription());require(input!=null,"Controls search missing");input.setText("kickstart");assertControls(activity,"kickstart");input.setText("no-match");assertControls(activity,"no-match");require(findText(screenView(activity),T.mainSearchNone())!=null,"No-match explanation missing");input.setText("");});
        runMain(()->selectOnMain(activity,"lights"));waitForIdleSync();runMain(()->assertControls(activity,"lights"));
        runMain(()->require(Boolean.TRUE.equals(call(currentScreen(activity),"back",new Class<?>[0])),"Lights page Back was not handled"));
        waitForIdleSync();runMain(()->assertControls(activity,null));render(activity,"navigation-controls.png");
        navigate(activity,"stats");
        AtomicReference<Boolean> wideStats=new AtomicReference<>();AtomicReference<Integer> statsTabs=new AtomicReference<>();
        runMain(()->{Object stats=currentScreen(activity);wideStats.set(statsWide(stats));List<TextView> choices=(List<TextView>)field(field(field(stats,"sectionTabs"),"track"),"choices");statsTabs.set(choices.size());
            if(wideStats.get())for(TextView choice:choices)require(!T.statsTabReadings().contentEquals(choice.getText()),"Wide Stats must not offer a Readings tab beside the Readings pane");});
        Set<String> statsSeen=new LinkedHashSet<>();
        for(String section:new String[]{"Overview","Readings","Charts","Diagnostics"}){if(wideStats.get()&&section.equals("Readings"))continue;clickStatsSection(activity,section);statsSeen.add(section);if(wideStats.get())statsSeen.add("Readings");}
        if(!wideStats.get())clickStatsSection(activity,"Readings");
        runMain(()->{EditText input=findInput(screenView(activity),T.statsReadingsSearchDescription());require(input!=null,"Stats search missing");input.setText("voltage");
            Map<?,?> rows=(Map<?,?>)field(currentScreen(activity),"rows");Object voltage=rows.get("voltage_v");require(voltage!=null,"Voltage favourite row missing");require(((View)field(voltage,"layout")).isShown(),"Voltage search result missing");View star=(View)field(voltage,"star");require(star.isShown(),"Voltage favourite is not reachable");star.performClick();star.performClick();});
        render(activity,"navigation-stats-readings.png");runMain(()->{EditText input=findInput(screenView(activity),T.statsReadingsSearchDescription());input.setText("");require(input.getText().length()==0&&"".equals(field(currentScreen(activity),"query")),"Clearing Stats search did not clear its input/query");});clickStatsSection(activity,"Overview");render(activity,"navigation-stats-overview.png");
        navigate(activity,"more");
        // The expected sections come from the screen's own rules: Developer only with developer options on; Units always; Compatibility with developer options on or a test recovery pending.
        List<String[]> menu=new ArrayList<>();Set<String> expected=new LinkedHashSet<>();AtomicReference<Boolean> compatibility=new AtomicReference<>();
        runMain(()->{Object more=currentScreen(activity);String[] ids=(String[])field(more,"SECTIONS");int[] labels=(int[])field(more,"SECTION_LABEL_IDS");require(ids.length==labels.length,"More menu labels out of step with its sections");
            for(int i=0;i<ids.length;i++)if(Boolean.TRUE.equals(call(more,"knownSection",new Class<?>[]{String.class},ids[i]))){menu.add(new String[]{ids[i],T.render(labels[i])});expected.add(ids[i]);}
            expected.add("Units");compatibility.set(Boolean.TRUE.equals(call(more,"knownSection",new Class<?>[]{String.class},"Compatibility")));if(compatibility.get())expected.add("Compatibility");});
        String appLabel=null,developerLabel=null;Set<String> moreSeen=new LinkedHashSet<>();
        for(String[] section:menu){
            if(section[0].equals("App"))appLabel=section[1];if(section[0].equals("Developer"))developerLabel=section[1];
            clickMoreSection(activity,section[1]);eventually(()->assertSectionMap(currentScreen(activity),section[0],expected));moreSeen.add(section[0]);
        }
        require(appLabel!=null,"More menu has no App preferences row");
        clickMoreSection(activity,appLabel);clickMoreTagged(activity,"preference-units-menu");eventually(()->assertSectionMap(currentScreen(activity),"Units",expected));moreSeen.add("Units");
        if(compatibility.get()){
            if(developerLabel!=null){clickMoreSection(activity,developerLabel);clickMoreTagged(activity,"developer-compatibility-check");}
            // Without developer options, Compatibility is the recovery-only route an unfinished test opens; open it the same way.
            else runMain(()->call(currentScreen(activity),"selectSection",new Class<?>[]{String.class},"Compatibility"));
            waitForIdleSync();eventually(()->assertSectionMap(currentScreen(activity),"Compatibility",expected));moreSeen.add("Compatibility");
        }
        require(moreSeen.equals(expected),"More sections visited "+moreSeen+" but the screen has "+expected);
        clickMoreSection(activity,appLabel);render(activity,"navigation-more-app.png");
        runMain(()->require(mutations()==initial.get(),"Navigation/search/favourite actions submitted a scooter mutation"));
        offlineSliderCancel(activity);
        runMain(()->require(mutations()==initial.get(),"Slider cancellation submitted a scooter mutation"));
        navigate(activity,"home");JSONObject evidence=new JSONObject();evidence.put("mode","navigation");evidence.put("tabs",5);evidence.put("controls_groups",groups.get());evidence.put("stats_wide",wideStats.get());evidence.put("stats_tabs",statsTabs.get());evidence.put("stats_sections",statsSeen.size());evidence.put("more_sections",moreSeen.size());evidence.put("more_section_ids",new JSONArray(moreSeen));evidence.put("scooter_mutations",0);evidence.put("slider_cancel","Disconnected: kick-start row present, slider only in the connected edit sheet; no mutation");evidence.put("rides_first_open",rides);results.put(evidence);
        return "All five tabs, "+groups.get()+" Scooter groups / "+statsSeen.size()+" Stats sections ("+statsTabs.get()+" tabs) / "+moreSeen.size()+" More sections, search and optional favourite passed without scooter mutations";
    }
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
        runMain(()->require("rides".equals(field(activity,"selectedPage")),"Navigation did not select rides"));waitForIdleSync();
        return timing;
    }
    @SuppressWarnings("unchecked") private void offlineSliderCancel(Activity activity)throws Exception{
        runMain(()->{assertNoUserRecording();reconnectAfterSmoke=service.ble.connected()||service.ble.state()==BleController.State.CONNECTING||service.ble.state()==BleController.State.AUTHENTICATING;service.ble.disconnect();service.settings.edit().putString("scooter_section","").commit();});
        navigate(activity,"controls");
        runMain(()->{
            require(service.ble.state()==BleController.State.DISCONNECTED&&!service.ble.connected(),"Slider cancel regression requires no live GATT");
            Object target=null;for(Object row:(List<Object>)field(currentScreen(activity),"rows"))if(((ScooterCommands.Feature)field(row,"feature")).key.equals("start_speed")){target=row;break;}
            require(target!=null,"Kick-start slider row unavailable");
            // Rows have no inline slider; the SeekBar lives in an edit sheet that opens only while connected.
        });status("Read-only slider row check passed while disconnected; no control was sent");
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
     * notifications, so LiveChargingHoldCheck judges the effective hold from what it observed. Live charging then goes off so
     * the grace itself can be timed whether or not the scooter is plugged in; the switch is in SMOKE_PREFS, so cleanup restores it.
     * Returns the evidence line.
     */
    private String allowForLiveChargingHold()throws Exception{
        AtomicReference<String> evidence=new AtomicReference<>();
        runMain(()->{assertNoUserRecording();service.settings.edit().putBoolean("background_connection",false).commit();service.visible(true);service.visible(false);
            LiveChargingHoldCheck.Observed observed;
            try{observed=LiveChargingHoldProbe.observe(service);}catch(ReflectiveOperationException error){throw new IllegalStateException("Cannot read the live charging hold",error);}
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
