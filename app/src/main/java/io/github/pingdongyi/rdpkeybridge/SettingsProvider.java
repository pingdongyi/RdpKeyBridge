package io.github.pingdongyi.rdpkeybridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 让被 LSPatch 注入的目标进程能读到模块配置（目标进程与模块 App 不同进程，
 * 直接读 SharedPreferences 会被沙箱挡住，所以用 ContentProvider.call 返回 Bundle）。
 */
public class SettingsProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.pingdongyi.rdpkeybridge.settings";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/settings");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        return Config.load(getContext()).toBundle();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
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
