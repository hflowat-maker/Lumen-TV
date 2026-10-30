package io.freemytv;

import android.bluetooth.BluetoothClass;
import android.content.Context;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The paired headphones and speakers, read and connected through BtShell (the TV's own ADB, as the shell user). */
final class Bt {
    private Bt() {}

    static final class Device {
        String address, name;
        int major;
        boolean connected;
    }

    static final class Result {
        final List<Device> devices = new ArrayList<>();
        boolean off;
        String error;
    }

    private static Result last;
    private static long lastAt;

    /** Paired audio devices (headphones, speakers), connected ones first. Cached for 2 s. */
    static synchronized Result audio(Context c, boolean fresh) {
        long now = System.currentTimeMillis();
        if (!fresh && last != null && now - lastAt < 2000) return last;
        Result r = new Result();
        try {
            for (String line : helper(c, "list").split("\n")) {
                String[] f = line.trim().split("\t", 5);
                if (f[0].equals("OFF")) r.off = true;
                if (f[0].equals("ERR")) r.error = f.length > 1 ? f[1] : "error";
                if (!f[0].equals("DEV") || f.length < 5) continue;
                Device d = new Device();
                d.address = f[1];
                d.major = parse(f[2]);
                d.connected = "1".equals(f[3]);
                d.name = f[4];
                if (d.major == BluetoothClass.Device.Major.AUDIO_VIDEO) r.devices.add(d);
            }
        } catch (IOException e) {
            r.error = e.getMessage();
        }
        java.util.Collections.sort(r.devices, (a, b) -> Boolean.compare(b.connected, a.connected));
        last = r;
        lastAt = now;
        return r;
    }

    /** Connects (or disconnects) a paired device; null when it went through, else why not. */
    static String connect(Context c, String address, boolean on) {
        if (address == null || !address.matches("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}")) return "bad address";
        try {
            String out = helper(c, (on ? "connect " : "disconnect ") + address.toUpperCase(java.util.Locale.ROOT));
            synchronized (Bt.class) {
                last = null;
            }
            for (String line : out.split("\n")) {
                line = line.trim();
                if (line.startsWith("OK")) {
                    String code = line.length() > 3 ? line.substring(3).trim() : "0";
                    // BluetoothStatusCodes.SUCCESS = 0 (Android 13+); true on Android 12
                    return code.equals("0") || code.equals("true") ? null : "Bluetooth: " + code;
                }
                if (line.startsWith("ERR")) return line.substring(3).trim();
            }
            return out.trim().isEmpty() ? "no answer" : out.trim();
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    private static String helper(Context c, String args) throws IOException {
        String apk = c.getApplicationInfo().sourceDir;
        if (apk == null || apk.contains("'")) throw new IOException("APK path");
        return AdbRunner.get(c).run("CLASSPATH='" + apk + "' app_process /system/bin io.freemytv.BtShell " + args + " 2>&1", 25_000);
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
