# 壁纸延展（Android）

通过透明度渐变把原图延展到更高的手机屏幕比例，不裁切原图。Android 应用支持上方、居中、下方延展，以及人物保护：渐变进入人物区域时，人物保持清晰，背景继续平滑过渡到填充色。

## 使用

1. 从 GitHub Releases 下载 APK 并安装。
2. 选择图片，填写目标手机分辨率。
3. 在“人物保护”中选择“自动”“真人”“二次元”或“关闭”。
4. 等待首次模型下载和识别完成，再生成壁纸。

“自动”会依次运行真人和二次元分割模型，以尽量保住人物轮廓；选择“真人”或“二次元”可以减少处理时间。模型只在首次使用时下载，下载后保存在应用私有目录，图片处理在本机完成，不会上传图片。下载支持进度、取消和重试，并会校验 SHA-256。

## 构建

用 Android Studio 打开 `android-app/`，或在 GitHub Actions 中运行 **Build & Release APK** 工作流。工作流会使用 JDK 17 和 Gradle 8.10.2 构建 release APK，并上传 APK artifact；推送到 `main` 时同时创建 GitHub Release。

构建会按 `arm64-v8a`、`armeabi-v7a` 和 `x86_64` 输出独立 APK，不生成包含全部架构的通用 APK；手机通常选择 `arm64-v8a` 版本即可。

```text
android-app/
└── app/src/main/java/com/example/extendwallpaper/
    ├── MainActivity.kt       # 页面、下载状态和导出
    ├── PersonMasker.kt       # 真人/二次元分割与蒙版
    ├── ModelRepository.kt    # 模型下载、缓存和校验
    └── WallpaperExtender.kt  # 预览与导出共用的像素合成
```

## 模型

- 真人：Google MediaPipe Selfie Multiclass Segmentation（Apache 2.0），约 16 MB。
- 二次元：SkyTNT Anime Segmentation `isnetis.onnx`（Apache 2.0），约 176 MB。

模型来源、固定下载地址和校验值记录在 `ModelRepository.kt`。人物保护只保留原图中已有的人物，不会补画被边缘裁断的部分。

## 许可证

应用代码使用 MIT License。第三方模型按各自许可证发布。
