/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.mozstumbler.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Registered as a receiver in manifest. Starts the StumblerService in passive listening mode.
 * Using GPS_* event changes during development, switch to using the existing permissions for a
 * service on Fennec.
 <meta-data name="MOZILLA_API_KEY" value="aValue">
 <receiver android:name=".service.PassiveServiceReceiver">
 <intent-filter>
 <action android:name="android.location.GPS_ENABLED_CHANGE" />
 <action android:name="android.location.GPS_FIX_CHANGE" />
 </intent-filter>
 </receiver>
 */
public class PassiveServiceReceiver extends BroadcastReceiver {
    final static String LOGTAG = PassiveServiceReceiver.class.getName();

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

       if (SharedConstants.isDebug) Log.d(LOGTAG, "Starting Passively");
        if (SharedConstants.mozillaApiKey == null) {
            try {
                ApplicationInfo ai = context.getPackageManager().
                        getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
                Bundle aBundle = ai.metaData;
                if (ai.metaData != null) {
                    SharedConstants.mozillaApiKey = aBundle.getString("MOZILLA_API_KEY");
                }
            } catch (PackageManager.NameNotFoundException ex) {
                Log.d(SharedConstants.appName, "Exception getting service upload key");
            }
        }

        Intent startServiceIntent = new Intent(context, StumblerService.class);
        startServiceIntent.putExtra(StumblerService.ACTION_START_PASSIVE, true);
        context.startService(startServiceIntent);
    }
}

