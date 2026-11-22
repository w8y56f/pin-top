package com.stone.pintop;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class SettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            view.setPadding(padding + bars.left, padding + bars.top,
                    padding + bars.right, padding + bars.bottom);
            return insets;
        });

        TextView title = new TextView(this);
        String name = getString(R.string.app_name);
        String version = BuildConfig.VERSION_NAME + " (" + BuildConfig.REVISION
                + (BuildConfig.DIRTY ? " - dirty)" : ")");
        var heading = new android.text.SpannableString(getString(R.string.versioned_title, name, version));
        heading.setSpan(new android.text.style.RelativeSizeSpan(0.55f), heading.length() - version.length(),
                heading.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setText(heading);
        title.setTextSize(28);
        content.addView(title);
        TextView help = new TextView(this);
        help.setText(getString(R.string.description, getString(R.string.intro), getString(R.string.permission_help)));
        help.setPadding(0, padding / 2, 0, padding / 2);
        content.addView(help);

        Button read = new Button(this);
        read.setText(R.string.allow_read);
        read.setOnClickListener(view -> {
            String images = Build.VERSION.SDK_INT >= 33
                    ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
            String location = Manifest.permission.ACCESS_MEDIA_LOCATION;
            requestPermissions(Build.VERSION.SDK_INT >= 34
                    ? new String[]{images, Manifest.permission.READ_MEDIA_VIDEO, location, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}
                    : Build.VERSION.SDK_INT == 33 ? new String[]{images, Manifest.permission.READ_MEDIA_VIDEO, location}
                    : new String[]{images, location}, 1);
        });
        content.addView(read);

        if (Build.VERSION.SDK_INT >= 31) {
            Button manage = new Button(this);
            manage.setText(R.string.manage_media);
            manage.setOnClickListener(view -> {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA,
                            Uri.fromParts("package", getPackageName(), null)));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show();
                }
            });
            content.addView(manage);
        }
        setContentView(content);
    }
}
