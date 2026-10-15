package com.demo.edu.classroom.research;

/** 将问题中的目标保留到回答约束；只使用显式问句，不推断学生人格、经济条件或掌握水平。 */
public final class QuestionIntentSupport {
    private QuestionIntentSupport() {}
    public static String directive(String question) {
        if(question==null) return "";
        StringBuilder result=new StringBuilder("\n回答时保留学生的问题目标和所指对象，不只输出一个答案实体。");
        if(contains(question,"为什么","原因","原理","解释")) result.append("学生在追问原因：解释有依据的一般原理或数学理由，并注明条件；个体动机或原因尚不清楚时先澄清，不作臆测。");
        if(contains(question,"怎么","怎样","步骤","如何")) result.append("学生在询问做法：给出少量可执行步骤，先提示再展开。");
        if(contains(question,"比较","区别","对比","不同")) result.append("学生在作比较：先对齐比较对象、单位和标准，再说明异同。");
        if(contains(question,"依据","来源","证据","哪一段","核对")) result.append("学生在找依据：指向本次真实提供的页面或资料片段；没有依据时直接说明，不编造引用。");
        return result.toString();
    }
    private static boolean contains(String text,String... words) {for(String word:words) if(text.contains(word)) return true;return false;}
}
