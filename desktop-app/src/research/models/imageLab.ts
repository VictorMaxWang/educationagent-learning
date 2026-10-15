/**
 * P020 的 GUI 分模块与并排对照用于教学组织；这里只实现基础滤波，不复现医学分割或 Retinex。
 * 图像为本地合成 RGBA 像素，值域0…255。均值/中值采用含中心的奇数方窗，越界复制最近边缘。
 * 椒盐噪声使用固定 LCG seed；每像素固定取两次随机数，调噪声比例时保持同一随机底图。
 */
export interface PixelImage { width: number; height: number; pixels: Uint8ClampedArray }
export const DEFAULT_IMAGE_SEED = 20261016;

function checked(image: PixelImage): void {
  if (!Number.isInteger(image.width) || !Number.isInteger(image.height) || image.width < 1 || image.height < 1
      || image.width > 256 || image.height > 256 || image.pixels.length !== image.width * image.height * 4) {
    throw new RangeError('教学图像尺寸或像素长度无效');
  }
}
const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, value));

export function makeCheckerScene(width = 96, height = 96, noiseFraction = 0.15, tint = 0.22, seed = DEFAULT_IMAGE_SEED): {
  clean: PixelImage; observed: PixelImage;
} {
  const clean = { width, height, pixels: new Uint8ClampedArray(width * height * 4) };
  checked(clean);
  if (!Number.isFinite(noiseFraction) || noiseFraction < 0 || noiseFraction > 1
      || !Number.isFinite(tint) || Math.abs(tint) > 0.5 || !Number.isInteger(seed)) throw new RangeError('噪声参数无效');
  const observed = { width, height, pixels: new Uint8ClampedArray(clean.pixels.length) };
  let state = seed >>> 0;
  const random = () => { state = (Math.imul(1664525, state) + 1013904223) >>> 0; return state / 0x1_0000_0000; };
  const cell = Math.max(1, Math.floor(Math.min(width, height) / 8));
  for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
    const offset = (y * width + x) * 4;
    const level = (Math.floor(x / cell) + Math.floor(y / cell)) % 2 ? 185 : 65;
    const isNoise = random() < noiseFraction, salt = random() < 0.5;
    for (let channel = 0; channel < 3; channel++) {
      clean.pixels[offset + channel] = level;
      const gain = channel === 0 ? 1 + tint : channel === 2 ? 1 - tint : 1;
      observed.pixels[offset + channel] = isNoise ? salt ? 255 : 0 : Math.round(level * gain);
    }
    clean.pixels[offset + 3] = observed.pixels[offset + 3] = 255;
  }
  return { clean, observed };
}

export function filterImage(image: PixelImage, kind: 'mean' | 'median', windowSize = 3): PixelImage {
  checked(image);
  if (!['mean', 'median'].includes(kind) || !Number.isInteger(windowSize) || windowSize < 1 || windowSize > 7 || windowSize % 2 === 0) {
    throw new RangeError('滤波方窗必须为1、3、5或7');
  }
  const output = { width: image.width, height: image.height, pixels: new Uint8ClampedArray(image.pixels) };
  const radius = (windowSize - 1) / 2;
  for (let y = 0; y < image.height; y++) for (let x = 0; x < image.width; x++) {
    for (let channel = 0; channel < 3; channel++) {
      const values: number[] = [];
      let sum = 0;
      for (let dy = -radius; dy <= radius; dy++) for (let dx = -radius; dx <= radius; dx++) {
        const ix = clamp(x + dx, 0, image.width - 1), iy = clamp(y + dy, 0, image.height - 1);
        const value = image.pixels[(iy * image.width + ix) * 4 + channel];
        sum += value;
        if (kind === 'median') values.push(value);
      }
      output.pixels[(y * image.width + x) * 4 + channel] = kind === 'mean'
        ? Math.round(sum / (windowSize * windowSize))
        : values.sort((a, b) => a - b)[(values.length - 1) / 2];
    }
  }
  return output;
}

/** 灰度世界教学补充：把三个通道均值向共同均值拉齐；不是 P020 的 Retinex 算法。 */
export function grayWorldBalance(image: PixelImage, strength = 1): PixelImage {
  checked(image);
  if (!Number.isFinite(strength) || strength < 0 || strength > 1) throw new RangeError('颜色平衡强度须为0至1');
  const means = [0, 0, 0];
  for (let offset = 0; offset < image.pixels.length; offset += 4) {
    for (let channel = 0; channel < 3; channel++) means[channel] += image.pixels[offset + channel];
  }
  const count = image.width * image.height;
  means.forEach((sum, channel) => { means[channel] = sum / count; });
  const target = means.reduce((sum, value) => sum + value, 0) / 3;
  // 全黑或缺失通道不除以0；算法不能凭空恢复一个全零通道的真实颜色。
  const gains = means.map(mean => mean > 0 ? 1 + strength * (target / mean - 1) : 1);
  const output = { width: image.width, height: image.height, pixels: new Uint8ClampedArray(image.pixels) };
  for (let offset = 0; offset < output.pixels.length; offset += 4) {
    for (let channel = 0; channel < 3; channel++) output.pixels[offset + channel] = Math.round(image.pixels[offset + channel] * gains[channel]);
  }
  return output;
}

/** 仅因合成示例有已知干净原图，才可以计算 RGB 平均绝对误差；单位是0…255灰度级。 */
export function meanAbsolutePixelError(image: PixelImage, reference: PixelImage): number {
  checked(image); checked(reference);
  if (image.width !== reference.width || image.height !== reference.height) throw new RangeError('对照图像尺寸不同');
  let error = 0;
  for (let offset = 0; offset < image.pixels.length; offset += 4) {
    for (let channel = 0; channel < 3; channel++) error += Math.abs(image.pixels[offset + channel] - reference.pixels[offset + channel]);
  }
  return error / (image.width * image.height * 3);
}
