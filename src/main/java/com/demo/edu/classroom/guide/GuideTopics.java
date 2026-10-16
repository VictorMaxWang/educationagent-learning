package com.demo.edu.classroom.guide;

import com.demo.edu.classroom.ClassroomException;
import java.util.*;

/** 成长话题只提供提问入口；这里不存教学结论，也不推断学生的家庭或健康情况。 */
public final class GuideTopics {
    private GuideTopics() {}

    public record Persona(String name, String identity, String intro) {}
    public record Topic(String id, String label, String description, String domain, List<String> questions, String group) {}
    public record Group(String id, String label, String description, List<String> categories) {}
    public record TopicResponse(int grade, Persona persona, List<Topic> categories, List<Group> groups) {}
    private record Definition(String id, String label, String description, String domain,
                              List<String> common, List<String> primary, List<String> middle, List<String> high) {}

    private static final List<Definition> DEFINITIONS = List.of(
        new Definition("study-stress", "学习压力", "学习卡住时，先找到一小步", "psychology",
            List.of("一想到作业很多，我就不知道从哪里开始，怎么办？", "我努力了却没考好，可以怎样调整？", "我总忍不住和别人比较，怎么把注意力放回自己？"),
            List.of("有道题一直不会，我不敢问老师，可以怎么开口？", "写作业时总想玩，怎样安排学习和休息？", "考试前我很紧张，可以先做些什么？"),
            List.of("科目变多以后，我怎样安排一周的复习？", "担心考试排名影响同学看法，我可以怎么办？", "上课听懂了但做题不会，我该怎样找出卡住的地方？"),
            List.of("备考时计划总完不成，怎样定一个能做到的计划？", "一次考试失利后，怎样复盘又不一直责备自己？", "担心升学选择，我怎样和老师讨论自己的想法？")),
        new Definition("emotions", "情绪认识", "先说出感受，再一起想办法", "psychology",
            List.of("我说不清自己为什么难过，可以怎样认识这种感受？", "生气的时候，怎样表达才不伤到自己和别人？", "今天发生一件不开心的事，我想慢慢讲给你听。"),
            List.of("有时我想哭，却怕别人笑话，可以怎么办？", "我很想念重要的人时，可以怎样照顾自己？", "我开心和紧张一起出现，这正常吗？"),
            List.of("我有时情绪变化很快，可以怎样记录和理解？", "我觉得自己不够好，怎样发现自己做得不错的地方？", "同学一句话让我难受很久，我可以怎么整理想法？"),
            List.of("压力大时很难放松，我怎样判断自己需要哪些支持？", "我不想把烦恼都憋着，但又不知道怎么说。", "遇到挫折后，我怎样对自己说话才能更温和？")),
        new Definition("family", "家人沟通", "把想念、分歧和需要说清楚", "psychology",
            List.of("我和家人想法不一样，怎样把话说清楚？", "我想让家人多听我说一会儿，可以怎么开口？", "家人不在身边时，我想保持联系，可以一起想个办法吗？"),
            List.of("我想给家人打电话，又不知道聊什么。", "被误会时我很委屈，可以怎样说明发生的事？", "我想请照顾我的大人帮忙，但怕打扰他们。"),
            List.of("家人总拿我和别人比较，我可以怎样表达感受？", "关于手机时间，我怎样和照顾我的大人商量？", "我有一些自己的想法，但家人觉得我还小，怎么办？"),
            List.of("我和家人对未来的选择不同，怎样准备一次认真沟通？", "我希望有自己的空间，怎样表达边界又保持联系？", "长期不在一起生活的家人回来了，我不知道怎样相处。")),
        new Definition("friends", "朋友相处", "练习理解、边界与修复关系", "psychology",
            List.of("和朋友闹矛盾后，我可以怎样重新开口？", "朋友提出让我不舒服的要求，我可以怎样拒绝？", "我想认识新朋友，但不知道怎么开始聊天。"),
            List.of("游戏时大家不带我玩，我可以怎么说？", "朋友拿了我的东西没有归还，我该怎么办？", "我不小心说错话伤到朋友，可以怎样道歉？"),
            List.of("朋友在群里开让我难受的玩笑，我可以怎样回应？", "好朋友和别人更亲近了，我觉得失落怎么办？", "朋友让我替他保守一件让我担心的秘密，我该找谁商量？"),
            List.of("我和朋友越来越不一样，怎样保持尊重？", "同伴的评价影响了我的决定，我怎样辨别自己的想法？", "在关系中，我怎样表达愿意与不愿意？")),
        new Definition("campus-safety", "校园安全", "遇到不舒服的事，找到支持", "lifesafety",
            List.of("如果有人反复嘲笑或排挤我，我可以怎样求助？", "看到同学被欺负，我怎样在保护自己的前提下帮忙？", "我担心告诉老师后事情变糟，可以怎样寻找支持？"),
            List.of("上学放学路上觉得不安全，我应该和谁商量？", "有人抢我的东西，还让我不告诉大人，怎么办？", "在学校受了委屈，我可以怎样把事情讲清楚？"),
            List.of("同学在网上传播我的照片或谣言，我可以怎样求助？", "遇到同伴威胁，我怎样向老师说明情况并请求保护？", "学校活动中遇到危险，我可以先找哪些成年人？"),
            List.of("如果被持续骚扰，怎样记录事实并向学校寻求帮助？", "同学约我去不安全的地方，我怎样拒绝并离开？", "身边有人遇到严重困扰，我如何陪他一起找可信的大人？")),
        new Definition("life-safety", "网络与生活安全", "辨认风险，保护信息和自己", "lifesafety",
            List.of("陌生人在网上让我发照片或个人信息，我应该怎么办？", "收到让人着急转钱的信息，怎样先停下来核实？", "有人让我保守一个让我害怕的秘密，我可以找谁？"),
            List.of("陌生人说认识我的家人，要带我走，怎么办？", "同学约我到水边玩，我可以怎样做安全的选择？", "我看到网络上可怕的内容，想找人聊一聊。"),
            List.of("网友约我单独见面，我怎样辨别和避免风险？", "怎样设置账号隐私，减少泄露学校和住址的信息？", "群里出现不确定的消息，我怎样判断要不要转发？"),
            List.of("兼职信息让我先交钱或提供证件，我怎样核实？", "有人用照片或聊天记录威胁我，我怎样寻求帮助？", "外出或独处时觉得不安全，我可以怎样联系支持？")),
        new Definition("puberty", "身体与青春期", "用适龄知识认识变化和边界", "sexedu",
            List.of("身体有了我不理解的变化，可以向谁询问？", "如果别人的接触让我不舒服，我可以怎样说不？", "怎样尊重自己和别人的隐私？"),
            List.of("我想知道身体隐私和个人边界是什么意思。", "有人要求看我的隐私部位或让我看他的，我可以怎么办？", "同学拿身体变化开玩笑，我觉得不好受，可以怎么说？"),
            List.of("进入青春期后，身体和情绪的变化可以怎样理解？", "我对身体的问题感到不好意思，怎样向可信的大人求助？", "同学谈论的青春期知识让我疑惑，怎样找可靠资料？"),
            List.of("在亲密关系中，尊重、同意和边界怎样表达？", "有人施压让我做不愿意的事，我可以怎样拒绝和求助？", "面对网上矛盾的身体健康说法，我怎样辨别信息来源？")),
        new Definition("needs", "表达需要", "让值得信任的人知道你需要什么", "psychology",
            List.of("我需要帮助，却不知道怎样开口，可以和我练习吗？", "如果第一次求助没有被认真听见，我还能怎么办？", "我想找一个值得信任的大人，可以怎样判断和选择？"),
            List.of("我想告诉老师我遇到困难，可以先说哪一句？", "我不舒服或害怕时，可以怎样说得更清楚？", "我想被陪一会儿，这个需要可以怎样表达？"),
            List.of("我想让大人先听完再给建议，可以怎么说？", "我担心求助会被说矫情，可以怎样迈出第一步？", "我需要学习上的帮助，怎样向老师描述具体困难？"),
            List.of("我想寻求学校心理老师的支持，第一次可以怎么开始？", "我想表达自己的选择，又希望得到支持，可以怎样说？", "朋友需要的帮助超过我能承担的范围，我该怎么办？")),
        new Definition("english", "英语学习", "把家乡与生活说给更多人听", "school",
            List.of("怎样用英语介绍我的家乡？", "能陪我练习一段问候对话吗？", "怎样从例句中记住一个新单词？"),
            List.of("我想用三句英语介绍自己和家乡，可以一步一步教我吗？", "英语单词读不熟，我可以怎样每天练一点？", "能用菜地和小河举例，教我描述身边的事物吗？"),
            List.of("我写的家乡介绍怎样连成一段通顺的英文？", "英语句子里时态不一致，我可以怎样检查？", "能陪我练习一次向游客介绍家乡的对话吗？"),
            List.of("介绍乡村变化的英语短文怎样安排论点和例子？", "这段英文中哪些表达可以更准确自然？", "阅读长句时，怎样找主干和修饰关系？")),
        new Definition("general", "通识探索", "从身边的问题开始观察与求证", "school",
            List.of("一场雨从哪里来，最后又到哪里去？", "怎样判断网上一条知识说法是否可靠？", "观察家乡时，我可以提出哪些好问题？"),
            List.of("能用家乡的水塘解释水循环吗？", "菜地的面积和收获有什么关系？", "做观察记录时，怎样区分看见的事实和自己的猜想？"),
            List.of("调查家乡用水情况时，怎样设计问题和整理资料？", "一条新闻中的数字怎样核实来源？", "能带我比较两种解释，再想办法验证吗？"),
            List.of("怎样围绕乡村生态问题建立一个有证据的论证？", "相关关系与因果关系有什么区别？", "一个调查样本不足时，结论应该怎样表述？")),
        new Definition("practice", "实践与创造", "在操作、观察与修改中理解原理", "school",
            List.of("怎样用代码记录菜地的浇水次数？", "我想把分数通分做成一步一步的图解。", "模拟实验中改变一个参数后，我应该观察什么？"),
            List.of("我想用循环记录五天的浇水量，可以先解释思路吗？", "通分图里每一份的大小为什么要保持一样？", "长方体的长、宽、高改变时，体积怎样变化？"),
            List.of("数组排序每一步都改变了什么，怎样读变量追踪？", "我怎样设计一个只改变一个条件的仿真实验？", "怎样把分数配对游戏的规则写成程序？"),
            List.of("递归函数的调用栈怎样帮助定位错误？", "仿真的假设与实际观察不同，怎样判断模型的局限？", "怎样为一个算法选择输入、测试用例和可视化？"))
    );

