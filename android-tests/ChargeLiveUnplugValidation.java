package com.jjricks.volt;

import android.Manifest;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.bluetooth.*;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.*;
import android.service.notification.StatusBarNotification;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Disposable emulator only: the live charging card out of sight, on the real service, real tick and real poll cadence.
 * Every packet goes to an in-process GATT stand-in with a synthesized scooter; no radio, no phone. A run takes about
 * five minutes. The stand-in reports charging_state only in its reply to the background read (0x72) unless a scenario
 * pushes a report, so an unplug shows up at the next 15 s poll, as on a scooter that pushes nothing.
 *
 * Unplug just after a poll (near the worst case) and just before one, Volt in the background: the card clears within one
 * poll plus ChargeLiveHold.UNPLUG_MS and the measured times go in the timeline. A cut-out between two polls is never seen,
 * a replug inside the window keeps the card, one after it doesn't re-arm, and a connected scooter that stops giving
 * a reading (null, a gap) keeps the card. Mode notifications-off runs the sweep's own hold check with notifications off.
 */
public final class ChargeLiveUnplugValidation extends Instrumentation {
    private static final long S=1000L;
    private static final byte OFF=0,ON=1;
    private MainActivity activity;
    private ScooterService service;
    private BleController ble;
    private ScooterProfiles.Profile profile;
    private FakeLink link;
    private int checks;
    private final StringBuilder timeline=new StringBuilder();
    /** "notifications-off": POST_NOTIFICATIONS withheld, the sweep's hold check only. */
    private String mode="";
    @Override public void onCreate(Bundle args){if(args!=null)mode=args.getString("mode","");start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            admit();
            main(()->{
                settings().edit().putBoolean("auto_connect",false).putBoolean("background_connection",true).commit();
                ScooterProfiles profiles=new ScooterProfiles(getTargetContext());profile=profiles.create("Synthetic live card fixture","000000000000",0);
                profile.serial="Q2538ZZ9US7777777";profile.mac="C0:FF:EE:00:25:38";profiles.update(profile);profiles.select(profile.id);
            });
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(()->activity.service()!=null,10000,"Service missing");service=activity.service();ble=service.ble;waitForIdleSync();
            main(()->{link=new FakeLink();link.attach();});
            await(()->onMain(()->ble.ready()),10000,"Synthetic link never ready");
            log("link ready, charging");
            if("notifications-off".equals(mode)){notificationsBlockedHasNoEffectiveHold();}
            else{
                sweepCheckJudgesArming();
                cutOutBetweenPollsKeepsTheCard();
                replugInsideWindowKeepsTheCard();
                unplugClears("just after a poll",0);
                unplugClears("just before a poll",StatusCadence.POLL_SLOW_MS-1500);
                noFreshReadingKeepsTheCard();
            }
            result.putString("result","PASS");
        }catch(Throwable error){result.putString("result","FAIL");result.putString("error",error.toString());error.printStackTrace();}
        finally{
            result.putInt("assertions",checks);result.putBoolean("physical_device_tested",false);result.putString("timeline",timeline.toString());
            finish("PASS".equals(result.getString("result"))?0:1,result);
        }
    }
    private void admit(){
        String hardware=Build.HARDWARE.toLowerCase(Locale.ROOT);
        require(hardware.contains("ranchu")||hardware.contains("goldfish")||Build.PRODUCT.startsWith("sdk_gphone"),"Physical device refused");
        for(String permission:new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT})
            require(getTargetContext().checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED,"Grant emulator permissions first");
        boolean notifications=getTargetContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
        require("notifications-off".equals(mode)!=notifications,"notifications-off runs without POST_NOTIFICATIONS, the default mode with it");
        require(getTargetContext().getSystemService(BluetoothManager.class).getAdapter()!=null,"The emulator has no Bluetooth adapter object");
    }

    /** Volt in front with a charge running, then Home: the owner's real path, through MainActivity.onPause. */
    private void leaveWhileCharging(String what){
        ensureFront();
        readChargingInFront();
        getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(()->onMain(()->!service.isVisible()),10000,"Volt never left the foreground");
        await(()->isCard(),20000,what+": the live card did not appear after leaving Volt");
        log(what+": left Volt, card showing: "+notice());
    }
    /** Coming back in front, the first 2 s poll waits out the 15 s one already queued, so allow one background poll. */
    private void readChargingInFront(){
        main(()->{link.charging(ON);link.home();});
        settle();
        await(()->onMain(()->ble.ready()&&Double.valueOf(1d).equals(ble.telemetry.number("charging_state"))),StatusCadence.POLL_SLOW_MS+10*S,"No fresh charging reading to arm on");
        // A couple of 2 s polls in front, so the armed reading is a fresh "charging".
        SystemClock.sleep(4*S);
    }
    private void ensureFront(){
        if(onMain(()->service.isVisible()))return;
        // Not startActivitySync: it waits for a new activity, and this brings the existing one back.
        getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));
        await(()->onMain(()->service.isVisible()),10000,"Volt never returned to the foreground");
        settle();
    }

    /** The last background read sent, then a moment for its reply to be handled. */
    private void awaitNextPoll(){
        long before=onMainLong(()->link.lastBatteryRead);
        await(()->onMainLong(()->link.lastBatteryRead)>before,StatusCadence.POLL_SLOW_MS+5*S,"No background battery read within one poll");
        SystemClock.sleep(150);
    }
    private long onMainLong(java.util.function.LongSupplier query){final long[] result={0};main(()->result[0]=query.getAsLong());return result[0];}

    /** A charger that cuts out and is back before the next poll reads it: no reading says off, so the card stays. */
    private void cutOutBetweenPollsKeepsTheCard(){
        leaveWhileCharging("cut-out");
        awaitNextPoll();
        main(()->link.charging(OFF));log("cut-out: off just after a poll");
        SystemClock.sleep(5*S);
        main(()->link.charging(ON));log("cut-out: back before the next poll");
        watch(40*S,"cut-out: back on");
        require(onMain(()->((ChargeLiveHold)field(service,"chargeLive")).active()),"The hold ended on a cut-out no reading saw");
    }
    /** The off reading arrives, then a report the scooter pushes says charging again inside the window. */
    private void replugInsideWindowKeepsTheCard(){
        leaveWhileCharging("replug inside");
        main(()->link.charging(OFF));log("replug inside: unplugged");
        await(()->onMain(()->Double.valueOf(0d).equals(ble.telemetry.number("charging_state"))),StatusCadence.POLL_SLOW_MS+5*S,"The off reading never arrived");
        log("replug inside: off reading seen");
        SystemClock.sleep(1500);
        main(()->{link.charging(ON);link.home();});log("replug inside: plugged back, report pushed");
        SystemClock.sleep(500);
        require(isCard(),"The card left inside the window: "+notice());
        watch(40*S,"replug inside: charging again");
        require(onMain(()->((ChargeLiveHold)field(service,"chargeLive")).active()),"The hold ended although charging came back inside the window");
    }
    /**
     * The reported bug: unplugged with Volt out of sight, the card goes back to the plain Connected notice within one
     * poll plus the unplug window (about 20 s at worst), with nothing bringing Volt to the front. After that the card doesn't
     * come back by itself on a replug: only leaving Volt again arms it.
     */
    private void unplugClears(String phase,long delayAfterPoll){
        leaveWhileCharging("unplug "+phase);
        awaitNextPoll();
        SystemClock.sleep(delayAfterPoll);
        long unplugged=SystemClock.elapsedRealtime();
        main(()->link.charging(OFF));log("unplug "+phase+": unplugged");
        long deadline=unplugged+StatusCadence.POLL_SLOW_MS+ChargeLiveHold.UNPLUG_MS+3*S;long cleared=0;
        while(SystemClock.elapsedRealtime()<deadline){
            if(!isCard()){cleared=SystemClock.elapsedRealtime();break;}
            SystemClock.sleep(100);
        }
        require(cleared!=0,"The card still shows "+((deadline-unplugged)/1000)+" s after unplugging, with Volt in the background: "+notice());
        log("unplug "+phase+": card cleared "+(cleared-unplugged)+" ms after unplugging: "+notice());
        require(cleared-unplugged>=ChargeLiveHold.UNPLUG_MS-S,"The card cleared before the window");
        SystemClock.sleep(3*S);
        require(!isCard()&&notice().contains("Synthetic"),"Expected the plain Connected notice, got: "+notice());
        require(onMain(()->!service.isVisible()),"Volt came to the front during the check");
        main(()->{link.charging(ON);link.home();});log("unplug "+phase+": plugged back in");
        SystemClock.sleep(20*S);
        require(!isCard()&&onMain(()->!((ChargeLiveHold)field(service,"chargeLive")).active()),"The hold came back on a replug with Volt closed: "+notice());
    }
    /** Connected, but the battery reads stop (charging null after FRESH_MS): a gap in the readings, not an unplug. */
    private void noFreshReadingKeepsTheCard(){
        leaveWhileCharging("gap");
        main(()->link.quiet=true);log("gap: readings stopped, status continues");
        watch(ChargeAlertPolicy.FRESH_MS+20*S,"gap: no fresh reading");
        require(onMain(()->((ChargeLiveHold)field(service,"chargeLive")).active()),"The hold ended during a reading gap");
        main(()->link.quiet=false);
    }
    /**
     * The sweep's own check (the same LiveChargingHoldCheck and Probe as ParkedValidation) with notifications off (the
     * permission withheld: a channel the app itself turned off comes straight back, because notification() recreates it):
     * the hold is armed internally but holds nothing, so the ordinary grace runs and the check doesn't fail the sweep.
     */
    private void notificationsBlockedHasNoEffectiveHold(){
        ensureFront();
        readChargingInFront();
        require(!onMain(()->service.chargeLiveAllowed()),"Notifications must be off for this mode");
        log("notifications off: chargeLiveAllowed false, leaving Volt");
        main(()->{
            settings().edit().putBoolean("background_connection",false).commit();
            LiveChargingHoldCheck.Observed observed=leaveAndObserve();
            String problem=LiveChargingHoldCheck.problem(observed);
            require(problem==null,"The sweep check failed: "+problem);
            require(observed.before.armable(),"Expected the arming conditions to be met; observed: "+LiveChargingHoldCheck.evidence(observed));
            require(observed.armed&&!observed.effective&&!observed.notificationsAllowed,"Expected armed, no effective hold, notifications blocked; observed: "+LiveChargingHoldCheck.evidence(observed));
            require(observed.graceDeadlineMs>observed.nowMs,"Expected the ordinary 10 s grace");
            log("blocked evidence: "+LiveChargingHoldCheck.evidence(observed));
            settings().edit().putBoolean("background_connection",true).commit();service.visible(true);
        });
    }

    /** As the sweep does it, on the main thread: Volt in front, the arming conditions read, then leaving. */
    private LiveChargingHoldCheck.Observed leaveAndObserve(){
        service.visible(true);
        try{LiveChargingHoldCheck.Conditions before=LiveChargingHoldProbe.conditions(service);service.visible(false);return LiveChargingHoldProbe.observe(service,before);}
        catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
    }
    /**
     * The sweep's check against the real service with notifications allowed: conditions met and armed passes; the same
     * leave with the hold then forced off (standing in for a hold that failed to arm) fails; Live charging off, so the
     * conditions were not met, stays unarmed and passes.
     */
    private void sweepCheckJudgesArming(){
        ensureFront();
        readChargingInFront();
        main(()->{
            settings().edit().putBoolean("background_connection",false).commit();
            LiveChargingHoldCheck.Observed armed=leaveAndObserve();
            require(armed.before.armable()&&armed.armed&&armed.effective,"Expected an armed, effective hold; observed: "+LiveChargingHoldCheck.evidence(armed));
            String problem=LiveChargingHoldCheck.problem(armed);
            require(problem==null,"The sweep check failed an armed hold: "+problem);
            log("armed evidence: "+LiveChargingHoldCheck.evidence(armed));
            ((ChargeLiveHold)field(service,"chargeLive")).shown();
            LiveChargingHoldCheck.Observed missing;
            try{missing=LiveChargingHoldProbe.observe(service,armed.before);}catch(ReflectiveOperationException error){throw new IllegalStateException(error);}
            String reported=LiveChargingHoldCheck.problem(missing);
            require(!missing.armed&&reported!=null&&reported.contains("did not arm"),"The sweep check missed a hold that failed to arm: "+reported);
            log("eligible but unarmed: "+reported);
            service.setChargeLiveEnabled(false);
            LiveChargingHoldCheck.Observed off=leaveAndObserve();
            require(!off.before.armable()&&!off.armed&&LiveChargingHoldCheck.problem(off)==null,"Live charging off must stay unarmed and pass; observed: "+LiveChargingHoldCheck.evidence(off));
            log("not armable: "+LiveChargingHoldCheck.evidence(off));
            service.setChargeLiveEnabled(true);
            settings().edit().putBoolean("background_connection",true).commit();service.visible(true);
        });
    }

    private void watch(long ms,String what){long end=SystemClock.elapsedRealtime()+ms,sample=0;while(SystemClock.elapsedRealtime()<end){require(isCard(),what+": the card left early: "+notice());if(SystemClock.elapsedRealtime()-sample>=30*S){sample=SystemClock.elapsedRealtime();log(what+": "+notice());}SystemClock.sleep(S);}}
    private void log(String line){timeline.append(SystemClock.elapsedRealtime()/1000).append("s ").append(line).append('\n');Bundle status=new Bundle();status.putString("progress",line);sendStatus(0,status);}
    private StatusBarNotification connection(){for(StatusBarNotification n:getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications())if(n.getId()==71)return n;return null;}
    private String notice(){StatusBarNotification n=connection();if(n==null)return "(no connection notice)";Bundle extras=n.getNotification().extras;return extras.getCharSequence(Notification.EXTRA_TITLE)+" | "+extras.getCharSequence(Notification.EXTRA_TEXT);}
    /** The card's title starts Charging or Last seen; the plain connected notice's doesn't. */
    private boolean isCard(){StatusBarNotification n=connection();if(n==null)return false;CharSequence title=n.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);return title!=null&&title.toString().startsWith("Charging");}

    private final class FakeLink {
        byte charging=ON;
        final Handler handler=new Handler(Looper.getMainLooper());
        BluetoothGatt gatt;BluetoothGattCharacteristic writer,notifier;BluetoothGattCallback callbacks;
        boolean quiet;long lastBatteryRead;
        void charging(byte value){charging=value;}
        void attach(){try{
            BluetoothDevice device=getTargetContext().getSystemService(BluetoothManager.class).getAdapter().getRemoteDevice(profile.mac);
            Class<?> unsafeType=Class.forName("sun.misc.Unsafe");Field unsafe=unsafeType.getDeclaredField("theUnsafe");unsafe.setAccessible(true);
            gatt=(BluetoothGatt)unsafeType.getMethod("allocateInstance",Class.class).invoke(unsafe.get(null),BluetoothGatt.class);
            Class<?> iface=Class.forName("android.bluetooth.IBluetoothGatt");
            InvocationHandler transport=(proxy,method,args)->{
                if(method.getDeclaringClass()==Object.class){if(method.getName().equals("equals"))return proxy==args[0];if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);return "LiveCardFixtureGatt";}
                if(method.getName().equals("writeCharacteristic")){
                    byte[] frame=null;if(args!=null)for(Object arg:args)if(arg instanceof byte[])frame=((byte[])arg).clone();
                    set(gatt,"mDeviceBusy",false);final byte[] packet=frame;
                    if(packet!=null)handler.postDelayed(()->received(packet),10);return 0;
                }
                Class<?> type=method.getReturnType();return type==int.class?0:type==boolean.class?false:type==long.class?0L:null;
            };
            set(gatt,"mService",Proxy.newProxyInstance(iface.getClassLoader(),new Class<?>[]{iface},transport));
            set(gatt,"mClientRegistered",true);set(gatt,"mDeviceBusy",false);set(gatt,"mDeviceBusyLock",new Object());set(gatt,"mStateLock",new Object());set(gatt,"mDevice",device);set(gatt,"mServices",new CopyOnWriteArrayList<BluetoothGattService>());
            BluetoothGattService scooter=new BluetoothGattService(BleController.SERVICE,BluetoothGattService.SERVICE_TYPE_PRIMARY);set(scooter,"mDevice",device);
            writer=new BluetoothGattCharacteristic(BleController.WRITE,BluetoothGattCharacteristic.PROPERTY_WRITE,BluetoothGattCharacteristic.PERMISSION_WRITE);
            notifier=new BluetoothGattCharacteristic(BleController.NOTIFY,BluetoothGattCharacteristic.PROPERTY_NOTIFY,0);scooter.addCharacteristic(writer);scooter.addCharacteristic(notifier);
            callbacks=(BluetoothGattCallback)field(ble,"callbacks");set(ble,"profile",profile);set(ble,"gatt",gatt);set(ble,"writer",writer);set(ble,"serial",profile.serial);
            set(ble,"authenticated",true);set(ble,"discovered",true);set(ble,"subscribed",true);set(ble,"wanted",false);set(ble,"state",BleController.State.READY);
            status();home();battery();
            // The real poll loop: 2 s in sight, 15 s out of it, and the battery reads only while something watches them.
            Method poll=BleController.class.getDeclaredMethod("poll",int.class);poll.setAccessible(true);poll.invoke(ble,ble.connectionGeneration());
        }catch(Exception error){throw new IllegalStateException("Synthetic GATT unavailable; relax hidden_api_policy on the disposable emulator",error);}}
        void received(byte[] frame){
            callbacks.onCharacteristicWrite(gatt,writer,0);
            if(frame.length<4)return;
            switch(frame[3]&255){case 0x70:status();break;case 0x72:lastBatteryRead=SystemClock.elapsedRealtime();if(!quiet)battery();break;default:break;}
        }
        void status(){byte[] state=new byte[44];state[1]=3;state[3]=1;state[6]=2;state[7]=1;state[19]=3;state[25]=50;state[31]=100;state[34]=3;packet(0x70,state);}
        void home(){byte[] data=new byte[12];data[1]=1;data[2]=62;data[4]=charging;packet(0x90,data);}
        void battery(){byte[] data=new byte[24];data[1]=62;data[12]=charging;packet(0x72,data);}
        void packet(int cmd,byte[] payload){
            byte[] packet=new byte[payload.length+9];packet[0]=0x55;packet[1]=(byte)0xaa;packet[3]=(byte)cmd;packet[4]=(byte)(payload.length+1);
            System.arraycopy(payload,0,packet,6,payload.length);int sum=0;for(int i=0;i<packet.length-3;i++)sum+=packet[i]&255;
            packet[packet.length-3]=(byte)sum;packet[packet.length-2]=(byte)0xfe;packet[packet.length-1]=(byte)0xfd;
            handler.postDelayed(()->callbacks.onCharacteristicChanged(gatt,notifier,packet),10);
        }
    }
    private SharedPreferences settings(){return getTargetContext().getSharedPreferences("app_settings",android.content.Context.MODE_PRIVATE);}
    private void require(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private void main(Runnable task){final Throwable[] failure={null};runOnMainSync(()->{try{task.run();}catch(Throwable error){failure[0]=error;}});if(failure[0]!=null)throw new IllegalStateException(failure[0]);}
    private boolean onMain(BooleanSupplier query){final boolean[] result={false};main(()->result[0]=query.getAsBoolean());return result[0];}
    private void await(BooleanSupplier query,long ms,String message){long end=SystemClock.uptimeMillis()+ms;while(SystemClock.uptimeMillis()<end){if(query.getAsBoolean())return;SystemClock.sleep(50);}throw new AssertionError(message);}
    private void settle(){SystemClock.sleep(350);waitForIdleSync();}
    private static Field reflected(Object object,String name){Class<?> type=object.getClass();while(type!=null){try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException missing){type=type.getSuperclass();}}throw new IllegalStateException("Missing field "+name);}
    private static Object field(Object object,String name){try{return reflected(object,name).get(object);}catch(Exception error){throw new IllegalStateException(error);}}
    private static void set(Object object,String name,Object value){try{reflected(object,name).set(object,value);}catch(Exception error){throw new IllegalStateException(error);}}
}
