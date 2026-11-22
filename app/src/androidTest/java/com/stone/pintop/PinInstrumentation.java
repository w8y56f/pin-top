package com.stone.pintop;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.system.Os;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class PinInstrumentation extends Instrumentation {
    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            testShares();
            result.putString("stream", "\nPinTop tests passed\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            result.putString("stream", "\n" + android.util.Log.getStackTraceString(e));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void testShares() throws Exception {
        var context = getTargetContext();
        var resolver = context.getContentResolver();
        var fixtures = new ArrayList<Uri>();
        try {
            Uri jpeg = insert(fixtures, "camera.jpg", "image/jpeg");
            Uri gif = insert(fixtures, "animation.gif", "image/gif");
            Uri webp = insert(fixtures, "animation.webp", "image/webp");
            List<byte[]> hashes = new ArrayList<>();
            for (Uri uri : fixtures) hashes.add(hash(uri));

            long old = 1_500_000_000L;
            ModificationTime.update(context, LocalImages.resolve(context, jpeg), old + 1);
            ModificationTime.update(context, LocalImages.resolve(context, gif), old);
            ModificationTime.update(context, LocalImages.resolve(context, webp), old);

            Intent share = new Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setClass(context, ShareActivity.class).setType("image/*")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(List.of(
                            webp, gif, jpeg, jpeg, Uri.parse("content://invalid/unknown"))));
            long earliest = System.currentTimeMillis() / 1000;
            context.startActivity(share);
            for (int i = 0; i < 100 && modified(gif) < earliest - 2; i++) SystemClock.sleep(100);
            long first = modified(jpeg);
            require(first >= earliest, "The JPEG should move to the current time");
            require(modified(webp) == first - 1 && modified(gif) == first - 2,
                    "Multi-share must preserve time order and break ties with share order");
            for (int i = 0; i < fixtures.size(); i++) {
                require(Arrays.equals(hash(fixtures.get(i)), hashes.get(i)), "Image bytes changed");
                try (var file = resolver.openFileDescriptor(fixtures.get(i), "r")) {
                    require(Os.fstat(file.getFileDescriptor()).st_mtime == modified(fixtures.get(i)),
                            "File and MediaStore modification times differ");
                }
            }

            context.startActivity(new Intent(Intent.ACTION_SEND).setClass(context, ShareActivity.class)
                    .setType("image/gif").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    .putExtra(Intent.EXTRA_STREAM, gif));
            for (int i = 0; i < 100 && modified(gif) <= first - 2; i++) SystemClock.sleep(100);
            require(modified(gif) >= first, "Single GIF share did not refresh modification time");
            require(Arrays.equals(hash(gif), hashes.get(1)), "GIF bytes changed");
            Uri older = insert(fixtures, "rotated.mp4", "video/mp4");
            Uri newer = insert(fixtures, "rotated.mp4", "video/mp4");
            byte[] videoHash = hash(older);
            ModificationTime.update(context, LocalImages.resolve(context, older), old);
            ModificationTime.update(context, LocalImages.resolve(context, newer), old + 10);
            context.startActivity(new Intent(share).setType("video/mp4")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(List.of(older, newer))));
            for (int i = 0; i < 100 && modified(older) == old; i++) SystemClock.sleep(100);
            require(modified(newer) >= earliest && modified(older) == modified(newer) - 1, "Video order");
            require(Arrays.equals(hash(older), videoHash) && Arrays.equals(hash(newer), videoHash),
                    "Video bytes changed");
        } finally {
            for (Uri fixture : fixtures) resolver.delete(fixture, null, null);
        }
    }

    private Uri insert(List<Uri> fixtures, String asset, String mime) throws Exception {
        var resolver = getTargetContext().getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "PinTop-test-" + UUID.randomUUID() + "-" + asset);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri collection = mime.startsWith("video/") ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri uri = resolver.insert(collection, values);
        require(uri != null, "MediaStore did not create a fixture");
        fixtures.add(uri);
        try (InputStream input = getContext().getAssets().open(asset);
             var output = resolver.openOutputStream(uri)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return uri;
    }

    private long modified(Uri uri) {
        try (var row = getTargetContext().getContentResolver().query(uri,
                new String[]{MediaStore.MediaColumns.DATE_MODIFIED}, null, null, null)) {
            require(row != null && row.moveToFirst(), "No indexed modification time");
            return row.getLong(0);
        }
    }

    private byte[] hash(Uri uri) throws Exception {
        try (InputStream input = getTargetContext().getContentResolver().openInputStream(uri)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
            return digest.digest();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
