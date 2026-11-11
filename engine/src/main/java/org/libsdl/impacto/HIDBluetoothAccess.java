package org.libsdl.impacto;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import java.util.Collections;
import java.util.List;

/** Permission can be revoked between discovery and a later GATT callback. */
final class HIDBluetoothAccess {
    private HIDBluetoothAccess() {}

    private static void denied(SecurityException error) {
        Log.w("hidapi", "Bluetooth permission unavailable", error);
    }

    static boolean read(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        try { return gatt != null && gatt.readCharacteristic(characteristic); }
        catch (SecurityException error) { denied(error); return false; }
    }

    static boolean write(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        try { return gatt != null && gatt.writeCharacteristic(characteristic); }
        catch (SecurityException error) { denied(error); return false; }
    }

    static boolean notify(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, boolean enabled) {
        try { return gatt != null && gatt.setCharacteristicNotification(characteristic, enabled); }
        catch (SecurityException error) { denied(error); return false; }
    }

    static boolean writeDescriptor(BluetoothGatt gatt, BluetoothGattDescriptor descriptor) {
        try { return gatt != null && gatt.writeDescriptor(descriptor); }
        catch (SecurityException error) { denied(error); return false; }
    }

    static BluetoothGatt connect(BluetoothDevice device, Context context, boolean managed,
                                 BluetoothGattCallback callback, int transport) {
        try { return device.connectGatt(context, managed, callback, transport); }
        catch (SecurityException error) { denied(error); return null; }
    }

    static BluetoothGatt connect(BluetoothDevice device, Context context, boolean managed,
                                 BluetoothGattCallback callback) {
        try { return device.connectGatt(context, managed, callback); }
        catch (SecurityException error) { denied(error); return null; }
    }

    static int connectionState(BluetoothManager manager, BluetoothDevice device, int profile) {
        try { return manager.getConnectionState(device, profile); }
        catch (SecurityException error) { denied(error); return BluetoothProfile.STATE_DISCONNECTED; }
    }

    static List<BluetoothDevice> connectedDevices(BluetoothManager manager, int profile) {
        try { return manager.getConnectedDevices(profile); }
        catch (SecurityException error) { denied(error); return Collections.emptyList(); }
    }

    static void disconnect(BluetoothGatt gatt) {
        try { if (gatt != null) gatt.disconnect(); }
        catch (SecurityException error) { denied(error); }
    }

    static void close(BluetoothGatt gatt) {
        try { if (gatt != null) gatt.close(); }
        catch (SecurityException error) { denied(error); }
    }

    static boolean discover(BluetoothGatt gatt) {
        try { return gatt != null && gatt.discoverServices(); }
        catch (SecurityException error) { denied(error); return false; }
    }

    static boolean requestMtu(BluetoothGatt gatt, int size) {
        try { return gatt != null && gatt.requestMtu(size); }
        catch (SecurityException error) { denied(error); return false; }
    }

    static String name(BluetoothDevice device) {
        try { String name = device.getName(); return name == null ? "" : name; }
        catch (SecurityException error) { denied(error); return ""; }
    }

    static int type(BluetoothDevice device) {
        try { return device.getType(); }
        catch (SecurityException error) { denied(error); return BluetoothDevice.DEVICE_TYPE_UNKNOWN; }
    }
}
