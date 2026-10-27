package hu.petya.genossongbook;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * WiFi-s MIDI a Genoshoz (RTP-MIDI / AppleMIDI).
 * discover(): hálózati MIDI-eszközök keresése (_apple-midi._udp, tájékoztatásul _midi2._udp)
 * connect({host, port}) / disconnect() / send({data:[…]})
 * Események: "service" {name, host, port, type}, "state" {state, info}, "midi" {data:[…]}
 */
@CapacitorPlugin(name = "NetMidi")
public class NetMidiPlugin extends Plugin {

    private NsdManager nsd;
    private final List<NsdManager.DiscoveryListener> discoveries = new ArrayList<>();
    private final LinkedList<NsdServiceInfo> resolveQueue = new LinkedList<>();
    private boolean resolving = false;
    private RtpMidiSession session;
    private WifiManager.MulticastLock mlock;

    @PluginMethod
    public void discover(PluginCall call) {
        stopDiscovery();
        try {
            nsd = (NsdManager) getContext().getSystemService(Context.NSD_SERVICE);
            WifiManager wm = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            mlock = wm.createMulticastLock("genos-netmidi");
            mlock.setReferenceCounted(false);
            mlock.acquire();
            for (String type : new String[]{"_apple-midi._udp", "_midi2._udp"}) {
                NsdManager.DiscoveryListener l = makeListener(type);
                discoveries.add(l);
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void stopDiscover(PluginCall call) {
        stopDiscovery();
        call.resolve();
    }

    private void stopDiscovery() {
        if (nsd != null) {
            for (NsdManager.DiscoveryListener l : discoveries) {
                try {
                    nsd.stopServiceDiscovery(l);
                } catch (Exception ignored) {
                }
            }
        }
        discoveries.clear();
        synchronized (resolveQueue) {
            resolveQueue.clear();
            resolving = false;
        }
        try {
            if (mlock != null && mlock.isHeld()) mlock.release();
        } catch (Exception ignored) {
        }
    }

    private NsdManager.DiscoveryListener makeListener(final String type) {
        return new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                JSObject o = new JSObject();
                o.put("state", "discover-error");
                o.put("info", type + " (" + errorCode + ")");
                notifyListeners("state", o);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                synchronized (resolveQueue) {
                    resolveQueue.add(info);
                }
                resolveNext();
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
            }
        };
    }

    // a régebbi Androidok egyszerre csak egy feloldást engednek: sorban haladunk
    private void resolveNext() {
        NsdServiceInfo next;
        synchronized (resolveQueue) {
            if (resolving || resolveQueue.isEmpty() || nsd == null) return;
            resolving = true;
            next = resolveQueue.removeFirst();
        }
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    done();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo info) {
                    JSObject o = new JSObject();
                    o.put("name", info.getServiceName());
                    o.put("host", info.getHost() != null ? info.getHost().getHostAddress() : "");
                    o.put("port", info.getPort());
                    o.put("type", String.valueOf(info.getServiceType()));
                    notifyListeners("service", o);
                    done();
                }

                private void done() {
                    synchronized (resolveQueue) {
                        resolving = false;
                    }
                    resolveNext();
                }
            });
        } catch (Exception e) {
            synchronized (resolveQueue) {
                resolving = false;
            }
            resolveNext();
        }
    }

    @PluginMethod
    public void connect(PluginCall call) {
        String host = call.getString("host", "");
        int port = call.getInt("port", 5004);
        String name = call.getString("name", "Genos Songbook");
        if (host == null || host.isEmpty()) {
            call.reject("Nincs megadva cím");
            return;
        }
        if (session != null) session.stop();
        session = new RtpMidiSession(host, port, name, new RtpMidiSession.Listener() {
            @Override
            public void onState(String state, String info) {
                JSObject o = new JSObject();
                o.put("state", state);
                o.put("info", info);
                notifyListeners("state", o);
            }

            @Override
            public void onMidi(byte[] msg) {
                JSArray a = new JSArray();
                for (byte b : msg) a.put(b & 0xFF);
                JSObject o = new JSObject();
                o.put("data", a);
                notifyListeners("midi", o);
            }
        });
        session.start();
        call.resolve();
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        if (session != null) session.stop();
        session = null;
        call.resolve();
    }

    @PluginMethod
    public void send(PluginCall call) {
        JSArray arr = call.getArray("data");
        if (session == null || arr == null) {
            call.reject("Nincs WiFi MIDI kapcsolat");
            return;
        }
        try {
            byte[] b = new byte[arr.length()];
            for (int i = 0; i < b.length; i++) b[i] = (byte) arr.getInt(i);
            JSObject r = new JSObject();
            r.put("ok", session.send(b));
            call.resolve(r);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @Override
    protected void handleOnDestroy() {
        stopDiscovery();
        if (session != null) session.stop();
        session = null;
    }
}
