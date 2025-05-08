package com.lykhonis.simpleimagecrop;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.annotation.NonNull;
import androidx.exifinterface.media.ExifInterface;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.WRITE_EXTERNAL_STORAGE;

public class SimpleImageCropPlugin implements
        FlutterPlugin,
        MethodCallHandler,
        ActivityAware,
        RequestPermissionsResultListener {

    private static final int PERMISSION_REQUEST_CODE = 13094;

    private Activity activity;
    private MethodChannel channel;
    private Result permissionRequestResult;
    private ExecutorService executor;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPlugin.FlutterPluginBinding binding) {
        channel = new MethodChannel(binding.getBinaryMessenger(), "plugins.lykhonis.com/image_crop");
        channel.setMethodCallHandler(this);
    }

    // ActivityAware: capture Activity and register for permission callbacks
    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        this.activity = binding.getActivity();
        binding.addRequestPermissionsResultListener(this);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() { /* no-op */ }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        this.activity = binding.getActivity();
        binding.addRequestPermissionsResultListener(this);
    }

    @Override
    public void onDetachedFromActivity() {
        this.activity = null;
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPlugin.FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        switch (call.method) {
            case "cropImage":
                cropImage(
                    call.<String>argument("path"),
                    new RectF(
                        call.<Double>argument("left").floatValue(),
                        call.<Double>argument("top").floatValue(),
                        call.<Double>argument("right").floatValue(),
                        call.<Double>argument("bottom").floatValue()
                    ),
                    call.<Double>argument("scale").floatValue(),
                    result
                );
                break;
            case "sampleImage":
                sampleImage(
                    call.<String>argument("path"),
                    call.<Integer>argument("maximumWidth"),
                    call.<Integer>argument("maximumHeight"),
                    result
                );
                break;
            case "getImageOptions":
                getImageOptions(call.<String>argument("path"), result);
                break;
            case "requestPermissions":
                requestPermissions(result);
                break;
            default:
                result.notImplemented();
        }
    }

    private synchronized void io(@NonNull Runnable r) {
        if (executor == null) executor = Executors.newCachedThreadPool();
        executor.execute(r);
    }

    private void ui(@NonNull Runnable r) {
        activity.runOnUiThread(r);
    }

    private void cropImage(final String path, final RectF area, final float scale, final Result result) {
        io(() -> {
            File srcFile = new File(path);
            if (!srcFile.exists()) {
                ui(() -> result.error("INVALID", "Image source cannot be opened", null));
                return;
            }

            Bitmap srcBitmap = BitmapFactory.decodeFile(path);
            if (srcBitmap == null) {
                ui(() -> result.error("INVALID", "Image source cannot be decoded", null));
                return;
            }

            ImageOptions options = decodeImageOptions(path);
            if (options.isFlippedDimensions()) {
                Matrix m = new Matrix();
                m.postRotate(options.getDegrees());
                Bitmap rotated = Bitmap.createBitmap(
                    srcBitmap, 0, 0, srcBitmap.getWidth(), srcBitmap.getHeight(), m, true
                );
                srcBitmap.recycle();
                srcBitmap = rotated;
            }

            int dstW = (int)(options.getWidth()  * area.width()  * scale);
            int dstH = (int)(options.getHeight() * area.height() * scale);

            Bitmap dstBitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(dstBitmap);
            Paint paint = new Paint();
            paint.setAntiAlias(true);
            paint.setFilterBitmap(true);
            paint.setDither(true);

            Rect srcRect = new Rect(
                (int)(srcBitmap.getWidth()  * area.left),
                (int)(srcBitmap.getHeight() * area.top),
                (int)(srcBitmap.getWidth()  * area.right),
                (int)(srcBitmap.getHeight() * area.bottom)
            );
            Rect dstRect = new Rect(0, 0, dstW, dstH);
            canvas.drawBitmap(srcBitmap, srcRect, dstRect, paint);

            try {
                File dstFile = createTemporaryImageFile();
                compressBitmap(dstBitmap, dstFile);
                ui(() -> result.success(dstFile.getAbsolutePath()));
            } catch (IOException e) {
                ui(() -> result.error("INVALID", "Image could not be saved", e));
            } finally {
                canvas.setBitmap(null);
                dstBitmap.recycle();
                srcBitmap.recycle();
            }
        });
    }

    private void sampleImage(final String path, final int maxW, final int maxH, final Result result) {
        io(() -> {
            File srcFile = new File(path);
            if (!srcFile.exists()) {
                ui(() -> result.error("INVALID", "Image source cannot be opened", null));
                return;
            }

            ImageOptions options = decodeImageOptions(path);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = calculateInSampleSize(options.getWidth(), options.getHeight(), maxW, maxH);

            Bitmap bitmap = BitmapFactory.decodeFile(path, opts);
            if (bitmap == null) {
                ui(() -> result.error("INVALID", "Image source cannot be decoded", null));
                return;
            }

            if (options.getWidth() > maxW && options.getHeight() > maxH) {
                float ratio = Math.max(maxW / (float)options.getWidth(), maxH / (float)options.getHeight());
                Bitmap scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    Math.round(bitmap.getWidth()  * ratio),
                    Math.round(bitmap.getHeight() * ratio),
                    true
                );
                bitmap.recycle();
                bitmap = scaled;
            }

            try {
                File dstFile = createTemporaryImageFile();
                compressBitmap(bitmap, dstFile);
                copyExif(srcFile, dstFile);
                ui(() -> result.success(dstFile.getAbsolutePath()));
            } catch (IOException e) {
                ui(() -> result.error("INVALID", "Image could not be saved", e));
            } finally {
                bitmap.recycle();
            }
        });
    }

    private void compressBitmap(Bitmap bitmap, File file) throws IOException {
        try (OutputStream os = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, os)) {
                throw new IOException("Failed to compress bitmap");
            }
        }
    }

    private int calculateInSampleSize(int width, int height, int maxW, int maxH) {
        int inSample = 1;
        if (height > maxH || width > maxW) {
            int halfH = height / 2;
            int halfW = width  / 2;
            while ((halfH / inSample) >= maxH && (halfW / inSample) >= maxW) {
                inSample <<= 1;
            }
        }
        return inSample;
    }

    private void getImageOptions(final String path, final Result result) {
        io(() -> {
            File file = new File(path);
            if (!file.exists()) {
                ui(() -> result.error("INVALID", "Image source cannot be opened", null));
                return;
            }
            ImageOptions options = decodeImageOptions(path);
            Map<String, Object> props = new HashMap<>();
            props.put("width", options.getWidth());
            props.put("height", options.getHeight());
            ui(() -> result.success(props));
        });
    }

    private void requestPermissions(@NonNull Result result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (activity.checkSelfPermission(READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                activity.checkSelfPermission(WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                result.success(true);
            } else {
                permissionRequestResult = result;
                activity.requestPermissions(
                    new String[]{ READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE },
                    PERMISSION_REQUEST_CODE
                );
            }
        } else {
            result.success(true);
        }
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE && permissionRequestResult != null) {
            int read = getPermissionGrantResult(READ_EXTERNAL_STORAGE, permissions, grantResults);
            int write = getPermissionGrantResult(WRITE_EXTERNAL_STORAGE, permissions, grantResults);
            permissionRequestResult.success(
                read == PackageManager.PERMISSION_GRANTED &&
                write == PackageManager.PERMISSION_GRANTED
            );
            permissionRequestResult = null;
        }
        return false;
    }

    private int getPermissionGrantResult(String permission, String[] permissions, int[] grantResults) {
        for (int i = 0; i < permissions.length; i++) {
            if (permission.equals(permissions[i])) {
                return grantResults[i];
            }
        }
        return PackageManager.PERMISSION_DENIED;
    }

    private File createTemporaryImageFile() throws IOException {
        File dir = activity.getCacheDir();
        String name = "image_crop_" + UUID.randomUUID();
        return File.createTempFile(name, ".jpg", dir);
    }

    private ImageOptions decodeImageOptions(String path) {
        int rotation = 0;
        try {
            ExifInterface exif = new ExifInterface(path);
            rotation = exif.getRotationDegrees();
        } catch (IOException e) {
            Log.e("simple", "Failed to read EXIF", e);
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        return new ImageOptions(opts.outWidth, opts.outHeight, rotation);
    }

    private void copyExif(File src, File dst) {
        try {
            ExifInterface srcExif = new ExifInterface(src.getAbsolutePath());
            ExifInterface dstExif = new ExifInterface(dst.getAbsolutePath());
            List<String> tags = Arrays.asList(
                ExifInterface.TAG_F_NUMBER,
                ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_ISO_SPEED_RATINGS,
                ExifInterface.TAG_FOCAL_LENGTH,
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_FLASH,
                ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_ORIENTATION
            );
            for (String tag : tags) {
                String attr = srcExif.getAttribute(tag);
                if (attr != null) dstExif.setAttribute(tag, attr);
            }
            dstExif.saveAttributes();
        } catch (IOException e) {
            Log.e("simple", "Failed to copy EXIF", e);
        }
    }

    private static final class ImageOptions {
        private final int width, height, degrees;
        ImageOptions(int w, int h, int d) { width = w; height = h; degrees = d; }
        int getWidth()  { return (degrees == 90 || degrees == 270) ? height : width; }
        int getHeight() { return (degrees == 90 || degrees == 270) ? width  : height; }
        int getDegrees(){ return degrees; }
        boolean isFlippedDimensions() { return degrees == 90 || degrees == 270; }
    }
}
