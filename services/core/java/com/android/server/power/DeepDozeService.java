/*
 * Copyright (C) 2026 Project ASCP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.power;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.usage.UsageStatsManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.hardware.SensorPrivacyManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.nfc.NfcAdapter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Telephony;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.util.ArrayUtils;
import com.android.server.DeviceIdleInternal;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.usage.AppStandbyInternal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class DeepDozeService extends SystemService {

    private static final String TAG = "DeepDozeService";
    private static final boolean DEBUG = true;

    private static final int MAX_DISTURBANCE_LOG_ENTRIES = 50;
    private static final long PRE_ALARM_WARM_WINDOW_MS = 3 * 60 * 1000L;
    private static final long REPEAT_CALL_WINDOW_MS = 15 * 60 * 1000L;

    private static final String ACTION_PRE_ALARM_WAKE = "com.android.server.power.action.DEEP_DOZE_PRE_ALARM_WAKE";

    private final Context mContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private Handler mBgHandler;
    private HandlerThread mBgThread;

    private DeviceIdleInternal mDeviceIdleInternal;
    private ActivityManager mActivityManager;
    private AlarmManager mAlarmManager;
    private AudioManager mAudioManager;
    private WifiManager mWifiManager;
    private BluetoothAdapter mBluetoothAdapter;
    private TelephonyManager mTelephonyManager;
    private LocationManager mLocationManager;
    private SensorPrivacyManager mSensorPrivacyManager;
    private AppStandbyInternal mAppStandbyInternal;
    private NfcAdapter mNfcAdapter;

    private boolean mDeepDozeEnabled = false;
    private boolean mPreAlarmWarmingEnabled = true;
    private boolean mRepeatCallersEnabled = true;
    private int mScheduleMode = 0;

    private volatile boolean mIsDeepDozeActive = false;
    private volatile boolean mIsInCall = false;

    private boolean mPrevWifiState = false;
    private boolean mPrevBluetoothState = false;
    private boolean mPrevCellularState = false;

    private int mPrevWifiScanAlways = 0;
    private int mPrevBleScanAlways = 0;
    private boolean mPrevLocationEnabled = false;
    private boolean mPrevNfcEnabled = false;
    private int mPrevDozeAlwaysOn = 0;
    private int mPrevDozeEnabled = 1;
    private boolean mPrevMasterSync = false;
    private boolean mPrevSensorPrivacy = false;
    private final Set<String> mDemotedPackages = new HashSet<>();

    private int mCurrentBatteryLevel = -1;

    private final ConcurrentHashMap<String, Long> mRecentCalls = new ConcurrentHashMap<>();

    private final Object mLock = new Object();

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                if (DEBUG) Slog.d(TAG, "Screen OFF received");
                mBgHandler.post(() -> handleScreenOff());
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                if (DEBUG) Slog.d(TAG, "Screen ON received");
                mBgHandler.post(() -> handleScreenOn());
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) {
                    mCurrentBatteryLevel = (int) ((level / (float) scale) * 100);
                }
            } else if (ACTION_PRE_ALARM_WAKE.equals(action)) {
                Slog.i(TAG, "Pre-alarm network warming triggered");
                mBgHandler.post(() -> handlePreAlarmWarming());
            }
        }
    };

    private final class CallStateTracker extends TelephonyCallback
            implements TelephonyCallback.CallStateListener,
                       TelephonyCallback.OutgoingEmergencyCallListener {

        @Override
        public void onCallStateChanged(int state) {
            if (DEBUG) Slog.d(TAG, "onCallStateChanged: " + state);
            if (state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK) {
                mBgHandler.post(() -> handleIncomingOrActiveCall());
            } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                mBgHandler.post(() -> handleCallEnded());
            }
        }

        @Override
        public void onOutgoingEmergencyCall(EmergencyNumber placedEmergencyNumber, int subscriptionId) {
            Slog.w(TAG, "Outgoing emergency call detected! Aborting Deep Doze immediately.");
            mBgHandler.post(() -> handleEmergencyCall());
        }
    }

    private final CallStateTracker mCallStateTracker = new CallStateTracker();

    public DeepDozeService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        LocalServices.addService(DeepDozeService.class, this);
        mBgThread = new HandlerThread("DeepDozeBg");
        mBgThread.start();
        mBgHandler = new Handler(mBgThread.getLooper());
        Slog.i(TAG, "DeepDozeService started");
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            mDeviceIdleInternal = LocalServices.getService(DeviceIdleInternal.class);
            mActivityManager = mContext.getSystemService(ActivityManager.class);
            mAlarmManager = mContext.getSystemService(AlarmManager.class);
            mAudioManager = mContext.getSystemService(AudioManager.class);
            mWifiManager = mContext.getSystemService(WifiManager.class);
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
            mLocationManager = mContext.getSystemService(LocationManager.class);
            mSensorPrivacyManager = SensorPrivacyManager.getInstance(mContext);
            mAppStandbyInternal = LocalServices.getService(AppStandbyInternal.class);
            mNfcAdapter = NfcAdapter.getDefaultAdapter(mContext);

            if (mTelephonyManager != null) {
                mTelephonyManager.registerTelephonyCallback(mContext.getMainExecutor(), mCallStateTracker);
            }
        } else if (phase == PHASE_BOOT_COMPLETED) {
            registerSettingsObserver();
            registerReceivers();
            updateSettings();
            Slog.i(TAG, "DeepDozeService boot completed, enabled=" + mDeepDozeEnabled);
        }
    }

    private void registerSettingsObserver() {
        ContentObserver observer = new ContentObserver(mMainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                updateSettings();
            }
        };
        ContentResolver cr = mContext.getContentResolver();
        cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.DEEP_DOZE_ENABLED),
                false, observer, UserHandle.USER_ALL);
        cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.DEEP_DOZE_PRE_ALARM_WARMING),
                false, observer, UserHandle.USER_ALL);
        cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.DEEP_DOZE_REPEAT_CALLERS),
                false, observer, UserHandle.USER_ALL);
        cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.DEEP_DOZE_SCHEDULE_MODE),
                false, observer, UserHandle.USER_ALL);
    }

    private void registerReceivers() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(ACTION_PRE_ALARM_WAKE);
        mContext.registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
    }

    private void updateSettings() {
        ContentResolver cr = mContext.getContentResolver();
        mDeepDozeEnabled = Settings.Secure.getIntForUser(cr,
                Settings.Secure.DEEP_DOZE_ENABLED, 0, UserHandle.USER_CURRENT) == 1;
        mPreAlarmWarmingEnabled = Settings.Secure.getIntForUser(cr,
                Settings.Secure.DEEP_DOZE_PRE_ALARM_WARMING, 1, UserHandle.USER_CURRENT) == 1;
        mRepeatCallersEnabled = Settings.Secure.getIntForUser(cr,
                Settings.Secure.DEEP_DOZE_REPEAT_CALLERS, 1, UserHandle.USER_CURRENT) == 1;
        mScheduleMode = Settings.Secure.getIntForUser(cr,
                Settings.Secure.DEEP_DOZE_SCHEDULE_MODE, 0, UserHandle.USER_CURRENT);

        if (!mDeepDozeEnabled && mIsDeepDozeActive) {
            mBgHandler.post(() -> handleScreenOn());
        }
    }

    public boolean isDeepDozeActive() {
        return mIsDeepDozeActive;
    }

    private boolean isNightSleepTime() {
        if (mScheduleMode == 0) {
            return true;
        }
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 23 || hour < 7;
    }

    private TelephonyManager getTelephonyManager() {
        int subscriptionId = SubscriptionManager.getDefaultDataSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
            int[] activeSubIds = SubscriptionManager.from(mContext).getActiveSubscriptionIdList();
            if (!ArrayUtils.isEmpty(activeSubIds)) {
                subscriptionId = activeSubIds[0];
            }
        }
        return mContext.getSystemService(TelephonyManager.class).createForSubscriptionId(subscriptionId);
    }

    private void handleScreenOff() {
        synchronized (mLock) {
            if (!mDeepDozeEnabled || mIsDeepDozeActive || mIsInCall) {
                return;
            }

            if (!isNightSleepTime()) {
                Slog.d(TAG, "Screen off outside night sleep window, skipping deep doze");
                return;
            }

            Slog.i(TAG, "Entering aggressive Deep Doze mode for sleep");

            final long startTimeMs = System.currentTimeMillis();
            final long startUptimeMs = SystemClock.elapsedRealtime();
            final int startBattery = getBatteryLevel();
            final ContentResolver cr = mContext.getContentResolver();

            try {
                if (mWifiManager != null) {
                    mPrevWifiState = mWifiManager.isWifiEnabled();
                    if (mPrevWifiState) {
                        mWifiManager.setWifiEnabled(false);
                    }
                }
            } catch (Exception e) {
                Slog.e(TAG, "Error disabling Wi-Fi", e);
            }

            try {
                if (mBluetoothAdapter != null) {
                    mPrevBluetoothState = mBluetoothAdapter.isEnabled();
                    if (mPrevBluetoothState) {
                        mBluetoothAdapter.disable();
                    }
                }
            } catch (Exception e) {
                Slog.e(TAG, "Error disabling Bluetooth", e);
            }

            try {
                TelephonyManager tm = getTelephonyManager();
                if (tm != null) {
                    mPrevCellularState = tm.isDataEnabled();
                    if (mPrevCellularState) {
                        tm.setDataEnabled(false);
                    }
                }
            } catch (Exception e) {
                Slog.e(TAG, "Error disabling Mobile Data", e);
            }

            try {
                mPrevWifiScanAlways = Settings.Global.getInt(cr, Settings.Global.WIFI_SCAN_ALWAYS_AVAILABLE, 0);
                mPrevBleScanAlways = Settings.Global.getInt(cr, Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE, 0);
                if (mPrevWifiScanAlways != 0) {
                    Settings.Global.putInt(cr, Settings.Global.WIFI_SCAN_ALWAYS_AVAILABLE, 0);
                }
                if (mPrevBleScanAlways != 0) {
                    Settings.Global.putInt(cr, Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE, 0);
                }
            } catch (Exception e) {
                Slog.e(TAG, "Error disabling background Wi-Fi/BLE scanning", e);
            }

            try {
                if (mLocationManager != null) {
                    int currentUserId = ActivityManager.getCurrentUser();
                    mPrevLocationEnabled = mLocationManager.isLocationEnabledForUser(UserHandle.of(currentUserId));
                    if (mPrevLocationEnabled) {
                        mLocationManager.setLocationEnabledForUser(false, UserHandle.of(currentUserId));
                    }
                }
            } catch (Exception e) {
                Slog.e(TAG, "Error disabling location", e);
            }

            try {
                mPrevDozeAlwaysOn = Settings.Secure.getIntForUser(cr, Settings.Secure.DOZE_ALWAYS_ON, 0, UserHandle.USER_CURRENT);
                mPrevDozeEnabled = Settings.Secure.getIntForUser(cr, Settings.Secure.DOZE_ENABLED, 1, UserHandle.USER_CURRENT);
                if (mPrevDozeAlwaysOn != 0) {
                    Settings.Secure.putIntForUser(cr, Settings.Secure.DOZE_ALWAYS_ON, 0, UserHandle.USER_CURRENT);
                }
                if (mPrevDozeEnabled != 0) {
                    Settings.Secure.putIntForUser(cr, Settings.Secure.DOZE_ENABLED, 0, UserHandle.USER_CURRENT);
                }
            } catch (Exception e) {
                Slog.e(TAG, "Error disabling AOD/ambient display", e);
            }

            try {
                mPrevMasterSync = ContentResolver.getMasterSyncAutomatically();
                if (mPrevMasterSync) {
                    ContentResolver.setMasterSyncAutomatically(false);
                }
            } catch (Exception e) {
                Slog.e(TAG, "Error pausing master sync", e);
            }

            try {
                if (mNfcAdapter == null) {
                    mNfcAdapter = NfcAdapter.getDefaultAdapter(mContext);
                }
                if (mNfcAdapter != null && mNfcAdapter.isEnabled()) {
                    mPrevNfcEnabled = true;
                    mNfcAdapter.disable();
                } else {
                    mPrevNfcEnabled = false;
                }
            } catch (Exception e) {
                Slog.e(TAG, "Error disabling NFC", e);
            }

            try {
                if (mSensorPrivacyManager != null) {
                    mPrevSensorPrivacy = mSensorPrivacyManager.isAllSensorPrivacyEnabled();
                    if (!mPrevSensorPrivacy) {
                        mSensorPrivacyManager.setAllSensorPrivacy(true);
                    }
                }
            } catch (Exception e) {
                Slog.e(TAG, "Error enabling sensor privacy", e);
            }

            enforceAppRestrictionsLocked();

            schedulePreAlarmWarmingLocked();

            if (mDeviceIdleInternal != null) {
                mDeviceIdleInternal.forceDeepIdle(true);
            }

            final long entryDurationMs = SystemClock.elapsedRealtime() - startUptimeMs;
            mIsDeepDozeActive = true;

            Settings.Secure.putIntForUser(cr, Settings.Secure.DEEP_DOZE_IS_ACTIVE, 1, UserHandle.USER_CURRENT);
            Settings.Secure.putLongForUser(cr, Settings.Secure.DEEP_DOZE_START_TIME, startTimeMs, UserHandle.USER_CURRENT);
            Settings.Secure.putLongForUser(cr, Settings.Secure.DEEP_DOZE_ENTRY_DURATION_MS, entryDurationMs, UserHandle.USER_CURRENT);
            Settings.Secure.putIntForUser(cr, Settings.Secure.DEEP_DOZE_START_BATTERY, startBattery, UserHandle.USER_CURRENT);

            Slog.i(TAG, "Entered Deep Doze in " + entryDurationMs + " ms, startBattery=" + startBattery + "%");
        }
    }

    private void enforceAppRestrictionsLocked() {
        try {
            mDemotedPackages.clear();
            PackageManager pm = mContext.getPackageManager();
            if (pm == null) return;
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.MATCH_ALL);
            int currentUserId = ActivityManager.getCurrentUser();
            for (ApplicationInfo app : apps) {
                if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    continue;
                }
                if (isWhitelisted(app.packageName, app.uid, null)) {
                    continue;
                }

                if (mActivityManager != null) {
                    mActivityManager.killBackgroundProcesses(app.packageName);
                }

                if (mAppStandbyInternal != null) {
                    int bucket = mAppStandbyInternal.getAppStandbyBucket(app.packageName, currentUserId,
                            SystemClock.elapsedRealtime(), false);
                    if (bucket < UsageStatsManager.STANDBY_BUCKET_RESTRICTED) {
                        mAppStandbyInternal.setAppStandbyBucket(app.packageName,
                                UsageStatsManager.STANDBY_BUCKET_RESTRICTED, currentUserId,
                                Process.myUid(), Process.myPid());
                        mDemotedPackages.add(app.packageName);
                    }
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error enforcing app standby and freezer restrictions", e);
        }
    }

    private void handleScreenOn() {
        synchronized (mLock) {
            if (!mIsDeepDozeActive) {
                return;
            }

            mIsDeepDozeActive = false;
            final long endTimeMs = System.currentTimeMillis();
            final int endBattery = getBatteryLevel();

            Slog.i(TAG, "Exiting Deep Doze mode, endBattery=" + endBattery + "%");

            cancelPreAlarmWarmingLocked();

            if (mDeviceIdleInternal != null) {
                mDeviceIdleInternal.forceDeepIdle(false);
            }

            restoreSuppressorsLocked(true);

            ContentResolver cr = mContext.getContentResolver();
            Settings.Secure.putIntForUser(cr, Settings.Secure.DEEP_DOZE_IS_ACTIVE, 0, UserHandle.USER_CURRENT);
            Settings.Secure.putLongForUser(cr, Settings.Secure.DEEP_DOZE_END_TIME, endTimeMs, UserHandle.USER_CURRENT);
            Settings.Secure.putIntForUser(cr, Settings.Secure.DEEP_DOZE_END_BATTERY, endBattery, UserHandle.USER_CURRENT);
        }
    }

    private void restoreRadiosLocked() {
        try {
            if (mPrevWifiState && mWifiManager != null) {
                mWifiManager.setWifiEnabled(true);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error restoring Wi-Fi", e);
        }

        try {
            if (mPrevBluetoothState && mBluetoothAdapter != null) {
                mBluetoothAdapter.enable();
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error restoring Bluetooth", e);
        }

        try {
            if (mPrevCellularState) {
                TelephonyManager tm = getTelephonyManager();
                if (tm != null) {
                    tm.setDataEnabled(true);
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error restoring Mobile Data", e);
        }
    }

    private void restoreSuppressorsLocked(boolean fullRestore) {
        restoreRadiosLocked();

        try {
            ContentResolver cr = mContext.getContentResolver();
            if (mPrevWifiScanAlways != 0) {
                Settings.Global.putInt(cr, Settings.Global.WIFI_SCAN_ALWAYS_AVAILABLE, mPrevWifiScanAlways);
            }
            if (mPrevBleScanAlways != 0) {
                Settings.Global.putInt(cr, Settings.Global.BLE_SCAN_ALWAYS_AVAILABLE, mPrevBleScanAlways);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error restoring Wi-Fi/BLE background scanning", e);
        }

        try {
            if (mLocationManager != null && mPrevLocationEnabled) {
                int currentUserId = ActivityManager.getCurrentUser();
                mLocationManager.setLocationEnabledForUser(true, UserHandle.of(currentUserId));
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error restoring location", e);
        }

        try {
            ContentResolver cr = mContext.getContentResolver();
            if (mPrevDozeAlwaysOn != 0) {
                Settings.Secure.putIntForUser(cr, Settings.Secure.DOZE_ALWAYS_ON, mPrevDozeAlwaysOn, UserHandle.USER_CURRENT);
            }
            if (mPrevDozeEnabled != 0) {
                Settings.Secure.putIntForUser(cr, Settings.Secure.DOZE_ENABLED, mPrevDozeEnabled, UserHandle.USER_CURRENT);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error restoring AOD/ambient display", e);
        }

        try {
            if (mPrevMasterSync) {
                ContentResolver.setMasterSyncAutomatically(true);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error restoring master sync", e);
        }

        try {
            if (mPrevNfcEnabled && mNfcAdapter != null) {
                mNfcAdapter.enable();
                mPrevNfcEnabled = false;
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error restoring NFC", e);
        }

        try {
            if (mSensorPrivacyManager != null && !mPrevSensorPrivacy) {
                mSensorPrivacyManager.setAllSensorPrivacy(false);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error restoring sensor privacy", e);
        }

        if (fullRestore) {
            try {
                if (mAppStandbyInternal != null && !mDemotedPackages.isEmpty()) {
                    int currentUserId = ActivityManager.getCurrentUser();
                    for (String pkg : mDemotedPackages) {
                        mAppStandbyInternal.setAppStandbyBucket(pkg,
                                UsageStatsManager.STANDBY_BUCKET_ACTIVE, currentUserId,
                                Process.myUid(), Process.myPid());
                    }
                    mDemotedPackages.clear();
                }
            } catch (Exception e) {
                Slog.e(TAG, "Error restoring app standby buckets", e);
            }
        }
    }

    private void handleIncomingOrActiveCall() {
        synchronized (mLock) {
            mIsInCall = true;
            if (mIsDeepDozeActive) {
                Slog.i(TAG, "Incoming call received during sleep. Suspending Deep Doze for call!");
                if (mDeviceIdleInternal != null) {
                    mDeviceIdleInternal.forceDeepIdle(false);
                }
                mIsDeepDozeActive = false;

                restoreSuppressorsLocked(false);

                if (mRepeatCallersEnabled) {
                    handleRepeatCallerCheck();
                }
            }
        }
    }

    private void handleCallEnded() {
        synchronized (mLock) {
            mIsInCall = false;
            mBgHandler.postDelayed(() -> {
                synchronized (mLock) {
                    if (!mIsInCall && mDeepDozeEnabled) {
                        handleScreenOff();
                    }
                }
            }, 3000);
        }
    }

    private void handleEmergencyCall() {
        synchronized (mLock) {
            mIsInCall = true;
            mIsDeepDozeActive = false;
            if (mDeviceIdleInternal != null) {
                mDeviceIdleInternal.forceDeepIdle(false);
            }
            restoreSuppressorsLocked(true);
        }
    }

    private void handleRepeatCallerCheck() {
        final long now = System.currentTimeMillis();
        mRecentCalls.entrySet().removeIf(entry -> (now - entry.getValue()) > REPEAT_CALL_WINDOW_MS);

        String callKey = "caller_repeat";
        Long prevCallTime = mRecentCalls.get(callKey);
        if (prevCallTime != null && (now - prevCallTime) <= REPEAT_CALL_WINDOW_MS) {
            Slog.w(TAG, "Repeat caller detected within 15 min! Unmuting ringer for emergency night call.");
            try {
                if (mAudioManager != null) {
                    mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL);
                }
            } catch (Exception ignored) {}
        }
        mRecentCalls.put(callKey, now);
    }

    private void schedulePreAlarmWarmingLocked() {
        if (!mPreAlarmWarmingEnabled || mAlarmManager == null) {
            return;
        }
        final long nextAlarmTime = mAlarmManager.getNextWakeFromIdleTime();
        if (nextAlarmTime <= 0) {
            return;
        }
        final long triggerTime = nextAlarmTime - PRE_ALARM_WARM_WINDOW_MS;
        if (triggerTime > SystemClock.elapsedRealtime()) {
            Intent intent = new Intent(ACTION_PRE_ALARM_WAKE).setPackage(mContext.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            mAlarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pi);
            Slog.i(TAG, "Scheduled pre-alarm warming for " + (triggerTime - SystemClock.elapsedRealtime()) + " ms from now");
        }
    }

    private void cancelPreAlarmWarmingLocked() {
        if (mAlarmManager != null) {
            Intent intent = new Intent(ACTION_PRE_ALARM_WAKE).setPackage(mContext.getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
            if (pi != null) {
                mAlarmManager.cancel(pi);
            }
        }
    }

    private void handlePreAlarmWarming() {
        synchronized (mLock) {
            if (mIsDeepDozeActive) {
                Slog.i(TAG, "Warming network before morning alarm: restoring radios and master sync");
                restoreRadiosLocked();
                try {
                    if (mPrevMasterSync) {
                        ContentResolver.setMasterSyncAutomatically(true);
                    }
                } catch (Exception e) {
                    Slog.e(TAG, "Error restoring master sync for pre-alarm warming", e);
                }
                if (mDeviceIdleInternal != null) {
                    mDeviceIdleInternal.forceDeepIdle(false);
                }
            }
        }
    }

    public boolean onWakeLockAcquired(String packageName, int uid, String tag) {
        if (!mIsDeepDozeActive || TextUtils.isEmpty(packageName)) {
            return false;
        }

        if (isGmsPackage(packageName)) {
            if (isGmsThrottleTag(tag)) {
                Slog.w(TAG, "DeepDoze: Intercepted and throttled GMS wakelock: tag=" + tag);
                mBgHandler.post(() -> logGmsDisturbanceAndThrottle(packageName, tag));
                return true;
            }
            return false;
        }

        if (isWhitelisted(packageName, uid, tag)) {
            if (DEBUG) Slog.d(TAG, "WakeLock allowed for whitelisted core app: " + packageName + " (tag=" + tag + ")");
            return false;
        }

        Slog.w(TAG, "DeepDoze: Intercepted disturbing wakelock from: " + packageName + ", tag=" + tag + ", killing app!");

        mBgHandler.post(() -> logDisturbanceAndKill(packageName, tag));
        return true;
    }

    private boolean isGmsPackage(String packageName) {
        return "com.google.android.gms".equals(packageName)
                || "com.google.android.gsf".equals(packageName);
    }

    private boolean isGmsThrottleTag(String tag) {
        if (TextUtils.isEmpty(tag)) {
            return false;
        }
        String lower = tag.toLowerCase(Locale.ROOT);
        return lower.contains("gcore")
                || lower.contains("flp")
                || lower.contains("checkin")
                || lower.contains("analytics")
                || lower.contains("nlp")
                || lower.contains("location")
                || lower.contains("nearby")
                || lower.contains("geofence")
                || lower.contains("context")
                || lower.contains("wakeful")
                || lower.contains("passthrough")
                || lower.contains("measurement")
                || lower.contains("upload")
                || lower.contains("dispatching");
    }

    private void logGmsDisturbanceAndThrottle(String packageName, String tag) {
        try {
            ContentResolver cr = mContext.getContentResolver();
            String existingLog = Settings.Secure.getStringForUser(
                    cr, Settings.Secure.DEEP_DOZE_DISTURBANCE_LOG, UserHandle.USER_CURRENT);

            JSONArray array = TextUtils.isEmpty(existingLog) ? new JSONArray() : new JSONArray(existingLog);

            JSONObject entry = new JSONObject();
            entry.put("package", packageName);
            entry.put("tag", tag != null ? tag : "unknown");
            entry.put("timestamp", System.currentTimeMillis());
            entry.put("formatted_time", new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
            entry.put("action", "GMS Throttled & Suppressed");

            JSONArray updated = new JSONArray();
            updated.put(entry);
            for (int i = 0; i < Math.min(array.length(), MAX_DISTURBANCE_LOG_ENTRIES - 1); i++) {
                updated.put(array.get(i));
            }

            Settings.Secure.putStringForUser(
                    cr, Settings.Secure.DEEP_DOZE_DISTURBANCE_LOG, updated.toString(), UserHandle.USER_CURRENT);
        } catch (Exception e) {
            Slog.e(TAG, "Error saving GMS disturbance log", e);
        }

        try {
            if (mActivityManager != null) {
                mActivityManager.killBackgroundProcesses(packageName);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to kill auxiliary background processes for GMS: " + packageName, e);
        }
    }

    private boolean isWhitelisted(String packageName, int uid, String tag) {
        if (uid < Process.FIRST_APPLICATION_UID) {
            return true;
        }

        if ("android".equals(packageName) || "com.android.systemui".equals(packageName)) {
            return true;
        }

        if ("com.android.phone".equals(packageName)
                || "com.android.server.telecom".equals(packageName)
                || "com.android.incallui".equals(packageName)
                || packageName.contains("telecom")
                || packageName.contains("dialer")) {
            return true;
        }

        String defaultSms = Telephony.Sms.getDefaultSmsPackage(mContext);
        if (packageName.equals(defaultSms)
                || "com.android.mms".equals(packageName)
                || "com.google.android.apps.messaging".equals(packageName)) {
            return true;
        }

        if (packageName.contains("deskclock") || packageName.contains("clock")) {
            return true;
        }
        if (tag != null && (tag.toLowerCase(Locale.ROOT).contains("alarm")
                || tag.toLowerCase(Locale.ROOT).contains("ring"))) {
            return true;
        }

        return false;
    }

    private void logDisturbanceAndKill(String packageName, String tag) {
        try {
            ContentResolver cr = mContext.getContentResolver();
            String existingLog = Settings.Secure.getStringForUser(
                    cr, Settings.Secure.DEEP_DOZE_DISTURBANCE_LOG, UserHandle.USER_CURRENT);

            JSONArray array = TextUtils.isEmpty(existingLog) ? new JSONArray() : new JSONArray(existingLog);

            JSONObject entry = new JSONObject();
            entry.put("package", packageName);
            entry.put("tag", tag != null ? tag : "unknown");
            entry.put("timestamp", System.currentTimeMillis());
            entry.put("formatted_time", new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
            entry.put("action", "Killed & Blocked");

            JSONArray updated = new JSONArray();
            updated.put(entry);
            for (int i = 0; i < Math.min(array.length(), MAX_DISTURBANCE_LOG_ENTRIES - 1); i++) {
                updated.put(array.get(i));
            }

            Settings.Secure.putStringForUser(
                    cr, Settings.Secure.DEEP_DOZE_DISTURBANCE_LOG, updated.toString(), UserHandle.USER_CURRENT);
        } catch (Exception e) {
            Slog.e(TAG, "Error saving disturbance log", e);
        }

        try {
            if (mActivityManager != null) {
                mActivityManager.killBackgroundProcesses(packageName);
                mActivityManager.forceStopPackage(packageName);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to kill offending app: " + packageName, e);
        }
    }

    private int getBatteryLevel() {
        if (mCurrentBatteryLevel >= 0) {
            return mCurrentBatteryLevel;
        }
        BatteryManager bm = mContext.getSystemService(BatteryManager.class);
        if (bm != null) {
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        }
        return 100;
    }
}
