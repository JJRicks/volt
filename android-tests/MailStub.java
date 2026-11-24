package com.jjricks.volt.tests;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * ChargingWordingValidation's stand-in email app (-e mode invite): one more app that takes an email (mailto, or a shared
 * file), so Android always asks which app takes Volt's email, also where the emulator's one email app would otherwise
 * open directly. If it is ever opened it closes itself a moment later. Disabled in the manifest; the validation enables
 * it for its one step with pm and disables it again, so no other validation ever sees it.
 */
public final class MailStub extends Activity {
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle("Stand-in email app");
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 1500);
    }
}
