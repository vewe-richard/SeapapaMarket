package org.fdroid.fdroid;

import android.content.ContentValues;
import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import org.fdroid.fdroid.data.Apk;
import org.fdroid.fdroid.data.App;
import org.fdroid.fdroid.data.Repo;
import org.fdroid.fdroid.data.RepoPersister;
import org.fdroid.fdroid.data.RepoProvider;
import org.fdroid.fdroid.net.Downloader;
import org.fdroid.fdroid.net.DownloaderFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.CodeSigner;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * Responsible for updating an individual repository. This will:
 *  * Download the index.jar
 *  * Verify that it is signed correctly and by the correct certificate
 *  * Parse the index.xml from the .jar file
 *  * Save the resulting repo, apps, and apks to the database.
 *
 * <b>WARNING</b>: this class is the central piece of the entire security model of
 * FDroid!  Avoid modifying it when possible, if you absolutely must, be very,
 * very careful with the changes that you are making!
 */
public class RepoUpdater {

    private static final String TAG = "RepoUpdater";

    public static final String PROGRESS_TYPE_PROCESS_XML = "processingXml";
    public static final String PROGRESS_COMMITTING = "committing";
    public static final String PROGRESS_DATA_REPO_ADDRESS = "repoAddress";

    @NonNull protected final Context context;
    @NonNull protected final Repo repo;
    protected boolean hasChanged;
    @Nullable protected ProgressListener progressListener;
    private String cacheTag;
    private X509Certificate signingCertFromJar;

    @NonNull private final RepoPersister persister;

    /**
     * Updates an app repo as read out of the database into a {@link Repo} instance.
     *
     * @param repo A {@link Repo} read out of the local database
     */
    public RepoUpdater(@NonNull Context context, @NonNull Repo repo) {
        this.context = context;
        this.repo = repo;
        this.persister = new RepoPersister(context, repo);
    }

    public void setProgressListener(@Nullable ProgressListener progressListener) {
        this.progressListener = progressListener;
    }

    public boolean hasChanged() {
        return hasChanged;
    }

    protected URL getIndexAddress() throws MalformedURLException {
        String urlString = repo.address + "/index.jar";
        String versionName = Utils.getVersionName(context);
        if (versionName != null) {
            urlString += "?client_version=" + versionName;
        }
        return new URL(urlString);
    }

    Downloader downloadIndex() throws UpdateException {
        Downloader downloader = null;
        try {
            downloader = DownloaderFactory.create(context,
                getIndexAddress(), File.createTempFile("index-", "-downloaded", context.getCacheDir()),
                repo.getCredentials()
            );
            downloader.setCacheTag(repo.lastetag);
            downloader.downloadUninterrupted();

            if (downloader.isCached()) {
                // The index is unchanged since we last read it. We just mark
                // everything that came from this repo as being updated.
                Utils.debugLog(TAG, "Repo index for " + getIndexAddress() + " is up to date (by etag)");
            }

        } catch (IOException e) {
            if (downloader != null && downloader.getFile() != null) {
                if (!downloader.getFile().delete()) {
                    Log.w(TAG, "Couldn't delete file: " + downloader.getFile().getAbsolutePath());
                }
            }

            throw new UpdateException(repo, "Error getting index file", e);
        }
        return downloader;
    }

    /**
     * All repos are represented by a signed jar file, {@code index.jar}, which contains
     * a single file, {@code index.xml}.  This takes the {@code index.jar}, verifies the
     * signature, then returns the unzipped {@code index.xml}.
     *
     * @throws UpdateException All error states will come from here.
     */
    public void update() throws UpdateException {

        final Downloader downloader = downloadIndex();
        hasChanged = downloader.hasChanged();

        if (hasChanged) {
            // Don't worry about checking the status code for 200. If it was a
            // successful download, then we will have a file ready to use:
            cacheTag = downloader.getCacheTag();
            processDownloadedFile(downloader.getFile());
        }
    }

    private ContentValues repoDetailsToSave;
    private String signingCertFromIndexXml;

    private RepoXMLHandler.IndexReceiver createIndexReceiver() {
        return new RepoXMLHandler.IndexReceiver() {
            @Override
            public void receiveRepo(String name, String description, String signingCert, int maxAge, int version) {
                signingCertFromIndexXml = signingCert;
                repoDetailsToSave = prepareRepoDetailsForSaving(name, description, maxAge, version);
            }

            @Override
            public void receiveApp(App app, List<Apk> packages) {
                try {
                    persister.saveToDb(app, packages);
                } catch (UpdateException e) {
                    throw new RuntimeException("Error while saving repo details to database.", e);
                }
            }
        };
    }

