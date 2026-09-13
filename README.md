# RimetHook（钉钉助手 - 复活版）

钉钉（`com.alibaba.android.rimet`）的 Xposed 模块，提供三大功能：

| 功能 | 说明 |
| --- | --- |
| 📍 虚拟定位 | 地图选点 / 位置列表快速切换，支持 GCJ-02 与 WGS-84 双坐标系，高德与系统双通道改写 |
| 💬 防撤回 | 拦截钉钉撤回 RPC，被撤回的文本消息在本地追加「[已撤回]」标记，对方撤回后内容仍可见 |
| 🛡️ 安全检测绕过 | 隐藏 root / 开发者选项 / 调试状态，绕过 SystemProperties、Settings、Debug、File.exists 等检测项 |

- 包名：`com.sky.xposed.rimet`（当前版本 8.4 / versionCode 103）
- 作者：毛利老王
- 适配钉钉 8.x 混淆版本（`v9h` / `x6h`），Xposed 最小版本 82
- 仅作用于钉钉进程，不影响其他应用

## 功能详情

### 1. 虚拟定位（`LocationHook`）

- **主通道**：hook 高德 `AMapLocationClient`
  - `setLocationListener` → 用动态代理包裹 listener，在 `onLocationChanged` 回调中原地改写坐标（GCJ-02）
  - `getLastKnownLocation` → 强制返回 `null`，逼迫钉钉重新发起定位
- **兜底通道**：hook 系统 `LocationManager.requestLocationUpdates`，代理系统 `LocationListener`（WGS-84）
- 优先调用 `setLatitude/setLongitude` 改写对象，失败时反射修改字段（`f/h/g/i` 等混淆字段名兜底）

**坐标读取优先级**（从高到低）：

1. `Settings.System` 的 `rimet_*` 键（任何进程免权限可读，HyperOS 下最可靠）
2. 模块自带 `ContentProvider`（`content://com.sky.xposed.rimet.loc/`，30 秒缓存）
3. `XSharedPreferences`（shared_prefs 名为 `location`）
4. 公共文件 `/sdcard/rimet_location.txt`（`key=value` 行格式）
5. 默认值：台州市政府（椒江区）`28.6557, 121.4200`

**开关**：`Settings.System` 的 `rimet_enabled` 为 `"0"` 时放行真实位置，默认开启。

### 2. 防撤回（`RecallHook`，针对钉钉 8.x 混淆）

| 混淆类 | 对应功能 |
| --- | --- |
| `v9h.K(String, long, Callback)` | 单条撤回 RPC |
| `v9h.J(String, List, int, Callback)` | 批量撤回 RPC |
| `x6h` | 消息数据源（`MessageDs`） |
| `x6h.c(String, Collection, boolean)` | 消息处理 handler |
| `x6h.T(String, String, List)` | 消息 update 写库 |

工作逻辑：

1. 拦截 `v9h` 的两个撤回 RPC，直接 `setResult(null)`，撤回请求不发出
2. hook `x6h.c` 消息处理流程，检测到撤回通知（type=126）时，找到被撤回的文本消息（type=10），把内容改为 `原文 [已撤回]` 并写回数据库

> 注意：混淆映射随钉钉版本变化，新版钉钉更新后需重新分析混淆类名。

### 3. 安全检测绕过（`SafetyCheckHook`）

- **SystemProperties**：hook `get/read/getInt/getBoolean/getLong` 全部重载，拦截
  `ro.debuggable`、`ro.adb_enabled`、`ro.secure`、`ro.allow.mock.location`、
  `init.svc.adbd`、`sys.usb.state`、`persist.sys.usb.config` 等键，返回「非 root / 非调试」值
- **Settings.Global/Secure/System**：hook `getInt/getString/getLong`，拦截
  `adb_enabled`、`development_settings_enabled`、`developer_options_enabled` 等开发者选项键，返回 0
- **Debug**：`Debug.isDebuggerConnected` 强制返回 `false`
- **Root 文件检测**：hook `File.exists`，对 `/system`、`/sbin`、`/magisk`、`/su` 路径下含 `su` 的探测返回 `false`

## 安装

### 前置要求

- 已 root 的设备，并装有 **LSPosed / EdXposed / Xposed 框架**（Xposed 版本 ≥ 82）
- 安装本模块 APK（见「构建」），或在 LSPosed 模块列表中安装仓库根目录的 `RimetHook-v8.apk`
- 框架中勾选模块并指定作用域为 **钉钉**（`com.alibaba.android.rimet`），重启设备

### 使用虚拟定位

