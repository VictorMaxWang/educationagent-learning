import { describe, expect, it } from 'vitest';
import { amplitudeDb, arrayFactor, gratingLobeAngles, sampleArrayPattern, type ArraySetup } from '../src/research/models/arrayLab';
import { filterImage, grayWorldBalance, makeCheckerScene, meanAbsolutePixelError, type PixelImage } from '../src/research/models/imageLab';
import { evaluateResources, type LearningResource, type ResourceEvaluationInput } from '../src/research/models/resourceLab';

const array = (overrides: Partial<ArraySetup> = {}): ArraySetup => ({ count: 8, spacingRatio: 0.5, steerDeg: 0, enabled: Array(8).fill(true), ...overrides });

describe('理想线阵的已知极值、角度单位与失效比较', () => {
  it('等相位完整阵列在目标方向得到 N 倍幅度与0 dB', () => {
    const field = arrayFactor(0, array());
    expect(field.real).toBeCloseTo(8, 12);
    expect(field.imaginary).toBeCloseTo(0, 12);
    expect(amplitudeDb(field.magnitude, 8)).toBeCloseTo(0, 12);
  });

  it('四阵元半波长间距在30度形成解析零点，角度输入确实使用度', () => {
    // sin30°=1/2，四个相位依次0、π/2、π、3π/2，向量和为0。
    const setup = array({ count: 4, enabled: [true, true, true, true] });
    expect(arrayFactor(30, setup).magnitude).toBeLessThan(1e-12);
  });

  it('扫描到35度后主峰跟随目标，而不是停在正前方', () => {
    const pattern = sampleArrayPattern(array({ steerDeg: 35 }));
    const peak = pattern.reduce((best, point) => point.amplitude > best.amplitude ? point : best);
    expect(peak.angleDeg).toBe(35);
    expect(peak.amplitude).toBeCloseTo(8, 12);
  });

  it('关闭一半阵元后使用同一完整阵列参考，目标幅度损失为6.0206 dB', () => {
    const setup = array({ enabled: [true, true, true, true, false, false, false, false] });
    const field = arrayFactor(0, setup);
    expect(field.magnitude).toBeCloseTo(4, 12);
    expect(amplitudeDb(field.magnitude, 8)).toBeCloseTo(-6.0205999133, 8);
  });

  it('只开一个理想阵元时幅度不依赖观察方向；全关不会自动补开', () => {
    const single = array({ count: 4, enabled: [false, false, true, false], steerDeg: 20 });
    for (const angle of [-90, -30, 0, 55, 90]) expect(arrayFactor(angle, single).magnitude).toBeCloseTo(1, 12);
    const off = sampleArrayPattern(array({ enabled: Array(8).fill(false) }));
    expect(off.every(point => point.amplitude === 0 && point.db === -60)).toBe(true);
  });

  it('区分大间距扫描时的可见重复峰与正常宽侧向情况', () => {
    expect(gratingLobeAngles(array())).toEqual([]);
    expect(gratingLobeAngles(array({ spacingRatio: 0.75 }))).toEqual([]);
    // sinθ₀=2/3、d/λ=3/4 时，k=−1 的重复峰在sinθ=−2/3。
    const repeats = gratingLobeAngles(array({ spacingRatio: 0.75, steerDeg: 41.8103148958 }));
    expect(repeats).toHaveLength(1);
    expect(repeats[0]).toBeCloseTo(-41.8103148958, 8);
  });

  it('拒绝无效参考幅度、阵元个数和非有限角度', () => {
    expect(() => amplitudeDb(1, 0)).toThrow(RangeError);
    expect(() => arrayFactor(Number.NaN, array())).toThrow(RangeError);
    expect(() => sampleArrayPattern(array({ count: 3, enabled: [true, true, true] }))).toThrow(RangeError);
  });
});

function grayImage(width: number, height: number, values: number[]): PixelImage {
  const pixels = new Uint8ClampedArray(width * height * 4);
  values.forEach((value, index) => { pixels.set([value, value, value, 255], index * 4); });
  return { width, height, pixels };
}

