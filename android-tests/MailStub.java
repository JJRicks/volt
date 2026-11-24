package com.jjricks.volt.tests;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * ChargingWordingValidation's stand-in email app (-e mode invite): an opaque screen that takes a mailto email, as a real
 * email app's compose screen would, and closes itself a moment later. Disabled in the manifest; the validation enables
 * it for its one step with pm and disables it again, so no other validation ever sees an email app.
 */
public final class MailStub extends Activity {
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle("Stand-in email app");
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 1500);
    }
}
