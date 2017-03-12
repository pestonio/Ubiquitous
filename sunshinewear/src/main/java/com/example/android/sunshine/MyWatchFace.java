/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import static android.view.View.GONE;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, DataApi.DataListener {
        private static final String COLON = ":";
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        final String WEATHER_PATH = "/weather";
        final String MAX_TEMP = "com.example.android.sunshine.max_temp";
        final String MIN_TEMP = "com.example.android.sunshine.min_temp";
        final String ICON = "com.example.android.sunshine.icon";
        private final Point displaySize = new Point();
        boolean mRegisteredTimeZoneReceiver = false;
        //        Paint mBackgroundPaint;
//        Paint mTextPaint;
        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;
        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        private int specW, specH;
        private View myLayout;
        private View parentView;
        private TextView day, date, month, year, hour, minute, maxTemp, minTemp, timeColon;
        private ImageView weatherIconView;
        private boolean mShouldDrawColon;
        private String highTemp;
        private String lowTemp;
        private Bitmap weatherIcon;
        private GoogleApiClient mGoogleApiClient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            myLayout = inflater.inflate(R.layout.watch_face, null);

            Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
            display.getSize(displaySize);

            specW = View.MeasureSpec.makeMeasureSpec(displaySize.x, View.MeasureSpec.EXACTLY);
            specH = View.MeasureSpec.makeMeasureSpec(displaySize.y, View.MeasureSpec.EXACTLY);

            day = (TextView) myLayout.findViewById(R.id.watch_face_day);
            month = (TextView) myLayout.findViewById(R.id.watch_face_month);
            date = (TextView) myLayout.findViewById(R.id.watch_face_date);
            year = (TextView) myLayout.findViewById(R.id.watch_face_year);
            hour = (TextView) myLayout.findViewById(R.id.watch_face_hours);
            minute = (TextView) myLayout.findViewById(R.id.watch_face_minute);
            parentView = (View) myLayout.findViewById(R.id.watch_face_parent);
            maxTemp = (TextView) myLayout.findViewById(R.id.watch_face_max_temp);
            minTemp = (TextView) myLayout.findViewById(R.id.watch_face_min_temp);
            weatherIconView = (ImageView) myLayout.findViewById(R.id.watch_face_weather_icon);
            timeColon = (TextView) myLayout.findViewById(R.id.watch_face_colon);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = MyWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mCalendar = Calendar.getInstance();

            mGoogleApiClient = new GoogleApiClient.Builder(MyWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
            mGoogleApiClient.connect();

        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }


        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

//            mTextPaint.setTextSize(textSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long currentTime = System.currentTimeMillis();
            mCalendar.setTimeInMillis(currentTime);
            mShouldDrawColon = (System.currentTimeMillis() % 1000) < 500;
            String weekDay;
            String currentMonth;
            String currentDate;
            String currentYr;
            String currentHr;
            String currentMin;
            SimpleDateFormat dayFormat = new SimpleDateFormat("E", Locale.getDefault());
            SimpleDateFormat dateFormat = new SimpleDateFormat("dd", Locale.getDefault());
            SimpleDateFormat monthFormat = new SimpleDateFormat("MMM", Locale.getDefault());
            SimpleDateFormat yrFormat = new SimpleDateFormat("y", Locale.getDefault());
            SimpleDateFormat hourFormat = new SimpleDateFormat("HH", Locale.getDefault());
            SimpleDateFormat minFormat = new SimpleDateFormat("mm", Locale.getDefault());

            weekDay = dayFormat.format(mCalendar.getTime());
            currentMonth = monthFormat.format(mCalendar.getTime());
            currentDate = dateFormat.format(mCalendar.getTime());
            currentYr = yrFormat.format(mCalendar.getTime());
            currentHr = hourFormat.format(mCalendar.getTime());
            currentMin = minFormat.format(mCalendar.getTime());

            day.setText(weekDay);
            date.setText(currentDate);
            month.setText(currentMonth);
            year.setText(currentYr);
            hour.setText(currentHr);
            minute.setText(currentMin);
            if (highTemp != null) {
                maxTemp.setText(highTemp);
            }
            if (lowTemp != null) {
                minTemp.setText(lowTemp);
            }
            if (weatherIcon != null){
                weatherIconView.setImageBitmap(weatherIcon);
            }

            myLayout.measure(specW, specH);
            myLayout.layout(0, 0, myLayout.getMeasuredWidth(),
                    myLayout.getMeasuredHeight());
            if (isInAmbientMode()) {
                parentView.setBackgroundColor(getColor(R.color.black));
                date.setVisibility(GONE);
                month.setVisibility(GONE);
                year.setVisibility(GONE);
                day.setVisibility(GONE);
                minTemp.setVisibility(GONE);
                maxTemp.setVisibility(GONE);
                myLayout.draw(canvas);
            } else {
                if (mShouldDrawColon) {
                    timeColon.setTextColor(getColor(R.color.white));
                    timeColon.setText(COLON);
                } else {
                    timeColon.setTextColor(getColor(R.color.background));
                }
                canvas.drawColor(Color.BLACK);
                myLayout.draw(canvas);
            }
            invalidate();
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }


        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.v("MWF_onConnected", "Connected to API " + bundle);
            Wearable.DataApi.addListener(mGoogleApiClient, this);

        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.v("MWF_onConnected", "Called ");

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.v("MWF_onConnectionFailed", "Called " + connectionResult.getErrorMessage());

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.v("LOG", "onDataChanged wFace called");
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED && event.getDataItem()
                        .getUri()
                        .getPath()
                        .equals(WEATHER_PATH)) {
                    DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
                    final Asset icon = dataMapItem.getDataMap().getAsset(ICON);
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            weatherIcon = loadBitmapFromAsset(icon);

                        }
                    }).start();
                    highTemp = dataMapItem.getDataMap().getString(MAX_TEMP);
                    lowTemp = dataMapItem.getDataMap().getString(MIN_TEMP);
                }

            }
        }

        public Bitmap loadBitmapFromAsset(Asset asset) {
            if (asset == null) {
                throw new IllegalArgumentException("Asset must be non-null");
            }
            InputStream inputStream = Wearable
                    .DataApi.getFdForAsset(mGoogleApiClient, asset)
                    .await()
                    .getInputStream();
            mGoogleApiClient.disconnect();
            if (inputStream == null) {
                return null;
            }
            return BitmapFactory.decodeStream(inputStream);
        }
    }
}