1. 桌面打开「**虚拟定位设置**」（模块自带界面 `SettingsActivity`）
2. 拖动地图或点「定位到我的位置」选点（坐标系自动按 GCJ-02 处理）
3. 点「保存当前位置」并给位置起名 → 坐标写入模块 SharedPreferences，同时通过 root 写 `Settings.System` 的 `rimet_*` 键
4. 位置列表支持：**点击**切换（立即生效）、**长按**删除
5. 「启用虚拟定位」开关控制总闸：关闭后钉钉读取真实位置
6. 保存后**重启钉钉**生效

### 免地图的纯命令行方式

`Settings.System` 键可直接用 `settings` 命令写入（需 root）：

```sh
settings put system rimet_lat_gcj 28.6557
settings put system rimet_lng_gcj 121.4200
settings put system rimet_lat    28.6535    # WGS-84 通道
settings put system rimet_lng    121.4180
settings put system rimet_addr   "家"
settings put system rimet_enabled 1        # 0 = 关闭虚拟定位
```

或使用公共文件 `/sdcard/rimet_location.txt`（`key=value` 行格式，键名同上）作为最后回退数据源。

## 构建

### 工具链

- JDK 8/11 + Android SDK（`aapt` / `aapt2` / `d8`）
- 编译期 classpath：`lib/android.jar`（目标 API 级别）+ `lib/xposed-api-82.jar` + 高德 SDK（`lib/3dmap-7.0.0.jar`、`lib/map2d-6.0.0.jar`、`lib/search-9.7.1.jar`）

### 编译与打包

本仓库无 Gradle 工程，采用 aapt + javac + d8 手动打包流程（可参考 `build/` 下各 API 级别的产物）：

```bash
# 1. 资源打包（按目标 API 选 aapt）
aapt compile / p 输出 resources + AndroidManifest

# 2. 按目标 API 级别编译（不同 API 级别的 android.jar 产物放 build/c33 ~ c46）
javac -bootclasspath lib/android.jar \
      -cp "lib/xposed-api-82.jar:lib/3dmap-7.0.0.jar:lib/map2d-6.0.0.jar:lib/search-9.7.1.jar" \
      -d build/cXX src/com/sky/xposed/rimet/*.java

# 3. dex 化
d8 --min-api XX build/cXX/**/*.class --output build/apk

# 4. 组装 APK：resources + classes.dex + assets/xposed_init + 签名
```

`assets/xposed_init` 内容固定为一行入口类：

```
com.sky.xposed.rimet.Main
```

### 仓库产物说明

| 路径 | 说明 |
| --- | --- |
| `RimetHook-v8.apk` | 当前推荐安装版本（v8） |
| `build/aNN.apk` | 按 API 级别（33 ~ 46）构建的完整模块 APK |
| `build/aaptNN.apk` | 各 API 级别的资源包中间产物 |
| `build/cNN/` | 各 API 级别的 class 编译产物 |
| `lib/*.jar` | 编译期依赖（android.jar / xposed-api / 高德 SDK），**不随 APK 分发** |

## 项目结构

```
├── AndroidManifest.xml        # 模块清单（Xposed 模块声明 + 权限 + Provider + Activity）
├── assets/xposed_init         # Xposed 入口：com.sky.xposed.rimet.Main
├── src/com/sky/xposed/rimet/
│   ├── Main.java              # IXposedHookLoadPackage 入口，仅加载钉钉时安装 hook
│   ├── LocationHook.java      # 虚拟定位（高德 + 系统双通道）
│   ├── RecallHook.java        # 防撤回（8.x 混淆 v9h / x6h）
│   ├── SafetyCheckHook.java   # root / 开发者选项 / 调试检测绕过
│   ├── LocProvider.java       # ContentProvider，对外暴露定位坐标
│   └── SettingsActivity.java  # 模块设置界面（高德地图选点 + 位置列表）
├── lib/                       # 编译期依赖 jar（不打包进 APK）
└── build/                     # 各 API 级别构建产物
```

## 日志

logcat 过滤：

```sh
adb logcat -s RimetHook RimetHook-Safety RimetHook-Recall RimetHook-Location RimetHook-Map
```

- `RimetHook-Safety`：安全检测绕过安装日志（每个被 hook 的方法都有输出）
- `RimetHook-Recall`：撤回 RPC 拦截 / 消息处理记录
- `RimetHook-Location`：定位改写记录（含数据源命中情况）

## 免责声明

本项目仅供学习与研究 Xposed 机制使用。绕过应用安全检测、虚拟定位、拦截消息功能可能违反钉钉（及其他企业 IM）的使用条款，在职场 / 合规场景使用前请自行评估风险，后果自负。
