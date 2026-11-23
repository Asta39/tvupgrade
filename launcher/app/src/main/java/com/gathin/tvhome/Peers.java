package com.gathin.tvhome;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The other TVs running this launcher on the same Wi-Fi (this fork), for the host's dashboard of several TVs.
 * Each TV announces its phone page over mDNS / DNS-SD as "_tvhome._tcp" and keeps a list of the others it hears.
 * Only on the local network: TVs in other buildings need a cloud service, which this project doesn't have.
 */
final class Peers {
    private static final String TYPE = "_tvhome._tcp.";
    private static final Map<String, JSONObject> FOUND = new ConcurrentHashMap<>();
    private static NsdManager nsd;
    private static String mine = "";

    private Peers() {
    }

    /** The name this TV goes by: the place's name in Guest mode, else the device name. */
    static String myName(Context ctx) {
        String place = Stay.json(ctx).optString("place", "").trim();
        if (!place.isEmpty()) return place;
        String dev = android.provider.Settings.Global.getString(ctx.getContentResolver(), "device_name");
        return dev != null && !dev.isEmpty() ? dev : Build.MODEL;
    }

    static synchronized void start(Context ctx) {
        if (nsd != null) return;
        try {
            nsd = ctx.getSystemService(NsdManager.class);
            if (nsd == null) return;
            NsdServiceInfo me = new NsdServiceInfo();
            mine = myName(ctx);
            me.setServiceName(mine);
            me.setServiceType(TYPE);
            me.setPort(HostServer.PORT);
            nsd.registerService(me, NsdManager.PROTOCOL_DNS_SD, new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo i) {
                    mine = i.getServiceName();
                }
                @Override public void onRegistrationFailed(NsdServiceInfo i, int e) { }
                @Override public void onServiceUnregistered(NsdServiceInfo i) { }
                @Override public void onUnregistrationFailed(NsdServiceInfo i, int e) { }
            });
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, new NsdManager.DiscoveryListener() {
                @Override public void onServiceFound(NsdServiceInfo i) {
                    if (i.getServiceName().equals(mine)) return;
                    resolve(i);
                }
                @Override public void onServiceLost(NsdServiceInfo i) {
                    FOUND.remove(i.getServiceName());
                }
                @Override public void onDiscoveryStarted(String t) { }
                @Override public void onDiscoveryStopped(String t) { }
                @Override public void onStartDiscoveryFailed(String t, int e) { }
                @Override public void onStopDiscoveryFailed(String t, int e) { }
            });
        } catch (Exception e) {
            android.util.Log.w("Peers", "mDNS unavailable", e);
        }
    }

    @SuppressWarnings("deprecation")
    private static void resolve(NsdServiceInfo i) {
        try {
            nsd.resolveService(i, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo s, int e) {
                    // Older Android resolves one service at a time: try this one again shortly.
                    if (e == NsdManager.FAILURE_ALREADY_ACTIVE) new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(() -> resolve(i), 1500);
                }
                @Override public void onServiceResolved(NsdServiceInfo s) {
                    InetAddress a = s.getHost();
                    if (a == null || !(a instanceof java.net.Inet4Address)) return;
                    try {
                        FOUND.put(s.getServiceName(), new JSONObject().put("name", s.getServiceName())
                                .put("url", "http://" + a.getHostAddress() + ":" + s.getPort()).put("seen", System.currentTimeMillis()));
                    } catch (Exception ignored) {
                    }
                }
            });
        } catch (Exception ignored) {
            // one resolve at a time on older Android: the next discovery pass picks it up
        }
    }

    static JSONArray list() {
        JSONArray out = new JSONArray();
        for (JSONObject o : FOUND.values()) out.put(o);
        return out;
    }
}
