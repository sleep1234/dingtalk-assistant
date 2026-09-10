package com.sky.rimet.hook;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 虚拟定位设置界面
 */
public class SettingsActivity extends Activity {

    private EditText etLat, etLng, etAddr, etProvince, etCity, etDistrict;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("location", MODE_PRIVATE);

        float lat = prefs.getFloat("lat", 39.9087f);
        float lng = prefs.getFloat("lng", 116.3975f);
        String addr = prefs.getString("addr", "北京市东城区");
        String province = prefs.getString("province", "北京市");
        String city = prefs.getString("city", "北京市");
        String district = prefs.getString("district", "东城区");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("虚拟定位设置");
        title.setTextSize(22);
        layout.addView(title);

        addSpace(layout);

        etLat = addField(layout, "纬度 (latitude)", String.valueOf(lat));
        etLng = addField(layout, "经度 (longitude)", String.valueOf(lng));
        etProvince = addField(layout, "省", province);
        etCity = addField(layout, "市", city);
        etDistrict = addField(layout, "区", district);
        etAddr = addField(layout, "详细地址", addr);

        addSpace(layout);

        Button btnSave = new Button(this);
        btnSave.setText("保存");
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    float newLat = Float.parseFloat(etLat.getText().toString().trim());
                    float newLng = Float.parseFloat(etLng.getText().toString().trim());

                    prefs.edit()
                        .putFloat("lat", newLat)
                        .putFloat("lng", newLng)
                        .putString("province", etProvince.getText().toString().trim())
                        .putString("city", etCity.getText().toString().trim())
                        .putString("district", etDistrict.getText().toString().trim())
                        .putString("addr", etAddr.getText().toString().trim())
                        .commit();

                    // 设置文件为全局可读（XSharedPreferences 需要）
                    java.io.File prefsFile = new java.io.File(
                        getApplicationInfo().dataDir + "/shared_prefs/location.xml");
                    prefsFile.setReadable(true, false);

                    Toast.makeText(SettingsActivity.this,
                        "已保存，重启钉钉生效", Toast.LENGTH_SHORT).show();
                } catch (NumberFormatException e) {
                    Toast.makeText(SettingsActivity.this,
                        "经纬度格式错误", Toast.LENGTH_SHORT).show();
                }
            }
        });
        layout.addView(btnSave);

        setContentView(layout);
    }

    private void addSpace(LinearLayout layout) {
        View space = new View(this);
        space.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 16));
        layout.addView(space);
    }

    private EditText addField(LinearLayout layout, String label, String value) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(14);
        layout.addView(tv);

        EditText et = new EditText(this);
        et.setText(value);
        et.setTextSize(16);
        layout.addView(et);
        return et;
    }
}
