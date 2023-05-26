package com.example.mediacontroller;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MediaController";
    private static final String DEVICE_NAME = "HC-06";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothDevice targetDevice;
    private BluetoothSocket bluetoothSocket;

    private AudioManager audioManager;
    private MediaController mediaController;
    private List<MediaController> mediaControllers;
    private MediaSessionManager mediaSessionManager;

    TextView centralText;

    @RequiresApi(api = Build.VERSION_CODES.S)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialise central text
        centralText = findViewById(R.id.centralText);

        // Get audioManager (might not be used)
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        // Security requirements mandate I have to ask for "Notification" permission to manage media control???
        Intent i = new Intent(getApplicationContext(), NotificationListener.class);
        startService(i);

        // Gets all media sessions on the phone.
        mediaSessionManager = (MediaSessionManager) this.getSystemService(Context.MEDIA_SESSION_SERVICE);
        updateMediaSessions();

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Check if Bluetooth is enabled
        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth and try again", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Get a list of paired devices and ask for permissions to do so.
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(MainActivity.this, new String[] { android.Manifest.permission.BLUETOOTH_CONNECT }, 1);
        }
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();

        // Check if the target device is paired
        for (BluetoothDevice device : pairedDevices) {
            if (device.getName().contains(DEVICE_NAME)) {
                targetDevice = device;
                break;
            }
        }

        if (targetDevice == null) {
            Toast.makeText(this, "Target device not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Connect to the target device
        try {
            bluetoothSocket = targetDevice.createRfcommSocketToServiceRecord(MY_UUID);
            bluetoothSocket.connect();
            Toast.makeText(this, "Connected to " + DEVICE_NAME, Toast.LENGTH_SHORT).show();
            centralText.setText("Connected to " + DEVICE_NAME);
            loop();

        } catch (IOException e) {
            Log.e(TAG, "Error connecting to Bluetooth device: " + e.getMessage());
            Toast.makeText(this, "Failed to connect to " + DEVICE_NAME, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateMediaSessions() {
        mediaControllers = mediaSessionManager.getActiveSessions(new ComponentName(this, NotificationListener.class));
        Log.i(TAG, "found " + mediaControllers.size() + " controllers");

        for (MediaController mediaController : mediaControllers) {
            this.mediaController = mediaController;
            break;
        }
    }

    private void loop() {
        Thread thread = new Thread(() -> {
            InputStream inputStream;
            byte[] buffer = new byte[1024];
            int bytesRead;

            try {
                inputStream = bluetoothSocket.getInputStream();

                while (true) {
                    // Read data from the input stream
                    bytesRead = inputStream.read(buffer);
                    String receivedMessage = new String(buffer, 0, bytesRead);

                    // Update media sessions every time we receive a command.
                    updateMediaSessions();
                    // Check the code sent by the Arduino and do the appropiate action
                    if (receivedMessage.equals("P!")) {
                        pauseSong();
                    } else if (receivedMessage.equals("F!")) {
                        seekForward();
                    } else if (receivedMessage.equals("N!")) {
                        nextSong();
                    } else if (receivedMessage.equals("B!")) {
                        previousSong();
                    }

                    if (receivedMessage.startsWith("!")) {
                        // Volume is of format !(VOLUME), where VOLUME is of values from 0 to 99
                        float volume = Float.parseFloat(receivedMessage.substring(1, 3));
                        setVolume(volume / 100);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Error reading from Bluetooth socket: " + e.getMessage());
            }
        });
        thread.start();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Close the Bluetooth socket
        if (bluetoothSocket != null) {
            try {
                bluetoothSocket.close();
                Toast.makeText(this, "Disconnected from " + DEVICE_NAME, Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Log.e(TAG, "Error closing Bluetooth socket: " + e.getMessage());
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            // Checking whether user granted the permission or not.
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Showing the toast message
                Toast.makeText(MainActivity.this, "Bluetooth Granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(MainActivity.this, "Bluetooth Denied", Toast.LENGTH_SHORT).show();
            }
        }
    }
}