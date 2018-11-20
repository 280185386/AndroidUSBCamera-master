package com.serenegiant.usb.common;

import android.annotation.TargetApi;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.usb.UsbDevice;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usb.encoder.MediaEncoder;
import com.serenegiant.usb.encoder.MediaMuxerWrapper;
import com.serenegiant.usb.encoder.MediaSurfaceEncoder;
import com.serenegiant.usb.encoder.MediaVideoBufferEncoder;
import com.serenegiant.usb.encoder.MediaVideoEncoder;
import com.serenegiant.usb.encoder.RecordParams;
import com.serenegiant.usb.encoder.biz.AACEncodeConsumer;
import com.serenegiant.usb.encoder.biz.H264EncodeConsumer;
import com.serenegiant.usb.encoder.biz.Mp4MediaMuxer;
import com.serenegiant.usb.widget.CameraViewInterface;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import static com.jiangdg.usbcamera.UVCCameraHelper.strSDCardPathName;


public abstract class AbstractUVCCameraHandler extends Handler {

    private static final boolean DEBUG = true;    // TODO set false on release
    private static final String TAG = "AbsUVCCameraHandler";


    // อินเทอร์เฟซการติดต่อกลับภายนอก
    public interface CameraCallback {
        public void onOpen();

        public void onClose();

        public void onStartPreview();

        public void onStopPreview();

        public void onStartRecording();

        public void onStopRecording();

        public void onError(final Exception e);
    }

    public static OnEncodeResultListener mListener;
    public static OnPreViewResultListener mPreviewListener;
    public static OnCaptureListener mCaptureListener;

    public interface OnEncodeResultListener {
        void onEncodeResult(byte[] data, int offset, int length, long timestamp, int type);

        void onRecordResult(String videoPath);
    }

    public interface OnPreViewResultListener {
        void onPreviewResult(byte[] data);
    }

    public interface OnCaptureListener {
        void onCaptureResult(String picPath);
    }

    private static final int MSG_OPEN = 0;
    private static final int MSG_CLOSE = 1;
    private static final int MSG_PREVIEW_START = 2;
    private static final int MSG_PREVIEW_STOP = 3;
    private static final int MSG_CAPTURE_STILL = 4;
    private static final int MSG_CAPTURE_START = 5;
    private static final int MSG_CAPTURE_STOP = 6;
    private static final int MSG_MEDIA_UPDATE = 7;
    private static final int MSG_RELEASE = 9;
    private static final int MSG_CAMERA_FOUCS = 10;


    private final WeakReference<CameraThread> mWeakThread;
    private volatile boolean mReleased;
    protected static boolean isCaptureStill;

    protected AbstractUVCCameraHandler(final CameraThread thread) {
        mWeakThread = new WeakReference<CameraThread>(thread);
    }

    public int getWidth() {
        final CameraThread thread = mWeakThread.get();
        return thread != null ? thread.getWidth() : 0;
    }

    public int getHeight() {
        final CameraThread thread = mWeakThread.get();
        return thread != null ? thread.getHeight() : 0;
    }

    public boolean isOpened() {
        final CameraThread thread = mWeakThread.get();
        return thread != null && thread.isCameraOpened();
    }

    public boolean isPreviewing() {
        final CameraThread thread = mWeakThread.get();
        return thread != null && thread.isPreviewing();
    }

    public boolean isRecording() {
        final CameraThread thread = mWeakThread.get();
        return thread != null && thread.isRecording();
    }


    public boolean isEqual(final UsbDevice device) {
        final CameraThread thread = mWeakThread.get();
        return (thread != null) && thread.isEqual(device);
    }

    protected boolean isCameraThread() {
        final CameraThread thread = mWeakThread.get();
        return thread != null && (thread.getId() == Thread.currentThread().getId());
    }

    protected boolean isReleased() {
        final CameraThread thread = mWeakThread.get();
        return mReleased || (thread == null);
    }

    protected void checkReleased() {
        if (isReleased()) {
            throw new IllegalStateException("already released");
        }
    }

    public void open(final USBMonitor.UsbControlBlock ctrlBlock) {
        checkReleased();
        sendMessage(obtainMessage(MSG_OPEN, ctrlBlock));
    }

