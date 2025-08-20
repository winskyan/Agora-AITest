### 打包压缩 so（useLegacyPackaging）说明

本文说明如何控制 APK/AAB 中 `.so` 原生库是否采用“传统压缩方式”进行打包，并给出 Kotlin DSL（kts）与 Groovy（gradle）两种写法示例。参考官方文档中的 `JniLibsPackagingOptions.useLegacyPackaging` 属性说明：[Android Developers - JniLibsPackagingOptions.useLegacyPackaging](https://developer.android.com/reference/tools/gradle-api/7.1/com/android/build/api/dsl/JniLibsPackagingOptions#useLegacyPackaging:kotlin.Boolean)。

### 背景

- 从 Android 6.0（API 23）开始，系统支持直接对 APK 中未压缩且页对齐的 `.so` 执行内存映射（mmap），减少安装时解压和磁盘占用拷贝步骤。
- Android Gradle Plugin（AGP）默认策略（当 `useLegacyPackaging` 为 `null` 且 `minSdk >= 23`）是：将 `.so` 以未压缩、页对齐的方式打包，以便运行时直接 mmap，优化启动与安装性能。
- 若强制启用传统压缩（`useLegacyPackaging = true`），则会把所有 `.so` 压缩进 APK，通常会：
  - 略微减小 APK 传输体积（下载包更小），
  - 但安装阶段需要解压到设备存储并拷贝，增加安装时间与设备占用，且无法享受 mmap 带来的冷启动收益。

### 属性说明

- `useLegacyPackaging: Boolean?`
  - `null`（推荐，遵循 AGP 默认策略）：
    - 当 `minSdk >= 23` 时：`.so` 将未压缩且页对齐。
    - 当 `minSdk < 23` 时：走传统兼容路径（通常为压缩并在安装时解压）。
  - `true`：强制使用“传统压缩”方式，将所有 `.so` 压缩进 APK。
  - `false`：强制不压缩 `.so` 并页对齐（即使 `minSdk < 23` 也会尝试按此设置，但请注意旧设备行为与兼容性）。

> 官方说明要点：当该值为 `null` 且 `minSdk >= 23` 时，`.so` 默认“不压缩 + 页对齐”。详见文档链接：[Android Developers - JniLibsPackagingOptions.useLegacyPackaging](https://developer.android.com/reference/tools/gradle-api/7.1/com/android/build/api/dsl/JniLibsPackagingOptions#useLegacyPackaging:kotlin.Boolean)。

### 何时使用

- **选择不压缩（推荐）**：`null` 或 `false` 且 `minSdk >= 23`
  - 追求更快的安装与启动（mmap 优势）。
  - 面向现代设备（API 23+）为主的应用。
- **选择传统压缩（`true`）**：
  - 需要尽量减小下载包体积（侧重传输占用）。
  - 有历史流程或三方分发渠道要求 APK 中 `.so` 必须压缩。
  - 正在排查与“未压缩 so”相关的设备或渠道兼容问题（临时策略）。

### 影响与权衡

- **不压缩 `.so`**：
  - 优点：安装更快、冷启动更快（mmap）、避免安装期的磁盘膨胀。
  - 代价：APK 文件体积会略大（传输体积上升）。
- **压缩 `.so`**：
  - 优点：下载体积更小。
  - 代价：安装时间增加，安装后设备存储会额外有一份解压后的 `.so`，且无法 mmap。

### 工作原理：压缩 vs 不压缩如何影响“已安装体积”

- **压缩方案（`useLegacyPackaging = true` / Manifest `android:extractNativeLibs="true"`）**
  - APK 中保存的是“压缩”的 `.so`，系统在安装阶段会将其解压到应用私有目录（历史路径如 `/data/app-lib/<pkg>/`，新系统为应用沙箱内的 `lib` 目录）。
  - 因此设备上会同时存在两份副本：APK 内的压缩副本 + 设备上的解压副本。再叠加文件系统块对齐开销以及 ART 生成的 `oat/vdex` 产物，最终“已安装体积”往往比不压缩更大。
  - 运行时也无法直接对 APK 内的压缩条目进行 `mmap`，冷启动相对更慢。

- **不压缩方案（`useLegacyPackaging = null/false` 且 `minSdk >= 23` / Manifest `android:extractNativeLibs="false"`）**
  - `.so` 在 APK 内保持未压缩并页对齐，系统可直接对其执行 `mmap`，无需在安装期复制/解压至独立目录。
  - 设备上仅保留 APK 内的一份 `.so`，因此安装占用更小且启动更快。

- **AGP 与 Manifest 的对应关系**
  - 当 `useLegacyPackaging` 为 `null` 且 `minSdk >= 23` 时，AGP 会使最终 Manifest 中包含 `android:extractNativeLibs="false"`（不解压策略）。
  - 当 `useLegacyPackaging = true` 时，将对应 `android:extractNativeLibs="true"`（安装期解压）。
  - 当 `useLegacyPackaging = false` 时，将强制 `android:extractNativeLibs="false"`（即使需要兼容更低 API 也会尝试不解压，注意旧设备行为与兼容性）。

### 配置示例

Kotlin DSL（`build.gradle.kts`，模块级，如 `app/build.gradle.kts`）

```kotlin
android {
    // ...
    packaging {
        jniLibs {
            // 方案一：遵循默认策略（推荐，尤其当 minSdk >= 23）
            // useLegacyPackaging = null

            // 方案二：强制传统压缩（把所有 .so 压缩进 APK）
            // useLegacyPackaging = true

            // 方案三：强制不压缩（即使在低于 23 的设备上也尝试不压缩）
            // useLegacyPackaging = false
        }
    }
}
```

Groovy（`build.gradle`，模块级，如 `app/build.gradle`）

```groovy
android {
    // ...
    packagingOptions {
        jniLibs {
            // 方案一：遵循默认策略（推荐，尤其当 minSdk >= 23）
            // useLegacyPackaging = null

            // 方案二：强制传统压缩（把所有 .so 压缩进 APK）
            // useLegacyPackaging = true

            // 方案三：强制不压缩（即使在低于 23 的设备上也尝试不压缩）
            // useLegacyPackaging = false
        }
    }
}
```

### 常见注意事项

- 使用 App Bundle（AAB）时，最终交付给设备的是拆分 APK；`useLegacyPackaging` 仍会影响生成的包含 `.so` 的 split 包处理方式。
- 与 `packagingOptions.jniLibs` 的其他属性（如 `excludes`、`pickFirsts`、`keepDebugSymbols`）互不冲突，可按需组合。
- 对低于 API 23 的设备，即使未压缩策略生效，也要留意旧系统的加载兼容性；通常建议 `minSdk >= 23` 的项目直接采用默认不压缩策略。

### 验证方式

- 构建 APK 后，用解包工具或 `aapt dump badging`/`apktool` 检查 `lib/<abi>/*.so` 是否被压缩。
- 检查 Manifest：`aapt dump xmltree <apk> AndroidManifest.xml | grep extractNativeLibs`，若为 `false` 则表示走“不解压”路径（mmap）。
- 实机安装后，观察安装耗时、应用冷启动时间与设备上 `app_lib` 解压目录占用（压缩策略会带来安装期解压与拷贝）。

### 参考

- 官方文档：`JniLibsPackagingOptions.useLegacyPackaging` — [Android Developers - JniLibsPackagingOptions.useLegacyPackaging](https://developer.android.com/reference/tools/gradle-api/7.1/com/android/build/api/dsl/JniLibsPackagingOptions#useLegacyPackaging:kotlin.Boolean)
- 项目示例位置（kts）：`app/build.gradle.kts` → `android { packaging { jniLibs { useLegacyPackaging = true } } }`
