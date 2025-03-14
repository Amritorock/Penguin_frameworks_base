/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.shade;

import static com.android.systemui.flags.Flags.LOCKSCREEN_WALLPAPER_DREAM_ENABLED;
import static com.android.systemui.flags.Flags.TRACKPAD_GESTURE_COMMON;
import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

import android.app.StatusBarManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.AuthKeyguardMessageArea;
import com.android.keyguard.KeyguardMessageAreaController;
import com.android.keyguard.LockIconViewController;
import com.android.keyguard.dagger.KeyguardBouncerComponent;
import com.android.systemui.R;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.back.domain.interactor.BackActionInteractor;
import com.android.systemui.bouncer.domain.interactor.BouncerMessageInteractor;
import com.android.systemui.bouncer.ui.binder.KeyguardBouncerViewBinder;
import com.android.systemui.bouncer.ui.viewmodel.KeyguardBouncerViewModel;
import com.android.systemui.classifier.FalsingCollector;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dock.DockManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.keyevent.domain.interactor.KeyEventInteractor;
import com.android.systemui.keyguard.KeyguardUnlockAnimationController;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.keyguard.shared.model.TransitionState;
import com.android.systemui.keyguard.shared.model.TransitionStep;
import com.android.systemui.keyguard.ui.viewmodel.PrimaryBouncerToGoneTransitionViewModel;
import com.android.systemui.log.BouncerLogger;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.shared.animation.DisableSubpixelTextTransitionListener;
import com.android.systemui.statusbar.DragDownHelper;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.NotificationInsetsController;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.data.repository.NotificationExpansionRepository;
import com.android.systemui.statusbar.notification.stack.AmbientState;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.PhoneStatusBarViewController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.window.StatusBarWindowStateController;
import com.android.systemui.unfold.UnfoldTransitionProgressProvider;
import com.android.systemui.util.time.SystemClock;

import java.io.PrintWriter;
import java.util.Optional;
import java.util.function.Consumer;

import javax.inject.Inject;

/**
 * Controller for {@link NotificationShadeWindowView}.
 */
@SysUISingleton
public class NotificationShadeWindowViewController {
    private static final String TAG = "NotifShadeWindowVC";
    private final FalsingCollector mFalsingCollector;
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final NotificationShadeWindowView mView;
    private final NotificationShadeDepthController mDepthController;
    private final NotificationStackScrollLayoutController mNotificationStackScrollLayoutController;
    private final LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    private final LockIconViewController mLockIconViewController;
    private final ShadeLogger mShadeLogger;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final StatusBarWindowStateController mStatusBarWindowStateController;
    private final KeyguardUnlockAnimationController mKeyguardUnlockAnimationController;
    private final AmbientState mAmbientState;
    private final PulsingGestureListener mPulsingGestureListener;
    private final LockscreenHostedDreamGestureListener mLockscreenHostedDreamGestureListener;
    private final NotificationInsetsController mNotificationInsetsController;
    private final boolean mIsTrackpadCommonEnabled;
    private final FeatureFlags mFeatureFlags;
    private final KeyEventInteractor mKeyEventInteractor;
    private GestureDetector mPulsingWakeupGestureHandler;
    private GestureDetector mDreamingWakeupGestureHandler;
    private View mBrightnessMirror;
    private boolean mTouchActive;
    private boolean mTouchCancelled;
    private MotionEvent mDownEvent;
    // TODO rename to mLaunchAnimationRunning
    private boolean mExpandAnimationRunning;
    /**
     *  When mExpandAnimationRunning is true and the touch dispatcher receives a down even after
     *  uptime exceeds this, the dispatcher will stop blocking touches for the launch animation,
     *  which has presumabely not completed due to an error.
     */
    private long mLaunchAnimationTimeout;
    private NotificationStackScrollLayout mStackScrollLayout;
    private PhoneStatusBarViewController mStatusBarViewController;
    private final CentralSurfaces mService;
    private final BackActionInteractor mBackActionInteractor;
    private final PowerInteractor mPowerInteractor;
    private final NotificationShadeWindowController mNotificationShadeWindowController;
    private DragDownHelper mDragDownHelper;
    private boolean mExpandingBelowNotch;
    private final DockManager mDockManager;
    private final NotificationPanelViewController mNotificationPanelViewController;
    private final ShadeExpansionStateManager mShadeExpansionStateManager;

