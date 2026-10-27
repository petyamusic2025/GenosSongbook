package hu.petya.genossongbook;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * RTP-MIDI (AppleMIDI / "hálózati MIDI") kliens: kapcsolat a Genoshoz WiFi-n.
 * Tiszta Java, Android nélkül is futtatható (így tesztelhető).
 *
 * Menete: meghívás a vezérlő portra (IN → OK), meghívás az adatportra (port+1),
 * óraszinkron (CK), utána MIDI-üzenetek RTP csomagokban mindkét irányba.
 */
public class RtpMidiSession {

    public interface Listener {
        void onState(String state, String info);   // connecting / connected / closed / error

        void onMidi(byte[] msg);                    // egy teljes MIDI-üzenet (SysEx: F0 … F7)
    }

    private final String host;
    private final int port;
    private final String name;
    private final Listener listener;

    private DatagramSocket ctrl, data;
    private InetAddress addr;
    private final int ssrc, token;
    private int remoteSsrc;
    private volatile boolean running = false;
    private volatile boolean connected = false;
    private final long t0 = System.nanoTime();
    private int seq;
    private final ByteArrayOutputStream sysex = new ByteArrayOutputStream();
    private boolean inSysex = false;
    private byte runningStatus = 0;

    public RtpMidiSession(String host, int port, String name, Listener l) {
        this.host = host;
        this.port = port;
        this.name = name == null ? "Genos Songbook" : name;
        this.listener = l;
        Random r = new Random();
        this.ssrc = r.nextInt();
        this.token = r.nextInt();
        this.seq = r.nextInt(0xFFFF);
    }

    public boolean isConnected() {
        return connected;
    }

    /** 100 mikroszekundumos egységek a kapcsolat kezdete óta */
    private long now() {
        return (System.nanoTime() - t0) / 100_000L;
    }

    public void start() {
        running = true;
        Thread t = new Thread(this::runSession, "rtpmidi");
        t.setDaemon(true);
        t.start();
    }

    private void runSession() {
        try {
            listener.onState("connecting", host + ":" + port);
            addr = InetAddress.getByName(host);
            bindPair();
            if (!invite(ctrl, port)) {
                fail("A Genos nem válaszolt a vezérlő porton (" + host + ":" + port + ")");
                return;
            }
            if (!invite(data, port + 1)) {
                fail("A Genos nem válaszolt az adatporton (" + (port + 1) + ")");
                return;
            }
            connected = true;
            listener.onState("connected", host + ":" + port);
            Thread rc = new Thread(() -> receiveLoop(ctrl), "rtpmidi-ctrl");
            rc.setDaemon(true);
            rc.start();
            Thread rd = new Thread(() -> receiveLoop(data), "rtpmidi-data");
            rd.setDaemon(true);
            rd.start();
            // óraszinkron: eleinte sűrűbben, utána 10 mp-enként
            int n = 0;
            while (running) {
                sendCk(0, now(), 0, 0);
                Thread.sleep(n++ < 5 ? 1500 : 10000);
            }
        } catch (Exception e) {
            if (running) fail(String.valueOf(e.getMessage()));
        }
    }

    private void bindPair() throws Exception {
        // a saját vezérlő- és adatport egymás után (P, P+1), ahogy a szabvány elvárja
        for (int p = 5104; p < 5200; p += 2) {
            try {
                DatagramSocket a = new DatagramSocket(p);
                try {
                    DatagramSocket b = new DatagramSocket(p + 1);
                    ctrl = a;
                    data = b;
                    return;
                } catch (Exception e) {
                    a.close();
                }
            } catch (Exception ignored) {
            }
        }
        ctrl = new DatagramSocket();
        data = new DatagramSocket();
    }

    private byte[] cmd(char a, char b, int extraLen) {
        byte[] p = new byte[4 + extraLen];
        p[0] = (byte) 0xFF;
        p[1] = (byte) 0xFF;
        p[2] = (byte) a;
        p[3] = (byte) b;
        return p;
    }

    private static void put32(byte[] p, int off, int v) {
        p[off] = (byte) (v >>> 24);
        p[off + 1] = (byte) (v >>> 16);
        p[off + 2] = (byte) (v >>> 8);
        p[off + 3] = (byte) v;
    }

    private static void put64(byte[] p, int off, long v) {
        for (int i = 0; i < 8; i++) p[off + i] = (byte) (v >>> (56 - 8 * i));
    }

    private static int get32(byte[] p, int off) {
        return ((p[off] & 0xFF) << 24) | ((p[off + 1] & 0xFF) << 16) | ((p[off + 2] & 0xFF) << 8) | (p[off + 3] & 0xFF);
    }

