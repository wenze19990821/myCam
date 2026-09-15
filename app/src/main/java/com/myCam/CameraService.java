package com.myCam;

import static android.Manifest.permission.CAMERA;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class CameraService extends Service {
    private static final String ACTION_START = "ACTION_START";
    private static final String ACTION_STOP = "ACTION_STOP";

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private MediaRecorder mediaRecorder;
    private volatile boolean isRecording = false;
    private volatile boolean isRecorderReady = false;
    private PowerManager.WakeLock wakeLock;

    private OrientationEventListener orientationEventListener;
    private volatile int deviceRotation = -1;
    private File tempVideoFile; // 替换原有的 Uri/pfd，起录直接写本地私有缓存
    private final CountDownLatch orientationLatch = new CountDownLatch(1);

    @Override
    public void onCreate() {
        super.onCreate();
        backgroundThread = new HandlerThread("CameraBackgroundWorker");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        PowerManager powerManager = getSystemService(PowerManager.class);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyCam::RecordingWakeLock");

        orientationEventListener = new OrientationEventListener(this, SensorManager.SENSOR_DELAY_UI) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation != ORIENTATION_UNKNOWN) {
                    deviceRotation = (((orientation + 45) % 360) / 90) * 90;
                    orientationLatch.countDown();
                }
            }
        };
        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            if (!isRecording) {
                startForegroundWithNotification();
                backgroundHandler.post(this::startRecordingPipeline);
            }
        } else {
            backgroundHandler.post(() -> {
                stopRecording();
                stopSelf();
            });
        }
        return START_NOT_STICKY;
    }

    private void startForegroundWithNotification() {
        NotificationChannel channel = new NotificationChannel("MyCam_Channel", "MyCam Recording", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0,
                new Intent(this, CameraService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(this, "MyCam_Channel")
                .setContentTitle("MyCam")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(stopPendingIntent)
                .setOngoing(true)
                .build();

        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
    }

    private void startRecordingPipeline() {
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            stopSelf();
            return;
        }

        CameraManager manager = getSystemService(CameraManager.class);
        try {
            String[] cameraIds = manager.getCameraIdList();
            if (cameraIds.length == 0 || checkSelfPermission(CAMERA) != PackageManager.PERMISSION_GRANTED) {
                stopSelf();
                return;
            }

            manager.openCamera(cameraIds[0], new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    if (isRecorderReady) {
                        createCaptureSessionWithRecorder();
                    }
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    closeCameraResources();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    closeCameraResources();
                    stopRecording();
                    stopSelf();
                }
            }, backgroundHandler);

            if (deviceRotation == -1) {
                try {
                    orientationLatch.await(250, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {}
            }

            int currentRotation = (deviceRotation != -1) ? deviceRotation : 0;
            setupMediaRecorder(currentRotation);
            isRecorderReady = true;

            if (cameraDevice != null) {
                createCaptureSessionWithRecorder();
            }

        } catch (CameraAccessException | IOException e) {
            e.printStackTrace();
            closeCameraResources();
            stopRecording();
            stopSelf();
        }
    }

    private void createCaptureSessionWithRecorder() {
        if (cameraDevice == null || !isRecorderReady || isRecording) {
            return;
        }

        try {
            Surface recorderSurface = mediaRecorder.getSurface();

            CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            captureRequestBuilder.addTarget(recorderSurface);

            // 关闭视频防抖算法开销
            captureRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);

            // 【配置硬件级 OIS】：0 算法算力损耗，纯机械马达减震
            try {
                CameraManager manager = getSystemService(CameraManager.class);
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
                int[] availableOis = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
                if (availableOis != null) {
                    for (int mode : availableOis) {
                        if (mode == CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                            captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}

            // 显式指定连续自动对焦
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);

            SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    Collections.singletonList(new OutputConfiguration(recorderSurface)),
                    backgroundHandler::post,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            cameraCaptureSession = session;
                            try {
                                // 【时序修复】：先启动 MediaRecorder，再请求出帧流，防止首帧丢失与底层时序竞争
                                mediaRecorder.start();
                                session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                                isRecording = true;
                                
                                // 【WakeLock 修复】：解除 15 分钟硬编码超时限制
                                if (wakeLock != null && !wakeLock.isHeld()) {
                                    wakeLock.acquire();
                                }
                                runOnUiThread(() -> Toast.makeText(CameraService.this, "START", Toast.LENGTH_SHORT).show());
                            } catch (Exception e) {
                                e.printStackTrace();
                                stopSelf();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            runOnUiThread(() -> Toast.makeText(CameraService.this, "Failed", Toast.LENGTH_SHORT).show());
                            stopSelf();
                        }
                    }
            );
            cameraDevice.createCaptureSession(sessionConfig);

        } catch (CameraAccessException e) {
            e.printStackTrace();
            stopSelf();
        }
    }

    private void setupMediaRecorder(int rotation) throws IOException {
        // 【I/O 优化】：直写应用私有临时文件，消除启动时 MediaStore 跨进程 Binder IPC 与 SQLite 写入延迟
        tempVideoFile = new File(getCacheDir(), "RAW_REC_" + System.currentTimeMillis() + ".mp4");

        mediaRecorder = new MediaRecorder(this);

        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

        mediaRecorder.setOutputFile(tempVideoFile.getAbsolutePath());

        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setVideoEncodingBitRate(3500000);
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoSize(1920, 1080);

        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setAudioEncodingBitRate(96000);
        mediaRecorder.setAudioSamplingRate(48000);
        mediaRecorder.setAudioChannels(1);

        if (orientationEventListener != null) {
            orientationEventListener.disable();
            orientationEventListener = null;
        }

        int sensorOrientation = 90;
        boolean isFrontCamera = false;
        try {
            CameraManager manager = getSystemService(CameraManager.class);
            String[] cameraIds = manager.getCameraIdList();
            if (cameraIds.length > 0) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraIds[0]);
                Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (orientation != null) sensorOrientation = orientation;
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null) isFrontCamera = (facing == CameraCharacteristics.LENS_FACING_FRONT);
            }
        } catch (Exception ignored) {}

        int hint = isFrontCamera ? (sensorOrientation - rotation + 360) % 360 : (sensorOrientation + rotation) % 360;
        mediaRecorder.setOrientationHint(hint);
        mediaRecorder.prepare();
    }

    private void stopRecording() {
        boolean wasRecording = isRecording;
        isRecording = false;
        isRecorderReady = false;

        try {
            if (cameraCaptureSession != null) {
                cameraCaptureSession.stopRepeating();
                cameraCaptureSession.abortCaptures();
            }
        } catch (Exception ignored) {}

        if (wasRecording) {
            try {
                if (mediaRecorder != null) {
                    mediaRecorder.stop();
                }
            } catch (RuntimeException ignored) {}
        }

        if (mediaRecorder != null) {
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;
        }

        // 【I/O 优化】：录制停止后，在后台任务中将临时视频迁移发布至 MediaStore 相册
        if (tempVideoFile != null && tempVideoFile.exists()) {
            if (wasRecording) {
                exportTempVideoToMediaStore(tempVideoFile);
            } else {
                tempVideoFile.delete();
            }
            tempVideoFile = null;
        }

        closeCameraResources();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wasRecording) {
            runOnUiThread(() -> Toast.makeText(this, "FINISH", Toast.LENGTH_SHORT).show());
        }
    }

    private void exportTempVideoToMediaStore(File file) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "EVIDENCE_" + timestamp + ".mp4";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/MyCam");

        Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri != null) {
            try (InputStream is = new FileInputStream(file);
                 OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os != null) {
                    byte[] buffer = new byte[64 * 1024];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                    os.flush();
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                file.delete();
            }
        }
    }

    private void closeCameraResources() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    private void runOnUiThread(Runnable action) {
        new Handler(getMainLooper()).post(action);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (orientationEventListener != null) {
            orientationEventListener.disable();
            orientationEventListener = null;
        }
        stopRecording();
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException ignored) {}
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
