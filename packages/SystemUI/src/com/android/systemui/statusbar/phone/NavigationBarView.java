/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import java.io.File;
import java.net.URISyntaxException;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.ServiceManager;
import android.provider.Settings;
import android.support.v4.view.ViewPager;
import android.support.v4.view.ViewPager.OnPageChangeListener;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.R;
import com.android.systemui.WidgetSelectActivity;
import com.android.systemui.statusbar.WidgetPagerAdapter;
import com.android.systemui.statusbar.policy.KeyButtonView;
import com.android.systemui.statusbar.policy.buttons.ExtensibleKeyButtonView;

public class NavigationBarView extends LinearLayout {
    final static boolean DEBUG = false;
    final static String TAG = "PhoneStatusBar/NavigationBarView";

    final static boolean DEBUG_DEADZONE = false;

    final static boolean NAVBAR_ALWAYS_AT_RIGHT = true;

    final static boolean ANIMATE_HIDE_TRANSITION = false; // turned off because
                                                          // it introduces
                                                          // unsightly delay
                                                          // when videos goes to
                                                          // full screen

    protected IStatusBarService mBarService;
    final Display mDisplay;
    View mCurrentView = null;
    View[] mRotatedViews = new View[4];

    int mBarSize;
    boolean mVertical;

    int originalHeight = 0;

    boolean mHidden, mLowProfile, mShowMenu;
    int mDisabledFlags = 0;

    final static String ACTION_HOME = "**home**";
    final static String ACTION_BACK = "**back**";
    final static String ACTION_SEARCH = "**search**";
    final static String ACTION_MENU = "**menu**";
    final static String ACTION_POWER = "**power**";
    final static String ACTION_RECENTS = "**recents**";
    final static String ACTION_KILL = "**kill**";
    final static String ACTION_NULL = "**null**";

    int mNumberOfButtons = 3;

    public String[] mClickActions = new String[5];
    public String[] mLongpressActions = new String[5];
    public String[] mPortraitIcons = new String[5];

    public final static int StockButtonsQty = 3;
    public final static String[] StockClickActions = {
            "**back**", "**home**", "**recents**", "**null**", "**null**"
    };

    public final static String[] StockLongpress = {
            "**null**", "**null**", "**null**", "**null**", "**null**"
    };

    public final static int SHOW_LEFT_MENU = 1;
    public final static int SHOW_RIGHT_MENU = 0;
    public final static int SHOW_BOTH_MENU = 2;
    public final static int SHOW_DONT = 4;

    public final static int VISIBILITY_SYSTEM = 0;
    public final static int VISIBILITY_SYSTEM_AND_INVIZ = 3;
    public final static int VISIBILITY_NEVER = 1;
    public final static int VISIBILITY_ALWAYS = 2;

    public static final int KEY_MENU_RIGHT = 2;
    public static final int KEY_MENU_LEFT = 5;

    // workaround for LayoutTransitions leaving the nav buttons in a weird state
    // (bug 5549288)
    final static boolean WORKAROUND_INVALID_LAYOUT = true;
    final static int MSG_CHECK_INVALID_LAYOUT = 8686;

    public FrameLayout mPopupView;
    public WindowManager mWindowManager;

    private class H extends Handler {
        public void handleMessage(Message m) {
            switch (m.what) {
                case MSG_CHECK_INVALID_LAYOUT:
                    final String how = "" + m.obj;
                    final int w = getWidth();
                    final int h = getHeight();
                    final int vw = mCurrentView.getWidth();
                    final int vh = mCurrentView.getHeight();

                    if (h != vh || w != vw) {
                        Slog.w(TAG, String.format(
                                "*** Invalid layout in navigation bar (%s this=%dx%d cur=%dx%d)",
                                how, w, h, vw, vh));
                        if (WORKAROUND_INVALID_LAYOUT) {
                            requestLayout();
                        }
                    }
                    break;
            }
        }
    }

    private H mHandler = new H();

    public View getLeftMenuButton() {
        return mCurrentView.findViewById(R.id.menu_left);
    }

    public View getRightMenuButton() {
        return mCurrentView.findViewById(R.id.menu);
    }

    public View getSearchButton() {
        return mCurrentView.findViewById(R.id.search);
    }

    public View getRecentsButton() {
        return mCurrentView.findViewById(R.id.recent_apps);
    }