    public void close() {
        if (DEBUG) Log.v(TAG, "close:");
        if (isOpened()) {
            stopPreview();
            sendEmptyMessage(MSG_CLOSE);
        }
        if (DEBUG) Log.v(TAG, "close:finished");
    }

    // Image resolution
    public void resize(final int width, final int height) {
        checkReleased();
        throw new UnsupportedOperationException("does not support now");
    }

    // Open Camera preview
    public void startPreview(final Object surface) {
        checkReleased();
        if (!((surface instanceof SurfaceHolder) || (surface instanceof Surface) || (surface instanceof SurfaceTexture))) {
            throw new IllegalArgumentException("surface should be one of SurfaceHolder, Surface or SurfaceTexture: " + surface);
        }
        sendMessage(obtainMessage(MSG_PREVIEW_START, surface));
    }

    public void setOnPreViewResultListener(OnPreViewResultListener listener) {
        AbstractUVCCameraHandler.mPreviewListener = listener;
    }

    // Close Camera Preview
    public void stopPreview() {
        if (DEBUG) Log.v(TAG, "stopPreview:");
        removeMessages(MSG_PREVIEW_START);
        if (isRecording()) {
            stopRecording();
        }
        if (isPreviewing()) {
            final CameraThread thread = mWeakThread.get();
            if (thread == null) return;
            synchronized (thread.mSync) {
                sendEmptyMessage(MSG_PREVIEW_STOP);
                if (!isCameraThread()) {
                    try {
                        thread.mSync.wait();
                    } catch (final InterruptedException e) {
                    }
                }
            }
        }
        if (DEBUG) Log.v(TAG, "stopPreview:finished");
    }

    // Take Photos
    public void captureStill(final String path, AbstractUVCCameraHandler.OnCaptureListener listener) {
        AbstractUVCCameraHandler.mCaptureListener = listener;
        checkReleased();
        sendMessage(obtainMessage(MSG_CAPTURE_STILL, path));
        isCaptureStill = true;
    }

    // Start recording
    public void startRecording(final RecordParams params, OnEncodeResultListener listener) {
        AbstractUVCCameraHandler.mListener = listener;
        checkReleased();
        sendMessage(obtainMessage(MSG_CAPTURE_START, params));
    }

    // Stop recording
    public void stopRecording() {
        sendEmptyMessage(MSG_CAPTURE_STOP);
    }

    public void startCameraFoucs() {
        sendEmptyMessage(MSG_CAMERA_FOUCS);
    }

    public List<Size> getSupportedPreviewSizes() {
        return mWeakThread.get().getSupportedSizes();
    }


    public void release() {
        mReleased = true;
        close();
        sendEmptyMessage(MSG_RELEASE);
    }


    protected void updateMedia(final String path) {
        sendMessage(obtainMessage(MSG_MEDIA_UPDATE, path));
    }

    public boolean checkSupportFlag(final long flag) {
        checkReleased();
        final CameraThread thread = mWeakThread.get();
        return thread != null && thread.mUVCCamera != null && thread.mUVCCamera.checkSupportFlag(flag);
    }

    public int getValue(final int flag) {
        checkReleased();
        final CameraThread thread = mWeakThread.get();
        final UVCCamera camera = thread != null ? thread.mUVCCamera : null;
        if (camera != null) {
            if (flag == UVCCamera.PU_BRIGHTNESS) {
                return camera.getBrightness();
            } else if (flag == UVCCamera.PU_CONTRAST) {
                return camera.getContrast();
            }
        }
        throw new IllegalStateException();
    }

    public int setValue(final int flag, final int value) {
        checkReleased();
        final CameraThread thread = mWeakThread.get();
        final UVCCamera camera = thread != null ? thread.mUVCCamera : null;
        if (camera != null) {
            if (flag == UVCCamera.PU_BRIGHTNESS) {
                camera.setBrightness(value);
                return camera.getBrightness();
            } else if (flag == UVCCamera.PU_CONTRAST) {
                camera.setContrast(value);
                return camera.getContrast();
            }
        }
        throw new IllegalStateException();
    }

