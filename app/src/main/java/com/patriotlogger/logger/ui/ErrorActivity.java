package com.patriotlogger.logger.ui;

import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.patriotlogger.logger.R;

public class ErrorActivity extends AppCompatActivity {
    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debug);
        //((TextView)findViewById(R.id.tvDebug)).setText(getIntent().getStringExtra("err"));
    }
}
