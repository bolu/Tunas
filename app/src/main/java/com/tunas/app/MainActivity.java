package com.tunas.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;
import android.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import java.util.ArrayList;
import java.util.List;
import com.tunas.app.TuneFavorites.StarColor;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST = 1;
    
    private ListView tunesList;
    private EditText filterBox;
    private Button randomButton;
    private Button starFilter;
    private SwipeRefreshLayout swipeRefreshLayout;
    private FloatingActionButton addTuneButton;
    private List<String> allTunes;
    private List<String> filteredTunes;
    private TuneListAdapter adapter;
    private TuneFavorites favorites;
    private boolean showOnlyFavorites = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d("Tunas", "onCreate() called - starting app initialization");
        setContentView(R.layout.activity_main);
        
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        tunesList = findViewById(R.id.tunesList);
        tunesList.setItemsCanFocus(false);
        filterBox = findViewById(R.id.filterBox);
        randomButton = findViewById(R.id.randomButton);
        starFilter = findViewById(R.id.starFilter);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);
        addTuneButton = findViewById(R.id.addTuneButton);

        allTunes = new ArrayList<>();
        filteredTunes = new ArrayList<>();
        favorites = new TuneFavorites(this);

        adapter = new TuneListAdapter(this, filteredTunes, favorites);
        tunesList.setAdapter(adapter);

        boolean hasPermissions = checkPermissions();
        Log.d("Tunas", "onCreate() - initial permission check result: " + hasPermissions);
        if (hasPermissions) {
            Log.d("Tunas", "onCreate() - permissions granted, calling loadTunes()");
            loadTunes();
        } else {
            Log.d("Tunas", "onCreate() - permissions not granted, calling requestPermissions()");
            requestPermissions();
        }

        filterBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterTunes(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        tunesList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String tuneName = filteredTunes.get(position);
                openTune(tuneName);
            }
        });

        randomButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String randomTune = TuneUtils.pickRandomTune(filteredTunes);
                if (randomTune != null) {
                    openTune(randomTune);
                }
            }
        });

        // Star filter button click listener - toggles favorite filter
        starFilter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleFavoriteFilter();
            }
        });

        // Pull to refresh functionality
        swipeRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                loadTunes();
                swipeRefreshLayout.setRefreshing(false);
            }
        });

        // Fix scrolling conflict: only allow refresh when at the top of the list
        swipeRefreshLayout.setOnChildScrollUpCallback(new SwipeRefreshLayout.OnChildScrollUpCallback() {
            @Override
            public boolean canChildScrollUp(SwipeRefreshLayout parent, @Nullable View child) {
                // Only allow refresh when the ListView is at the top (first visible position is 0)
                return tunesList.getFirstVisiblePosition() > 0;
            }
        });

        // Add tune button click listener
        addTuneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddTuneDialog();
            }
        });

        updateFilterButtonStates();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d("Tunas", "onResume() called - allTunes.isEmpty(): " + allTunes.isEmpty());
        // Re-check permissions when returning from settings (for MANAGE_EXTERNAL_STORAGE)
        boolean hasPermissions = checkPermissions();
        Log.d("Tunas", "onResume() - checkPermissions() returned: " + hasPermissions);
        if (hasPermissions && allTunes.isEmpty()) {
            Log.d("Tunas", "onResume() - permissions granted and allTunes is empty, calling loadTunes()");
            // Permissions granted and we haven't loaded tunes yet, load them now
            loadTunes();
        } else {
            Log.d("Tunas", "onResume() - not loading tunes - hasPermissions: " + hasPermissions + ", allTunes.isEmpty(): " + allTunes.isEmpty());
        }
    }

    private boolean checkPermissions() {
        boolean hasPermission;
        if (Build.VERSION.SDK_INT >= 30) {
            // Android 11+ uses MANAGE_EXTERNAL_STORAGE for broad access
            // ContextCompat.checkSelfPermission doesn't work for MANAGE_EXTERNAL_STORAGE
            hasPermission = Environment.isExternalStorageManager();
            Log.d("Tunas", "Android 11+ - MANAGE_EXTERNAL_STORAGE permission (using Environment.isExternalStorageManager()): " + hasPermission);
        } else {
            // Legacy storage permission for Android 10 and below
            hasPermission = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED;
            Log.d("Tunas", "Android 10 and below - READ_EXTERNAL_STORAGE permission: " + hasPermission);
        }
        Log.d("Tunas", "checkPermissions() returning: " + hasPermission);
        return hasPermission;
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= 30) {
            // MANAGE_EXTERNAL_STORAGE requires directing user to settings
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Storage Permission Required")
                    .setMessage("This app needs access to all files on your device to read audio and bar files. Please grant 'All files access' permission in the next screen.")
                    .setPositiveButton("Open Settings", (dialog, which) -> {
                        Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .setNegativeButton("Cancel", null)
                    .setCancelable(false)
                    .show();
        } else {
            // Legacy storage permission for Android 10 and below
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    PERMISSION_REQUEST
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Log.d("Tunas", "onRequestPermissionsResult() called - requestCode: " + requestCode + ", grantResults.length: " + grantResults.length);
        if (requestCode == PERMISSION_REQUEST) {
            Log.d("Tunas", "onRequestPermissionsResult() - handling PERMISSION_REQUEST");
            if (grantResults.length > 0) {
                boolean allGranted = true;
                for (int i = 0; i < grantResults.length; i++) {
                    Log.d("Tunas", "onRequestPermissionsResult() - permission[" + i + "]: " + permissions[i] + ", result: " + grantResults[i]);
                    if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                        allGranted = false;
                        Log.w("Tunas", "onRequestPermissionsResult() - permission DENIED: " + permissions[i]);
                        break;
                    }
                }

                Log.d("Tunas", "onRequestPermissionsResult() - allGranted: " + allGranted);
                if (allGranted) {
                    Log.d("Tunas", "onRequestPermissionsResult() - all permissions granted, calling loadTunes()");
                    loadTunes();
                } else {
                    Log.w("Tunas", "onRequestPermissionsResult() - permissions not fully granted, showing toast");
                    Toast.makeText(this, "Storage permission required", Toast.LENGTH_LONG).show();
                }
            } else {
                Log.w("Tunas", "onRequestPermissionsResult() - grantResults.length is 0");
            }
        } else {
            Log.d("Tunas", "onRequestPermissionsResult() - ignoring requestCode: " + requestCode);
        }
    }

    private void loadTunes() {
        Log.d("Tunas", "loadTunes() called - allTunes size before: " + allTunes.size());
        allTunes.clear();
        List<String> loadedTunes = TuneUtils.loadTunes();
        allTunes.addAll(loadedTunes);
        Log.d("Tunas", "loadTunes() - loaded " + loadedTunes.size() + " tunes from TuneUtils.loadTunes()");
        Log.d("Tunas", "loadTunes() - allTunes size after: " + allTunes.size());
        if (allTunes.isEmpty()) {
            Log.w("Tunas", "loadTunes() - WARNING: No tunes were loaded! Check permissions and file system access.");
        }
        filteredTunes.clear();
        filteredTunes.addAll(allTunes);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
            Log.d("Tunas", "loadTunes() - adapter notified of data set change");
        }
    }

    private void filterTunes(String query) {
        filteredTunes.clear();
        String lowerQuery = query.toLowerCase();

        for (String tune : allTunes) {
            boolean matchesQuery = query.isEmpty() || tune.toLowerCase().contains(lowerQuery);
            boolean matchesFavoriteFilter = !showOnlyFavorites || favorites.hasAnyFavorites(tune);

            if (matchesQuery && matchesFavoriteFilter) {
                filteredTunes.add(tune);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void openTune(String tuneName) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra("TUNE_NAME", tuneName);
        intent.putExtra("TUNE_PATH", TuneUtils.BASE_DIR + "/" + tuneName);
        startActivity(intent);
    }

    private void toggleFavoriteFilter() {
        showOnlyFavorites = !showOnlyFavorites;
        updateFilterButtonStates();
        filterTunes(filterBox.getText().toString());
    }

    private void updateFilterButtonStates() {
        if (showOnlyFavorites) {
            starFilter.setText("★");
            starFilter.setTextColor(StarColor.STAR.getColor());
        } else {
            starFilter.setText("☆");
            starFilter.setTextColor(getResources().getColor(android.R.color.primary_text_light));
        }
    }

    private void showAddTuneDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Create New Tune");

        final EditText input = new EditText(this);
        input.setHint("Enter tune name");
        builder.setView(input);

        builder.setPositiveButton("Create", (dialog, which) -> {
            String tuneName = input.getText().toString().trim();
            if (!tuneName.isEmpty()) {
                createNewTune(tuneName);
            } else {
                Toast.makeText(this, "Tune name cannot be empty", Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void createNewTune(String tuneName) {
        // Check if tune already exists
        if (allTunes.contains(tuneName)) {
            Toast.makeText(this, "A tune with this name already exists", Toast.LENGTH_SHORT).show();
            return;
        }

        // Create the directory
        java.io.File tuneDir = new java.io.File(TuneUtils.BASE_DIR + "/" + tuneName);
        if (tuneDir.mkdirs()) {
            // Add to the list and refresh
            allTunes.add(tuneName);
            java.util.Collections.sort(allTunes, String.CASE_INSENSITIVE_ORDER);
            filterTunes(filterBox.getText().toString());

            // Open the player activity for the new tune
            openTune(tuneName);
        } else {
            Toast.makeText(this, "Failed to create tune directory", Toast.LENGTH_SHORT).show();
        }
    }
}