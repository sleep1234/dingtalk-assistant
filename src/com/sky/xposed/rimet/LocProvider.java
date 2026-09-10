package com.sky.xposed.rimet;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * 对外暴露虚拟定位坐标，供目标应用（钉钉）的 Xposed hook 通过
 *  content://com.sky.xposed.rimet.loc/  读取。
 * 运行在模块应用自身进程，可正常读取被 HyperOS 隔离到 apexdata 的 shared_prefs。
 */
public class LocProvider extends ContentProvider {

    private static final String[] COLS = {"key", "value"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                         String[] selectionArgs, String sortOrder) {
        SharedPreferences p = getContext().getSharedPreferences("location", Context.MODE_PRIVATE);
        MatrixCursor c = new MatrixCursor(COLS, 8);
        c.addRow(new Object[]{"lat", String.valueOf(p.getFloat("lat", 0f))});
        c.addRow(new Object[]{"lng", String.valueOf(p.getFloat("lng", 0f))});
        c.addRow(new Object[]{"lat_gcj", String.valueOf(p.getFloat("lat_gcj", 0f))});
        c.addRow(new Object[]{"lng_gcj", String.valueOf(p.getFloat("lng_gcj", 0f))});
        c.addRow(new Object[]{"addr", String.valueOf(p.getString("addr", ""))});
        c.addRow(new Object[]{"ts", String.valueOf(System.currentTimeMillis())});
        return c;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/keyvalue"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String sel, String[] selArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String sel, String[] selArgs) { return 0; }
}
