package com.stone.pintop;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public final class ShareActivity extends Activity {
    private static final int WRITE_REQUEST = 1;
    private static final String WAITING = "waiting";
    private static final Executor WORKER = Executors.newSingleThreadExecutor();

    private Batch pending;
    private boolean waiting;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        waiting = state != null && state.getBoolean(WAITING);
        if (!waiting) prepare(false);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean(WAITING, waiting);
        super.onSaveInstanceState(state);
    }

    @SuppressWarnings("deprecation")
    static List<Uri> sharedUris(Intent intent) {
        List<Uri> uris = new ArrayList<>();
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            if (intent.getParcelableExtra(Intent.EXTRA_STREAM) instanceof Uri) {
                uris.add(intent.getParcelableExtra(Intent.EXTRA_STREAM));
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            List<?> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) {
                for (Object item : streams) {
                    if (item instanceof Uri) uris.add((Uri) item);
                }
            }
        } else {
            return uris;
        }
        if (uris.isEmpty() && intent.getClipData() != null) {
            var clip = intent.getClipData();
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        }
        return uris;
    }

    private void prepare(boolean alreadyAuthorized) {
        WORKER.execute(() -> {
            var images = new LinkedHashMap<Uri, LocalImages.Image>();
            int skipped = 0;
            try {
                for (Uri uri : sharedUris(getIntent())) {
                    LocalImages.Image image = null;
                    try {
                        image = LocalImages.resolve(this, uri);
                    } catch (Exception e) {
                        Log.w("PinTop", "Could not resolve shared image", e);
                    }
                    if (image == null || images.putIfAbsent(image.uri, image) != null) skipped++;
                }
            } catch (RuntimeException e) {
                Log.w("PinTop", "Invalid share intent", e);
                skipped++;
            }
            Batch batch = new Batch(new ArrayList<>(images.values()), skipped);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (alreadyAuthorized) apply(batch);
                else authorize(batch);
            });
        });
    }

    private void authorize(Batch batch) {
        List<Uri> needsWrite = new ArrayList<>();
        for (LocalImages.Image image : batch.images) {
            if (!getPackageName().equals(image.owner)
                    && checkUriPermission(image.uri, android.os.Process.myPid(), android.os.Process.myUid(),
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                needsWrite.add(image.uri);
            }
        }
        if (needsWrite.isEmpty()) {
            apply(batch);
            return;
        }
        try {
            pending = batch;
            waiting = true;
            var request = MediaStore.createWriteRequest(getContentResolver(), needsWrite);
            startIntentSenderForResult(request.getIntentSender(), WRITE_REQUEST, null, 0, 0, 0);
        } catch (Exception e) {
            Log.w("PinTop", "Could not request write access", e);
            showResult(0, batch.skipped, batch.images.size());
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != WRITE_REQUEST) return;
        waiting = false;
        if (result != RESULT_OK) {
            Toast.makeText(this, R.string.cancelled, Toast.LENGTH_SHORT).show();
            finish();
        } else if (pending == null) {
            prepare(true);
        } else {
            apply(pending);
        }
    }

    static void sort(List<LocalImages.Image> images) {
        images.sort(Comparator.comparingLong((LocalImages.Image image) -> image.modifiedSeconds).reversed());
    }

    private void apply(Batch batch) {
        WORKER.execute(() -> {
            sort(batch.images);
            long seconds = System.currentTimeMillis() / 1000;
            int updated = 0;
            for (LocalImages.Image image : batch.images) {
                try {
                    ModificationTime.update(this, image, seconds);
                    updated++;
                } catch (Exception e) {
                    Log.w("PinTop", "Could not update image modification time", e);
                }
                seconds--;
            }
            int count = updated;
            runOnUiThread(() -> showResult(count, batch.skipped, batch.images.size() - count));
        });
    }

    private void showResult(int updated, int skipped, int failed) {
        if (isDestroyed()) return;
        Toast.makeText(this, getString(R.string.result, updated, skipped, failed), Toast.LENGTH_SHORT).show();
        finish();
    }

    private static final class Batch {
        final List<LocalImages.Image> images;
        final int skipped;

        Batch(List<LocalImages.Image> images, int skipped) {
            this.images = images;
            this.skipped = skipped;
        }
    }
}
