/* Updated for X-Core */
package dev.dev7.lib.v2ray.core;

import static dev.dev7.lib.v2ray.utils.V2rayConstants.V2RAY_SERVICE_CURRENT_CONFIG_DELAY_BROADCAST_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.V2RAY_SERVICE_CURRENT_CONFIG_DELAY_BROADCAST_INTENT;
import static dev.dev7.lib.v2ray.utils.Utilities.getDeviceIdForXUDPBaseKey;
import static dev.dev7.lib.v2ray.utils.Utilities.getUserAssetsPath;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

import dev.dev7.lib.v2ray.interfaces.V2rayServicesListener;
import dev.dev7.lib.v2ray.model.V2rayConfigModel;
import dev.dev7.lib.v2ray.utils.Utilities;
import dev.dev7.lib.v2ray.utils.V2rayConstants;
import go.Seq;
import libv2ray.Libv2ray;
import libv2ray.V2RayPoint;
import libv2ray.V2RayVPNServiceSupportsSet;

public class V2rayCoreExecutor {
    private static final Object ENV_LOCK = new Object();
    private static final AtomicBoolean IS_ENV_READY = new AtomicBoolean(false);

    private final Object coreLock = new Object();
    private final Service targetService;
    private V2rayServicesListener v2rayServicesListener;
    private V2RayPoint coreController;
    private volatile V2rayConstants.CORE_STATES coreState = V2rayConstants.CORE_STATES.IDLE;
    private volatile boolean isRunning = false;

    public V2rayCoreExecutor(final Service targetService) {
        this.targetService = targetService;
        this.v2rayServicesListener = (V2rayServicesListener) targetService;
        ensureCoreEnv(targetService.getApplicationContext());
        initCoreController();
        Log.d(V2rayCoreExecutor.class.getSimpleName(), "V2rayCoreExecutor -> initialized from : " + targetService.getClass().getSimpleName());
    }

    private void ensureCoreEnv(final Context context) {
        if (IS_ENV_READY.get()) {
            return;
        }
        synchronized (ENV_LOCK) {
            if (IS_ENV_READY.get()) {
                return;
            }
            try {
                Utilities.copyAssets(context);
                Seq.setContext(context);
                String assetsPath = getUserAssetsPath(context);
                if (assetsPath == null || assetsPath.isEmpty()) {
                    assetsPath = context.getDir("assets", Context.MODE_PRIVATE).getAbsolutePath();
                }
                Libv2ray.initV2Env(assetsPath, getDeviceIdForXUDPBaseKey(context));
                IS_ENV_READY.set(true);
            } catch (Exception e) {
                Log.e(V2rayCoreExecutor.class.getSimpleName(), "ensureCoreEnv failed", e);
            }
        }
    }

    private void initCoreController() {
        synchronized (coreLock) {
            if (coreController != null) {
                return;
            }
            coreController = Libv2ray.newV2RayPoint(new V2RayVPNServiceSupportsSet() {
                @Override
                public long shutdown() {
                    try {
                        isRunning = false;
                        coreState = V2rayConstants.CORE_STATES.STOPPED;
                        if (v2rayServicesListener != null) {
                            v2rayServicesListener.stopService();
                        }
                        return 0;
                    } catch (Exception e) {
                        Log.d(V2rayCoreExecutor.class.getSimpleName(), "shutdown =>", e);
                        return -1;
                    }
                }

                @Override
                public long prepare() {
                    return 0;
                }

                @Override
                public boolean protect(long socket) {
                    if (v2rayServicesListener != null) {
                        try {
                            return v2rayServicesListener.onProtect((int) socket);
                        } catch (Exception e) {
                            Log.w(V2rayCoreExecutor.class.getSimpleName(), "protect failed", e);
                        }
                    }
                    return true;
                }

                @Override
                public long onEmitStatus(long code, String status) {
                    Log.d(V2rayCoreExecutor.class.getSimpleName(), "onEmitStatus => " + status);
                    return 0;
                }

                @Override
                public long setup(String s) {
                    try {
                        coreState = V2rayConstants.CORE_STATES.RUNNING;
                        isRunning = true;
                        if (v2rayServicesListener != null) {
                            v2rayServicesListener.startService();
                        }
                    } catch (Exception e) {
                        Log.d(V2rayCoreExecutor.class.getSimpleName(), "setupFailed => ", e);
                        return -1;
                    }
                    return 0;
                }
            }, Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1);
        }
    }

    public void startCore(final V2rayConfigModel v2rayConfig) {
        if (v2rayConfig == null || v2rayConfig.fullJsonConfig == null || v2rayConfig.fullJsonConfig.isEmpty()) {
            coreState = V2rayConstants.CORE_STATES.STOPPED;
            return;
        }
        ensureCoreEnv(targetService.getApplicationContext());
        synchronized (coreLock) {
            if (coreController == null) {
                initCoreController();
            }
            stopLoopInternal(false);
            try {
                Libv2ray.testConfig(v2rayConfig.fullJsonConfig);
            } catch (Exception testException) {
                coreState = V2rayConstants.CORE_STATES.STOPPED;
                Log.d(V2rayCoreExecutor.class.getSimpleName(), "startCore => v2ray json config not valid.", testException);
                stopLoopInternal(true);
                return;
            }
            try {
                coreController.setConfigureFileContent(v2rayConfig.fullJsonConfig);
                coreController.setDomainName(Utilities.normalizeIpv6(v2rayConfig.currentServerAddress) + ":" + v2rayConfig.currentServerPort);
            } catch (Exception setConfigError) {
                Log.e(V2rayCoreExecutor.class.getSimpleName(), "startCore set config =>", setConfigError);
            }
            Thread loopThread = new Thread(() -> {
                try {
                    isRunning = true;
                    coreController.runLoop(false);
                    coreState = V2rayConstants.CORE_STATES.RUNNING;
                } catch (Exception e) {
                    Log.e(V2rayCoreExecutor.class.getSimpleName(), "startCore =>", e);
                    coreState = V2rayConstants.CORE_STATES.STOPPED;
                }
            }, "v2ray_core_loop");
            loopThread.start();
        }
    }

