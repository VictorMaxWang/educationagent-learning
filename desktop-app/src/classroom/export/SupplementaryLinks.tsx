import type { PortableCourse } from './contract';
import { resourceDuration, supplementaryForScene } from './supplementary';

/** 本地播放器只保存链接，打开后由外部站点播放；不下载视频、不请求在线成绩接口。 */
export default function SupplementaryLinks({ course, sceneId }: { course: PortableCourse; sceneId: string }) {
  const resources = supplementaryForScene(course, sceneId);
  if (!resources.length) return null;
  return <section className="ep-supplementary" aria-label="补充视频资料">
    <h3>补充视频资料</h3><p className="ep-muted">访问 Bilibili 需联网。这里保留导出时的历史资料入口，未打包视频；当前可用情况以外部站点为准。</p>
    <ul>{resources.map(item => <li key={item.id}><a href={item.url} target="_blank" rel="noopener noreferrer">{item.title}<span aria-hidden="true"> ↗</span></a><p className="ep-muted">{item.author} · {resourceDuration(item.durationSeconds)}{item.purpose && ` · ${item.purpose}`}</p>{item.reason && <p>{item.reason}</p>}{item.goals.length > 0 && <p className="ep-muted">相关知识点：{item.goals.join('、')}</p>}</li>)}</ul>
  </section>;
}
