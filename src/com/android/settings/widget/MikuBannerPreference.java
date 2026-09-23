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

package com.android.settings.widget;

import android.content.Context;
import android.util.AttributeSet;

import com.android.settingslib.widget.LayoutPreference;
import com.android.settingslib.widget.NormalPaddingMixin;

/**
 * Full-width banner. LayoutPreference is a section divider, so expressive theme would
 * otherwise inset it by settingslib_expressive_space_small1; NormalPaddingMixin plus
 * GroupSectionDividerMixin is the adapter's edge-to-edge case.
 */
public class MikuBannerPreference extends LayoutPreference implements NormalPaddingMixin {

    public MikuBannerPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MikuBannerPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
}