    private static final List<Group> GROUPS = List.of(
        new Group("mind-communication", "心灵与沟通", "认识感受，练习沟通与表达需要", List.of("study-stress", "emotions", "family", "friends", "needs")),
        new Group("safety-health", "安全与健康", "学习保护自己，科学认识身体变化", List.of("campus-safety", "life-safety", "puberty")),
        new Group("language-general", "语言与通识", "练习语言，从生活问题走向知识探索", List.of("english", "general")),
        new Group("practice-creation", "实践与创造", "用代码、图解、实验和游戏边做边学", List.of("practice"))
    );

    public static int requireGrade(Integer grade) {
        if (grade == null || grade < 1 || grade > 12) throw new ClassroomException(400, "请选择 1 至 12 年级");
        return grade;
    }

    public static TopicResponse forGrade(Integer grade) {
        int checked = requireGrade(grade);
        List<Topic> topics = DEFINITIONS.stream().map(d -> {
            List<String> questions = new ArrayList<>(checked <= 6 ? d.primary : checked <= 9 ? d.middle : d.high);
            questions.addAll(d.common);
            return new Topic(d.id, d.label, d.description, d.domain, List.copyOf(questions), groupFor(d.id).id());
        }).toList();
        return new TopicResponse(checked, new Persona("小陶", "AI成长陪伴助手",
            "我是小陶，晓陶学伴的 AI 学习与成长助手。你可以和我整理想法、练习语言，也可以一起观察、动手和创造。现实中的困难也可以向可信的大人寻求支持。"), topics, GROUPS);
    }

