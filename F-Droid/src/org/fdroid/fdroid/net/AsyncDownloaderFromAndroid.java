package org.fdroid.fdroid.net;

import android.annotation.TargetApi;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;

import org.fdroid.fdroid.R;
import org.fdroid.fdroid.Utils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A downloader that uses Android's DownloadManager to perform a download.
 */
@TargetApi(Build.VERSION_CODES.GINGERBREAD)
public class AsyncDownloaderFromAndroid implements AsyncDownloader {
    private final Context context;
    private final DownloadManager dm;
    private final LocalBroadcastManager localBroadcastManager;
    private final File localFile;
    private final String remoteAddress;
    private final String downloadTitle;
    private final String uniqueDownloadId;
    private final Listener listener;
    private boolean isCancelled;

    private long downloadManagerId = -1;

    /**
     * Normally the listener would be provided using a setListener method.
     * However for the purposes of this async downloader, it doesn't make
     * sense to have an async task without any way to notify the outside
     * world about completion. Therefore, we require the listener as a
     * parameter to the constructor.
     */
    public AsyncDownloaderFromAndroid(Context context, Listener listener, String downloadTitle, String downloadId, String remoteAddress, File localFile) {
        this.context = context;
        this.uniqueDownloadId = downloadId;
        this.remoteAddress = remoteAddress;
        this.listener = listener;
        this.localFile = localFile;

        if (TextUtils.isEmpty(downloadTitle)) {
            this.downloadTitle = remoteAddress;
        } else {
            this.downloadTitle = downloadTitle;
        }

        dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        localBroadcastManager = LocalBroadcastManager.getInstance(context);
    }

    @Override
    public void download() {
        isCancelled = false;

        // check if download failed
        if (downloadManagerId >= 0) {
            int status = validDownload(context, downloadManagerId);
            if (status > 0) {
                // error downloading
                dm.remove(downloadManagerId);
                if (listener != null) {
                    listener.onErrorDownloading(context.getString(R.string.download_error));
                }
                return;
            }
        }

        // Check if the download is complete
        downloadManagerId = isDownloadComplete(context, uniqueDownloadId);
        if (downloadManagerId > 0) {
            // clear the download
            dm.remove(downloadManagerId);

            try {
                // write the downloaded file to the expected location
                ParcelFileDescriptor fd = dm.openDownloadedFile(downloadManagerId);
                copyFile(fd.getFileDescriptor(), localFile);
                listener.onDownloadComplete();
            } catch (IOException e) {
                listener.onErrorDownloading(e.getLocalizedMessage());
            }
            return;
        }

        // Check if the download is still in progress
        if (downloadManagerId < 0) {
            downloadManagerId = isDownloading(context, uniqueDownloadId);
        }

        // Start a new download
        if (downloadManagerId < 0) {
            // set up download request
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(remoteAddress));
            request.setTitle(downloadTitle);
            request.setDescription(uniqueDownloadId); // we will retrieve this later from the description field
            this.downloadManagerId = dm.enqueue(request);
        }

        context.registerReceiver(receiver,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        Thread progressThread = new Thread() {
            @Override
            public void run() {
                while (!isCancelled && isDownloading(context, uniqueDownloadId) >= 0) {
                    try {
                        Thread.sleep(1000);
                    } catch (Exception e) {
                        // ignore
                    }
                    sendProgress(getBytesRead(), getTotalBytes());
                }
            }
        };
        progressThread.start();
    }

    /**
     * Copy input file to output file
     * @throws IOException
     */
    private void copyFile(FileDescriptor inputFile, File outputFile) throws IOException {
        InputStream input = null;
        OutputStream output = null;
        try {
            input  = new FileInputStream(inputFile);
            output = new FileOutputStream(outputFile);
            Utils.copy(input, output);
        } finally {
            Utils.closeQuietly(output);
            Utils.closeQuietly(input);
        }
    }

    @Override
    public int getBytesRead() {
        if (downloadManagerId < 0) return 0;

        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(downloadManagerId);
        Cursor c = dm.query(query);

        try {
            if (c.moveToFirst()) {
                // we use the description column to store the unique id of this download
                int columnIndex = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                return c.getInt(columnIndex);
            }
        } finally {
            c.close();
        }

        return 0;
    }

    @Override
    public int getTotalBytes() {
        if (downloadManagerId < 0) return 0;

        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(downloadManagerId);
        Cursor c = dm.query(query);

        try {
            if (c.moveToFirst()) {
                // we use the description column to store the unique id for this download
                int columnIndex = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                return c.getInt(columnIndex);
            }
        } finally {
            c.close();
        }

        return 0;
    }

    protected void sendProgress(int bytesRead, int totalBytes) {
        Intent intent = new Intent(Downloader.LOCAL_ACTION_PROGRESS);
        intent.putExtra(Downloader.EXTRA_ADDRESS, remoteAddress);
        intent.putExtra(Downloader.EXTRA_BYTES_READ, bytesRead);
        intent.putExtra(Downloader.EXTRA_TOTAL_BYTES, totalBytes);
        localBroadcastManager.sendBroadcast(intent);
    }

