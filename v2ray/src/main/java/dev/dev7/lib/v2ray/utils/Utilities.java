/* Updated for X-Core */
package dev.dev7.lib.v2ray.utils;

import static dev.dev7.lib.v2ray.utils.V2rayConfigs.currentConfig;
import static dev.dev7.lib.v2ray.utils.V2rayConstants.DEFAULT_OUT_BOUND_PLACE_IN_FULL_JSON_CONFIG;

import android.content.Context;
import android.provider.Settings;
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

import libv2ray.Libv2ray;

public final class Utilities {

    private Utilities() {
    }

    public static String getDeviceIdForXUDPBaseKey(Context context) {
        String androidId = "";
        try {
            androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception e) {
            Log.w(Utilities.class.getSimpleName(), "getDeviceIdForXUDPBaseKey fallback", e);
        }
        if (androidId == null) {
            androidId = "";
        }
        byte[] androidIdBytes = androidId.getBytes(StandardCharsets.UTF_8);
        return Base64.encodeToString(Arrays.copyOf(androidIdBytes, 32), Base64.NO_WRAP);
    }

    private static void copyStream(final InputStream src, final File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (InputStream in = src; OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[16 * 1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    public static String getUserAssetsPath(Context context) {
        File assetDir = context.getExternalFilesDir("assets");
        if (assetDir == null) {
            assetDir = context.getDir("assets", Context.MODE_PRIVATE);
        }
        if (assetDir != null && !assetDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            assetDir.mkdirs();
        }
        if (assetDir == null) {
            return context.getFilesDir().getAbsolutePath();
        }
        return assetDir.getAbsolutePath();
    }

    public static void copyAssets(final Context context) {
        String extFolder = getUserAssetsPath(context);
        try {
            String[] geoFiles = new String[]{"geosite.dat", "geoip.dat"};
            for (String assetName : geoFiles) {
                File targetFile = new File(extFolder, assetName);
                if (targetFile.exists() && targetFile.length() > 0) {
                    continue;
                }
                try (InputStream assetStream = context.getAssets().open(assetName)) {
                    copyStream(assetStream, targetFile);
                } catch (Exception assetError) {
                    Log.e(Utilities.class.getSimpleName(), "copyAssets failed for " + assetName, assetError);
                }
            }
        } catch (Exception e) {
            Log.e(Utilities.class.getSimpleName(), "copyAssets failed=>", e);
        }
    }

    public static String convertIntToTwoDigit(int value) {
        if (value < 10 && value >= 0) {
            return "0" + value;
        } else {
            return String.valueOf(value);
        }
    }

    public static String parseTraffic(final long rawBytes, final boolean inBits, final boolean isMomentary) {
        long safeBytes = Math.max(0, rawBytes);
        double value = inBits ? safeBytes * 8d : safeBytes;
        String suffix = inBits ? "b" : "B";
        double divisor = 1;
        String unit = "";
        if (value >= (double) V2rayConstants.KILO_BYTE * V2rayConstants.KILO_BYTE * V2rayConstants.KILO_BYTE * V2rayConstants.KILO_BYTE) {
            divisor = (double) V2rayConstants.KILO_BYTE * V2rayConstants.KILO_BYTE * V2rayConstants.KILO_BYTE * V2rayConstants.KILO_BYTE;
            unit = "T";
        } else if (value >= V2rayConstants.GIGA_BYTE) {
            divisor = V2rayConstants.GIGA_BYTE;
            unit = "G";
        } else if (value >= V2rayConstants.MEGA_BYTE) {
            divisor = V2rayConstants.MEGA_BYTE;
            unit = "M";
        } else if (value >= V2rayConstants.KILO_BYTE) {
            divisor = V2rayConstants.KILO_BYTE;
            unit = "K";
        } else {
            divisor = 1;
            unit = "";
        }
        double displayValue = value / divisor;
        String pattern;
        if (displayValue >= 100) {
            pattern = "%.0f";
        } else if (displayValue >= 10) {
            pattern = "%.1f";
        } else {
            pattern = "%.2f";
        }
        return String.format(Locale.getDefault(), pattern + unit + suffix + (isMomentary ? "/s" : ""), displayValue);
    }

    public static String normalizeV2rayFullConfig(String config) {
        if (config == null) {
            return "";
        }
        try {
            if (Libv2ray.isXrayURI(config)) {
                return V2rayConstants.DEFAULT_FULL_JSON_CONFIG.replace(DEFAULT_OUT_BOUND_PLACE_IN_FULL_JSON_CONFIG, Libv2ray.getXrayOutboundFromURI(config));
            }
        } catch (Exception e) {
            Log.w(Utilities.class.getSimpleName(), "normalizeV2rayFullConfig fallback", e);
        }
        return config;
    }

    public static boolean refillV2rayConfig(String remark, String config, final ArrayList<String> blockedApplications) {
        currentConfig.remark = remark;
        currentConfig.blockedApplications = blockedApplications;
        try {
            JSONObject configJson = new JSONObject(normalizeV2rayFullConfig(config));
            try {
                JSONArray inbounds = configJson.getJSONArray("inbounds");
                for (int i = 0; i < inbounds.length(); i++) {
                    try {
                        if ("socks".equals(inbounds.getJSONObject(i).getString("protocol"))) {
                            currentConfig.localSocksPort = inbounds.getJSONObject(i).getInt("port");
                        }
                    } catch (Exception ignored) {
                    }
                    try {
                        if ("http".equals(inbounds.getJSONObject(i).getString("protocol"))) {
                            currentConfig.localHttpPort = inbounds.getJSONObject(i).getInt("port");
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                Log.w(Utilities.class.getSimpleName(), "startCore warn => can`t find inbound port of socks5 or http.");
                return false;
            }
            extractCurrentServerAddressAndPort(configJson.toString());
            try {
                if (configJson.has("policy")) {
                    configJson.remove("policy");
                }
                if (configJson.has("stats")) {
                    configJson.remove("stats");
                }
            } catch (Exception ignoreError) {
                //ignore
            }
            if (currentConfig.enableTrafficStatics) {
                try {
                    JSONObject policy = new JSONObject();
                    JSONObject levels = new JSONObject();
                    levels.put("8", new JSONObject().put("connIdle", 300).put("downlinkOnly", 1).put("handshake", 4).put("uplinkOnly", 1));
                    JSONObject system = new JSONObject().put("statsOutboundUplink", true).put("statsOutboundDownlink", true);
                    policy.put("levels", levels);
                    policy.put("system", system);
                    configJson.put("policy", policy);
                    configJson.put("stats", new JSONObject());
                } catch (Exception e) {
                    Log.e(Utilities.class.getSimpleName(), "refillV2rayConfig stats => ", e);
                    currentConfig.enableTrafficStatics = false;
                }
            }
            currentConfig.fullJsonConfig = configJson.toString();
            return true;
        } catch (Exception e) {
            Log.e(Utilities.class.getSimpleName(), "parseV2rayJsonFile failed => ", e);
            return false;
        }
    }

    public static void extractCurrentServerAddressAndPort(String config) {
        try {
            JSONObject configJson = new JSONObject(normalizeV2rayFullConfig(config));
            JSONObject outbound = configJson.getJSONArray("outbounds").getJSONObject(0);
            JSONObject settings = outbound.getJSONObject("settings");
            try {
                JSONObject vnext = settings.getJSONArray("vnext").getJSONObject(0);
                currentConfig.currentServerAddress = vnext.getString("address");
                currentConfig.currentServerPort = vnext.getInt("port");
            } catch (Exception e) {
                JSONObject server = settings.getJSONArray("servers").getJSONObject(0);
                currentConfig.currentServerAddress = server.optString("address", currentConfig.currentServerAddress);
                currentConfig.currentServerPort = server.optInt("port", currentConfig.currentServerPort);
            }
        } catch (Exception e) {
            Log.w(Utilities.class.getSimpleName(), "extractCurrentServerAddressAndPort warn", e);
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
        String[] tmp = address.split(":");
        return tmp.length > 2;
    }

    public static boolean isFileExists(String path) {
        try {
            return new File(path).exists();
        } catch (Exception ignore) {
            return false;
        }
    }
}
