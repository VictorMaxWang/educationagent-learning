import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { writeFileSync } from 'node:fs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const desktop = resolve(root, 'desktop-app');
const require = createRequire(resolve(desktop, 'package.json'));
const vite = resolve(dirname(require.resolve('vite/package.json')), 'bin/vite.js');
// 公网模式只传入本次构建子进程；不修改本机桌面版、用户环境或任何凭据文件。
const environment = { ...process.env, VITE_PUBLIC_CLASSROOM: 'true' };
for (const args of [[resolve(root, 'scripts/build-classroom-export.mjs')], [vite, 'build']]) {
  const result = spawnSync(process.execPath, args, { cwd: desktop, env: environment, stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) process.exit(result.status ?? 1);
}
// 构建成功后才写发布证明，防止普通桌面构建误用于公网课堂。
writeFileSync(resolve(desktop, 'dist/build-profile.json'), JSON.stringify({
  profile: 'public-classroom-v1', publicClassroom: true,
}) + '\n');
