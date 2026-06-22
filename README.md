# phone-wallpaper-extended

> [English](#english) | [中文](#中文)

Extend image height with a seamless transparency-gradient transition — adapt wallpapers to taller phone screens **without cropping**.

通过透明度渐变无缝过渡来扩展图片高度——**不裁切**原图，将壁纸适配到更长的手机屏幕。

---

## English

### How it works

```
┌──────────────────────────┐
│   solid fill background  │  ← pure colour from blurred image top
│   (extension + original) │
├──────────────────────────┤
│   original image with    │  ← S-curve alpha: slow start, fast finish
│   transparency gradient  │
│   (top ~10% of height)   │
├──────────────────────────┤
│   fully opaque original  │  ← untouched below gradient zone
└──────────────────────────┘
```

### Quick Start

```bash
git clone https://github.com/yorushikanbuna/phone-wallpaper-extended.git
cd phone-wallpaper-extended
npm install
```

Requires **Node.js ≥ 18**.

### CLI

```bash
node extend-wallpaper.js <input> [output] --target <WxH>
```

| Option | Default | Description |
|--------|---------|-------------|
| `--target WxH` | *(required)* | Phone resolution |
| `--ratio N` | — | Alt: aspect ratio |
| `--modify-zone N` | `height × 0.1` | Gradient zone px |
| `--fill-blur N` | `80` | Fill colour blur sigma |
| `--exp-k N` | `3` | S-curve steepness |

**Examples**

```bash
# 1440×2520 wallpaper → iPhone-style 1216×2640 display
node extend-wallpaper.js photo.png --target 1216x2640
# Output: 1440×3126 (+606px top, fill #1b1821)

# Custom output name
node extend-wallpaper.js art.png wallpaper.png --target 1080x2400

# Batch: extend all PNGs in current folder
for f in *.png; do node extend-wallpaper.js "$f" --target 1216x2640; done

# Tune for darker fill / smoother transition
node extend-wallpaper.js photo.png --target 1216x2640 --exp-k 5 --modify-zone 300
```

### API

```js
const { extendImage } = require('./extend-wallpaper.js');

// Basic — phone resolution string
await extendImage('in.png', 'out.png', { target: '1216x2640' });

// Advanced — full customisation
await extendImage('in.png', 'out.png', {
  target:     '1216x2640',
  modifyZone: 300,   // px of transparency gradient
  fillBlur:   100,   // blur sigma for fill colour
  expK:       5,     // S-curve steepness
});
```

### Tuning

| Symptom | Fix |
|---------|-----|
| Gradient starts too fast | Increase `--exp-k` |
| Gradient finishes too abruptly | Decrease `--exp-k` |
| Gradient zone too short | Increase `--modify-zone` |
| Fill colour too light | Increase `--fill-blur` |

---

## 中文

### 原理

```
┌──────────────────────────┐
│   纯色填充背景             │  ← 取原图顶部模糊后颜色
│   （延展区 + 原图区）      │
├──────────────────────────┤
│   带透明度渐变的原图       │  ← S 曲线 alpha：慢启动、快收尾
│   （顶部约 10% 高度）      │
├──────────────────────────┤
│   完全不透明的原图         │  ← 渐变区以下未触碰
└──────────────────────────┘
```

### 快速开始

```bash
git clone https://github.com/yorushikanbuna/phone-wallpaper-extended.git
cd phone-wallpaper-extended
npm install
```

需要 **Node.js ≥ 18**。

### 命令行

```bash
node extend-wallpaper.js <输入> [输出] --target <宽x高>
```

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `--target WxH` | *(必填)* | 手机分辨率 |
| `--ratio N` | — | 或指定宽高比 |
| `--modify-zone N` | `高度 × 0.1` | 渐变区像素数 |
| `--fill-blur N` | `80` | 填充色模糊强度 |
| `--exp-k N` | `3` | S 曲线陡峭度 |

**使用实例**

```bash
# 1440×2520 壁纸 → 适配 1216×2640 手机
node extend-wallpaper.js photo.png --target 1216x2640
# 输出：1440×3126（顶部扩展 606px，填充色 #1b1821）

# 指定输出文件名
node extend-wallpaper.js art.png wallpaper.png --target 1080x2400

# 批量处理当前文件夹所有 PNG
for f in *.png; do node extend-wallpaper.js "$f" --target 1216x2640; done

# 调参：更深填充色 + 更平滑过渡
node extend-wallpaper.js photo.png --target 1216x2640 --exp-k 5 --modify-zone 300
```

### API 调用

```js
const { extendImage } = require('./extend-wallpaper.js');

// 基本用法 — 指定手机分辨率
await extendImage('in.png', 'out.png', { target: '1216x2640' });

// 高级用法 — 完整参数
await extendImage('in.png', 'out.png', {
  target:     '1216x2640',
  modifyZone: 300,   // 透明度渐变像素数
  fillBlur:   100,   // 填充色模糊强度
  expK:       5,     // S 曲线陡峭度
});
```

### 调参指南

| 现象 | 解决 |
|------|------|
| 渐变启动太快 | 增大 `--exp-k` |
| 渐变收尾太陡 | 减小 `--exp-k` |
| 渐变区域太短 | 增大 `--modify-zone` |
| 填充色偏亮 | 增大 `--fill-blur` |

---

## Android App

原生 Android 版本（Kotlin + Canvas），可在手机上一键处理壁纸。

[![Build APK](https://github.com/yorushikanbuna/phone-wallpaper-extended/actions/workflows/build-apk.yml/badge.svg)](https://github.com/yorushikanbuna/phone-wallpaper-extended/actions/workflows/build-apk.yml)

**下载安装：** [Releases](https://github.com/yorushikanbuna/phone-wallpaper-extended/releases) → 下载最新 APK → 直接安装

**自己编译：** 用 Android Studio 打开 `android-app/` 目录

---

## License

MIT
