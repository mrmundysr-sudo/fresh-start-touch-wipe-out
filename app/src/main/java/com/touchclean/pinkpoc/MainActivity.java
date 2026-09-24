package com.touchclean.pinkpoc;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Size;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQUEST_PHOTOS = 42;
    private static final int REQUEST_DELETE_PHOTOS = 43;
    private static final int REQUEST_BIG_FILES = 44;
    private static final int REQUEST_ALL_FILES = 45;
    private static final int REQUEST_UNINSTALL_APP = 46;
    private static final long BIG_FILE_MIN_BYTES = 100L * 1024L * 1024L;
    private static final float DESIGN_W = 841f;
    private static final float DESIGN_H = 1870f;
    private static final int SWIPE_THRESHOLD = 110;
    private static final int TAP_SLOP = 28;

    private enum ScreenMode { STARTUP, REVIEW, REVIEW_BIN }
    private enum ReviewKind { PHOTOS, BIG_FILES, DOWNLOADS, APPS }

    private static final String PREFS_NAME = "touch_clean_decisions";
    private static final String PREF_DECISIONS = "photo_decisions";

    private static class Decision {
        final int index;
        final String action;

        Decision(int index, String action) {
            this.index = index;
            this.action = action;
        }
    }

    private static class MediaCandidate {
        final Uri uri;
        final long size;
        final String type;
        final String name;

        MediaCandidate(Uri uri, long size, String type, String name) {
            this.uri = uri;
            this.size = size;
            this.type = type;
            this.name = name;
        }
    }

    private FrameLayout stage;
    private TextView status;
    private ScreenMode screenMode = ScreenMode.STARTUP;
    private boolean storageMode;

    private ImageView aBottomTray;
    private ImageView aMediaTab;
    private ImageView aStorageTab;
    private ImageView aPhotos;
    private ImageView aSimilar;
    private ImageView aVideos;
    private ImageView aScreenshots;

    private ImageView bBottomTray;
    private ImageView bMediaTab;
    private ImageView bStorageTab;
    private ImageView bBigFiles;
    private ImageView bOldFiles;
    private ImageView bDownloads;
    private ImageView bApps;

    private ImageView photoView;
    private ImageView reviewHud;
    private TextView reviewStatus;
    private TextView reviewBinButton;
    private TextView reviewExitButton;
    private TextView actionFeedback;
    private LinearLayout fileDetailsPanel;
    private TextView fileTypeText;
    private TextView fileSizeText;
    private TextView fileNameText;
    private TextView fileLocationText;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<Uri> photos = new ArrayList<>();
    private final ArrayList<Decision> decisions = new ArrayList<>();
    private final ArrayList<Uri> trashPhotos = new ArrayList<>();
    private final ArrayList<Uri> pendingDeletion = new ArrayList<>();
    private Uri pendingAppUninstall;
    private final Set<String> persistedDecisions = new HashSet<>();
    private final HashMap<String, Long> itemSizes = new HashMap<>();
    private final HashMap<String, String> itemTypes = new HashMap<>();
    private final HashMap<String, String> itemNames = new HashMap<>();
    private int photoIndex;
    private ReviewKind reviewKind = ReviewKind.PHOTOS;
    private ReviewKind pendingAllFilesReview = ReviewKind.BIG_FILES;

    private float downX;
    private float downY;
    private boolean stepBackCandidate;
    private ScaleGestureDetector scaleDetector;
    private float photoScale = 1f;
    private float photoTranslationX;
    private float photoTranslationY;
    private float lastDragX;
    private float lastDragY;
    private boolean zoomGesture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.rgb(5, 0, 8));
        setContentView(stage);
        showStartup();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (screenMode == ScreenMode.REVIEW) {
            updateReviewHud();
        }
        enterImmersiveMode();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
        }
    }

    @Override
    public void onBackPressed() {
        if (screenMode == ScreenMode.REVIEW_BIN) {
            showReview();
        } else if (screenMode == ScreenMode.REVIEW) {
            showStartup();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PHOTOS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startPhotoReview();
            } else {
                status.setText("Photo permission needed to scan.");
                showThemedNotice("Photo access is needed to review photos.");
            }
        } else if (requestCode == REQUEST_BIG_FILES) {
            boolean granted = false;
            for (int result : grantResults) {
                granted |= result == PackageManager.PERMISSION_GRANTED;
            }
            if (granted) {
                startBigFilesReview();
            } else {
                status.setText("Media permission needed to scan big files.");
                showThemedNotice("Media access is needed to review big files.");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_UNINSTALL_APP) {
            finishAppUninstall();
            return;
        }
        if (requestCode != REQUEST_DELETE_PHOTOS) {
            if (requestCode == REQUEST_ALL_FILES && Build.VERSION.SDK_INT >= 30) {
                if (Environment.isExternalStorageManager()) {
                    if (pendingAllFilesReview == ReviewKind.DOWNLOADS) {
                        requestDownloadsReview();
                    } else {
                        requestBigFilesReview();
                    }
                } else {
                    showThemedNotice("All files access is needed to find large files.");
                }
            }
            return;
        }
        if (resultCode == RESULT_OK) {
            finishApprovedDeletion();
            showThemedNotice("Deletion approved.");
        } else {
            pendingDeletion.clear();
            showThemedNotice("Deletion cancelled. Items remain in Review Bin.");
        }
        if (screenMode == ScreenMode.REVIEW_BIN) {
            showReviewBin();
        }
    }

    private void showStartup() {
        uiHandler.removeCallbacksAndMessages(null);
        screenMode = ScreenMode.STARTUP;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        storageMode = false;
        stage.removeAllViews();
        stage.setBackgroundColor(Color.rgb(5, 0, 8));

        // Fresh visual foundation: use the approved complete Touch Wipe Out artwork as one
        // authoritative screen. Do not rebuild it from the archived overlay layers.
        addFullScreenImage("touch_wipe_out_wave_adventure", true);

        aMediaTab = addFullScreenImage("a_media_tab", false);
        aStorageTab = addFullScreenImage("a_storage_tab", false);
        aPhotos = addFullScreenImage("approved_category_photos", false);
        aSimilar = addFullScreenImage("approved_category_similar", false);
        aVideos = addFullScreenImage("approved_category_videos", false);
        aScreenshots = addFullScreenImage("approved_category_screenshots", false);
        aBottomTray = addFullScreenImage("a_bottom_tray", false);

        bMediaTab = addFullScreenImage("b_media_tab", false);
        bStorageTab = addFullScreenImage("b_storage_tab", false);
        bBigFiles = addFullScreenImage("approved_category_big_files", false);
        bOldFiles = addFullScreenImage("approved_category_old_files", false);
        bDownloads = addFullScreenImage("approved_category_downloads", false);
        bApps = addFullScreenImage("approved_category_apps", false);
        bBottomTray = addFullScreenImage("b_bottom_tray", false);

        addHitZone("Photos or Big Files", 286, 738, 270, 220, () -> {
            if (storageMode) {
                requestBigFilesReview();
            } else {
                requestPhotoReview();
            }
        });
        addHitZone("Similar", 487, 870, 230, 260, () -> pressCenter(storageMode ? bOldFiles : aSimilar, storageMode ? "Old Files selected" : "Similar selected"));
        addHitZone("Videos or Downloads", 292, 1058, 260, 210, () -> {
            if (storageMode) {
                requestDownloadsReview();
            } else {
                pressCenter(aVideos, "Videos selected");
            }
        });
        addHitZone("Screenshots or Apps", 135, 880, 230, 250, () -> {
            if (storageMode) {
                startAppsReview();
            } else {
                pressCenter(aScreenshots, "Screenshots selected");
            }
        });
        addHitZone("Bottom tray switcher", 95, 1362, 650, 230, this::toggleMode);

        TextView resetButton = makeOverlayButton("◎ RESET", Color.rgb(85, 255, 175));
        resetButton.setContentDescription("Restore existing photos to Touch Clean search");
        resetButton.setOnClickListener(v -> confirmGlobalReset());
        FrameLayout.LayoutParams resetParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END
        );
        resetParams.setMargins(24, 28, 24, 0);
        stage.addView(resetButton, resetParams);

        status = new TextView(this);
        status.setText("Tap Photos to scan and review.");
        status.setTextColor(Color.WHITE);
        status.setTextSize(14f);
        status.setGravity(Gravity.CENTER);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setShadowLayer(14f, 0f, 0f, Color.rgb(230, 40, 255));
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        statusParams.setMargins(24, 0, 24, 26);
        stage.addView(status, statusParams);
        enterImmersiveMode();
    }

    private void requestPhotoReview() {
        status.setText("Preparing photo scan...");
        pulse(aPhotos);
        String permission = photoPermission();
        if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            startPhotoReview();
        } else {
            requestPermissions(new String[]{permission}, REQUEST_PHOTOS);
        }
    }

    private String photoPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return Manifest.permission.READ_MEDIA_IMAGES;
        }
        return Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void startPhotoReview() {
        reviewKind = ReviewKind.PHOTOS;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        photos.clear();
        decisions.clear();
        itemSizes.clear();
        itemTypes.clear();
        loadPersistedDecisions();
        photoIndex = 0;
        scanPhotos();
        showReview();
    }

    private void requestBigFilesReview() {
        pendingAllFilesReview = ReviewKind.BIG_FILES;
        status.setText("Preparing 100 MB+ file scan...");
        pulse(bBigFiles);
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            showThemedDialog(new AlertDialog.Builder(this)
                    .setTitle("Allow Big Files scan")
                    .setMessage("Touch Clean needs Android's All files access to find ZIPs, APKs, PDFs, videos and other files over 100 MB. It never deletes anything without your confirmation.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open Settings", (dialog, which) -> openAllFilesSettings()));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            boolean videos = checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
            if (videos) {
                startBigFilesReview();
            } else {
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_VIDEO}, REQUEST_BIG_FILES);
            }
        } else if (Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            startBigFilesReview();
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_BIG_FILES);
        }
    }

    private void requestDownloadsReview() {
        pendingAllFilesReview = ReviewKind.DOWNLOADS;
        status.setText("Preparing Downloads scan...");
        pulse(bDownloads);
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            showThemedDialog(new AlertDialog.Builder(this)
                    .setTitle("Allow Downloads scan")
                    .setMessage("Touch Clean needs Android's All files access to find smaller APKs, ZIPs, PDFs, documents and other files in Downloads. It never deletes anything without your confirmation.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open Settings", (dialog, which) -> openAllFilesSettings()));
            return;
        }
        startDownloadsReview();
    }

    private void openAllFilesSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_ALL_FILES);
        } catch (Exception e) {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQUEST_ALL_FILES);
        }
    }

    private void startBigFilesReview() {
        reviewKind = ReviewKind.BIG_FILES;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        photos.clear();
        decisions.clear();
        itemSizes.clear();
        itemTypes.clear();
        itemNames.clear();
        loadPersistedDecisions();
        photoIndex = 0;
        scanBigFiles();
        showReview();
    }

    private void startDownloadsReview() {
        reviewKind = ReviewKind.DOWNLOADS;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        photos.clear();
        decisions.clear();
        itemSizes.clear();
        itemTypes.clear();
        itemNames.clear();
        loadPersistedDecisions();
        photoIndex = 0;
        scanDownloads();
        showReview();
    }

    private void startAppsReview() {
        reviewKind = ReviewKind.APPS;
        status.setText("Preparing installed apps...");
        pulse(bApps);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        photos.clear();
        decisions.clear();
        itemSizes.clear();
        itemTypes.clear();
        itemNames.clear();
        loadPersistedDecisions();
        photoIndex = 0;
        scanApps();
        showReview();
    }

    private void scanApps() {
        PackageManager manager = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN, null);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        ArrayList<String> seen = new ArrayList<>();
        for (ResolveInfo resolved : manager.queryIntentActivities(launcher, 0)) {
            ApplicationInfo info = resolved.activityInfo == null ? null : resolved.activityInfo.applicationInfo;
            if (info == null || info.packageName == null
                    || info.packageName.equals(getPackageName())
                    || seen.contains(info.packageName)) {
                continue;
            }
            seen.add(info.packageName);
            Uri item = Uri.parse("package:" + info.packageName);
            String label = String.valueOf(manager.getApplicationLabel(info));
            long size = info.sourceDir == null ? 0L : new File(info.sourceDir).length();
            itemNames.put(item.toString(), label);
            itemTypes.put(item.toString(), info.packageName);
            itemSizes.put(item.toString(), size);
            String savedAction = savedActionFor(item);
            if ("Trash".equals(savedAction)) {
                trashPhotos.add(item);
            } else if ("Later".equals(savedAction)) {
                removeDecision(item);
                photos.add(item);
            } else if (savedAction.isEmpty()) {
                photos.add(item);
            }
        }
        photos.sort((left, right) -> Long.compare(
                itemSizes.getOrDefault(right.toString(), 0L),
                itemSizes.getOrDefault(left.toString(), 0L)));
    }

    private void scanDownloads() {
        ArrayList<MediaCandidate> candidates = new ArrayList<>();
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        scanDownloadsTree(downloads, candidates);
        candidates.sort((left, right) -> Long.compare(right.size, left.size));
        for (MediaCandidate candidate : candidates) {
            if (photos.size() >= 500) {
                break;
            }
            Uri item = candidate.uri;
            itemSizes.put(item.toString(), candidate.size);
            itemTypes.put(item.toString(), candidate.type);
            itemNames.put(item.toString(), candidate.name);
            String savedAction = savedActionFor(item);
            if ("Trash".equals(savedAction)) {
                trashPhotos.add(item);
            } else if ("Later".equals(savedAction)) {
                removeDecision(item);
                photos.add(item);
            } else if (savedAction.isEmpty()) {
                photos.add(item);
            }
        }
    }

    private void scanDownloadsTree(File directory, ArrayList<MediaCandidate> destination) {
        File[] children;
        try {
            children = directory.listFiles();
        } catch (SecurityException e) {
            return;
        }
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if (!child.getName().startsWith(".")) {
                    scanDownloadsTree(child, destination);
                }
            } else if (child.length() > 0L
                    && child.length() < BIG_FILE_MIN_BYTES
                    && !isPictureFile(child)
                    && !isVideoFile(child)) {
                destination.add(new MediaCandidate(
                        Uri.fromFile(child), child.length(), mimeTypeFor(child), child.getAbsolutePath()));
            }
        }
    }

    private void scanBigFiles() {
        ArrayList<MediaCandidate> candidates = new ArrayList<>();
        boolean canReadVideos = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
        if (canReadVideos) {
            Uri videos = Build.VERSION.SDK_INT >= 29
                    ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            scanMediaCollection(videos, "video", candidates);
        }
        if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) {
            scanFileTree(Environment.getExternalStorageDirectory(), candidates);
        }

        candidates.sort((left, right) -> Long.compare(right.size, left.size));
        for (MediaCandidate candidate : candidates) {
            if (photos.size() >= 500) {
                break;
            }
            Uri item = candidate.uri;
            itemSizes.put(item.toString(), candidate.size);
            itemTypes.put(item.toString(), candidate.type);
            itemNames.put(item.toString(), candidate.name);
            String savedAction = savedActionFor(item);
            if ("Trash".equals(savedAction)) {
                trashPhotos.add(item);
            } else if ("Later".equals(savedAction)) {
                removeDecision(item);
                photos.add(item);
            } else if (savedAction.isEmpty()) {
                photos.add(item);
            }
        }

        if (!canReadVideos) {
            showThemedNotice("Video access is required for Big Files.");
        }
    }

    private void scanFileTree(File directory, ArrayList<MediaCandidate> destination) {
        File[] children;
        try {
            children = directory.listFiles();
        } catch (SecurityException e) {
            return;
        }
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                String name = child.getName();
                if (!"Android".equalsIgnoreCase(name) && !name.startsWith(".")) {
                    scanFileTree(child, destination);
                }
            } else if (child.length() >= BIG_FILE_MIN_BYTES && !isPictureFile(child) && !isVideoFile(child)) {
                Uri uri = Uri.fromFile(child);
                String type = mimeTypeFor(child);
                destination.add(new MediaCandidate(uri, child.length(), type, child.getAbsolutePath()));
            }
        }
    }

    private boolean isPictureFile(File file) {
        String type = mimeTypeFor(file);
        return type.startsWith("image/");
    }

    private boolean isVideoFile(File file) {
        String type = mimeTypeFor(file);
        return type.startsWith("video/");
    }

    private String mimeTypeFor(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return type == null ? (extension.isEmpty() ? "file" : extension) : type;
    }

    private void scanMediaCollection(Uri collection, String fallbackType, ArrayList<MediaCandidate> destination) {
        String[] projection = new String[]{
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DISPLAY_NAME
        };
        try (Cursor cursor = getContentResolver().query(collection, projection, null, null,
                MediaStore.MediaColumns.SIZE + " DESC")) {
            if (cursor == null) {
                return;
            }
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int typeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
            int nameColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            while (cursor.moveToNext()) {
                Uri item = Uri.withAppendedPath(collection, String.valueOf(cursor.getLong(idColumn)));
                long size = Math.max(0L, cursor.getLong(sizeColumn));
                if (size < BIG_FILE_MIN_BYTES) {
                    continue;
                }
                String type = valueAt(cursor, typeColumn);
                if (type.isEmpty()) {
                    type = fallbackType;
                }
                destination.add(new MediaCandidate(item, size, type, valueAt(cursor, nameColumn)));
            }
        } catch (SecurityException ignored) {
            // A denied media category is skipped; the granted category still remains usable.
        } catch (Exception e) {
            showThemedNotice(fallbackType + " scan failed: " + e.getMessage());
        }
    }

    private void scanPhotos() {
        ContentResolver resolver = getContentResolver();
        Uri collection = Build.VERSION.SDK_INT >= 29
                ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection;
        if (Build.VERSION.SDK_INT >= 29) {
            projection = new String[]{
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Images.Media.RELATIVE_PATH
            };
        } else {
            projection = new String[]{
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Images.Media.DATA
            };
        }
        String sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC";

        try (Cursor cursor = resolver.query(collection, projection, null, null, sortOrder)) {
            if (cursor == null) {
                return;
            }
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int nameColumn = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
            int bucketColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
            int pathColumn = cursor.getColumnIndex(Build.VERSION.SDK_INT >= 29
                    ? MediaStore.Images.Media.RELATIVE_PATH
                    : MediaStore.Images.Media.DATA);
            while (cursor.moveToNext() && photos.size() < 500) {
                String name = valueAt(cursor, nameColumn);
                String bucket = valueAt(cursor, bucketColumn);
                String path = valueAt(cursor, pathColumn);
                if (isScreenshot(name, bucket, path)) {
                    continue;
                }
                long id = cursor.getLong(idColumn);
                Uri photo = Uri.withAppendedPath(collection, String.valueOf(id));
                String savedAction = savedActionFor(photo);
                if ("Trash".equals(savedAction)) {
                    trashPhotos.add(photo);
                } else if ("Later".equals(savedAction)) {
                    removeDecision(photo);
                    photos.add(photo);
                } else if (savedAction.isEmpty()) {
                    photos.add(photo);
                }
            }
        } catch (Exception e) {
            showThemedNotice("Photo scan failed: " + e.getMessage());
        }
    }

    private String valueAt(Cursor cursor, int column) {
        return column >= 0 && !cursor.isNull(column) ? cursor.getString(column) : "";
    }

    private boolean isScreenshot(String name, String bucket, String path) {
        String location = (name + " " + bucket + " " + path).toLowerCase(Locale.US);
        return location.contains("screenshot")
                || location.contains("screen shot")
                || location.contains("screenrecord")
                || location.contains("screen_record");
    }

    private void showReview() {
        screenMode = ScreenMode.REVIEW;
        stage.removeAllViews();
        stage.setBackgroundColor(Color.BLACK);

        if (reviewKind == ReviewKind.APPS) {
            ImageView appsBackdrop = new ImageView(this);
            appsBackdrop.setImageResource(R.drawable.apps_review_background);
            appsBackdrop.setScaleType(ImageView.ScaleType.FIT_CENTER);
            stage.addView(appsBackdrop, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            photoView = new ImageView(this);
            photoView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            FrameLayout.LayoutParams appIconParams = new FrameLayout.LayoutParams(
                    280, 280, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            stage.addView(photoView, appIconParams);
            stage.post(() -> {
                FrameLayout.LayoutParams placed = (FrameLayout.LayoutParams) photoView.getLayoutParams();
                placed.topMargin = Math.round(stage.getHeight() * 0.365f);
                photoView.setLayoutParams(placed);
            });
        } else {
            photoView = new ImageView(this);
            photoView.setBackgroundResource(R.drawable.review_fallback_background);
            photoView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            stage.addView(photoView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                zoomGesture = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                photoScale = Math.max(1f, Math.min(4f, photoScale * detector.getScaleFactor()));
                if (photoScale == 1f) {
                    photoTranslationX = 0f;
                    photoTranslationY = 0f;
                }
                clampAndApplyPhotoTransform();
                return true;
            }
        });

        reviewHud = new ImageView(this);
        reviewHud.setScaleType(ImageView.ScaleType.FIT_CENTER);
        reviewHud.setAdjustViewBounds(false);
        stage.addView(reviewHud, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        createFileDetailsPanel();

        View gestureLayer = new View(this);
        gestureLayer.setBackgroundColor(Color.TRANSPARENT);
        gestureLayer.setOnTouchListener(this::handleReviewTouch);
        stage.addView(gestureLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        reviewStatus = new TextView(this);
        reviewStatus.setTextColor(Color.WHITE);
        reviewStatus.setTextSize(13f);
        reviewStatus.setGravity(Gravity.CENTER);
        reviewStatus.setTypeface(Typeface.DEFAULT_BOLD);
        reviewStatus.setShadowLayer(12f, 0f, 0f, Color.BLACK);
        reviewStatus.setContentDescription("Open Trash Review Bin");
        reviewStatus.setOnClickListener(v -> {
            if (!trashPhotos.isEmpty()) {
                showReviewBin();
            }
        });
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        statusParams.setMargins(24, 0, 24, 18);
        stage.addView(reviewStatus, statusParams);

        reviewBinButton = new TextView(this);
        reviewBinButton.setTextColor(Color.WHITE);
        reviewBinButton.setTextSize(14f);
        reviewBinButton.setGravity(Gravity.CENTER);
        reviewBinButton.setTypeface(Typeface.DEFAULT_BOLD);
        reviewBinButton.setPadding(24, 14, 24, 14);
        reviewBinButton.setContentDescription("Open Trash Review Bin");
        GradientDrawable binBackground = new GradientDrawable();
        binBackground.setColor(Color.argb(220, 58, 12, 70));
        binBackground.setCornerRadius(28f);
        binBackground.setStroke(3, Color.rgb(255, 75, 130));
        reviewBinButton.setBackground(binBackground);
        reviewBinButton.setElevation(18f);
        reviewBinButton.setOnClickListener(v -> showReviewBin());
        FrameLayout.LayoutParams binParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END
        );
        binParams.setMargins(24, 28, 24, 0);
        stage.addView(reviewBinButton, binParams);

        reviewExitButton = makeOverlayButton("EXIT REVIEW", Color.rgb(100, 205, 255));
        reviewExitButton.setContentDescription("Pause and exit photo review");
        reviewExitButton.setOnClickListener(v -> showStartup());
        FrameLayout.LayoutParams exitParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL
        );
        exitParams.setMargins(0, 28, 0, 0);
        stage.addView(reviewExitButton, exitParams);

        actionFeedback = new TextView(this);
        actionFeedback.setTextColor(Color.WHITE);
        actionFeedback.setTextSize(24f);
        actionFeedback.setGravity(Gravity.CENTER);
        actionFeedback.setTypeface(Typeface.DEFAULT_BOLD);
        actionFeedback.setPadding(34, 22, 34, 22);
        actionFeedback.setVisibility(View.INVISIBLE);
        actionFeedback.setElevation(24f);
        FrameLayout.LayoutParams feedbackParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        stage.addView(actionFeedback, feedbackParams);

        updateReviewHud();
        updateReviewBinButton();
        showCurrentPhoto();
        enterImmersiveMode();
    }

    private boolean handleReviewTouch(View view, MotionEvent event) {
        boolean transformEnabled = reviewKind != ReviewKind.DOWNLOADS
                && reviewKind != ReviewKind.APPS;
        if (transformEnabled && scaleDetector != null) {
            scaleDetector.onTouchEvent(event);
        }
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                stepBackCandidate = isInsideStepBack(event.getX(), event.getY());
                lastDragX = event.getX();
                lastDragY = event.getY();
                zoomGesture = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (transformEnabled && event.getPointerCount() > 1) {
                    zoomGesture = true;
                    return true;
                }
                if (transformEnabled && photoScale > 1f) {
                    photoTranslationX += event.getX() - lastDragX;
                    photoTranslationY += event.getY() - lastDragY;
                    lastDragX = event.getX();
                    lastDragY = event.getY();
                    zoomGesture = true;
                    clampAndApplyPhotoTransform();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (transformEnabled && (zoomGesture || photoScale > 1f)) {
                    return true;
                }
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (stepBackCandidate && Math.abs(dx) < TAP_SLOP && Math.abs(dy) < TAP_SLOP) {
                    undoDecision();
                    return true;
                }
                if (Math.abs(dx) < SWIPE_THRESHOLD && Math.abs(dy) < SWIPE_THRESHOLD) {
                    return true;
                }
                if (Math.abs(dx) > Math.abs(dy)) {
                    recordDecision(dx > 0 ? "Trash" : "Keep");
                } else {
                    recordDecision(dy > 0 ? "Later" : "Protect");
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                stepBackCandidate = false;
                return true;
            default:
                return true;
        }
    }

    private boolean isInsideStepBack(float x, float y) {
        int orientation = getResources().getConfiguration().orientation;
        float width = stage.getWidth();
        float height = stage.getHeight();
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            return x < width * 0.16f && y < height * 0.28f;
        }
        return x < width * 0.24f && y < height * 0.18f;
    }

    private void recordDecision(String action) {
        if (photos.isEmpty() || photoIndex >= photos.size()) {
            return;
        }
        decisions.add(new Decision(photoIndex, action));
        stage.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        Uri decidedPhoto = photos.get(photoIndex);
        saveDecision(decidedPhoto, action);
        if ("Trash".equals(action) && !trashPhotos.contains(decidedPhoto)) {
            trashPhotos.add(decidedPhoto);
        }
        updateReviewBinButton();
        photoIndex++;
        showActionFeedback(action);
        uiHandler.postDelayed(this::showCurrentPhoto, 180);
    }

    private void undoDecision() {
        if (decisions.isEmpty()) {
            updateReviewStatus("Nothing to step back yet.");
            return;
        }
        Decision decision = decisions.remove(decisions.size() - 1);
        stage.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        Uri restoredPhoto = photos.get(decision.index);
        removeDecision(restoredPhoto);
        trashPhotos.remove(restoredPhoto);
        updateReviewBinButton();
        photoIndex = decision.index;
        showCurrentPhoto();
        updateReviewStatus("Stepped back from " + decision.action + ".");
    }

    private void showCurrentPhoto() {
        if (photos.isEmpty()) {
            photoView.setImageDrawable(null);
            hideFileDetails();
            reviewStatus.setVisibility(View.VISIBLE);
            updateReviewStatus(reviewKind == ReviewKind.BIG_FILES
                    ? "No files over 100 MB found."
                    : reviewKind == ReviewKind.DOWNLOADS
                    ? "No smaller non-media files found in Downloads."
                    : reviewKind == ReviewKind.APPS
                    ? "No launchable user apps found."
                    : "No photos found.");
            return;
        }
        if (photoIndex >= photos.size()) {
            photoView.setImageDrawable(null);
            hideFileDetails();
            reviewStatus.setVisibility(View.VISIBLE);
            updateReviewStatus("Review complete. This session: " + decisions.size()
                    + "   Review Bin: " + trashPhotos.size());
            return;
        }
        resetPhotoTransform();
        Uri current = photos.get(photoIndex);
        showItem(current, photoView, 1200, 1200);
        if (reviewKind == ReviewKind.BIG_FILES || reviewKind == ReviewKind.DOWNLOADS
                || reviewKind == ReviewKind.APPS) {
            updateFileDetails(current);
            reviewStatus.setVisibility(View.GONE);
            String type = itemTypes.getOrDefault(current.toString(), "file");
            if (reviewKind == ReviewKind.APPS) {
                type = "app";
            } else if (type.startsWith("video/")) {
                type = "video";
            } else if (type.startsWith("image/")) {
                type = "image";
            }
            String location = itemNames.getOrDefault(current.toString(), "");
            String name = location.isEmpty() ? "" : new File(location).getName() + "   ";
            updateReviewStatus((photoIndex + 1) + " / " + photos.size()
                    + "   " + formatSize(itemSizes.getOrDefault(current.toString(), 0L))
                    + " " + type + "   " + name
                    + "   This session: " + decisions.size()
                    + "   Review Bin: " + trashPhotos.size());
        } else {
            hideFileDetails();
            reviewStatus.setVisibility(View.VISIBLE);
            updateReviewStatus((photoIndex + 1) + " / " + photos.size()
                    + "   This session: " + decisions.size()
                    + "   Review Bin: " + trashPhotos.size());
        }
    }

    private void loadPersistedDecisions() {
        persistedDecisions.clear();
        trashPhotos.clear();
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        persistedDecisions.addAll(preferences.getStringSet(PREF_DECISIONS, new HashSet<>()));
    }

    private String savedActionFor(Uri photo) {
        String prefix = photo.toString() + "|";
        for (String entry : persistedDecisions) {
            if (entry.startsWith(prefix)) {
                return entry.substring(prefix.length());
            }
        }
        return "";
    }

    private void saveDecision(Uri photo, String action) {
        removeDecisionFromMemory(photo);
        persistedDecisions.add(photo.toString() + "|" + action);
        persistDecisionSet();
    }

    private void removeDecision(Uri photo) {
        removeDecisionFromMemory(photo);
        persistDecisionSet();
    }

    private void removeDecisionFromMemory(Uri photo) {
        String prefix = photo.toString() + "|";
        persistedDecisions.removeIf(entry -> entry.startsWith(prefix));
    }

    private void persistDecisionSet() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putStringSet(PREF_DECISIONS, new HashSet<>(persistedDecisions))
                .apply();
    }

    private void showReviewBin() {
        screenMode = ScreenMode.REVIEW_BIN;
        stage.removeAllViews();
        stage.setBackgroundColor(Color.rgb(18, 12, 22));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(28, 42, 28, 56);

        TextView title = makeBinText("Trash Review Bin", 25f);
        title.setTextColor(Color.rgb(255, 75, 130));
        list.addView(title);

        TextView safety = makeBinText("Nothing here has been deleted. Restore an item, or choose Delete and approve Android's confirmation.", 15f);
        safety.setPadding(0, 12, 0, 24);
        list.addView(safety);

        ArrayList<Uri> snapshot = new ArrayList<>(trashPhotos);
        for (Uri photo : snapshot) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 8, 0, 8);

            ImageView thumbnail = new ImageView(this);
            showItem(photo, thumbnail, 360, 360);
            if (!"package".equals(photo.getScheme())) {
                thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            }
            row.addView(thumbnail, new LinearLayout.LayoutParams(180, 180));

            TextView restore = makeBinText("RESTORE", 16f);
            restore.setGravity(Gravity.CENTER);
            restore.setTextColor(Color.rgb(85, 255, 175));
            restore.setContentDescription("Restore photo");
            restore.setOnClickListener(v -> {
                stage.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                removeDecision(photo);
                trashPhotos.remove(photo);
                if (!photos.contains(photo)) {
                    photos.add(Math.min(photoIndex, photos.size()), photo);
                }
                showReviewBin();
            });
            LinearLayout.LayoutParams restoreParams = new LinearLayout.LayoutParams(0, 180, 1f);
            restoreParams.setMargins(24, 0, 0, 0);
            row.addView(restore, restoreParams);

            TextView delete = makeBinText("DELETE", 16f);
            delete.setGravity(Gravity.CENTER);
            delete.setTextColor(Color.rgb(255, 75, 130));
            delete.setContentDescription("Permanently delete photo");
            delete.setOnClickListener(v -> confirmDeletion(photo));
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, 180, 1f);
            deleteParams.setMargins(12, 0, 0, 0);
            row.addView(delete, deleteParams);
            list.addView(row);
        }

        if (snapshot.isEmpty()) {
            TextView empty = makeBinText("Your review bin is empty.", 17f);
            empty.setPadding(0, 40, 0, 40);
            list.addView(empty);
        } else if (reviewKind != ReviewKind.APPS) {
            TextView deleteAll = makeBinText("DELETE ALL (" + snapshot.size() + ")", 17f);
            deleteAll.setTextColor(Color.rgb(255, 75, 130));
            deleteAll.setGravity(Gravity.CENTER);
            deleteAll.setPadding(0, 30, 0, 24);
            deleteAll.setContentDescription("Permanently delete every photo in Review Bin");
            deleteAll.setOnClickListener(v -> confirmDeleteAll());
            list.addView(deleteAll);
        }

        TextView back = makeBinText("BACK TO REVIEW", 17f);
        back.setTextColor(Color.rgb(190, 90, 255));
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, 36, 0, 36);
        back.setOnClickListener(v -> showReview());
        list.addView(back);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        stage.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        enterImmersiveMode();
    }

    private void confirmDeletion(Uri photo) {
        if ("package".equals(photo.getScheme())) {
            String label = itemNames.getOrDefault(photo.toString(), "this app");
            showThemedDialog(new AlertDialog.Builder(this)
                    .setTitle("Uninstall " + label + "?")
                    .setMessage("Touch Clean will open Android's uninstall confirmation. Nothing is removed unless you approve it there.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open confirmation", (dialog, which) -> requestAppUninstall(photo)));
            return;
        }
        showThemedDialog(new AlertDialog.Builder(this)
                .setTitle("Permanently delete this photo?")
                .setMessage("This cannot be undone. Media items also require Android's deletion approval.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (dialog, which) -> requestPermanentDeletion(photo)));
    }

    private void confirmDeleteAll() {
        int count = trashPhotos.size();
        if (count == 0) {
            return;
        }
        showThemedDialog(new AlertDialog.Builder(this)
                .setTitle("Permanently delete all " + count + " photos?")
                .setMessage("This cannot be undone. Media items also require Android's deletion approval.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (dialog, which) -> requestPermanentDeletion(new ArrayList<>(trashPhotos))));
    }

    private void requestPermanentDeletion(Uri photo) {
        ArrayList<Uri> selection = new ArrayList<>();
        selection.add(photo);
        requestPermanentDeletion(selection);
    }

    private void requestPermanentDeletion(ArrayList<Uri> selection) {
        if (selection.isEmpty()) {
            return;
        }
        pendingDeletion.clear();
        pendingDeletion.addAll(selection);
        ArrayList<Uri> mediaSelection = new ArrayList<>();
        for (Uri item : selection) {
            if ("content".equals(item.getScheme())) {
                mediaSelection.add(item);
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            if (mediaSelection.isEmpty()) {
                finishApprovedDeletion();
                showThemedNotice("File deletion complete.");
                showReviewBin();
                return;
            }
            try {
                IntentSender sender = MediaStore.createDeleteRequest(getContentResolver(), mediaSelection).getIntentSender();
                startIntentSenderForResult(sender, REQUEST_DELETE_PHOTOS, null, 0, 0, 0);
            } catch (IntentSender.SendIntentException | RuntimeException e) {
                pendingDeletion.clear();
                showThemedNotice("Could not open Android deletion approval: " + e.getMessage());
            }
            return;
        }

        try {
            for (Uri photo : new ArrayList<>(pendingDeletion)) {
                getContentResolver().delete(photo, null, null);
            }
            finishApprovedDeletion();
            showThemedNotice("Photo deletion complete.");
            showReviewBin();
        } catch (SecurityException e) {
            pendingDeletion.clear();
            showThemedNotice("Android did not allow deletion of this photo.");
        }
    }

    private void requestAppUninstall(Uri app) {
        pendingAppUninstall = app;
        Intent uninstall = new Intent(Intent.ACTION_DELETE, app);
        uninstall.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        try {
            startActivityForResult(uninstall, REQUEST_UNINSTALL_APP);
        } catch (Exception e) {
            pendingAppUninstall = null;
            showThemedNotice("Android could not open uninstall confirmation.");
        }
    }

    private void finishAppUninstall() {
        if (pendingAppUninstall == null) {
            return;
        }
        Uri app = pendingAppUninstall;
        pendingAppUninstall = null;
        String packageName = app.getSchemeSpecificPart();
        boolean installed = true;
        try {
            getPackageManager().getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException ignored) {
            installed = false;
        }
        if (installed) {
            showThemedNotice("Uninstall cancelled. App remains in Review Bin.");
        } else {
            removeDecision(app);
            trashPhotos.remove(app);
            photos.remove(app);
            itemSizes.remove(app.toString());
            itemTypes.remove(app.toString());
            itemNames.remove(app.toString());
            updateReviewBinButton();
            showThemedNotice("App uninstalled.");
        }
        if (screenMode == ScreenMode.REVIEW_BIN) {
            showReviewBin();
        } else {
            showCurrentPhoto();
        }
    }

    private void finishApprovedDeletion() {
        for (Uri item : new ArrayList<>(pendingDeletion)) {
            boolean deleted = true;
            if ("file".equals(item.getScheme())) {
                String path = item.getPath();
                deleted = path != null && new File(path).delete();
            }
            if (deleted) {
                removeDecisionFromMemory(item);
                trashPhotos.remove(item);
                photos.remove(item);
                itemSizes.remove(item.toString());
                itemTypes.remove(item.toString());
                itemNames.remove(item.toString());
            } else {
                showThemedNotice("Android could not delete " + itemNames.getOrDefault(item.toString(), "this file"));
            }
        }
        persistDecisionSet();
        pendingDeletion.clear();
        if (photoIndex > photos.size()) {
            photoIndex = photos.size();
        }
    }

    private void resetPhotoTransform() {
        photoScale = 1f;
        photoTranslationX = 0f;
        photoTranslationY = 0f;
        zoomGesture = false;
        if (photoView != null) {
            photoView.setScaleX(1f);
            photoView.setScaleY(1f);
            photoView.setTranslationX(0f);
            photoView.setTranslationY(0f);
        }
    }

    private void clampAndApplyPhotoTransform() {
        if (photoView == null) {
            return;
        }
        float maxX = Math.max(0f, (photoScale - 1f) * photoView.getWidth() / 2f);
        float maxY = Math.max(0f, (photoScale - 1f) * photoView.getHeight() / 2f);
        photoTranslationX = Math.max(-maxX, Math.min(maxX, photoTranslationX));
        photoTranslationY = Math.max(-maxY, Math.min(maxY, photoTranslationY));
        photoView.setScaleX(photoScale);
        photoView.setScaleY(photoScale);
        photoView.setTranslationX(photoTranslationX);
        photoView.setTranslationY(photoTranslationY);
    }

    private void showItem(Uri item, ImageView target, int width, int height) {
        try {
            String type = itemTypes.getOrDefault(item.toString(), "");
            if ("package".equals(item.getScheme())) {
                target.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                target.setPadding(10, 10, 10, 10);
                target.setImageDrawable(getPackageManager().getApplicationIcon(type));
            } else if ((reviewKind == ReviewKind.BIG_FILES || reviewKind == ReviewKind.DOWNLOADS)
                    && "file".equals(item.getScheme())) {
                target.setScaleType(ImageView.ScaleType.FIT_CENTER);
                target.setImageResource(getResources().getIdentifier(
                        reviewKind == ReviewKind.DOWNLOADS
                                ? "downloads_review_background"
                                : "big_files_review_background",
                        "drawable", getPackageName()));
            } else if (Build.VERSION.SDK_INT >= 29 && type.startsWith("video/")) {
                target.setScaleType(ImageView.ScaleType.CENTER_CROP);
                Bitmap thumbnail = getContentResolver().loadThumbnail(item, new Size(width, height), null);
                target.setImageBitmap(thumbnail);
            } else {
                target.setScaleType(ImageView.ScaleType.CENTER_CROP);
                target.setImageURI(item);
            }
        } catch (Exception e) {
            target.setImageDrawable(null);
        }
    }

    private void createFileDetailsPanel() {
        fileDetailsPanel = new LinearLayout(this);
        fileDetailsPanel.setOrientation(LinearLayout.VERTICAL);
        fileDetailsPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        fileDetailsPanel.setPadding(18, 6, 18, 10);
        fileDetailsPanel.setVisibility(View.GONE);
        fileDetailsPanel.setElevation(20f);

        fileTypeText = makeFileDetailText(15f, Color.WHITE, true);
        fileTypeText.setPadding(28, 7, 28, 7);
        GradientDrawable badge = new GradientDrawable();
        badge.setColor(Color.argb(225, 160, 5, 100));
        badge.setCornerRadius(22f);
        badge.setStroke(2, Color.rgb(255, 70, 190));
        fileTypeText.setBackground(badge);
        fileDetailsPanel.addView(fileTypeText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        fileSizeText = makeFileDetailText(31f, Color.WHITE, true);
        fileSizeText.setShadowLayer(14f, 0f, 0f, Color.rgb(75, 210, 255));
        fileSizeText.setPadding(0, 14, 0, 2);
        fileDetailsPanel.addView(fileSizeText);

        fileNameText = makeFileDetailText(15.5f, Color.WHITE, true);
        fileNameText.setMaxLines(3);
        fileNameText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (Build.VERSION.SDK_INT >= 23) {
            fileNameText.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_BALANCED);
            fileNameText.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        }
        fileNameText.setPadding(0, 4, 0, 6);
        fileDetailsPanel.addView(fileNameText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        fileLocationText = makeFileDetailText(14f, Color.rgb(175, 190, 225), false);
        fileDetailsPanel.addView(fileLocationText);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        stage.addView(fileDetailsPanel, params);
        stage.post(() -> {
            FrameLayout.LayoutParams placed = (FrameLayout.LayoutParams) fileDetailsPanel.getLayoutParams();
            placed.width = Math.round(stage.getWidth() * 0.58f);
            placed.leftMargin = 0;
            placed.topMargin = Math.round(stage.getHeight() * 0.455f);
            fileDetailsPanel.setLayoutParams(placed);
        });
    }

    private TextView makeFileDetailText(float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setLetterSpacing(bold ? 0.025f : 0.015f);
        return view;
    }

    private void updateFileDetails(Uri item) {
        if (fileDetailsPanel == null) {
            return;
        }
        String storedName = itemNames.getOrDefault(item.toString(), "File");
        if ("package".equals(item.getScheme())) {
            fileTypeText.setText("APP");
            fileSizeText.setText(formatSize(itemSizes.getOrDefault(item.toString(), 0L)));
            fileNameText.setText(storedName);
            fileLocationText.setText(itemTypes.getOrDefault(item.toString(), "Installed app")
                    + "   •   " + (photoIndex + 1) + " of " + photos.size());
            fileDetailsPanel.setVisibility(View.VISIBLE);
            return;
        }
        File storedFile = new File(storedName);
        String displayName = "file".equals(item.getScheme()) ? storedFile.getName() : storedName;
        displayName = displayName.replaceFirst("^\\.trashed-\\d+-", "");
        String location = "file".equals(item.getScheme()) && storedFile.getParentFile() != null
                ? storedFile.getParentFile().getName()
                : "Media Library";
        int dot = displayName.lastIndexOf('.');
        String extension = dot >= 0 && dot < displayName.length() - 1
                ? displayName.substring(dot + 1).toUpperCase(Locale.US)
                : itemTypes.getOrDefault(item.toString(), "FILE").toUpperCase(Locale.US);
        if (extension.length() > 8) {
            extension = "FILE";
        }
        fileTypeText.setText(extension);
        fileSizeText.setText(formatSize(itemSizes.getOrDefault(item.toString(), 0L)));
        String displayBaseName = dot > 0 ? displayName.substring(0, dot) : displayName;
        fileNameText.setText(displayBaseName);
        fileLocationText.setText(location + "   •   " + (photoIndex + 1) + " of " + photos.size());
        fileDetailsPanel.setVisibility(View.VISIBLE);
    }

    private void hideFileDetails() {
        if (fileDetailsPanel != null) {
            fileDetailsPanel.setVisibility(View.GONE);
        }
    }

    private String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.2f GB", bytes / (1024d * 1024d * 1024d));
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024d * 1024d));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024d);
        }
        return bytes + " B";
    }

    private TextView makeBinText(String text, float size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(size);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private void updateReviewStatus(String message) {
        if (reviewStatus != null) {
            reviewStatus.setText(message);
        }
    }

    private void updateReviewBinButton() {
        if (reviewBinButton != null) {
            reviewBinButton.setText("REVIEW BIN (" + trashPhotos.size() + ")");
        }
    }

    private TextView makeOverlayButton(String text, int accentColor) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14f);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setPadding(24, 14, 24, 14);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(220, 24, 10, 38));
        background.setCornerRadius(28f);
        background.setStroke(3, accentColor);
        button.setBackground(background);
        button.setElevation(18f);
        return button;
    }

    private void showActionFeedback(String action) {
        if (actionFeedback == null) {
            return;
        }
        int accent;
        String message;
        switch (action) {
            case "Trash":
                accent = Color.rgb(255, 75, 130);
                message = "TRASH → REVIEW BIN";
                break;
            case "Keep":
                accent = Color.rgb(85, 255, 175);
                message = "KEEP";
                break;
            case "Protect":
                accent = Color.rgb(100, 205, 255);
                message = "PROTECT";
                break;
            default:
                accent = Color.rgb(190, 90, 255);
                message = "LATER";
                break;
        }
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(230, 20, 8, 30));
        background.setCornerRadius(34f);
        background.setStroke(4, accent);
        actionFeedback.setBackground(background);
        actionFeedback.setText(message);
        actionFeedback.setAlpha(1f);
        actionFeedback.setScaleX(0.92f);
        actionFeedback.setScaleY(0.92f);
        actionFeedback.setVisibility(View.VISIBLE);
        actionFeedback.animate().scaleX(1f).scaleY(1f).alpha(0f).setDuration(430)
                .withEndAction(() -> actionFeedback.setVisibility(View.INVISIBLE)).start();
    }

    private void confirmGlobalReset() {
        showThemedDialog(new AlertDialog.Builder(this)
                .setTitle("Reset Touch Clean search?")
                .setMessage("This returns existing photos and files removed by Keep, Trash, Protect, or Later to their Touch Clean searches. Items already deleted from your device stay deleted and are not restored.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset search", (dialog, which) -> {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .remove(PREF_DECISIONS)
                            .apply();
                    persistedDecisions.clear();
                    trashPhotos.clear();
                    decisions.clear();
                    photoIndex = 0;
                    showThemedNotice("Existing items restored to search");
                    String permission = photoPermission();
                    if (Build.VERSION.SDK_INT < 23
                            || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                        startPhotoReview();
                    } else {
                        status.setText("Search reset. Tap Photos and allow access to rebuild results.");
                    }
                }));
    }

    private void showThemedDialog(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            GradientDrawable shell = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{Color.rgb(24, 7, 38), Color.rgb(8, 16, 34)});
            shell.setCornerRadius(34f);
            shell.setStroke(3, Color.rgb(205, 55, 255));
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(shell);
                dialog.getWindow().setDimAmount(0.72f);
            }
            int titleId = getResources().getIdentifier("alertTitle", "id", "android");
            TextView title = dialog.findViewById(titleId);
            if (title != null) {
                title.setTextColor(Color.WHITE);
                title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            }
            TextView message = dialog.findViewById(android.R.id.message);
            if (message != null) {
                message.setTextColor(Color.rgb(225, 220, 242));
                message.setTextSize(16f);
                message.setLineSpacing(0f, 1.12f);
            }
            TextView positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            TextView negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (positive != null) {
                positive.setTextColor(Color.rgb(255, 75, 190));
                positive.setTypeface(Typeface.DEFAULT_BOLD);
            }
            if (negative != null) {
                negative.setTextColor(Color.rgb(90, 215, 255));
                negative.setTypeface(Typeface.DEFAULT_BOLD);
            }
        });
        dialog.show();
    }

    private void showThemedNotice(String message) {
        if (stage == null) {
            return;
        }
        TextView notice = new TextView(this);
        notice.setText("✦  " + message);
        notice.setTextColor(Color.WHITE);
        notice.setTextSize(15f);
        notice.setGravity(Gravity.CENTER);
        notice.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        notice.setPadding(30, 18, 30, 18);
        notice.setElevation(30f);
        GradientDrawable shell = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.argb(245, 30, 8, 48), Color.argb(245, 7, 30, 48)});
        shell.setCornerRadius(40f);
        shell.setStroke(3, Color.rgb(205, 55, 255));
        notice.setBackground(shell);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.setMargins(34, 0, 34, Math.round(stage.getHeight() * 0.09f));
        stage.addView(notice, params);
        notice.setAlpha(0f);
        notice.setScaleX(0.96f);
        notice.setScaleY(0.96f);
        notice.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();
        uiHandler.postDelayed(() -> notice.animate().alpha(0f).setDuration(220)
                .withEndAction(() -> {
                    if (notice.getParent() == stage) {
                        stage.removeView(notice);
                    }
                }).start(), 2600);
    }

    private void updateReviewHud() {
        if (reviewHud == null) {
            return;
        }
        int orientation = getResources().getConfiguration().orientation;
        reviewHud.setImageResource(orientation == Configuration.ORIENTATION_LANDSCAPE
                ? R.drawable.review_landscape_hud_normal
                : R.drawable.review_portrait_hud_normal);
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private ImageView addFullScreenImage(String resourceName, boolean visible) {
        ImageView image = new ImageView(this);
        int id = getResources().getIdentifier(resourceName, "drawable", getPackageName());
        image.setImageResource(id);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(false);
        image.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        stage.addView(image, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        return image;
    }

    private void addHitZone(String description, float x, float y, float w, float h, Runnable action) {
        View hit = new View(this);
        hit.setContentDescription(description);
        hit.setBackgroundColor(Color.TRANSPARENT);
        hit.setOnClickListener(v -> action.run());
        hit.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                v.setAlpha(0.35f);
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                v.setAlpha(1f);
            }
            return false;
        });

        stage.addView(hit, scaledParams(x, y, w, h));
    }

    private FrameLayout.LayoutParams scaledParams(float x, float y, float w, float h) {
        float scale = Math.min(
                getResources().getDisplayMetrics().widthPixels / DESIGN_W,
                getResources().getDisplayMetrics().heightPixels / DESIGN_H
        );
        int designLeftInset = Math.round((getResources().getDisplayMetrics().widthPixels - DESIGN_W * scale) / 2f);
        int designTopInset = Math.round((getResources().getDisplayMetrics().heightPixels - DESIGN_H * scale) / 2f);

        int width = Math.round(w * scale);
        int height = Math.round(h * scale);
        int left = designLeftInset + Math.round(x * scale);
        int top = designTopInset + Math.round(y * scale);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = left;
        params.topMargin = top;
        return params;
    }

    private void toggleMode() {
        storageMode = !storageMode;

        setVisible(aMediaTab, !storageMode);
        setVisible(aStorageTab, false);
        setVisible(aPhotos, !storageMode);
        setVisible(aSimilar, !storageMode);
        setVisible(aVideos, !storageMode);
        setVisible(aScreenshots, !storageMode);
        setVisible(aBottomTray, !storageMode);

        setVisible(bMediaTab, false);
        setVisible(bStorageTab, storageMode);
        setVisible(bBigFiles, storageMode);
        setVisible(bOldFiles, storageMode);
        setVisible(bDownloads, storageMode);
        setVisible(bApps, storageMode);
        setVisible(bBottomTray, storageMode);

        status.setText(storageMode
                ? "Storage in center. Tap bottom tray to swap."
                : "Media in center. Tap bottom tray to swap.");

        pulse(storageMode ? bStorageTab : aMediaTab);
    }

    private void setVisible(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    private void pressCenter(View target, String message) {
        status.setText(message);
        pulse(target);
    }

    private void pulse(View target) {
        target.setVisibility(View.VISIBLE);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(target, "alpha", 1f, 0.72f, 1f);
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(target, "scaleX", 0.985f, 1.025f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(target, "scaleY", 0.985f, 1.025f, 1f);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(alpha, scaleX, scaleY);
        set.setDuration(260);
        set.start();
    }
}
