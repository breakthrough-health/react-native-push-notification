package com.dieam.reactnativepushnotification.modules;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;

import com.dieam.reactnativepushnotification.helpers.ApplicationBadgeHelper;
import com.facebook.react.ReactApplication;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;

import org.json.JSONObject;

import java.util.Map;
import java.util.List;
import java.util.Random;

import static android.content.Context.ACTIVITY_SERVICE;
import static com.dieam.reactnativepushnotification.modules.RNPushNotification.LOG_TAG;

public class RNReceivedMessageHandler {
    private FirebaseMessagingService mFirebaseMessagingService;

    public RNReceivedMessageHandler(@NonNull FirebaseMessagingService service) {
        this.mFirebaseMessagingService = service;
    }

    public void handleReceivedMessage(RemoteMessage message) {
        String from = message.getFrom();
        RemoteMessage.Notification remoteNotification = message.getNotification();
        final Bundle bundle = new Bundle();
        // Putting it from remoteNotification first so it can be overriden if message
        // data has it
        if (remoteNotification != null) {
            // ^ It's null when message is from GCM
            bundle.putString("title", remoteNotification.getTitle());
            bundle.putString("message", remoteNotification.getBody());
            bundle.putString("sound", remoteNotification.getSound());
            bundle.putString("color", remoteNotification.getColor());
        }

        Map<String, String> notificationData = message.getData();
        JSONObject btxNotificationData = null;
        if (notificationData.containsKey("btx_notification")) {
            btxNotificationData = getPushData(notificationData.get("btx_notification"));
        }

        if (btxNotificationData != null) {
            if (!bundle.containsKey("message")) {
                bundle.putString("message", btxNotificationData.optString("message", null));
            }
            if (!bundle.containsKey("title")) {
                bundle.putString("title", btxNotificationData.optString("title", null));
            }
            if (!bundle.containsKey("sound")) {
                bundle.putString("soundName", btxNotificationData.optString("soundName", null));
            }
            if (!bundle.containsKey("color")) {
                bundle.putString("color", btxNotificationData.optString("color", null));
            }

            final int badge = btxNotificationData.optInt("badge", -1);
            if (badge >= 0) {
                ApplicationBadgeHelper.INSTANCE.setApplicationIconBadgeNumber(mFirebaseMessagingService, badge);
            }
        }

        Bundle dataBundle = new Bundle();
        for(Map.Entry<String, String> entry : notificationData.entrySet()) {
            dataBundle.putString(entry.getKey(), entry.getValue());
        }
        bundle.putParcelable("data", dataBundle);

        Log.v(LOG_TAG, "onMessageReceived: " + bundle);

        // We need to run this on the main thread, as the React code assumes that is true.
        // Namely, DevServerHelper constructs a Handler() without a Looper, which triggers:
        // "Can't create handler inside thread that has not called Looper.prepare()"
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            public void run() {
                // Construct and load our normal React JS code bundle
                ReactInstanceManager mReactInstanceManager = ((ReactApplication) mFirebaseMessagingService.getApplication()).getReactNativeHost().getReactInstanceManager();
                ReactContext context = mReactInstanceManager.getCurrentReactContext();
                // If it's constructed, send a notificationre
                if (context != null) {
                    handleRemotePushNotification((ReactApplicationContext) context, bundle);
                } else {
                    // Otherwise wait for construction, then send the notification
                    mReactInstanceManager.addReactInstanceEventListener(new ReactInstanceManager.ReactInstanceEventListener() {
                        public void onReactContextInitialized(ReactContext context) {
                            handleRemotePushNotification((ReactApplicationContext) context, bundle);
                        }
                    });
                    if (!mReactInstanceManager.hasStartedCreatingInitialContext()) {
                        // Construct it in the background
                        mReactInstanceManager.createReactContextInBackground();
                    }
                }
            }
        });
    }

    private JSONObject getPushData(String dataString) {
        try {
            return new JSONObject(dataString);
        } catch (Exception e) {
            return null;
        }
    }

    private void handleRemotePushNotification(ReactApplicationContext context, Bundle bundle) {

        // If notification ID is not provided by the user for push notification, generate one at random
        if (bundle.getString("id") == null) {
            Random randomNumberGenerator = new Random(System.currentTimeMillis());
            bundle.putString("id", String.valueOf(randomNumberGenerator.nextInt()));
        }

        RNPushNotificationConfig config = new RNPushNotificationConfig(mFirebaseMessagingService.getApplication());

        Boolean isForeground = isApplicationInForeground();

        RNPushNotificationJsDelivery jsDelivery = new RNPushNotificationJsDelivery(context);
        bundle.putBoolean("foreground", isForeground);
        bundle.putBoolean("userInteraction", false);

        Log.i(LOG_TAG, "Notification prepared: " + bundle);
        Log.i(LOG_TAG, "Foreground info -> config.getNotificationForeground(): " + config.getNotificationForeground() + "Foreground: " + isForeground);

        if (config.getNotificationForeground() || !isForeground) {
            Log.i(LOG_TAG, "Sending out notification");
            Application applicationContext = (Application) context.getApplicationContext();
            RNPushNotificationHelper pushNotificationHelper = new RNPushNotificationHelper(applicationContext);
            pushNotificationHelper.sendToNotificationCentre(bundle);
        } else if (isForeground) {
            // In case something received while the app is being used
            bundle.putBoolean("notification_clicked", true);
            jsDelivery.notifyNotification(bundle);
        }
    }

    private boolean isApplicationInForeground() {
        ActivityManager activityManager = (ActivityManager) mFirebaseMessagingService.getSystemService(ACTIVITY_SERVICE);
        List<RunningAppProcessInfo> processInfos = activityManager.getRunningAppProcesses();
        if (processInfos != null) {
            for (RunningAppProcessInfo processInfo : processInfos) {
                if (processInfo.processName.equals(mFirebaseMessagingService.getPackageName())
                    && processInfo.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                    && processInfo.pkgList.length > 0) {
                    return true;
                }
            }
        }
        return false;
    }

}
