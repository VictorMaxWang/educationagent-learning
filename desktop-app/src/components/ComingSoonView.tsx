/**
 * 占位视图（功能建设中）
 * 用于导航栏中尚未实现功能的条目（旅游 / 健康 / 萌宠等）
 */
interface ComingSoonViewProps {
  title: string;
  icon: string;
  desc?: string;
}

export default function ComingSoonView({ title, icon, desc }: ComingSoonViewProps) {
  return (
    <div className="coming-soon">
      <div className="coming-soon-icon">{icon}</div>
      <div className="coming-soon-title">{title}</div>
      <div className="coming-soon-desc">{desc || '功能建设中，敬请期待'}</div>
    </div>
  );
}
