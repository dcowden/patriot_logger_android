package com.patriotlogger.logger.util;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.patriotlogger.logger.data.Repository;
import com.patriotlogger.logger.data.RepositoryVoidCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class CsvExportUtils {
    private CsvExportUtils() {}

    /**
     * Creates a CSV in Downloads and streams the track’s samples via Repository.exportTrackCsv.
     * @param ctx Android context
     * @param repository your Repository
     * @param trackId pass/track to export
     * @param fileName e.g. "track_" + trackId + ".csv" (no path)
     * @param cb callback on completion (main thread)
     */
    public static void exportTrackToDownloads(Context ctx,
                                              Repository repository,
                                              int trackId,
                                              String fileName,
                                              RepositoryVoidCallback cb) {
        try {
            OutputStream os;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("Failed to create Downloads entry");
                os = ctx.getContentResolver().openOutputStream(uri);
                if (os == null) throw new IllegalStateException("Failed to open output stream");
            } else {
                File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloads.exists() && !downloads.mkdirs()) {
                    throw new IllegalStateException("Cannot create Downloads directory");
                }
                File out = new File(downloads, fileName);
                os = new FileOutputStream(out);
                // Optionally write a UTF-8 BOM so Excel is happier
                os.write("\uFEFF".getBytes(StandardCharsets.UTF_8));
            }
            repository.exportTrackCsv(trackId, os, cb);
        } catch (Exception e) {
            if (cb != null) cb.onError(e);
        }
    }
}
