/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.mozstumbler.service.scanners;

import android.content.Context;
import android.content.Intent;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Bundle;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import org.mozilla.mozstumbler.service.SharedConstants;
import org.mozilla.mozstumbler.service.SharedConstants.ActiveOrPassiveStumbling;
import org.mozilla.mozstumbler.service.Prefs;
import org.mozilla.mozstumbler.service.Scanner;
import java.text.SimpleDateFormat;

public class GPSScanner implements LocationListener {
    public static final String ACTION_BASE = SharedConstants.ACTION_NAMESPACE + ".GPSScanner.";
    public static final String ACTION_GPS_UPDATED = ACTION_BASE + "GPS_UPDATED";
    public static final String ACTION_ARG_TIME = SharedConstants.ACTION_ARG_TIME;
    public static final String SUBJECT_NEW_STATUS = "new_status";
    public static final String SUBJECT_LOCATION_LOST = "location_lost";
    public static final String SUBJECT_NEW_LOCATION = "new_location";
    public static final String NEW_STATUS_ARG_FIXES = "fixes";
    public static final String NEW_STATUS_ARG_SATS = "sats";
    public static final String NEW_LOCATION_ARG_LOCATION = "location";

    private static final String   LOGTAG                  = GPSScanner.class.getName();
    private static final long     GEO_MIN_UPDATE_TIME     = 1000;
    private static final float    GEO_MIN_UPDATE_DISTANCE = 10;
    private static final int      MIN_SAT_USED_IN_FIX     = 3;

    private final Context         mContext;
    private GpsStatus.Listener    mGPSListener;

    private int mLocationCount;
    private double mLatitude;
    private double mLongitude;
    private LocationBlockList mBlockList = new LocationBlockList();
    private boolean mAutoGeofencing;
    private boolean mIsPassiveMode;

    private Scanner mScanner;

    public GPSScanner(Context context, Scanner scanner) {
        mContext = context;
        mScanner = scanner;
    }

    public void start(final ActiveOrPassiveStumbling stumblingMode) {
        mIsPassiveMode = (stumblingMode == ActiveOrPassiveStumbling.PASSIVE_STUMBLING);
        if (mIsPassiveMode ) {
            startPassiveMode();
        } else {
            startActiveMode();
        }
    }

    private void startPassiveMode() {
        LocationManager locationManager = (LocationManager)mContext.getSystemService(Context.LOCATION_SERVICE);
        locationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0, 0, this);
    }

    private void startActiveMode() {
        LocationManager lm = getLocationManager();
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                                  GEO_MIN_UPDATE_TIME,
                                  GEO_MIN_UPDATE_DISTANCE,
                                  this);

        reportLocationLost();
        mGPSListener = new GpsStatus.Listener() {
                public void onGpsStatusChanged(int event) {
                    if (event == GpsStatus.GPS_EVENT_SATELLITE_STATUS) {
                        GpsStatus status = getLocationManager().getGpsStatus(null);
                        Iterable<GpsSatellite> sats = status.getSatellites();

                        int satellites = 0;
                        int fixes = 0;

                        for (GpsSatellite sat : sats) {
                            satellites++;
                            if(sat.usedInFix()) {
                                fixes++;
                            }
                        }
                        reportNewGpsStatus(fixes,satellites);
                        if (fixes < MIN_SAT_USED_IN_FIX) {
                            reportLocationLost();
                        }
                        if (SharedConstants.isDebug) Log.d(LOGTAG, "onGpsStatusChange - satellites: " + satellites + " fixes: " + fixes);
                    } else if (event == GpsStatus.GPS_EVENT_STOPPED) {
                        reportLocationLost();
                    }
                }
            };

        lm.addGpsStatusListener(mGPSListener);
    }

    public void stop() {
        LocationManager lm = getLocationManager();
        lm.removeUpdates(this);
        reportLocationLost();

        if (mGPSListener != null) {
          lm.removeGpsStatusListener(mGPSListener);
          mGPSListener = null;
        }
    }

    public int getLocationCount() {
        return mLocationCount;
    }

    public double getLatitude() {
        return mLatitude;
    }

    public double getLongitude() {
        return mLongitude;
    }

    public void checkPrefs() {
        if (mBlockList!=null) mBlockList.update_blocks();

        mAutoGeofencing = Prefs.getInstance().getGeofenceHere();
    }

    public boolean isGeofenced() {
        return (mBlockList != null) && mBlockList.isGeofenced();
    }

    private void sendToLogActivity(String msg) {
        if (SharedConstants.guiLogMessageBuffer != null)
            SharedConstants.guiLogMessageBuffer.add("<font color='#33ccff'>" + msg + "</font>");
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null) { // TODO: is this even possible??
            reportLocationLost();
            return;
        }

        String logMsg = (mIsPassiveMode)? "[Passive] " : "[Active] ";

        String provider = location.getProvider();
        if (!provider.toLowerCase().contains("gps")) {
            sendToLogActivity(logMsg + "Discard fused/network location.");
            // only interested in GPS locations
            return;
        }

        java.util.Date date = new java.util.Date(location.getTime());
        SimpleDateFormat formatter = new SimpleDateFormat("HH:mm:ss");
        String time = formatter.format(date);
        logMsg += String.format("%s Coord: %.4f,%.4f, Acc: %.0f, Speed: %.0f, Alt: %.0f, Bearing: %.1f", time, location.getLatitude(),
                location.getLongitude(), location.getAccuracy(), location.getSpeed(), location.getAltitude(), location.getBearing());
        sendToLogActivity(logMsg);

        if (mBlockList.contains(location)) {
            Log.w(LOGTAG, "Blocked location: " + location);
            reportLocationLost();
            return;
        }

        if (SharedConstants.isDebug) Log.d(LOGTAG, "New location: " + location);

        mLongitude = location.getLongitude();
        mLatitude = location.getLatitude();

        if (!mAutoGeofencing) { reportNewLocationReceived(location); }
        mLocationCount++;

        if (mIsPassiveMode) {
            mScanner.newPassiveGpsLocation();
        }
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            reportLocationLost();
        }
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        if ((status != LocationProvider.AVAILABLE)
                && (LocationManager.GPS_PROVIDER.equals(provider))) {
            reportLocationLost();
        }
    }

    private LocationManager getLocationManager() {
        return (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
    }

    private void reportNewLocationReceived(Location location) {
        Intent i = new Intent(ACTION_GPS_UPDATED);
        i.putExtra(Intent.EXTRA_SUBJECT, SUBJECT_NEW_LOCATION);
        i.putExtra(NEW_LOCATION_ARG_LOCATION, location);
        i.putExtra(ACTION_ARG_TIME, System.currentTimeMillis());
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(i);
    }

    private void reportLocationLost() {
        Intent i = new Intent(ACTION_GPS_UPDATED);
        i.putExtra(Intent.EXTRA_SUBJECT, SUBJECT_LOCATION_LOST);
        i.putExtra(ACTION_ARG_TIME, System.currentTimeMillis());
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(i);
    }

    private void reportNewGpsStatus(int fixes, int sats) {
        Intent i = new Intent(ACTION_GPS_UPDATED);
        i.putExtra(Intent.EXTRA_SUBJECT, SUBJECT_NEW_STATUS);
        i.putExtra(NEW_STATUS_ARG_FIXES, fixes);
        i.putExtra(NEW_STATUS_ARG_SATS, sats);
        i.putExtra(ACTION_ARG_TIME, System.currentTimeMillis());
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(i);
    }
}
