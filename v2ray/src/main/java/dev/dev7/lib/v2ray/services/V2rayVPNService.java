/* Updated for X-Core */
package dev.dev7.lib.v2ray.services;

import static dev.dev7.lib.v2ray.utils.V2rayConstants.V2RAY_SERVICE_COMMAND_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.V2RAY_SERVICE_COMMAND_INTENT;
import static android.content.Context.RECEIVER_EXPORTED;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.StrictMode;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileDescriptor;
import java.io.OutputStream;

import dev.dev7.lib.v2ray.core.Tun2SocksExecutor;
import dev.dev7.lib.v2ray.core.V2rayCoreExecutor;
import dev.dev7.lib.v2ray.interfaces.StateListener;
import dev.dev7.lib.v2ray.interfaces.Tun2SocksListener;
import dev.dev7.lib.v2ray.interfaces.V2rayServicesListener;
import dev.dev7.lib.v2ray.model.V2rayConfigModel;
import dev.dev7.lib.v2ray.utils.V2rayConstants;

public class V2rayVPNService extends VpnService implements V2rayServicesListener, Tun2SocksListener {
    private ParcelFileDescriptor tunnelInterface;
    private Tun2SocksExecutor tun2SocksExecutor;
    private V2rayCoreExecutor v2rayCoreExecutor;
    private NotificationService notificationService;
    private StaticsBroadCastService staticsBroadCastService;
    private V2rayConstants.CONNECTION_STATES connectionState = V2rayConstants.CONNECTION_STATES.DISCONNECTED;
    private V2rayConfigModel currentConfig = new V2rayConfigModel();
    private boolean isServiceCreated = false;
    private boolean isServiceSetupStarted = false;
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
            StrictMode.ThreadPolicy threadPolicy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
            StrictMode.setThreadPolicy(threadPolicy);
            tun2SocksExecutor = new Tun2SocksExecutor(this);
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

    @Override
    public void onRevoke() {
        stopService();
    }

    private void setupService() {
        if (isServiceSetupStarted) {
            return;
        } else {
            isServiceSetupStarted = true;
        }
        try {
            if (tunnelInterface != null) {
                tunnelInterface.close();
            }
        } catch (Exception ignore) {
        }
        Intent prepareIntent = prepare(this);
        if (prepareIntent != null) {
            return;
        }
        Builder builder = getBuilder();
        try {
            tunnelInterface = builder.establish();
            int localDNSPort = 0;
            if (currentConfig.enableLocalTunneledDNS) {
                localDNSPort = currentConfig.localDNSPort;
            }
            tun2SocksExecutor.run(this, currentConfig.localSocksPort, localDNSPort);
            sendFileDescriptor();
            if (tun2SocksExecutor.isTun2SucksRunning()) {
                connectionState = V2rayConstants.CONNECTION_STATES.CONNECTED;
                notificationService.setConnectedNotification(currentConfig.remark, currentConfig.applicationIcon);
                staticsBroadCastService.start();
            }
        } catch (Exception e) {
            Log.e(V2rayVPNService.class.getSimpleName(), "setupFailed => ", e);
            stopService();
        }
    }

    @NonNull
    private Builder getBuilder() {
        Builder builder = new Builder();
        builder.setSession(currentConfig.remark);
        builder.setMtu(1500);
        builder.addAddress("26.26.26.1", 30);
        builder.addRoute("0.0.0.0", 0);
        if (currentConfig.enableLocalTunneledDNS) {
            builder.addDnsServer("26.26.26.2");
        } else {
            builder.addDnsServer("1.1.1.1");
            builder.addDnsServer("8.8.8.8");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false);
        }
        if (currentConfig.blockedApplications != null) {
            for (int i = 0; i < currentConfig.blockedApplications.size(); i++) {
                try {
                    builder.addDisallowedApplication(currentConfig.blockedApplications.get(i));
                } catch (Exception ignore) {
                }
            }
        }
        return builder;
    }

    private void sendFileDescriptor() {
        if (tunnelInterface == null) {
            return;
        }
        String localSocksFile = new File(getApplicationContext().getFilesDir(), "sock_path").getAbsolutePath();
        FileDescriptor tunFd = tunnelInterface.getFileDescriptor();
        new Thread(() -> {
            boolean isSendFDSuccess = false;
            for (int sendFDTries = 0; sendFDTries < 5; sendFDTries++) {
                try {
                    Thread.sleep(50L * sendFDTries);
                    LocalSocket clientLocalSocket = new LocalSocket();
                    clientLocalSocket.connect(new LocalSocketAddress(localSocksFile, LocalSocketAddress.Namespace.FILESYSTEM));
                    OutputStream clientOutStream = clientLocalSocket.getOutputStream();
                    clientLocalSocket.setFileDescriptorsForSend(new FileDescriptor[]{tunFd});
                    clientOutStream.write(42);
                    clientLocalSocket.shutdownOutput();
                    clientLocalSocket.close();
                    isSendFDSuccess = true;
                    break;
                } catch (Exception ignore) {
                }
            }
            if (!isSendFDSuccess) {
                Log.w("SendFDFailed", "Could`nt send file descriptor !");
            }
        }, "sendFd_Thread").start();
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
        return protect(socket);
    }

    @Override
    public Service getService() {
        return this;
    }

    @Override
    public void startService() {
        setupService();
    }

    @Override
    public void stopService() {
        shutdownService();
    }

    @Override
    public void OnTun2SocksHasMassage(V2rayConstants.CORE_STATES tun2SocksState, String newMessage) {
        Log.i("TUN2SOCKS", newMessage);
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
            tun2SocksExecutor.stopTun2Socks();
        } catch (Exception e) {
            Log.w(V2rayVPNService.class.getSimpleName(), "shutdownService tun2socks", e);
        }
        try {
            if (v2rayCoreExecutor != null) {
                v2rayCoreExecutor.stopCore(false);
            }
        } catch (Exception e) {
            Log.w(V2rayVPNService.class.getSimpleName(), "shutdownService core", e);
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
        try {
            if (tunnelInterface != null) {
                tunnelInterface.close();
                tunnelInterface = null;
            }
        } catch (Exception ignore) {
        }
        connectionState = V2rayConstants.CONNECTION_STATES.DISCONNECTED;
        isServiceSetupStarted = false;
        isStopping = false;
    }

    private V2rayConstants.SERVICE_COMMANDS resolveCommand(Intent intent) {
        if (intent == null) {
            return null;
        }
        Object extra = intent.getSerializableExtra(V2RAY_SERVICE_COMMAND_EXTRA);
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
