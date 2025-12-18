// Updated for X-Core
package dev.dev7.lib.v2ray;

import static android.Manifest.permission.POST_NOTIFICATIONS;
import static android.content.Context.RECEIVER_EXPORTED;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.V2RAY_SERVICE_COMMAND_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.V2RAY_SERVICE_COMMAND_INTENT;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.V2RAY_SERVICE_CONFIG_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.SERVICE_CONNECTION_STATE_BROADCAST_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.V2RAY_SERVICE_CURRENT_CONFIG_DELAY_BROADCAST_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.V2RAY_SERVICE_CURRENT_CONFIG_DELAY_BROADCAST_INTENT;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.V2RAY_SERVICE_STATICS_BROADCAST_INTENT;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.SERVICE_TYPE_BROADCAST_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConfigs.connectionState;
import static dev.dev7.lib.v2ray.utils.V2rayConfigs.currentConfig;
import static dev.dev7.lib.v2ray.utils.V2rayConfigs.serviceMode;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.PermissionChecker;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.dev7.lib.v2ray.core.V2rayCoreExecutor;
import dev.dev7.lib.v2ray.interfaces.LatencyDelayListener;
import dev.dev7.lib.v2ray.services.V2rayProxyService;
import dev.dev7.lib.v2ray.services.V2rayVPNService;
import dev.dev7.lib.v2ray.utils.V2rayConfigs;
import dev.dev7.lib.v2ray.utils.Utilities;
import dev.dev7.lib.v2ray.utils.V2rayConstants;
import libv2ray.Libv2ray;

public final class V2rayController {
    private static final String TAG = V2rayController.class.getSimpleName();
    private static ActivityResultLauncher<Intent> activityResultLauncher;
    private static Context sAppContext;
    private static final AtomicBoolean sReceiversRegistered = new AtomicBoolean(false);

