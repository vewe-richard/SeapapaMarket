package org.fdroid.fdroid.net;

import android.content.Context;
import android.content.Intent;
import android.support.annotation.NonNull;
import android.support.v4.content.LocalBroadcastManager;

import org.fdroid.fdroid.Utils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;

public abstract class Downloader {

    private static final String TAG = "Downloader";

    public static final String LOCAL_ACTION_PROGRESS = "Downloader.PROGRESS";

    public static final String EXTRA_ADDRESS = "extraAddress";
    public static final String EXTRA_BYTES_READ = "extraBytesRead";
    public static final String EXTRA_TOTAL_BYTES = "extraTotalBytes";

    private final OutputStream outputStream;

    private final LocalBroadcastManager localBroadcastManager;
    private final File outputFile;

    protected final URL sourceUrl;
    protected String cacheTag;
    protected int bytesRead;
    protected int totalBytes;

    protected abstract InputStream getDownloadersInputStream() throws IOException;

    protected abstract void close() throws IOException;

    Downloader(Context context, URL url, File destFile)
            throws FileNotFoundException, MalformedURLException {
        this.sourceUrl = url;
        outputFile = destFile;
        outputStream = new FileOutputStream(outputFile);
        localBroadcastManager = LocalBroadcastManager.getInstance(context);
    }

    public final InputStream getInputStream() throws IOException {
        return new WrappedInputStream(getDownloadersInputStream());
    }

    /**
     * If you ask for the cacheTag before calling download(), you will get the
     * same one you passed in (if any). If you call it after download(), you
     * will get the new cacheTag from the server, or null if there was none.
     */
    public String getCacheTag() {
        return cacheTag;
    }

    /**
     * If this cacheTag matches that returned by the server, then no download will
     * take place, and a status code of 304 will be returned by download().
     */
    public void setCacheTag(String cacheTag) {
        this.cacheTag = cacheTag;
    }

    protected boolean wantToCheckCache() {
        return cacheTag != null;
    }

    /**
     * Only available if you passed a context object into the constructor
     * (rather than an outputStream, which may or  may not be associated with
     * a file).
     */
    public File getFile() {
        return outputFile;
    }

    public abstract boolean hasChanged();

    public abstract int totalDownloadSize();

    /**
     * Helper function for synchronous downloads (i.e. those *not* using AsyncDownloadWrapper),
     * which don't really want to bother dealing with an InterruptedException.
     * The InterruptedException thrown from download() is there to enable cancelling asynchronous
     * downloads, but regular synchronous downloads cannot be cancelled because download() will
     * block until completed.
     * @throws IOException
     */
    public void downloadUninterrupted() throws IOException {
        try {
            download();
        } catch (InterruptedException ignored) { }
    }

    public abstract void download() throws IOException, InterruptedException;

    public abstract boolean isCached();

    protected void downloadFromStream(int bufferSize) throws IOException, InterruptedException {
        Utils.debugLog(TAG, "Downloading from stream");
        InputStream input = null;
        try {
            input = getInputStream();

            // Getting the input stream is slow(ish) for HTTP downloads, so we'll check if
            // we were interrupted before proceeding to the download.
            throwExceptionIfInterrupted();

            copyInputToOutputStream(input, bufferSize);
        } finally {
            Utils.closeQuietly(outputStream);
            Utils.closeQuietly(input);
        }

        // Even if we have completely downloaded the file, we should probably respect
        // the wishes of the user who wanted to cancel us.
        throwExceptionIfInterrupted();
    }

    /**
     * In a synchronous download (the usual usage of the Downloader interface),
     * you will not be able to interrupt this because the thread will block
     * after you have called download(). However if you use the AsyncDownloadWrapper,
     * then it will use this mechanism to cancel the download.
     *
     * After every network operation that could take a while, we will check if an
     * interrupt occured during that blocking operation. The goal is to ensure we
     * don't move onto another slow, network operation if we have cancelled the
     * download.
     * @throws InterruptedException
     */
    private void throwExceptionIfInterrupted() throws InterruptedException {
        if (Thread.interrupted()) {
            Utils.debugLog(TAG, "Received interrupt, cancelling download");
            throw new InterruptedException();
        }
    }

    /**
     * This copies the downloaded data from the InputStream to the OutputStream,
     * keeping track of the number of bytes that have flowed through for the
     * progress counter.
     */
    protected void copyInputToOutputStream(InputStream input, int bufferSize) throws IOException, InterruptedException {

        int bytesRead = 0;
        this.totalBytes = totalDownloadSize();
        byte[] buffer = new byte[bufferSize];

        // Getting the total download size could potentially take time, depending on how
        // it is implemented, so we may as well check this before we proceed.
        throwExceptionIfInterrupted();

        sendProgress(bytesRead, totalBytes);
        while (true) {

            int count;
            if (input.available() > 0) {
                int readLength = Math.min(input.available(), buffer.length);
                count = input.read(buffer, 0, readLength);
            } else {
                count = input.read(buffer);
            }

            throwExceptionIfInterrupted();

            if (count == -1) {
                Utils.debugLog(TAG, "Finished downloading from stream");
                break;
            }

            bytesRead += count;
            sendProgress(bytesRead, totalBytes);
            outputStream.write(buffer, 0, count);

        }
        outputStream.flush();
        outputStream.close();
    }

    protected void sendProgress(int bytesRead, int totalBytes) {
        this.bytesRead = bytesRead;
        Intent intent = new Intent(LOCAL_ACTION_PROGRESS);
        intent.putExtra(EXTRA_ADDRESS, sourceUrl.toString());
        intent.putExtra(EXTRA_BYTES_READ, bytesRead);
        intent.putExtra(EXTRA_TOTAL_BYTES, totalBytes);
        localBroadcastManager.sendBroadcast(intent);
    }

    public int getBytesRead() {
        return bytesRead;
    }

    public int getTotalBytes() {
        return totalBytes;
    }

    /**
     * Overrides every method in {@link InputStream} and delegates to the wrapped stream.
     * The only difference is that when we call the {@link WrappedInputStream#close()} method,
     * after delegating to the wrapped stream we invoke the {@link Downloader#close()} method
     * on the {@link Downloader} which created this.
     */
    private class WrappedInputStream extends InputStream {

        private final InputStream toWrap;

        WrappedInputStream(InputStream toWrap) {
            super();
            this.toWrap = toWrap;
        }

        @Override
        public void close() throws IOException {
            toWrap.close();
            Downloader.this.close();
        }

        @Override
        public int available() throws IOException {
            return toWrap.available();
        }

        @Override
        public void mark(int readlimit) {
            toWrap.mark(readlimit);
        }

        @Override
        public boolean markSupported() {
            return toWrap.markSupported();
        }

        @Override
        public int read(@NonNull byte[] buffer) throws IOException {
            return toWrap.read(buffer);
        }

        @Override
        public int read(@NonNull byte[] buffer, int byteOffset, int byteCount) throws IOException {
            return toWrap.read(buffer, byteOffset, byteCount);
        }

        @Override
        public synchronized void reset() throws IOException {
            toWrap.reset();
        }

        @Override
        public long skip(long byteCount) throws IOException {
            return toWrap.skip(byteCount);
        }

        @Override
        public int read() throws IOException {
            return toWrap.read();
        }
    }
}
