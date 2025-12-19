/* Updated for X-Core */
package dev.dev7.lib.v2ray;

import static android.Manifest.permission.POST_NOTIFICATIONS;
import static android.content.Context.RECEIVER_EXPORTED;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.SERVICE_CONNECTION_STATE_BROADCAST_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.SERVICE_TYPE_BROADCAST_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.V2RAY_SERVICE_COMMAND_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.V2RAY_SERVICE_COMMAND_INTENT;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.V2RAY_SERVICE_CONFIG_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.V2RAY_SERVICE_CURRENT_CONFIG_DELAY_BROADCAST_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.V2RAY_SERVICE_CURRENT_CONFIG_DELAY_BROADCAST_INTENT;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.V2RAY_SERVICE_STATICS_BROADCAST_INTENT;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.dev7.lib.v2ray.core.V2rayCoreExecutor;
import dev.dev7.lib.v2ray.interfaces.LatencyDelayListener;
import dev.dev7.lib.v2ray.services.V2rayProxyService;
import dev.dev7.lib.v2ray.services.V2rayVPNService;
import dev.dev7.lib.v2ray.utils.V2rayConfigs;
import dev.dev7.lib.v2ray.utils.Utilities;
import dev.dev7.lib.v2ray.utils.V2rayConstants;
import libv2ray.Libv2ray;

