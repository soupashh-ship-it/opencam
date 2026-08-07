package io.opencam.webcam.mdns;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import io.opencam.webcam.util.Logs;

/**
 * Registers the stream with mDNS/Bonjour so desktop tools can auto-discover the phone.
 * The service type {@code _droidcamobs._tcp.} is the established discovery type used by
 * OBS-plugin style clients (protocol/interoperability fact).
 */
public class Discovery {

    private static final String SERVICE_TYPE = "_droidcamobs._tcp.";

    private final NsdManager nsdManager;
    private final NsdManager.RegistrationListener listener;
    private boolean registered;

    public Discovery(Context context) {
        nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        listener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                Logs.i("mDNS registered: " + serviceInfo.getServiceName());
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Logs.e("mDNS registration failed: " + errorCode);
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            }
        };
    }

    public void start(String deviceName, int port) {
        if (nsdManager == null || registered) {
            return;
        }
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(deviceName);
        info.setServiceType(SERVICE_TYPE);
        info.setAttribute("name", deviceName);
        info.setPort(port);
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
            registered = true;
        } catch (Exception e) {
            Logs.e("mDNS start failed", e);
        }
    }

    public void stop() {
        if (nsdManager != null && registered) {
            try {
                nsdManager.unregisterService(listener);
            } catch (Exception e) {
                Logs.e("mDNS stop failed", e);
            }
            registered = false;
        }
    }
}