    public int resetValue(final int flag) {
        checkReleased();
        final CameraThread thread = mWeakThread.get();
        final UVCCamera camera = thread != null ? thread.mUVCCamera : null;
        if (camera != null) {
            if (flag == UVCCamera.PU_BRIGHTNESS) {
                camera.resetBrightness();
                return camera.getBrightness();
            } else if (flag == UVCCamera.PU_CONTRAST) {
                camera.resetContrast();
                return camera.getContrast();
            }
        }
        throw new IllegalStateException();
    }

    @Override
    public void handleMessage(final Message msg) {
        final CameraThread thread = mWeakThread.get();
        if (thread == null) return;
        switch (msg.what) {
            case MSG_OPEN:
                thread.handleOpen((USBMonitor.UsbControlBlock) msg.obj);
                break;
            case MSG_CLOSE:
                thread.handleClose();
                break;
            case MSG_PREVIEW_START:
                thread.handleStartPreview(msg.obj);
                break;
            case MSG_PREVIEW_STOP:
                thread.handleStopPreview();
                break;
            case MSG_CAPTURE_STILL:
                thread.handleStillPicture((String) msg.obj);
                break;
            case MSG_CAPTURE_START:
                thread.handleStartPusher((RecordParams) msg.obj);
                break;
            case MSG_CAPTURE_STOP:
                thread.handleStopPusher();
                break;
            case MSG_MEDIA_UPDATE:
                thread.handleUpdateMedia((String) msg.obj);
                break;
            case MSG_RELEASE:
                thread.handleRelease();
                break;
            // 自动对焦
            case MSG_CAMERA_FOUCS:
                thread.handleCameraFoucs();
                break;
            default:
                throw new RuntimeException("unsupported message:what=" + msg.what);
        }
    }

    public static final class CameraThread extends Thread {
        private static final String TAG_THREAD = "CameraThread";
        private final Object mSync = new Object();
        private final Class<? extends AbstractUVCCameraHandler> mHandlerClass;
        private final WeakReference<Activity> mWeakParent;
        private final WeakReference<CameraViewInterface> mWeakCameraView;
        private final int mEncoderType;
        private final Set<CameraCallback> mCallbacks = new CopyOnWriteArraySet<CameraCallback>();
        private int mWidth, mHeight, mPreviewMode;
        private float mBandwidthFactor;
        private boolean mIsPreviewing;
        private boolean mIsRecording;

        private AbstractUVCCameraHandler mHandler;
        private UVCCamera mUVCCamera;

        private MediaVideoBufferEncoder mVideoEncoder;
        private Mp4MediaMuxer mMuxer;
        private boolean isPushing;
        private String videoPath;


        CameraThread(final Class<? extends AbstractUVCCameraHandler> clazz,
                     final Activity parent, final CameraViewInterface cameraView,
                     final int encoderType, final int width, final int height, final int format,
                     final float bandwidthFactor) {

            super("CameraThread");
            mHandlerClass = clazz;
            mEncoderType = encoderType;
            mWidth = width;
            mHeight = height;
            mPreviewMode = format;
            mBandwidthFactor = bandwidthFactor;
            mWeakParent = new WeakReference<>(parent);
            mWeakCameraView = new WeakReference<>(cameraView);
        }

        @Override
        protected void finalize() throws Throwable {
            Log.i(TAG, "CameraThread#finalize");
            super.finalize();
        }

        public AbstractUVCCameraHandler getHandler() {
            if (DEBUG) Log.v(TAG_THREAD, "getHandler:");
            synchronized (mSync) {
                if (mHandler == null)
                    try {
                        mSync.wait();
                    } catch (final InterruptedException e) {
                    }
            }
            return mHandler;
        }

        public int getWidth() {
            synchronized (mSync) {
                return mWidth;
            }
        }

        public int getHeight() {
            synchronized (mSync) {
                return mHeight;
            }
        }

        public boolean isCameraOpened() {
            synchronized (mSync) {
                return mUVCCamera != null;
            }
        }

        public boolean isPreviewing() {
            synchronized (mSync) {
                return mUVCCamera != null && mIsPreviewing;
            }
        }

        public boolean isRecording() {
            synchronized (mSync) {
                return (mUVCCamera != null) && (mH264Consumer != null);
            }
        }


