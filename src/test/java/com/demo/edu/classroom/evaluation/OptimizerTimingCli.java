package com.demo.edu.classroom.evaluation;

import com.demo.edu.classroom.recommendation.RecommendationOptimizer;
import com.demo.edu.classroom.recommendation.RecommendationOptimizer.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 仅依赖JDK和当前生产Optimizer。服务器实测与Windows结果分别保留，容量夹具不冒充资源样本。 */
public final class OptimizerTimingCli {
    private OptimizerTimingCli() {}
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("usage: OptimizerTimingCli input.bin report.json");
        List<Input> inputs=read(Path.of(args[0]));
        Timing actual=measure(inputs),capacity=measure(List.of(capacity()));
        String result="{\"algorithmVersion\":\""+RecommendationOptimizer.ALGORITHM_VERSION+"\",\"hostOs\":\""+System.getProperty("os.name")
                +"\",\"javaVersion\":\""+System.getProperty("java.version")+"\",\"realInputCount\":"+inputs.size()
                +",\"realFixedInputTiming\":"+actual.json()+",\"syntheticCapacity20Timing\":"+capacity.json()
                +",\"scope\":\"optimizer only; excludes model/network/independent verifier; capacity fixture not real resources\"}";
        Files.writeString(Path.of(args[1]),result,StandardCharsets.UTF_8);System.out.println(result);
    }
    record Timing(int samples,double p50Ms,double p95Ms,double maxMs){String json(){return "{\"samples\":"+samples+",\"p50Ms\":"+p50Ms+",\"p95Ms\":"+p95Ms+",\"maxMs\":"+maxMs+",\"p95Below100Ms\":"+(p95Ms<100)+"}";}}
    static Timing measure(List<Input> inputs){
        for(int i=0;i<150;i++)RecommendationOptimizer.select(inputs.get(i%inputs.size()));
        long[] elapsed=new long[500];
        for(int i=0;i<elapsed.length;i++){long start=System.nanoTime();RecommendationOptimizer.select(inputs.get(i%inputs.size()));elapsed[i]=System.nanoTime()-start;}
        Arrays.sort(elapsed);return new Timing(500,elapsed[249]/1_000_000d,elapsed[474]/1_000_000d,elapsed[499]/1_000_000d);
    }
    static Input capacity(){
        List<Candidate> values=new ArrayList<>();for(int i=0;i<20;i++)values.add(new Candidate("capacity-"+i,80+i,Set.of("g"+(i%5)),i%2==0?"concept":"observation",.5+(i%5)*.1,.8,0,true,""));
        return new Input(values,Set.of("g0","g1","g2","g3","g4"),600,false,Map.of());
    }
    static List<Input> read(Path path) throws IOException {
        try(DataInputStream in=new DataInputStream(Files.newInputStream(path))){
            if(!text(in).equals("xiaotao-optimizer-timing-v1"))throw new IOException("unknown format");
            int count=bounded(in.readInt(),1,100);List<Input> result=new ArrayList<>();
            for(int r=0;r<count;r++){
                Set<String> goals=strings(in);int budget=in.readInt();boolean preference=in.readBoolean();List<Candidate> candidates=new ArrayList<>();
                int n=bounded(in.readInt(),0,20);for(int i=0;i<n;i++)candidates.add(new Candidate(text(in),in.readInt(),strings(in),text(in),in.readDouble(),in.readDouble(),in.readDouble(),in.readBoolean(),text(in)));
                Map<String,Double> pairs=new HashMap<>();int pairCount=bounded(in.readInt(),0,190);for(int i=0;i<pairCount;i++)pairs.put(text(in),in.readDouble());
                result.add(new Input(candidates,goals,budget,preference,pairs));
            }
            if(in.read()!=-1)throw new IOException("unexpected trailing input");return result;
        }
    }
    static Set<String> strings(DataInputStream in)throws IOException{int count=bounded(in.readInt(),0,100);Set<String> values=new TreeSet<>();for(int i=0;i<count;i++)values.add(text(in));return values;}
    static String text(DataInputStream in)throws IOException{int size=bounded(in.readInt(),0,10000);byte[] bytes=in.readNBytes(size);if(bytes.length!=size)throw new EOFException();return new String(bytes,StandardCharsets.UTF_8);}
    static int bounded(int n,int min,int max)throws IOException{if(n<min||n>max)throw new IOException("input bounds");return n;}
}
