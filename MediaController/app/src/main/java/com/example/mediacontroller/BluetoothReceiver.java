package com.example.mediacontroller;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BluetoothReceiver extends BroadcastReceiver {
    public BluetoothReceiver(BluetoothService service) {
        this.service = service;
    }

    BluetoothService service;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            // Bluetooth device disconnected
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            // Handle the disconnection event by trying to reconnect
            service.connectToBluetoothDevice();
        }
    }
}
