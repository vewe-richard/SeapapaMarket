package org.fdroid.fdroid.net;

import android.content.Context;
import android.util.Log;
import com.nostra13.universalimageloader.core.download.BaseImageDownloader;

import org.fdroid.fdroid.FDroidApp;
import org.fdroid.fdroid.Preferences;
import org.fdroid.fdroid.Utils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.URL;

import javax.net.ssl.SSLHandshakeException;
import org.fdroid.fdroid.data.Credentials;

public class HttpDownloader extends Downloader {
    private static final String TAG = "HttpDownloader";

    protected static final String HEADER_IF_NONE_MATCH = "If-None-Match";
    protected static final String HEADER_FIELD_ETAG = "ETag";

    protected HttpURLConnection connection;
    private Credentials credentials;
    private int statusCode  = -1;

    HttpDownloader(Context context, URL url, File destFile)
            throws FileNotFoundException, MalformedURLException {
        this(context, url, destFile, null);
    }

    HttpDownloader(Context context, URL url, File destFile, final Credentials credentials)
            throws FileNotFoundException, MalformedURLException {
        super(context, url, destFile);

        this.credentials = credentials;
    }

    /**
     * Note: Doesn't follow redirects (as far as I'm aware).
     * {@link BaseImageDownloader#getStreamFromNetwork(String, Object)} has an implementation worth
     * checking out that follows redirects up to a certain point. I guess though the correct way
     * is probably to check for a loop (keep a list of all URLs redirected to and if you hit the
     * same one twice, bail with an exception).
     * @throws IOException
     */
    @Override
    protected InputStream getDownloadersInputStream() throws IOException {
        setupConnection();
        return new BufferedInputStream(connection.getInputStream());
    }

    // Get a remote file. Returns the HTTP response code.
    // If 'etag' is not null, it's passed to the server as an If-None-Match
    // header, in which case expect a 304 response if nothing changed.
    // In the event of a 200 response ONLY, 'retag' (which should be passed
    // empty) may contain an etag value for the response, or it may be left
    // empty if none was available.
    @Override
    public void download() throws IOException, InterruptedException {
        try {
            setupConnection();
            doDownload();
        } catch (SSLHandshakeException e) {
            // TODO this should be handled better, it is not internationalised here
            throw new IOException(
                    "A problem occurred while establishing an SSL " +
                            "connection. If this problem persists, AND you have a " +
                            "very old device, you could try using http instead of " +
                            "https for the repo URL." + Log.getStackTraceString(e));
        }
    }

    boolean isSwapUrl() {
        String host = sourceUrl.getHost();
        return sourceUrl.getPort() > 1023 // only root can use <= 1023, so never a swap repo
                && host.matches("[0-9.]+") // host must be an IP address
                && FDroidApp.subnetInfo.isInRange(host); // on the same subnet as we are
    }

    protected void setupConnection() throws IOException {
        if (connection != null)
            return;
        Preferences prefs = Preferences.get();
        if (prefs.isProxyEnabled() && !isSwapUrl()) {
            SocketAddress sa = new InetSocketAddress(prefs.getProxyHost(), prefs.getProxyPort());
            Proxy proxy = new Proxy(Proxy.Type.HTTP, sa);
            connection = (HttpURLConnection) sourceUrl.openConnection(proxy);
        } else {

            connection = (HttpURLConnection) sourceUrl.openConnection();
            if (credentials != null) {
                credentials.authenticate((HttpURLConnection) connection);
            }
        }
    }

    protected void doDownload() throws IOException, InterruptedException {
        if (wantToCheckCache()) {
            setupCacheCheck();
            Utils.debugLog(TAG, "Checking cached status of " + sourceUrl);
            statusCode = connection.getResponseCode();
        }

        if (isCached()) {
            Utils.debugLog(TAG, sourceUrl + " is cached, so not downloading (HTTP " + statusCode + ")");
        } else {
            Utils.debugLog(TAG, "Downloading from " + sourceUrl);
            downloadFromStream(4096);
            updateCacheCheck();
        }
    }

    @Override
    public boolean isCached() {
        return wantToCheckCache() && statusCode == 304;
    }

    private void setupCacheCheck() {
        if (cacheTag != null) {
            connection.setRequestProperty(HEADER_IF_NONE_MATCH, cacheTag);
        }
    }

    private void updateCacheCheck() {
        cacheTag = connection.getHeaderField(HEADER_FIELD_ETAG);
    }

    // Testing in the emulator for me, showed that figuring out the
    // filesize took about 1 to 1.5 seconds.
    // To put this in context, downloading a repo of:
    //  - 400k takes ~6 seconds
    //  - 5k   takes ~3 seconds
    // on my connection. I think the 1/1.5 seconds is worth it,
    // because as the repo grows, the tradeoff will
    // become more worth it.
    @Override
    public int totalDownloadSize() {
        return connection.getContentLength();
    }

    @Override
    public boolean hasChanged() {
        return this.statusCode != 304;
    }

    @Override
    public void close() {
        connection.disconnect();
    }
}