        public boolean isEqual(final UsbDevice device) {
            return (mUVCCamera != null) && (mUVCCamera.getDevice() != null) && mUVCCamera.getDevice().equals(device);
        }

        public void handleOpen(final USBMonitor.UsbControlBlock ctrlBlock) {
            if (DEBUG) Log.v(TAG_THREAD, "handleOpen:");
            handleClose();
            try {
                final UVCCamera camera = new UVCCamera();
                camera.open(ctrlBlock);
                synchronized (mSync) {
                    mUVCCamera = camera;
                }
                callOnOpen();
            } catch (final Exception e) {
                callOnError(e);
            }
            if (DEBUG)
                Log.i(TAG, "supportedSize:" + (mUVCCamera != null ? mUVCCamera.getSupportedSize() : null));
        }

        public void handleClose() {
            if (DEBUG) Log.v(TAG_THREAD, "handleClose:");
            handleStopPusher();
            final UVCCamera camera;
            synchronized (mSync) {
                camera = mUVCCamera;
                mUVCCamera = null;
            }
            if (camera != null) {
                camera.stopPreview();
                camera.destroy();
                callOnClose();
            }
        }

        // Camera PreView Page Main
        public void handleStartPreview(final Object surface) {
            if (DEBUG) Log.v(TAG_THREAD, "handleStartPreview:");
            if ((mUVCCamera == null) || mIsPreviewing) return;
            try {
                mUVCCamera.setPreviewSize(mWidth, mHeight, 1, 31, mPreviewMode, mBandwidthFactor);
                mUVCCamera.setFrameCallback(mIFrameCallback, UVCCamera.PIXEL_FORMAT_YUV420SP);
            } catch (final IllegalArgumentException e) {
                try {
                    // fallback to YUV mode
                    mUVCCamera.setPreviewSize(mWidth, mHeight, 1, 31, UVCCamera.DEFAULT_PREVIEW_MODE, mBandwidthFactor);
                } catch (final IllegalArgumentException e1) {
                    callOnError(e1);
                    return;
                }
            }
            if (surface instanceof SurfaceHolder) {
                mUVCCamera.setPreviewDisplay((SurfaceHolder) surface);
            }
            if (surface instanceof Surface) {
                mUVCCamera.setPreviewDisplay((Surface) surface);
            } else {
                mUVCCamera.setPreviewTexture((SurfaceTexture) surface);
            }
            mUVCCamera.startPreview();
            mUVCCamera.updateCameraParams();
            synchronized (mSync) {
                mIsPreviewing = true;
            }
            callOnStartPreview();
        }

        public void handleStopPreview() {
            if (DEBUG) Log.v(TAG_THREAD, "handleStopPreview:");
            if (mIsPreviewing) {
                if (mUVCCamera != null) {
                    mUVCCamera.stopPreview();
                    mUVCCamera.setFrameCallback(null, 0);
                }
                synchronized (mSync) {
                    mIsPreviewing = false;
                    mSync.notifyAll();
                }
                callOnStopPreview();
            }
            if (DEBUG) Log.v(TAG_THREAD, "handleStopPreview:finished");
        }


        private AACEncodeConsumer mAacConsumer;
        private H264EncodeConsumer mH264Consumer;

        public void handleStartPusher(RecordParams params) {
            if ((mUVCCamera == null) || (mH264Consumer != null))
                return;

            // Initialize the mixer
            if (params != null) {
                videoPath = params.getRecordPath();
                mMuxer = new Mp4MediaMuxer(params.getRecordPath(),
                        params.getRecordDuration() * 60 * 1000, params.isVoiceClose());
            }
            // Start video encoding thread
            startVideoRecord();
            // Start audio encoding thread
            if (params != null && !params.isVoiceClose()) {
                startAudioRecord();
            }
            callOnStartRecording();
        }


        public void handleStopPusher() {
            // Stop the mixer
            if (mMuxer != null) {
                mMuxer.release();
                mMuxer = null;
                Log.i(TAG, TAG + "---->Stop local recording");
            }
            // Stop audio and video encoding thread
            stopAudioRecord();
            stopVideoRecord();

            mWeakCameraView.get().setVideoEncoder(null);
            // you should not wait here
            callOnStopRecording();
            // Return path
            if (mListener != null) {
                mListener.onRecordResult(videoPath + ".mp4");
            }
        }

