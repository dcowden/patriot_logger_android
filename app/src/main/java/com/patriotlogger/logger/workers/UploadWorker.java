package com.patriotlogger.logger.workers;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.patriotlogger.logger.data.AppDatabase; // Import AppDatabase
import com.patriotlogger.logger.data.RaceContext;
import com.patriotlogger.logger.data.RaceContextDao; // Import DAOs
import com.patriotlogger.logger.data.Racer;
import com.patriotlogger.logger.data.RacerDao;
import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.TagData;
import com.patriotlogger.logger.data.TagDataDao;
import com.patriotlogger.logger.data.TagStatus;
import com.patriotlogger.logger.data.TagStatusDao;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UploadWorker extends Worker {

    private static final String TAG = "UploadWorker";

    public UploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void enqueue(Context ctx) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(UploadWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(15))
                .build();

        WorkManager.getInstance(ctx).enqueue(req);
        Log.d(TAG, "UploadWorker enqueued.");
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "doWork started.");
        // Get Repository instance to access the AppDatabase instance
        Repository repository = Repository.get(getApplicationContext());
        AppDatabase db = repository.getDatabase(); // You'll need to add this getter to Repository

        // Get DAOs directly
        RaceContextDao raceContextDao = db.raceContextDao();
        TagStatusDao tagStatusDao = db.tagStatusDao();
        RacerDao racerDao = db.racerDao();
        TagDataDao tagDataDao = db.tagDataDao();


        // Since doWork() is already on a background thread, we can call synchronous DAO methods.
        RaceContext ctx = raceContextDao.latestSync(); // Call DAO sync method
        if (ctx == null) {
            Log.w(TAG, "No RaceContext in DB; nothing to upload.");
            return Result.failure();
        }

        String endpoint = ctx.baseUrl;
        if (TextUtils.isEmpty(endpoint)) {
            // Corrected the default endpoint - assuming it should be http/https
            endpoint = "https://splitriot.onrender.com/upload";
            Log.w(TAG, "Using default endpoint: " + endpoint);
        }
        String token = ctx.authToken;
        if (TextUtils.isEmpty(token)) {
            Log.e(TAG, "Missing auth token; refusing to upload.");
            return Result.failure();
        }

        List<TagStatus> statuses = tagStatusDao.getAllSync(); // Call DAO sync method
        if (statuses == null) {
            statuses = Collections.emptyList();
        }

        List<Racer> racers = racerDao.getBySplitSync(ctx.splitAssignmentId); // Call DAO sync method
        if (racers == null) {
            racers = Collections.emptyList();
        }

        Map<Integer, String> namesById = new HashMap<>();
        for (Racer r : racers) {
            if (r != null) {
                namesById.put(r.id, r.name != null ? r.name : "");
            }
        }

        JsonObject root = new JsonObject();
        root.addProperty("race_id", ctx.raceId);
        root.addProperty("gun_time", ctx.gunTimeMs);

        JsonObject splitData = new JsonObject();
        splitData.addProperty("id", ctx.splitAssignmentId);

        JsonArray racersArr = new JsonArray();
        for (TagStatus s : statuses) {
            if (s == null) continue;

            JsonObject rj = new JsonObject();
            rj.addProperty("id", s.tagId);
            String name = (s.friendlyName != null && !s.friendlyName.isEmpty())
                    ? s.friendlyName
                    : namesById.getOrDefault(s.tagId, "");
            rj.addProperty("name", name != null ? name : "");

            rj.addProperty("entry_time", s.entryTimeMs);
            rj.addProperty("peak_time", s.peakTimeMs);
            rj.addProperty("exit_time", s.exitTimeMs);

            rj.addProperty("arriveTime", s.arrivedTimeMs); // Assuming this should be peak_rssi, not s.lowestRssi twice
            //rj.addProperty("lowest_rssi", s.peakRssi);

            List<TagData> samples = tagDataDao.getSamplesForTrackIdSync(s.trackId); // Call DAO sync method
            int sampleCount = (samples != null) ? samples.size() : 0;
            rj.addProperty("num_samples", sampleCount);

            racersArr.add(rj);
        }
        splitData.add("racers", racersArr);
        root.add("split_data", splitData);

        String payload = root.toString();
        Log.d(TAG, "Upload payload: " + payload);

        try {
            int code = postJson(endpoint, token, payload);
            if (code == 200 || code == 201) {
                Log.i(TAG, "Upload success (HTTP " + code + ").");
                return Result.success();
            } else if (code == 408 || code == 429 || (code >= 500 && code < 600)) {
                Log.w(TAG, "Server temporary error (HTTP " + code + "); will retry.");
                return Result.retry();
            } else {
                Log.e(TAG, "Upload permanent failure (HTTP " + code + "); will not retry.");
                return Result.failure();
            }
        } catch (IOException ioe) {
            Log.w(TAG, "Network I/O error during upload; will retry.", ioe);
            return Result.retry();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error during upload; failing.", e);
            return Result.failure();
        }
    }

    private int postJson(String endpoint, String bearer, String json) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + bearer);

            byte[] body = json.getBytes(UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }

            int code = conn.getResponseCode();

            InputStream responseStream = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (responseStream != null) {
                try (InputStream streamToProcess = responseStream) {
                    String responseBody = slurp(streamToProcess);
                    Log.d(TAG, "Server response (HTTP " + code + "): " + responseBody);
                }
            } else {
                Log.d(TAG, "Server response (HTTP " + code + ") with no body.");
            }
            return code;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String slurp(InputStream in) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }
}

