package com.stone.pintop;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class ModificationTime {
    static void update(Context context, LocalImages.Image image, long seconds) throws Exception {
        var resolver = context.getContentResolver();
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(image.uri, "rw")) {
            if (descriptor == null || !OsConstants.S_ISREG(Os.fstat(descriptor.getFileDescriptor()).st_mode)) {
                throw new IOException("Expected a local image file");
            }
            File openedFile = new File("/proc/self/fd/" + descriptor.getFd());
            long millis = seconds * 1000;
            // Some MediaProvider versions reject timestamp changes through procfs.
            if (!openedFile.setLastModified(millis) && !new File(image.path).setLastModified(millis)) {
                throw new IOException("Could not set file modification time");
            }
            if (Os.fstat(descriptor.getFileDescriptor()).st_mtime != seconds) {
                throw new IOException("File modification time does not match");
            }
        }
        CountDownLatch scanned = new CountDownLatch(1);
        MediaScannerConnection.scanFile(context, new String[]{image.path}, null,
                (path, uri) -> scanned.countDown());
        if (!scanned.await(15, TimeUnit.SECONDS)) throw new IOException("Media scan timed out");
        try (Cursor row = resolver.query(image.uri, new String[]{MediaStore.MediaColumns.DATE_MODIFIED},
                null, null, null)) {
            if (row == null || !row.moveToFirst() || row.isNull(0) || row.getLong(0) != seconds) {
                throw new IOException("MediaStore did not index the new modification time");
            }
        }
    }

    private ModificationTime() {}
}
