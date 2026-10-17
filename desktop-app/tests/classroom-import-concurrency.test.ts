import { afterEach, expect, it, vi } from 'vitest';
import JSZip from 'jszip';
import { readPortableFile } from '../src/classroom/export/importCourse';

afterEach(() => vi.restoreAllMocks());

async function archive(paths: string[]) {
  const input = { format: 'educationagent.classroom', version: 1, course: {
    id: 'c', title: '重复图片的课堂', grade: 5, scenes: [{ id: 's', type: 'slide', content: {
      type: 'slide', canvas: { id: 'canvas', viewportSize: 1280, viewportRatio: .5625,
        elements: paths.map((src, index) => ({ id: `image-${index}`, type: 'image', src })) },
    } }],
  } };
  const zip = new JSZip(); zip.file('course.json', JSON.stringify(input));
  for (const path of new Set(paths)) zip.file(path, new Uint8Array(64 * 1024).fill(7));
  const bytes = await zip.generateAsync({ type: 'uint8array', compression: 'DEFLATE' });
  const parsed = await JSZip.loadAsync(bytes);
  vi.spyOn(JSZip, 'loadAsync').mockResolvedValue(parsed);
  return { file: new File([bytes], 'lesson.zip'), parsed };
}

it('同一ZIP图片被多处引用时只解压一次，并保持所有引用内容一致', async () => {
  const { file, parsed } = await archive(Array(30).fill('assets/shared.png'));
  const asset = parsed.file('assets/shared.png')! as JSZip.JSZipObject & { internalStream(type: string): unknown };
  const reads = vi.spyOn(asset, 'internalStream');
  const result = await readPortableFile(file);
  expect(reads).toHaveBeenCalledTimes(1);
  const images = result.course.scenes[0].content.canvas!.elements as unknown as { src: string }[];
  expect(new Set(images.map(image => image.src)).size).toBe(1);
  expect(images[0].src).toMatch(/^data:image\/png;base64,/);
});

it('不同ZIP图片同时解压最多两个，而不是每个引用同时占用缓冲区', async () => {
  const paths = Array.from({ length: 8 }, (_, index) => `assets/p-${index}.png`);
  const { file, parsed } = await archive(paths);
  let active = 0, peak = 0;
  for (const path of paths) {
    const entry = parsed.file(path)! as JSZip.JSZipObject & { internalStream(type: string): { on(type: string, listener: () => void): unknown } };
    const original = entry.internalStream.bind(entry);
    vi.spyOn(entry, 'internalStream').mockImplementation(type => {
      active++; peak = Math.max(peak, active); const stream = original(type);
      stream.on('end', () => { active--; }); return stream;
    });
  }
  const result = await readPortableFile(file);
  expect(result.course.scenes[0].content.canvas!.elements).toHaveLength(8);
  expect(peak).toBeLessThanOrEqual(2); expect(active).toBe(0);
});

it('损坏的资源不返回半份可导入课程', async () => {
  const { file, parsed } = await archive(['assets/broken.png', 'assets/broken.png', 'assets/valid.png']);
  parsed.remove('assets/broken.png');
  await expect(readPortableFile(file)).rejects.toThrow('缺少图片');
});
