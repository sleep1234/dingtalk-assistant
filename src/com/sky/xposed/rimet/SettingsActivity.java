package com.sky.xposed.rimet;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.CompoundButton;
import android.widget.Toast;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.model.BitmapDescriptorFactory;
import com.amap.api.maps.model.CameraPosition;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.Marker;
import com.amap.api.maps.model.MarkerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

public class SettingsActivity extends Activity
        implements AMap.OnCameraChangeListener, AMap.OnMarkerDragListener {

    private static final String TAG = "RimetHook-Map";

    private MapView mMapView;
    private AMap mAMap;
    private Marker mMarker;
    private TextView mTvInfo;
    private SharedPreferences mPrefs;
    private LinearLayout mListLayout;

    double mLat = 28.6557, mLng = 121.4200;
    String mAddr = "";
    final ArrayList<LocItem> mLocList = new ArrayList<>();

    static class LocItem {
        String addr;
        double lat, lng;  // GCJ-02
        LocItem(String a, double la, double ln) { addr = a; lat = la; lng = ln; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestLocationPermission();

        try {
            MapsInitializer.sdcardDir = getFilesDir().getAbsolutePath();
            MapsInitializer.setApiKey("6528e2dca132967d339e407699815a8d");
            MapsInitializer.initialize(this);
        } catch (Throwable t) { Log.e(TAG, "地图初始化失败", t); }

        mPrefs = getSharedPreferences("location", MODE_PRIVATE);
        float glat = mPrefs.getFloat("lat_gcj", Float.NaN);
        float glng = mPrefs.getFloat("lng_gcj", Float.NaN);
        if (!Float.isNaN(glat) && !Float.isNaN(glng)) {
            mLat = glat; mLng = glng;
        } else {
            double[] g = wgs84ToGcj02(mPrefs.getFloat("lat", 28.6557f), mPrefs.getFloat("lng", 121.4200f));
            mLat = g[0]; mLng = g[1];
        }
        mAddr = mPrefs.getString("addr", "");

        loadList();

        // 整体 ScrollView
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 页面标题
        TextView tvTitle = new TextView(this);
        tvTitle.setText("钉钉助手-复活版");
        tvTitle.setTextSize(22);
        tvTitle.setTextColor(0xFF222222);
        tvTitle.setPadding(dpPx(12), dpPx(14), dpPx(12), dpPx(2));
        root.addView(tvTitle);

        // 作者（标题下方）
        TextView tvAuthor = new TextView(this);
        tvAuthor.setText("作者：毛利老王");
        tvAuthor.setTextSize(12);
        tvAuthor.setTextColor(0xFF999999);
        tvAuthor.setPadding(dpPx(12), dpPx(0), dpPx(12), dpPx(6));
        root.addView(tvAuthor);

        // 功能描述
        TextView tvDesc = new TextView(this);
        tvDesc.setText("功能：\n• 虚拟定位（地图选点 + 位置列表快速切换）\n• 防撤回（对方撤回仍可见）\n• 开发者选项/调试/root 检测绕过\n\n用法：拖动地图选点 → 保存当前位置 → 重启钉钉生效。\n关闭「启用虚拟定位」开关即可读取真实位置。");
        tvDesc.setTextSize(12);
        tvDesc.setTextColor(0xFF666666);
        tvDesc.setPadding(dpPx(12), dpPx(4), dpPx(12), dpPx(8));
        root.addView(tvDesc);

        // 地图 320dp
        mMapView = new MapView(this);
        mMapView.onCreate(savedInstanceState);
        FrameLayout mapWrap = new FrameLayout(this);
        mapWrap.addView(mMapView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dpPx(320)));
        root.addView(mapWrap);

        // 信息栏
        mTvInfo = new TextView(this);
        mTvInfo.setTextSize(13);
        mTvInfo.setPadding(dpPx(12), dpPx(8), dpPx(12), 0);
        root.addView(mTvInfo);

        // 按钮行（保存 + 定位）
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button bSave = new Button(this);
        bSave.setText("保存当前位置");
        bSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { promptSave(); }
        });
        btns.addView(bSave);
        Button bLoc = new Button(this);
        bLoc.setText("定位到我的位置");
        bLoc.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { locateCurrent(); }
        });
        btns.addView(bLoc);

        // 虚拟定位开关
        final Switch sw = new Switch(this);
        sw.setText("启用虚拟定位");
        sw.setChecked(isSwitchOn());
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                setSwitchOn(on ? "1" : "0");
                toast("虚拟定位已" + (on ? "开启" : "关闭"));
            }
        });
        btns.addView(sw);
        root.addView(btns);

        // 位置列表标题
        TextView tvList = new TextView(this);
        tvList.setText("▸ 位置列表（点击切换，长按删除）");
        tvList.setTextSize(13);
        tvList.setPadding(dpPx(12), dpPx(12), dpPx(12), dpPx(4));
        root.addView(tvList);

        // 列表容器
        mListLayout = new LinearLayout(this);
        mListLayout.setOrientation(LinearLayout.VERTICAL);
        mListLayout.setPadding(dpPx(12), 0, dpPx(12), 0);
        root.addView(mListLayout);

        refreshListView();

        scroll.addView(root);
        setContentView(scroll);

        // 地图
        mAMap = mMapView.getMap();
        mAMap.setOnCameraChangeListener(this);
        mAMap.setOnMarkerDragListener(this);
        mAMap.getUiSettings().setZoomControlsEnabled(false);
        mAMap.setOnMapLoadedListener(new AMap.OnMapLoadedListener() {
            @Override
            public void onMapLoaded() {
                mMarker = mAMap.addMarker(new MarkerOptions()
                    .position(new LatLng(mLat, mLng))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    .title("选点").draggable(true));
                Log.i(TAG, "标记点已创建");
            }
        });
        mAMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(mLat, mLng), 16));

        updateText();
    }

    // ====== 地图事件 ======
    @Override public void onCameraChange(CameraPosition pos) {}
    @Override public void onCameraChangeFinish(CameraPosition pos) {
        LatLng t = pos.target;
        mLat = t.latitude; mLng = t.longitude;
        if (mMarker != null) mMarker.setPosition(t);
        else {
            mMarker = mAMap.addMarker(new MarkerOptions()
                .position(t).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                .title("选点").draggable(true));
        }
        updateText();
    }
    @Override public void onMarkerDrag(Marker m) {}
    @Override public void onMarkerDragEnd(Marker m) {
        LatLng p = m.getPosition();
        mLat = p.latitude; mLng = p.longitude;
        updateText();
    }
    @Override public void onMarkerDragStart(Marker m) {}

    void updateText() {
        mTvInfo.setText(String.format("当前: %.6f, %.6f  %s", mLat, mLng, mAddr.isEmpty() ? "" : "(" + mAddr + ")"));
    }

    // ====== 定位 ======
    void locateCurrent() {
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestLocationPermission(); toast("请先授予定位权限"); return;
        }
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) { toast("无法获取定位服务"); return; }
            Location last = null;
            try { last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (Throwable ignored) {}
            if (last == null)
                try { last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (Throwable ignored) {}
            if (last != null) {
                double[] g = wgs84ToGcj02(last.getLatitude(), last.getLongitude());
                moveMap(g[0], g[1]); return;
            }
            final boolean[] done = {false};
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 1f, new LocationListener() {
                public void onLocationChanged(Location l) {
                    if (!done[0]) { done[0] = true; double[] g = wgs84ToGcj02(l.getLatitude(), l.getLongitude()); moveMap(g[0], g[1]); }
                }
                public void onStatusChanged(String p, int s, Bundle b) {}
                public void onProviderEnabled(String p) {}
                public void onProviderDisabled(String p) {}
            });
            toast("正在定位…");
        } catch (Throwable t) { toast("定位失败: " + t.getMessage()); }
    }

    void moveMap(double gcjLat, double gcjLng) {
        mLat = gcjLat; mLng = gcjLng;
        if (mMarker != null) mMarker.setPosition(new LatLng(gcjLat, gcjLng));
        else mMarker = mAMap.addMarker(new MarkerOptions()
            .position(new LatLng(gcjLat, gcjLng))
            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
            .title("选点").draggable(true));
        mAMap.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(gcjLat, gcjLng), 15));
        updateText();
    }

    // ====== 保存（弹框输入地址名） ======
    void promptSave() {
        if (((int)(mLat * 1e6)) == 0 || ((int)(mLng * 1e6)) == 0) {
            toast("经纬度无效，请选点"); return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("给这个位置起个名字");
        final EditText input = new EditText(this);
        input.setHint("例如：台州市中心医院、公司、家");
        input.setText(mAddr.isEmpty() ? String.format("%.4f,%.4f", mLat, mLng) : mAddr);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        b.setView(input);
        b.setPositiveButton("保存", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) name = String.format("%.4f,%.4f", mLat, mLng);
                mAddr = name;
                saveLocation(name);
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    /** 用 su(root) 写 Settings.System（钉钉 hook 端免权限读） */
    boolean writeSettingsToSystem(double gcjLat, double gcjLng, double wgsLat, double wgsLng, String addr) {
        try {
            String cmd = "settings put system rimet_lat_gcj " + gcjLat
                + " && settings put system rimet_lng_gcj " + gcjLng
                + " && settings put system rimet_lat " + wgsLat
                + " && settings put system rimet_lng " + wgsLng
                + " && settings put system rimet_addr '" + (addr == null ? "" : addr.replace("'", "\\'")) + "'";
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            int rc = p.waitFor();
            if (rc != 0) { toast("root 写入失败 (exit=" + rc + ")"); return false; }
            return true;
        } catch (Exception ex) {
            toast("root 写入异常: " + ex.getMessage());
            return false;
        }
    }

    void saveLocation(String name) {
        double[] wgs = gcj02ToWgs84(mLat, mLng);

        // 写入当前激活坐标
        mPrefs.edit()
            .putFloat("lat", (float) wgs[0])
            .putFloat("lng", (float) wgs[1])
            .putFloat("lat_gcj", (float) mLat)
            .putFloat("lng_gcj", (float) mLng)
            .putString("addr", name)
            .commit();

        // 追加到位置列表
        addToList(name, mLat, mLng, wgs[0], wgs[1]);

        // root 写 Settings.System
        try {
            String cmd = "settings put system rimet_lat_gcj " + mLat
                + " && settings put system rimet_lng_gcj " + mLng
                + " && settings put system rimet_lat " + wgs[0]
                + " && settings put system rimet_lng " + wgs[1]
                + " && settings put system rimet_addr '" + name.replace("'", "\'") + "'";
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            int rc = p.waitFor();
            if (rc != 0) toast("root 写入失败 (exit=" + rc + ")");
            else {
                toast("已保存: " + name);
                updateText();
                refreshListView();
            }
        } catch (Exception ex) {
            toast("root 写入异常: " + ex.getMessage());
        }
    }

    // ====== 位置列表管理 ======
    void loadList() {
        mLocList.clear();
        try {
            String json = mPrefs.getString("location_list", "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                mLocList.add(new LocItem(o.getString("addr"), o.getDouble("lat_gcj"), o.getDouble("lng_gcj")));
            }
        } catch (Exception ignored) {}
    }

    void addToList(String addr, double gcjLat, double gcjLng, double wgsLat, double wgsLng) {
        // 去重：相同地址名的替换
        for (int i = 0; i < mLocList.size(); i++) {
            if (mLocList.get(i).addr.equals(addr)) {
                mLocList.set(i, new LocItem(addr, gcjLat, gcjLng));
                saveList();
                return;
            }
        }
        mLocList.add(new LocItem(addr, gcjLat, gcjLng));
        saveList();
    }

    void saveList() {
        try {
            JSONArray arr = new JSONArray();
            for (LocItem it : mLocList) {
                JSONObject o = new JSONObject();
                o.put("addr", it.addr);
                o.put("lat_gcj", it.lat);
                o.put("lng_gcj", it.lng);
                arr.put(o);
            }
            mPrefs.edit().putString("location_list", arr.toString()).commit();
        } catch (Exception ignored) {}
    }

    void refreshListView() {
        mListLayout.removeAllViews();
        for (int i = 0; i < mLocList.size(); i++) {
            final LocItem it = mLocList.get(i);
            final int idx = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dpPx(4), 0, dpPx(4));

            TextView tv = new TextView(this);
            tv.setText(it.addr + "  [" + String.format("%.4f,%.4f", it.lat, it.lng) + "]");
            tv.setTextSize(13);
            tv.setPadding(dpPx(8), dpPx(6), dpPx(8), dpPx(6));
            tv.setBackgroundColor(0xFFEEEEEE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(lp);

            // 点击 → 切换到该位置
            tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    moveMap(it.lat, it.lng);
                    mAddr = it.addr;
                    updateText();
                    // 切换后立即写 Settings.System，钉钉实时生效
                    double[] wgs = gcj02ToWgs84(it.lat, it.lng);
                    if (writeSettingsToSystem(it.lat, it.lng, wgs[0], wgs[1], it.addr)) {
                        toast("已切换到: " + it.addr);
                    }
                }
            });

            // 长按 → 删除
            tv.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("删除 " + it.addr + " ?")
                        .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                removeFromList(idx);
                                refreshListView();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
                    return true;
                }
            });

            row.addView(tv);
            mListLayout.addView(row);
        }
        if (mLocList.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("（暂无保存的位置）");
            empty.setTextSize(12);
            empty.setTextColor(0xFF888888);
            empty.setPadding(dpPx(8), dpPx(6), 0, 0);
            mListLayout.addView(empty);
        }
    }

    void removeFromList(int idx) {
        if (idx >= 0 && idx < mLocList.size()) {
            mLocList.remove(idx);
            saveList();
            toast("已删除");
        }
    }

    // ====== 开关 ======
    boolean isSwitchOn() {
        try {
            String v = Settings.System.getString(getContentResolver(), "rimet_enabled");
            return v == null || v.equals("1");  // 默认开
        } catch (Throwable ignored) {}
        return true;
    }
    void setSwitchOn(String v) {
        try {
            Runtime.getRuntime().exec(new String[]{"su", "-c", "settings put system rimet_enabled " + v}).waitFor();
        } catch (Exception ignored) {}
        try {
            Settings.System.putString(getContentResolver(), "rimet_enabled", v);
        } catch (Exception ignored) {}
    }

    // ====== 坐标系转换 ======
    static final double A = 6378245.0;
    static final double EE = 0.00669342162296594323;
    static boolean outOfChina(double lat, double lng) {
        return (lng < 72.004) || (lng > 137.8347) || (lat < 0.8293) || (lat > 55.8271);
    }
    static double transformLat(double x, double y) {
        double r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        r += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        r += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        r += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320.0 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return r;
    }
    static double transformLng(double x, double y) {
        double r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        r += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        r += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        r += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return r;
    }
    static double[] wgs84ToGcj02(double wLat, double wLng) {
        if (outOfChina(wLat, wLng)) return new double[]{wLat, wLng};
        double dLat = transformLat(wLng - 105.0, wLat - 35.0);
        double dLng = transformLng(wLng - 105.0, wLat - 35.0);
        double radLat = wLat / 180.0 * Math.PI;
        double m = Math.sin(radLat); m = 1 - EE * m * m;
        double s = Math.sqrt(m);
        return new double[]{wLat + (dLat * 180.0) / ((A * (1 - EE)) / (s * s * s) * Math.PI),
                           wLng + (dLng * 180.0) / (A / s * Math.cos(radLat) * Math.PI)};
    }
    static double[] gcj02ToWgs84(double gcjLat, double gcjLng) {
        if (outOfChina(gcjLat, gcjLng)) return new double[]{gcjLat, gcjLng};
        double dLat = transformLat(gcjLng - 105.0, gcjLat - 35.0);
        double dLng = transformLng(gcjLng - 105.0, gcjLat - 35.0);
        double radLat = gcjLat / 180.0 * Math.PI;
        double m = Math.sin(radLat); m = 1 - EE * m * m;
        double s = Math.sqrt(m);
        return new double[]{gcjLat - (dLat * 180.0) / ((A * (1 - EE)) / (s * s * s) * Math.PI),
                           gcjLng - (dLng * 180.0) / (A / s * Math.cos(radLat) * Math.PI)};
    }

    // ====== 权限 ======
    static final int REQ = 100;
    void requestLocationPermission() {
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ);
        }
    }
    @Override
    public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        super.onRequestPermissionsResult(c, p, r);
        if (c == REQ) {
            boolean ok = true; for (int v : r) if (v != PackageManager.PERMISSION_GRANTED) ok = false;
            toast(ok ? "定位权限已授予" : "未授予定位权限");
        }
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    int dpPx(int dp) { return (int)(dp * getResources().getDisplayMetrics().density); }

    @Override protected void onResume() { super.onResume(); mMapView.onResume(); }
    @Override protected void onPause() { super.onPause(); mMapView.onPause(); }
    @Override protected void onDestroy() { super.onDestroy(); mMapView.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle st) { super.onSaveInstanceState(st); mMapView.onSaveInstanceState(st); }
}
