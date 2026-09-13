# 壁纸延展（Android）

通过透明度渐变把原图延展到更高的手机屏幕比例，不裁切原图。Android 应用支持上方、居中、下方延展，以及人物保护：识别版先按普通流程生成背景，再用软蒙版覆盖原图人物，让发丝保持清晰，背景与关闭识别时保持一致。

## 使用

1. 从 GitHub Releases 下载 APK 并安装。
2. 选择图片，填写目标手机分辨率。
3. 人物识别版可在“人物保护”中手动选择“真人”“二次元”或“关闭”；轻量版不包含此选项。
4. 选择识别模式后，等待首次模型下载和识别完成，再生成壁纸。

模型只在首次使用时下载，下载后保存在应用私有目录，图片处理在本机完成，不会上传图片。下载支持进度、取消和重试，并会校验 SHA-256。

## 构建

用 Android Studio 打开 `android-app/`，或在 GitHub Actions 中运行 **Build & Release APK** 工作流。工作流会使用 JDK 17 和 Gradle 8.10.2 构建 release APK，并上传 APK artifact；推送到 `main` 时同时创建 GitHub Release。

工作流最终只发布两个 `arm64-v8a` APK：人物识别版和轻量版。人物识别依赖只加入前者，轻量版不包含 MediaPipe、OpenCV 和 ONNX Runtime。

```text
android-app/
└── app/src/main/java/com/example/extendwallpaper/
    ├── MainActivity.kt       # 页面、下载状态和导出
    ├── PersonMask.kt         # 公共蒙版数据结构
    └── WallpaperExtender.kt  # 预览与导出共用的像素合成

识别版的模型推理代码和模型下载器位于 `app/src/person/`；轻量版使用 `app/src/lite/` 中的空实现。
```

## 模型

- 真人：Google MediaPipe Selfie Multiclass Segmentation（Apache 2.0），约 16 MB。
- 二次元：SkyTNT Anime Segmentation `isnetis.onnx`（Apache 2.0），约 176 MB。

模型来源、固定下载地址和校验值记录在 `ModelRepository.kt`。人物保护只保留原图中已有的人物，不会补画被边缘裁断的部分。

## 许可证

应用代码使用 MIT License。第三方模型按各自许可证发布。
