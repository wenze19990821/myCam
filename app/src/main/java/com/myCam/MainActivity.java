package com.myCam;

import static android.Manifest.permission.CAMERA;
import static android.Manifest.permission.POST_NOTIFICATIONS;
import static android.Manifest.permission.RECORD_AUDIO;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        String[] requiredPerms = {CAMERA, RECORD_AUDIO, POST_NOTIFICATIONS};
        ArrayList<String> missingPerms = new ArrayList<>();
        for (String p : requiredPerms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                missingPerms.add(p);
            }
        }

        if (missingPerms.isEmpty()) {
            startRecordingService();
        } else {
            requestPermissions(missingPerms.toArray(new String[0]), 200);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permissions Required", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
        }
        startRecordingService();
    }

    private void startRecordingService() {
        Intent serviceIntent = new Intent(this, CameraService.class).setAction("ACTION_START");
        startForegroundService(serviceIntent);
        finish();
    }
}
