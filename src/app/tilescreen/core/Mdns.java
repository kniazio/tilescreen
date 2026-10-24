package app.tilescreen.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Advertises the ESPHome API the way an ESPHome device does: _esphomelib._tcp with the node's own
 * host name and TXT records, so Home Assistant discovers the screen by itself.
 */
public final class Mdns {
    static final String SERVICE = "_esphomelib._tcp.local";
    static final String META = "_services._dns-sd._udp.local";
    static final int A = 1, PTR = 12, TXT = 16, SRV = 33, ANY = 255;
    static final int PORT = 5353;

    public interface Address {
        /** The IPv4 address to advertise, or null while there is none. */
        Inet4Address get();
    }

    private final String node, instance, host;
    private final int servicePort;
    private volatile Map<String, String> txt;
    private final Address address;
    private final EspDevice.Logger log;
    private volatile MulticastSocket socket;
    private volatile boolean running;
    private volatile Inet4Address announced;
    private volatile boolean joinedWithout = true;
    private final InetAddress group;

    public Mdns(String node, int servicePort, Map<String, String> txt, Address address, EspDevice.Logger log) throws IOException {
        this.node = node;
        this.instance = node + "." + SERVICE;
        this.host = node + ".local";
        this.servicePort = servicePort;
        this.txt = txt;
        this.address = address;
        this.log = log;
        this.group = InetAddress.getByName("224.0.0.251");
    }

