// Updated for X-Core
package dev.dev7.lib.v2ray.core;

import static dev.dev7.lib.v2ray.utils.V2rayConstants.V2RAY_SERVICE_CURRENT_CONFIG_DELAY_BROADCAST_EXTRA;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.V2RAY_SERVICE_CURRENT_CONFIG_DELAY_BROADCAST_INTENT;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

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
    private static boolean sCoreEnvInitialized = false;
    private static boolean sAssetsPrepared = false;

    private final Object coreLock = new Object();
    private final V2rayServicesListener v2rayServicesListener;
    private V2RayPoint coreController;
    private volatile V2rayConstants.CORE_STATES coreState = V2rayConstants.CORE_STATES.STOPPED;

    public V2rayCoreExecutor(final Service targetService) {
        this.v2rayServicesListener = (V2rayServicesListener) targetService;
        ensureCoreEnv(targetService.getApplicationContext());
        createCoreController();
        coreState = V2rayConstants.CORE_STATES.IDLE;
        Log.d(V2rayCoreExecutor.class.getSimpleName(), "V2rayCoreExecutor initialized for " + targetService.getClass().getSimpleName());
    }

    public void startCore(final V2rayConfigModel v2rayConfig) {
        if (v2rayConfig == null || v2rayConfig.fullJsonConfig == null || v2rayConfig.fullJsonConfig.trim().isEmpty()) {
            Log.w(V2rayCoreExecutor.class.getSimpleName(), "startCore => empty config, skip");
            return;
        }
        synchronized (coreLock) {
            stopCore(false);
            try {
                Libv2ray.testConfig(v2rayConfig.fullJsonConfig);
            } catch (Exception testException) {
                coreState = V2rayConstants.CORE_STATES.STOPPED;
                Log.d(V2rayCoreExecutor.class.getSimpleName(), "startCore => v2ray json config not valid.", testException);
                stopCore(true);
                return;
            }
            try {
                ensureCoreEnv(v2rayServicesListener.getService().getApplicationContext());
                if (coreController == null) {
                    createCoreController();
                }
                coreController.setConfigureFileContent(v2rayConfig.fullJsonConfig);
                coreController.setDomainName(Utilities.normalizeIpv6(v2rayConfig.currentServerAddress) + ":" + v2rayConfig.currentServerPort);
                coreState = V2rayConstants.CORE_STATES.RUNNING;
                new Thread(() -> {
                    try {
                        coreController.runLoop(false);
                    } catch (Exception e) {
                        Log.e(V2rayCoreExecutor.class.getSimpleName(), "startCore runLoop error", e);
                        stopCore(true);
                    }
                }, "v2ray_core_loop").start();
            } catch (Exception e) {
                Log.e(V2rayCoreExecutor.class.getSimpleName(), "startCore =>", e);
                stopCore(true);
            }
        }
    }

    public void stopCore(final boolean shouldStopService) {
        synchronized (coreLock) {
            try {
                if (coreController != null && coreController.getIsRunning()) {
                    try {
                        coreController.stopLoop();
                    } catch (Exception e) {
                        Log.w(V2rayCoreExecutor.class.getSimpleName(), "stopCore stopLoop", e);
                    }
                }
            } finally {
                coreState = V2rayConstants.CORE_STATES.STOPPED;
                if (shouldStopService && v2rayServicesListener != null) {
                    try {
                        v2rayServicesListener.stopService();
                    } catch (Exception e) {
                        Log.w(V2rayCoreExecutor.class.getSimpleName(), "stopCore stopService", e);
                    }
                }
            }
        }
    }

    public long getDownloadSpeed() {
        try {
            return queryStats("block", "downlink") + queryStats("proxy", "downlink");
        } catch (Exception e) {
            Log.w(V2rayCoreExecutor.class.getSimpleName(), "getDownloadSpeed", e);
            return 0;
        }
    }

    public long getUploadSpeed() {
        try {
            return queryStats("block", "uplink") + queryStats("proxy", "uplink");
        } catch (Exception e) {
            Log.w(V2rayCoreExecutor.class.getSimpleName(), "getUploadSpeed", e);
            return 0;
        }
    }

    public V2rayConstants.CORE_STATES getCoreState() {
        if (coreState == V2rayConstants.CORE_STATES.RUNNING && coreController != null && !coreController.getIsRunning()) {
            coreState = V2rayConstants.CORE_STATES.STOPPED;
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
            if (v2rayServicesListener != null) {
                Intent serverDelayBroadcast = new Intent(V2RAY_SERVICE_CURRENT_CONFIG_DELAY_BROADCAST_INTENT);
                serverDelayBroadcast.setPackage(v2rayServicesListener.getService().getPackageName());
                serverDelayBroadcast.putExtra(V2RAY_SERVICE_CURRENT_CONFIG_DELAY_BROADCAST_EXTRA, -1);
                v2rayServicesListener.getService().sendBroadcast(serverDelayBroadcast);
            }
        }
    }

    public long measureDelay(String url) {
        try {
            if (coreController != null) {
                return coreController.measureDelay(url == null ? "" : url);
            }
        } catch (Exception e) {
            Log.w(V2rayCoreExecutor.class.getSimpleName(), "measureDelay", e);
        }
        return -1;
    }

    public long queryStats(String tag, String link) {
        try {
            if (coreController != null) {
                return coreController.queryStats(tag, link);
            }
        } catch (Exception e) {
            Log.w(V2rayCoreExecutor.class.getSimpleName(), "queryStats", e);
        }
        return 0;
    }

    public static long getConfigDelay(final String config) {
        try {
            JSONObject config_json = new JSONObject(config);
            config_json.remove("routing");
            config_json.remove("dns");
            JSONObject routing = new JSONObject();
            routing.put("domainStrategy", "IPIfNonMatch");
            config_json.put("routing", routing);
            config_json.put("dns", new JSONObject("{\n" +
                    "    \"hosts\": {\n" +
                    "        \"domain:googleapis.cn\": \"googleapis.com\"\n" +
                    "    },\n" +
                    "    \"servers\": [\n" +
                    "        \"1.1.1.1\"\n" +
                    "    ]\n" +
                    "}"));
            return Libv2ray.measureOutboundDelay(config_json.toString(), "");
        } catch (Exception json_error) {
            Log.d(V2rayCoreExecutor.class.getSimpleName(), "getCurrentServerDelay -> ", json_error);
            return -1;
        }
    }

    private void createCoreController() {
        coreController = Libv2ray.newV2RayPoint(new V2RayVPNServiceSupportsSet() {
            @Override
            public long shutdown() {
                try {
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
            public boolean protect(long l) {
                if (v2rayServicesListener != null) {
                    return v2rayServicesListener.onProtect((int) l);
                }
                return true;
            }

            @Override
            public long onEmitStatus(long l, String s) {
                Log.d(V2rayCoreExecutor.class.getSimpleName(), "onEmitStatus => " + s);
                return 0;
            }

            @Override
            public long setup(String s) {
                if (v2rayServicesListener != null) {
                    try {
                        coreState = V2rayConstants.CORE_STATES.RUNNING;
                        v2rayServicesListener.startService();
                    } catch (Exception e) {
                        Log.d(V2rayCoreExecutor.class.getSimpleName(), "setupFailed => ", e);
                        return -1;
                    }
                }
                return 0;
            }
        }, Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1);
    }

    private static void ensureCoreEnv(Context context) {
        if (context == null) {
            return;
        }
        synchronized (ENV_LOCK) {
            if (!sAssetsPrepared) {
                Utilities.copyAssets(context.getApplicationContext());
                sAssetsPrepared = true;
            }
            if (sCoreEnvInitialized) {
                return;
            }
            try {
                Seq.setContext(context);
            } catch (Exception e) {
                Log.w(V2rayCoreExecutor.class.getSimpleName(), "Seq.setContext", e);
            }
            try {
                Libv2ray.initV2Env(Utilities.getUserAssetsPath(context), Utilities.getDeviceIdForXUDPBaseKey(context));
                sCoreEnvInitialized = true;
            } catch (Exception e) {
                Log.e(V2rayCoreExecutor.class.getSimpleName(), "initV2Env failed", e);
            }
        }
    }
}