    public View getBackButton() {
        return mCurrentView.findViewById(R.id.back);
    }

    public View getHomeButton() {
        return mCurrentView.findViewById(R.id.home);
    }

    public View getBigMenuButton() {
        return mCurrentView.findViewById(R.id.menu_big);
    }

    public NavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);

        mHidden = false;

        mDisplay = ((WindowManager) context.getSystemService(
                Context.WINDOW_SERVICE)).getDefaultDisplay();
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        final Resources res = mContext.getResources();
        mBarSize = res.getDimensionPixelSize(R.dimen.navigation_bar_size);
        mVertical = false;
        mShowMenu = false;
        originalHeight = getHeight();
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(WidgetReceiver.ACTION_ALLOCATE_ID);
        filter.addAction(WidgetReceiver.ACTION_DEALLOCATE_ID);
        filter.addAction(WidgetReceiver.ACTION_TOGGLE_WIDGETS);
        context.registerReceiver(new WidgetReceiver(), filter);
    }

    FrameLayout rot0;
    FrameLayout rot90;

    private void makeBar() {

        ((LinearLayout) rot0.findViewById(R.id.nav_buttons)).removeAllViews();
        ((LinearLayout) rot0.findViewById(R.id.lights_out)).removeAllViews();
        ((LinearLayout) rot90.findViewById(R.id.nav_buttons)).removeAllViews();
        ((LinearLayout) rot90.findViewById(R.id.lights_out)).removeAllViews();

        for (int i = 0; i <= 1; i++) {
            boolean landscape = (i == 1);

            LinearLayout navButtonLayout = (LinearLayout) (landscape ? rot90
                    .findViewById(R.id.nav_buttons) : rot0
                    .findViewById(R.id.nav_buttons));

            LinearLayout lightsOut = (LinearLayout) (landscape ? rot90
                    .findViewById(R.id.lights_out) : rot0
                    .findViewById(R.id.lights_out));

            // add left menu
            if (currentSetting != SHOW_DONT) {
                View leftmenuKey = generateKey(landscape, KEY_MENU_LEFT);
                addButton(navButtonLayout, leftmenuKey, landscape);
                addLightsOutButton(lightsOut, leftmenuKey, landscape, true);
            }

            for (int j = 0; j < mNumberOfButtons; j++) {
                ExtensibleKeyButtonView v = generateKey(landscape, mClickActions[j],
                        mLongpressActions[j],
                        mPortraitIcons[j]);
                v.setTag((landscape ? "key_land_" : "key_") + j);

                addButton(navButtonLayout, v, landscape);
                addLightsOutButton(lightsOut, v, landscape, false);

                if (mNumberOfButtons == 3 && j != (mNumberOfButtons - 1)) {
                    // add separator view here
                    View separator = new View(mContext);
                    separator.setLayoutParams(getSeparatorLayoutParams(landscape));
                    addButton(navButtonLayout, separator, landscape);
                    addLightsOutButton(lightsOut, separator, landscape, true);
                }

            }

            // add right menu
            if (currentSetting != SHOW_DONT) {
                View rightMenuKey = generateKey(landscape, KEY_MENU_RIGHT);
                addButton(navButtonLayout, rightMenuKey, landscape);
                addLightsOutButton(lightsOut, rightMenuKey, landscape, true);
            }
        }
        createWidgetView();
    }

    private void addLightsOutButton(LinearLayout root, View v, boolean landscape, boolean empty) {

        ImageView addMe = new ImageView(mContext);
        addMe.setLayoutParams(v.getLayoutParams());
        addMe.setImageResource(empty ? R.drawable.ic_sysbar_lights_out_dot_large
                : R.drawable.ic_sysbar_lights_out_dot_small);
        addMe.setScaleType(ImageView.ScaleType.CENTER);
        addMe.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);

        if (landscape)
            root.addView(addMe, 0);
        else
            root.addView(addMe);

    }

    private void addButton(ViewGroup root, View addMe, boolean landscape) {
        if (landscape)
            root.addView(addMe, 0);
        else
            root.addView(addMe);
    }

    /*
     * TODO we can probably inflate each key from an XML would also be extremely
     * useful to themers, they may hate this for now
     */
    private View generateKey(boolean landscape, int keyId) {
        KeyButtonView v = null;
        Resources r = getResources();

        int btnWidth = 80;

        switch (keyId) {

            case KEY_MENU_RIGHT:
                v = new KeyButtonView(mContext, null);
                v.setLayoutParams(getLayoutParams(landscape, 40));

                v.setId(R.id.menu);
                v.setCode(KeyEvent.KEYCODE_MENU);
                v.setImageResource(landscape ? R.drawable.ic_sysbar_menu_land
                        : R.drawable.ic_sysbar_menu);
                v.setVisibility(View.INVISIBLE);
                v.setContentDescription(r.getString(R.string.accessibility_menu));
                v.setGlowBackground(landscape ? R.drawable.ic_sysbar_highlight_land
                        : R.drawable.ic_sysbar_highlight);
                return v;

            case KEY_MENU_LEFT:
                v = new KeyButtonView(mContext, null);
                v.setLayoutParams(getLayoutParams(landscape, 40));

                v.setId(R.id.menu_left);
                v.setCode(KeyEvent.KEYCODE_MENU);
                v.setImageResource(landscape ? R.drawable.ic_sysbar_menu_land
                        : R.drawable.ic_sysbar_menu);
                v.setVisibility(View.INVISIBLE);
                v.setContentDescription(r.getString(R.string.accessibility_menu));
                v.setGlowBackground(landscape ? R.drawable.ic_sysbar_highlight_land
                        : R.drawable.ic_sysbar_highlight);
                return v;

        }

        return null;
    }

    private ExtensibleKeyButtonView generateKey(boolean landscape, String clickAction,
            String longpress,
            String iconUri) {

        final int iconSize = 80;
        ExtensibleKeyButtonView v = new ExtensibleKeyButtonView(mContext, null, clickAction,
                longpress);
        Log.i("key.ext", "generated ex key: " + clickAction);
        v.setLayoutParams(getLayoutParams(landscape, iconSize));

        boolean drawableSet = false;

        if (iconUri != null) {
            if (iconUri.length() > 0) {
                // custom icon from the URI here
                File f = new File(Uri.parse(iconUri).getPath());
                if (f.exists()) {
                    v.setImageDrawable(new BitmapDrawable(getResources(), f.getAbsolutePath()));
                    drawableSet = true;
                }
            }
            if (!drawableSet && clickAction != null && !clickAction.startsWith("**")) {
                // here it's not a system action (**action**), so it must be an
                // app intent
                try {
                    Drawable d = mContext.getPackageManager().getActivityIcon(
                            Intent.parseUri(clickAction, 0));
                    final int[] appIconPadding = getAppIconPadding();
                    if (landscape)
                        v.setPaddingRelative(appIconPadding[1], appIconPadding[0],
                                appIconPadding[3], appIconPadding[2]);
                    else
                        v.setPaddingRelative(appIconPadding[0], appIconPadding[1],
                                appIconPadding[2], appIconPadding[3]);
                    v.setImageDrawable(d);
                    drawableSet = true;
                } catch (NameNotFoundException e) {
                    e.printStackTrace();
                    drawableSet = false;
                } catch (URISyntaxException e) {
                    e.printStackTrace();
                    drawableSet = false;
                }
            }
        }

        if (!drawableSet) {
            v.setImageDrawable(getNavbarIconImage(landscape, clickAction));
        }

        v.setGlowBackground(landscape ? R.drawable.ic_sysbar_highlight_land
                : R.drawable.ic_sysbar_highlight);
        return v;
    }

    private int[] getAppIconPadding() {
        int[] padding = new int[4];
        // left
        padding[0] = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2, getResources()
                .getDisplayMetrics());
        // top
        padding[1] = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, getResources()
                .getDisplayMetrics());
        // right
        padding[2] = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2, getResources()
                .getDisplayMetrics());
        // bottom
        padding[3] = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5,
                getResources()
                        .getDisplayMetrics());
        return padding;
    }

    private LayoutParams getLayoutParams(boolean landscape, float dp) {
        float px = dp * getResources().getDisplayMetrics().density;
        return landscape ?
                new LayoutParams(LayoutParams.MATCH_PARENT, (int) px, 1f) :
                new LayoutParams((int) px, LayoutParams.MATCH_PARENT, 1f);
    }

    private LayoutParams getSeparatorLayoutParams(boolean landscape) {
        float px = 25 * getResources().getDisplayMetrics().density;
        return landscape ?
                new LayoutParams(LayoutParams.MATCH_PARENT, (int) px) :
                new LayoutParams((int) px, LayoutParams.MATCH_PARENT);
    }

    View.OnTouchListener mLightsOutListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                // even though setting the systemUI visibility below will turn
                // these views
                // on, we need them to come up faster so that they can catch
                // this motion
                // event
                setLowProfile(false, false, false);

                try {
                    mBarService.setSystemUiVisibility(0);
                } catch (android.os.RemoteException ex) {
                }
            }
            return false;
        }
    };
    private int currentVisibility;
    private int currentSetting;

    public void setDisabledFlags(int disabledFlags) {
        setDisabledFlags(disabledFlags, false);
    }

    public void setDisabledFlags(int disabledFlags, boolean force) {
        if (!force && mDisabledFlags == disabledFlags)
            return;

        mDisabledFlags = disabledFlags;

        final boolean disableHome = ((disabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);
        final boolean disableRecent = ((disabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0);
        final boolean disableBack = ((disabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0);

        for (int j = 0; j < mNumberOfButtons; j++) {
            View v = (View) findViewWithTag((mVertical ? "key_land_" : "key_") + j);
            if (v != null) {
                int vid = v.getId();
                if (vid == R.id.back) {
                    v.setVisibility(disableBack ? View.INVISIBLE : View.VISIBLE);
                } else if (vid == R.id.recent_apps) {
                    v.setVisibility(disableRecent ? View.INVISIBLE : View.VISIBLE);
                } else { // treat all other buttons as same rule as home
                    v.setVisibility(disableHome ? View.INVISIBLE : View.VISIBLE);
                }

            }
        }
    }

    public void setMenuVisibility(final boolean show) {
        setMenuVisibility(show, false);
    }

    public void setMenuVisibility(final boolean show, final boolean force) {
        if (!force && mShowMenu == show)
            return;

        if (currentSetting == SHOW_DONT) {
            return;
        }

        mShowMenu = show;
        boolean localShow = show;

        ImageView leftButton = (ImageView) getLeftMenuButton();
        ImageView rightButton = (ImageView) getRightMenuButton();

        switch (currentVisibility) {
            case VISIBILITY_SYSTEM:
                leftButton
                        .setImageResource(mVertical ? R.drawable.ic_sysbar_menu_land
                                : R.drawable.ic_sysbar_menu);
                rightButton
                        .setImageResource(mVertical ? R.drawable.ic_sysbar_menu_land
                                : R.drawable.ic_sysbar_menu);
                break;
            case VISIBILITY_ALWAYS:
                leftButton
                        .setImageResource(mVertical ? R.drawable.ic_sysbar_menu_land
                                : R.drawable.ic_sysbar_menu);
                rightButton
                        .setImageResource(mVertical ? R.drawable.ic_sysbar_menu_land
                                : R.drawable.ic_sysbar_menu);
                localShow = true;
                break;
            case VISIBILITY_NEVER:
                leftButton
                        .setImageResource(R.drawable.ic_sysbar_menu_inviz);
                rightButton
                        .setImageResource(R.drawable.ic_sysbar_menu_inviz);
                localShow = true;
                break;
            case VISIBILITY_SYSTEM_AND_INVIZ:
                if (localShow) {
                    leftButton
                            .setImageResource(mVertical ? R.drawable.ic_sysbar_menu_land
                                    : R.drawable.ic_sysbar_menu);
                    ((ImageView) getRightMenuButton())
                            .setImageResource(mVertical ? R.drawable.ic_sysbar_menu_land
                                    : R.drawable.ic_sysbar_menu);
                } else {
                    localShow = true;
                    leftButton
                            .setImageResource(R.drawable.ic_sysbar_menu_inviz);
                    rightButton
                            .setImageResource(R.drawable.ic_sysbar_menu_inviz);
                }
                break;
        }

        // do this after just in case show was changed
        switch (currentSetting) {
            case SHOW_BOTH_MENU:
                getLeftMenuButton().setVisibility(localShow ? View.VISIBLE : View.INVISIBLE);
                getRightMenuButton().setVisibility(localShow ? View.VISIBLE : View.INVISIBLE);
                break;
            case SHOW_LEFT_MENU:
                getLeftMenuButton().setVisibility(localShow ? View.VISIBLE : View.INVISIBLE);
                getRightMenuButton().setVisibility(View.INVISIBLE);
                break;
            default:
            case SHOW_RIGHT_MENU:
                getLeftMenuButton().setVisibility(View.INVISIBLE);
                getRightMenuButton().setVisibility(localShow ? View.VISIBLE : View.INVISIBLE);
                break;
        }
    }

    public void setLowProfile(final boolean lightsOut) {
        setLowProfile(lightsOut, true, false);
    }

    public void setLowProfile(final boolean lightsOut, final boolean animate, final boolean force) {
        // if (!force && lightsOut == mLowProfile)
        // return;

        mLowProfile = lightsOut;

        if (DEBUG)
            Slog.d(TAG, "setting lights " + (lightsOut ? "out" : "on"));

        final View navButtons = mCurrentView.findViewById(R.id.nav_buttons);
        final View lowLights = mCurrentView.findViewById(R.id.lights_out);

        // ok, everyone, stop it right there
        navButtons.animate().cancel();
        lowLights.animate().cancel();

        if (!animate) {
            navButtons.setAlpha(lightsOut ? 0f : 1f);

            lowLights.setAlpha(lightsOut ? 1f : 0f);
            lowLights.setVisibility(lightsOut ? View.VISIBLE : View.GONE);
        } else {
            navButtons.animate()
                    .alpha(lightsOut ? 0f : 1f)
                    .setDuration(lightsOut ? 600 : 200)
                    .start();

            lowLights.setOnTouchListener(mLightsOutListener);
            if (lowLights.getVisibility() == View.GONE) {
                lowLights.setAlpha(0f);
                lowLights.setVisibility(View.VISIBLE);
            }
            lowLights.animate()
                    .alpha(lightsOut ? 1f : 0f)
                    .setStartDelay(lightsOut ? 500 : 0)
                    .setDuration(lightsOut ? 1000 : 300)
                    .setInterpolator(new AccelerateInterpolator(2.0f))
                    .setListener(lightsOut ? null : new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator _a) {
                            lowLights.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
    }

    public void setHidden(final boolean hide) {
        if (hide == mHidden)
            return;

        mHidden = hide;
        Slog.d(TAG,
                (hide ? "HIDING" : "SHOWING") + " navigation bar");

        // bring up the lights no matter what
        setLowProfile(false);
    }

    public void onFinishInflate() {
        rot0 = (FrameLayout) findViewById(R.id.rot0);
        rot90 = (FrameLayout) findViewById(R.id.rot90);

        mRotatedViews[Surface.ROTATION_0] =
                mRotatedViews[Surface.ROTATION_180] = findViewById(R.id.rot0);

        mRotatedViews[Surface.ROTATION_90] = findViewById(R.id.rot90);

        mRotatedViews[Surface.ROTATION_270] = NAVBAR_ALWAYS_AT_RIGHT
                ? findViewById(R.id.rot90)
                : findViewById(R.id.rot270);

        for (View v : mRotatedViews) {
            // this helps avoid drawing artifacts with glowing navigation keys
            ViewGroup group = (ViewGroup) v.findViewById(R.id.nav_buttons);
            group.setMotionEventSplittingEnabled(false);
        }
        mCurrentView = mRotatedViews[Surface.ROTATION_0];

        // this takes care of making the buttons
        SettingsObserver settingsObserver = new SettingsObserver(new Handler());
        settingsObserver.observe();

    }

    public void reorient() {
        final int rot = mDisplay.getRotation();
        for (int i = 0; i < mRotatedViews.length; i++) {
            mRotatedViews[i].setVisibility(View.GONE);
        }
        mCurrentView = mRotatedViews[rot];
        mCurrentView.setVisibility(View.VISIBLE);
        mVertical = (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270);

        // force the low profile & disabled states into compliance
        setLowProfile(mLowProfile, false, true /* force */);
        setDisabledFlags(mDisabledFlags, true /* force */);
        setMenuVisibility(mShowMenu, true /* force */);

        if (DEBUG_DEADZONE) {
            mCurrentView.findViewById(R.id.deadzone).setBackgroundColor(0x808080FF);
        }

        if (DEBUG) {
            Slog.d(TAG, "reorient(): rot=" + mDisplay.getRotation());
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (DEBUG)
            Slog.d(TAG, String.format(
                    "onSizeChanged: (%dx%d) old: (%dx%d)", w, h, oldw, oldh));
        postCheckForInvalidLayout("sizeChanged");
        super.onSizeChanged(w, h, oldw, oldh);
    }

    private void postCheckForInvalidLayout(final String how) {
        mHandler.obtainMessage(MSG_CHECK_INVALID_LAYOUT, 0, 0, how).sendToTarget();
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            // resolver.registerContentObserver(
            // Settings.System.getUriFor(Settings.System.NAVIGATION_BAR_BUTTONS),
            // false,
            // this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.MENU_LOCATION), false,
                    this);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.MENU_VISIBILITY), false,
                    this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NAVIGATION_BAR_BUTTONS_QTY), false,
                    this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NAVIGATION_BAR_GLOW_TINT), false,
                    this);

            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NAVIGATION_BAR_WIDGETS),
                    false,
                    this);

            for (int j = 0; j < 5; j++) { // watch all 5 settings for changes.
                resolver.registerContentObserver(
                        Settings.System.getUriFor(Settings.System.NAVIGATION_CUSTOM_ACTIVITIES[j]),
                        false,
                        this);
                resolver.registerContentObserver(
                        Settings.System
                                .getUriFor(Settings.System.NAVIGATION_LONGPRESS_ACTIVITIES[j]),
                        false,
                        this);
                resolver.registerContentObserver(
                        Settings.System.getUriFor(Settings.System.NAVIGATION_CUSTOM_APP_ICONS[j]),
                        false,
                        this);
            }
            updateSettings();
        }

        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
    }

    protected void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();

        // userNavBarButtons = Settings.System.getString(resolver,
        // Settings.System.NAVIGATION_BAR_BUTTONS);

        currentSetting = Settings.System.getInt(resolver,
                Settings.System.MENU_LOCATION, SHOW_RIGHT_MENU);

        currentVisibility = Settings.System.getInt(resolver,
                Settings.System.MENU_VISIBILITY, VISIBILITY_SYSTEM);

        mNumberOfButtons = Settings.System.getInt(resolver,
                Settings.System.NAVIGATION_BAR_BUTTONS_QTY, 0);
        if (mNumberOfButtons == 0) {
            mNumberOfButtons = StockButtonsQty;
            Settings.System.putInt(resolver,
                    Settings.System.NAVIGATION_BAR_BUTTONS_QTY, StockButtonsQty);
        }

        for (int j = 0; j < 5; j++) {
            mClickActions[j] = Settings.System.getString(resolver,
                    Settings.System.NAVIGATION_CUSTOM_ACTIVITIES[j]);
            if (mClickActions[j] == null) {
                mClickActions[j] = StockClickActions[j];
                Settings.System.putString(resolver,
                        Settings.System.NAVIGATION_CUSTOM_ACTIVITIES[j], mClickActions[j]);
            }

            mLongpressActions[j] = Settings.System.getString(resolver,
                    Settings.System.NAVIGATION_LONGPRESS_ACTIVITIES[j]);
            if (mLongpressActions[j] == null) {
                mLongpressActions[j] = StockLongpress[j];
                Settings.System.putString(resolver,
                        Settings.System.NAVIGATION_LONGPRESS_ACTIVITIES[j], mLongpressActions[j]);
            }
            mPortraitIcons[j] = Settings.System.getString(resolver,
                    Settings.System.NAVIGATION_CUSTOM_APP_ICONS[j]);
            if (mPortraitIcons[j] == null) {
                mPortraitIcons[j] = "";
                Settings.System.putString(resolver,
                        Settings.System.NAVIGATION_CUSTOM_APP_ICONS[j], "");
            }
        }

        String settingWidgets = Settings.System.getString(resolver,
                Settings.System.NAVIGATION_BAR_WIDGETS);
        if (settingWidgets != null && settingWidgets.length() > 0) {
            String[] split = settingWidgets.split("\\|");
            widgetIds = new int[split.length];
            for (int i = 0; i < widgetIds.length; i++) {
                widgetIds[i] = Integer.parseInt(split[i]);
            }
            Log.d(TAG,"Made Widgets:"+ widgetIds.length);
        }

        makeBar();

    }

    private Drawable getNavbarIconImage(boolean landscape, String uri) {

        if (uri == null)
            return getResources().getDrawable(R.drawable.ic_sysbar_null);

        if (uri.startsWith("**")) {
            if (uri.equals(ACTION_HOME)) {

                return getResources().getDrawable(R.drawable.ic_sysbar_home);
            } else if (uri.equals(ACTION_BACK)) {

                return getResources().getDrawable(R.drawable.ic_sysbar_back);
            } else if (uri.equals(ACTION_RECENTS)) {

                return getResources().getDrawable(R.drawable.ic_sysbar_recent);
            } else if (uri.equals(ACTION_SEARCH)) {

                return getResources().getDrawable(R.drawable.ic_sysbar_search);
            } else if (uri.equals(ACTION_MENU)) {

                return getResources().getDrawable(R.drawable.ic_sysbar_menu_big);
            } else if (uri.equals(ACTION_KILL)) {

                return getResources().getDrawable(R.drawable.ic_sysbar_killtask);
            } else if (uri.equals(ACTION_POWER)) {

                return getResources().getDrawable(R.drawable.ic_sysbar_power);
            }
        }

        return getResources().getDrawable(R.drawable.ic_sysbar_null);
    }

    boolean showing = false;

    public void toggleWidgetView() {
        if (showing) {
            if (mPopupView != null) {
                mAdapter.onHide();
                mWindowManager.removeView(mPopupView);
                showing = false;
            }
        } else {
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.BOTTOM;
            params.setTitle("Widgets");
            if (mWindowManager != null && mAdapter !=null){
            	mWindowManager.addView(mPopupView, params);
            	mAdapter.onShow();
            	showing = true;
            } else {
            	Log.e(TAG,"WTF - ToggleWidget when no pager or window manager exist?");
            }
        }
    }

    ViewPager mWidgetPager;
    WidgetPagerAdapter mAdapter;
    int widgetIds[];

    public void createWidgetView() {
        mPopupView = new FrameLayout(mContext);
        View widgetView = View.inflate(mContext, R.layout.navigation_bar_expanded, null);
        mPopupView.addView(widgetView);
        mWidgetPager = (ViewPager) widgetView.findViewById(R.id.pager);
        mWidgetPager.setAdapter(mAdapter = new WidgetPagerAdapter(mContext, widgetIds));
        mWidgetPager.setOnPageChangeListener(mNewPageListener);

        int dp = mAdapter.getHeight(mWidgetPager.getCurrentItem());
        float px = dp * getResources().getDisplayMetrics().density;
        mWidgetPager.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, (int) px));

        mPopupView.setOnTouchListener(new View.OnTouchListener() {

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                    toggleWidgetView();
                    return true;
                }
                return false;
            }
        });

    }

    public OnPageChangeListener mNewPageListener = new OnPageChangeListener() {

        @Override
        public void onPageSelected(int page) {
            int dp = mAdapter.getHeight(page);
            float px = dp * getResources().getDisplayMetrics().density;
            mWidgetPager.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, (int) px));
            TextView tv = (TextView) mPopupView.findViewById(R.id.widgetlabel);
            if (tv != null) {
            	tv.setText(mAdapter.getLabel(page));
            }
        }

        @Override
        public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {

        }

        @Override
        public void onPageScrollStateChanged(int arg0) {

        }
    };

    public class WidgetReceiver extends BroadcastReceiver {

        public static final String ACTION_ALLOCATE_ID = "com.android.systemui.ACTION_ALLOCATE_ID";
        public static final String ACTION_DEALLOCATE_ID = "com.android.systemui.ACTION_DEALLOCATE_ID";
        public static final String ACTION_TOGGLE_WIDGETS = "com.android.systemui.ACTION_TOGGLE_WIDGETS";

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (ACTION_ALLOCATE_ID.equals(action)) {
                int appWidgetId = mAdapter.mAppWidgetHost.allocateAppWidgetId();

                Intent select = new Intent(context, WidgetSelectActivity.class);
                select.putExtra("selected_widget_id", appWidgetId);
                select.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(select);

            } else if (ACTION_DEALLOCATE_ID.equals(action)) {
                int appWidgetId =
                        intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
                if (appWidgetId != -1) {
                    mAdapter.mAppWidgetHost.deleteAppWidgetId(appWidgetId);
                }
            } else if (ACTION_TOGGLE_WIDGETS.equals(action)) {
                toggleWidgetView();
            }
        }
    }
}