    public void start() throws IOException {
        MulticastSocket s = new MulticastSocket(null);
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(PORT));
        s.setTimeToLive(255);
        joinEverywhere(s);
        socket = s;
        running = true;
        Thread t = new Thread(new Runnable() {
            public void run() { loop(); }
        }, "mdns");
        t.setDaemon(true);
        t.start();
        Thread a = new Thread(new Runnable() {
            public void run() { announceLoop(); }
        }, "mdns-announce");
        a.setDaemon(true);
        a.start();
        log.log("mDNS: advertising " + instance + " on port " + servicePort);
    }

    private void joinEverywhere(MulticastSocket s) {
        boolean joined = false;
        try {
            for (NetworkInterface ni : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                boolean v4 = false;
                for (InetAddress a : java.util.Collections.list(ni.getInetAddresses())) if (a instanceof Inet4Address) v4 = true;
                if (!v4) continue;
                try {
                    s.joinGroup(new InetSocketAddress(group, PORT), ni);
                    joined = true;
                } catch (IOException e) {
                    joined = true; // already a member there
                }
            }
        } catch (IOException e) {
            log.log("mDNS: listing interfaces failed: " + e);
        }
        joinedWithout = !joined;
        if (!joined) {
            try { s.joinGroup(group); } catch (IOException e) { log.log("mDNS: no network to join yet"); }
        }
    }

    /** Says goodbye on the network and closes, on a thread of its own. */
    public void stop() {
        running = false;
        final MulticastSocket s = socket;
        if (s == null) return;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    Inet4Address ip = address.get();
                    if (ip != null) send(s, records(ip, true, 0), new InetSocketAddress(group, PORT), 0);
                } catch (IOException ignored) {
                }
                s.close();
            }
        }, "mdns-goodbye");
        t.start();
        try { t.join(1500); } catch (InterruptedException ignored) {}
    }

    /** New TXT records (the encryption state changed), announced at once. */
    public void setTxt(Map<String, String> records) {
        txt = records;
        announced = null;
    }

    /** Announces again, for example after the address changed. */
    public void announce() {
        announced = null;
    }

    private void announceLoop() {
        int burst = 0;
        long next = 0;
        while (running) {
            try {
                Inet4Address ip = address.get();
                long now = System.currentTimeMillis();
                if (ip != null && !ip.equals(announced)) {
                    if (announced != null || joinedWithout) joinEverywhere(socket); // Wi-Fi came up or moved
                    announced = ip;
                    burst = 3;
                    next = 0;
                    log.log("mDNS: " + host + " is " + ip.getHostAddress());
                }
                if (ip != null && burst > 0 && now >= next) {
                    send(socket, records(ip, true, -1), new InetSocketAddress(group, PORT), 0);
                    burst--;
                    next = now + 1000L * (4 - burst);
                }
                if (ip != null && burst == 0 && now >= next) {
                    next = now + 60000L;
                    burst = 1; // keep caches warm about once a minute
                }
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            } catch (IOException e) {
                if (running) log.log("mDNS: announce failed: " + e);
            }
        }
    }

    private void loop() {
        byte[] buf = new byte[9000];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                handle(p);
            } catch (IOException e) {
                if (running) log.log("mDNS: receive failed: " + e);
            } catch (RuntimeException e) {
                // malformed packet
            }
        }
    }

    private void handle(DatagramPacket p) throws IOException {
        byte[] d = p.getData();
        int len = p.getLength();
        if (len < 12) return;
        int id = u16(d, 0), flags = u16(d, 2), qd = u16(d, 4);
        if ((flags & 0x8000) != 0) return; // a response
        int[] at = {12};
        boolean wantPtr = false, wantSrv = false, wantTxt = false, wantA = false, wantMeta = false, unicast = false;
        for (int q = 0; q < qd; q++) {
            String name = name(d, len, at).toLowerCase(Locale.US);
            int type = u16(d, at[0]), cls = u16(d, at[0] + 2);
            at[0] += 4;
            if ((cls & 0x8000) != 0) unicast = true;
            boolean any = type == ANY;
            if (name.equals(SERVICE) && (type == PTR || any)) wantPtr = true;
            if (name.equals(META) && (type == PTR || any)) wantMeta = true;
            if (name.equals(instance.toLowerCase(Locale.US))) {
                if (type == SRV || any) wantSrv = true;
                if (type == TXT || any) wantTxt = true;
            }
            if (name.equals(host.toLowerCase(Locale.US)) && (type == A || any)) wantA = true;
        }
        if (!(wantPtr || wantSrv || wantTxt || wantA || wantMeta)) return;
        Inet4Address ip = address.get();
        if (ip == null) return;
        Records r = new Records();
        if (wantMeta) r.answer(ptr(META, SERVICE, 4500));
        if (wantPtr) {
            r.answer(ptr(SERVICE, instance, 4500));
            r.extra(srv(120));
            r.extra(txt(4500));
            r.extra(a(ip, 120));
        }
        if (wantSrv) {
            r.answer(srv(120));
            r.extra(a(ip, 120));
        }
        if (wantTxt) r.answer(txt(4500));
        if (wantA) r.answer(a(ip, 120));
        boolean legacy = p.getPort() != PORT;
        if (legacy || unicast) {
            send(socket, r.packet(legacy ? id : 0), new InetSocketAddress(p.getAddress(), p.getPort()), 0);
        } else {
            send(socket, r.packet(0), new InetSocketAddress(group, PORT), 0);
        }
    }

    private void send(MulticastSocket s, byte[] packet, InetSocketAddress to, int unused) throws IOException {
        if (s == null) return;
        s.send(new DatagramPacket(packet, packet.length, to));
    }

    private byte[] records(Inet4Address ip, boolean all, int ttl) {
        Records r = new Records();
        r.answer(ptr(SERVICE, instance, ttl < 0 ? 4500 : ttl));
        r.answer(srv(ttl < 0 ? 120 : ttl));
        r.answer(txt(ttl < 0 ? 4500 : ttl));
        r.answer(a(ip, ttl < 0 ? 120 : ttl));
        return r.packet(0);
    }

    // ----- records -----

    private static final class Records {
        final List<byte[]> answers = new ArrayList<byte[]>(), extras = new ArrayList<byte[]>();

        void answer(byte[] r) { answers.add(r); }

        void extra(byte[] r) { extras.add(r); }

        byte[] packet(int id) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            put16(b, id);
            put16(b, 0x8400); // response, authoritative
            put16(b, 0);
            put16(b, answers.size());
            put16(b, 0);
            put16(b, extras.size());
            for (byte[] r : answers) b.write(r, 0, r.length);
            for (byte[] r : extras) b.write(r, 0, r.length);
            return b.toByteArray();
        }
    }

    private byte[] ptr(String name, String target, int ttl) {
        return record(name, PTR, false, ttl, encodeName(target));
    }

    private byte[] srv(int ttl) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        put16(b, 0);
        put16(b, 0);
        put16(b, servicePort);
        byte[] h = encodeName(host);
        b.write(h, 0, h.length);
        return record(instance, SRV, true, ttl, b.toByteArray());
    }

    private byte[] txt(int ttl) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (Map.Entry<String, String> e : txt.entrySet()) {
            byte[] kv = (e.getKey() + "=" + e.getValue()).getBytes(Pb.UTF8);
            if (kv.length > 255) continue;
            b.write(kv.length);
            b.write(kv, 0, kv.length);
        }
        if (b.size() == 0) b.write(0);
        return record(instance, TXT, true, ttl, b.toByteArray());
    }

    private byte[] a(Inet4Address ip, int ttl) {
        return record(host, A, true, ttl, ip.getAddress());
    }

    private static byte[] record(String name, int type, boolean flush, int ttl, byte[] data) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] n = encodeName(name);
        b.write(n, 0, n.length);
        put16(b, type);
        put16(b, flush ? 0x8001 : 0x0001);
        b.write((ttl >>> 24) & 0xff);
        b.write((ttl >>> 16) & 0xff);
        b.write((ttl >>> 8) & 0xff);
        b.write(ttl & 0xff);
        put16(b, data.length);
        b.write(data, 0, data.length);
        return b.toByteArray();
    }

    static byte[] encodeName(String name) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (String label : name.split("\\.")) {
            if (label.isEmpty()) continue;
            byte[] l = label.getBytes(Pb.UTF8);
            b.write(Math.min(63, l.length));
            b.write(l, 0, Math.min(63, l.length));
        }
        b.write(0);
        return b.toByteArray();
    }

    static String name(byte[] d, int len, int[] at) {
        StringBuilder b = new StringBuilder();
        int p = at[0];
        boolean jumped = false;
        int hops = 0;
        while (true) {
            if (p >= len) throw new IllegalArgumentException("short name");
            int l = d[p] & 0xff;
            if (l == 0) {
                p++;
                break;
            }
            if ((l & 0xC0) == 0xC0) {
                if (++hops > 16) throw new IllegalArgumentException("loop");
                int to = ((l & 0x3F) << 8) | (d[p + 1] & 0xff);
                if (!jumped) at[0] = p + 2;
                jumped = true;
                p = to;
                continue;
            }
            if (b.length() > 0) b.append('.');
            b.append(new String(d, p + 1, l, Pb.UTF8));
            p += l + 1;
        }
        if (!jumped) at[0] = p;
        return b.toString();
    }

    private static int u16(byte[] d, int at) { return ((d[at] & 0xff) << 8) | (d[at + 1] & 0xff); }

    private static void put16(ByteArrayOutputStream b, int v) {
        b.write((v >>> 8) & 0xff);
        b.write(v & 0xff);
    }
}
