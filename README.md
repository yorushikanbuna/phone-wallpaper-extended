# phone-wallpaper-extended

> [English](#english) | [中文](#中文)

Extend image height with a seamless transparency-gradient transition — adapt wallpapers to taller phone screens **without cropping**. Works with any image resolution and any phone aspect ratio.

通过透明度渐变无缝过渡来扩展图片高度——**不裁切**原图，将壁纸适配到更长的手机屏幕。适用于任意图片分辨率和任意手机比例。

---

## English

### How it works

```
┌──────────────────────────┐
│   solid fill background  │  ← pure colour, sampled from image top
│   (extension + original) │
├──────────────────────────┤
│                          │
│   original image with    │  ← exponential-S-curve alpha:
│   transparency gradient  │    α = e^(-k*(1-t)), slow start, fast finish
│   (top ~10% of height)   │
│                          │
│   fully opaque original  │  ← untouched below gradient zone
└──────────────────────────┘
```

### Install

```bash
git clone https://github.com/yorushikanbuna/phone-wallpaper-extended.git
cd phone-wallpaper-extended
npm install
```

Requires **Node.js ≥ 18**.

### Usage

```bash
node extend-wallpaper.js <input> [output] --target <WxH>
```

| Option | Default | Description |
|--------|---------|-------------|
| `--target WxH` | *(required)* | Phone resolution, e.g. `1216x2640` |
| `--ratio N` | — | Alt: aspect ratio, e.g. `0.4606` |
| `--modify-zone N` | `height * 0.1` | Pixels of transparency gradient |
| `--fill-blur N` | `80` | Blur sigma for fill colour |
| `--exp-k N` | `3` | S-curve steepness (higher = flatter start, sharper finish) |

### API

```js
const { extendImage } = require('./extend-wallpaper.js');

await extendImage('in.png', 'out.png', { target: '1216x2640' });

await extendImage('in.png', 'out.png', {
  target:     '1216x2640',
  modifyZone: 300,
  expK:       5,
});
```

### Tuning

| Symptom | Fix |
|---------|-----|
| Gradient starts too fast | Increase `--exp-k` |
| Gradient finishes too abruptly | Decrease `--exp-k` |
| Gradient zone too short | Increase `--modify-zone` |
| Fill colour doesn't match | Increase `--fill-blur` |

---

## 中文

### 原理

```
┌──────────────────────────┐
│   纯色填充背景             │  ← 取原图顶部模糊后颜色
│   （延展区 + 原图区）      │
├──────────────────────────┤
│                          │
│   带透明度渐变的原图       │  ← S 曲线 alpha:
│   （顶部约 10% 高度）      │    α = e^(-k*(1-t))，慢启动、快收尾
│                          │
│   完全不透明的原图         │  ← 渐变区以下未触碰
└──────────────────────────┘
```

### 安装

```bash
git clone https://github.com/yorushikanbuna/phone-wallpaper-extended.git
cd phone-wallpaper-extended
npm install
```

需要 **Node.js ≥ 18**。

### 使用

```bash
node extend-wallpaper.js <输入> [输出] --target <宽x高>
```

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `--target WxH` | *(必填)* | 手机分辨率，如 `1216x2640` |
| `--ratio N` | — | 或指定宽高比，如 `0.4606` |
| `--modify-zone N` | `高度 × 0.1` | 透明度渐变像素数 |
| `--fill-blur N` | `80` | 填充色模糊 sigma |
| `--exp-k N` | `3` | S 曲线陡峭度（越大越极端） |

### API

```js
const { extendImage } = require('./extend-wallpaper.js');

await extendImage('in.png', 'out.png', { target: '1216x2640' });
```

### 调参

| 现象 | 解决 |
|------|------|
| 渐变启动太快 | 增大 `--exp-k` |
| 渐变收尾太陡 | 减小 `--exp-k` |
| 渐变区域太短 | 增大 `--modify-zone` |
| 填充色不对 | 增大 `--fill-blur` |

---

## Android App

The `android-app/` directory contains a native Android version (Kotlin + Canvas).

[![Build APK](https://github.com/yorushikanbuna/phone-wallpaper-extended/actions/workflows/build-apk.yml/badge.svg)](https://github.com/yorushikanbuna/phone-wallpaper-extended/actions/workflows/build-apk.yml)

Open `android-app/` in **Android Studio**, or download the latest APK from [Releases](https://github.com/yorushikanbuna/phone-wallpaper-extended/releases).

---

## License

MIT