        private void startVideoRecord() {
            mH264Consumer = new H264EncodeConsumer(getWidth(), getHeight());
            mH264Consumer.setOnH264EncodeResultListener(new H264EncodeConsumer.OnH264EncodeResultListener() {
                @Override
                public void onEncodeResult(byte[] data, int offset, int length, long timestamp) {
                    if (mListener != null) {
                        mListener.onEncodeResult(data, offset, length, timestamp, 1);
                    }
                }
            });
            mH264Consumer.start();
            // Add mixer
            if (mMuxer != null) {
                if (mH264Consumer != null) {
                    mH264Consumer.setTmpuMuxer(mMuxer);
                }
            }
        }

        private void stopVideoRecord() {
            if (mH264Consumer != null) {
                mH264Consumer.exit();
                mH264Consumer.setTmpuMuxer(null);
                try {
                    Thread t2 = mH264Consumer;
                    mH264Consumer = null;
                    if (t2 != null) {
                        t2.interrupt();
                        t2.join();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        private void startAudioRecord() {
            mAacConsumer = new AACEncodeConsumer();
            mAacConsumer.setOnAACEncodeResultListener(new AACEncodeConsumer.OnAACEncodeResultListener() {
                @Override
                public void onEncodeResult(byte[] data, int offset, int length, long timestamp) {
                    if (mListener != null) {
                        mListener.onEncodeResult(data, offset, length, timestamp, 0);
                    }
                }
            });
            mAacConsumer.start();
            // Add mixer
            if (mMuxer != null) {
                if (mAacConsumer != null) {
                    mAacConsumer.setTmpuMuxer(mMuxer);
                }
            }
        }

        private void stopAudioRecord() {
            if (mAacConsumer != null) {
                mAacConsumer.exit();
                mAacConsumer.setTmpuMuxer(null);
                try {
                    Thread t1 = mAacConsumer;
                    mAacConsumer = null;
                    if (t1 != null) {
                        t1.interrupt();
                        t1.join();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

        }

        private String picPath = null;

        public void handleStillPicture(String picPath) {
            this.picPath = picPath;
        }

        private final IFrameCallback mIFrameCallback = new IFrameCallback() {
            @Override
            public void onFrame(final ByteBuffer frame) {

                int len = frame.capacity();
                final byte[] yuv = new byte[len];
                frame.get(yuv);
                // nv21 yuv data callback
                if (mPreviewListener != null) {
                    mPreviewListener.onPreviewResult(yuv);
                }
                // Take Photos
                if (isCaptureStill && !TextUtils.isEmpty(picPath)) {
                    isCaptureStill = false;
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            saveYuv2Jpeg(picPath, yuv);
                        }
                    }).start();

                    isCaptureStill = false;
                }
                // video
                if (mH264Consumer != null) {
                    // Modify resolution parameters
                    mH264Consumer.setRawYuv(yuv, mWidth, mHeight);
                }
            }
        };

        // Modify resolution parameters Photos
        private void saveYuv2Jpeg(String path, byte[] data) {
            YuvImage yuvImage = new YuvImage(data, ImageFormat.NV21, mWidth, mHeight, null);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length);
            boolean result = yuvImage.compressToJpeg(new Rect(0, 0, mWidth, mHeight), 100, bos);
            if (result) {
                byte[] buffer = bos.toByteArray();
                Bitmap bmp = BitmapFactory.decodeByteArray(buffer, 0, buffer.length);

                File file = new File(path);
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(file);
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }
                bmp.compress(Bitmap.CompressFormat.JPEG, 100, fos);
                try {
                    fos.flush();
                    fos.close();
                    bmp.recycle();
                    if (mCaptureListener != null) {
                        mCaptureListener.onCaptureResult(path);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            try {
                bos.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
        public void handleUpdateMedia(final String path) {
            if (DEBUG) Log.v(TAG_THREAD, "handleUpdateMedia:path=" + path);
            final Activity parent = mWeakParent.get();
            final boolean released = (mHandler == null) || mHandler.mReleased;
            if (parent != null && parent.getApplicationContext() != null) {
                try {
                    if (DEBUG) Log.i(TAG, "MediaScannerConnection#scanFile");
                    MediaScannerConnection.scanFile(parent.getApplicationContext(), new String[]{path}, null, null);
                } catch (final Exception e) {
                    Log.e(TAG, "handleUpdateMedia:", e);
                }
                if (released || parent.isDestroyed())
                    handleRelease();
            } else {
                Log.w(TAG, "MainActivity already destroyed");
                handleRelease();
            }
        }

        public void handleRelease() {
            if (DEBUG) Log.v(TAG_THREAD, "handleRelease:mIsRecording=" + mIsRecording);
            handleClose();
            mCallbacks.clear();
            if (!mIsRecording) {
                mHandler.mReleased = true;
                Looper.myLooper().quit();
            }
            if (DEBUG) Log.v(TAG_THREAD, "handleRelease:finished");
        }

        // auto focus
        public void handleCameraFoucs() {
            if (DEBUG) Log.v(TAG_THREAD, "handleStartPreview:");
            if ((mUVCCamera == null) || !mIsPreviewing)
                return;
            mUVCCamera.setAutoFocus(true);
        }

        // Get supported resolutions
        public List<Size> getSupportedSizes() {
            if ((mUVCCamera == null) || !mIsPreviewing)
                return null;
            return mUVCCamera.getSupportedSizeList();
        }

        @Override
        public void run() {
            Looper.prepare();
            AbstractUVCCameraHandler handler = null;
            try {
                final Constructor<? extends AbstractUVCCameraHandler> constructor = mHandlerClass.getDeclaredConstructor(CameraThread.class);
                handler = constructor.newInstance(this);
            } catch (final NoSuchMethodException e) {
                Log.w(TAG, e);
            } catch (final IllegalAccessException e) {
                Log.w(TAG, e);
            } catch (final InstantiationException e) {
                Log.w(TAG, e);
            } catch (final InvocationTargetException e) {
                Log.w(TAG, e);
            }
            if (handler != null) {
                synchronized (mSync) {
                    mHandler = handler;
                    mSync.notifyAll();
                }
                Looper.loop();

                if (mHandler != null) {
                    mHandler.mReleased = true;
                }
            }
            mCallbacks.clear();
            synchronized (mSync) {
                mHandler = null;
                mSync.notifyAll();
            }
        }

        private void callOnOpen() {
            for (final CameraCallback callback : mCallbacks) {
                try {
                    callback.onOpen();
                } catch (final Exception e) {
                    mCallbacks.remove(callback);
                    Log.w(TAG, e);
                }
            }
        }

        private void callOnClose() {
            for (final CameraCallback callback : mCallbacks) {
                try {
                    callback.onClose();
                } catch (final Exception e) {
                    mCallbacks.remove(callback);
                    Log.w(TAG, e);
                }
            }
        }

        private void callOnStartPreview() {
            for (final CameraCallback callback : mCallbacks) {
                try {
                    callback.onStartPreview();
                } catch (final Exception e) {
                    mCallbacks.remove(callback);
                    Log.w(TAG, e);
                }
            }
        }

        private void callOnStopPreview() {
            for (final CameraCallback callback : mCallbacks) {
                try {
                    callback.onStopPreview();
                } catch (final Exception e) {
                    mCallbacks.remove(callback);
                    Log.w(TAG, e);
                }
            }
        }

        private void callOnStartRecording() {
            for (final CameraCallback callback : mCallbacks) {
                try {
                    callback.onStartRecording();
                } catch (final Exception e) {
                    mCallbacks.remove(callback);
                    Log.w(TAG, e);
                }
            }
        }

        private void callOnStopRecording() {
            for (final CameraCallback callback : mCallbacks) {
                try {
                    callback.onStopRecording();
                } catch (final Exception e) {
                    mCallbacks.remove(callback);
                    Log.w(TAG, e);
                }
            }
        }

        private void callOnError(final Exception e) {
            for (final CameraCallback callback : mCallbacks) {
                try {
                    callback.onError(e);
                } catch (final Exception e1) {
                    mCallbacks.remove(callback);
                    Log.w(TAG, e);
                }
            }
        }
    }
}
