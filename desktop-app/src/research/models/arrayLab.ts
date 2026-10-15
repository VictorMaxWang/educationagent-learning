/**
 * P013 PDF p2 式(1)的理想线阵教学实现。
 * d 是 d/λ（无量纲），θ 与扫描目标输入用度，指数相位用弧度；n=0…N−1。
 * 各阵元等幅、各向同性，不含互耦、硬件误差或文献中的凸优化/波束赋形优化器。
 */
export interface ArraySetup {
  count: number;
  spacingRatio: number;
  steerDeg: number;
  enabled: readonly boolean[];
}
export interface ArrayField { real: number; imaginary: number; magnitude: number }
export interface PatternPoint { angleDeg: number; amplitude: number; db: number }

const radians = (degrees: number) => degrees * Math.PI / 180;

function checkSetup(setup: ArraySetup): void {
  if (!Number.isInteger(setup.count) || setup.count < 4 || setup.count > 16
      || setup.enabled.length !== setup.count || setup.enabled.some(value => typeof value !== 'boolean')
      || !Number.isFinite(setup.spacingRatio) || setup.spacingRatio < 0.25 || setup.spacingRatio > 0.75
      || !Number.isFinite(setup.steerDeg) || Math.abs(setup.steerDeg) > 60) {
    throw new RangeError('阵列参数超出本实验范围');
  }
}

/** F(θ)=Σaₙexp(j[2π(d/λ)n sinθ+φₙ])，φₙ=−2π(d/λ)n sinθ₀。 */
export function arrayFactor(angleDeg: number, setup: ArraySetup): ArrayField {
  checkSetup(setup);
  if (!Number.isFinite(angleDeg) || Math.abs(angleDeg) > 90) throw new RangeError('观察方向须在 −90° 至 90°');
  const phaseStep = 2 * Math.PI * setup.spacingRatio
    * (Math.sin(radians(angleDeg)) - Math.sin(radians(setup.steerDeg)));
  let real = 0, imaginary = 0;
  for (let n = 0; n < setup.count; n++) {
    if (!setup.enabled[n]) continue;
    real += Math.cos(n * phaseStep);
    imaginary += Math.sin(n * phaseStep);
  }
  return { real, imaginary, magnitude: Math.hypot(real, imaginary) };
}

/** 幅度采用 20log10；0 幅度只在绘图中截到 floorDb，不把它解释为有辐射。 */
export function amplitudeDb(amplitude: number, reference: number, floorDb = -60): number {
  if (!Number.isFinite(amplitude) || amplitude < 0 || !Number.isFinite(reference) || reference <= 0
      || !Number.isFinite(floorDb) || floorDb >= 0) throw new RangeError('幅度归一化参数无效');
  return amplitude === 0 ? floorDb : Math.max(floorDb, 20 * Math.log10(amplitude / reference));
}

/** 两条对照曲线都用完整 N 阵元的相干幅度 N 作参考，不能各自归一化后隐藏失效损失。 */
export function sampleArrayPattern(setup: ArraySetup): PatternPoint[] {
  checkSetup(setup);
  return Array.from({ length: 361 }, (_, index) => {
    const angleDeg = -90 + index * 0.5;
    const amplitude = arrayFactor(angleDeg, setup).magnitude;
    return { angleDeg, amplitude, db: Math.min(0, amplitudeDb(amplitude, setup.count)) };
  });
}

/** 均匀线阵的同相重复方向满足 sinθ=sinθ₀+k/(d/λ)，只保留可见区内非零整数 k。 */
export function gratingLobeAngles(setup: ArraySetup): number[] {
  checkSetup(setup);
  const target = Math.sin(radians(setup.steerDeg));
  return [-2, -1, 1, 2].flatMap(order => {
    const sine = target + order / setup.spacingRatio;
    return sine >= -1 && sine <= 1 ? [Math.asin(sine) * 180 / Math.PI] : [];
  });
}
