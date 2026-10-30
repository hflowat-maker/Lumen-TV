package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Wakes the :home process when the TV boots or the app is updated, so the iPhone remote answers even before the home
 * screen has been shown (after an update while watching something, say). Receiving it starts the process; App starts
 * the server.
 */
public class RemoteReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Remote.start(context);
    }
}
