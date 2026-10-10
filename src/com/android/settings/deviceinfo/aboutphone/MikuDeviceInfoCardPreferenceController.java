/*
 * Copyright (C) 2026 Miku UI
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

package com.android.settings.deviceinfo.aboutphone;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Process;
import android.os.SELinux;
import android.os.SystemProperties;
import android.system.Os;
import android.system.StructUtsname;
import android.text.TextUtils;
import android.view.Display;
import android.view.View;
import android.widget.TextView;

import androidx.preference.PreferenceScreen;

import com.android.internal.os.PowerProfile;
import com.android.settings.R;
import com.android.settings.core.BasePreferenceController;
import com.android.settings.widget.MikuDeviceInfoSpecItem;
import com.android.settingslib.DeviceInfoUtils;
import com.android.settingslib.widget.LayoutPreference;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MikuDeviceInfoCardPreferenceController extends BasePreferenceController {

    private static final String BUILD_TYPE_PROPERTY = "ro.miku.buildtype";
    private static final String MAINTAINER_PROPERTY = "ro.miku.maintainer";
    private static final String MIKU_VERSION_PROPERTY = "ro.miku.version";
    private static final String BUILD_TYPE_OFFICIAL = "OFFICIAL";
    private static final String BUILD_TYPE_COMMUNITY = "COMMUNITY";
    private static final Pattern KERNEL_VERSION = Pattern.compile("^(\\d+(?:\\.\\d+)*)");
    private static final int[] RAM_SKUS_GB = {
            1, 2, 3, 4, 6, 8, 10, 12, 16, 18, 24, 32, 48, 64
    };

    public MikuDeviceInfoCardPreferenceController(Context context, String preferenceKey) {
        super(context, preferenceKey);
    }

    @Override
    public int getAvailabilityStatus() {
        return AVAILABLE_UNSEARCHABLE;
    }

    @Override
    public boolean isSliceable() {
        return false;
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        final LayoutPreference pref = screen.findPreference(getPreferenceKey());
        if (pref == null) {
            return;
        }
        bind(pref);
    }

    private void bind(LayoutPreference pref) {
        setText(pref, R.id.miku_device_info_model_value, getDeviceModel());
        setText(pref, R.id.miku_device_info_version_value, getMikuVersion());
        setSpec(pref, R.id.miku_device_info_cpu, getCpuInfo());
        setSpec(pref, R.id.miku_device_info_ram, getRamInfo());
        setSpec(pref, R.id.miku_device_info_display, getDisplayInfo());
        setSpec(pref, R.id.miku_device_info_battery, getBatteryInfo());
        setSpec(pref, R.id.miku_device_info_kernel, getKernelInfo());
        setSpec(pref, R.id.miku_device_info_security, getSecurityPatch());
        setSpec(pref, R.id.miku_device_info_selinux, getSelinuxStatus());
        setSpec(pref, R.id.miku_device_info_maintainer, getMaintainer());
        bindBuildType(pref);
    }

    private void bindBuildType(LayoutPreference pref) {
        final View official = pref.findViewById(R.id.miku_device_info_build_official);
        final View community = pref.findViewById(R.id.miku_device_info_build_community);
        final View unofficial = pref.findViewById(R.id.miku_device_info_build_unofficial);
        official.setVisibility(View.GONE);
        community.setVisibility(View.GONE);
        unofficial.setVisibility(View.GONE);
        final String type = SystemProperties.get(BUILD_TYPE_PROPERTY, "");
        if (BUILD_TYPE_OFFICIAL.equalsIgnoreCase(type)) {
            official.setVisibility(View.VISIBLE);
        } else if (BUILD_TYPE_COMMUNITY.equalsIgnoreCase(type)) {
            community.setVisibility(View.VISIBLE);
        } else {
            unofficial.setVisibility(View.VISIBLE);
        }
    }

    private String getDeviceModel() {
        return fallback(Build.MODEL);
    }

    private String getMikuVersion() {
        return fallback(SystemProperties.get(MIKU_VERSION_PROPERTY, ""));
    }

    private String getCpuInfo() {
        String manufacturer = Build.SOC_MANUFACTURER;
        String model = Build.SOC_MODEL;
        if (TextUtils.isEmpty(manufacturer)) {
            manufacturer = SystemProperties.get("ro.soc.manufacturer", "");
        }
        if (TextUtils.isEmpty(model)) {
            model = SystemProperties.get("ro.soc.model",
                    SystemProperties.get("ro.board.platform", ""));
        }
        // Board id QTI/QCT is Qualcomm Technologies, Inc.
        if ("QTI".equalsIgnoreCase(manufacturer) || "QCT".equalsIgnoreCase(manufacturer)) {
            manufacturer = "Qualcomm";
        }
        if (TextUtils.isEmpty(manufacturer) && TextUtils.isEmpty(model)) {
            return unknown();
        }
        if (TextUtils.isEmpty(manufacturer)) {
            return model;
        }
        if (TextUtils.isEmpty(model)) {
            return manufacturer;
        }
        return manufacturer + "\n" + model;
    }

    private String getRamInfo() {
        final long totalBytes = Process.getTotalMemory();
        if (totalBytes <= 0) {
            return unknown();
        }
        return mContext.getString(R.string.miku_device_info_ram_value, advertisedRamGb(totalBytes));
    }

    /**
     * /proc MemTotal is below the marketed SKU; pick the smallest SKU that covers it.
     */
    private static int advertisedRamGb(long bytes) {
        final double gb = bytes / (1024d * 1024d * 1024d);
        for (int sku : RAM_SKUS_GB) {
            if (gb <= sku) {
                return sku;
            }
        }
        return (int) Math.ceil(gb);
    }

    private String getDisplayInfo() {
        Display display = mContext.getDisplay();
        if (display == null) {
            final DisplayManager dm = mContext.getSystemService(DisplayManager.class);
            if (dm != null) {
                display = dm.getDisplay(Display.DEFAULT_DISPLAY);
            }
        }
        if (display == null) {
            return unknown();
        }
        final Display.Mode current = display.getMode();
        final int width = current.getPhysicalWidth();
        final int height = current.getPhysicalHeight();
        float peakHz = current.getRefreshRate();
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == width && mode.getPhysicalHeight() == height) {
                peakHz = Math.max(peakHz, mode.getRefreshRate());
            }
        }
        return mContext.getString(R.string.miku_device_info_display_value,
                width, height, Math.round(peakHz));
    }

    private String getBatteryInfo() {
        final double mah = new PowerProfile(mContext).getBatteryCapacity();
        if (mah <= 0) {
            return unknown();
        }
        return mContext.getString(R.string.miku_device_info_battery_value, Math.round(mah));
    }

    private String getKernelInfo() {
        final StructUtsname uname = Os.uname();
        if (uname == null || TextUtils.isEmpty(uname.release)) {
            return unknown();
        }
        final Matcher matcher = KERNEL_VERSION.matcher(uname.release);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return uname.release;
    }

    private String getSecurityPatch() {
        final String patch = DeviceInfoUtils.getSecurityPatch();
        return TextUtils.isEmpty(patch) ? unknown() : patch;
    }

    private String getSelinuxStatus() {
        if (!SELinux.isSELinuxEnabled()) {
            return mContext.getString(R.string.selinux_status_disabled);
        }
        if (!SELinux.isSELinuxEnforced()) {
            return mContext.getString(R.string.selinux_status_permissive);
        }
        return mContext.getString(R.string.selinux_status_enforcing);
    }

    private String getMaintainer() {
        return fallback(SystemProperties.get(MAINTAINER_PROPERTY, ""));
    }

    private String fallback(String value) {
        return TextUtils.isEmpty(value) ? unknown() : value;
    }

    private String unknown() {
        return mContext.getString(R.string.device_info_default);
    }

    private static void setText(LayoutPreference pref, int id, CharSequence value) {
        final TextView view = pref.findViewById(id);
        if (view != null) {
            view.setText(value);
        }
    }

    private static void setSpec(LayoutPreference pref, int id, CharSequence value) {
        final MikuDeviceInfoSpecItem item = pref.findViewById(id);
        if (item != null) {
            item.setValue(value);
        }
    }
}
