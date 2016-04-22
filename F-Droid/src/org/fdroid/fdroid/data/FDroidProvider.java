package org.fdroid.fdroid.data;

import android.annotation.TargetApi;
import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentValues;
import android.content.OperationApplicationException;
import android.content.UriMatcher;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public abstract class FDroidProvider extends ContentProvider {

    public static final String AUTHORITY = "myblog.richard.vewe.fdroid.data";

    protected static final int CODE_LIST   = 1;
    protected static final int CODE_SINGLE = 2;

    private DBHelper dbHelper;

    private boolean isApplyingBatch;

    protected abstract String getTableName();

    protected abstract String getProviderName();

    /**
     * Should always be the same as the provider:name in the AndroidManifest
     */
    public final String getName() {
        return AUTHORITY + "." + getProviderName();
    }

    /**
     * Tells us if we are in the middle of a batch of operations. Allows us to
     * decide not to notify the content resolver of changes,
     * every single time we do something during many operations.
     * Based on http://stackoverflow.com/a/15886915.
     */
    protected final boolean isApplyingBatch() {
        return this.isApplyingBatch;
    }

    @Override
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
        throws OperationApplicationException {
        ContentProviderResult[] result = null;
        isApplyingBatch = true;
        write().beginTransaction();
        try {
            result = super.applyBatch(operations);
            write().setTransactionSuccessful();
        } finally {
            write().endTransaction();
            isApplyingBatch = false;
        }
        return result;
    }

    @Override
    public boolean onCreate() {
        dbHelper = new DBHelper(getContext());
        return true;
    }

    protected final DBHelper db() {
        return dbHelper;
    }

    protected final SQLiteDatabase read() {
        return db().getReadableDatabase();
    }

    protected final SQLiteDatabase write() {
        return db().getWritableDatabase();
    }

    @Override
    public String getType(Uri uri) {
        String type;
        switch (getMatcher().match(uri)) {
            case CODE_LIST:
                type = "dir";
                break;
            case CODE_SINGLE:
            default:
                type = "item";
                break;
        }
        return "vnd.android.cursor." + type + "/vnd." + AUTHORITY + "." + getProviderName();
    }

    protected abstract UriMatcher getMatcher();

    protected static String generateQuestionMarksForInClause(int num) {
        StringBuilder sb = new StringBuilder(num * 2);
        for (int i = 0; i < num; i++) {
            if (i != 0) {
                sb.append(',');
            }
            sb.append('?');
        }
        return sb.toString();
    }

    @TargetApi(11)
    protected Set<String> getKeySet(ContentValues values) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            return values.keySet();
        }

        Set<String> keySet = new HashSet<>();
        for (Map.Entry<String, Object> item : values.valueSet()) {
            String key = item.getKey();
            keySet.add(key);
        }
        return keySet;

    }

    protected void validateFields(String[] validFields, ContentValues values)
        throws IllegalArgumentException {
        for (final String key : getKeySet(values)) {
            boolean isValid = false;
            for (final String validKey : validFields) {
                if (validKey.equals(key)) {
                    isValid = true;
                    break;
                }
            }

            if (!isValid) {
                throw new IllegalArgumentException(
                    "Cannot save field '" + key + "' to provider " + getProviderName());
            }
        }
    }
}
