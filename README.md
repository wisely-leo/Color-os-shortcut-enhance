# ColorOS Blur Enhance

为 **ColorOS 16 桌面与时钟组件**提供动态模糊增强的 [LSPosed](https://github.com/LSPosed/LSPosed) 模块。

> 本模块由 **wisely-leo/Color-os-shortcut-enhance** 与桌面时钟字形模糊实验合并重构而来：以原 ShortcutBlur 为主体，并入时钟文字 / 天气图标的字形贴合动态高斯模糊能力。

---

## ✨ 功能

### 桌面模糊（原 ShortcutBlur 能力）
- **图标模糊**：长按 / 拖拽图标时，按需为图标叠加动态模糊
- **文件夹模糊**：打开文件夹时，内部图标模糊并带有渐进动画；关闭时平滑还原
- **壁纸深度模糊**：接入桌面 depth controller，随桌面状态联动，让模糊层次更自然
- **后处理采样适配**：统一后处理采样率，改善模糊边缘的马赛克 / 颗粒感

### 时钟组件字形模糊（新增）
- **字形贴合模糊**：对 ColorOS 桌面时钟组件的**时间 / 日期 / 天气文字**以及**天气图标**，生成与字形轮廓贴合的 Path，通过 setPathProvider + invalidatePath 施加动态高斯模糊
- **时钟文字**：Hook 时钟进程 RemoteViews.setTextColor，对时钟文字颜色叠加 alpha（70% 透明度）
- **天气图标**：因天气图标由 RemoteViews.setImageViewBitmap() 设置，改用 View.setAlpha() 处理
- **刷新轮询**：TextClock 不触发 TextWatcher，模块以 500ms 轮询感知文字变化并重建快照

---

## 📱 支持环境

| 项目 | 要求 |
|---|---|
| 系统 | **Android 16（API 36）** |
| 框架 | LSPosed（libxposed API 102） |
| 桌面 | **仅 ColorOS / OPPO 系统桌面** |

本模块声明 **5 个作用域包**，分三类：

- **桌面进程**：com.android.launcher、com.oplus.launcher、com.coloros.launcher（代码中以 isTargetLauncher 统一匹配）。ColorOS 桌面内部复用 AOSP launcher3 的类路径，模块对 PopupBlurView、ArrowPopup、OplusPopupContainerWithArrow 等挂载 Hook，实现图标 / 文件夹 / 壁纸深度模糊；并对 RemoteViews.apply / AppWidgetHostView.updateAppWidget 挂载 Hook，驱动时钟组件字形模糊。
- **时钟进程**：com.coloros.alarmclock。Hook 时钟文字颜色，实现字形模糊背景与文字透明。
- **后处理进程**：com.oplus.blur（独立进程，非桌面本身）。模块对类 e.a 的 c / e / d / f 四个方法挂载 Hook，将后处理模糊采样率由系统原生 0.25 提升至 0.5。

> ⚠️ 因此本模块**并非只作用于桌面**：必须同时覆盖 com.oplus.blur 与 com.coloros.alarmclock 进程，否则后处理采样适配与时钟字形模糊不会生效。

已在 OnePlus / OPPO PLC110（ColorOS 16.1，Android 16 / API 36）实机验证。

---

## 🚀 安装

1. 确保设备已安装 **LSPosed** 框架
2. 从 [Releases](../../releases) 下载并安装模块 APK
3. 在 LSPosed 管理器中启用本模块
4. **作用域**保持默认（模块已声明，全选即可）
5. 重启相应作用域（桌面进程、com.oplus.blur 与 com.coloros.alarmclock 进程）生效

---

## 📦 模块信息

| 项 | 值 |
|---|---|
| 模块 ID（applicationId） | com.wiselyleo.blurenhance |
| 模块入口 | com.shortcutblur.BlurEnhanceModule |
| Java 包名（namespace） | com.shortcutblur |
| 版本 | v38（versionCode 38） |
| 最低 / 目标 SDK | 36 / 36 |

---

## 📁 目录结构

```
Color-os-shortcut-enhance/
├── assets/icons/                       # 应用图标（各密度）
├── libs/
│   └── libxposed-api-102.jar           # 编译依赖（LSPosed API 102）
└── src/main/java/com/shortcutblur/
    ├── BlurEnhanceModule.java          # 模块主入口（桌面 + 时钟 Hook 安装）
    ├── GlyphBlurRenderer.java          # 字形贴合模糊渲染（Path 构建 / 刷新）
    ├── WidgetBlurAttacher.java         # 桌面组件（Widget）模糊挂载
    ├── ClockTextAlphaHook.java         # 时钟文字 alpha Hook
    ├── Logger.java                     # 统一日志门面（logcat + 文件）
    └── ModuleLog.java                  # 可选文件日志（默认关闭）
```

---

## 🧠 实现原理

### 桌面模糊
- Hook 桌面弹窗容器的入场 / 退场动画创建入口（onCreateOpenAnimation / onCreateCloseAnimation），把「模糊 0→1 / 1→0」的动画直接 set.play(...) 并进原生 AnimatorSet，与原生 alpha / scale 动画同步。
- 按视图所处状态分流处理：在文件夹内时走图标模糊路径，其余走壁纸深度模糊路径；判定结果在单次弹窗流程内缓存，流程结束时失效。
- 模糊由 RenderEffect.createBlurEffect(64f, ...) 实现：优先调用 com.oplus.view.OplusViewBackgroundRenderEffect.setBackgroundRenderEffect(effect, view)，失败则回退标准 View.setRenderEffect(effect)。
- 图标模糊动画在收尾与逐帧更新时校验有效性，中途状态变化时立即取消，避免闪回。

### 时钟字形模糊
- 桌面进程 Hook RemoteViews.apply / AppWidgetHostView.updateAppWidget，在组件视图更新后定位到 provider 根视图，交给 GlyphBlurRenderer。
- GlyphBlurRenderer 遍历时间 / 日期 / 天气文字与天气图标，按 getTotalPaddingTop() + getLayout().getLineBaseline(0) 计算基线，在载体 View 的**本地坐标系**内构建字形 Path，再通过 setPathProvider + invalidatePath() 施加动态模糊；矩形遮罩取模糊层 bounds（对齐 provider 根）。
- 时钟进程 Hook RemoteViews.setTextColor，对目标文字 id 叠加 alpha。
- 任何 static 全局状态均按 **per-container** 维护，避免多组件串扰。

---

## 📝 探针日志（可选）

ModuleLog 提供调试文件日志，**默认关闭**（编译期常量，零开销）。
开启方式：将 src/main/java/com/shortcutblur/ModuleLog.java 中的

```java
public static final boolean ENABLED = false;
```

改为 true 后重新构建。日志固定输出到：

```
/storage/emulated/0/Download/ColorOSBlurEnhance.log
```

运行时 logcat 统一 TAG 为 ColorOSBlurEnhance。

---

## 📄 许可证

GPL-3.0，见 [LICENSE](LICENSE)。