public class V2rayController {
    private static final String TAG = V2rayController.class.getSimpleName();
    private static ActivityResultLauncher<Intent> activityResultLauncher;
    private static boolean sReceiversRegistered = false;
    private static Context sAppContext;
    private static final BroadcastReceiver stateUpdaterBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                Object stateObj = intent.getExtras() != null ? intent.getExtras().getSerializable(SERVICE_CONNECTION_STATE_BROADCAST_EXTRA) : null;
                if (stateObj instanceof V2rayConstants.CONNECTION_STATES) {
                    connectionState = (V2rayConstants.CONNECTION_STATES) stateObj;
                }
                String serviceName = intent.getStringExtra(SERVICE_TYPE_BROADCAST_EXTRA);
                if (V2rayProxyService.class.getSimpleName().equals(serviceName)) {
                    V2rayConfigs.serviceMode = V2rayConstants.SERVICE_MODES.PROXY_MODE;
                } else if (V2rayVPNService.class.getSimpleName().equals(serviceName)) {
                    V2rayConfigs.serviceMode = V2rayConstants.SERVICE_MODES.VPN_MODE;
                }
            } catch (Exception e) {
                Log.w(TAG, "stateUpdaterBroadcastReceiver failed", e);
            }
        }
    };

    public static void init(final AppCompatActivity activity, final int appIcon, final String appName) {
        sAppContext = activity.getApplicationContext();
        Utilities.copyAssets(activity);
        try {
            currentConfig.applicationIcon = appIcon;
            currentConfig.applicationName = appName;
        } catch (Exception e) {
            Log.w(TAG, "init optional fields", e);
        }
        registerReceivers(activity.getApplicationContext());
        activityResultLauncher = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK) {
                startTunnel(activity);
            } else {
                Toast.makeText(activity, "Permission not granted.", Toast.LENGTH_LONG).show();
            }
        });
    }

    public static void init(final Context context) {
        sAppContext = context.getApplicationContext();
        Utilities.copyAssets(context);
        registerReceivers(context.getApplicationContext());
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public static void registerReceivers(final Context context) {
        if (sReceiversRegistered) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(stateUpdaterBroadcastReceiver, new IntentFilter(V2RAY_SERVICE_STATICS_BROADCAST_INTENT), RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(stateUpdaterBroadcastReceiver, new IntentFilter(V2RAY_SERVICE_STATICS_BROADCAST_INTENT));
            }
            sReceiversRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "registerReceivers failed", e);
        }
    }

    public static V2rayConstants.CONNECTION_STATES getConnectionState() {
        return connectionState == null ? V2rayConstants.CONNECTION_STATES.DISCONNECTED : connectionState;
    }

    public static boolean isPreparedForConnection(final Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS) != PermissionChecker.PERMISSION_GRANTED) {
                return false;
            }
        }
        Intent vpnServicePrepareIntent = VpnService.prepare(context);
        return vpnServicePrepareIntent == null;
    }

    private static void prepareForConnection(final Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(activity, POST_NOTIFICATIONS) != PermissionChecker.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity, new String[]{POST_NOTIFICATIONS}, 101);
                return;
            }
        }
        Intent vpnServicePrepareIntent = VpnService.prepare(activity);
        if (vpnServicePrepareIntent != null) {
            activityResultLauncher.launch(vpnServicePrepareIntent);
        }
    }

    public static void startV2ray(final Activity activity, final String remark, final String config, final ArrayList<String> blockedApps) {
        if (!Utilities.refillV2rayConfig(remark, config, blockedApps)) {
            connectionState = V2rayConstants.CONNECTION_STATES.DISCONNECTED;
            return;
        }
        Context ctx = activity != null ? activity : (sAppContext != null ? sAppContext : null);
        if (activity != null && !isPreparedForConnection(activity)) {
            prepareForConnection(activity);
        } else {
            startTunnel(ctx);
        }
    }

    public static void startV2ray(final Context context, final String remark, final String config, final LatencyDelayListener latencyDelayCallback) {
        if (!Utilities.refillV2rayConfig(remark, config, null)) {
            if (latencyDelayCallback != null) {
                latencyDelayCallback.OnResultReady(-1);
            }
            connectionState = V2rayConstants.CONNECTION_STATES.DISCONNECTED;
            return;
        }
        startTunnel(context);
        if (latencyDelayCallback != null) {
            getConnectedV2rayServerDelay(context, latencyDelayCallback);
        }
    }

    public static void startV2ray(final Context context, final String config, final V2rayConstants.SERVICE_MODES mode) {
        V2rayConfigs.serviceMode = mode == null ? V2rayConstants.SERVICE_MODES.VPN_MODE : mode;
        String remark = currentConfig.remark == null ? "V2Ray Server" : currentConfig.remark;
        startV2ray(context instanceof Activity ? (Activity) context : null, remark, config, null);
    }

    private static void startV2ray(final Activity activity, final String remark, final String config, final ArrayList<String> blockedApps, final boolean skipPrepare) {
        if (!Utilities.refillV2rayConfig(remark, config, blockedApps)) {
            connectionState = V2rayConstants.CONNECTION_STATES.DISCONNECTED;
            return;
        }
        Context ctx = activity != null ? activity : sAppContext;
        if (!skipPrepare && activity != null && !isPreparedForConnection(activity)) {
            prepareForConnection(activity);
        } else {
            startTunnel(ctx);
        }
    }

    public static void stopV2ray(final Context context) {
        try {
            Intent stopIntent = new Intent(V2RAY_SERVICE_COMMAND_INTENT);
            stopIntent.setPackage(context.getPackageName());
            stopIntent.putExtra(V2RAY_SERVICE_COMMAND_EXTRA, V2rayConstants.SERVICE_COMMANDS.STOP_SERVICE);
            stopIntent.putExtra("command", "STOP");
            context.sendBroadcast(stopIntent);
        } catch (Exception e) {
            Log.w(TAG, "stopV2ray broadcast failed", e);
        }
        try {
            context.stopService(new Intent(context, V2rayVPNService.class));
            context.stopService(new Intent(context, V2rayProxyService.class));
        } catch (Exception e) {
            Log.w(TAG, "stopV2ray stopService failed", e);
        }
        connectionState = V2rayConstants.CONNECTION_STATES.DISCONNECTED;
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public static void getConnectedV2rayServerDelay(final Context context, final LatencyDelayListener latencyDelayCallback) {
        if (getConnectionState() != V2rayConstants.CONNECTION_STATES.CONNECTED) {
            latencyDelayCallback.OnResultReady(-1);
            return;
        }
        BroadcastReceiver connectionLatencyBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                try {
                    int delay = intent.getExtras() != null ? intent.getExtras().getInt(V2RAY_SERVICE_CURRENT_CONFIG_DELAY_BROADCAST_EXTRA, -1) : -1;
                    latencyDelayCallback.OnResultReady(delay);
                } catch (Exception ignore) {
                    latencyDelayCallback.OnResultReady(-1);
                }
                try {
                    ctx.unregisterReceiver(this);
                } catch (Exception e) {
                    Log.w(TAG, "unregister delay receiver", e);
                }
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(connectionLatencyBroadcastReceiver, new IntentFilter(V2RAY_SERVICE_CURRENT_CONFIG_DELAY_BROADCAST_INTENT), RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(connectionLatencyBroadcastReceiver, new IntentFilter(V2RAY_SERVICE_CURRENT_CONFIG_DELAY_BROADCAST_INTENT));
        }
        Intent getDelayIntent = new Intent(V2RAY_SERVICE_COMMAND_INTENT);
        getDelayIntent.setPackage(context.getPackageName());
        getDelayIntent.putExtra(V2RAY_SERVICE_COMMAND_EXTRA, V2rayConstants.SERVICE_COMMANDS.MEASURE_DELAY);
        getDelayIntent.putExtra("command", "MEASURE_DELAY");
        context.sendBroadcast(getDelayIntent);
    }

    public static long getConnectedV2rayServerDelay(final Context context) {
        final long[] delay = new long[]{-1};
        final CountDownLatch latch = new CountDownLatch(1);
        getConnectedV2rayServerDelay(context, result -> {
            delay[0] = result;
            latch.countDown();
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.w(TAG, "getConnectedV2rayServerDelay sync wait", e);
        }
        return delay[0];
    }

    public static long getV2rayServerDelay(final String config) {
        return V2rayCoreExecutor.getConfigDelay(Utilities.normalizeV2rayFullConfig(config));
    }

    public static String getCoreVersion() {
        try {
            return Libv2ray.checkVersionX();
        } catch (Exception e) {
            Log.w(TAG, "getCoreVersion fallback", e);
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

    public static void toggleConnectionMode(final Context context) {
        toggleConnectionMode();
        if (context != null) {
            V2rayConfigs.serviceMode = serviceMode;
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
        Context appCtx = context != null ? context : sAppContext;
        if (appCtx == null) {
            Log.w(TAG, "startTunnel failed: context null");
            return;
        }
        Intent startIntent;
        if (serviceMode == V2rayConstants.SERVICE_MODES.PROXY_MODE) {
            startIntent = new Intent(appCtx, V2rayProxyService.class);
        } else {
            startIntent = new Intent(appCtx, V2rayVPNService.class);
        }
        startIntent.setPackage(appCtx.getPackageName());
        startIntent.putExtra(V2RAY_SERVICE_COMMAND_EXTRA, V2rayConstants.SERVICE_COMMANDS.START_SERVICE);
        startIntent.putExtra("command", "START_SERVICE");
        startIntent.putExtra(V2RAY_SERVICE_CONFIG_EXTRA, currentConfig);
        connectionState = V2rayConstants.CONNECTION_STATES.CONNECTING;
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
            appCtx.startForegroundService(startIntent);
        } else {
            appCtx.startService(startIntent);
        }
    }

    @Deprecated
    public static boolean IsPreparedForConnection(final Context context) {
        return isPreparedForConnection(context);
    }

    @Deprecated
    public static void StartV2ray(final Context context, final String remark, final String config, final ArrayList<String> blockedApps) {
        startV2ray(context instanceof Activity ? (Activity) context : null, remark, config, blockedApps, true);
    }

    @Deprecated
    public static void StopV2ray(final Context context) {
        stopV2ray(context);
    }
}
