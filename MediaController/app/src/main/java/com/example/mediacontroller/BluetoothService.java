package com.example.mediacontroller;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class BluetoothService extends Service {
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "BluetoothServiceChannel";

    private static final String TAG = "BluetoothService";
    private static final String DEVICE_NAME = "HC-06";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothDevice targetDevice;
    private BluetoothSocket bluetoothSocket;

    private AudioManager audioManager;
    private MediaController mediaController;
    private MediaSessionManager mediaSessionManager;

    @Override
    public void onCreate() {
        super.onCreate();
        Notification notification = createNotification();
        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Get audioManager (might not be used)
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        // Security requirements mandate I have to ask for "Notification" permission to manage media control???
        Intent i = new Intent(getApplicationContext(), NotificationListener.class);
        startService(i);

        // Gets all media sessions on the phone.
        mediaSessionManager = (MediaSessionManager) this.getSystemService(Context.MEDIA_SESSION_SERVICE);
        updateMediaSessions();

        BluetoothReceiver bluetoothReceiver = new BluetoothReceiver(this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        registerReceiver(bluetoothReceiver, filter);

        connectToBluetoothDevice();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (bluetoothSocket != null) {
            try {
                bluetoothSocket.close();
                Log.i(TAG, "Disconnected from " + DEVICE_NAME);
            } catch (IOException e) {
                Log.e(TAG, "Error closing Bluetooth socket: " + e.getMessage());
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void connectToBluetoothDevice() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT);
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();

        // Check if the target device is paired
        for (BluetoothDevice device : pairedDevices) {
            if (device.getName().contains(DEVICE_NAME)) {
                targetDevice = device;
                break;
            }
        }

        if (targetDevice == null) {
            Log.e(TAG, "Target device not found, please pair the device.");
            stopSelf();
            return;
        }

        boolean isConnected = false;
        while (!isConnected) {
            try {
                bluetoothSocket = targetDevice.createRfcommSocketToServiceRecord(MY_UUID);
                bluetoothSocket.connect();
                Log.i(TAG, "Connected to " + DEVICE_NAME);
                isConnected = true; // Connection successful, exit the loop

            } catch (IOException e) {
                Log.e(TAG, "Error connecting to Bluetooth device: " + e.getMessage());
                Log.i(TAG, "Failed to connect to " + DEVICE_NAME + ", retrying.");

                // Retry connection after a delay
                try {
                    Thread.sleep(1000); // Delay for 1 second before retrying
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }
        }
        startBluetoothCommandDecoder();
    }

    private void startBluetoothCommandDecoder() {
        if (bluetoothSocket == null) {
            Log.e(TAG, "Bluetooth socket is null");
            connectToBluetoothDevice();
        }

        try {
            InputStream inputStream = bluetoothSocket.getInputStream();

            byte[] buffer = new byte[1024];
            int bytesRead;

            while (bluetoothSocket.isConnected()) {
                // Read data from the input stream
                bytesRead = inputStream.read(buffer);
                String receivedMessage = new String(buffer, 0, bytesRead);

                // Update media sessions every time we receive a command.
                updateMediaSessions();
                // Check the code sent by the Arduino and do the appropiate action
                if (mediaController != null) {
                    switch (receivedMessage) {
                        case "P!":
                            pauseSong();
                            break;
                        case "F!":
                            seekForward();
                            break;
                        case "N!":
                            nextSong();
                            break;
                        case "B!":
                            previousSong();
                            break;
                    }
                }

                if (receivedMessage.startsWith("!")) {
                    // Volume is of format !(VOLUME), where VOLUME is of values from 0 to 99
                    float volume = Float.parseFloat(receivedMessage.substring(1, 3));
                    setVolume(volume / 100);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error getting Bluetooth input stream: " + e.getMessage());
        }
    }

    private void previousSong() {
        PlaybackState playbackState = mediaController.getPlaybackState();
        if (playbackState != null) {
            mediaController.getTransportControls().skipToPrevious();
        }
    }

    private void nextSong() {
        PlaybackState playbackState = mediaController.getPlaybackState();
        if (playbackState != null) {
            mediaController.getTransportControls().skipToNext();
        }
    }

    private void pauseSong() {
        PlaybackState playbackState = mediaController.getPlaybackState();
        if (playbackState != null) {
            if (playbackState.getState() == PlaybackState.STATE_PLAYING) {
                mediaController.getTransportControls().pause();
            } else {
                mediaController.getTransportControls().play();
            }
        }
    }

    public void seekForward() {
        PlaybackState playbackState = mediaController.getPlaybackState();
        if (playbackState != null) {
            long currentPosition = playbackState.getPosition();
            long seekPosition = currentPosition + 10000; // 10 seconds in milliseconds
            mediaController.getTransportControls().seekTo(seekPosition);
        }
    }

    public void setVolume(Float percent) {
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int volume = (int) (maxVolume*percent);
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
    }

    private void updateMediaSessions() {
        List<MediaController> mediaControllers = mediaSessionManager.getActiveSessions(new ComponentName(this, NotificationListener.class));
        Log.i(TAG, "found " + mediaControllers.size() + " controllers");

        for (MediaController mediaController : mediaControllers) {
            this.mediaController = mediaController;
            break;
        }
    }

    private Notification createNotification() {
        // Create a notification channel if targeting API level 26+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Bluetooth Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }

        // Create the notification
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Bluetooth Media Controller")
                .setContentText("Running in the background.")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        // Set the notification click action to open the MainActivity
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        builder.setContentIntent(pendingIntent);

        return builder.build();
    }
}
