package com.simplemobiletools.gallery.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.support.design.widget.CoordinatorLayout;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.SparseBooleanArray;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.GridView;
import android.widget.Toast;

import com.simplemobiletools.gallery.Constants;
import com.simplemobiletools.gallery.models.Medium;
import com.simplemobiletools.gallery.R;
import com.simplemobiletools.gallery.Utils;
import com.simplemobiletools.gallery.adapters.MediaAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import butterknife.BindView;
import butterknife.ButterKnife;

public class MediaActivity extends AppCompatActivity
        implements AdapterView.OnItemClickListener, GridView.MultiChoiceModeListener, MediaScannerConnection.OnScanCompletedListener,
        GridView.OnTouchListener {
    @BindView(R.id.media_grid) GridView gridView;

    private static final int STORAGE_PERMISSION = 1;
    private List<Medium> media;
    private int selectedItemsCnt;
    private String path;
    private Snackbar snackbar;
    private boolean isSnackbarShown;
    private List<String> toBeDeleted;
    private Parcelable state;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media);
        ButterKnife.bind(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        tryloadGallery();
        if (state != null && gridView != null)
            gridView.onRestoreInstanceState(state);
    }

    @Override
    protected void onPause() {
        super.onPause();
        deleteFiles();
        if (gridView != null)
            state = gridView.onSaveInstanceState();
    }

    private void tryloadGallery() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            initializeGallery();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, STORAGE_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeGallery();
            } else {
                Toast.makeText(this, getResources().getString(R.string.no_permissions), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void initializeGallery() {
        toBeDeleted = new ArrayList<>();
        path = getIntent().getStringExtra(Constants.DIRECTORY);
        media = getMedia();
        if (isDirEmpty())
            return;

        final MediaAdapter adapter = new MediaAdapter(this, media);
        gridView.setAdapter(adapter);
        gridView.setOnItemClickListener(this);
        gridView.setMultiChoiceModeListener(this);
        gridView.setOnTouchListener(this);
        isSnackbarShown = false;

        final String dirName = Utils.getFilename(path);
        setTitle(dirName);
    }

    private void deleteDirectoryIfEmpty() {
        final File file = new File(path);
        if (file.isDirectory() && file.listFiles().length == 0) {
            file.delete();
        }
    }

    private List<Medium> getMedia() {
        final List<Medium> myMedia = new ArrayList<>();
        final List<String> invalidFiles = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            if (i == 1) {
                uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            }
            final String where = MediaStore.Images.Media.DATA + " like ? ";
            final String[] args = new String[]{path + "%"};
            final String[] columns = {MediaStore.Images.Media.DATA};
            final String order = MediaStore.Images.Media.DATE_MODIFIED + " DESC";
            final Cursor cursor = getContentResolver().query(uri, columns, where, args, order);
            final String pattern = Pattern.quote(path) + "/[^/]*";

            if (cursor != null && cursor.moveToFirst()) {
                final int pathIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA);
                do {
                    final String curPath = cursor.getString(pathIndex);
                    if (curPath.matches(pattern) && !toBeDeleted.contains(curPath)) {
                        final File file = new File(curPath);
                        if (file.exists()) {
                            myMedia.add(new Medium(curPath, (i == 1)));
                        } else {
                            invalidFiles.add(file.getAbsolutePath());
                        }
                    }
                } while (cursor.moveToNext());
                cursor.close();
            }
        }

        final String[] invalids = invalidFiles.toArray(new String[invalidFiles.size()]);
        MediaScannerConnection.scanFile(getApplicationContext(), invalids, null, null);

        return myMedia;
    }

    private boolean isDirEmpty() {
        if (media.size() <= 0) {
            deleteDirectoryIfEmpty();
            finish();
            return true;
        }
        return false;
    }

    private void prepareForDeleting() {
        Utils.showToast(this, R.string.deleting);
        final SparseBooleanArray items = gridView.getCheckedItemPositions();
        int cnt = items.size();
        int deletedCnt = 0;
        for (int i = 0; i < cnt; i++) {
            if (items.valueAt(i)) {
                final int id = items.keyAt(i);
                final String path = media.get(id).getPath();
                toBeDeleted.add(path);
                deletedCnt++;
            }
        }

        notifyDeletion(deletedCnt);
    }

    private void notifyDeletion(int cnt) {
        media = getMedia();

        if (media.isEmpty()) {
            deleteFiles();
        } else {
            final CoordinatorLayout coordinator = (CoordinatorLayout) findViewById(R.id.coordinator_layout);
            final Resources res = getResources();
            final String msg = res.getQuantityString(R.plurals.files_deleted, cnt, cnt);
            snackbar = Snackbar.make(coordinator, msg, Snackbar.LENGTH_INDEFINITE);
            snackbar.setAction(res.getString(R.string.undo), undoDeletion);
            snackbar.setActionTextColor(Color.WHITE);
            snackbar.show();
            isSnackbarShown = true;
            updateGridView();
        }
    }

    private void deleteFiles() {
        if (toBeDeleted.isEmpty())
            return;

        if (snackbar != null) {
            snackbar.dismiss();
        }

        isSnackbarShown = false;

        for (String delPath : toBeDeleted) {
            final File file = new File(delPath);
            if (file.exists())
                file.delete();
        }

        final String[] deletedPaths = toBeDeleted.toArray(new String[toBeDeleted.size()]);
        MediaScannerConnection.scanFile(this, deletedPaths, null, this);
        toBeDeleted.clear();
    }

    private View.OnClickListener undoDeletion = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            snackbar.dismiss();
            isSnackbarShown = false;
            toBeDeleted.clear();
            media = getMedia();
            updateGridView();
        }
    };

    private void updateGridView() {
        if (!isDirEmpty()) {
            final MediaAdapter adapter = (MediaAdapter) gridView.getAdapter();
            adapter.updateItems(media);
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        final Intent intent = new Intent(this, ViewPagerActivity.class);
        intent.putExtra(Constants.MEDIUM, media.get(position).getPath());
        startActivity(intent);
    }

    @Override
    public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
        if (checked)
            selectedItemsCnt++;
        else
            selectedItemsCnt--;

        if (selectedItemsCnt > 0)
            mode.setTitle(String.valueOf(selectedItemsCnt));

        mode.invalidate();
    }

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        final MenuInflater inflater = mode.getMenuInflater();
        inflater.inflate(R.menu.media_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        return false;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        switch (item.getItemId()) {
            case R.id.cab_delete:
                prepareForDeleting();
                mode.finish();
                return true;
            default:
                return false;
        }
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        selectedItemsCnt = 0;
    }

    @Override
    public void onScanCompleted(String path, Uri uri) {
        if (media.isEmpty()) {
            finish();
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (isSnackbarShown) {
            deleteFiles();
        }

        return false;
    }
}
