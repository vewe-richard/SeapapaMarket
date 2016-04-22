package org.fdroid.fdroid.data;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.provider.BaseColumns;
import android.util.Log;

import org.fdroid.fdroid.Utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ApkProvider extends FDroidProvider {

    private static final String TAG = "ApkProvider";

    /**
     * SQLite has a maximum of 999 parameters in a query. Each apk we add
     * requires two (packageName and vercode) so we can only query half of that. Then,
     * we may want to add additional constraints, so we give our self some
     * room by saying only 450 apks can be queried at once.
     */
    protected static final int MAX_APKS_TO_QUERY = 450;

    public static final class Helper {

        private Helper() { }

        public static void update(Context context, Apk apk) {
            ContentResolver resolver = context.getContentResolver();
            Uri uri = getContentUri(apk.packageName, apk.vercode);
            resolver.update(uri, apk.toContentValues(), null, null);
        }

        public static List<Apk> cursorToList(Cursor cursor) {
            int knownApkCount = cursor != null ? cursor.getCount() : 0;
            List<Apk> apks = new ArrayList<>(knownApkCount);
            if (cursor != null) {
                if (knownApkCount > 0) {
                    cursor.moveToFirst();
                    while (!cursor.isAfterLast()) {
                        apks.add(new Apk(cursor));
                        cursor.moveToNext();
                    }
                }
                cursor.close();
            }
            return apks;
        }

        public static int deleteApksByRepo(Context context, Repo repo) {
            ContentResolver resolver = context.getContentResolver();
            final Uri uri = getRepoUri(repo.getId());
            return resolver.delete(uri, null, null);
        }

        public static void deleteApksByApp(Context context, App app) {
            ContentResolver resolver = context.getContentResolver();
            final Uri uri = getAppUri(app.packageName);
            resolver.delete(uri, null, null);
        }

        public static void deleteApks(final Context context, final List<Apk> apks) {
            if (apks.size() > ApkProvider.MAX_APKS_TO_QUERY) {
                int middle = apks.size() / 2;
                List<Apk> apks1 = apks.subList(0, middle);
                List<Apk> apks2 = apks.subList(middle, apks.size());
                deleteApks(context, apks1);
                deleteApks(context, apks2);
            } else {
                deleteApksSafely(context, apks);
            }
        }

        private static void deleteApksSafely(final Context context, final List<Apk> apks) {
            ContentResolver resolver = context.getContentResolver();
            final Uri uri = getContentUri(apks);
            resolver.delete(uri, null, null);
        }

        public static Apk find(Context context, String packageName, int versionCode) {
            return find(context, packageName, versionCode, DataColumns.ALL);
        }

        /**
         * Find all apks for a particular app, but limit it to those originating from the
         * specified repo.
         */
        public static List<Apk> find(Context context, Repo repo, List<App> apps, String[] projection) {
            ContentResolver resolver = context.getContentResolver();
            final Uri uri = getContentUriForApps(repo, apps);
            Cursor cursor = resolver.query(uri, projection, null, null, null);
            return cursorToList(cursor);
        }

        /**
         * @see org.fdroid.fdroid.data.ApkProvider.Helper#find(Context, Repo, List, String[])
         */
        public static List<Apk> find(Context context, Repo repo, List<App> apps) {
            return find(context, repo, apps, DataColumns.ALL);
        }

        public static Apk find(Context context, String packageName, int versionCode, String[] projection) {
            ContentResolver resolver = context.getContentResolver();
            final Uri uri = getContentUri(packageName, versionCode);
            Cursor cursor = resolver.query(uri, projection, null, null, null);
            Apk apk = null;
            if (cursor != null) {
                if (cursor.getCount() > 0) {
                    cursor.moveToFirst();
                    apk = new Apk(cursor);
                }
                cursor.close();
            }
            return apk;
        }

        public static List<Apk> findByPackageName(Context context, String packageName) {
            return findByPackageName(context, packageName, ApkProvider.DataColumns.ALL);
        }

        public static List<Apk> findByPackageName(Context context,
                                                  String packageName, String[] projection) {
            ContentResolver resolver = context.getContentResolver();
            final Uri uri = getAppUri(packageName);
            final String sort = ApkProvider.DataColumns.VERSION_CODE + " DESC";
            Cursor cursor = resolver.query(uri, projection, null, null, sort);
            return cursorToList(cursor);
        }

        /**
         * Returns apks in the database, which have the same packageName and version as
         * one of the apks in the "apks" argument.
         */
        public static List<Apk> knownApks(Context context, List<Apk> apks, String[] fields) {
            if (apks.isEmpty()) {
                return new ArrayList<>();
            }

            List<Apk> knownApks = new ArrayList<>();
            if (apks.size() > ApkProvider.MAX_APKS_TO_QUERY) {
                int middle = apks.size() / 2;
                List<Apk> apks1 = apks.subList(0, middle);
                List<Apk> apks2 = apks.subList(middle, apks.size());
                knownApks.addAll(knownApks(context, apks1, fields));
                knownApks.addAll(knownApks(context, apks2, fields));
            } else {
                knownApks.addAll(knownApksSafe(context, apks, fields));
            }
            return knownApks;

        }

        private static List<Apk> knownApksSafe(final Context context, final List<Apk> apks, final String[] fields) {
            ContentResolver resolver = context.getContentResolver();
            final Uri uri = getContentUri(apks);
            Cursor cursor = resolver.query(uri, fields, null, null, null);
            return cursorToList(cursor);
        }

        public static List<Apk> findByRepo(Context context, Repo repo, String[] fields) {
            ContentResolver resolver = context.getContentResolver();
            final Uri uri = getRepoUri(repo.getId());
            Cursor cursor = resolver.query(uri, fields, null, null, null);
            return cursorToList(cursor);
        }

        public static Apk get(Context context, Uri uri) {
            return get(context, uri, DataColumns.ALL);
        }

        public static Apk get(Context context, Uri uri, String[] fields) {
            ContentResolver resolver = context.getContentResolver();
            Cursor cursor = resolver.query(uri, fields, null, null, null);
            Apk apk = null;
            if (cursor != null) {
                if (cursor.getCount() > 0) {
                    cursor.moveToFirst();
                    apk = new Apk(cursor);
                }
                cursor.close();
            }
            return apk;
        }
    }

    public interface DataColumns extends BaseColumns {

        String _COUNT_DISTINCT_ID = "countDistinct";

        String PACKAGE_NAME    = "id";
        String VERSION         = "version";
        String REPO_ID         = "repo";
        String HASH            = "hash";
        String VERSION_CODE    = "vercode";
        String NAME            = "apkName";
        String SIZE            = "size";
        String SIGNATURE       = "sig";
        String SOURCE_NAME     = "srcname";
        String MIN_SDK_VERSION = "minSdkVersion";
        String MAX_SDK_VERSION = "maxSdkVersion";
        String PERMISSIONS     = "permissions";
        String FEATURES        = "features";
        String NATIVE_CODE     = "nativecode";
        String HASH_TYPE       = "hashType";
        String ADDED_DATE      = "added";
        String IS_COMPATIBLE   = "compatible";
        String INCOMPATIBLE_REASONS = "incompatibleReasons";
        String REPO_VERSION    = "repoVersion";
        String REPO_ADDRESS    = "repoAddress";

        String[] ALL = {
            _ID, PACKAGE_NAME, VERSION, REPO_ID, HASH, VERSION_CODE, NAME, SIZE,
            SIGNATURE, SOURCE_NAME, MIN_SDK_VERSION, MAX_SDK_VERSION,
            PERMISSIONS, FEATURES, NATIVE_CODE, HASH_TYPE, ADDED_DATE,
            IS_COMPATIBLE, REPO_VERSION, REPO_ADDRESS, INCOMPATIBLE_REASONS,
        };
    }

    private static final int CODE_APP = CODE_SINGLE + 1;
    private static final int CODE_REPO = CODE_APP + 1;
    private static final int CODE_APKS = CODE_REPO + 1;
    private static final int CODE_REPO_APPS = CODE_APKS + 1;
    protected static final int CODE_REPO_APK = CODE_REPO_APPS + 1;

    private static final String PROVIDER_NAME = "ApkProvider";
    protected static final String PATH_APK = "apk";
    private static final String PATH_APKS = "apks";
    private static final String PATH_APP = "app";
    private static final String PATH_REPO      = "repo";
    private static final String PATH_REPO_APPS = "repo-apps";
    protected static final String PATH_REPO_APK  = "repo-apk";

    private static final UriMatcher matcher = new UriMatcher(-1);

    public static final Map<String, String> REPO_FIELDS = new HashMap<>();

    static {
        REPO_FIELDS.put(DataColumns.REPO_VERSION, RepoProvider.DataColumns.VERSION);
        REPO_FIELDS.put(DataColumns.REPO_ADDRESS, RepoProvider.DataColumns.ADDRESS);

        matcher.addURI(getAuthority(), PATH_REPO + "/#", CODE_REPO);
        matcher.addURI(getAuthority(), PATH_APK + "/#/*", CODE_SINGLE);
        matcher.addURI(getAuthority(), PATH_APKS + "/*", CODE_APKS);
        matcher.addURI(getAuthority(), PATH_APP + "/*", CODE_APP);
        matcher.addURI(getAuthority(), PATH_REPO_APPS + "/#/*", CODE_REPO_APPS);
        matcher.addURI(getAuthority(), PATH_REPO_APK + "/#/*", CODE_REPO_APK);
        matcher.addURI(getAuthority(), null, CODE_LIST);
    }

    public static String getAuthority() {
        return AUTHORITY + "." + PROVIDER_NAME;
    }

    public static Uri getContentUri() {
        return Uri.parse("content://" + getAuthority());
    }

    public static Uri getAppUri(String packageName) {
        return getContentUri()
            .buildUpon()
            .appendPath(PATH_APP)
            .appendPath(packageName)
            .build();
    }

    public static Uri getRepoUri(long repoId) {
        return getContentUri()
            .buildUpon()
            .appendPath(PATH_REPO)
            .appendPath(Long.toString(repoId))
            .build();
    }

    public static Uri getContentUri(Apk apk) {
        return getContentUri(apk.packageName, apk.vercode);
    }

    public static Uri getContentUri(String packageName, int versionCode) {
        return getContentUri()
            .buildUpon()
            .appendPath(PATH_APK)
            .appendPath(Integer.toString(versionCode))
            .appendPath(packageName)
            .build();
    }

    public static Uri getContentUriForApps(Repo repo, List<App> apps) {
        return getContentUri()
            .buildUpon()
            .appendPath(PATH_REPO_APPS)
            .appendPath(Long.toString(repo.id))
            .appendPath(buildAppString(apps))
            .build();
    }

    public static Uri getContentUriForApks(Repo repo, List<Apk> apks) {
        return getContentUri()
            .buildUpon()
            .appendPath(PATH_REPO_APK)
            .appendPath(Long.toString(repo.id))
            .appendPath(buildApkString(apks))
            .build();
    }

    /**
     * Intentionally left protected because it will break if apks is larger than
     * {@link org.fdroid.fdroid.data.ApkProvider#MAX_APKS_TO_QUERY}. Instead of using
     * this directly, think about using
     * {@link org.fdroid.fdroid.data.ApkProvider.Helper#knownApks(android.content.Context, java.util.List, String[])}
     */
    protected static Uri getContentUri(List<Apk> apks) {
        return getContentUri().buildUpon()
                .appendPath(PATH_APKS)
                .appendPath(buildApkString(apks))
                .build();
    }

    protected static String buildApkString(List<Apk> apks) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < apks.size(); i++) {
            if (i != 0) {
                builder.append(',');
            }
            final Apk a = apks.get(i);
            builder.append(a.packageName).append(':').append(a.vercode);
        }
        return builder.toString();
    }

    private static String buildAppString(List<App> apks) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < apks.size(); i++) {
            if (i != 0) {
                builder.append(',');
            }
            builder.append(apks.get(i).packageName);
        }
        return builder.toString();
    }

    @Override
    protected String getTableName() {
        return DBHelper.TABLE_APK;
    }

    @Override
    protected String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    protected UriMatcher getMatcher() {
        return matcher;
    }

    private static class Query extends QueryBuilder {

        private boolean repoTableRequired;

        @Override
        protected String getRequiredTables() {
            return DBHelper.TABLE_APK + " AS apk";
        }

        @Override
        public void addField(String field) {
            if (REPO_FIELDS.containsKey(field)) {
                addRepoField(REPO_FIELDS.get(field), field);
            } else if (field.equals(DataColumns._ID)) {
                appendField("rowid", "apk", "_id");
            } else if (field.equals(DataColumns._COUNT)) {
                appendField("COUNT(*) AS " + DataColumns._COUNT);
            } else if (field.equals(DataColumns._COUNT_DISTINCT_ID)) {
                appendField("COUNT(DISTINCT apk.id) AS " + DataColumns._COUNT_DISTINCT_ID);
            } else {
                appendField(field, "apk");
            }
        }

        private void addRepoField(String field, String alias) {
            if (!repoTableRequired) {
                repoTableRequired = true;
                leftJoin(DBHelper.TABLE_REPO, "repo", "apk.repo = repo._id");
            }
            appendField(field, "repo", alias);
        }

    }

    private QuerySelection queryApp(String packageName) {
        final String selection = DataColumns.PACKAGE_NAME + " = ? ";
        final String[] args = {packageName};
        return new QuerySelection(selection, args);
    }

    private QuerySelection querySingle(Uri uri) {
        final String selection = " vercode = ? and id = ? ";
        final String[] args = {
            // First (0th) path segment is the word "apk",
            // and we are not interested in it.
            uri.getPathSegments().get(1),
            uri.getPathSegments().get(2),
        };
        return new QuerySelection(selection, args);
    }

    protected QuerySelection queryRepo(long repoId) {
        final String selection = DataColumns.REPO_ID + " = ? ";
        final String[] args = {Long.toString(repoId)};
        return new QuerySelection(selection, args);
    }

    private QuerySelection queryRepoApps(long repoId, String packageNames) {
        return queryRepo(repoId).add(AppProvider.queryApps(packageNames, DataColumns.PACKAGE_NAME));
    }

    protected QuerySelection queryApks(String apkKeys) {
        final String[] apkDetails = apkKeys.split(",");
        if (apkDetails.length > MAX_APKS_TO_QUERY) {
            throw new IllegalArgumentException(
                "Cannot query more than " + MAX_APKS_TO_QUERY + ". " +
                "You tried to query " + apkDetails.length);
        }
        final String[] args = new String[apkDetails.length * 2];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < apkDetails.length; i++) {
            String[] parts = apkDetails[i].split(":");
            String packageName = parts[0];
            String verCode = parts[1];
            args[i * 2] = packageName;
            args[i * 2 + 1] = verCode;
            if (i != 0) {
                sb.append(" OR ");
            }
            sb.append(" ( id = ? AND vercode = ? ) ");
        }
        return new QuerySelection(sb.toString(), args);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {

        QuerySelection query = new QuerySelection(selection, selectionArgs);

        switch (matcher.match(uri)) {
            case CODE_LIST:
                break;

            case CODE_SINGLE:
                query = query.add(querySingle(uri));
                break;

            case CODE_APP:
                query = query.add(queryApp(uri.getLastPathSegment()));
                break;

            case CODE_APKS:
                query = query.add(queryApks(uri.getLastPathSegment()));
                break;

            case CODE_REPO:
                query = query.add(queryRepo(Long.parseLong(uri.getLastPathSegment())));
                break;

            case CODE_REPO_APPS:
                List<String> pathSegments = uri.getPathSegments();
                query = query.add(queryRepoApps(Long.parseLong(pathSegments.get(1)), pathSegments.get(2)));
                break;

            default:
                Log.e(TAG, "Invalid URI for apk content provider: " + uri);
                throw new UnsupportedOperationException("Invalid URI for apk content provider: " + uri);
        }

        Query queryBuilder = new Query();
        for (final String field : projection) {
            queryBuilder.addField(field);
        }
        queryBuilder.addSelection(query.getSelection());
        queryBuilder.addOrderBy(sortOrder);

        Cursor cursor = read().rawQuery(queryBuilder.toString(), query.getArgs());
        cursor.setNotificationUri(getContext().getContentResolver(), uri);
        return cursor;
    }

    private static void removeRepoFields(ContentValues values) {
        for (Map.Entry<String, String> repoField : REPO_FIELDS.entrySet()) {
            final String field = repoField.getKey();
            if (values.containsKey(field)) {
                Utils.debugLog(TAG, "Cannot insert/update '" + field + "' field " +
                        "on apk table, as it belongs to the repo table. " +
                        "This field will be ignored.");
                values.remove(field);
            }
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        removeRepoFields(values);
        validateFields(DataColumns.ALL, values);
        write().insertOrThrow(getTableName(), null, values);
        if (!isApplyingBatch()) {
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return getContentUri(
            values.getAsString(DataColumns.PACKAGE_NAME),
            values.getAsInteger(DataColumns.VERSION_CODE));

    }

    @Override
    public int delete(Uri uri, String where, String[] whereArgs) {

        QuerySelection query = new QuerySelection(where, whereArgs);

        switch (matcher.match(uri)) {

            case CODE_REPO:
                query = query.add(queryRepo(Long.parseLong(uri.getLastPathSegment())));
                break;

            case CODE_APP:
                query = query.add(queryApp(uri.getLastPathSegment()));
                break;

            case CODE_APKS:
                query = query.add(queryApks(uri.getLastPathSegment()));
                break;

            // TODO: Add tests for this.
            case CODE_REPO_APK:
                List<String> pathSegments = uri.getPathSegments();
                query = query.add(queryRepo(Long.parseLong(pathSegments.get(1)))).add(queryApks(pathSegments.get(2)));
                break;

            case CODE_LIST:
                throw new UnsupportedOperationException("Can't delete all apks.");

            case CODE_SINGLE:
                throw new UnsupportedOperationException("Can't delete individual apks.");

            default:
                Log.e(TAG, "Invalid URI for apk content provider: " + uri);
                throw new UnsupportedOperationException("Invalid URI for apk content provider: " + uri);
        }

        int rowsAffected = write().delete(getTableName(), query.getSelection(), query.getArgs());
        getContext().getContentResolver().notifyChange(uri, null);
        return rowsAffected;

    }

    @Override
    public int update(Uri uri, ContentValues values, String where, String[] whereArgs) {
        if (matcher.match(uri) != CODE_SINGLE) {
            throw new UnsupportedOperationException("Cannot update anything other than a single apk.");
        }
        return performUpdateUnchecked(uri, values, where, whereArgs);
    }

    protected int performUpdateUnchecked(Uri uri, ContentValues values, String where, String[] whereArgs) {
        validateFields(DataColumns.ALL, values);
        removeRepoFields(values);

        QuerySelection query = new QuerySelection(where, whereArgs);
        query = query.add(querySingle(uri));

        int numRows = write().update(getTableName(), values, query.getSelection(), query.getArgs());
        if (!isApplyingBatch()) {
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return numRows;
    }

}
