# RimetHook（钉钉助手-复活）

钉钉（`com.alibaba.android.rimet`）的 Xposed 模块，提供三大功能：

| 功能 | 说明 |
| --- | --- |
| 📍 虚拟定位 | 地图选点 / 位置列表快速切换，支持 GCJ-02 与 WGS-84 双坐标系，热重载无需重启钉钉 |
| 💬 防撤回 | 拦截钉钉撤回 RPC，被撤回的文本消息在本地追加「[已撤回]」标记，对方撤回后内容仍可见 |
| 🛡️ 安全检测绕过 | 隐藏 root / 开发者选项 / 调试状态，绕过 SystemProperties、Settings、Debug、File.exists 等检测项 |

- 包名：`com.sky.xposed.rimet`（当前版本 8.4 / versionCode 103）
- 作者：毛利老王
- 适配钉钉 8.x 混淆版本（`v9h` / `x6h`），Xposed 最小版本 82
- 仅作用于钉钉进程，不影响其他应用

## 功能详情

### 1. 虚拟定位（`LocationHook` v6）

- **结果层拦截**：hook `AMapLocation.getLatitude/getLongitude`，直接返回值改写，任何遗漏的调用方都无法绕过
- **门面层拦截**：hook 钉钉自研 `LocationProxy.onLocationChanged()`（钉钉所有定位请求的统一入口）
- **监听器代理**：hook `AMapLocationClient.setLocationListener`，用动态代理包裹 listener 原地改写坐标
- **缓存绕过**：hook `AMapLocationClient.getLastKnownLocation` / `LocationManager.getLastKnownLocation`，强制返回 `null` 逼迫重新定位
- **兜底通道**：hook 系统 `LocationManager.requestLocationUpdates`（含 Looper 重载），代理系统 `LocationListener`（WGS-84）
- **热重载**：位置切换后通过文件 mtime 检测自动重载坐标，**无需重启钉钉**
- **逆地理编码**：拖动地图后自动获取当前地址，保存位置时可复用地址作为名称

**坐标/开关读取通道**：

模块界面通过 `su -c` root 写入公共文件 `/data/local/tmp/rimet_location.txt`（`key=value` 行格式），钉钉进程通过文件 mtime 检测实时读取，实现跨进程热重载。

**开关**：「启用虚拟定位」开关通过 `sed` 更新公共文件的 `enabled` 键，钉钉进程热重载即时生效，无需重启。

> **v6 架构说明**：原 `Settings.System`/`Settings.Global` 通道在 Android 14（HyperOS）上被权限校验拦截，`/sdcard/` 被 Scoped Storage 拒绝。v6 统一走 `/data/local/tmp/rimet_location.txt`（所有进程可读的公共目录），开关和坐标使用同一通道，代码审查中已清理所有失效通道。

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

> **v6 修复**：原 v4 版本中混淆类名缺少 `defpackage.` 前缀（`v9h` → `defpackage.v9h`），导致防撤回功能完全失效。此外 `getWritableDatabase()` 强制返回类型转换和 `callStaticMethod` 调用实例方法的问题也已修复。

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
- 安装本模块 APK（见「构建」下方产物说明）
- 框架中勾选模块并指定作用域为 **钉钉**（`com.alibaba.android.rimet`），重启设备

### 使用虚拟定位

1. 桌面打开「钉钉助手-复活」（模块自带界面 `SettingsActivity`，沉浸式状态栏 + 钉钉蓝主题）
2. 拖动地图选点，自动显示逆编码地址（高德 Web 服务 API）
3. 点「保存当前位置」→ 输入框默认填入逆编码地址 → 坐标通过 root 写入公共文件
4. 位置列表支持：**点击**切换（热重载，无需重启钉钉）、**长按**删除
5. 「启用虚拟定位」开关控制总闸，关闭后钉钉立即读取真实位置
6. 保存/切换后**无需重启钉钉**（热重载自动检测文件变化）

### 免地图的纯命令行方式

公共文件 `/data/local/tmp/rimet_location.txt`（`key=value` 行格式，需 root 写入）：