    @Override
    public void attemptCancel(boolean userRequested) {
        isCancelled = true;
        try {
            context.unregisterReceiver(receiver);
        } catch (Exception e) {
            // ignore if receiver already unregistered
        }

        if (userRequested && downloadManagerId >= 0) {
            dm.remove(downloadManagerId);
        }
    }

    /**
     * Extract the uniqueDownloadId from a given download id.
     * @return - uniqueDownloadId or null if not found
     */
    public static String getDownloadId(Context context, long downloadId) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(downloadId);
        Cursor c = dm.query(query);

        try {
            if (c.moveToFirst()) {
                // we use the description column to store the unique id for this download
                int columnIndex = c.getColumnIndex(DownloadManager.COLUMN_DESCRIPTION);
                return c.getString(columnIndex);
            }
        } finally {
            c.close();
        }

        return null;
    }

    /**
     * Extract the download title from a given download id.
     * @return - title or null if not found
     */
    public static String getDownloadTitle(Context context, long downloadId) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(downloadId);
        Cursor c = dm.query(query);

        try {
            if (c.moveToFirst()) {
                int columnIndex = c.getColumnIndex(DownloadManager.COLUMN_TITLE);
                return c.getString(columnIndex);
            }
        } finally {
            c.close();
        }

        return null;
    }

    /**
     * Get the downloadManagerId from an Intent sent by the DownloadManagerReceiver
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public static long getDownloadId(Intent intent) {
        if (intent != null) {
            if (intent.hasExtra(DownloadManager.EXTRA_DOWNLOAD_ID)) {
                // we have been passed a DownloadManager download id, so get the unique id for that download
                return intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            }

            if (intent.hasExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS)) {
                // we have been passed multiple download id's - just return the first one
                long[] downloadIds = intent.getLongArrayExtra(DownloadManager.EXTRA_NOTIFICATION_CLICK_DOWNLOAD_IDS);
                if (downloadIds != null && downloadIds.length > 0) {
                    return downloadIds[0];
                }
            }
        }

        return -1;
    }

    /**
     * Check if a download is running for the specified id
     * @return -1 if not downloading, else the id from the Android download manager
     */
    public static long isDownloading(Context context, String uniqueDownloadId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.GINGERBREAD) {
            // TODO: remove. This is necessary because AppDetails calls this
            // static method directly, without using the whole pipe through
            // DownloaderFactory. This shouldn't be called at all on android-8
            // devices, since AppDetails is really using the old downloader,
            // not this one.
            return -1;
        }
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query();
        Cursor c = dm.query(query);
        if (c == null) {
            // TODO: same as above.
            return -1;
        }
        int columnUniqueDownloadId = c.getColumnIndex(DownloadManager.COLUMN_DESCRIPTION);
        int columnId = c.getColumnIndex(DownloadManager.COLUMN_ID);

        try {
            while (c.moveToNext()) {
                if (uniqueDownloadId.equals(c.getString(columnUniqueDownloadId))) {
                    return c.getLong(columnId);
                }
            }
        } finally {
            c.close();
        }

        return -1;
    }

    /**
     * Check if a specific download is complete.
     * @return -1 if download is not complete, otherwise the download id
     */
    public static long isDownloadComplete(Context context, String uniqueDownloadId) {
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL);
        Cursor c = dm.query(query);
        int columnUniqueDownloadId = c.getColumnIndex(DownloadManager.COLUMN_DESCRIPTION);
        int columnId = c.getColumnIndex(DownloadManager.COLUMN_ID);

        try {
            while (c.moveToNext()) {
                if (uniqueDownloadId.equals(c.getString(columnUniqueDownloadId))) {
                    return c.getLong(columnId);
                }
            }
        } finally {
            c.close();
        }

        return -1;
    }

    /**
     * Check if download was valid, see issue
     * http://code.google.com/p/android/issues/detail?id=18462
     * From http://stackoverflow.com/questions/8937817/downloadmanager-action-download-complete-broadcast-receiver-receiving-same-downl
     * @return 0 if successful, -1 if download doesn't exist, else the DownloadManager.ERROR_... code
     */
    public static int validDownload(Context context, long downloadId) {
        //Verify if download is a success
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        Cursor c = dm.query(new DownloadManager.Query().setFilterById(downloadId));

        try {
            if (c.moveToFirst()) {
                int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));

                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    return 0; // Download is valid, celebrate
                }
                return c.getInt(c.getColumnIndex(DownloadManager.COLUMN_REASON));
            }
        } finally {
            c.close();
        }

        return -1; // download doesn't exist
    }

    /**
     * Broadcast receiver to listen for ACTION_DOWNLOAD_COMPLETE broadcasts
     */
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                long dId = getDownloadId(intent);
                String downloadId = getDownloadId(context, dId);
                if (listener != null && dId == AsyncDownloaderFromAndroid.this.downloadManagerId && downloadId != null) {
                    // our current download has just completed, so let's throw up install dialog
                    // immediately
                    try {
                        context.unregisterReceiver(receiver);
                    } catch (Exception e) {
                        // ignore if receiver already unregistered
                    }

                    // call download() to copy the file and start the installer
                    download();
                }
            }
        }
    };
}
