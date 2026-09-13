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
    // 与 LocationHook 读取的 key 保持一致
    private static final String K_LAT = "lat";
    private static final String K_LNG = "lng";
    private static final String K_LAT_GCJ = "lat_gcj";
    private static final String K_LNG_GCJ = "lng_gcj";
    private static final String K_ADDR = "addr";
    private static final String K_TS = "ts";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                         String[] selectionArgs, String sortOrder) {
        SharedPreferences p = getContext().getSharedPreferences("location", Context.MODE_PRIVATE);
        MatrixCursor c = new MatrixCursor(COLS, 8);
        c.addRow(new Object[]{K_LAT, String.valueOf(p.getFloat(K_LAT, 0f))});
        c.addRow(new Object[]{K_LNG, String.valueOf(p.getFloat(K_LNG, 0f))});
        c.addRow(new Object[]{K_LAT_GCJ, String.valueOf(p.getFloat(K_LAT_GCJ, 0f))});
        c.addRow(new Object[]{K_LNG_GCJ, String.valueOf(p.getFloat(K_LNG_GCJ, 0f))});
        c.addRow(new Object[]{K_ADDR, String.valueOf(p.getString(K_ADDR, ""))});
        c.addRow(new Object[]{K_TS, String.valueOf(System.currentTimeMillis())});
        return c;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/keyvalue"; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String sel, String[] selArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String sel, String[] selArgs) { return 0; }
}
