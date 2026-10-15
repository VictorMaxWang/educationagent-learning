import { useEffect, useMemo, useRef, useState } from 'react';
import { DEFAULT_IMAGE_SEED, filterImage, grayWorldBalance, makeCheckerScene, meanAbsolutePixelError, type PixelImage } from './models/imageLab';
import { LabQuestion, LabSource, RangeControl } from './LabParts';

function ImageTile({ image, title, description, error }: { image: PixelImage; title: string; description: string; error: number }) {
  const canvas = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const context = canvas.current?.getContext('2d');
    if (!context) return;
    const frame = context.createImageData(image.width, image.height);
    frame.data.set(image.pixels);
    context.putImageData(frame, 0, 0);
  }, [image]);
  return <figure className="tr-image-tile"><div className="tr-image-heading"><h3>{title}</h3><small>{description}</small></div>
    <canvas ref={canvas} width={image.width} height={image.height} role="img" aria-label={`${title}：合成棋盘图像`} />
    <figcaption><span>与干净参照的平均误差</span><strong>{error.toFixed(1)} <small>灰度级</small></strong></figcaption>
  </figure>;
}

export default function ImageFilterLab() {
  const [noise, setNoise] = useState(0.15);
  const [tint, setTint] = useState(0.22);
  const [windowSize, setWindowSize] = useState(3);
  const [strength, setStrength] = useState(1);
  const [seed, setSeed] = useState(DEFAULT_IMAGE_SEED);
  const scene = useMemo(() => makeCheckerScene(96, 96, noise, tint, seed), [noise, tint, seed]);
  const mean = useMemo(() => filterImage(scene.observed, 'mean', windowSize), [scene, windowSize]);
  const median = useMemo(() => filterImage(scene.observed, 'median', windowSize), [scene, windowSize]);
  const balanced = useMemo(() => grayWorldBalance(scene.observed, strength), [scene, strength]);
  const errors = [scene.observed, mean, median, balanced].map(image => meanAbsolutePixelError(image, scene.clean));
  const reset = () => { setNoise(0.15); setTint(0.22); setWindowSize(3); setStrength(1); setSeed(DEFAULT_IMAGE_SEED); };

  return <article className="tr-experiment">
    <LabQuestion number="02" title="让图片变平滑，会不会丢掉细节？">四张图使用同一份输入。试着增加噪声、放大滤波窗口，比较噪点、边界与颜色的变化。</LabQuestion>
    <div className="tr-controls-grid">
      <RangeControl label="椒盐噪声比例" min={0} max={0.35} step={0.01} value={noise} display={`${Math.round(noise * 100)}%`} onChange={setNoise} />
      <RangeControl label="冷暖偏色" min={-0.35} max={0.35} step={0.01} value={tint} display={tint === 0 ? '无偏色' : `${tint > 0 ? '偏暖' : '偏冷'} ${Math.round(Math.abs(tint) * 100)}%`} onChange={setTint} />
      <label className="tr-control"><span>滤波方窗<output>{windowSize} × {windowSize}</output></span><select value={windowSize} onChange={event => setWindowSize(Number(event.currentTarget.value))}><option value={3}>3 × 3：观察附近像素</option><option value={5}>5 × 5：更大的邻域</option><option value={7}>7 × 7：更强的平滑</option></select></label>
      <RangeControl label="颜色平衡强度" min={0} max={1} step={0.05} value={strength} display={`${Math.round(strength * 100)}%`} onChange={setStrength} />
    </div>
    <div className="tr-actions tr-image-actions"><span>仅使用本地合成棋盘，无需上传照片</span><button onClick={() => setSeed(value => (value + 1) >>> 0)}>换一组噪声</button><button onClick={reset}>恢复示例</button></div>
    <div className="tr-image-grid">
      <ImageTile image={scene.observed} title="原图" description="加入噪声和偏色后的输入" error={errors[0]} />
      <ImageTile image={mean} title="均值滤波" description="取邻域像素的平均值" error={errors[1]} />
      <ImageTile image={median} title="中值滤波" description="取邻域像素的中间值" error={errors[2]} />
      <ImageTile image={balanced} title="简易颜色平衡" description="调整三个颜色通道的均值" error={errors[3]} />
    </div>
    <div className="tr-observation" aria-live="polite"><strong>观察一下</strong><p>{noise === 0 && tint === 0
      ? '输入已经没有噪声和偏色。比较均值结果与原图：额外平滑也可能让棋盘边界变模糊。'
      : errors[2] < errors[1] ? '当前合成示例里，中值滤波的误差更小。把窗口调大后，再检查棋盘边界是否也被改变。'
        : '当前设置下，均值滤波的误差不高于中值。继续改变噪声和窗口，观察两者各保留了什么。'}</p>
      <p>颜色平衡直接处理原图，噪点仍可能存在。它假设整体颜色应接近中性，真实场景不一定满足这个假设。</p></div>
    <LabSource><p><strong>P020</strong>《基于 MATLAB GUI 的图像处理和机器视觉辅助教学平台设计与实现》：PDF 第 2 页的独立子函数与控件组织，第 8 页的均值、中值滤波和同图对照。</p>
      <p>本页采用包含中心像素的奇数方窗；边界复制最近像素。颜色平衡是灰度世界教学补充：将 R、G、B 的平均值向共同均值靠拢，零均值通道保持不变。医学分割及论文中的 Retinex 不在本实验范围。</p>
      <p>棋盘为 96 × 96 像素，噪声种子为 {seed}。误差是相对已知、无偏色且无噪声棋盘的 RGB 平均绝对差，单位为 0–255 灰度级，仅用于这个合成例的比较。</p>
    </LabSource>
  </article>;
}
