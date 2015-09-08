package info.awesomedevelopment.tvgrid.library;

import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.v4.util.LruCache;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.MessageFormat;

/*
    Copyright 2015 Sam Mathias Weggersen

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
 */

public class TVGridView extends RecyclerView {

    private static final int ANIMATION_DURATION = 140;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({INSIDE, CENTER, OUTSIDE})
    public @interface StrokePosition {}
    public static final int INSIDE = 0;
    public static final int CENTER = 1;
    public static final int OUTSIDE = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({OVER, UNDER})
    public @interface SelectorPosition {}
    public static final int OVER = 0;
    public static final int UNDER = 1;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({RECTANGLE, CIRCLE})
    public @interface SelectorShape {}
    public static final int RECTANGLE = 0;
    public static final int CIRCLE = 1;

    private ValueAnimator mYSize;
    private ValueAnimator mXSize;
    private ValueAnimator mYLocation;
    private ValueAnimator mXLocation;

    private LruCache<String, BitmapDrawable> mCache;

    private static Paint sStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static Paint sShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static Paint sCutoutPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static Paint sFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    static {
        sStrokePaint.setStyle(Paint.Style.FILL);

        sShadowPaint.setStyle(Paint.Style.FILL);
        sShadowPaint.setColor(Color.BLACK);
        sShadowPaint.setAlpha((int) Math.ceil(0.5 * 255));

        sCutoutPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OUT));

        sFillPaint.setStyle(Paint.Style.FILL);
    }

    private class DeselectRunnable implements Runnable {

        private View view;

        public DeselectRunnable(View view) {
            this.view = view;
        }

        @Override
        public void run() {
            hardUpdateSelector(view, hasFocus(), false);
        }

    }

    private final AnimatorSet mSelectorAnimationSet = new AnimatorSet();
    private final Handler mSelectorDeselectHandler = new Handler();
    private DeselectRunnable mSelectorDeselectRunnable;

    private Drawable mStrokeCell;
    private Rect mStrokeCellPrevBounds;
    private Rect mStrokeCellCurrentBounds;

    private int mScrollY = 0;
    private boolean mHardScrollChange = false;
    private boolean mEdgeChange = false;

    private int mOffsetX = -1;
    private int mOffsetY = -1;
    private boolean mOffsetOnPrev = false;

    @StrokePosition private int mStrokePosition;
    @SelectorPosition private int mSelectorPosition;
    @SelectorShape private int mSelectorShape = RECTANGLE;

    private boolean mAnimateSelectorChanges;

    private float mCornerRadiusX;
    private float mCornerRadiusY;

    private boolean mIsFilled;

    private int mFillColor;
    private int mFillColorSelected;
    private int mFillColorClicked;

    private float mFillAlpha;
    private float mFillAlphaSelected;
    private float mFillAlphaClicked;

    private float mStrokeWidth;

    private int mStrokeColor;
    private int mStrokeColorSelected;
    private int mStrokeColorClicked;

    private float mStrokeMarginLeft;
    private float mStrokeMarginTop;
    private float mStrokeMarginRight;
    private float mStrokeMarginBottom;

    private float mStrokeSpacingLeft;
    private float mStrokeSpacingTop;
    private float mStrokeSpacingRight;
    private float mStrokeSpacingBottom;

    public TVGridView(Context context) {
        super(context);

        init(null);
    }

    public TVGridView(Context context, AttributeSet attrs) {
        super(context, attrs);

        init(attrs);
    }

    public TVGridView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        init(attrs);
    }

    @SuppressWarnings("deprecation")
    private void init(AttributeSet attrs) {
        ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        mCache = new LruCache<>(am.getMemoryClass() * 1024);

        mYSize = new ValueAnimator();
        mXSize = new ValueAnimator();
        mYLocation = new ValueAnimator();
        mXLocation = new ValueAnimator();

        mXSize.addUpdateListener(xSizeListener);
        mYSize.addUpdateListener(ySizeListener);
        mYLocation.addUpdateListener(yLocationListener);
        mXLocation.addUpdateListener(xLocationListener);

        mSelectorAnimationSet.playTogether(mXLocation, mYLocation, mXSize, mYSize);
        mSelectorAnimationSet.setInterpolator(new AccelerateDecelerateInterpolator());
        mSelectorAnimationSet.setDuration(ANIMATION_DURATION);

        TypedValue fillAlpha = new TypedValue();
        getResources().getValue(R.dimen.tvg_defFillAlpha, fillAlpha, true);

        TypedValue fillAlphaSelected = new TypedValue();
        getResources().getValue(R.dimen.tvg_defFillAlphaSelected, fillAlphaSelected, true);

        if (attrs != null) {
            TypedArray a = getContext().getTheme().obtainStyledAttributes(
                    attrs,
                    R.styleable.TVGridView,
                    0,
                    0
            );

            try {
                //noinspection ResourceType
                mStrokePosition = a.getInteger(R.styleable.TVGridView_tvg_strokePosition, OUTSIDE);
                //noinspection ResourceType
                mSelectorPosition = a.getInteger(R.styleable.TVGridView_tvg_selectorPosition, OVER);
                //noinspection ResourceType
                mSelectorShape = a.getInteger(R.styleable.TVGridView_tvg_selectorShape, RECTANGLE);

                mAnimateSelectorChanges = a.getBoolean(R.styleable.TVGridView_tvg_animateSelectorChanges, getResources().getInteger(R.integer.tvg_defAnimateSelectorChanges) == 1);
                mIsFilled = a.getBoolean(R.styleable.TVGridView_tvg_filled, getResources().getInteger(R.integer.tvg_defIsFilled) == 1);
                mFillAlpha = a.getFloat(R.styleable.TVGridView_tvg_fillAlpha, fillAlpha.getFloat());
                mFillAlphaSelected = a.getFloat(R.styleable.TVGridView_tvg_fillAlphaSelected, fillAlphaSelected.getFloat());
                mFillColor = a.getColor(R.styleable.TVGridView_tvg_fillColor, getResources().getColor(R.color.tvg_defFillColor));
                mFillColorSelected = a.getColor(R.styleable.TVGridView_tvg_fillColorSelected, getResources().getColor(R.color.tvg_defFillColorSelected));
                mCornerRadiusX = a.getDimension(R.styleable.TVGridView_tvg_cornerRadius, getResources().getDimension(R.dimen.tvg_defCornerRadius));
                mCornerRadiusY = a.getDimension(R.styleable.TVGridView_tvg_cornerRadius, getResources().getDimension(R.dimen.tvg_defCornerRadius));
                mStrokeWidth = a.getDimension(R.styleable.TVGridView_tvg_strokeWidth, getResources().getDimension(R.dimen.tvg_defStrokeWidth));
                mStrokeColor = a.getColor(R.styleable.TVGridView_tvg_strokeColor, getResources().getColor(R.color.tvg_defStrokeColor));
                mStrokeColorSelected = a.getColor(R.styleable.TVGridView_tvg_strokeColorSelected, getResources().getColor(R.color.tvg_defStrokeColorSelected));
                mStrokeMarginLeft = a.getDimension(R.styleable.TVGridView_tvg_marginLeft, getResources().getDimension(R.dimen.tvg_defStrokeMarginLeft));
                mStrokeMarginTop = a.getDimension(R.styleable.TVGridView_tvg_marginTop, getResources().getDimension(R.dimen.tvg_defStrokeMarginTop));
                mStrokeMarginRight = a.getDimension(R.styleable.TVGridView_tvg_marginRight, getResources().getDimension(R.dimen.tvg_defStrokeMarginRight));
                mStrokeMarginBottom = a.getDimension(R.styleable.TVGridView_tvg_marginBottom, getResources().getDimension(R.dimen.tvg_defStrokeMarginBottom));
                mStrokeSpacingLeft = a.getDimension(R.styleable.TVGridView_tvg_spacingLeft, getResources().getDimension(R.dimen.tvg_defStrokeSpacingLeft));
                mStrokeSpacingTop = a.getDimension(R.styleable.TVGridView_tvg_spacingTop, getResources().getDimension(R.dimen.tvg_defStrokeSpacingTop));
                mStrokeSpacingRight = a.getDimension(R.styleable.TVGridView_tvg_spacingRight, getResources().getDimension(R.dimen.tvg_defStrokeSpacingRight));
                mStrokeSpacingBottom = a.getDimension(R.styleable.TVGridView_tvg_spacingBottom, getResources().getDimension(R.dimen.tvg_defStrokeSpacingBottom));
            } finally {
                a.recycle();
            }
        } else {
            mStrokePosition = OUTSIDE;
            mSelectorPosition = OVER;
            mSelectorShape = RECTANGLE;

            mAnimateSelectorChanges = getResources().getInteger(R.integer.tvg_defAnimateSelectorChanges) == 1;
            mIsFilled = getResources().getInteger(R.integer.tvg_defIsFilled) == 1;
            mFillAlpha = fillAlpha.getFloat();
            mFillAlphaSelected = fillAlphaSelected.getFloat();
            mFillColor = getResources().getColor(R.color.tvg_defFillColor);
            mFillColorSelected = getResources().getColor(R.color.tvg_defFillColorSelected);
            mCornerRadiusX = getResources().getDimension(R.dimen.tvg_defCornerRadius);
            mCornerRadiusY = getResources().getDimension(R.dimen.tvg_defCornerRadius);
            mStrokeWidth = getResources().getDimension(R.dimen.tvg_defStrokeWidth);
            mStrokeColor = getResources().getColor(R.color.tvg_defStrokeColor);
            mStrokeColorSelected = getResources().getColor(R.color.tvg_defStrokeColorSelected);
            mStrokeMarginLeft = getResources().getDimension(R.dimen.tvg_defStrokeMarginLeft);
            mStrokeMarginTop = getResources().getDimension(R.dimen.tvg_defStrokeMarginTop);
            mStrokeMarginRight = getResources().getDimension(R.dimen.tvg_defStrokeMarginRight);
            mStrokeMarginBottom = getResources().getDimension(R.dimen.tvg_defStrokeMarginBottom);
            mStrokeSpacingLeft = getResources().getDimension(R.dimen.tvg_defStrokeSpacingLeft);
            mStrokeSpacingTop = getResources().getDimension(R.dimen.tvg_defStrokeSpacingTop);
            mStrokeSpacingRight = getResources().getDimension(R.dimen.tvg_defStrokeSpacingRight);
            mStrokeSpacingBottom = getResources().getDimension(R.dimen.tvg_defStrokeSpacingBottom);
        }

        addOnScrollListener(new OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == SCROLL_STATE_IDLE) {
                    mEdgeChange = false;
                    mHardScrollChange = false;
                }
            }

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                mScrollY = mScrollY + dy;

                if (mStrokeCellCurrentBounds == null || mStrokeCell == null) return;

                if (useAnimations()) {
                    mSelectorAnimationSet.cancel();

                    mStrokeCellCurrentBounds.offsetTo(mStrokeCellCurrentBounds.left - dx, mStrokeCellCurrentBounds.top - dy);

                    performSelectorAnimation();
                } else if (mHardScrollChange || mEdgeChange) {
                    mStrokeCellCurrentBounds.offsetTo(mStrokeCellCurrentBounds.left - dx, mStrokeCellCurrentBounds.top - dy);
                    setPrevBounds();

                    mStrokeCell.setBounds(mStrokeCellPrevBounds);
                    invalidate();
                }
            }
        });

        setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                clearHighlightedView();
                return false;
            }
        });
    }

    @SuppressWarnings("unused")
    public int getScroll() {
        return mScrollY;
    }

    @SuppressWarnings("unused")
    public void scrollByY(int y, boolean edgeChange) {
        mEdgeChange = edgeChange;
        mHardScrollChange = false;

        if (useAnimations()) {
            super.smoothScrollBy(0, y);
        } else {
            super.scrollBy(0, y);

            if (!edgeChange) {
                if (mStrokeCellCurrentBounds == null || mStrokeCell == null) return;

                mSelectorAnimationSet.cancel();
                mStrokeCellCurrentBounds.offsetTo(mStrokeCellCurrentBounds.left, mStrokeCellCurrentBounds.top);

                setPrevBounds();

                mStrokeCell.setBounds(mStrokeCellPrevBounds);
                invalidate();
            }
        }
    }

    @Override
    public void smoothScrollToPosition(int position) {
        mHardScrollChange = true;
        super.smoothScrollToPosition(position);
    }

    @Override
    public void scrollToPosition(int position) {
        mHardScrollChange = true;
        super.scrollToPosition(position);
    }

    @Override
    public void scrollTo(int x, int y) {
        mHardScrollChange = true;
        super.scrollTo(x, y);
    }

    @Override
    public void smoothScrollBy(int dx, int dy) {
        mHardScrollChange = true;
        super.smoothScrollBy(dx, dy);
    }

    @Override
    public void scrollBy(int x, int y) {
        mHardScrollChange = true;
        super.scrollBy(x, y);
    }

    @SuppressWarnings("unused")
    public void setStrokePosition(@StrokePosition int strokePosition) {
        mStrokePosition = strokePosition;
    }

    @SuppressWarnings("unused")
    @StrokePosition
    public int getStrokePosition() {
        return mStrokePosition;
    }

    @SuppressWarnings("unused")
    public void setSelectorPosition(@SelectorPosition int position) {
        mSelectorPosition = position;
    }

    @SuppressWarnings("unused")
    @SelectorPosition
    public int setSelectorPosition() {
        return mSelectorPosition;
    }

    @SuppressWarnings("unused")
    public void setSelectorShape(@SelectorShape int shape) {
        mSelectorShape = shape;
    }

    @SuppressWarnings("unused")
    @SelectorShape
    public int getSelectorShape() {
        return mSelectorShape;
    }

    @SuppressWarnings("unused")
    public void setCornerRadius(float radius) {
        mCornerRadiusX = radius;
        mCornerRadiusY = radius;
    }

    @SuppressWarnings("unused")
    public void setCornerRadius(float x, float y) {
        mCornerRadiusX = x;
        mCornerRadiusY = y;
    }

    /**
     * @param animate true if selector should animate between focus views
     */
    @SuppressWarnings("unused")
    public void setAnimateSelectorChanges(boolean animate) {
        mAnimateSelectorChanges = animate;
    }

    @SuppressWarnings("unused")
    public int getAnimationDuration() {
        return ANIMATION_DURATION;
    }

    @SuppressWarnings("unused")
    public float getCornerRadiusX() {
        return mCornerRadiusX;
    }

    @SuppressWarnings("unused")
    public float getCornerRadiusY() {
        return mCornerRadiusY;
    }

    @SuppressWarnings("unused")
    public void setFilled(boolean filled) {
        mIsFilled = filled;
    }

    @SuppressWarnings("unused")
    public boolean isFilled() {
        return mIsFilled;
    }

    @SuppressWarnings("unused")
    public void setFillColor(int color) {
        mFillColor = color;
    }

    @SuppressWarnings("unused")
    public int getFillColor() {
        return mFillColor;
    }

    @SuppressWarnings("unused")
    public void setFillColorSelected(int color) {
        mFillColorSelected = color;
    }

    @SuppressWarnings("unused")
    public int getFillColorSelected() {
        return mFillColorSelected;
    }

    @SuppressWarnings("unused")
    public void setFillColorClicked(int color) {
        mFillColorClicked = color;
    }

    @SuppressWarnings("unused")
    public int getFillColorClicked() {
        return mFillColorClicked;
    }

    @SuppressWarnings("unused")
    public void setFillAlpha(float alpha) {
        mFillAlpha = alpha;
    }

    @SuppressWarnings("unused")
    public float getFillAlpha() {
        return mFillAlpha;
    }

    @SuppressWarnings("unused")
    public void setFillAlphaSelected(float alpha) {
        mFillAlphaSelected = alpha;
    }

    @SuppressWarnings("unused")
    public float getFillAlphaSelected() {
        return mFillAlphaSelected;
    }

    @SuppressWarnings("unused")
    public void setFillAlphaClicked(float alpha) {
        mFillAlphaClicked = alpha;
    }

    @SuppressWarnings("unused")
    public float getFillAlphaClicked() {
        return mFillAlphaClicked;
    }

    @SuppressWarnings("unused")
    public void setStrokeWidth(float width) {
        mStrokeWidth = width;
    }

    @SuppressWarnings("unused")
    public float getStrokeWidth() {
        return mStrokeWidth;
    }

    @SuppressWarnings("unused")
    public void setStrokeColor(int color) {
        mStrokeColorSelected = color;
    }

    @SuppressWarnings("unused")
    public int getStrokeColor() {
        return mStrokeColor;
    }

    @SuppressWarnings("unused")
    public void setStrokeColorSelected(int color) {
        mStrokeColorSelected = color;
    }

    @SuppressWarnings("unused")
    public int getStrokeColorSelected() {
        return mStrokeColorSelected;
    }

    @SuppressWarnings("unused")
    public void setStrokeColorClicked(int color) {
        mStrokeColorClicked = color;
    }

    @SuppressWarnings("unused")
    public int getStrokeColorClicked() {
        return mStrokeColorClicked;
    }

    /**
     * Stroke margin for selector
     *
     *              MARGIN
     * |------------------------------|
     * |                              |
     * |                              |
     * |                              |
     * |                              |
     * |                              |
     * |                              |
     * |------------------------------|
     *
     * @param left margin left
     * @param top margin top
     * @param right margin right
     * @param bottom margin bottom
     */
    @SuppressWarnings("unused")
    public void setStrokeMargin(float left, float top, float right, float bottom) {
        mStrokeMarginLeft = left;
        mStrokeMarginTop = top;
        mStrokeMarginRight = right;
        mStrokeMarginBottom = bottom;
    }

    /**
     * Stroke margin for selector
     *
     *              MARGIN
     * |------------------------------|
     * |                              |
     * |                              |
     * |                              |
     * |                              |
     * |                              |
     * |                              |
     * |------------------------------|
     *
     * @param all same margin in all directions
     */
    @SuppressWarnings("unused")
    public void setStrokeMargin(float all) {
        mStrokeMarginLeft = all;
        mStrokeMarginTop = all;
        mStrokeMarginRight = all;
        mStrokeMarginBottom = all;
    }

    @SuppressWarnings("unused")
    public float getStrokeMarginLeft() {
        return mStrokeMarginLeft;
    }

    @SuppressWarnings("unused")
    public float getStrokeMarginTop() {
        return mStrokeMarginTop;
    }

    @SuppressWarnings("unused")
    public float getStrokeMarginRight() {
        return mStrokeMarginRight;
    }

    @SuppressWarnings("unused")
    public float getStrokeMarginBottom() {
        return mStrokeMarginBottom;
    }

    /**
     * Stroke spacing for selector
     *
     * |------------------------------|
     * |            SPACING           |
     * |                              |
     * |                              |
     * |                              |
     * |                              |
     * |                              |
     * |------------------------------|
     *
     * @param left spacing left
     * @param top spacing top
     * @param right spacing right
     * @param bottom spacing bottom
     */
    @SuppressWarnings("unused")
    public void setStrokeSpacing(float left, float top, float right, float bottom) {
        mStrokeSpacingLeft = left;
        mStrokeSpacingTop = top;
        mStrokeSpacingRight = right;
        mStrokeSpacingBottom = bottom;
    }

    /**
     * Stroke spacing for selector
     *
     * |------------------------------|
     * |            SPACING           |
     * |                              |
     * |                              |
     * |                              |
     * |                              |
     * |                              |
     * |------------------------------|
     *
     * @param all same spacing in all directions
     */
    @SuppressWarnings("unused")
    public void setStrokeSpacing(float all) {
        mStrokeSpacingLeft = all;
        mStrokeSpacingTop = all;
        mStrokeSpacingRight = all;
        mStrokeSpacingBottom = all;
    }

    @SuppressWarnings("unused")
    public float getStrokeSpacingLeft() {
        return mStrokeSpacingLeft;
    }

    @SuppressWarnings("unused")
    public float getStrokeSpacingTop() {
        return mStrokeSpacingTop;
    }

    @SuppressWarnings("unused")
    public float getStrokeSpacingRight() {
        return mStrokeSpacingRight;
    }

    @SuppressWarnings("unused")
    public float getStrokeSpacingBottom() {
        return mStrokeSpacingBottom;
    }

    /**
     * Creates and shows a click selector for a given view
     *
     * @param view view to click
     * @param offsetX offset in x direction, < 0 == 0
     * @param offsetY offset in y direction, < 0 == 0
     */
    @SuppressWarnings("unused")
    public void clickView(final View view, int offsetX, int offsetY) {
        if (view == null) return;

        mOffsetX = offsetX;
        mOffsetY = offsetY;

        hardUpdateSelector(view, true, true);
    }

    /**
     * Creates and shows a click selector for a given view
     *
     * @param view view to click
     */
    @SuppressWarnings("unused")
    public void clickView(final View view) {
        if (view == null) return;

        final Drawable clone = mStrokeCell.mutate();
        hardUpdateSelector(view, true, true);

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                mStrokeCell = clone;
                invalidate();
            }
        }, 100);

    }

    /**
     * Creates and shows a selector for a given view
     * with offset
     *
     * @param view view to select
     * @param offsetX offset in x direction, < 0 == 0
     * @param offsetY offset in y direction, < 0 == 0
     * @param focused true if view should get focus
     */
    @SuppressWarnings("unused")
    public void selectView(final View view, int offsetX, int offsetY, boolean focused) {
        if (view == null) return;

        mOffsetX = offsetX;
        mOffsetY = offsetY;

        highlightViewBase(view, focused);
    }

    /**
     * Creates and shows a selector for a given view
     *
     * @param view view to select
     * @param focused true if view should get focus
     */
    @SuppressWarnings("unused")
    public void selectView(View view, boolean focused) {
        selectView(view, -1, -1, focused);

    }

    /**
     * General logic for selecting a view
     *
     * @param view view to select or click
     * @param focused true if view should get focus
     */
    private void highlightViewBase(final View view, final boolean focused) {
        if (!focused) {
            if (mSelectorDeselectRunnable == null) mSelectorDeselectRunnable = new DeselectRunnable(view);
            mSelectorDeselectHandler.postDelayed(mSelectorDeselectRunnable, 50);
            return;
        }
        mSelectorDeselectHandler.removeCallbacksAndMessages(null);
        if (useAnimations() && mStrokeCell != null) {
            prepareAndPerformSelectorAnimation(view, mSelectorAnimationSet.isRunning());
        } else {
            hardUpdateSelector(view, true, false);
            clearOffset();
        }
    }

    private void hardUpdateSelector(View view, boolean focused, boolean clicked) {
        addStrokedView(view, focused, clicked, true, true);
    }

    private void setPrevBounds() {
        if (mStrokeCellPrevBounds == null) {
            mStrokeCellPrevBounds = new Rect(mStrokeCellCurrentBounds);
        } else {
            mStrokeCellPrevBounds.right = mStrokeCellCurrentBounds.right;
            mStrokeCellPrevBounds.top = mStrokeCellCurrentBounds.top;
            mStrokeCellPrevBounds.left = mStrokeCellCurrentBounds.left;
            mStrokeCellPrevBounds.bottom = mStrokeCellCurrentBounds.bottom;
        }
    }

    private void prepareAndPerformSelectorAnimation(View view, boolean running) {
        if (running) mSelectorAnimationSet.cancel();
        else setPrevBounds();

        if (mOffsetOnPrev && (mOffsetX == -1 || mOffsetY == -1)) {
            mStrokeCellPrevBounds.offset(-(mOffsetX == -1 ? 0 : mOffsetX), -(mOffsetY == -1 ? 0 : mOffsetY));
        }

        addStrokedView(view, true, false, !running, false);

        performSelectorAnimation();

    }

    private ValueAnimator.AnimatorUpdateListener ySizeListener = new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            mStrokeCellPrevBounds.set(
                    mStrokeCellPrevBounds.left,
                    mStrokeCellPrevBounds.top,
                    mStrokeCellPrevBounds.right,
                    (int) animation.getAnimatedValue());
            mStrokeCell.setBounds(mStrokeCellPrevBounds);
            invalidate();
        }
    };

    private ValueAnimator.AnimatorUpdateListener xSizeListener = new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            mStrokeCellPrevBounds.set(
                    mStrokeCellPrevBounds.left,
                    mStrokeCellPrevBounds.top,
                    (int) animation.getAnimatedValue(),
                    mStrokeCellPrevBounds.bottom);
            mStrokeCell.setBounds(mStrokeCellPrevBounds);
            invalidate();
        }
    };

    private ValueAnimator.AnimatorUpdateListener yLocationListener = new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            mStrokeCellPrevBounds.offsetTo(mStrokeCellPrevBounds.left, (int) animation.getAnimatedValue());
            mStrokeCell.setBounds(mStrokeCellPrevBounds);
            invalidate();
        }
    };

    private ValueAnimator.AnimatorUpdateListener xLocationListener = new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            mStrokeCellPrevBounds.offsetTo((int) animation.getAnimatedValue(), mStrokeCellPrevBounds.top);
            mStrokeCell.setBounds(mStrokeCellPrevBounds);
            invalidate();
        }
    };

    private void performSelectorAnimation() {
        if (mStrokeCellPrevBounds == null || mStrokeCell == null) return;

        mYSize.setIntValues(mStrokeCellPrevBounds.bottom, mStrokeCellCurrentBounds.bottom);
        mXSize.setIntValues(mStrokeCellPrevBounds.right, mStrokeCellCurrentBounds.right);

        mStrokeCellPrevBounds.right = mStrokeCellPrevBounds.left + (mStrokeCellCurrentBounds.right - mStrokeCellCurrentBounds.left);
        mStrokeCellPrevBounds.bottom = mStrokeCellPrevBounds.top + (mStrokeCellCurrentBounds.bottom - mStrokeCellCurrentBounds.top);

        mYLocation.setIntValues(mStrokeCellPrevBounds.top, mStrokeCellCurrentBounds.top);
        mXLocation.setIntValues(mStrokeCellPrevBounds.left, mStrokeCellCurrentBounds.left);

        mSelectorAnimationSet.start();
    }

    /**
     * Creates the stroke cell with the appropriate bitmap and of appropriate
     * size. The stroke cell's BitmapDrawable is drawn on top or under of the bitmap every
     * single time an invalidate call is made.
     */
    private void addStrokedView(final View view, final boolean focused,  final boolean clicked, final boolean setBounds, final boolean invalidate) {
        setCorrectBounds(view);

        String id = MessageFormat.format(
                "{0}:{1}:{2}:{3}:{4}:{5}",
                mIsFilled,
                mStrokeWidth,
                mStrokeColor,
                mFillColor,
                view.getHeight(),
                view.getWidth()
        );

        BitmapDrawable bd = mCache.get(id);
        if (bd == null) {
            bd = new BitmapDrawable(getResources(), generateBitmap(view.getWidth(), view.getHeight(), focused, clicked));
            mCache.put(id, bd);
        }
        mStrokeCell = bd;
        if (setBounds) mStrokeCell.setBounds(mStrokeCellCurrentBounds);
        if (invalidate) invalidate();
    }

    private void setCorrectBounds(View v) {
        int spacing = 0;
        switch (mStrokePosition) {
            case INSIDE:
                spacing = 0;
                break;
            case CENTER:
                spacing = (int) mStrokeWidth;
                break;
            case OUTSIDE:
                spacing = (int) mStrokeWidth * 2;
                break;
        }

        mOffsetOnPrev = mOffsetX != -1 || mOffsetY != -1;

        int w = v.getWidth();
        int h = v.getHeight();
        int w_scaled = v.getWidth() + spacing;
        int h_scaled = v.getHeight() + spacing;
        int top = v.getTop() - ((h_scaled - h) / 2) + (mOffsetY == -1 ? 0 : mOffsetY);
        int left = v.getLeft() - ((w_scaled - w) / 2) + (mOffsetX == -1 ? 0 : mOffsetX);

        mStrokeCellCurrentBounds = new Rect(
                (int) (left - mStrokeSpacingLeft),
                (int) (top - mStrokeSpacingTop),
                (int) (left + w_scaled + mStrokeSpacingRight),
                (int) (top + h_scaled + mStrokeSpacingBottom));
    }

    /**
     * Generates a bitmap according to the size and state of a view in the recycler view
     *
     * @param w width of the bitmap
     * @param h height of the bitmap
     * @param focused true if the view is focused
     * @param clicked true if the view is clicked
     * @return Bitmap
     */
    private Bitmap generateBitmap(int w, int h, boolean focused, boolean clicked) {
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        RectF fillRect = new RectF(mStrokeMarginLeft, mStrokeMarginTop, w-mStrokeMarginRight, h-mStrokeMarginBottom);
        RectF shadowRect = new RectF(mStrokeWidth +mStrokeMarginLeft, mStrokeWidth +mStrokeMarginTop, w- mStrokeWidth -mStrokeMarginRight, h- mStrokeWidth -mStrokeMarginBottom);
        RectF cutoutRect = new RectF(mStrokeWidth +mStrokeMarginLeft+2, mStrokeWidth +mStrokeMarginTop+2, w- mStrokeWidth -mStrokeMarginRight-1, h- mStrokeWidth -mStrokeMarginBottom-1);

        if (mStrokeWidth > 0.0f) {
            sStrokePaint.setColor(clicked ? mStrokeColorClicked : focused ? mStrokeColor : mStrokeColorSelected);
            paintCanvas(canvas, fillRect, sStrokePaint);

            if (!mIsFilled) {
                paintCanvas(canvas, shadowRect, sShadowPaint);
            } else {
                cutoutRect = shadowRect;
            }

            paintCanvas(canvas, cutoutRect, sCutoutPaint);
        }

        if (mIsFilled) {
            sFillPaint.setColor(clicked ? mFillColorClicked : focused ? mFillColor : mFillColorSelected);
            sFillPaint.setAlpha((int) Math.ceil((clicked ? mFillAlphaClicked : focused ? mFillAlpha : mFillAlphaSelected) * 255));
            paintCanvas(canvas, cutoutRect, sFillPaint);
        }

        return bitmap;
    }

    /**
     * Helper method to paint the canvas used in generate bitmap
     *
     * @param canvas the canvas used to draw onto
     * @param rectF size
     * @param paint paint
     */
    private void paintCanvas(Canvas canvas, RectF rectF, Paint paint) {
        if (mSelectorShape == RECTANGLE) {
            canvas.drawRoundRect(rectF, mCornerRadiusX, mCornerRadiusY, paint);
        } else if (mSelectorShape == CIRCLE) {
            canvas.drawCircle(rectF.centerX(), rectF.centerY(), rectF.width() / 2, paint);
        } else {
            throw new IllegalArgumentException("Selector shape must be one of RECTANGLE or CIRCLE");
        }
    }

    /**
     * onDraw gets invoked before all the child views are about to be drawn.
     * By overriding this method, the stroke cell (BitmapDrawable) can be drawn
     * under the RecyclerViews' items whenever the RecyclerViews is redrawn.
     *
     * @param c canvas
     */
    @Override
    public void onDraw(@NonNull final Canvas c) {
        if (mSelectorPosition == UNDER) {
            if (mStrokeCell != null) {
                mStrokeCell.draw(c);
            }
        }
        super.onDraw(c);
    }

    /**
     * dispatchDraw gets invoked when all the child views are about to be drawn.
     * By overriding this method, the stroke cell (BitmapDrawable) can be drawn
     * over the RecyclerViews' items whenever the RecyclerViews is redrawn.
     *
     * @param c canvas
     */
    @Override
    protected void dispatchDraw(@NonNull final Canvas c) {
        super.dispatchDraw(c);

        if (mSelectorPosition == OVER) {
            if (mStrokeCell != null) {
                mStrokeCell.draw(c);
            }
        }
    }

    /**
     * @return true if selector should animate movement
     */
    public boolean useAnimations() {
        return mAnimateSelectorChanges;
    }

    /**
     * Clear highlighted view
     */
    public void clearHighlightedView() {
        mStrokeCell = null;
        mStrokeCellPrevBounds = null;
        invalidate();
        requestLayout();
    }

    /**
     * Clear offset
     */
    private void clearOffset() {
        mOffsetY = -1;
        mOffsetX = -1;
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        return true;
    }
}
