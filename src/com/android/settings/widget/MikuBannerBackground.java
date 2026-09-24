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
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import com.android.settings.R;

/**
 * 16:9 Cinderella banner backdrop. Three PSD rows of "Miku UI" tiles:
 * rows 1 and 3 scroll left-to-right, row 2 right-to-left, looping on
 * the 956px design period so the wrap has no seam.
 */
public class MikuBannerBackground extends View {

    private static final float DESIGN_W = 1792f;
    private static final float DESIGN_H = 1008f;
    private static final float TILE_W = 876f;
    private static final float TILE_H = 243f;
    /** Horizontal repeat from the PSD (945 - (-11)). */
    private static final float PERIOD_X = 956f;
    private static final long PERIOD_MS = 8000L;

    private static final float[] ROW_Y = {52f, 386f, 720f};
    private static final float[] ROW_X = {-11f, -603f, -249f};
    /** +1 = LTR (content moves right), -1 = RTL. */
    private static final float[] ROW_DIR = {1f, -1f, 1f};

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF mDest = new RectF();
    private final Choreographer mChoreographer = Choreographer.getInstance();

    private Bitmap mTile;
    private boolean mRunning;
    private long mStartMs;

    private final Choreographer.FrameCallback mFrameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!mRunning) {
                return;
            }
            invalidate();
            mChoreographer.postFrameCallback(this);
        }
    };

    public MikuBannerBackground(Context context) {
        this(context, null);
    }

    public MikuBannerBackground(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MikuBannerBackground(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);
        final BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inScaled = false;
        mTile = BitmapFactory.decodeResource(context.getResources(),
                R.drawable.hero_visual_bg_tile, opts);
        applyTint();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            width = Math.round(DESIGN_W);
        }
        setMeasuredDimension(width, Math.round(width * DESIGN_H / DESIGN_W));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        applyTint();
        startMarquee();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopMarquee();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            startMarquee();
        } else {
            stopMarquee();
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyTint();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mTile == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        final float scale = getWidth() / DESIGN_W;
        final float period = PERIOD_X * scale;
        final float tileW = TILE_W * scale;
        final float tileH = TILE_H * scale;
        final float frac = marqueeFraction();
        for (int i = 0; i < ROW_Y.length; i++) {
            drawRow(canvas, ROW_Y[i] * scale, ROW_X[i] * scale + ROW_DIR[i] * frac * period,
                    period, tileW, tileH);
        }
    }

    private void drawRow(Canvas canvas, float y, float x, float period, float tileW, float tileH) {
        float start = x - period * (float) Math.floor(x / period);
        if (start > 0f) {
            start -= period;
        }
        for (float dx = start; dx < getWidth(); dx += period) {
            mDest.set(dx, y, dx + tileW, y + tileH);
            canvas.drawBitmap(mTile, null, mDest, mPaint);
        }
    }

    private float marqueeFraction() {
        if (!mRunning || !animationsEnabled()) {
            return 0f;
        }
        final float t = (SystemClock.elapsedRealtime() - mStartMs) / (float) PERIOD_MS;
        return t - (float) Math.floor(t);
    }

    private void startMarquee() {
        if (mRunning || getWindowVisibility() != VISIBLE || !isAttachedToWindow()) {
            return;
        }
        if (!animationsEnabled()) {
            invalidate();
            return;
        }
        mRunning = true;
        mStartMs = SystemClock.elapsedRealtime();
        mChoreographer.postFrameCallback(mFrameCallback);
    }

    private void stopMarquee() {
        if (!mRunning) {
            return;
        }
        mRunning = false;
        mChoreographer.removeFrameCallback(mFrameCallback);
    }

    private void applyTint() {
        final int color = getContext().getColor(R.color.mikuui_banner_text);
        mPaint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        invalidate();
    }

    private boolean animationsEnabled() {
        return Settings.Global.getFloat(getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }
}