    public void replaceConfig(final String config) {
        synchronized (coreLock) {
            try {
                if (coreController == null) {
                    initCoreController();
                }
                coreController.setConfigureFileContent(config);
                if (coreController.getIsRunning()) {
                    coreController.stopLoop();
                    coreController.runLoop(false);
                }
                coreState = V2rayConstants.CORE_STATES.RUNNING;
                isRunning = true;
            } catch (Exception e) {
                Log.e(V2rayCoreExecutor.class.getSimpleName(), "replaceConfig =>", e);
                coreState = V2rayConstants.CORE_STATES.STOPPED;
                isRunning = false;
            }
        }
    }

    public void stopCore(final boolean shouldStopService) {
        synchronized (coreLock) {
            stopLoopInternal(shouldStopService);
        }
    }

    private void stopLoopInternal(final boolean shouldStopService) {
        try {
            if (coreController != null && coreController.getIsRunning()) {
                coreController.stopLoop();
            }
        } catch (Exception e) {
            Log.d(V2rayCoreExecutor.class.getSimpleName(), "stopCore =>", e);
        } finally {
            isRunning = false;
            coreState = V2rayConstants.CORE_STATES.STOPPED;
            if (shouldStopService && v2rayServicesListener != null) {
                try {
                    v2rayServicesListener.stopService();
                } catch (Exception e) {
                    Log.w(V2rayCoreExecutor.class.getSimpleName(), "stopService callback failed", e);
                }
            }
        }
    }

    public long getDownloadSpeed() {
        try {
            return coreController.queryStats("block", "downlink") + coreController.queryStats("proxy", "downlink");
        } catch (Exception e) {
            Log.w(V2rayCoreExecutor.class.getSimpleName(), "getDownloadSpeed fallback", e);
            return 0;
        }
    }

    public long getUploadSpeed() {
        try {
            return coreController.queryStats("block", "uplink") + coreController.queryStats("proxy", "uplink");
        } catch (Exception e) {
            Log.w(V2rayCoreExecutor.class.getSimpleName(), "getUploadSpeed fallback", e);
            return 0;
        }
    }

    public V2rayConstants.CORE_STATES getCoreState() {
        if (coreState == V2rayConstants.CORE_STATES.RUNNING && coreController != null) {
            try {
                if (!coreController.getIsRunning()) {
                    coreState = V2rayConstants.CORE_STATES.STOPPED;
                    isRunning = false;
                }
            } catch (Exception e) {
                Log.w(V2rayCoreExecutor.class.getSimpleName(), "getCoreState fallback", e);
            }
        }
        return coreState;
    }

    public void broadCastCurrentServerDelay() {
        try {
            if (v2rayServicesListener != null) {
                int serverDelay = (int) measureDelay("");
                Intent serverDelayBroadcast = new Intent(V2RAY_SERVICE_CURRENT_CONFIG_DELAY_BROADCAST_INTENT);
                serverDelayBroadcast.setPackage(v2rayServicesListener.getService().getPackageName());
                serverDelayBroadcast.putExtra(V2RAY_SERVICE_CURRENT_CONFIG_DELAY_BROADCAST_EXTRA, serverDelay);
                v2rayServicesListener.getService().sendBroadcast(serverDelayBroadcast);
            }
        } catch (Exception e) {
            Log.d(V2rayCoreExecutor.class.getSimpleName(), "broadCastCurrentServerDelay => ", e);
            try {
                Intent serverDelayBroadcast = new Intent(V2RAY_SERVICE_CURRENT_CONFIG_DELAY_BROADCAST_INTENT);
                serverDelayBroadcast.setPackage(v2rayServicesListener.getService().getPackageName());
                serverDelayBroadcast.putExtra(V2RAY_SERVICE_CURRENT_CONFIG_DELAY_BROADCAST_EXTRA, -1);
                v2rayServicesListener.getService().sendBroadcast(serverDelayBroadcast);
            } catch (Exception ignore) {
                //ignore
            }
        }
    }

    public long measureDelay(final String url) {
        try {
            if (coreController == null) {
                return -1;
            }
            return coreController.measureDelay(url == null ? "" : url);
        } catch (Exception e) {
            Log.w(V2rayCoreExecutor.class.getSimpleName(), "measureDelay =>", e);
            return -1;
        }
    }

    public static long getConfigDelay(final String config) {
        try {
            JSONObject configJson = new JSONObject(config);
            configJson.remove("routing");
            configJson.remove("dns");
            JSONObject routing = new JSONObject();
            routing.put("domainStrategy", "IPIfNonMatch");
            configJson.put("routing", routing);
            configJson.put("dns", new JSONObject("{\n" +
                    "    \"hosts\": {\n" +
                    "        \"domain:googleapis.cn\": \"googleapis.com\"\n" +
                    "    },\n" +
                    "    \"servers\": [\n" +
                    "        \"1.1.1.1\"\n" +
                    "    ]\n" +
                    "}"));
            return Libv2ray.measureOutboundDelay(configJson.toString(), "");
        } catch (Exception json_error) {
            Log.d(V2rayCoreExecutor.class.getSimpleName(), "getCurrentServerDelay -> ", json_error);
            return -1;
        }
    }
}
