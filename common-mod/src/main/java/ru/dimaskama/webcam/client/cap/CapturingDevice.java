package ru.dimaskama.webcam.client.cap;

import net.minecraft.network.chat.Component;
import org.lwjgl.system.MemoryUtil;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;
import ru.dimaskama.webcam.client.config.Resolution;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.opencv.videoio.Videoio.*;

public class CapturingDevice extends Thread {

    private final AtomicBoolean closed = new AtomicBoolean();
    private final int deviceNumber;
    private final FrameConsumer frameConsumer;
    private final Mat tempMat;
    private final Mat tempMat2;
    private volatile Resolution resolution;
    private volatile int fps;
    private volatile int squareDimension;
    private volatile Throwable error;

    public CapturingDevice(int deviceNumber, FrameConsumer frameConsumer) {
        this.deviceNumber = deviceNumber;
        this.frameConsumer = frameConsumer;
        tempMat = new Mat();
        tempMat2 = new Mat();
        setDaemon(true);
        setName("CapturingDevice" + deviceNumber);
        setUncaughtExceptionHandler((t, e) -> error = e);
    }

    public void setResolution(Resolution resolution) {
        this.resolution = resolution;
    }

    public void setFps(int fps) {
        this.fps = fps;
    }

    public void setSquareDimension(int squareDimension) {
        this.squareDimension = (squareDimension >> 1) << 1;
    }

    public Throwable getError() {
        return error;
    }

    @Override
    public void run() {
        Resolution lastResolution = null;
        int realWidth = 0, realHeight = 0;
        int lastFps = 0;
        int realFps = 0;

        int apiPreference = org.opencv.videoio.Videoio.CAP_ANY;
        VideoCapture cap = new VideoCapture(deviceNumber, apiPreference);
        Mat mat = new Mat();
        int debugFrames = 0;
        try {
            if (!cap.isOpened()) {
                throw new DeviceException(Component.translatable("webcam.error.device_unavailable", deviceNumber));
            }
            ru.dimaskama.webcam.Webcam.getLogger().info("Camera opened with backend: " + cap.getBackendName());
            while (!closed.get()) {
                Resolution resolution = this.resolution;
                if (lastResolution != resolution) {
                    lastResolution = resolution;
                    debugFrames = 10; // Log first 10 frames after resolution change
                    boolean wSet = cap.set(CAP_PROP_FRAME_WIDTH, resolution.width);
                    boolean hSet = cap.set(CAP_PROP_FRAME_HEIGHT, resolution.height);
                    realWidth = (int) Math.round(cap.get(CAP_PROP_FRAME_WIDTH));
                    realHeight = (int) Math.round(cap.get(CAP_PROP_FRAME_HEIGHT));
                    ru.dimaskama.webcam.Webcam.getLogger().info("Set resolution: " + resolution.width + "x" + resolution.height + ". Set width success: " + wSet + ", set height success: " + hSet + ". Real resolution: " + realWidth + "x" + realHeight);
                }
                int fps = this.fps;
                if (lastFps != fps) {
                    lastFps = fps;
                    boolean fpsSet = cap.set(CAP_PROP_FPS, fps);
                    realFps = (int) Math.round(cap.get(CAP_PROP_FPS));
                    ru.dimaskama.webcam.Webcam.getLogger().info("Set FPS: " + fps + ". Success: " + fpsSet + ". Real FPS: " + realFps);
                }
                if (!cap.read(mat)) {
                    throw new DeviceException(Component.translatable("webcam.error.device_disconnected", deviceNumber));
                }
                
                // If frame is empty or black (DSHOW bug workaround for OBSBOT / OBS Virtual Camera)
                // We check after 3 frames to avoid false positives on stream start
                if (mat.channels() == 3 && debugFrames == 7) { 
                    org.opencv.core.Scalar s = org.opencv.core.Core.sumElems(mat);
                    if (s.val[0] == 0 && s.val[1] == 0 && s.val[2] == 0) {
                        ru.dimaskama.webcam.Webcam.getLogger().info("Detected black frame. Applying NV12 workaround...");
                        cap.set(CAP_PROP_FOURCC, org.opencv.videoio.VideoWriter.fourcc('N', 'V', '1', '2'));
                    }
                }
                
                if (debugFrames > 0) {
                    debugFrames--;
                    org.opencv.core.Scalar sum = org.opencv.core.Core.sumElems(mat);
                    ru.dimaskama.webcam.Webcam.getLogger().info("DEBUG FRAME: empty=" + mat.empty() + ", cols=" + mat.cols() + ", rows=" + mat.rows() + ", channels=" + mat.channels() + ", type=" + mat.type() + ", sum=[" + sum.val[0] + ", " + sum.val[1] + ", " + sum.val[2] + "]");
                }
                
                onFrame(realFps, realWidth, realHeight, mat);
            }
        } finally {
            cap.release();
            mat.release();
        }
    }

    private void onFrame(int fps, int width, int height, Mat frame) {
        int minDim;
        // Crop to square
        if (width != height) {
            minDim = Math.min(width, height);
            int startX = (width - minDim) >> 1;
            int startY = (height - minDim) >> 1;
            frame = new Mat(frame, new Rect(startX, startY, minDim, minDim));
        } else {
            minDim = width;
        }
        // Downscale
        int squareDimension = this.squareDimension;
        if (minDim != squareDimension) {
            Imgproc.resize(frame, tempMat, new Size(squareDimension, squareDimension));
            frame = tempMat;
        }
        Imgproc.cvtColor(frame, tempMat2, Imgproc.COLOR_BGR2RGBA);
        frame = tempMat2;
        int rgbaSize = squareDimension * squareDimension * 4;
        byte[] rgba = new byte[rgbaSize];
        MemoryUtil.memByteBuffer(frame.dataAddr(), rgbaSize).get(rgba);
        frameConsumer.consumeFrame(fps, squareDimension, squareDimension, rgba);
    }

    public boolean close() {
        if (closed.compareAndSet(false, true)) {
            tempMat.release();
            tempMat2.release();
            return true;
        }
        return false;
    }

    @FunctionalInterface
    public interface FrameConsumer {

        void consumeFrame(int fps, int width, int height, byte[] rgba);

    }

}