    static final BroadcastReceiver stateUpdaterBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                Object stateExtra = intent.getExtras() != null ? intent.getExtras().getSerializable(SERVICE_CONNECTION_STATE_BROADCAST_EXTRA) : null;
                if (stateExtra instanceof V2rayConstants.CONNECTION_STATES) {
                    connectionState = (V2rayConstants.CONNECTION_STATES) stateExtra;
                }
                String serviceType = intent.getStringExtra(SERVICE_TYPE_BROADCAST_EXTRA);
                if (V2rayProxyService.class.getSimpleName().equals(serviceType)) {
                    V2rayConfigs.serviceMode = V2rayConstants.SERVICE_MODES.PROXY_MODE;
                } else if (serviceType != null) {
                    V2rayConfigs.serviceMode = V2rayConstants.SERVICE_MODES.VPN_MODE;
                }
            } catch (Exception e) {
                Log.w(TAG, "stateUpdaterBroadcastReceiver error", e);
            }
        }
    };

    private V2rayController() {
    }

    public static void init(final AppCompatActivity activity, final int app_icon, final String app_name) {
        if (activity == null) {
            return;
        }
        sAppContext = activity.getApplicationContext();
        Utilities.copyAssets(activity);
        try {
            currentConfig.applicationIcon = app_icon;
        } catch (Exception e) {
            Log.w(TAG, "init icon set failed", e);
        }
        try {
            currentConfig.applicationName = app_name;
        } catch (Exception e) {
            Log.w(TAG, "init app name set failed", e);
        }
        registerReceivers(activity.getApplicationContext());
        activityResultLauncher = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK) {
                startTunnel(activity.getApplicationContext());
            } else {
                Toast.makeText(activity, "Permission not granted.", Toast.LENGTH_LONG).show();
            }
        });
    }

    public static void init(final Context context) {
        if (context == null) {
            return;
        }
        sAppContext = context.getApplicationContext();
        Utilities.copyAssets(sAppContext);
        registerReceivers(sAppContext);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public static void registerReceivers(final Context context) {
        if (context == null) {
            return;
        }
        if (sReceiversRegistered.get()) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(stateUpdaterBroadcastReceiver, new IntentFilter(V2RAY_SERVICE_STATICS_BROADCAST_INTENT), RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(stateUpdaterBroadcastReceiver, new IntentFilter(V2RAY_SERVICE_STATICS_BROADCAST_INTENT));
            }
            sReceiversRegistered.set(true);
        } catch (Exception e) {
            Log.w(TAG, "registerReceivers", e);
        }
    }

    public static V2rayConstants.CONNECTION_STATES getConnectionState() {
        if (connectionState == null) {
            return V2rayConstants.CONNECTION_STATES.DISCONNECTED;
        }
        return connectionState;
    }

    public static boolean isPreparedForConnection(final Context context) {
        if (context == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS) != PermissionChecker.PERMISSION_GRANTED) {
                return false;
            }
        }
        Intent vpnServicePrepareIntent = VpnService.prepare(context);
        return vpnServicePrepareIntent == null;
    }

    private static void prepareForConnection(final Activity activity) {
        if (activity == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, POST_NOTIFICATIONS) != PermissionChecker.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity, new String[]{POST_NOTIFICATIONS}, 101);
                return;
            }
        }
        Intent vpnServicePrepareIntent = VpnService.prepare(activity);
        if (vpnServicePrepareIntent != null) {
            if (activityResultLauncher != null) {
                activityResultLauncher.launch(vpnServicePrepareIntent);
            }
        }
    }

    public static void startV2ray(final Activity activity, final String remark, final String config, final ArrayList<String> blocked_apps) {
        if (activity == null) {
            return;
        }
        if (!Utilities.refillV2rayConfig(remark, config, blocked_apps)) {
            return;
        }
        registerReceivers(activity.getApplicationContext());
        if (!isPreparedForConnection(activity)) {
            prepareForConnection(activity);
        } else {
            connectionState = V2rayConstants.CONNECTION_STATES.CONNECTING;
            startTunnel(activity.getApplicationContext());
        }
    }

    public static void startV2ray(final Context context, final String remark, final String config, final LatencyDelayListener latencyDelayListener) {
        if (context == null) {
            if (latencyDelayListener != null) {
                latencyDelayListener.OnResultReady(-1);
            }
            return;
        }
        if (!Utilities.refillV2rayConfig(remark, config, null)) {
            if (latencyDelayListener != null) {
                latencyDelayListener.OnResultReady(-1);
            }
            return;
        }
        registerReceivers(context.getApplicationContext());
        connectionState = V2rayConstants.CONNECTION_STATES.CONNECTING;
        startTunnel(context.getApplicationContext());
        if (latencyDelayListener != null) {
            getConnectedV2rayServerDelay(context.getApplicationContext(), latencyDelayListener);
        }
    }

    public static void startV2ray(final Context context, final String config, final V2rayConstants.SERVICE_MODES mode) {
        if (context == null) {
            return;
        }
        if (!Utilities.refillV2rayConfig("", config, null)) {
            return;
        }
        registerReceivers(context.getApplicationContext());
        serviceMode = mode == null ? V2rayConstants.SERVICE_MODES.VPN_MODE : mode;
        connectionState = V2rayConstants.CONNECTION_STATES.CONNECTING;
        startTunnel(context.getApplicationContext());
    }

    public static long getConnectedV2rayServerDelay(final Context context) {
        final long[] result = new long[]{-1};
        final Object lock = new Object();
        getConnectedV2rayServerDelay(context, delay -> {
            synchronized (lock) {
                result[0] = delay;
                lock.notifyAll();
            }
        });
        try {
            synchronized (lock) {
                lock.wait(1500);
            }
        } catch (InterruptedException ignored) {
        }
        return result[0];
    }

    public static void stopV2ray(final Context context) {
        if (context == null) {
            return;
        }
        try {
            context.stopService(new Intent(context, V2rayVPNService.class));
            context.stopService(new Intent(context, V2rayProxyService.class));
        } catch (Exception e) {
            Log.w(TAG, "stopV2ray stopService", e);
        }
        try {
            Intent stop_intent = new Intent(V2RAY_SERVICE_COMMAND_INTENT);
            stop_intent.setPackage(context.getPackageName());
            stop_intent.putExtra(V2RAY_SERVICE_COMMAND_EXTRA, V2rayConstants.SERVICE_COMMANDS.STOP_SERVICE.name());
            context.sendBroadcast(stop_intent);
        } catch (Exception e) {
            Log.w(TAG, "stopV2ray broadcast", e);
        }
        connectionState = V2rayConstants.CONNECTION_STATES.DISCONNECTED;
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public static void getConnectedV2rayServerDelay(final Context context, final LatencyDelayListener latencyDelayCallback) {
        if (latencyDelayCallback == null || context == null) {
            return;
        }
        if (getConnectionState() != V2rayConstants.CONNECTION_STATES.CONNECTED) {
            latencyDelayCallback.OnResultReady(-1);
            return;
        }
        BroadcastReceiver connectionLatencyBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                try {
                    int delay = intent != null && intent.getExtras() != null ? intent.getExtras().getInt(V2RAY_SERVICE_CURRENT_CONFIG_DELAY_BROADCAST_EXTRA, -1) : -1;
                    latencyDelayCallback.OnResultReady(delay);
                } catch (Exception ignore) {
                    latencyDelayCallback.OnResultReady(-1);
                }
                try {
                    ctx.unregisterReceiver(this);
                } catch (Exception e) {
                    Log.w(TAG, "getConnectedV2rayServerDelay unregister", e);
                }
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(connectionLatencyBroadcastReceiver, new IntentFilter(V2RAY_SERVICE_CURRENT_CONFIG_DELAY_BROADCAST_INTENT), RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(connectionLatencyBroadcastReceiver, new IntentFilter(V2RAY_SERVICE_CURRENT_CONFIG_DELAY_BROADCAST_INTENT));
            }
        } catch (Exception e) {
            Log.w(TAG, "getConnectedV2rayServerDelay register", e);
            latencyDelayCallback.OnResultReady(-1);
            return;
        }
        Intent get_delay_intent = new Intent(V2RAY_SERVICE_COMMAND_INTENT);
        get_delay_intent.setPackage(context.getPackageName());
        get_delay_intent.putExtra(V2RAY_SERVICE_COMMAND_EXTRA, V2rayConstants.SERVICE_COMMANDS.MEASURE_DELAY.name());
        context.sendBroadcast(get_delay_intent);
    }

    public static long getV2rayServerDelay(final String config) {
        return V2rayCoreExecutor.getConfigDelay(Utilities.normalizeV2rayFullConfig(config));
    }

    public static String getCoreVersion() {
        try {
            return Libv2ray.checkVersionX();
        } catch (Exception e) {
            Log.w(TAG, "getCoreVersion", e);
            return "";
        }
    }

    public static void toggleConnectionMode() {
        if (serviceMode == V2rayConstants.SERVICE_MODES.PROXY_MODE) {
            serviceMode = V2rayConstants.SERVICE_MODES.VPN_MODE;
        } else {
            serviceMode = V2rayConstants.SERVICE_MODES.PROXY_MODE;
        }
    }

    public static void toggleConnectionMode(Context context) {
        toggleConnectionMode();
        if (context != null) {
            connectionState = getConnectionState();
        }
    }

    public static void toggleTrafficStatics() {
        if (currentConfig.enableTrafficStatics) {
            currentConfig.enableTrafficStatics = false;
            currentConfig.enableTrafficStaticsOnNotification = false;
        } else {
            currentConfig.enableTrafficStatics = true;
            currentConfig.enableTrafficStaticsOnNotification = true;
        }
    }

    private static void startTunnel(final Context context) {
        if (context == null) {
            return;
        }
        if (currentConfig == null || currentConfig.fullJsonConfig == null || currentConfig.fullJsonConfig.trim().isEmpty()) {
            connectionState = V2rayConstants.CONNECTION_STATES.DISCONNECTED;
            return;
        }
        Intent start_intent;
        if (serviceMode == V2rayConstants.SERVICE_MODES.PROXY_MODE) {
            start_intent = new Intent(context, V2rayProxyService.class);
        } else {
            start_intent = new Intent(context, V2rayVPNService.class);
        }
        start_intent.setPackage(context.getPackageName());
        start_intent.putExtra(V2RAY_SERVICE_COMMAND_EXTRA, V2rayConstants.SERVICE_COMMANDS.START_SERVICE.name());
        start_intent.putExtra(V2RAY_SERVICE_CONFIG_EXTRA, currentConfig);
        try {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
                context.startForegroundService(start_intent);
            } else {
                context.startService(start_intent);
            }
        } catch (Exception e) {
            Log.w(TAG, "startTunnel", e);
            connectionState = V2rayConstants.CONNECTION_STATES.DISCONNECTED;
        }
    }

    @Deprecated
    public static boolean IsPreparedForConnection(final Context context) {
        return isPreparedForConnection(context);
    }

    @Deprecated
    public static void StartV2ray(final Context context, final String remark, final String config, final ArrayList<String> blocked_apps) {
        startV2ray(context instanceof Activity ? (Activity) context : null, remark, config, blocked_apps);
    }

    @Deprecated
    public static void StopV2ray(final Context context) {
        stopV2ray(context);
    }
}
