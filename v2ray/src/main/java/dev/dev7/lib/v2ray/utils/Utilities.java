// Updated for X-Core
package dev.dev7.lib.v2ray.utils;

import static dev.dev7.lib.v2ray.utils.V2rayConfigs.currentConfig;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.DEFAULT_OUT_BOUND_PLACE_IN_FULL_JSON_CONFIG;

import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;

import libv2ray.Libv2ray;

public final class Utilities {

    private static final int BUFFER_SIZE = 16 * 1024;

    private Utilities() {
    }

    public static String getDeviceIdForXUDPBaseKey(Context context) {
        String androidId = "";
        try {
            if (context != null) {
                androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            }
        } catch (Exception e) {
            Log.w(Utilities.class.getSimpleName(), "getDeviceIdForXUDPBaseKey => fallback", e);
        }
        if (TextUtils.isEmpty(androidId)) {
            androidId = "xudp-" + Math.abs(new Random().nextLong());
        }
        byte[] androidIdBytes = Arrays.copyOf(androidId.getBytes(StandardCharsets.UTF_8), 32);
        return Base64.encodeToString(androidIdBytes, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
    }

    public static String getDeviceIdForXUDPBaseKey() {
        return getDeviceIdForXUDPBaseKey(null);
    }

    public static void CopyFiles(InputStream src, File dst) throws IOException {
        if (dst == null || src == null) {
            return;
        }
        if (dst.getParentFile() != null && !dst.getParentFile().exists()) {
            dst.getParentFile().mkdirs();
        }
        try (InputStream in = src; OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[BUFFER_SIZE];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    public static String getUserAssetsPath(Context context) {
        if (context == null) {
            return "";
        }
        File extDir = context.getExternalFilesDir("assets");
        File target = extDir;
        if (extDir == null || (extDir.exists() && !extDir.canWrite())) {
            target = context.getDir("assets", Context.MODE_PRIVATE);
        } else if (!extDir.exists()) {
            boolean mkdirs = extDir.mkdirs();
            if (!mkdirs) {
                target = context.getDir("assets", Context.MODE_PRIVATE);
            }
        }
        if (target != null && !target.exists()) {
            target.mkdirs();
        }
        return target != null ? target.getAbsolutePath() : "";
    }

    public static void copyAssets(final Context context) {
        if (context == null) {
            return;
        }
        String extFolder = getUserAssetsPath(context);
        if (TextUtils.isEmpty(extFolder)) {
            extFolder = context.getDir("assets", Context.MODE_PRIVATE).getAbsolutePath();
        }
        File targetDir = new File(extFolder);
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        try {
            String[] assetList = context.getAssets().list("");
            if (assetList == null) {
                return;
            }
            for (String assetName : assetList) {
                if (!"geoip.dat".equals(assetName) && !"geosite.dat".equals(assetName)) {
                    continue;
                }
                File outFile = new File(targetDir, assetName);
                if (outFile.exists() && outFile.length() > 0) {
                    continue;
                }
                try (InputStream inputStream = context.getAssets().open(assetName)) {
                    CopyFiles(inputStream, outFile);
                }
            }
        } catch (Exception e) {
            Log.e(Utilities.class.getSimpleName(), "copyAssets failed =>", e);
        }
    }

    public static String convertIntToTwoDigit(int value) {
        if (value < 0) {
            value = 0;
        }
        if (value < 10) {
            return "0" + value;
        }
        return String.valueOf(value);
    }

    public static String parseTraffic(final long rawBytes, final boolean inBits, final boolean isMomentary) {
        return parseTraffic(rawBytes, inBits, isMomentary, true);
    }

    public static String parseTraffic(final long rawBytes, final boolean inBits, final boolean isMomentary, final boolean showUnit) {
        double value = Math.max(0, inBits ? rawBytes * 8.0 : rawBytes);
        String unit = inBits ? "b" : "B";
        double displayValue = value;
        if (displayValue >= V2rayConstants.TERA_BYTE) {
            displayValue = displayValue / V2rayConstants.TERA_BYTE;
            unit = "T" + unit;
        } else if (displayValue >= V2rayConstants.GIGA_BYTE) {
            displayValue = displayValue / V2rayConstants.GIGA_BYTE;
            unit = "G" + unit;
        } else if (displayValue >= V2rayConstants.MEGA_BYTE) {
            displayValue = displayValue / V2rayConstants.MEGA_BYTE;
            unit = "M" + unit;
        } else if (displayValue >= V2rayConstants.KILO_BYTE) {
            displayValue = displayValue / V2rayConstants.KILO_BYTE;
            unit = "K" + unit;
        }
        String pattern;
        if (displayValue >= 100) {
            pattern = "%.0f";
        } else if (displayValue >= 10) {
            pattern = "%.1f";
        } else {
            pattern = "%.2f";
        }
        String suffix = showUnit ? unit : unit;
        String speedSuffix = isMomentary ? "/s" : "";
        return String.format(Locale.getDefault(), pattern, displayValue) + suffix + speedSuffix;
    }

    public static String normalizeV2rayFullConfig(String config) {
        if (Libv2ray.isXrayURI(config)) {
            try {
                return V2rayConstants.DEFAULT_FULL_JSON_CONFIG.replace(DEFAULT_OUT_BOUND_PLACE_IN_FULL_JSON_CONFIG, Libv2ray.getXrayOutboundFromURI(config));
            } catch (Exception e) {
                Log.w(Utilities.class.getSimpleName(), "normalizeV2rayFullConfig uri failed", e);
            }
        }
        return config;
    }

    public static boolean refillV2rayConfig(String remark, String config, final ArrayList<String> blockedApplications) {
        currentConfig.remark = remark;
        currentConfig.blockedApplications = blockedApplications;
        try {
            JSONObject config_json = new JSONObject(normalizeV2rayFullConfig(config));
            try {
                JSONArray inbounds = config_json.getJSONArray("inbounds");
                for (int i = 0; i < inbounds.length(); i++) {
                    try {
                        JSONObject inbound = inbounds.getJSONObject(i);
                        String protocol = inbound.optString("protocol");
                        if ("socks".equals(protocol)) {
                            currentConfig.localSocksPort = inbound.optInt("port", currentConfig.localSocksPort);
                        }
                        if ("http".equals(protocol)) {
                            currentConfig.localHttpPort = inbound.optInt("port", currentConfig.localHttpPort);
                        }
                    } catch (Exception ignore) {
                    }
                }
            } catch (Exception e) {
                Log.w(Utilities.class.getSimpleName(), "refillV2rayConfig => no inbound port", e);
                return false;
            }
            try {
                currentConfig.currentServerAddress = config_json.getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).optString("address", "");
                currentConfig.currentServerPort = config_json.getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).optInt("port", currentConfig.currentServerPort);
            } catch (Exception e) {
                try {
                    currentConfig.currentServerAddress = config_json.getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("servers").getJSONObject(0).optString("address", "");
                    currentConfig.currentServerPort = config_json.getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("servers").getJSONObject(0).optInt("port", currentConfig.currentServerPort);
                } catch (Exception ignore) {
                    Log.w(Utilities.class.getSimpleName(), "refillV2rayConfig => server address not found", ignore);
                }
            }
            try {
                if (config_json.has("policy")) {
                    config_json.remove("policy");
                }
                if (config_json.has("stats")) {
                    config_json.remove("stats");
                }
            } catch (Exception ignore_error) {
            }
            if (currentConfig.enableTrafficStatics) {
                try {
                    JSONObject policy = new JSONObject();
                    JSONObject levels = new JSONObject();
                    levels.put("8", new JSONObject().put("connIdle", 300).put("downlinkOnly", 1).put("handshake", 4).put("uplinkOnly", 1));
                    JSONObject system = new JSONObject().put("statsOutboundUplink", true).put("statsOutboundDownlink", true);
                    policy.put("levels", levels);
                    policy.put("system", system);
                    config_json.put("policy", policy);
                    config_json.put("stats", new JSONObject());
                } catch (Exception e) {
                    Log.e(Utilities.class.getSimpleName(), "enable stats failed", e);
                    currentConfig.enableTrafficStatics = false;
                }
            }
            currentConfig.fullJsonConfig = config_json.toString();
            return true;
        } catch (Exception e) {
            Log.e(Utilities.class.getSimpleName(), "parseV2rayJsonFile failed => ", e);
            return false;
        }
    }

    public static String normalizeIpv6(String address) {
        if (isIpv6Address(address) && !address.contains("[") && !address.contains("]")) {
            return String.format("[%s]", address);
        } else {
            return address;
        }
    }

    public static boolean isIpv6Address(String address) {
        if (address == null) {
            return false;
        }
        String[] tmp = address.split(":");
        return tmp.length > 2;
    }

    public static String extractCurrentServerAddressAndPort(String config) {
        try {
            JSONObject configJson = new JSONObject(normalizeV2rayFullConfig(config));
            JSONObject outbound = configJson.getJSONArray("outbounds").getJSONObject(0);
            JSONObject settings = outbound.optJSONObject("settings");
            if (settings != null) {
                JSONArray vnext = settings.optJSONArray("vnext");
                if (vnext != null && vnext.length() > 0) {
                    JSONObject server = vnext.getJSONObject(0);
                    return server.optString("address", "") + ":" + server.optInt("port", 0);
                }
                JSONArray servers = settings.optJSONArray("servers");
                if (servers != null && servers.length() > 0) {
                    JSONObject server = servers.getJSONObject(0);
                    return server.optString("address", "") + ":" + server.optInt("port", 0);
                }
            }
        } catch (Exception e) {
            Log.w(Utilities.class.getSimpleName(), "extractCurrentServerAddressAndPort => fallback", e);
        }
        return "";
    }
}
