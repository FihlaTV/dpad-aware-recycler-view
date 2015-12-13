/*
 * Copyright 2015 Vsevolod Ganin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.ganin.darv;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.v7.widget.RecyclerView;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;

public class DpadAwareRecyclerView extends RecyclerView
        implements ViewTreeObserver.OnTouchModeChangeListener {

    private static final String TAG = "DpadAwareRecyclerView";

    private static final String BOUNDS_PROP_NAME = "bounds";

    /**
     * Callback for {@link Drawable} selectors. View must keep this reference in order for
     * {@link java.lang.ref.WeakReference} in selectors to survive.
     *
     * @see Drawable#setCallback(Drawable.Callback)
     */
    private final Drawable.Callback mSelectorCallback = new Drawable.Callback() {
        @Override
        public void invalidateDrawable(Drawable who) {
            invalidate(who.getBounds());
        }

        @Override
        public void scheduleDrawable(Drawable who, Runnable what, long when) {
            getHandler().postAtTime(what, who, when);
        }

        @Override
        public void unscheduleDrawable(Drawable who, Runnable what) {
            getHandler().removeCallbacks(what, who);
        }
    };

    private final Rect mSelectorSourceRect = new Rect();
    private final Rect mSelectorDestRect = new Rect();

    /**
     * Fraction of parent size which always will be offset from left border to currently focused
     * item view center.
     */
    private Float mScrollOffsetFractionX;

    /**
     * Fraction of parent size which always will be offset from top border to currently focused
     * item view center.
     */
    private Float mScrollOffsetFractionY;

    private final Interpolator mTransitionInterpolator = new LinearInterpolator();

    private int mSelectorVelocity = 0;

    private boolean mSmoothScrolling = false;

    private Drawable mBackgroundSelector;

    private Drawable mForegroundSelector;

    private AnimatorSet mSelectorAnimator;

    private View mLastFocusedChild;

    public DpadAwareRecyclerView(Context context) {
        super(context);
        init(context, null, 0);
    }

    public DpadAwareRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs, 0);
    }

    public DpadAwareRecyclerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context, attrs, defStyle);
    }

    public void setSelectorVelocity(int velocity) {
        mSelectorVelocity = velocity;
    }

    public int getSelectorVelocity() {
        return mSelectorVelocity;
    }

    public void setSmoothScrolling(boolean smoothScrolling) {
        mSmoothScrolling = smoothScrolling;
    }

    public boolean getSmoothScrolling() {
        return mSmoothScrolling;
    }

    public void setBackgroundSelector(Drawable drawable) {
        mBackgroundSelector = drawable;
        setSelectorCallback(mForegroundSelector);
    }

    public void setBackgroundSelector(int resId) {
        setBackgroundSelector(getDrawableResource(resId));
    }

    public Drawable getBackgroundSelector() {
        return mBackgroundSelector;
    }

    public void setForegroundSelector(Drawable drawable) {
        mForegroundSelector = drawable;
        setSelectorCallback(mForegroundSelector);
    }

    public void setForegroundSelector(int resId) {
        setForegroundSelector(getDrawableResource(resId));
    }

    public Drawable getForegroundSelector() {
        return mForegroundSelector;
    }

    /**
     * Sets scroll offset from left border. Pass <code>null</code> to disable feature.
     *
     * @param scrollOffsetFraction scroll offset from left border
     */
    public void setScrollOffsetFractionX(Float scrollOffsetFraction) {
        mScrollOffsetFractionX = scrollOffsetFraction;
    }

    public Float getScrollOffsetFractionX() {
        return mScrollOffsetFractionX;
    }

    /**
     * Sets scroll offset from top border. Pass <code>null</code> to disable feature.
     *
     * @param scrollOffsetFraction scroll offset from top border
     */
    public void setScrollOffsetFractionY(Float scrollOffsetFraction) {
        mScrollOffsetFractionY = scrollOffsetFraction;
    }

    public Float getScrollOffsetFractionY() {
        return mScrollOffsetFractionY;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        ViewTreeObserver obs = getViewTreeObserver();
        if (obs != null) {
            obs.addOnTouchModeChangeListener(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        ViewTreeObserver obs = getViewTreeObserver();
        if (obs != null) {
            obs.removeOnTouchModeChangeListener(this);
        }
    }

    @Override
    public void onTouchModeChanged(boolean isInTouchMode) {
        enforceSelectorsVisibility(isInTouchMode, hasFocus());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        enforceSelectorsVisibility(isInTouchMode(), hasFocus());

        return super.dispatchKeyEvent(event);
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);

        if (mLastFocusedChild != focused) {
            animateSelectorChange(focused, mLastFocusedChild);
            mLastFocusedChild = focused;
        }
    }

    @Override
    public void onScrolled(int dx, int dy) {
        super.onScrolled(dx, dy);

        mSelectorSourceRect.offset(-dx, -dy);
        mSelectorDestRect.offset(-dx, -dy);

        if (mSelectorAnimator == null || !mSelectorAnimator.isRunning()) {
            mBackgroundSelector.getBounds().offset(-dx, -dy);
            mBackgroundSelector.invalidateSelf();
            mForegroundSelector.getBounds().offset(-dx, -dy);
            mForegroundSelector.invalidateSelf();
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (mBackgroundSelector != null && mBackgroundSelector.isVisible()) {
            mBackgroundSelector.draw(canvas);
        }

        super.dispatchDraw(canvas);

        if (mForegroundSelector != null && mForegroundSelector.isVisible()) {
            mForegroundSelector.draw(canvas);
        }
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rect, boolean immediate) {
        immediate = immediate || !mSmoothScrolling;

        if (mScrollOffsetFractionX == null && mScrollOffsetFractionY == null) {
            return super.requestChildRectangleOnScreen(child, rect, immediate);
        }

        final int parentLeft = getPaddingLeft();
        final int parentTop = getPaddingTop();
        final int parentRight = getWidth() - getPaddingRight();
        final int parentBottom = getHeight() - getPaddingBottom();
        final int childLeft = child.getLeft() + rect.left;
        final int childTop = child.getTop() + rect.top;
        final int childRight = childLeft + rect.width();
        final int childBottom = childTop + rect.height();

        int cameraLeft;
        int cameraRight;
        int cameraTop;
        int cameraBottom;

        if (mScrollOffsetFractionX == null) {
            cameraLeft = parentLeft;
            cameraRight = parentRight;
        } else {
            final int cameraCenterX = (int) ((parentRight + parentLeft) * mScrollOffsetFractionX);
            final int childHalfWidth = (int) Math.ceil((childRight - childLeft) * 0.5);
            cameraLeft = cameraCenterX - childHalfWidth;
            cameraRight = cameraCenterX + childHalfWidth;
        }

        if (mScrollOffsetFractionY == null) {
            cameraTop = parentTop;
            cameraBottom = parentBottom;
        } else {
            final int cameraCenterY = (int) ((parentBottom + parentTop) * mScrollOffsetFractionY);
            final int childHalfHeight = (int) Math.ceil((childBottom - childTop) * 0.5);
            cameraTop = cameraCenterY - childHalfHeight;
            cameraBottom = cameraCenterY + childHalfHeight;
        }

        final int offScreenLeft = Math.min(0, childLeft - cameraLeft);
        final int offScreenTop = Math.min(0, childTop - cameraTop);
        final int offScreenRight = Math.max(0, childRight - cameraRight);
        final int offScreenBottom = Math.max(0, childBottom - cameraBottom);

        // Favor the "start" layout direction over the end when bringing one side or the other
        // of a large rect into view. If we decide to bring in end because start is already
        // visible, limit the scroll such that start won't go out of bounds.
        final int dx;
        if (getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            dx = offScreenRight != 0 ? offScreenRight
                    : Math.max(offScreenLeft, childRight - parentRight);
        } else {
            dx = offScreenLeft != 0 ? offScreenLeft
                    : Math.min(childLeft - parentLeft, offScreenRight);
        }

        // Favor bringing the top into view over the bottom. If top is already visible and
        // we should scroll to make bottom visible, make sure top does not go out of bounds.
        final int dy = offScreenTop != 0 ? offScreenTop
                : Math.min(childTop - parentTop, offScreenBottom);

        if (dx != 0 || dy != 0) {
            if (immediate) {
                scrollBy(dx, dy);
            } else {
                smoothScrollBy(dx, dy);
            }
            return true;
        }
        return false;
    }

    private void init(Context context, AttributeSet attrs, int defStyle) {
        if (attrs != null) {
            TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.DpadAwareRecyclerView,
                    defStyle, 0);

            if (ta.hasValue(R.styleable.DpadAwareRecyclerView_scrollOffsetX)) {
                mScrollOffsetFractionX = ta.getFraction(
                        R.styleable.DpadAwareRecyclerView_scrollOffsetX, 1, 1, 0f);
            }

            if (ta.hasValue(R.styleable.DpadAwareRecyclerView_scrollOffsetY)) {
                mScrollOffsetFractionY = ta.getFraction(
                        R.styleable.DpadAwareRecyclerView_scrollOffsetY, 1, 1, 0f);
            }

            if (ta.hasValue(R.styleable.DpadAwareRecyclerView_backgroundSelector)) {
                setBackgroundSelector(ta.getDrawable(
                        R.styleable.DpadAwareRecyclerView_backgroundSelector));
            }

            if (ta.hasValue(R.styleable.DpadAwareRecyclerView_foregroundSelector)) {
                setForegroundSelector(ta.getDrawable(
                        R.styleable.DpadAwareRecyclerView_foregroundSelector));
            }

            if (ta.hasValue(R.styleable.DpadAwareRecyclerView_selectorVelocity)) {
                setSelectorVelocity(ta.getInt(
                        R.styleable.DpadAwareRecyclerView_selectorVelocity, 0));
            }

            setSmoothScrolling(ta.getBoolean(
                    R.styleable.DpadAwareRecyclerView_smoothScrolling, false));

            ta.recycle();
        }
    }

    /**
     * Animates selector {@link Drawable} when changes happen.
     */
    private void animateSelectorChange(final View newlyFocused, final View prevFocused) {
        if (newlyFocused == null) {
            return;
        } else {
            newlyFocused.getHitRect(mSelectorDestRect);
        }

        if (prevFocused != null) {
            prevFocused.getHitRect(mSelectorSourceRect);
        } else {
            mSelectorSourceRect.set(0, 0, 0, 0);
        }

        if (mSelectorSourceRect.equals(mSelectorDestRect)) {
            return;
        }

        if (mSelectorAnimator != null) {
            mSelectorAnimator.cancel();
        }

        mSelectorAnimator = new AnimatorSet();

        if (mForegroundSelector != null) {
            Animator foregroundSelectorAnim = createAnimatorForSelector(mForegroundSelector);
            mSelectorAnimator.playTogether(foregroundSelectorAnim);
        }

        if (mBackgroundSelector != null) {
            Animator backgroundAnimator = createAnimatorForSelector(mBackgroundSelector);
            mSelectorAnimator.playTogether(backgroundAnimator);
        }

        mSelectorAnimator.setInterpolator(mTransitionInterpolator);

        mSelectorAnimator.addListener(new AnimatorListenerAdapter() {

            @Override
            public void onAnimationStart(Animator animation) {
                if (prevFocused != null) {
                    prevFocused.setSelected(false);
                }
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                newlyFocused.setSelected(true);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                onAnimationEnd(animation);
            }
        });

        int duration = 0;
        if (mSelectorVelocity > 0) {
            int dx = mSelectorDestRect.centerX() - mSelectorSourceRect.centerX();
            int dy = mSelectorDestRect.centerY() - mSelectorSourceRect.centerY();
            duration = computeTravelDuration(dx, dy, mSelectorVelocity);
        }

        mSelectorAnimator.setDuration(duration);
        mSelectorAnimator.start();
    }

    private Animator createAnimatorForSelector(final Drawable selector) {
        ObjectAnimator animator = ObjectAnimator.ofObject(
                selector, BOUNDS_PROP_NAME, new RectEvaluator(),
                mSelectorDestRect);

        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                invalidate(selector.getBounds());
            }
        });

        return animator;
    }

    private int computeTravelDuration(int dx, int dy, int velocity) {
        return (int) (Math.sqrt(dx * dx + dy * dy) / velocity * 1000);
    }

    private void enforceSelectorsVisibility(boolean isInTouchMode, boolean hasFocus) {
        boolean visible = !isInTouchMode && hasFocus;

        if (mBackgroundSelector != null) mBackgroundSelector.setVisible(visible, false);
        if (mForegroundSelector != null) mForegroundSelector.setVisible(visible, false);
    }

    private Drawable getDrawableResource(int resId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return getResources().getDrawable(resId, getContext().getTheme());
        } else {
            return getResources().getDrawable(resId);
        }
    }

    private void setSelectorCallback(Drawable selector) {
        if (selector != null) {
            selector.setCallback(mSelectorCallback);
        }
    }
}