    public void processDownloadedFile(File downloadedFile) throws UpdateException {
        InputStream indexInputStream = null;
        try {
            if (downloadedFile == null || !downloadedFile.exists())
                throw new UpdateException(repo, downloadedFile + " does not exist!");

            // Due to a bug in Android 5.0 Lollipop, the inclusion of spongycastle causes
            // breakage when verifying the signature of the downloaded .jar. For more
            // details, check out https://gitlab.com/fdroid/fdroidclient/issues/111.
            FDroidApp.disableSpongyCastleOnLollipop();

            JarFile jarFile = new JarFile(downloadedFile, true);
            JarEntry indexEntry = (JarEntry) jarFile.getEntry("index.xml");
            indexInputStream = new ProgressBufferedInputStream(jarFile.getInputStream(indexEntry),
                progressListener, repo, (int) indexEntry.getSize());

            // Process the index...
            final SAXParser parser = SAXParserFactory.newInstance().newSAXParser();
            final XMLReader reader = parser.getXMLReader();
            final RepoXMLHandler repoXMLHandler = new RepoXMLHandler(repo, createIndexReceiver());
            reader.setContentHandler(repoXMLHandler);
            reader.parse(new InputSource(indexInputStream));

            signingCertFromJar = getSigningCertFromJar(indexEntry);

            // JarEntry can only read certificates after the file represented by that JarEntry
            // has been read completely, so verification cannot run until now...
            assertSigningCertFromXmlCorrect();
            commitToDb();
        } catch (SAXException | ParserConfigurationException | IOException e) {
            throw new UpdateException(repo, "Error parsing index", e);
        } finally {
            FDroidApp.enableSpongyCastleOnLollipop();
            Utils.closeQuietly(indexInputStream);
            if (downloadedFile != null) {
                if (!downloadedFile.delete()) {
                    Log.w(TAG, "Couldn't delete file: " + downloadedFile.getAbsolutePath());
                }
            }
        }
    }

    private void commitToDb() throws UpdateException {
        Log.i(TAG, "Repo signature verified, saving app metadata to database.");
        if (progressListener != null) {
            progressListener.onProgress(new ProgressListener.Event(PROGRESS_COMMITTING));
        }
        persister.commit(repoDetailsToSave);
    }

    private void assertSigningCertFromXmlCorrect() throws SigningException {

        // no signing cert read from database, this is the first use
        if (repo.pubkey == null) {
            verifyAndStoreTOFUCerts(signingCertFromIndexXml, signingCertFromJar);
        }
        verifyCerts(signingCertFromIndexXml, signingCertFromJar);

    }

    /**
     * Update tracking data for the repo represented by this instance (index version, etag,
     * description, human-readable name, etc.
     */
    private ContentValues prepareRepoDetailsForSaving(String name, String description, int maxAge, int version) {
        ContentValues values = new ContentValues();

        values.put(RepoProvider.DataColumns.LAST_UPDATED, Utils.formatTime(new Date(), ""));

        if (repo.lastetag == null || !repo.lastetag.equals(cacheTag)) {
            values.put(RepoProvider.DataColumns.LAST_ETAG, cacheTag);
        }

        if (version != -1 && version != repo.version) {
            Utils.debugLog(TAG, "Repo specified a new version: from " + repo.version + " to " + version);
            values.put(RepoProvider.DataColumns.VERSION, version);
        }

        if (maxAge != -1 && maxAge != repo.maxage) {
            Utils.debugLog(TAG, "Repo specified a new maximum age - updated");
            values.put(RepoProvider.DataColumns.MAX_AGE, maxAge);
        }

        if (description != null && !description.equals(repo.description)) {
            values.put(RepoProvider.DataColumns.DESCRIPTION, description);
        }

        if (name != null && !name.equals(repo.name)) {
            values.put(RepoProvider.DataColumns.NAME, name);
        }

        return values;
    }

    public static class UpdateException extends Exception {

        private static final long serialVersionUID = -4492452418826132803L;
        public final Repo repo;

        public UpdateException(Repo repo, String message) {
            super(message);
            this.repo = repo;
        }

