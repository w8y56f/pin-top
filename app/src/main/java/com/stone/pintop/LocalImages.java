package com.stone.pintop;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.InputStream;
import java.io.BufferedInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

final class LocalImages {
    private static final String[] METADATA = {
            MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns._ID
    };

    static final class Image {
        final Uri uri;
        final String path;
        final long modifiedSeconds;
        final String owner;

        Image(Uri uri, String path, long modifiedSeconds, String owner) {
            this.uri = uri;
            this.path = path;
            this.modifiedSeconds = modifiedSeconds;
            this.owner = owner;
        }
    }

    static Image resolve(Context context, Uri source) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        if (!ContentResolver.SCHEME_CONTENT.equals(source.getScheme())) return null;
        String authority = source.getAuthority();
        int currentUser = Process.myUid() / 100_000; // Android reserves 100,000 UIDs per user.
        if ("com.android.providers.media.documents".equals(authority)
                || "com.android.externalstorage.documents".equals(authority)) {
            Uri media = MediaStore.getMediaUri(context, source);
            if (media != null) return resolve(context, media);
        }
        List<String> path = source.getPathSegments();
        if (MediaStore.AUTHORITY.equals(authority)
                || (currentUser + "@media").equals(authority)) {
            if (path.size() == 4 && ("images".equals(path.get(1)) || "video".equals(path.get(1))) && "media".equals(path.get(2))) {
                Image direct = read(resolver, source);
                if (direct != null) return direct;
                return "images".equals(path.get(1)) ? matchSharedFile(resolver, source) : null;
            }
            if (path.size() == 3 && "file".equals(path.get(1))) {
                Image direct = read(resolver, source);
                if (direct != null) return direct;
                String mime = resolver.getType(source);
                return mime != null && mime.startsWith("image/") ? matchSharedFile(resolver, source) : null;
            }
            if (path.size() == 5 && ("picker".equals(path.get(0))
                    || "picker_get_content".equals(path.get(0)))) {
                if (!"com.android.providers.media.photopicker".equals(path.get(2))
                        || !"media".equals(path.get(3))
                        || !Integer.toString(currentUser).equals(path.get(1))) {
                    return null;
                }
                return read(resolver, ContentUris.withAppendedId(
                        collection(resolver.getType(source), MediaStore.VOLUME_EXTERNAL), Long.parseLong(path.get(4))));
            }
        }
        String type = resolver.getType(source);
        if (type != null && type.startsWith("video/")) return null;
        return matchSharedFile(resolver, source);
    }

    private static Uri collection(String mime, String volume) {
        return mime != null && mime.startsWith("video/")
                ? MediaStore.Video.Media.getContentUri(volume)
                : MediaStore.Images.Media.getContentUri(volume);
    }

    private static Image read(ContentResolver resolver, Uri uri) {
        if (MediaStore.VOLUME_INTERNAL.equals(uri.getPathSegments().get(0))) {
            return null;
        }
        try (Cursor row = resolver.query(uri, METADATA, null, null, null)) {
            if (row == null || !row.moveToFirst()) return null;
            Image image = fromRow(row, row.getLong(5));
            if (image == null || image.uri.equals(uri)) return image;
            // A gallery can expose an alias whose ID is valid only in the shared URI.
            try (Cursor original = resolver.query(image.uri, METADATA, null, null, null)) {
                if (original != null && original.moveToFirst() && image.path.equals(original.getString(0))) {
                    return image;
                }
            }
            List<String> segments = uri.getPathSegments();
            if (segments.size() == 4 && Long.toString(row.getLong(5)).equals(segments.get(3))) {
                return new Image(uri, image.path, image.modifiedSeconds, image.owner);
            }
            return null;
        }
    }

    private static Image fromRow(Cursor row, long id) {
        String path = row.getString(0);
        String mime = row.getString(2);
        String volume = row.getString(3);
        if (path == null || path.isEmpty() || row.isNull(1) || mime == null
                || !(mime.startsWith("image/") || mime.startsWith("video/")) || volume == null
                || MediaStore.VOLUME_INTERNAL.equals(volume)) return null;
        Uri uri = ContentUris.withAppendedId(collection(mime, volume), id);
        return new Image(uri, path, row.getLong(1), row.getString(4));
    }

    private static Image matchSharedFile(ContentResolver resolver, Uri source) throws Exception {
        String name;
        long size;
        try (Cursor row = resolver.query(source,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (row == null || !row.moveToFirst() || row.isNull(0)) return null;
            name = row.getString(0);
            size = row.isNull(1) ? -1 : row.getLong(1);
        }
        String[] projection = {METADATA[0], METADATA[1], METADATA[2], METADATA[3], METADATA[4],
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE};
        Image match = null;
        byte[] sharedHash = null;
        try (Cursor rows = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection,
                MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{name}, null)) {
            if (rows == null) return null;
            while (rows.moveToNext()) {
                Image candidate = fromRow(rows, rows.getLong(5));
                if (candidate == null) continue;
                boolean matches = false;
                if (size < 0 || size == rows.getLong(6)) {
                    if (sharedHash == null) sharedHash = hash(resolver, source);
                    matches = sharedHash != null && Arrays.equals(sharedHash, hash(resolver, candidate.uri));
                }
                if (!matches && "image/jpeg".equalsIgnoreCase(rows.getString(2))) {
                    try (InputStream shared = resolver.openInputStream(source);
                         InputStream original = resolver.openInputStream(candidate.uri)) {
                        matches = shared != null && original != null && sameJpegImageData(shared, original);
                    }
                }
                if (matches) {
                    if (match != null) return null;
                    match = candidate;
                }
            }
        }
        return match;
    }

    private static byte[] hash(ContentResolver resolver, Uri uri) throws Exception {
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) return null;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
            return digest.digest();
        }
    }

    static boolean sameJpegImageData(InputStream sharedInput, InputStream originalInput)
            throws java.io.IOException {
        InputStream shared = new BufferedInputStream(sharedInput);
        InputStream original = new BufferedInputStream(originalInput);
        if (!skipJpegMetadata(shared) || !skipJpegMetadata(original)) return false;
        byte[] sharedBuffer = new byte[64 * 1024];
        byte[] originalBuffer = new byte[64 * 1024];
        int count;
        while ((count = shared.read(sharedBuffer)) != -1) {
            int offset = 0;
            while (offset < count) {
                int read = original.read(originalBuffer, offset, count - offset);
                if (read == -1) return false;
                offset += read;
            }
            for (int i = 0; i < count; i++) {
                if (sharedBuffer[i] != originalBuffer[i]) return false;
            }
        }
        return original.read() == -1;
    }

    // Compare the JPEG scan and everything after it. APP/EXIF segments before
    // the scan may differ after a gallery redacts location and camera metadata.
    private static boolean skipJpegMetadata(InputStream input) throws java.io.IOException {
        if (input.read() != 0xff || input.read() != 0xd8) return false;
        while (true) {
            if (input.read() != 0xff) return false;
            int marker;
            do { marker = input.read(); } while (marker == 0xff);
            if (marker < 0 || marker == 0xd9 || marker == 0x00) return false;
            if (marker == 0xd8 || marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
            int high = input.read();
            int low = input.read();
            if (high < 0 || low < 0) return false;
            int length = (high << 8) | low;
            if (length < 2) return false;
            int remaining = length - 2;
            while (remaining > 0) {
                long skipped = input.skip(remaining);
                if (skipped == 0) {
                    if (input.read() == -1) return false;
                    skipped = 1;
                }
                remaining -= (int) skipped;
            }
            if (marker == 0xda) return true;
        }
    }

    private LocalImages() {}
}
