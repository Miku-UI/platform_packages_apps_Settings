/*
 * Copyright (C) 2021-2023 Miku UI
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
package com.android.settings.fuelgauge;

import static android.provider.Settings.Secure.CPU_PERFORMANCE_MODE;

import android.app.settings.SettingsEnums;
import android.content.Context;
import android.provider.Settings;
import com.android.internal.util.miku.FileUtils;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreference;

import com.android.internal.R;
import com.android.settings.Utils;
import com.android.settings.core.BasePreferenceController;
import com.android.settings.core.PreferenceControllerMixin;

/**
 * A controller to manage the switch for enabling performance mode.
 */

public class PerformanceModeController extends BasePreferenceController implements
        Preference.OnPreferenceChangeListener {

    private Preference mPreference;

    private static final int MAX_COUNT = 9;
    private static final String PATH_PREFIX = "/sys/devices/system/cpu/cpufreq/policy";
    private static final String PATH_SUFFIX = "/scaling_governor";
    private static final String[] SUPPORTED_GOVERNOR = {
        "interactive",
        "schedutil",
        "walt"
    };
    private static final String PERFORMANCE = "performance";

    public PerformanceModeController(Context context, String preferenceKey) {
        super(context, preferenceKey);
    }

    @Override
    public int getAvailabilityStatus() {
        return FileUtils.isFileWritable(PATH_PREFIX + "0" + PATH_SUFFIX) ? AVAILABLE
                : UNSUPPORTED_ON_DEVICE;
    }

    @Override
    public void updateState(Preference preference) {
        int setting = Settings.Secure.getInt(mContext.getContentResolver(),
                CPU_PERFORMANCE_MODE, 0);
        String result = FileUtils.readOneLine(PATH_PREFIX + "0" + PATH_SUFFIX);
        ((SwitchPreference) preference).setChecked(setting == 1 && result.equals(PERFORMANCE));
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        boolean enablePerformanceMode = (Boolean) newValue;
        Settings.Secure.putInt(mContext.getContentResolver(), CPU_PERFORMANCE_MODE,
                enablePerformanceMode ? 1 : 0);
        if (enablePerformanceMode) {
            for (int i = 0; i < MAX_COUNT; i++){
                if (FileUtils.isFileWritable(PATH_PREFIX + i + PATH_SUFFIX))
                    FileUtils.writeLine(PATH_PREFIX + i + PATH_SUFFIX, PERFORMANCE);
            }
        } else {
            for (int i = 0; i < MAX_COUNT; i++){
                for (int j = 0; j < SUPPORTED_GOVERNOR.length; j++) {
                    if (FileUtils.isFileWritable(PATH_PREFIX + i + PATH_SUFFIX))
                        FileUtils.writeLine(PATH_PREFIX + i + PATH_SUFFIX, SUPPORTED_GOVERNOR[j]);
                }
            }
        }
        return true;
    }
}