describe('合成图像、滤波边界与灰度世界教学补充', () => {
  it('相同seed重建相同噪声，去掉噪声和偏色后输入等于干净参照', () => {
    expect(makeCheckerScene(16, 16, 0.2, 0.1, 42).observed.pixels)
      .toEqual(makeCheckerScene(16, 16, 0.2, 0.1, 42).observed.pixels);
    const perfect = makeCheckerScene(16, 16, 0, 0, 99);
    expect(perfect.observed.pixels).toEqual(perfect.clean.pixels);
  });

  it('100%椒盐噪声的RGB像素只会是同通道黑或白，透明度保持255', () => {
    const image = makeCheckerScene(16, 16, 1, 0.3, 42).observed;
    for (let at = 0; at < image.pixels.length; at += 4) {
      expect([0, 255]).toContain(image.pixels[at]);
      expect(image.pixels[at + 1]).toBe(image.pixels[at]);
      expect(image.pixels[at + 2]).toBe(image.pixels[at]);
      expect(image.pixels[at + 3]).toBe(255);
    }
  });

  it('复制边界的均值不会把图边缘当作黑色填充', () => {
    const source = grayImage(2, 1, [0, 90]);
    const result = filterImage(source, 'mean', 3);
    // 左端邻域水平值0、0、90，右端0、90、90；竖向复制三遍，不改变均值。
    expect(result.pixels[0]).toBe(30);
    expect(result.pixels[4]).toBe(60);
    expect(source.pixels[0]).toBe(0);
    expect(source.pixels[4]).toBe(90);
  });

  it('3×3含中心中值可移除孤立椒盐点，而均值会混入它', () => {
    const source = grayImage(3, 3, [10, 10, 10, 10, 255, 10, 10, 10, 10]);
    expect(filterImage(source, 'median', 3).pixels[4 * 4]).toBe(10);
    expect(filterImage(source, 'mean', 3).pixels[4 * 4]).toBe(37);
  });

  it('常量图边缘不改变，1×1方窗是恒等处理', () => {
    const constant = grayImage(2, 2, [70, 70, 70, 70]);
    expect(filterImage(constant, 'mean', 7).pixels).toEqual(constant.pixels);
    expect(filterImage(constant, 'median', 7).pixels).toEqual(constant.pixels);
    const mixed = grayImage(2, 2, [0, 50, 200, 255]);
    expect(filterImage(mixed, 'median', 1).pixels).toEqual(mixed.pixels);
    expect(() => filterImage(mixed, 'median', 4)).toThrow(RangeError);
  });

  it('颜色平衡使用三个通道的共同均值，强度0保持输入', () => {
    const source = { width: 1, height: 1, pixels: new Uint8ClampedArray([200, 100, 50, 255]) };
    expect(grayWorldBalance(source, 1).pixels).toEqual(new Uint8ClampedArray([117, 117, 117, 255]));
    expect(grayWorldBalance(source, 0).pixels).toEqual(source.pixels);
    expect(source.pixels).toEqual(new Uint8ClampedArray([200, 100, 50, 255]));
  });

  it('全黑与零均值通道不除以0、不凭空恢复颜色', () => {
    const black = grayImage(1, 1, [0]);
    expect(grayWorldBalance(black).pixels).toEqual(black.pixels);
    const absent = { width: 1, height: 1, pixels: new Uint8ClampedArray([0, 100, 100, 255]) };
    expect(grayWorldBalance(absent).pixels).toEqual(new Uint8ClampedArray([0, 67, 67, 255]));
  });

  it('误差单位是灰度级，不把0至255范围偷换成百分比', () => {
    expect(meanAbsolutePixelError(grayImage(2, 1, [0, 90]), grayImage(2, 1, [0, 0]))).toBe(45);
    expect(() => meanAbsolutePixelError(grayImage(1, 1, [0]), grayImage(2, 1, [0, 0]))).toThrow(RangeError);
  });
});

const resource = (id: string, knowledge: boolean[], difficulty = 3, minutes = 10): LearningResource => ({ id, title: id, knowledge, difficulty, minutes });
const evaluationInput = (resources: LearningResource[], overrides: Partial<ResourceEvaluationInput> = {}): ResourceEvaluationInput => ({
  resources, selected: resources.map(() => true), goals: resources[0].knowledge.map(() => true),
  ability: 3, timeBudgetMinutes: 20, weights: [1, 1, 1, 1, 1], ...overrides,
});

describe('P017原式五项评价，保持分母、范围和单位', () => {
  it('完整覆盖、难度相合且未超时的单资源得到零代价', () => {
    const result = evaluateResources(evaluationInput([resource('all', [true, true, true])]));
    expect(result.feasible).toBe(true);
    expect(result.values).toEqual([0, 0, 0, 0, 0]);
    expect(result.total).toBe(0);
  });

  it('空选择没有可行评价，不偷偷补选或返回0分', () => {
    const result = evaluateResources(evaluationInput([resource('a', [true, true])], { selected: [false] }));
    expect(result.feasible).toBe(false);
    expect(result.selectedCount).toBe(0);
    expect(result.total).toBeNull();
    expect(result.values).toBeNull();
  });

  it('所选资源没有任何覆盖时f5分母为0，保持不可评价', () => {
    const result = evaluateResources(evaluationInput([resource('empty', [false, false])]));
    expect(result.feasible).toBe(false);
    expect(result.coveredCount).toBe(0);
    expect(result.total).toBeNull();
    if (!result.feasible) expect(result.reason).toContain('分母为0');
  });

  it('f4每缺一项计100；f5包括缺失点，不能替换为普通方差', () => {
    const result = evaluateResources(evaluationInput([resource('one', [true, false, false])]));
    expect(result.counts).toEqual([1, 0, 0]);
    expect(result.values).toEqual([2, 0, 0, 200, 2]);
    // 原式中心为1/1=1，因此两个零覆盖点各贡献(0−1)²=1。
    expect(result.total).toBe(204);
  });

  it('目标g只进入f1，不悄悄把f4/f5改成目标子集公式', () => {
    const result = evaluateResources(evaluationInput([resource('one', [true, false, false])], { goals: [true, false, false] }));
    expect(result.values).toEqual([0, 0, 0, 200, 2]);
  });

  it('手算的双资源例给出真实五项与未归一化权重加总', () => {
    const result = evaluateResources(evaluationInput([
      resource('a', [true, true, false], 2, 10), resource('b', [false, true, true], 4, 15),
    ], { weights: [2, 3, 0.5, 0.01, 1.5] }));
    expect(result.feasible).toBe(true);
    if (!result.feasible) throw new Error('固定算例应可评价');
    expect(result.values.slice(0, 4)).toEqual([1, 1, 5, 0]);
    expect(result.values[4]).toBeCloseTo(2 / 3, 12);
    expect(result.total).toBeCloseTo(8.5, 12); // 2+3+2.5+0+1，未除以权重和。
  });

  it('超时使用分钟差，且拒绝负时间/非有限权重', () => {
    const input = evaluationInput([resource('a', [true], 3, 2.5)], { timeBudgetMinutes: 1 });
    const result = evaluateResources(input);
    expect(result.values?.[2]).toBe(1.5);
    expect(() => evaluateResources({ ...input, timeBudgetMinutes: -1 })).toThrow(RangeError);
    expect(() => evaluateResources({ ...input, weights: [1, 1, Number.NaN, 1, 1] })).toThrow(RangeError);
  });
});
