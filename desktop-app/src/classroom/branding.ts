export const PROJECT_NAME = '晓陶学伴';
export const COMPANION_NAME = '小陶';
export const PROJECT_FULL_NAME = '晓陶学伴——践行“教学做合一”的乡村学生个性化学习智能体';

/** 旧课不回写数据库，只兼容自我介绍和学伴称呼；教材中的普通“禾”字与用户原话保持原样。 */
export function companionIdentityText(text: string): string {
  return text
    .replace(/((?:我是(?:你的)?|我叫(?:做)?|我就是|这里是|你的(?:AI\s*)?学伴[：:]?|(?:AI\s*)?学伴[“”"「]?)[\s：:]*)小(?:禾(?:姐姐)?|盒)/g, '$1小陶')
    .replace(/^小(?:禾(?:姐姐)?|盒)(?=[，,：:。！!·\s])/g, '小陶')
    .replace(/小(?:禾(?:姐姐)?|盒)(?=在这里|陪你|语音|\s*[··]?\s*AI)/g, '小陶')
    .replace(/和小(?:禾(?:姐姐)?|盒)(?=一起|讨论|聊聊)/g, '和小陶');
}
