package com.demo.edu.classroom.research;

import java.text.Normalizer;
import java.util.*;

/**
 * 在固定提示长度内选择不重复的检索证据。借鉴团队成果中的约束、覆盖和多样性设计；
 * 这里是确定性的贪心选择，不是专利中的鸽群、萤火虫或入侵杂草优化器。
 */
public final class EvidenceSelector {
    public static final int CANDIDATE_LIMIT=8;
    private EvidenceSelector() {}
    private record Candidate(Map<String,Object> source,int rank,String normalized,Set<String> grams,String file) {}

    public static List<Map<String,Object>> select(List<Map<String,Object>> candidates,int budget) {
        if(candidates==null||budget<=0) return List.of();
        List<Candidate> pool=new ArrayList<>();
        for(int i=0;i<Math.min(candidates.size(),60);i++) {
            Map<String,Object> source=candidates.get(i);
            if(source==null||!(source.get("content") instanceof String text)||text.isBlank()) continue;
            String normalized=normalize(text);
            if(normalized.isEmpty()) continue;
            Candidate item=new Candidate(Collections.unmodifiableMap(new LinkedHashMap<>(source)),i,normalized,grams(normalized),displayFile(source.get("file")));
            if(pool.stream().noneMatch(prior->duplicate(prior,item))) pool.add(item);
        }
        List<Candidate> selected=new ArrayList<>();Set<String> files=new HashSet<>();
        while(!pool.isEmpty()&&selected.size()<Math.min(budget,8)) {
            Candidate winner=null;double best=Double.NEGATIVE_INFINITY;
            for(Candidate item:pool) {
                // 原检索顺序是唯一相关性基线，不混用不同向量库的距离量纲。
                double relevance=1.0/(1+item.rank()*0.15);
                double novelty=selected.stream().mapToDouble(prior->similarity(item.grams(),prior.grams())).max().orElse(0);
                double value=relevance+(files.contains(item.file())?0:0.14)-0.20*novelty;
                if(value>best) {best=value;winner=item;}
            }
            selected.add(winner);files.add(winner.file());pool.remove(winner);
        }
        return selected.stream().map(Candidate::source).toList();
    }

    public static String displayFile(Object value) {
        String file=value instanceof String text&&!text.isBlank()?text:"专题知识库片段";
        file=file.replace('\\','/');file=file.substring(file.lastIndexOf('/')+1);
        return file.substring(0,Math.min(200,file.length()));
    }
    private static String normalize(String value) {return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+","");}
    private static Set<String> grams(String value) {
        int[] points=value.codePoints().limit(2500).toArray();Set<String> grams=new HashSet<>();
        for(int i=0;i+2<points.length;i++) grams.add(new String(points,i,3));
        return grams;
    }
    private static double similarity(Set<String> left,Set<String> right) {
        if(left.isEmpty()||right.isEmpty()) return 0;
        long overlap=left.stream().filter(right::contains).count();return overlap/(double)(left.size()+right.size()-overlap);
    }
    private static boolean duplicate(Candidate left,Candidate right) {
        return left.normalized().equals(right.normalized()) || Math.min(left.normalized().length(),right.normalized().length())>=80&&similarity(left.grams(),right.grams())>=0.90;
    }
}
