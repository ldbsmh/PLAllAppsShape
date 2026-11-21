# Pixel 抽屉图标形状 · PLAllAppsShape

LSPosed 模块：让 **Pixel 启动器的抽屉（全部应用）里的图标跟随桌面图标形状设置**。
桌面选的是方圆/水滴/花朵，抽屉里就不会再是一排圆圈。

没有设置界面，也没有启动图标——这个模块只做一件事，在 Xposed 管理器里启用即可。

---

## 反编译结论（Pixel 启动器 1.7 / launcher3）

| 环节 | 类 / 方法 | 关键点 |
| --- | --- | --- |
| 形状来源 | `ThemeManager.parseIconState(IconState)` | 按 `PREF_ICON_SHAPE`（`backedUpItem("icon_shape_model","",ENCRYPTED)`，**加密**）查 `ShapesProvider`，`iconMask` 就是该条目的 **SVG path**，再由 `ShapeDelegate.Companion.pickBestShape(path)` 构建 `iconShape`（一个 `ShapeDelegate`） |
| 形状回退 | `icons.BitmapInfo.newIcon(ctx, flags, shape)` | `shape != null ? shape : this.defaultIconShape` —— **回退值就是抽屉的来源** |
| 谁传形状 | `model.data.ItemInfoWithIcon.newIcon(ctx, flags)` | 桌面图标传 `iconShapeData.value`；**其余（抽屉/任务栏）传 `null`**，于是落到 `defaultIconShape` |
| 回退值从哪来 | `BaseIconFactory.defaultIconShapeCache` | **static** `SparseArray<WeakReference<IconShape>>`，由 `AdaptiveIconDrawable.getIconMask()` = 系统 `config_icon_mask`（圆形）构建 |
| 真正绘制 | `FastBitmapDrawableDelegate` | `iconShape.shapeRenderer.render(canvas, paint)` |

所以抽屉拿到的永远是系统 mask 的圆形。要让它跟随桌面，只需要在 `newIcon` 收到 `null` 形状时
把它替换掉 —— 桌面图标自带形状，不受影响。

## 实现

```
app/src/main/java/com/plallappsshape/launcher/
├── HookEntry.java      Xposed 入口：只注入 Pixel 启动器
├── LauncherHooks.java  hook 安装 + Platform 实现
├── ShapeSync.java      框架无关的核心（纯反射），可单元测试
└── Log.java            仅报错
```

三个 hook：

1. **`BitmapInfo.newIcon(Context, int, IconShape)`** — `shape == null`（抽屉）时替换参数。
   形状由 `ShapeDelegate$GenericPathShape(path).addToPath(0,0,size/2,p)` +
   `GraphicsUtils.generateIconShape(size, p)` 构建，按像素尺寸缓存。**绘制时求值，不碰缓存**。
2. **`Resources.getString(int)`** — 拦截 `config_icon_mask`，让静态形状缓存和图标阴影也用同一路径。
3. **`ThemeManager.parseIconState`** — 只读取结果（**绝不回调**），用来知道当前形状和变更时机。

改形状后立即生效：调用启动器自己的 `LauncherModel.rebindCallbacks("icon-shape-changed")`
（这正是 `ModelInitializer.initializeDisplayEvents` 里 `iconShapeData.forEach` 注册的那个回调），
再在主线程 post `ActivityAllAppsContainerView.rebindAdapters(true)` 重建抽屉适配器。

## 踩过的坑（都写在代码注释里了）

1. **Xposed API 不能用桩**：手写的 `findAndHookMethod` 返回类型写成 `Method`（真实是
   `XC_MethodHook$Unhook`），`invokestatic` 按完整描述符解析，一调用就 `NoSuchMethodError`。
   现在 `app/libs/api-82.jar` 是官方原版，CI 还会检查它没被打进 APK。
2. **不能 hook 里回调被 hook 的方法**：`parseIconState` 的 hook 里调 `parseIconState()` 会无限递归，
   直接把启动器搞崩。现在 hook 只记录快照。
3. **`BitmapInfo.defaultIconShape` 的类型是 `icons.IconShape`**，不是 `graphics.ShapeDelegate`，
   两者不能直接赋值。
4. **`newIcon` 第二个参数是 flags，不是尺寸**：当成尺寸传给 `generateIconShape` 会让
   `ShadowGenerator` 构造非法的 `BlurMaskFilter`。
5. **改 `BitmapInfo` 实例没用**：图标缓存复用它们，必须重启才生效；拦截 `newIcon` 参数才行。

## 安装

1. 安装 APK，在 LSPosed 里启用，作用域勾选 **Pixel 启动器**（`com.google.android.apps.nexuslauncher`）
2. 重启启动器一次（首次安装后）
3. 桌面 → 长按 → 壁纸与样式 → 图标 → **形状**：换形状后打开抽屉即生效，**不用再重启**

> ⚠️ Android 15+（SDK 35）的启动器跑在「T 进程」，Xposed 无法注入。本模块适用于 Android 16 及以下。

## 排查

默认只输出错误。要看详细日志，把 `Log.VERBOSE` 改成 `true` 重新编译，然后：

```bash
adb logcat -s PLAllAppsShape
```

正常时的完整流程（verbose 模式）：

```
desktop shape: M53.689 0.82 L...     ← 读到桌面形状
drawer shape built for 221px (1)     ← 按尺寸构建
icons rebound for the new shape      ← 触发启动器重建
drawer adapters rebound              ← 抽屉适配器重建
```

错误信息会直接说明缺什么，例如
`LauncherModel.rebindCallbacks not found: a shape change needs a restart`。

## 构建

```bash
sh bootstrap.sh assembleDebug    # 需要 Android SDK 36 + JDK 17
```

没有 SDK 也能先自查（纯 JDK）：

```bash
JAVAC=$JAVA_HOME/bin/javac ./verify_android.sh   # 用桩类对真实源码做类型检查
JAVA=... JAVAC=... ./verify_tests.sh             # 跑 ShapeSyncTest（12 个用例）
```

CI：`.github/workflows/build.yml`，push / PR / 手动触发，产物在 Actions → Artifacts。

## License

Apache-2.0