    private boolean mIsTrackingBarGesture = false;
    private boolean mIsOcclusionTransitionRunning = false;
    private DisableSubpixelTextTransitionListener mDisableSubpixelTextTransitionListener;
    private final Consumer<TransitionStep> mLockscreenToDreamingTransition =
            (TransitionStep step) -> {
                mIsOcclusionTransitionRunning =
                    step.getTransitionState() == TransitionState.RUNNING;
            };
    private final SystemClock mClock;
    private final PowerManager mPowerManager;

    private GestureDetector mDoubleTapGestureListener;
    private SettingsObserver mSettingsObserver;
    private boolean mDoubleTapEnabled;

    @Inject
    public NotificationShadeWindowViewController(
            @Main Handler handler,
            LockscreenShadeTransitionController transitionController,
            FalsingCollector falsingCollector,
            SysuiStatusBarStateController statusBarStateController,
            DockManager dockManager,
            NotificationShadeDepthController depthController,
            NotificationShadeWindowView notificationShadeWindowView,
            NotificationPanelViewController notificationPanelViewController,
            ShadeExpansionStateManager shadeExpansionStateManager,
            NotificationStackScrollLayoutController notificationStackScrollLayoutController,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            StatusBarWindowStateController statusBarWindowStateController,
            LockIconViewController lockIconViewController,
            CentralSurfaces centralSurfaces,
            BackActionInteractor backActionInteractor,
            PowerInteractor powerInteractor,
            NotificationShadeWindowController controller,
            Optional<UnfoldTransitionProgressProvider> unfoldTransitionProgressProvider,
            KeyguardUnlockAnimationController keyguardUnlockAnimationController,
            NotificationInsetsController notificationInsetsController,
            AmbientState ambientState,
            ShadeLogger shadeLogger,
            PulsingGestureListener pulsingGestureListener,
            LockscreenHostedDreamGestureListener lockscreenHostedDreamGestureListener,
            KeyguardBouncerViewModel keyguardBouncerViewModel,
            KeyguardBouncerComponent.Factory keyguardBouncerComponentFactory,
            KeyguardMessageAreaController.Factory messageAreaControllerFactory,
            KeyguardTransitionInteractor keyguardTransitionInteractor,
            PrimaryBouncerToGoneTransitionViewModel primaryBouncerToGoneTransitionViewModel,
            NotificationExpansionRepository notificationExpansionRepository,
            FeatureFlags featureFlags,
            SystemClock clock,
            BouncerMessageInteractor bouncerMessageInteractor,
            BouncerLogger bouncerLogger,
            KeyEventInteractor keyEventInteractor) {
        mLockscreenShadeTransitionController = transitionController;
        mFalsingCollector = falsingCollector;
        mStatusBarStateController = statusBarStateController;
        mView = notificationShadeWindowView;
        mDockManager = dockManager;
        mNotificationPanelViewController = notificationPanelViewController;
        mShadeExpansionStateManager = shadeExpansionStateManager;
        mDepthController = depthController;
        mNotificationStackScrollLayoutController = notificationStackScrollLayoutController;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mStatusBarWindowStateController = statusBarWindowStateController;
        mLockIconViewController = lockIconViewController;
        mBackActionInteractor = backActionInteractor;
        mShadeLogger = shadeLogger;
        mLockIconViewController.init();
        mService = centralSurfaces;
        mPowerInteractor = powerInteractor;
        mNotificationShadeWindowController = controller;
        mKeyguardUnlockAnimationController = keyguardUnlockAnimationController;
        mAmbientState = ambientState;
        mPulsingGestureListener = pulsingGestureListener;
        mLockscreenHostedDreamGestureListener = lockscreenHostedDreamGestureListener;
        mNotificationInsetsController = notificationInsetsController;
        mIsTrackpadCommonEnabled = featureFlags.isEnabled(TRACKPAD_GESTURE_COMMON);
        mFeatureFlags = featureFlags;
        mKeyEventInteractor = keyEventInteractor;

        // This view is not part of the newly inflated expanded status bar.
        mBrightnessMirror = mView.findViewById(R.id.brightness_mirror_container);
        mDisableSubpixelTextTransitionListener = new DisableSubpixelTextTransitionListener(mView);
        KeyguardBouncerViewBinder.bind(
                mView.findViewById(R.id.keyguard_bouncer_container),
                keyguardBouncerViewModel,
                primaryBouncerToGoneTransitionViewModel,
                keyguardBouncerComponentFactory,
                messageAreaControllerFactory,
                bouncerMessageInteractor,
                bouncerLogger,
                featureFlags);

        collectFlow(mView, keyguardTransitionInteractor.getLockscreenToDreamingTransition(),
                mLockscreenToDreamingTransition);
        collectFlow(
                mView,
                notificationExpansionRepository.isExpandAnimationRunning(),
                this::setExpandAnimationRunning);

        mClock = clock;
        if (featureFlags.isEnabled(Flags.SPLIT_SHADE_SUBPIXEL_OPTIMIZATION)) {
            unfoldTransitionProgressProvider.ifPresent(
                    progressProvider -> progressProvider.addCallback(
                            mDisableSubpixelTextTransitionListener));
        }

        mPowerManager = mView.getContext().getSystemService(PowerManager.class);
        mDoubleTapGestureListener = new GestureDetector(mView.getContext(),
                new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent event) {
                if (mDoubleTapEnabled
                        && mStatusBarStateController.getState() == StatusBarState.KEYGUARD
                        && !mService.isBouncerShowing()
                        && !mNotificationPanelViewController.isShadeFullyExpanded()
                        && !mStatusBarStateController.isDozing()
                        && !mStatusBarStateController.isPulsing()) {
                    mPowerManager.goToSleep(event.getEventTime());
                    return true;
                }
                return false;
            }
        });
        mSettingsObserver = new SettingsObserver(handler);
        mSettingsObserver.register();
    }

    /**
     * @return Location where to place the KeyguardMessageArea
     */
    public AuthKeyguardMessageArea getKeyguardMessageArea() {
        return mView.findViewById(R.id.keyguard_message_area);
    }

    private Boolean logDownDispatch(MotionEvent ev, String msg, Boolean result) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            mShadeLogger.logShadeWindowDispatch(ev, msg, result);
        }
        return result;
    }

    /** Inflates the {@link R.layout#status_bar_expanded} layout and sets it up. */
    public void setupExpandedStatusBar() {
        mStackScrollLayout = mView.findViewById(R.id.notification_stack_scroller);
        mPulsingWakeupGestureHandler = new GestureDetector(mView.getContext(),
                mPulsingGestureListener);
        if (mFeatureFlags.isEnabled(LOCKSCREEN_WALLPAPER_DREAM_ENABLED)) {
            mDreamingWakeupGestureHandler = new GestureDetector(mView.getContext(),
                    mLockscreenHostedDreamGestureListener);
        }
        mView.setLayoutInsetsController(mNotificationInsetsController);
        mView.setInteractionEventHandler(new NotificationShadeWindowView.InteractionEventHandler() {
            @Override
            public Boolean handleDispatchTouchEvent(MotionEvent ev) {
                if (mStatusBarViewController == null) { // Fix for b/192490822
                    return logDownDispatch(ev,
                            "Ignoring touch while statusBarView not yet set", false);
                }
                boolean isDown = ev.getActionMasked() == MotionEvent.ACTION_DOWN;
                boolean isUp = ev.getActionMasked() == MotionEvent.ACTION_UP;
                boolean isCancel = ev.getActionMasked() == MotionEvent.ACTION_CANCEL;

                boolean expandingBelowNotch = mExpandingBelowNotch;
                if (isUp || isCancel) {
                    mExpandingBelowNotch = false;
                }

                // Reset manual touch dispatch state here but make sure the UP/CANCEL event still
                // gets delivered.
                if (!isCancel && mService.shouldIgnoreTouch()) {
                    return logDownDispatch(ev, "touch ignored by CS", false);
                }

                if (isDown) {
                    mTouchActive = true;
                    mTouchCancelled = false;
                    mDownEvent = ev;
                } else if (ev.getActionMasked() == MotionEvent.ACTION_UP
                        || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    mTouchActive = false;
                    mDownEvent = null;
                }
                if (mTouchCancelled) {
                    return logDownDispatch(ev, "touch cancelled", false);
                }
                if (mExpandAnimationRunning) {
                    if (isDown && mClock.uptimeMillis() > mLaunchAnimationTimeout) {
                        Log.wtf(TAG, "NSWVC: launch animation timed out");
                        setExpandAnimationRunning(false);
                    } else {
                        return logDownDispatch(ev, "expand animation running", false);
                    }
                }

                if (mKeyguardUnlockAnimationController.isPlayingCannedUnlockAnimation()) {
                    // If the user was sliding their finger across the lock screen,
                    // we may have been intercepting the touch and forwarding it to the
                    // UDFPS affordance via mStatusBarKeyguardViewManager.onTouch (see below).
                    // If this touch ended up unlocking the device, we want to cancel the touch
                    // immediately, so we don't cause swipe or expand animations afterwards.
                    cancelCurrentTouch();
                    return true;
                }

                if (mIsOcclusionTransitionRunning) {
                    return logDownDispatch(ev, "occlusion transition running", false);
                }

                mFalsingCollector.onTouchEvent(ev);
                mDoubleTapGestureListener.onTouchEvent(ev);
                mPulsingWakeupGestureHandler.onTouchEvent(ev);
                if (mDreamingWakeupGestureHandler != null
                        && mDreamingWakeupGestureHandler.onTouchEvent(ev)) {
                    return logDownDispatch(ev, "dream wakeup gesture handled", true);
                }
                if (mStatusBarKeyguardViewManager.dispatchTouchEvent(ev)) {
                    return logDownDispatch(ev, "dispatched to Keyguard", true);
                }
                if (mBrightnessMirror != null
                        && mBrightnessMirror.getVisibility() == View.VISIBLE) {
                    // Disallow new pointers while the brightness mirror is visible. This is so that
                    // you can't touch anything other than the brightness slider while the mirror is
                    // showing and the rest of the panel is transparent.
                    if (ev.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
                        return logDownDispatch(ev, "disallowed new pointer", false);
                    }
                }
                if (isDown) {
                    mNotificationStackScrollLayoutController.closeControlsIfOutsideTouch(ev);
                }

                if (mStatusBarStateController.isDozing()) {
                    mService.extendDozePulse();
                }
                mLockIconViewController.onTouchEvent(
                        ev,
                        /* onGestureDetectedRunnable */
                        () -> {
                            mService.userActivity();
                            mPowerInteractor.wakeUpIfDozing(
                                    "LOCK_ICON_TOUCH",
                                    PowerManager.WAKE_REASON_GESTURE);
                        }
                );

                // In case we start outside of the view bounds (below the status bar), we need to
                // dispatch
                // the touch manually as the view system can't accommodate for touches outside of
                // the
                // regular view bounds.
                if (isDown && ev.getY() >= mView.getBottom()) {
                    mExpandingBelowNotch = true;
                    expandingBelowNotch = true;
                }
                if (expandingBelowNotch) {
                    return logDownDispatch(ev,
                            "expand below notch. sending touch to status bar",
                            mStatusBarViewController.sendTouchToView(ev));
                }

                if (!mIsTrackingBarGesture && isDown
                        && mNotificationPanelViewController.isFullyCollapsed()) {
                    float x = ev.getRawX();
                    float y = ev.getRawY();
                    if (mStatusBarViewController.touchIsWithinView(x, y)) {
                        if (mStatusBarWindowStateController.windowIsShowing()) {
                            mIsTrackingBarGesture = true;
                            return logDownDispatch(ev, "sending touch to status bar",
                                    mStatusBarViewController.sendTouchToView(ev));
                        } else {
                            return logDownDispatch(ev, "hidden or hiding", true);
                        }
                    }
                } else if (mIsTrackingBarGesture) {
                    final boolean sendToStatusBar = mStatusBarViewController.sendTouchToView(ev);
                    if (isUp || isCancel) {
                        mIsTrackingBarGesture = false;
                    }
                    return logDownDispatch(ev, "sending bar gesture to status bar",
                            sendToStatusBar);
                }
                return logDownDispatch(ev, "no custom touch dispatch of down event", null);
            }

            @Override
            public void dispatchTouchEventComplete() {
                mFalsingCollector.onMotionEventComplete();
            }

            @Override
            public boolean shouldInterceptTouchEvent(MotionEvent ev) {
                if (mStatusBarStateController.isDozing() && !mService.isPulsing()
                        && !mDockManager.isDocked()) {
                    if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                        mShadeLogger.d("NSWVC: capture all touch events in always-on");
                    }
                    return true;
                }

                if (mStatusBarKeyguardViewManager.shouldInterceptTouchEvent(ev)) {
                    // Don't allow touches to proceed to underlying views if alternate
                    // bouncer is showing
                    if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                        mShadeLogger.d("NSWVC: alt bouncer showing");
                    }
                    return true;
                }

                if (mLockIconViewController.onInterceptTouchEvent(ev)) {
                    // immediately return true; don't send the touch to the drag down helper
                    if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                        mShadeLogger.d("NSWVC: don't send touch to drag down helper");
                    }
                    return true;
                }

                if (mNotificationPanelViewController.isFullyExpanded()
                        && mDragDownHelper.isDragDownEnabled()
                        && !mService.isBouncerShowing()
                        && !mStatusBarStateController.isDozing()) {
                    boolean result = mDragDownHelper.onInterceptTouchEvent(ev);
                    if (result) {
                        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                            mShadeLogger.d("NSWVC: drag down helper intercepted");
                        }
                    }
                    return result;
                } else {
                    return false;
                }
            }

            @Override
            public void didIntercept(MotionEvent ev) {
                MotionEvent cancellation = MotionEvent.obtain(ev);
                cancellation.setAction(MotionEvent.ACTION_CANCEL);
                mStackScrollLayout.onInterceptTouchEvent(cancellation);
                mNotificationPanelViewController.handleExternalInterceptTouch(cancellation);
                cancellation.recycle();
            }

            @Override
            public boolean handleTouchEvent(MotionEvent ev) {
                boolean handled = false;
                if (mStatusBarStateController.isDozing()) {
                    handled = !mService.isPulsing();
                }

                if (mStatusBarKeyguardViewManager.onTouch(ev)) {
                    return true;
                }

                if (mDragDownHelper.isDragDownEnabled()
                        || mDragDownHelper.isDraggingDown()) {
                    // we still want to finish our drag down gesture when locking the screen
                    return mDragDownHelper.onTouchEvent(ev) || handled;
                } else {
                    return handled;
                }
            }

            @Override
            public void didNotHandleTouchEvent(MotionEvent ev) {
                final int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    mService.setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false);
                }
            }

            @Override
            public boolean interceptMediaKey(KeyEvent event) {
                return mKeyEventInteractor.interceptMediaKey(event);
            }

            @Override
            public boolean dispatchKeyEventPreIme(KeyEvent event) {
                return mKeyEventInteractor.dispatchKeyEventPreIme(event);
            }

            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                return mKeyEventInteractor.dispatchKeyEvent(event);
            }
        });

        mView.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                if (child.getId() == R.id.brightness_mirror_container) {
                    mBrightnessMirror = child;
                }
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
            }
        });

        setDragDownHelper(mLockscreenShadeTransitionController.getTouchHelper());

        mDepthController.setRoot(mView);
        ShadeExpansionChangeEvent currentState =
                mShadeExpansionStateManager.addExpansionListener(mDepthController);
        mDepthController.onPanelExpansionChanged(currentState);
    }

    public NotificationShadeWindowView getView() {
        return mView;
    }

    public void cancelCurrentTouch() {
        mShadeLogger.d("NSWVC: cancelling current touch");
        if (mTouchActive) {
            final long now = mClock.uptimeMillis();
            final MotionEvent event;
            if (mIsTrackpadCommonEnabled) {
                event = MotionEvent.obtain(mDownEvent);
                event.setDownTime(now);
                event.setAction(MotionEvent.ACTION_CANCEL);
                event.setLocation(0.0f, 0.0f);
            } else {
                event = MotionEvent.obtain(now, now,
                        MotionEvent.ACTION_CANCEL, 0.0f, 0.0f, 0);
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            }
            Log.w(TAG, "Canceling current touch event (should be very rare)");
            mView.dispatchTouchEvent(event);
            event.recycle();
            mTouchCancelled = true;
        }
        mAmbientState.setSwipingUp(false);
    }

    public void dump(PrintWriter pw, String[] args) {
        pw.print("  mExpandAnimationRunning=");
        pw.println(mExpandAnimationRunning);
        pw.print("  mTouchCancelled=");
        pw.println(mTouchCancelled);
        pw.print("  mTouchActive=");
        pw.println(mTouchActive);
    }

    @VisibleForTesting
    void setExpandAnimationRunning(boolean running) {
        if (mExpandAnimationRunning != running) {
            // TODO(b/288507023): Remove this log.
            if (ActivityLaunchAnimator.DEBUG_LAUNCH_ANIMATION) {
                Log.d(TAG, "Setting mExpandAnimationRunning=" + running);
            }
            if (running) {
                mLaunchAnimationTimeout = mClock.uptimeMillis() + 5000;
            }
            mExpandAnimationRunning = running;
            mNotificationShadeWindowController.setLaunchingActivity(mExpandAnimationRunning);
        }
    }

    public void cancelExpandHelper() {
        if (mStackScrollLayout != null) {
            mStackScrollLayout.cancelExpandHelper();
        }
    }

    public void setStatusBarViewController(PhoneStatusBarViewController statusBarViewController) {
        mStatusBarViewController = statusBarViewController;
    }

    @VisibleForTesting
    void setDragDownHelper(DragDownHelper dragDownHelper) {
        mDragDownHelper = dragDownHelper;
    }

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            update();
        }

        public void register() {
            mView.getContext().getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(Settings.System.GESTURE_DOUBLE_TAP_SLEEP),
                    false, this);
            update();
        }

        public void update() {
            mDoubleTapEnabled = Settings.System.getIntForUser(
                    mView.getContext().getContentResolver(),
                    Settings.System.GESTURE_DOUBLE_TAP_SLEEP, 1, UserHandle.USER_CURRENT) == 1;
        }
    }
}
