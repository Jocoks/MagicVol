package io.github.jocoks.magicvol.ui;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import io.github.jocoks.magicvol.feature.Constants;
import io.github.jocoks.magicvol.feature.SettingsContract;
import io.github.jocoks.magicvol.feature.StepTable;

/**
 * 只读设置出口：把 9 个档位的百分比暴露给 system_server 里的钩子。
 *
 * <p>为什么必须有它：钩子跑在 system_server（uid 1000），拿不到模块本体的
 * {@code /data/data/.../shared_prefs}。系统的媒体库、联系人都是同一套机制，
 * 这是"一个 App 把自己的只读数据给系统进程用"的标准做法。
 *
 * <p>只实现 {@link #query}，insert/update/delete 全部空实现 —— 外部没有任何写入面。
 */
public class SettingsProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        if (SettingsContract.PATH_DEBUG.equals(uri.getLastPathSegment())) {
            MatrixCursor debug = new MatrixCursor(new String[]{SettingsContract.COL_ON});
            SharedPreferences p = getContext() == null ? null
                    : getContext().getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
            debug.addRow(new Object[]{p != null && p.getBoolean(Constants.KEY_DEBUG, false) ? 1 : 0});
            return debug;
        }
        int[] table = readTable();
        MatrixCursor cursor = new MatrixCursor(
                new String[]{SettingsContract.COL_STEP, SettingsContract.COL_PCT});
        for (int i = 0; i < table.length; i++) {
            cursor.addRow(new Object[]{i + 1, table[i]});
        }
        return cursor;
    }

    private int[] readTable() {
        Context ctx = getContext();
        SharedPreferences p = ctx == null
                ? null
                : ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
        int[] raw = new int[Constants.CUSTOM_STEPS];
        for (int i = 0; i < raw.length; i++) {
            int fallback = StepTable.defaultPercent(i + 1);
            raw[i] = p == null ? fallback : p.getInt(Constants.key(i + 1), fallback);
        }
        return StepTable.sanitize(raw);
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.magicvol.table";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
