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

package com.android.systemui.qs.tiles;

import android.content.ComponentName;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.res.R;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.UserSettingObserver;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.settings.SecureSettings;

import javax.inject.Inject;

public class DeepDozeTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "deep_doze";

    private static final ComponentName DEEP_DOZE_SETTING_COMPONENT = new ComponentName(
            "com.android.settings", "com.android.settings.Settings$DeepDozeActivity");

    private static final Intent DEEP_DOZE_SETTINGS =
            new Intent().setComponent(DEEP_DOZE_SETTING_COMPONENT);

    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_sleep);

    private final UserSettingObserver mSetting;

    @Inject
    public DeepDozeTile(
            QsEventLogger uiEventLogger,
            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            SecureSettings secureSettings,
            UserTracker userTracker
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);

        mSetting = new UserSettingObserver(secureSettings, mHandler, Settings.Secure.DEEP_DOZE_ENABLED,
                userTracker.getUserId()) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                handleRefreshState(value);
            }
        };

        SettingsObserver settingsObserver = new SettingsObserver(mainHandler);
        settingsObserver.observe();
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        setEnabled(!mState.value);
        refreshState();
    }

    private void setEnabled(boolean enabled) {
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.DEEP_DOZE_ENABLED, enabled ? 1 : 0, UserHandle.USER_CURRENT);
    }

    @Override
    protected void handleLongClick(@Nullable Expandable expandable) {
        mActivityStarter.postStartActivityDismissingKeyguard(DEEP_DOZE_SETTINGS, 0);
    }

    @Override
    public Intent getLongClickIntent() {
        return DEEP_DOZE_SETTINGS;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final int value = arg instanceof Integer ? (Integer) arg : mSetting.getValue();
        final boolean enabled = value != 0;
        final boolean isActive = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.DEEP_DOZE_IS_ACTIVE, 0, UserHandle.USER_CURRENT) == 1;

        state.value = enabled;
        state.label = mContext.getString(R.string.quick_settings_deep_doze_label);
        state.icon = mIcon;
        if (enabled) {
            state.state = Tile.STATE_ACTIVE;
            state.secondaryLabel = isActive
                    ? mContext.getString(R.string.quick_settings_deep_doze_active)
                    : null;
        } else {
            state.state = Tile.STATE_INACTIVE;
            state.secondaryLabel = null;
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_deep_doze_label);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsLogger.VIEW_UNKNOWN;
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        mSetting.setListening(listening);
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.DEEP_DOZE_IS_ACTIVE),
                    false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            refreshState();
        }
    }
}