    private static long get64(byte[] p, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (p[off + i] & 0xFF);
        return v;
    }

    private byte[] invitation(char a, char b) {
        byte[] nm = name.getBytes(StandardCharsets.UTF_8);
        byte[] p = cmd(a, b, 12 + nm.length + 1);
        put32(p, 4, 2);          // protokoll verzió
        put32(p, 8, token);
        put32(p, 12, ssrc);
        System.arraycopy(nm, 0, p, 16, nm.length);
        return p;                 // a végén 0 (null-terminált név)
    }

    /** IN küldése, várakozás OK-ra (legfeljebb 12 próbálkozás) */
    private boolean invite(DatagramSocket s, int toPort) throws Exception {
        byte[] inv = invitation('I', 'N');
        byte[] buf = new byte[1500];
        s.setSoTimeout(1000);
        for (int i = 0; i < 12 && running; i++) {
            s.send(new DatagramPacket(inv, inv.length, addr, toPort));
            long until = System.currentTimeMillis() + 1000;
            while (System.currentTimeMillis() < until) {
                DatagramPacket dp = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(dp);
                } catch (SocketTimeoutException e) {
                    break;
                }
                int n = dp.getLength();
                if (n >= 16 && (buf[0] & 0xFF) == 0xFF && (buf[1] & 0xFF) == 0xFF) {
                    if (buf[2] == 'O' && buf[3] == 'K' && get32(buf, 8) == token) {
                        remoteSsrc = get32(buf, 12);
                        s.setSoTimeout(0);
                        return true;
                    }
                    if (buf[2] == 'N' && buf[3] == 'O') {
                        throw new Exception("A Genos elutasította a kapcsolatot");
                    }
                }
            }
        }
        return false;
    }

    private void sendCk(int count, long ts1, long ts2, long ts3) {
        try {
            byte[] p = cmd('C', 'K', 32);
            put32(p, 4, ssrc);
            p[8] = (byte) count;
            put64(p, 12, ts1);
            put64(p, 20, ts2);
            put64(p, 28, ts3);
            data.send(new DatagramPacket(p, p.length, addr, port + 1));
        } catch (Exception ignored) {
        }
    }

    private void receiveLoop(DatagramSocket s) {
        byte[] buf = new byte[4096];
        while (running) {
            try {
                DatagramPacket dp = new DatagramPacket(buf, buf.length);
                s.receive(dp);
                int n = dp.getLength();
                if (n >= 4 && (buf[0] & 0xFF) == 0xFF && (buf[1] & 0xFF) == 0xFF) {
                    handleCommand(buf, n);
                } else if (n >= 13) {
                    handleRtp(buf, n);
                }
            } catch (Exception e) {
                if (running) {
                    fail("Megszakadt: " + e.getMessage());
                }
                return;
            }
        }
    }

    private void handleCommand(byte[] b, int n) {
        char c1 = (char) b[2], c2 = (char) b[3];
        if (c1 == 'B' && c2 == 'Y') {
            running = false;
            connected = false;
            closeSockets();
            listener.onState("closed", "A Genos bontotta a kapcsolatot");
        } else if (c1 == 'C' && c2 == 'K' && n >= 36) {
            int count = b[8] & 0xFF;
            long ts1 = get64(b, 12), ts2 = get64(b, 20);
            if (count == 0) sendCk(1, ts1, now(), 0);          // a Genos kezdte: válaszolunk
            else if (count == 1) sendCk(2, ts1, ts2, now());   // a mi szinkronunk második lépése
        }
    }

    /* ---------- bejövő RTP-MIDI ---------- */

    private void handleRtp(byte[] b, int n) {
        if ((b[0] & 0xC0) != 0x80) return;               // RTP 2-es verzió
        int cc = b[0] & 0x0F;
        int pos = 12 + 4 * cc;
        if (pos >= n) return;
        int h = b[pos] & 0xFF;
        boolean big = (h & 0x80) != 0, zFirst = (h & 0x20) != 0;
        int len = h & 0x0F;
        pos++;
        if (big) {
            if (pos >= n) return;
            len = (len << 8) | (b[pos] & 0xFF);
            pos++;
        }
        int end = Math.min(n, pos + len);
        boolean first = true;
        while (pos < end) {
            if (!first || zFirst) {                       // delta idő (1–4 bájt) – nem használjuk
                int k = 0;
                while (pos < end && (b[pos] & 0x80) != 0 && k < 3) {
                    pos++;
                    k++;
                }
                pos++;
                if (pos >= end) break;
            }
            first = false;
            pos = parseCommand(b, pos, end);
        }
    }

    private int parseCommand(byte[] b, int pos, int end) {
        int st = b[pos] & 0xFF;
        if (st == 0xF0 || (st == 0xF7 && inSysex)) {      // SysEx (darabolva is jöhet)
            if (st == 0xF0) {
                sysex.reset();
                inSysex = true;
                sysex.write(0xF0);
            }
            pos++;
            while (pos < end) {
                int v = b[pos] & 0xFF;
                pos++;
                if (v == 0xF7) {                           // vége
                    sysex.write(0xF7);
                    inSysex = false;
                    listener.onMidi(sysex.toByteArray());
                    return pos;
                }
                if (v == 0xF0) return pos;                 // a darab vége, folytatás a következőben
                if (v == 0xF4) {                           // megszakítva
                    inSysex = false;
                    return pos;
                }
                sysex.write(v);
            }
            return pos;
        }
        int status;
        if (st >= 0x80) {
            status = st;
            pos++;
            if (st < 0xF0) runningStatus = (byte) st;
        } else {
            status = runningStatus & 0xFF;                 // futó státusz
            if (status == 0) return end;
        }
        int dl = dataLen(status);
        if (pos + dl > end) return end;
        byte[] msg = new byte[1 + dl];
        msg[0] = (byte) status;
        System.arraycopy(b, pos, msg, 1, dl);
        listener.onMidi(msg);
        return pos + dl;
    }

    private static int dataLen(int status) {
        int hi = status & 0xF0;
        if (hi == 0xC0 || hi == 0xD0) return 1;
        if (hi >= 0x80 && hi <= 0xE0) return 2;
        if (status == 0xF1 || status == 0xF3) return 1;
        if (status == 0xF2) return 2;
        return 0;
    }

    /* ---------- kimenő MIDI ---------- */

    /** Egy vagy több egymás utáni MIDI-üzenet küldése a Genosnak */
    public boolean send(byte[] midi) {
        if (!connected || midi == null || midi.length == 0) return false;
        try {
            ByteArrayOutputStream list = new ByteArrayOutputStream();
            int i = 0;
            boolean first = true;
            while (i < midi.length) {
                int st = midi[i] & 0xFF;
                int len;
                if (st == 0xF0) {
                    int j = i + 1;
                    while (j < midi.length && (midi[j] & 0xFF) != 0xF7) j++;
                    len = Math.min(midi.length, j + 1) - i;
                } else if (st >= 0x80) {
                    len = 1 + dataLen(st);
                } else {
                    break;                                 // hibás bájt: a többit eldobjuk
                }
                if (!first) list.write(0x00);              // delta idő a második üzenettől
                list.write(midi, i, Math.min(len, midi.length - i));
                first = false;
                i += len;
            }
            byte[] cmds = list.toByteArray();
            if (cmds.length == 0) return false;
            int hl = cmds.length > 15 ? 2 : 1;
            byte[] p = new byte[12 + hl + cmds.length];
            p[0] = (byte) 0x80;
            p[1] = (byte) 0x61;
            seq = (seq + 1) & 0xFFFF;
            p[2] = (byte) (seq >>> 8);
            p[3] = (byte) seq;
            put32(p, 4, (int) now());
            put32(p, 8, ssrc);
            if (hl == 2) {
                p[12] = (byte) (0x80 | ((cmds.length >>> 8) & 0x0F));
                p[13] = (byte) cmds.length;
            } else {
                p[12] = (byte) cmds.length;
            }
            System.arraycopy(cmds, 0, p, 12 + hl, cmds.length);
            data.send(new DatagramPacket(p, p.length, addr, port + 1));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void stop() {
        boolean was = running;
        running = false;
        try {
            if (connected && ctrl != null) {
                byte[] by = cmd('B', 'Y', 12);
                put32(by, 4, 2);
                put32(by, 8, token);
                put32(by, 12, ssrc);
                ctrl.send(new DatagramPacket(by, by.length, addr, port));
            }
        } catch (Exception ignored) {
        }
        connected = false;
        closeSockets();
        if (was) listener.onState("closed", "Leválasztva");
    }

    private void closeSockets() {
        try {
            if (ctrl != null) ctrl.close();
        } catch (Exception ignored) {
        }
        try {
            if (data != null) data.close();
        } catch (Exception ignored) {
        }
    }

    private void fail(String msg) {
        boolean was = running;
        running = false;
        connected = false;
        closeSockets();
        if (was) listener.onState("error", msg);
    }
}
