package com.patriotlogger.logger.ui;

import android.app.TimePickerDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TimePicker;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.patriotlogger.logger.R;
import com.patriotlogger.logger.data.RaceContext;
import com.patriotlogger.logger.data.Repository;

import java.util.Calendar;

public class LogFragment extends Fragment {

    private RecyclerView recyclerView;
    private RunnerAdapter runnerAdapter;
    private Repository repository;

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

        repository.liveTagStatuses().observe(getViewLifecycleOwner(), statuses -> {
            if (statuses != null) {
                runnerAdapter.submitList(statuses);
            }
        });

        repository.liveContext().observe(getViewLifecycleOwner(), raceContext -> {
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
                    repository.clearAll(); 
                    runnerAdapter.setGunTime(0L); 
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSetGunTimeDialog() {
        final Calendar c = Calendar.getInstance();
        
        RaceContext currentRaceContext = repository.latestContextNow();
        if (currentRaceContext != null && currentRaceContext.gunTimeMs > 0) {
            c.setTimeInMillis(currentRaceContext.gunTimeMs);
        }
        
        int hour = c.get(Calendar.HOUR_OF_DAY);
        int minute = c.get(Calendar.MINUTE);

        TimePickerDialog timePickerDialog = new TimePickerDialog(requireContext(),
                (TimePicker view, int selectedHour, int selectedMinute) -> { // Explicitly typed the 'view' parameter
                    Calendar selectedTime = Calendar.getInstance();
                    selectedTime.set(Calendar.HOUR_OF_DAY, selectedHour);
                    selectedTime.set(Calendar.MINUTE, selectedMinute);
                    selectedTime.set(Calendar.SECOND, 0); 
                    selectedTime.set(Calendar.MILLISECOND, 0);
                    repository.setGunTime(selectedTime.getTimeInMillis()); 
                }, hour, minute, true); 
        timePickerDialog.setTitle("Set Gun Time");
        timePickerDialog.show();
    }
}
