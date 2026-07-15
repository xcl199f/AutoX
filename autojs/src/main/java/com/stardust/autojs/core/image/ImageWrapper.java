package com.stardust.autojs.core.image;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.Image;
import android.os.Build;

import com.stardust.autojs.core.opencv.Mat;
import com.stardust.autojs.core.opencv.OpenCVHelper;
import com.stardust.pio.UncheckedIOException;

import org.opencv.android.Utils;
import org.opencv.imgcodecs.Imgcodecs;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

/**
 * Created by Stardust on 2017/11/25.
 */
public class ImageWrapper {
    private static final List<WeakReference<ImageWrapper>> sAllImages = new ArrayList<>();
    private static final Object sLock = new Object();
    private static boolean sTrackingEnabled = false;

    /**
     * 启用图片追踪（在脚本开始时调用）
     */
    public static void enableTracking() {
        android.util.Log.d("ImageWrapper", "enableTracking called, sAllImages size before clear: " + sAllImages.size());
        synchronized (sLock) {
            sTrackingEnabled = true;
            android.util.Log.d("ImageWrapper", "sTrackingEnabled set to true");
            // 清除旧引用
            int size = sAllImages.size();
            sAllImages.clear();
            android.util.Log.d("ImageWrapper", "sAllImages cleared, size before: " + size);
        }
        android.util.Log.d("ImageWrapper", "enableTracking done");
    }

    /**
     * 禁用图片追踪（在脚本结束时调用）
     */
    public static void disableTracking() {
        synchronized (sLock) {
            sTrackingEnabled = false;
            // 不立即清除，给回收机会
        }
    }

    /**
     * 回收所有追踪的图片
     */
    public static void recycleAllTrackedImages() {
        synchronized (sLock) {
            // 先清理已经回收的弱引用
            sAllImages.removeIf(ref -> ref.get() == null);

            // 回收所有存活的图片
            for (WeakReference<ImageWrapper> ref : sAllImages) {
                ImageWrapper img = ref.get();
                if (img != null && img.isAlive()) {
                    try {
                        img.recycle();
                    } catch (Exception e) {
                        // 忽略单个图片的回收异常
                    }
                }
            }

            sAllImages.clear();
        }
    }

    /**
     * 获取当前追踪的图片数量
     */
    public static int getTrackedImageCount() {
        synchronized (sLock) {
            // 清理已回收的引用
            sAllImages.removeIf(ref -> ref.get() == null);
            return sAllImages.size();
        }
    }

    private Mat mMat;
    private int mWidth;
    private int mHeight;
    private Bitmap mBitmap;
    private boolean mRecycled = false;

    protected ImageWrapper(Mat mat) {
        mMat = mat;
        mWidth = mat.cols();
        mHeight = mat.rows();
        trackThis();
    }

    protected ImageWrapper(Bitmap bitmap) {
        mBitmap = bitmap;
        mWidth = bitmap.getWidth();
        mHeight = bitmap.getHeight();
        trackThis();
    }

    protected ImageWrapper(Bitmap bitmap, Mat mat) {
        mBitmap = bitmap;
        mMat = mat;
        mWidth = bitmap.getWidth();
        mHeight = bitmap.getHeight();
        trackThis();
    }

    public ImageWrapper(int width, int height) {
        this(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888));
    }

    private void trackThis() {
        synchronized (sLock) {
            if (sTrackingEnabled) {
                // 清理已回收的引用
                sAllImages.removeIf(ref -> ref.get() == null);
                sAllImages.add(new WeakReference<>(this));

                // 防止无限制增长（如果超过10000张，触发一次回收）
                if (sAllImages.size() > 10000) {
                    recycleAllTrackedImages();
                }
            }
        }
    }

    /**
     * 检查图片是否还存活（未被回收）
     */
    public boolean isAlive() {
        return !mRecycled && (mBitmap != null || mMat != null);
    }

    public static ImageWrapper ofImage(Image image) {
        if (image == null) {
            return null;
        }
        try (image) {
            return new ImageWrapper(toBitmap(image));
        }
    }

    public static ImageWrapper ofMat(Mat mat) {
        if (mat == null) {
            return null;
        }
        return new ImageWrapper(mat);
    }


    public static ImageWrapper ofBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        return new ImageWrapper(bitmap);
    }

    public static Bitmap toBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        buffer.position(0);
        int pixelStride = plane.getPixelStride();
        int rowPadding = plane.getRowStride() - pixelStride * image.getWidth();
        Bitmap bitmap = Bitmap.createBitmap(image.getWidth() + rowPadding / pixelStride, image.getHeight(), Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        if (rowPadding == 0) {
            return bitmap;
        }
        return Bitmap.createBitmap(bitmap, 0, 0, image.getWidth(), image.getHeight());
    }

    public int getWidth() {
        ensureNotRecycled();
        return mWidth;
    }

    public int getHeight() {
        ensureNotRecycled();
        return mHeight;
    }

    public Mat getMat() {
        ensureNotRecycled();
        if (mMat == null && mBitmap != null) {
            mMat = new Mat();
            Utils.bitmapToMat(mBitmap, mMat, true);
        }
        return mMat;
    }

    public void saveTo(String path) {
        ensureNotRecycled();
        if (mBitmap != null) {
            try {
                mBitmap.compress(Bitmap.CompressFormat.PNG, 100, new FileOutputStream(path));
            } catch (FileNotFoundException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            Imgcodecs.imwrite(path, mMat);
        }
    }

    public int pixel(int x, int y) {
        ensureNotRecycled();
        if (mBitmap != null) {
            return mBitmap.getPixel(x, y);
        }
        double[] channels = mMat.get(x, y);
        return Color.argb((int) channels[3], (int) channels[0], (int) channels[1], (int) channels[2]);
    }

    public Bitmap getBitmap() {
        ensureNotRecycled();
        if (mBitmap == null && mMat != null) {
            mBitmap = Bitmap.createBitmap(mMat.width(), mMat.height(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(mMat, mBitmap);
        }
        return mBitmap;
    }

    public void recycle() {
        if (mRecycled) return;
        mRecycled = true;

        mBitmap = null;
        if (mMat != null) {
            OpenCVHelper.release(mMat);
            mMat = null;
        }
    }

    public void ensureNotRecycled() {
        if (mRecycled || (mBitmap == null && mMat == null))
            throw new IllegalStateException("image has been recycled");
    }

    @Override
    @NonNull
    public ImageWrapper clone() {
        ensureNotRecycled();
        ImageWrapper result;
        if (mBitmap == null) {
            result = ImageWrapper.ofMat(mMat.clone());
        } else if (mMat == null) {
            result = ImageWrapper.ofBitmap(mBitmap.copy(mBitmap.getConfig(), true));
        } else {
            result = new ImageWrapper(mBitmap.copy(mBitmap.getConfig(), true), mMat.clone());
        }
        return result;
    }
}