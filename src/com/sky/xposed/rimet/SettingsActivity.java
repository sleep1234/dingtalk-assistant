package com.sky.xposed.rimet;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

public class SettingsActivity extends Activity
        implements AMap.OnCameraChangeListener, AMap.OnMarkerDragListener {

    private static final String TAG = "RimetHook-Map";
    // 高德地图 Android key（地图 SDK 用，需要 SHA1 签名校验）
    private static final String AMAP_ANDROID_KEY = "aa2252ec1899d2284d514c19d0f7677e";
    // 高德 Web 服务 Key（逆编码用，不依赖 SHA1 签名校验）
    private static final String AMAP_WEB_KEY = "1d0d90f9061bf9d9587de84919c09afe";

    // 主题色：钉钉蓝
    private static final int COLOR_PRIMARY = 0xFF0089FF;
    private static final int COLOR_BG = 0xFFF5F6F8;
    private static final int COLOR_CARD = 0xFFFFFFFF;
    private static final int COLOR_TEXT_MAIN = 0xFF1F2329;
    private static final int COLOR_TEXT_SUB = 0xFF86909C;

    private MapView mMapView;
    private AMap mAMap;
    private Marker mMarker;       // 红色：已保存的激活位置（不可拖动）
    private Marker mDraggingMarker; // 蓝色：当前地图中心选点（可拖动）
    private TextView mTvInfo;
    private SharedPreferences mPrefs;
    private android.widget.ListView mListView;
    private android.widget.BaseAdapter mListAdapter;
    private String mCurAddr = "";   // 逆编码得到的当前地址

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

        // 隐藏系统标题栏（HyperOS 下 ActionBar 会显示 activity label）
        android.app.ActionBar ab = getActionBar();
        if (ab != null) ab.hide();
        setTitle(null);

        // 状态栏沉浸：状态栏与标题栏同色（钉钉蓝），内容延伸到状态栏下方
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            getWindow().setStatusBarColor(COLOR_PRIMARY);
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }

        requestLocationPermission();

        try {
            MapsInitializer.sdcardDir = getFilesDir().getAbsolutePath();
            // 高德 SDK 从 AndroidManifest.xml 的 <meta-data com.amap.api.v2.apikey> 读取 key，
            // setApiKey 只是显式再确认一次（某些离线构建场景有用）
            MapsInitializer.setApiKey(AMAP_ANDROID_KEY);
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

        // ============ 现代化布局 ============
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        // --- 顶部标题栏（渐变蓝底） ---
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        // 顶部留出状态栏高度，避免标题被状态栏遮挡（减少标题栏垂直间距，为列表留空间）
        header.setPadding(dpPx(20), getStatusBarHeight() + dpPx(10), dpPx(20), dpPx(10));
        header.setBackground(rounded(COLOR_PRIMARY, 0, 0, dpPx(24), dpPx(24)));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("钉钉助手-复活");
        tvTitle.setTextSize(20);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(tvTitle);

        TextView tvSubtitle = new TextView(this);
        tvSubtitle.setText("虚拟定位 · 防撤回 · 安全绕过");
        tvSubtitle.setTextSize(12);
        tvSubtitle.setTextColor(0xB3FFFFFF);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dpPx(2);
        header.addView(tvSubtitle, subLp);

        root.addView(header);

        // --- 功能说明卡片 ---
        TextView tvDesc = new TextView(this);
        tvDesc.setText("拖动地图选点后保存，即可在钉钉中虚拟定位到该位置。\n关闭下方开关可恢复真实位置。");
        tvDesc.setTextSize(13);
        tvDesc.setTextColor(COLOR_TEXT_SUB);
        tvDesc.setPadding(dpPx(16), dpPx(12), dpPx(16), dpPx(12));
        tvDesc.setBackground(rounded(COLOR_CARD, dpPx(12)));
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.leftMargin = dpPx(12);
        descLp.rightMargin = dpPx(12);
        descLp.topMargin = dpPx(12);
        root.addView(tvDesc, descLp);

        // --- 地图卡片 ---
        FrameLayout mapWrap = new FrameLayout(this);
        mMapView = new MapView(this);
        mMapView.onCreate(savedInstanceState);
        mapWrap.addView(mMapView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dpPx(220)));
        mapWrap.setBackground(rounded(COLOR_CARD, dpPx(12)));
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpPx(220));
        mapLp.leftMargin = dpPx(12);
        mapLp.rightMargin = dpPx(12);
        mapLp.topMargin = dpPx(8);
        root.addView(mapWrap, mapLp);

        // --- 信息栏（当前坐标） ---
        mTvInfo = new TextView(this);
        mTvInfo.setTextSize(14);
        mTvInfo.setTextColor(COLOR_TEXT_MAIN);
        mTvInfo.setTypeface(Typeface.MONOSPACE);
        mTvInfo.setPadding(dpPx(16), dpPx(10), dpPx(16), dpPx(10));
        mTvInfo.setBackground(rounded(COLOR_CARD, dpPx(12)));
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.leftMargin = dpPx(12);
        infoLp.rightMargin = dpPx(12);
        infoLp.topMargin = dpPx(12);
        root.addView(mTvInfo, infoLp);

        // --- 按钮行 ---
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(dpPx(12), dpPx(12), dpPx(12), 0);

        Button bSave = primaryButton("保存当前位置");
        bSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { promptSave(); }
        });
        btns.addView(bSave, new LinearLayout.LayoutParams(0, dpPx(44), 1f));

        Button bLoc = ghostButton("定位到我的位置");
        bLoc.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { locateCurrent(); }
        });
        LinearLayout.LayoutParams locLp = new LinearLayout.LayoutParams(0, dpPx(44), 1f);
        locLp.leftMargin = dpPx(10);
        btns.addView(bLoc, locLp);

        root.addView(btns);

        // --- 开关卡片 ---
        LinearLayout switchCard = new LinearLayout(this);
        switchCard.setOrientation(LinearLayout.HORIZONTAL);
        switchCard.setGravity(Gravity.CENTER_VERTICAL);
        switchCard.setPadding(dpPx(16), dpPx(6), dpPx(16), dpPx(6));
        switchCard.setBackground(rounded(COLOR_CARD, dpPx(12)));
        LinearLayout.LayoutParams switchCardLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        switchCardLp.leftMargin = dpPx(12);
        switchCardLp.rightMargin = dpPx(12);
        switchCardLp.topMargin = dpPx(12);
        root.addView(switchCard, switchCardLp);

        TextView swLabel = new TextView(this);
        swLabel.setText("启用虚拟定位");
        swLabel.setTextSize(15);
        swLabel.setTextColor(COLOR_TEXT_MAIN);
        switchCard.addView(swLabel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = new Switch(this);
        sw.setChecked(isSwitchOn());
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                setSwitchOn(on);
                toast("虚拟定位已" + (on ? "开启" : "关闭"));
            }
        });
        switchCard.addView(sw);

        // --- 位置列表标题 ---
        TextView tvList = new TextView(this);
        tvList.setText("位置列表");
        tvList.setTextSize(15);
        tvList.setTextColor(COLOR_TEXT_MAIN);
        tvList.setTypeface(Typeface.DEFAULT_BOLD);
        tvList.setPadding(dpPx(16), dpPx(16), dpPx(16), dpPx(4));
        root.addView(tvList);

        TextView tvListHint = new TextView(this);
        tvListHint.setText("点击切换 · 长按删除");
        tvListHint.setTextSize(12);
        tvListHint.setTextColor(COLOR_TEXT_SUB);
        tvListHint.setPadding(dpPx(16), 0, dpPx(16), dpPx(8));
        root.addView(tvListHint);

        // 列表容器（独立滚动的 ListView，自定义适配器缩小字号+分割线）
        mListView = new android.widget.ListView(this);
        mListView.setDivider(null);
        mListView.setBackgroundColor(COLOR_CARD);
        final int itemPadH = dpPx(16);
        final int itemPadV = dpPx(11);
        mListAdapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return mLocList.size(); }
            @Override public Object getItem(int i) { return mLocList.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override
            public View getView(int i, View v, ViewGroup parent) {
                LinearLayout item = new LinearLayout(SettingsActivity.this);
                item.setOrientation(LinearLayout.VERTICAL);
                item.setPadding(itemPadH, itemPadV, itemPadH, itemPadV);
                item.setBackgroundColor(COLOR_CARD);

                TextView tv = new TextView(SettingsActivity.this);
                tv.setTextSize(13);
                tv.setTextColor(COLOR_TEXT_MAIN);
                LocItem it = mLocList.get(i);
                tv.setText(it.addr + "  ·  " + String.format("%.4f, %.4f", it.lat, it.lng));
                item.addView(tv);

                // 分割线
                View divider = new View(SettingsActivity.this);
                divider.setBackgroundColor(0xFFE0E0E0);
                LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpPx(1));
                item.addView(divider, dp);

                return item;
            }
        };
        mListView.setAdapter(mListAdapter);
        mListView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                switchTo(position);
            }
        });
        mListView.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                confirmRemove(position);
                return true;
            }
        });
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        listLp.leftMargin = dpPx(12);
        listLp.rightMargin = dpPx(12);
        listLp.topMargin = dpPx(4);
        listLp.bottomMargin = dpPx(12);
        root.addView(mListView, listLp);

        refreshListView();

        setContentView(root);

        // 地图
        mAMap = mMapView.getMap();
        mAMap.setOnCameraChangeListener(this);
        mAMap.setOnMarkerDragListener(this);
        mAMap.getUiSettings().setZoomControlsEnabled(false);
        mAMap.setOnMapLoadedListener(new AMap.OnMapLoadedListener() {
            @Override
            public void onMapLoaded() {
                // 红色标记：已保存的激活位置（不可拖动，标识当前生效坐标）
                mMarker = mAMap.addMarker(new MarkerOptions()
                    .position(new LatLng(mLat, mLng))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    .title("生效位置").draggable(false));
                // 蓝色标记：当前地图中心选点（可拖动，显示要选的位置）
                mDraggingMarker = mAMap.addMarker(new MarkerOptions()
                    .position(new LatLng(mLat, mLng))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
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
        // 地图拖动：移动蓝色选点标点到新中心（红色生效位置保持不变）
        if (mDraggingMarker != null) mDraggingMarker.setPosition(t);
        else {
            mDraggingMarker = mAMap.addMarker(new MarkerOptions()
                .position(t).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                .title("选点").draggable(true));
        }
        updateText();
        doRegeocode(mLat, mLng);
    }
    @Override public void onMarkerDrag(Marker m) {}
    @Override public void onMarkerDragEnd(Marker m) {
        if (m == mDraggingMarker) {
            LatLng p = m.getPosition();
            mLat = p.latitude; mLng = p.longitude;
            updateText();
            doRegeocode(mLat, mLng);
        }
    }
    @Override public void onMarkerDragStart(Marker m) {}

    void updateText() {
        String addrPart = mCurAddr.isEmpty() ? (mAddr.isEmpty() ? "" : mAddr) : mCurAddr;
        mTvInfo.setText(String.format("%.6f, %.6f\n%s", mLat, mLng, addrPart));
    }

    /** 逆编码查询当前坐标的地址（HTTP 方式，绕过签名校验） */
    private void doRegeocode(final double lat, final double lng) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    String location = lng + "," + lat;
                    String url = "https://restapi.amap.com/v3/geocode/regeo?key=" + AMAP_WEB_KEY
                        + "&location=" + URLEncoder.encode(location, "UTF-8")
                        + "&output=json&radius=1000&extensions=base";
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);

                    BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();

                    JSONObject json = new JSONObject(sb.toString());
                    if ("1".equals(json.optString("status"))) {
                        JSONObject regeo = json.optJSONObject("regeocode");
                        if (regeo != null) {
                            String addr = regeo.optString("formatted_address");
                            if (addr != null && !addr.isEmpty()) {
                                mCurAddr = addr;
                                Log.i(TAG, "逆编码地址: " + addr);
                                runOnUiThread(new Runnable() {
                                    @Override public void run() { updateText(); }
                                });
                            }
                        }
                    } else {
                        Log.w(TAG, "逆编码失败: " + json.optString("info"));
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "逆编码异常: " + t.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
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
        // 红色生效位置标记移动到新位置
        if (mMarker != null) mMarker.setPosition(new LatLng(gcjLat, gcjLng));
        else mMarker = mAMap.addMarker(new MarkerOptions()
            .position(new LatLng(gcjLat, gcjLng))
            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
            .title("生效位置").draggable(false));
        // 蓝色选点标记也跟随到同一位置
        if (mDraggingMarker != null) mDraggingMarker.setPosition(new LatLng(gcjLat, gcjLng));
        else mDraggingMarker = mAMap.addMarker(new MarkerOptions()
            .position(new LatLng(gcjLat, gcjLng))
            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
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
        // 优先复用逆编码得到的地址，其次用已保存名称，最后用坐标
        String defaultName = mCurAddr;
        if (defaultName == null || defaultName.isEmpty()) defaultName = mAddr;
        if (defaultName == null || defaultName.isEmpty()) defaultName = String.format("%.4f,%.4f", mLat, mLng);
        input.setText(defaultName);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        b.setView(input);
        b.setPositiveButton("保存", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) name = mCurAddr.isEmpty() ? String.format("%.4f,%.4f", mLat, mLng) : mCurAddr;
                mAddr = name;
                saveLocation(name);
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    void saveLocation(String name) {
        double[] wgs = gcj02ToWgs84(mLat, mLng);

        // 写公共定位文件 /data/local/tmp/rimet_location.txt（通过 root，避開 Scoped Storage）
        writePublicFile(wgs, mLat, mLng, name);

        // 也写 SharedPreferences（如果生效的话），fixPrefsPermission 尝试修权限
        try {
            mPrefs.edit()
                .putFloat("lat", (float) wgs[0])
                .putFloat("lng", (float) wgs[1])
                .putFloat("lat_gcj", (float) mLat)
                .putFloat("lng_gcj", (float) mLng)
                .putString("addr", name)
                .commit();
            fixPrefsPermission();
        } catch (Throwable t) {
            Log.e(TAG, "prefs 写入失败", t);
        }

        addToList(name, mLat, mLng, wgs[0], wgs[1]);

        toast("已保存: " + name);
        updateText();
        refreshListView();
    }

    /**
     * 通过 root 写 /data/local/tmp/rimet_location.txt，钉钉 LocationHook 直接读取。
     * enabled 键保留文件中原有的值（不覆盖），若文件不存在则默认 enabled=1。
     */
    private void writePublicFile(double[] wgs, double gcjLat, double gcjLng, String addr) {
        try {
            // 先尝试用 sed 原地更新已有的坐标行（不覆盖 enabled）
            String sedLatGcj = "sed -i '/^lat_gcj=/c\\lat_gcj=" + gcjLat + "' " + PUBLIC_FILE;
            String sedLngGcj = "sed -i '/^lng_gcj=/c\\lng_gcj=" + gcjLng + "' " + PUBLIC_FILE;
            String sedLat = "sed -i '/^lat=/c\\lat=" + (float) wgs[0] + "' " + PUBLIC_FILE;
            String sedLng = "sed -i '/^lng=/c\\lng=" + (float) wgs[1] + "' " + PUBLIC_FILE;
            String sedAddr = "sed -i '/^addr=/c\\addr=" + addr + "' " + PUBLIC_FILE;
            String cmd = "if [ ! -f " + PUBLIC_FILE + " ]; then echo enabled=1 > " + PUBLIC_FILE + "; fi" 
                + "; " + sedLatGcj + "; " + sedLngGcj + "; " + sedLat + "; " + sedLng + "; " + sedAddr;
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.waitFor();
            Log.i(TAG, "公共定位文件已写入: " + PUBLIC_FILE);
        } catch (Throwable t) {
            Log.e(TAG, "写入公共定位文件失败", t);
        }
    }

    private static final String PUBLIC_FILE = "/data/local/tmp/rimet_location.txt";

    /**
     * 关键：把 prefs 文件设为所有进程可读（0644）。
     * LSPosed 重定向后的 prefs 文件默认是 660（仅模块 uid 可读），
     * 钉钉进程（不同 uid）读不到。这里修复权限，让跨进程读取生效。
     */
    private void fixPrefsPermission() {
        try {
            java.io.File f = new java.io.File(
                getApplicationInfo().dataDir + "/shared_prefs/location.xml");
            if (f.exists()) {
                f.setReadable(true, false);   // 所有进程可读
                java.io.File parent = f.getParentFile();
                if (parent != null) {
                    parent.setReadable(true, false);
                    parent.setExecutable(true, false);  // 目录需可遍历
                }
            }
        } catch (Throwable ignored) {}
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
        mListAdapter.notifyDataSetChanged();
    }

    /** 点击位置列表切换 */
    void switchTo(int idx) {
        if (idx < 0 || idx >= mLocList.size()) return;
        final LocItem it = mLocList.get(idx);
        moveMap(it.lat, it.lng);
        mAddr = it.addr;
        // 写入激活坐标到 SharedPreferences（LSPosed 自动共享，钉钉实时生效）
        double[] wgs = gcj02ToWgs84(it.lat, it.lng);
        mPrefs.edit()
            .putFloat("lat", (float) wgs[0])
            .putFloat("lng", (float) wgs[1])
            .putFloat("lat_gcj", (float) it.lat)
            .putFloat("lng_gcj", (float) it.lng)
            .putString("addr", it.addr)
            .commit();
        fixPrefsPermission();
        // 同步写公共文件（钉钉 LocationHook 第一优先级读取通道）
        writePublicFile(wgs, it.lat, it.lng, it.addr);
        updateText();
        toast("已切换到: " + it.addr);
    }

    /** 长按删除确认 */
    void confirmRemove(final int idx) {
        if (idx < 0 || idx >= mLocList.size()) return;
        new AlertDialog.Builder(SettingsActivity.this)
            .setTitle("删除 " + mLocList.get(idx).addr + " ?")
            .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    removeFromList(idx);
                    refreshListView();
                }
            })
            .setNegativeButton("取消", null)
            .show();
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
        // 从 SharedPreferences 读开关（LocationHook 同源读取）
        try {
            if (mPrefs.contains("enabled")) {
                try {
                    return mPrefs.getBoolean("enabled", true);
                } catch (Throwable t) {
                    String v = mPrefs.getString("enabled", "1");
                    return !"0".equals(v) && !"false".equalsIgnoreCase(v);
                }
            }
        } catch (Throwable ignored) {}
        return true;  // 默认开启
    }

    void setSwitchOn(final boolean on) {
        mPrefs.edit().putBoolean("enabled", on).commit();
        fixPrefsPermission();
        // 更新公共文件的 enabled 键（钉钉 LocationHook 读取此键控制开关）
        try {
            Process p = Runtime.getRuntime().exec(
                new String[]{"su", "-c", "sed -i 's/^enabled=.*/enabled=" + (on ? "1" : "0") + "/' " + PUBLIC_FILE});
            p.waitFor();
        } catch (Throwable ignored) {}
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

    /** 获取状态栏高度（px） */
    private int getStatusBarHeight() {
        int result = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = getResources().getDimensionPixelSize(resourceId);
        }
        return result;
    }

    /** 生成圆角背景 */
    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }
    private GradientDrawable rounded(int color, float topLeft, float topRight, float bottomRight, float bottomLeft) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadii(new float[]{topLeft, topLeft, topRight, topRight, bottomRight, bottomRight, bottomLeft, bottomLeft});
        return d;
    }

    /** 生成主按钮（钉钉蓝、白字、圆角） */
    private Button primaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackground(rounded(COLOR_PRIMARY, dpPx(8)));
        return b;
    }

    /** 生成次按钮（透明底、蓝字、蓝边框） */
    private Button ghostButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setTextColor(COLOR_PRIMARY);
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(dpPx(8));
        bg.setStroke(dpPx(1), COLOR_PRIMARY);
        b.setBackground(bg);
        return b;
    }

    @Override protected void onResume() { super.onResume(); mMapView.onResume(); }
    @Override protected void onPause() { super.onPause(); mMapView.onPause(); }
    @Override protected void onDestroy() { super.onDestroy(); mMapView.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle st) { super.onSaveInstanceState(st); mMapView.onSaveInstanceState(st); }
}
