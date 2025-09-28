package com.patriotlogger.logger.ui;

import android.app.TimePickerDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log; // For logging
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TimePicker;
import android.widget.Toast; // For showing errors/success

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
// import androidx.lifecycle.ViewModelProvider; // Not directly used for a ViewModel in this fragment
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.patriotlogger.logger.R;
import com.patriotlogger.logger.data.RaceContext;
import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.RepositoryVoidCallback; // Import callback

import java.util.Calendar;

public class LogFragment extends Fragment {
    private static final String TAG_FRAGMENT = "LogFragment"; // Tag for logging

    private RecyclerView recyclerView;
    private RunnerAdapter runnerAdapter;
    private Repository repository;
    private RaceContext currentObservedRaceContext; // To hold the latest observed context for dialogs

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        repository = Repository.get(requireActivity().getApplication());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_log, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerView = view.findViewById(R.id.rvLogEntries);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        runnerAdapter = new RunnerAdapter(requireContext());
        recyclerView.setAdapter(runnerAdapter);

        // Use the new LiveData method from Repository
        repository.getAllTagStatuses().observe(getViewLifecycleOwner(), statuses -> {
            if (statuses != null) {
                runnerAdapter.submitList(statuses);
            }
        });

        // Use the new LiveData method from Repository
        repository.getLiveRaceContext().observe(getViewLifecycleOwner(), raceContext -> {
            currentObservedRaceContext = raceContext; // Cache for dialogs
            if (raceContext != null && raceContext.gunTimeMs > 0) {
                runnerAdapter.setGunTime(raceContext.gunTimeMs);
            } else {
                runnerAdapter.setGunTime(0L);
            }
        });
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.menu_log_fragment, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_clear_log) {
            showClearLogConfirmationDialog();
            return true;
        } else if (itemId == R.id.action_set_gun_time) {
            showSetGunTimeDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showClearLogConfirmationDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Clear Log")
                .setMessage("Are you sure you want to clear all log entries and reset gun time?")
                .setPositiveButton("Clear", (dialog, which) -> {
                    repository.clearAllData(new RepositoryVoidCallback() {
                        @Override
                        public void onSuccess() {
                            // UI updates should ideally be driven by LiveData reacting to the clear.
                            // Forcing gunTime to 0 in adapter here is okay for immediate visual feedback.
                            if (runnerAdapter != null) { // Check adapter for safety
                                runnerAdapter.setGunTime(0L);
                            }
                            Toast.makeText(getContext(), "Log cleared.", Toast.LENGTH_SHORT).show();
                            Log.i(TAG_FRAGMENT, "Data cleared successfully via menu.");
                        }

                        @Override
                        public void onError(Exception e) {
                            Toast.makeText(getContext(), "Error clearing log.", Toast.LENGTH_SHORT).show();
                            Log.e(TAG_FRAGMENT, "Error clearing data: ", e);
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSetGunTimeDialog() {
        final Calendar c = Calendar.getInstance();

        // Use the cached currentObservedRaceContext from the LiveData observer
        if (currentObservedRaceContext != null && currentObservedRaceContext.gunTimeMs > 0) {
            c.setTimeInMillis(currentObservedRaceContext.gunTimeMs);
        }

        int hour = c.get(Calendar.HOUR_OF_DAY);
        int minute = c.get(Calendar.MINUTE);

        TimePickerDialog timePickerDialog = new TimePickerDialog(requireContext(),
                (timePickerView, selectedHour, selectedMinute) -> { // Explicitly typed parameter
                    Calendar selectedTime = Calendar.getInstance();
                    selectedTime.set(Calendar.HOUR_OF_DAY, selectedHour);
                    selectedTime.set(Calendar.MINUTE, selectedMinute);
                    selectedTime.set(Calendar.SECOND, 0);
                    selectedTime.set(Calendar.MILLISECOND, 0);

                    repository.setGunTime(selectedTime.getTimeInMillis(), new RepositoryVoidCallback() {
                        @Override
                        public void onSuccess() {
                            // LiveData from getLiveRaceContext() should update the UI automatically.
                            Toast.makeText(getContext(), "Gun time updated.", Toast.LENGTH_SHORT).show();
                            Log.i(TAG_FRAGMENT, "Gun time updated successfully.");
                        }

                        @Override
                        public void onError(Exception e) {
                            Toast.makeText(getContext(), "Error setting gun time.", Toast.LENGTH_SHORT).show();
                            Log.e(TAG_FRAGMENT, "Error setting gun time: ", e);
                        }
                    });
                }, hour, minute, true); // true for 24-hour format
        timePickerDialog.setTitle("Set Gun Time");
        timePickerDialog.show();
    }
}

