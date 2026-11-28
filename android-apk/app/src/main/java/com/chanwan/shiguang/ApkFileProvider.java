package com.chanwan.shiguang;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class ApkFileProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolveTargetFile(Uri uri) {
        if (getContext() == null) return null;
        String path = (uri != null && uri.getPath() != null) ? uri.getPath() : "";
        if (path.contains("capture_photo")) {
            return new File(getContext().getCacheDir(), "cw_cam_photo.jpg");
        }
        if (path.contains("capture_video")) {
            return new File(getContext().getCacheDir(), "cw_cam_video.mp4");
        }
        return new File(getContext().getCacheDir(), "chanwan-update.apk");
    }

    private String resolveDisplayName(Uri uri) {
        String path = (uri != null && uri.getPath() != null) ? uri.getPath() : "";
        if (path.contains("capture_photo")) return "camera_photo.jpg";
        if (path.contains("capture_video")) return "camera_video.mp4";
        return "chanwan-shiguang.apk";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolveTargetFile(uri);
        if (f == null) throw new FileNotFoundException("Target null");
        boolean wantsWrite = mode != null && (mode.contains("w") || mode.contains("W"));
        if (wantsWrite) {
            try {
                if (!f.exists()) f.createNewFile();
            } catch (IOException ioe) {
                throw new FileNotFoundException("Cannot create capture file");
            }
            int pfdMode = ParcelFileDescriptor.MODE_READ_WRITE
                    | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE;
            return ParcelFileDescriptor.open(f, pfdMode);
        }
        if (!f.exists()) throw new FileNotFoundException("File not found: " + f.getName());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String path = (uri != null && uri.getPath() != null) ? uri.getPath() : "";
        if (path.contains("capture_photo")) return "image/jpeg";
        if (path.contains("capture_video")) return "video/mp4";
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f = resolveTargetFile(uri);
        String dispName = resolveDisplayName(uri);
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor mc = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                row[i] = dispName;
            } else if (OpenableColumns.SIZE.equals(cols[i])) {
                row[i] = (f != null && f.exists()) ? f.length() : 0L;
            } else {
                row[i] = null;
            }
        }
        mc.addRow(row);
        return mc;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