        public UpdateException(Repo repo, String message, Exception cause) {
            super(message, cause);
            this.repo = repo;
        }
    }

    public static class SigningException extends UpdateException {
        public SigningException(Repo repo, String message) {
            super(repo, "Repository was not signed correctly: " + message);
        }
    }

    /**
     * FDroid's index.jar is signed using a particular format and does not allow lots of
     * signing setups that would be valid for a regular jar.  This validates those
     * restrictions.
     */
    private X509Certificate getSigningCertFromJar(JarEntry jarEntry) throws SigningException {
        final CodeSigner[] codeSigners = jarEntry.getCodeSigners();
        if (codeSigners == null || codeSigners.length == 0) {
            throw new SigningException(repo, "No signature found in index");
        }
        /* we could in theory support more than 1, but as of now we do not */
        if (codeSigners.length > 1) {
            throw new SigningException(repo, "index.jar must be signed by a single code signer!");
        }
        List<? extends Certificate> certs = codeSigners[0].getSignerCertPath().getCertificates();
        if (certs.size() != 1) {
            throw new SigningException(repo, "index.jar code signers must only have a single certificate!");
        }
        return (X509Certificate) certs.get(0);
    }

    /**
     * A new repo can be added with or without the fingerprint of the signing
     * certificate.  If no fingerprint is supplied, then do a pure TOFU and just
     * store the certificate as valid.  If there is a fingerprint, then first
     * check that the signing certificate in the jar matches that fingerprint.
     */
    private void verifyAndStoreTOFUCerts(String certFromIndexXml, X509Certificate rawCertFromJar)
        throws SigningException {
        if (repo.pubkey != null)
            return; // there is a repo.pubkey already, nothing to TOFU

        /* The first time a repo is added, it can be added with the signing certificate's
         * fingerprint.  In that case, check that fingerprint against what is
         * actually in the index.jar itself.  If no fingerprint, just store the
         * signing certificate */
        if (repo.fingerprint != null) {
            String fingerprintFromIndexXml = Utils.calcFingerprint(certFromIndexXml);
            String fingerprintFromJar = Utils.calcFingerprint(rawCertFromJar);
            if (!repo.fingerprint.equalsIgnoreCase(fingerprintFromIndexXml)
                    || !repo.fingerprint.equalsIgnoreCase(fingerprintFromJar)) {
                throw new SigningException(repo, "Supplied certificate fingerprint does not match!");
            }
        } // else - no info to check things are valid, so just Trust On First Use

        Utils.debugLog(TAG, "Saving new signing certificate in the database for " + repo.address);
        ContentValues values = new ContentValues(2);
        values.put(RepoProvider.DataColumns.LAST_UPDATED, Utils.formatDate(new Date(), ""));
        values.put(RepoProvider.DataColumns.PUBLIC_KEY, Hasher.hex(rawCertFromJar));
        RepoProvider.Helper.update(context, repo, values);
    }

    /**
     * FDroid works with three copies of the signing certificate:
     * <li>in the downloaded jar</li>
     * <li>in the index XML</li>
     * <li>stored in the local database</li>
     * It would work better removing the copy from the index XML, but it needs to stay
     * there for backwards compatibility since the old TOFU process requires it.  Therefore,
     * since all three have to be present, all three are compared.
     *
     * @param certFromIndexXml the cert written into the header of the index XML
     * @param rawCertFromJar   the {@link X509Certificate} embedded in the downloaded jar
     */
    private void verifyCerts(String certFromIndexXml, X509Certificate rawCertFromJar) throws SigningException {
        // convert binary data to string version that is used in FDroid's database
        String certFromJar = Hasher.hex(rawCertFromJar);

        // repo and repo.pubkey must be pre-loaded from the database
        if (TextUtils.isEmpty(repo.pubkey)
                || TextUtils.isEmpty(certFromJar)
                || TextUtils.isEmpty(certFromIndexXml))
            throw new SigningException(repo, "A empty repo or signing certificate is invalid!");

        // though its called repo.pubkey, its actually a X509 certificate
        if (repo.pubkey.equals(certFromJar)
                && repo.pubkey.equals(certFromIndexXml)
                && certFromIndexXml.equals(certFromJar)) {
            return;  // we have a match!
        }
        throw new SigningException(repo, "Signing certificate does not match!");
    }

}