    public static Topic requireTopic(String category, int grade) {
        return forGrade(grade).categories.stream().filter(t -> t.id.equals(category)).findFirst()
            .orElseThrow(() -> new ClassroomException(400, "请选择有效的成长话题"));
    }

    public static Group requireGroup(String id) {
        return GROUPS.stream().filter(group -> group.id().equals(id)).findFirst()
            .orElseThrow(() -> new ClassroomException(400, "请选择有效的成长课堂"));
    }

    public static Group groupFor(String category) {
        return GROUPS.stream().filter(group -> group.categories().contains(category)).findFirst()
            .orElseThrow(() -> new ClassroomException(400, "请选择有效的成长话题"));
    }

    /** 相关追问先于通用补位，并排除本轮及历史已问问题，避免固定候选占满推荐位。 */
    public static List<String> suggestions(Topic topic, String question, List<String> previousQuestions) {
        Set<String> asked = new HashSet<>(previousQuestions);
        asked.add(question);
        List<String> contextual = switch (topic.id) {
            case "family", "friends", "needs" -> List.of("可以陪我练习一句具体的开场白吗？", "如果对方暂时不愿意听，我可以怎样再找支持？", "我怎样把发生的事、感受和需要分别说清楚？");
            case "campus-safety", "life-safety", "puberty" -> List.of("我可以向哪一位可信的大人说明这件事？", "可以帮我整理一段求助时要说的话吗？", "如果我现在觉得不安全，怎样先联系身边的支持？");
            case "english" -> List.of("可以给我一个能模仿的短例句，再让我试着说吗？", "请指出我这句话里最值得先改的一处。", "换一个家乡生活的场景，我们再练一轮好吗？");
            case "general" -> List.of("可以用一个身边可观察的例子解释吗？", "这个解释需要哪些证据，我们怎样核实？", "我可以做什么小观察来检验自己的猜想？");
            case "practice" -> List.of("可以按当前步骤解释变量为什么变化吗？", "如果只改一个参数，结果会怎样变化？", "请给我一个提示，让我先自己试着修改。");
            default -> List.of("如果今天只做一小步，我可以从哪里开始？", "可以用一个贴近学校生活的例子说明吗？", "我怎样把这种感受告诉值得信任的人？");
        };
        LinkedHashSet<String> candidates = new LinkedHashSet<>(contextual);
        // 随对话推进轮换备选，既不伪造模型推荐，也不会始终重复最初三条。
        List<String> rotated = new ArrayList<>(topic.questions);
        Collections.rotate(rotated, -(previousQuestions.size() % rotated.size()));
        candidates.addAll(rotated);
        return candidates.stream().filter(s -> !asked.contains(s)).limit(3).toList();
    }
}
