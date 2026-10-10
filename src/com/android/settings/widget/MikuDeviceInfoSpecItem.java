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
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.settings.R;

/**
 * One about-phone spec cell: title above a wrapping value, top-aligned so a
 * GridLayout row keeps titles on one baseline.
 */
public class MikuDeviceInfoSpecItem extends LinearLayout {

    private final TextView mTitle;
    private final TextView mValue;

    public MikuDeviceInfoSpecItem(Context context) {
        this(context, null);
    }

    public MikuDeviceInfoSpecItem(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MikuDeviceInfoSpecItem(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        LayoutInflater.from(context).inflate(R.layout.miku_device_info_spec_item, this);
        mTitle = findViewById(R.id.miku_device_info_spec_title);
        mValue = findViewById(R.id.miku_device_info_spec_value);
        if (attrs != null) {
            final TypedArray a = context.obtainStyledAttributes(
                    attrs, R.styleable.MikuDeviceInfoSpecItem);
            final CharSequence title = a.getText(R.styleable.MikuDeviceInfoSpecItem_mikuSpecTitle);
            a.recycle();
            if (title != null) {
                mTitle.setText(title);
            }
        }
    }

    public void setValue(CharSequence value) {
        mValue.setText(value);
    }
}
