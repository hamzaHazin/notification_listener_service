package notification.listener.service;

import static notification.listener.service.NotificationUtils.isPermissionGranted;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.content.ActivityNotFoundException;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import notification.listener.service.models.Action;
import notification.listener.service.models.ActionCache;

import java.util.List;
import java.util.Map;

public class NotificationListenerServicePlugin implements FlutterPlugin, ActivityAware, MethodCallHandler, PluginRegistry.ActivityResultListener, EventChannel.StreamHandler {

    private static final String TAG = "NotifListenerPlugin";
    private static final String CHANNEL_TAG = "x-slayer/notifications_channel";
    private static final String EVENT_TAG = "x-slayer/notifications_event";

    private MethodChannel channel;
    private EventChannel eventChannel;
    private NotificationReceiver notificationReceiver;
    private Context context;
    private Activity mActivity;
    private EventChannel.EventSink eventSink;

    private Result pendingResult;
    final int REQUEST_CODE_FOR_NOTIFICATIONS = 1199;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        context = flutterPluginBinding.getApplicationContext();
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), CHANNEL_TAG);
        channel.setMethodCallHandler(this);
        eventChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), EVENT_TAG);
        eventChannel.setStreamHandler(this);
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        Log.d(TAG, "onMethodCall: " + call.method);
        if (call.method.equals("isPermissionGranted")) {
            result.success(isPermissionGranted(context));
        } else if (call.method.equals("requestPermission")) {
            Log.d(TAG, "Setting pendingResult for requestPermission: " + result);
            pendingResult = result;
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            try {
                mActivity.startActivityForResult(intent, REQUEST_CODE_FOR_NOTIFICATIONS);
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "ActivityNotFoundException: " + e.getMessage());
                pendingResult.error("ACTIVITY_NOT_FOUND", "No activity found to handle notification listener settings", null);
                pendingResult = null;
            }
        } else if (call.method.equals("sendReply")) {
            final String message = call.argument("message");
            final int notificationId = call.argument("notificationId");

            final Action action = ActionCache.cachedNotifications.get(notificationId);
            if (action == null) {
                result.error("Notification", "Can't find this cached notification", null);
                return;
            }
            try {
                action.sendReply(context, message);
                result.success(true);
            } catch (PendingIntent.CanceledException e) {
                result.success(false);
                e.printStackTrace();
            }
        } else if (call.method.equals("getActiveNotifications")) {
            NotificationListener service = NotificationListener.getInstance();
            if (service != null) {
                List<Map<String, Object>> notifications = service.getActiveNotificationData();
                result.success(notifications);
            } else {
                result.error("ServiceUnavailable", "NotificationService not running", null);
            }
        } else if (call.method.equals("startListening")) {
            startListening();
            result.success(true);
        } else if (call.method.equals("stopListening")) {
            stopListening();
            result.success(true);
        } else {
            result.notImplemented();
        }
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        eventChannel.setStreamHandler(null);
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        this.mActivity = binding.getActivity();
        binding.addActivityResultListener(this);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        this.mActivity = null;
    }

    @SuppressLint("WrongConstant")
    private void startListening() {
        if (notificationReceiver == null) {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(NotificationConstants.INTENT);
            notificationReceiver = new NotificationReceiver(eventSink);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                context.registerReceiver(notificationReceiver, intentFilter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(notificationReceiver, intentFilter);
            }
        }
        Intent listenerIntent = new Intent(context, NotificationListener.class);
        context.startService(listenerIntent);
        NotificationListener.setRunning(true);
        Log.i(TAG, "Started the notifications tracking service.");
    }

    private void stopListening() {
        if (notificationReceiver != null) {
            try {
                context.unregisterReceiver(notificationReceiver);
            } catch (Exception e) {
                // Ignore
            }
            notificationReceiver = null;
        }
        NotificationListener.setRunning(false);
        Log.i(TAG, "Stopped the notifications tracking service.");
    }

    @Override
    public void onListen(Object arguments, EventChannel.EventSink events) {
        Log.d(TAG, "onListen called");
        this.eventSink = events;
    }

    @Override
    public void onCancel(Object arguments) {
        Log.d(TAG, "onCancel called");
        stopListening();
        eventSink = null;
    }

    @Override
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(TAG, "onActivityResult. requestCode: " + requestCode + ", resultCode: " + resultCode + ", pendingResult: " + pendingResult);
        if (pendingResult == null) {
            Log.w(TAG, "onActivityResult called with a null pendingResult.");
            return false;
        }

        if (requestCode == REQUEST_CODE_FOR_NOTIFICATIONS) {
            try {
                if (resultCode == Activity.RESULT_OK) {
                    pendingResult.success(true);
                } else if (resultCode == Activity.RESULT_CANCELED) {
                    pendingResult.success(isPermissionGranted(context));
                } else {
                    pendingResult.success(false);
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error delivering result in onActivityResult: " + e.getMessage());
            }
            Log.d(TAG, "Clearing pendingResult.");
            pendingResult = null;
            return true;
        }
        return false;
    }
}
