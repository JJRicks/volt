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
 * six minutes: it waits out ChargeLiveHold.STOP_MS for real, because a short clock is what the unit tests are for.
 *
 * Unplug out of sight, Volt in the background the whole time: the card stays through the hold, then returns to plain Connected.
 * A dropout that ends inside the hold, and a replug during it, both keep the card.
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
    @Override public void onCreate(Bundle args){start();}
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
            dropoutInsideHold();
            replugDuringHold();
            unplugClearsAfterHold();
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
        for(String permission:new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.POST_NOTIFICATIONS})
            require(getTargetContext().checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED,"Grant emulator permissions first");
        require(getTargetContext().getSystemService(BluetoothManager.class).getAdapter()!=null,"The emulator has no Bluetooth adapter object");
    }

    /** Volt in front with a charge running, then Home: the owner's real path, through MainActivity.onPause. */
    private void leaveWhileCharging(String what){
        ensureFront();
        main(()->link.charging(ON));
        settle();
        // Three 2 s polls in front, so the armed reading is a fresh "charging".
        SystemClock.sleep(6*S);
        await(()->onMain(()->ble.ready()&&Double.valueOf(1d).equals(ble.telemetry.number("charging_state"))),10000,"No fresh charging reading to arm on");
        getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(()->onMain(()->!service.isVisible()),10000,"Volt never left the foreground");
        await(()->isCard(),20000,what+": the live card did not appear after leaving Volt");
        log(what+": left Volt, card showing: "+notice());
    }
    private void ensureFront(){
        if(onMain(()->service.isVisible()))return;
        // Not startActivitySync: it waits for a new activity, and this brings the existing one back.
        getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));
        await(()->onMain(()->service.isVisible()),10000,"Volt never returned to the foreground");
        settle();
    }

    /** A charger that cuts out for 40 s and resumes, inside the hold: the card never leaves, and the hold stays armed. */
    private void dropoutInsideHold(){
        leaveWhileCharging("dropout");
        main(()->link.charging(OFF));log("dropout: charger off");
        watch(40*S,"dropout: charger off");
        main(()->link.charging(ON));log("dropout: charger back");
        watch(40*S,"dropout: charger back");
        require(isCard(),"The card left after a brief dropout: "+notice());
        require(onMain(()->((ChargeLiveHold)field(service,"chargeLive")).active()),"The hold ended on a brief dropout");
        log("dropout: card kept: "+notice());
    }
    /** The charger off for most of the hold, then plugged back in before it ends: the card stays. */
    private void replugDuringHold(){
        leaveWhileCharging("replug");
        main(()->link.charging(OFF));log("replug: unplugged");
        watch(ChargeLiveHold.STOP_MS-45*S,"replug: unplugged");
        main(()->link.charging(ON));log("replug: plugged back in");
        watch(45*S,"replug: plugged back in");
        require(isCard(),"The card left after a replug inside the hold: "+notice());
        log("replug: card kept: "+notice());
    }
    /**
     * The reported bug: unplugged with Volt out of sight, the card must stay through the hold (ChargeLiveHold.STOP_MS)
     * and then go back to the plain Connected notice, with nothing bringing Volt to the front.
     */
    private void unplugClearsAfterHold(){
        leaveWhileCharging("unplug");
        long unplugged=SystemClock.elapsedRealtime();
        main(()->link.charging(OFF));log("unplug: unplugged");
        watch(ChargeLiveHold.STOP_MS-30*S,"unplug: inside the hold");
        require(isCard(),"The card left before the hold ended: "+notice());
        long deadline=unplugged+ChargeLiveHold.STOP_MS+StatusCadence.POLL_SLOW_MS+10*S;long cleared=0;
        while(SystemClock.elapsedRealtime()<deadline){
            if(!isCard()){cleared=SystemClock.elapsedRealtime();break;}
            SystemClock.sleep(S);
        }
        log("unplug: end of wait: "+notice());
        require(cleared!=0,"BUG REPRODUCED: the card still shows "+((deadline-unplugged)/1000)+" s after unplugging, with Volt in the background: "+notice());
        log("unplug: card cleared "+((cleared-unplugged)/1000)+" s after unplugging: "+notice());
        require(cleared-unplugged>ChargeLiveHold.STOP_MS-S,"The card cleared before the hold ended");
        SystemClock.sleep(3*S);
        require(!isCard()&&notice().contains("Synthetic"),"Expected the plain Connected notice, got: "+notice());
        require(onMain(()->!service.isVisible()),"Volt came to the front during the check");
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
        void charging(byte value){charging=value;home();}
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
            switch(frame[3]&255){case 0x70:status();break;case 0x72:battery();break;default:break;}
        }
        void status(){byte[] state=new byte[44];state[1]=3;state[3]=1;state[6]=2;state[7]=1;state[19]=3;state[25]=50;state[31]=100;state[34]=3;packet(0x70,state);}
        void home(){byte[] data=new byte[12];data[1]=1;data[2]=62;data[4]=charging;packet(0x90,data);}
        void battery(){byte[] data=new byte[24];data[12]=charging;packet(0x72,data);}
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
