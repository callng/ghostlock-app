# GhostLock-App

> English: [README.md](README.md)

## 支持的设备

| Device                       | SoC    | Kernel                                                 |
|------------------------------|--------|--------------------------------------------------------|
| OPPO Find N5 (PKH110)        | SM8750 | `6.6.118-android15-8-g2e6b9c3812c5-ab15114928-4k`      |
| OPPO Find X8 (PKB110)        | MT6991 | `6.6.118-android15-8-gebdfad32d749-ab15099304-4k`      |
| Xiaomi 17 Pro Max (popsicle) | SM8850 | `6.12.23-android16-5-g75e9b1c7ae7c-abogki463945075-4k` |
| Xiaomi 15 Pro (haotian)      | SM8750 | `6.6.77-android15-8-gca30f3b4bef6-abogki440974771-4k`  |
| Redmi K90 Pro Max (myron)    | SM8850 | `6.12.23-android16-5-g16e473de48a3-abogki462654244-4k` |
| OnePlus 13T (PKX110)         | SM8750 | `6.6.118-android15-8-g93e223c276e7-abogki500782043-4k` |

启动时按 `uname -r` 精确匹配 offset 表，未匹配的内核会直接拒绝运行；App 顶部会显示「内核支持 / 不支持」。

## Windows 编译

### 环境要求

| 工具          | 版本                                            | 安装方式                                                      |
|-------------|-----------------------------------------------|-----------------------------------------------------------|
| JDK         | 17+                                           | 例如 `winget install EclipseAdoptium.Temurin.17.JDK`        |
| Android SDK | platform 37 + build-tools                     | Android Studio 的 SDK Manager，或 Android command-line tools |
| Android NDK | r28+（需包含 `aarch64-linux-android35-clang.cmd`） | SDK Manager → "NDK (Side by side)"                        |
| GNU make    | 3.81+                                         | `winget install GnuWin32.Make`                            |
| adb（可选）     | 最新                                            | SDK platform-tools                                        |

注意事项：

- GnuWin32 make 会装到 `C:\Program Files (x86)\GnuWin32\bin` —— 装完后**新开一个终端**（或手动加入 `PATH`），`make` 才能被找到。
- Android SDK 路径通过 `local.properties`（`sdk.dir`）或 `ANDROID_HOME` 环境变量读取。
- Makefile 会从 `LOCALAPPDATA`/`ANDROID_HOME` 自动检测 NDK，并**优先选用支持目标 API（35）的最新版 NDK**。旧版本（如 r26 只提供到 android-34 的 clang 包装）会被自动跳过；也可以用 `NDK_ROOT` 强制指定版本。

### 编译 APK

```powershell
.\gradlew.bat :app:assembleDebug
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

Gradle 构建会先调用 `make` 编译原生二进制，再以 `libghostlock.so`（arm64-v8a）打包进 APK。

### 只编译原生二进制

```powershell
make ghostlock
```

产物：项目根目录下的 `ghostlock` —— 运行方式见下文 [命令行调试](#命令行调试)。`make clean` 可删除该二进制。

## 快速开始

打开 **GhostLock** 应用，点击 **执行** ，软件会自动完成提权流程，
需先自行安装 KernelSU（`me.weishu.kernelsu`）软件以使用 `ksud`，
缺少 `ksud` 时 W1/W2 仍可拿到 uid 0，但不会加载 KernelSU 模块。

## 命令行调试

adb/shell 环境无 seccomp 过滤，会跳过 W3 阶段，适合快速验证：

```powershell
make ghostlock
adb push ghostlock /data/local/tmp/ghostlock
adb shell chmod 755 /data/local/tmp/ghostlock
adb shell /data/local/tmp/ghostlock
```

## 偏移量提取

高通设备可用 `tools/extract_target.py` 从 `boot.img` 和 `xbl_config.img` 解析偏移量，依赖 Python 3 及 kallsyms 来源（`--kallsyms` 文件或 `--kallsyms-finder`）。
传入 `--llvm-objdump`（或确保 `llvm-objdump` 在 PATH/NDK 中）会额外反汇编内核，自动推导 `pselect_waiter_shift` 与 `off_slide_loggers_0_1`：

```powershell
python tools/extract_target.py `
  boot.img `
  --xbl-config xbl_config.img `
  --format c `
  --out offsets.h
```

### pselect 路线可行性

`core_sys_select` 只把 3 份 `FDS_BYTES(nfds)` 的用户 fd_set 拷到内核栈（nfds=320 时为 qword 0..14）。futex waiter 必须落在该可控区：waiter 起始字 + 11（lock 字段）≤ 14，即推导 shift（waiter 相对 fd_set 的 qword 偏移）≤ 3，否则 task/lock 落在内核清零区，路线不可行。脚本在推导出不可行布局时会直接报错。

同一内核版本在不同 SoC 分支的 PGO/LTO 布局可能不同：小米 15（`6.6.77`，`do_pselect` 未内联）的 waiter 位于第 12 个 qword，不可行；小米 15 Pro（同 `6.6.77`，中间层被内联）waiter 位于 word 0，可用 `pselect_waiter_shift=-2`。

## 来源与许可证

基于以下项目改写，继承 Apache License 2.0（见 [LICENSE](LICENSE)）：

- [NebuSec/CyberMeowfia](https://github.com/NebuSec/CyberMeowfia)
- [JoinChang/ghostlock-oneplus](https://github.com/JoinChang/ghostlock-oneplus)
- [x-spy/CVE-2026-43499-popsicle](https://github.com/x-spy/CVE-2026-43499-popsicle)
