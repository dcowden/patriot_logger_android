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
import com.patriotlogger.logger.data.RaceContext;
import com.patriotlogger.logger.data.Racer;
import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.TagStatus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
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
    }

    @NonNull @Override public Result doWork() {
        Repository repo = Repository.get(getApplicationContext());

        // Safe: doWork() runs off the main thread.
        RaceContext ctx = repo.latestContextNow();
        if (ctx == null) {
            Log.w(TAG, "No RaceContext in DB; nothing to upload.");
            return Result.failure();
        }

        String endpoint = ctx.baseUrl;
        if (TextUtils.isEmpty(endpoint)) {
            endpoint = "https://splitriot.onrender.com/upload";
        }
        String token = ctx.authToken;
        if (TextUtils.isEmpty(token)) {
            Log.w(TAG, "Missing auth token; refusing to upload.");
            return Result.failure();
        }

        List<TagStatus> statuses = repo.allTagStatusesNow();
        List<Racer> racers = repo.racersForSplitNow(ctx.splitAssignmentId);
        Map<Integer, String> namesById = new HashMap<>();
        for (Racer r : racers) {
            namesById.put(r.id, r.name != null ? r.name : "");
        }

        // Build payload
        JsonObject root = new JsonObject();
        root.addProperty("race_id", ctx.raceId);
        root.addProperty("gun_time", ctx.gunTimeMs);

        JsonObject splitData = new JsonObject();
        splitData.addProperty("id", ctx.splitAssignmentId);

        JsonArray racersArr = new JsonArray();
        for (TagStatus s : statuses) {
            JsonObject rj = new JsonObject();
            rj.addProperty("id", s.tagId);
            String name = (s.friendlyName != null && !s.friendlyName.isEmpty())
                    ? s.friendlyName
                    : namesById.getOrDefault(s.tagId, "");
            rj.addProperty("name", name);

            // All times are ms since epoch per your spec
            rj.addProperty("entry_time", s.entryTimeMs);
            rj.addProperty("peak_time",  s.peakTimeMs);
            rj.addProperty("exit_time",  s.exitTimeMs);

            // RSSI fields
            rj.addProperty("peak_rssi",   s.peakRssi);     // float/double fine in JSON
            rj.addProperty("lowest_rssi", s.lowestRssi);   // int
            rj.addProperty("num_samples", s.sampleCount);  // int

            racersArr.add(rj);
        }
        splitData.add("racers", racersArr);
        root.add("split_data", splitData);

        // POST
        try {
            int code = postJson(endpoint, token, root.toString());
            if (code == 200) {
                Log.i(TAG, "Upload success (200).");
                return Result.success();
            } else if (code == 408 || code == 429 || (code >= 500 && code < 600)) {
                Log.w(TAG, "Server temporary error " + code + "; will retry.");
                return Result.retry();
            } else {
                Log.e(TAG, "Permanent failure HTTP " + code + "; will not retry.");
                return Result.failure();
            }
        } catch (IOException ioe) {
            Log.w(TAG, "Network I/O error; will retry. " + ioe);
            return Result.retry();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error; failing. " + e);
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
            // Helpful for debugging server errors:
            InputStream es = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            if (es != null) {
                String resp = slurp(es);
                Log.d(TAG, "Server response (" + code + "): " + resp);
            }
            return code;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String slurp(InputStream in) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line; while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }
}
