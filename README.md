# phone-wallpaper-extended

> [English](#english) | [中文](#中文)

Extend image height with a seamless exponential-blend transition — adapt wallpapers to taller phone screens **without cropping**. Works with any image resolution and any phone aspect ratio.

通过无缝指数衰减混合过渡来扩展图片高度——**不裁切**原图，将壁纸适配到更长的手机屏幕。适用于任意图片分辨率和任意手机比例。

---

## English

### How it works

```
┌──────────────────────────┐
│   solid fill (adaptive)  │  ← pure colour from blurred image top
├──────────────────────────┤
│   solid zone (~8% of ext)│  ← overlay same colour → invisible seam
│  ──── seam invisible ────│
│   blend zone (~35% of ext│  ← e⁻⁵ˣ decay: texture releases slowly
├──────────────────────────┤
│   untouched original     │  ← full quality preserved
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
| `--solid N` | adaptive | Solid-zone px on original top |
| `--blend N` | adaptive | Blend transition px |
| `--fill-blur N` | `80` | Blur sigma for fill colour |
| `--blend-blur N` | `50` | Blur sigma for transition |
| `--exp-k N` | `5` | Exponential decay steepness |

```bash
# Basic usage
node extend-wallpaper.js photo.png --target 1216x2640

# Custom output path
node extend-wallpaper.js photo.png out.png --target 1080x2400

# Fine-tune
node extend-wallpaper.js photo.png --target 1216x2640 --solid 40 --blend 300

# Batch
for f in *.png; do node extend-wallpaper.js "$f" --target 1216x2640; done
```

### API

```js
const { extendImage } = require('./extend-wallpaper.js');

// By phone resolution
await extendImage('in.png', 'out.png', { target: '1216x2640' });

// By aspect ratio
await extendImage('in.png', 'out.png', { ratio: 1216 / 2640 });

// Custom zones
await extendImage('in.png', 'out.png', {
  target:    '1216x2640',
  solidZone: 60,
  blendZone: 250,
});
```

### Tuning

| Symptom | Fix |
|---------|-----|
| Seam visible | Increase `--solid` |
| Transition too abrupt | Increase `--blend` |
| Fill colour wrong | Increase `--fill-blur` |
| Extension too blurry | Decrease `--blend-blur` |
| Texture releases too fast | Increase `--exp-k` |

---

## 中文

### 原理

```
┌──────────────────────────┐
│   纯色延展区（自适应）     │  ← 取原图顶部重度模糊后的主色
├──────────────────────────┤
│   纯色覆盖区（约 8%）     │  ← 覆盖同色 → 接缝不可见
│  ──── 接缝不可见 ──────  │
│   指数混合区（约 35%）    │  ← e⁻⁵ˣ 衰减：纹理极慢释放
├──────────────────────────┤
│   原图未触碰部分          │  ← 画质完整保留
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
| `--ratio N` | — | 或直接指定宽高比，如 `0.4606` |
| `--solid N` | 自适应 | 原图顶部纯色覆盖像素数 |
| `--blend N` | 自适应 | 过渡混合像素数 |
| `--fill-blur N` | `80` | 填充色采样的模糊 sigma |
| `--blend-blur N` | `50` | 过渡区模糊 sigma |
| `--exp-k N` | `5` | 指数衰减陡峭度 |

```bash
# 基本用法
node extend-wallpaper.js photo.png --target 1216x2640

# 指定输出路径
node extend-wallpaper.js photo.png out.png --target 1080x2400

# 精细调参
node extend-wallpaper.js photo.png --target 1216x2640 --solid 40 --blend 300

# 批量处理
for f in *.png; do node extend-wallpaper.js "$f" --target 1216x2640; done
```

### API 调用

```js
const { extendImage } = require('./extend-wallpaper.js');

// 指定手机分辨率
await extendImage('in.png', 'out.png', { target: '1216x2640' });

// 指定宽高比
await extendImage('in.png', 'out.png', { ratio: 1216 / 2640 });

// 自定义参数
await extendImage('in.png', 'out.png', {
  target:    '1216x2640',
  solidZone: 60,     // 纯色覆盖区 px
  blendZone: 250,    // 过渡区 px
});
```

### 调参指南

| 现象 | 解决 |
|------|------|
| 接缝可见 | 增大 `--solid` |
| 过渡太突兀 | 增大 `--blend` |
| 填充色不对 | 增大 `--fill-blur` |
| 延展区太模糊 | 减小 `--blend-blur` |
| 纹理释放太快 | 增大 `--exp-k` |

---

## License

MIT
