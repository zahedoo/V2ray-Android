/* Updated for X-Core */
package dev.dev7.lib.v2ray.services;

import static dev.dev7.lib.v2ray.utils.V2rayConstants.V2RAY_SERVICE_COMMAND_INTENT;
import static android.content.Context.RECEIVER_EXPORTED;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import dev.dev7.lib.v2ray.core.V2rayCoreExecutor;
import dev.dev7.lib.v2ray.interfaces.StateListener;
import dev.dev7.lib.v2ray.interfaces.V2rayServicesListener;
import dev.dev7.lib.v2ray.model.V2rayConfigModel;
import dev.dev7.lib.v2ray.utils.V2rayConstants;

public class V2rayProxyService extends Service implements V2rayServicesListener {
    private V2rayCoreExecutor v2rayCoreExecutor;
    private NotificationService notificationService;
    private StaticsBroadCastService staticsBroadCastService;
    private V2rayConstants.CONNECTION_STATES connectionState = V2rayConstants.CONNECTION_STATES.DISCONNECTED;
    private V2rayConfigModel currentConfig = new V2rayConfigModel();
    private boolean isServiceCreated = false;
    private boolean isStopping = false;

    private final BroadcastReceiver serviceCommandBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                V2rayConstants.SERVICE_COMMANDS serviceCommand = resolveCommand(intent);
                if (serviceCommand == null) {
                    return;
                }
                switch (serviceCommand) {
                    case STOP_SERVICE:
                        stopCoreAndService();
                        break;
                    case MEASURE_DELAY:
                        if (v2rayCoreExecutor != null) {
                            v2rayCoreExecutor.broadCastCurrentServerDelay();
                        }
                        break;
                    default:
                        break;
                }
            } catch (Exception ignore) {
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        if (!isServiceCreated) {
            connectionState = V2rayConstants.CONNECTION_STATES.CONNECTING;
            v2rayCoreExecutor = new V2rayCoreExecutor(this);
            notificationService = new NotificationService(this);
            staticsBroadCastService = new StaticsBroadCastService(this, new StateListener() {
                @Override
                public V2rayConstants.CONNECTION_STATES getConnectionState() {
                    return connectionState;
                }

                @Override
                public V2rayConstants.CORE_STATES getCoreState() {
                    if (v2rayCoreExecutor == null) {
                        return V2rayConstants.CORE_STATES.IDLE;
                    }
                    return v2rayCoreExecutor.getCoreState();
                }

                @Override
                public long getDownloadSpeed() {
                    if (v2rayCoreExecutor == null) {
                        return -1;
                    }
                    return v2rayCoreExecutor.getDownloadSpeed();
                }

                @Override
                public long getUploadSpeed() {
                    if (v2rayCoreExecutor == null) {
                        return -1;
                    }
                    return v2rayCoreExecutor.getUploadSpeed();
                }
            });
            isServiceCreated = true;
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            V2rayConstants.SERVICE_COMMANDS serviceCommand = resolveCommand(intent);
            if (serviceCommand == null) {
                return super.onStartCommand(intent, flags, startId);
            }
            switch (serviceCommand) {
                case STOP_SERVICE:
                    stopCoreAndService();
                    break;
                case START_SERVICE:
                    currentConfig = (V2rayConfigModel) intent.getSerializableExtra(V2rayConstants.V2RAY_SERVICE_CONFIG_EXTRA);
                    if (currentConfig == null) {
                        stopService();
                        break;
                    }
                    staticsBroadCastService.isTrafficStaticsEnabled = currentConfig.enableTrafficStatics;
                    if (currentConfig.enableTrafficStatics && currentConfig.enableTrafficStaticsOnNotification) {
                        staticsBroadCastService.trafficListener = notificationService.trafficListener;
                    }
                    connectionState = V2rayConstants.CONNECTION_STATES.CONNECTING;
                    v2rayCoreExecutor.startCore(currentConfig);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        registerReceiver(serviceCommandBroadcastReceiver, new IntentFilter(V2RAY_SERVICE_COMMAND_INTENT), RECEIVER_EXPORTED);
                    } else {
                        registerReceiver(serviceCommandBroadcastReceiver, new IntentFilter(V2RAY_SERVICE_COMMAND_INTENT));
                    }
                    return START_STICKY;
                case MEASURE_DELAY:
                    if (v2rayCoreExecutor != null) {
                        v2rayCoreExecutor.broadCastCurrentServerDelay();
                    }
                    break;
                default:
                    onDestroy();
                    break;
            }
        } catch (Exception ignore) {
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(serviceCommandBroadcastReceiver);
        } catch (Exception ignore) {
        }
        super.onDestroy();
    }

    @Override
    public boolean onProtect(int socket) {
        return true;
    }

    @Override
    public Service getService() {
        return this;
    }

    @Override
    public void startService() {
        connectionState = V2rayConstants.CONNECTION_STATES.CONNECTED;
        notificationService.setConnectedNotification(currentConfig.remark, currentConfig.applicationIcon);
        staticsBroadCastService.start();
    }

    @Override
    public void stopService() {
        shutdownService();
    }

    private void stopCoreAndService() {
        shutdownService();
    }

    private void shutdownService() {
        if (isStopping) {
            return;
        }
        isStopping = true;
        try {
            staticsBroadCastService.sendDisconnectedBroadCast(this);
        } catch (Exception ignore) {
        }
        try {
            if (v2rayCoreExecutor != null) {
                v2rayCoreExecutor.stopCore(false);
            }
        } catch (Exception e) {
            Log.d(V2rayProxyService.class.getSimpleName(), "stopService => ", e);
        }
        try {
            staticsBroadCastService.stop();
            notificationService.dismissNotification();
        } catch (Exception ignore) {
        }
        try {
            stopForeground(true);
        } catch (Exception ignore) {
        }
        try {
            stopSelf();
        } catch (Exception ignore) {
        }
        connectionState = V2rayConstants.CONNECTION_STATES.DISCONNECTED;
        isStopping = false;
    }

    private V2rayConstants.SERVICE_COMMANDS resolveCommand(Intent intent) {
        if (intent == null) {
            return null;
        }
        Object extra = intent.getSerializableExtra(V2rayConstants.V2RAY_SERVICE_COMMAND_EXTRA);
        if (extra instanceof V2rayConstants.SERVICE_COMMANDS) {
            return (V2rayConstants.SERVICE_COMMANDS) extra;
        }
        String commandStr = intent.getStringExtra("command");
        if (commandStr == null && extra instanceof String) {
            commandStr = (String) extra;
        }
        if (commandStr != null) {
            try {
                return V2rayConstants.SERVICE_COMMANDS.valueOf(commandStr);
            } catch (Exception ignore) {
                return null;
            }
        }
        return null;
    }
}
