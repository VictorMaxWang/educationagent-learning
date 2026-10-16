import StudyReflectionSession from './StudyReflectionSession';
import { reflectionKey } from './study-reflection-model';
import './study-reflection.css';
export { reflectionKey, restoreReflection, reflectionQuestion } from './study-reflection-model';
export type { StudyReflection } from './study-reflection-model';

/** M003 §5.3 的学习过程设计借鉴：先自行重构，再核对；不把观察关联解释成学习效果保证。 */
export default function StudyReflectionCard(props: {
  courseId: string; sceneId: string; revision: number; sceneTitle: string; onAskTeacher(text: string): void;
  onStudyUpdate?(goals: string[]): void;
}) {
  return <StudyReflectionSession key={reflectionKey(props.courseId, props.sceneId, props.revision)} {...props} />;
}
