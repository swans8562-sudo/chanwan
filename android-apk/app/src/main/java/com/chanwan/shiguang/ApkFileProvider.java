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

public class ApkFileProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    private File resolveApkFile() {
        if (getContext() == null) return null;
        return new File(getContext().getCacheDir(), "chanwan-update.apk");
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolveApkFile();
        if (f == null || !f.exists()) throw new FileNotFoundException("APK not found");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f = resolveApkFile();
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor mc = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                row[i] = "chanwan-shiguang.apk";
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