```sh
su -c "echo enabled=1 > /data/local/tmp/rimet_location.txt"
su -c "echo lat_gcj=28.6557 >> /data/local/tmp/rimet_location.txt"
su -c "echo lng_gcj=121.4200 >> /data/local/tmp/rimet_location.txt"
su -c "echo lat=28.6535 >> /data/local/tmp/rimet_location.txt"
su -c "echo lng=121.4180 >> /data/local/tmp/rimet_location.txt"
su -c "chmod 644 /data/local/tmp/rimet_location.txt"
```

enable 为 `0` 时关闭虚拟定位，`1` 时开启。

## 构建

### 工具链

- JDK 8/11 + Android SDK（`aapt2` / `d8`）
- 编译期 classpath：`lib/android.jar`（目标 API 级别）+ `lib/xposed-api-82.jar` + 高德 SDK（`lib/3dmap-7.0.0.jar`、`lib/map2d-6.0.0.jar`、`lib/search-9.7.1.jar`）

### 编译与打包

本仓库无 Gradle 工程，采用手动打包流程：

```bash
# 1. 编译资源（图标等）
aapt2 compile --legacy --dir res -o compiled.zip
aapt2 link -o res.apk -I $ANDROID_HOME/platforms/android-34/android.jar \
    --manifest AndroidManifest.xml compiled.zip --min-sdk-version 21

# 2. 编译 Java 源码
javac -encoding UTF-8 -source 1.8 -target 1.8 \
      -cp "lib/xposed-api-82.jar:lib/android.jar:lib/3dmap-7.0.0.jar:lib/map2d-6.0.0.jar:lib/search-9.7.1.jar" \
      -d build/classes src/com/sky/xposed/rimet/*.java

# 3. dex 化
d8 --min-api 21 build/classes/com/sky/xposed/rimet/*.class \
    lib/3dmap-7.0.0.jar lib/map2d-6.0.0.jar lib/search-9.7.1.jar \
    --output build/dex

# 4. 组装 APK：resources.arsc + AndroidManifest + res/ + classes.dex + assets/xposed_init + 签名
```

`assets/xposed_init` 内容固定为一行入口类：

```
com.sky.xposed.rimet.Main
```

### 仓库产物说明

| 路径 | 说明 |
| --- | --- |
| `RimetHook-v8.apk` | 原始版本（v4），作为构建基底 |
| `build/aNN.apk` | 按 API 级别（33 ~ 46）构建的完整模块 APK |
| `res/` | 模块资源（桌面图标各密度版） |
| `lib/*.jar` | 编译期依赖（android.jar / xposed-api / 高德 SDK），**不随 APK 分发** |

## 项目结构

```
├── AndroidManifest.xml        # 模块清单（Xposed 模块声明 + 权限 + Provider + Activity + 图标引用）
├── assets/xposed_init         # Xposed 入口：com.sky.xposed.rimet.Main
├── res/                       # 模块资源（桌面图标等）
│   ├── drawable/
│   ├── mipmap-mdpi/
│   ├── mipmap-hdpi/
│   ├── mipmap-xhdpi/
│   ├── mipmap-xxhdpi/
│   └── mipmap-xxxhdpi/
├── src/com/sky/xposed/rimet/
│   ├── Main.java              # IXposedHookLoadPackage 入口，仅加载钉钉时安装 hook
│   ├── LocationHook.java      # 虚拟定位（v6：公共文件 + 热重载 + 多层拦截）
│   ├── RecallHook.java        # 防撤回（8.x 混淆 v9h / x6h）
│   ├── SafetyCheckHook.java   # root / 开发者选项 / 调试检测绕过
│   ├── LocProvider.java       # ContentProvider，对外暴露定位坐标
│   └── SettingsActivity.java  # 模块设置界面（现代化 UI：卡片式 + 钉钉蓝主题）
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
- `RimetHook-Location`：定位改写记录（含公共文件加载、热重载检测、坐标修改）
- `RimetHook-Map`：模块界面日志

## 免责声明

本项目仅供学习与研究 Xposed 机制使用。绕过应用安全检测、虚拟定位、拦截消息功能可能违反钉钉（及其他企业 IM）的使用条款，在职场 / 合规场景使用前请自行评估风险，后果自负。