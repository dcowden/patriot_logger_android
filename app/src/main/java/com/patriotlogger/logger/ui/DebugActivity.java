package com.patriotlogger.logger.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.patriotlogger.logger.R;
import com.patriotlogger.logger.service.BleScannerService;

public class DebugActivity extends AppCompatActivity {

    private DebugViewModel viewModel;
    private DebugInfoAdapter adapter;
    private RecyclerView rvDebugData; // Make RecyclerView a class field
    private LinearLayoutManager layoutManager; // Store layout manager as a field
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debug);

        viewModel = new ViewModelProvider(this).get(DebugViewModel.class);

        //Button btnStartScan = findViewById(R.id.btnDebugStartScan);
        //Button btnStopScan = findViewById(R.id.btnDebugStopScan);
        Button btnClearRoom = findViewById(R.id.btnDebugClearRoom);
        Button btnCloseApp = findViewById(R.id.btnDebugCloseApp);

        rvDebugData = findViewById(R.id.rvDebugData); // Initialize the class field
        layoutManager = new LinearLayoutManager(this);
        rvDebugData.setLayoutManager(layoutManager);
        adapter = new DebugInfoAdapter();
        rvDebugData.setAdapter(adapter);

        /*
        removed these because they get out of sync with the front page anyway.
        btnStartScan.setOnClickListener(v -> {
            Intent svcIntent = new Intent(this, BleScannerService.class).setAction(BleScannerService.ACTION_START);
            startService(svcIntent);
            Toast.makeText(this, "Start Scan command sent", Toast.LENGTH_SHORT).show();
        });

        btnStopScan.setOnClickListener(v -> {
            Intent svcIntent = new Intent(this, BleScannerService.class).setAction(BleScannerService.ACTION_STOP);
            stopService(svcIntent);
            Toast.makeText(this, "Stop Scan command sent", Toast.LENGTH_SHORT).show();
        });
        */
        btnClearRoom.setOnClickListener(v -> {
            viewModel.clearAllRoomData();
            Toast.makeText(this, "Clear Room Data command sent", Toast.LENGTH_SHORT).show();
        });

        btnCloseApp.setOnClickListener(v -> {
            finishAffinity();
        });

        viewModel.getAllDebugTagData().observe(this, debugTagDataList -> {
            if (debugTagDataList != null) {
                // Check if the user is currently at the top of the list
                boolean isAtTop = layoutManager.findFirstCompletelyVisibleItemPosition() == 0;

                // Submit the list and provide a callback
                adapter.submitList(debugTagDataList, () -> {
                    // This runnable is executed after the list is diffed and updated.
                    // Only scroll to the top if the user was already there.
                    if (isAtTop && debugTagDataList.size() > 0) {
                        rvDebugData.scrollToPosition(0);
                    }
                });
            }
        });
    }
}
