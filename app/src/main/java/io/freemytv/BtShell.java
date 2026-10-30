package io.freemytv;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.os.Looper;

import java.lang.reflect.Method;

/**
 * Bluetooth helper started by the app through the TV's own ADB, as the shell user:
 *   CLASSPATH=<this APK> app_process /system/bin io.freemytv.BtShell list | connect AA:BB:… | disconnect AA:BB:…
 * Connecting a paired device is a system-only call on Android (BluetoothDevice.connect(), needs
 * BLUETOOTH_PRIVILEGED), which the shell user has and a normal app doesn't. Prints one line per result:
 *   DEV<TAB>address<TAB>major class<TAB>connected (0/1)<TAB>name,  OFF (Bluetooth is off),  OK[<TAB>code],  ERR<TAB>why
 * Runs nothing else: no Context, none of the app's other classes.
 */
public final class BtShell {
    private BtShell() {}

    public static void main(String[] args) {
        try {
            try {
                Looper.prepareMainLooper();
            } catch (RuntimeException ignored) {
            }
            // Android 13+: an app's process gets Bluetooth's service manager when it starts (ActivityThread); this
            // bare process has to set it itself, or getDefaultAdapter() returns null.
            try {
                Class<?> manager = Class.forName("android.os.BluetoothServiceManager");
                Class.forName("android.bluetooth.BluetoothFrameworkInitializer")
                        .getMethod("setBluetoothServiceManager", manager)
                        .invoke(null, manager.getConstructor().newInstance());
            } catch (Throwable t) {   // Android 12 and older (no such class), or already set
                if (args.length > 0 && args[args.length - 1].equals("-v")) out("DBG\t" + t + (t.getCause() != null ? " / " + t.getCause() : ""));
            }
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                out("ERR\tno Bluetooth on this TV");
                return;
            }
            if (!adapter.isEnabled()) out("OFF");
            String cmd = args.length > 0 ? args[0] : "list";
            if ("list".equals(cmd)) {
                for (BluetoothDevice d : adapter.getBondedDevices()) {
                    BluetoothClass cls = d.getBluetoothClass();
                    int major = cls == null ? 0 : cls.getMajorDeviceClass();
                    String name = null;
                    try {
                        name = d.getAlias();
                    } catch (RuntimeException ignored) {
                    }
                    if (name == null || name.isEmpty()) name = d.getName();
                    if (name == null) name = d.getAddress();
                    out("DEV\t" + d.getAddress() + "\t" + major + "\t" + (connected(d) ? 1 : 0) + "\t"
                            + name.replace('\t', ' ').replace('\n', ' '));
                }
                out("OK");
            } else if (("connect".equals(cmd) || "disconnect".equals(cmd)) && args.length > 1) {
                BluetoothDevice d = adapter.getRemoteDevice(args[1]);
                boolean on = "connect".equals(cmd);
                Object result;
                try {
                    Method m = BluetoothDevice.class.getMethod(on ? "connect" : "disconnect");
                    result = m.invoke(d);
                } catch (NoSuchMethodException e) {
                    // Android 12: the same call lived on the adapter.
                    Method m = BluetoothAdapter.class.getMethod(on ? "connectAllEnabledProfiles" : "disconnectAllEnabledProfiles",
                            BluetoothDevice.class);
                    result = m.invoke(adapter, d);
                }
                out("OK\t" + result);
            } else {
                out("ERR\tunknown command");
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            out("ERR\t" + e.getCause());
        } catch (Throwable t) {
            out("ERR\t" + t);
        } finally {
            System.out.flush();
            System.exit(0);     // Bluetooth's binder threads would keep the process alive
        }
    }

    private static boolean connected(BluetoothDevice d) {
        try {
            return (Boolean) BluetoothDevice.class.getMethod("isConnected").invoke(d);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void out(String line) {
        System.out.println(line);
    }
}
