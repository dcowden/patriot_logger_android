package com.patriotlogger.logger.ui;

import android.content.Intent;
import android.os.Build;
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

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debug);

        viewModel = new ViewModelProvider(this).get(DebugViewModel.class);

        Button btnStartScan = findViewById(R.id.btnDebugStartScan);
        Button btnStopScan = findViewById(R.id.btnDebugStopScan);
        Button btnClearRoom = findViewById(R.id.btnDebugClearRoom);
        Button btnCloseApp = findViewById(R.id.btnDebugCloseApp);

        RecyclerView rvDebugData = findViewById(R.id.rvDebugData);
        rvDebugData.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DebugInfoAdapter();
        rvDebugData.setAdapter(adapter);

        btnStartScan.setOnClickListener(v -> {
            Intent svcIntent = new Intent(this, BleScannerService.class).setAction(BleScannerService.ACTION_START);
            startForegroundService(svcIntent);
            Toast.makeText(this, "Start Scan command sent", Toast.LENGTH_SHORT).show();
        });

        btnStopScan.setOnClickListener(v -> {
            Intent svcIntent = new Intent(this, BleScannerService.class).setAction(BleScannerService.ACTION_STOP);
            startForegroundService(svcIntent); // Or stopService(svcIntent) if appropriate
            Toast.makeText(this, "Stop Scan command sent", Toast.LENGTH_SHORT).show();
        });

        btnClearRoom.setOnClickListener(v -> {
            viewModel.clearAllRoomData(); // Corrected method name
            Toast.makeText(this, "Clear Room Data command sent", Toast.LENGTH_SHORT).show();
        });

        btnCloseApp.setOnClickListener(v -> {
            finishAffinity(); // Closes all activities in this task
        });

        viewModel.getAllTagStatuses().observe(this, tagStatuses -> {
            if (tagStatuses != null) {
                adapter.submitList(tagStatuses);
            }
        });
    }
}
