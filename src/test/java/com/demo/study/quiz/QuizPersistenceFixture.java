package com.demo.study.quiz;

import com.demo.entity.QuizAnswerEntity;
import com.demo.entity.QuizPaperEntity;
import com.demo.entity.QuizQuestionEntity;
import com.demo.mapper.*;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.util.ReflectionTestUtils;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实SQL+事务夹具；Mapper替身仅桥接实际注解SQL，不用内存赋值冒充数据库并发结论。 */
public final class QuizPersistenceFixture {
    public final JdbcTemplate jdbc;
    public final DataSourceTransactionManager manager;
    public final QuizService service = new QuizService();
    public final QuizAnswerMapper answers = mock(QuizAnswerMapper.class);
    public final QuizQuestionMapper questions = mock(QuizQuestionMapper.class);
    public final QuizPaperMapper papers = mock(QuizPaperMapper.class);
    public final OpenAiChatModel model = mock(OpenAiChatModel.class);

    public QuizPersistenceFixture() throws Exception {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:quiz_prepared_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(data); manager = new DataSourceTransactionManager(data);
        jdbc.execute("CREATE TABLE quiz_paper(id BIGINT PRIMARY KEY,user_id VARCHAR(128),title VARCHAR(200),deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE quiz_question(id BIGINT PRIMARY KEY,paper_id BIGINT,question_no INT,q_type VARCHAR(30),stem VARCHAR(2000),options CLOB,answer VARCHAR(2000),explanation VARCHAR(2000),knowledge_point VARCHAR(100),has_answer INT,passage_no INT)");
        jdbc.execute("CREATE TABLE quiz_answer(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id VARCHAR(128),paper_id BIGINT,question_id BIGINT,user_answer VARCHAR(4000),is_correct INT,answered_at TIMESTAMP(3) DEFAULT CURRENT_TIMESTAMP(3))");
        for (String sql : Files.readString(Path.of("deploy/sql/20261017-quiz-history.sql")).replaceAll("(?m)^--.*$", "").split(";"))
            if (!sql.isBlank()) jdbc.execute(sql);
        jdbc.update("INSERT INTO quiz_paper VALUES(7,'classroom','原课程',0)");
        when(questions.selectList(any())).thenAnswer(call -> readQuestions("SELECT * FROM quiz_question ORDER BY question_no", new Object[]{}));
        when(questions.selectById(anyLong())).thenAnswer(call -> readQuestions("SELECT * FROM quiz_question WHERE id=?", new Object[]{call.getArgument(0,Long.class)}).stream().findFirst().orElse(null));
        when(questions.selectBatchIds(any())).thenAnswer(call -> readQuestions("SELECT * FROM quiz_question ORDER BY question_no",new Object[]{}).stream()
                .filter(q -> ((Collection<?>)call.getArgument(0)).contains(q.getId())).toList());
        when(papers.selectById(anyLong())).thenAnswer(call -> {
            Long id=call.getArgument(0);var rows=jdbc.queryForList("SELECT * FROM quiz_paper WHERE id=?",id);
            if(rows.isEmpty())return null;var row=rows.getFirst();var paper=new QuizPaperEntity();
            paper.setId(id);paper.setUserId((String)row.get("user_id"));paper.setTitle((String)row.get("title"));paper.setDeleted((Integer)row.get("deleted"));return paper;
        });
        when(papers.selectBatchIds(any())).thenAnswer(call -> {
            List<QuizPaperEntity> result=new ArrayList<>();
            for(Object id:(Collection<?>)call.getArgument(0))result.add(papers.selectById(((Number)id).longValue()));return result;
        });
        when(answers.insert(any(QuizAnswerEntity.class))).thenAnswer(call -> {
            QuizAnswerEntity answer=call.getArgument(0);var key=new GeneratedKeyHolder();
            int count=jdbc.update(connection->{
                // H2会同时返回带默认值的answered_at；明确只取ID以匹配MyBatis主键回填契约。
                var statement=connection.prepareStatement("INSERT INTO quiz_answer(user_id,paper_id,question_id,user_answer,is_correct) VALUES(?,?,?,?,?)",new String[]{"ID"});
                statement.setString(1,answer.getUserId());statement.setLong(2,answer.getPaperId());statement.setLong(3,answer.getQuestionId());
                statement.setString(4,answer.getUserAnswer());statement.setInt(5,answer.getIsCorrect());return statement;
            },key);
            answer.setId(Objects.requireNonNull(key.getKey()).longValue());return count;
        });
        when(answers.findAttemptRequest(anyString(),anyString())).thenAnswer(call -> {
            Sql query=sql("findAttemptRequest",call.getArguments());var rows=jdbc.queryForList(query.text(),query.args());return rows.isEmpty()?null:rows.getFirst();
        });
        when(answers.createAttemptRequest(anyString(),anyString(),anyLong(),anyString(),anyString(),anyString())).thenAnswer(call -> update("createAttemptRequest",call.getArguments()));
        when(answers.claimAttemptEvaluation(anyLong(),anyString())).thenAnswer(call -> update("claimAttemptEvaluation",call.getArguments()));
        when(answers.recordAttemptEvaluation(anyLong(),anyString(),anyString(),anyString())).thenAnswer(call -> update("recordAttemptEvaluation",call.getArguments()));
        when(answers.markAttemptEvaluationUnknown(anyLong(),anyString())).thenAnswer(call -> update("markAttemptEvaluationUnknown",call.getArguments()));
        when(answers.finishAttemptRequest(anyLong(),anyString(),anyString(),anyString())).thenAnswer(call -> update("finishAttemptRequest",call.getArguments()));
        when(answers.recordResolution(anyString(),anyLong(),anyBoolean(),anyLong(),nullable(Long.class),anyString())).thenAnswer(call -> update("recordResolution",call.getArguments()));
        when(answers.isMistakeResolved(anyString(),anyLong())).thenAnswer(call -> {
            Sql query=sql("isMistakeResolved",call.getArguments());var rows=jdbc.queryForList(query.text(),Integer.class,query.args());return rows.isEmpty()?null:rows.getFirst();
        });
        when(answers.selectUnresolvedMistakes(anyString())).thenAnswer(call -> {
            Sql query=sql("selectUnresolvedMistakes",call.getArguments());
            return jdbc.query(query.text(),(row,index)->{
                var answer=new QuizAnswerEntity(row.getString("user_id"),row.getLong("paper_id"),row.getLong("question_id"),row.getString("user_answer"),row.getInt("is_correct"));
                answer.setId(row.getLong("id"));answer.setAnsweredAt(row.getTimestamp("answered_at").toLocalDateTime());return answer;
            },query.args());
        });
        when(answers.selectKnowledgeStats(anyString())).thenAnswer(call -> {
            Sql query=sql("selectKnowledgeStats",call.getArguments());return jdbc.queryForList(query.text(),query.args());
        });
        var favorites=mock(QuizKnowledgeFavMapper.class);when(favorites.selectList(any())).thenReturn(List.of());
        ReflectionTestUtils.setField(service,"questionMapper",questions);ReflectionTestUtils.setField(service,"answerMapper",answers);
        ReflectionTestUtils.setField(service,"paperMapper",papers);ReflectionTestUtils.setField(service,"deepseekChatModel",model);
        ReflectionTestUtils.setField(service,"knowledgeFavMapper",favorites);service.setTransactionManager(manager);
    }

    public void question(long id,int no,String type,String answer,String knowledge) {
        jdbc.update("INSERT INTO quiz_question VALUES(?,7,?,?,?,'[]',?,'依据参考要点评阅',?,1,NULL)",id,no,type,"第"+no+"题",answer,knowledge);
    }
    public List<Map<String,Object>> history() { return jdbc.queryForList("SELECT id,user_answer,is_correct,answered_at FROM quiz_answer ORDER BY id"); }
    /** 新服务实例不继承内存flight，仅复用持久数据库，模拟重启后的恢复边界。 */
    public QuizService restartedService() {
        var restarted=new QuizService();restarted.setTransactionManager(manager);
        ReflectionTestUtils.setField(restarted,"questionMapper",questions);ReflectionTestUtils.setField(restarted,"answerMapper",answers);
        ReflectionTestUtils.setField(restarted,"paperMapper",papers);ReflectionTestUtils.setField(restarted,"deepseekChatModel",model);
        return restarted;
    }
    private List<QuizQuestionEntity> readQuestions(String sql,Object[] args) {
        return jdbc.query(sql,(row,index)->{
            var q=new QuizQuestionEntity();q.setId(row.getLong("id"));q.setPaperId(row.getLong("paper_id"));q.setQuestionNo(row.getInt("question_no"));
            q.setQType(row.getString("q_type"));q.setStem(row.getString("stem"));q.setOptions(row.getString("options"));q.setAnswer(row.getString("answer"));
            q.setExplanation(row.getString("explanation"));q.setKnowledgePoint(row.getString("knowledge_point"));q.setHasAnswer(row.getInt("has_answer"));
            q.setPassageNo((Integer)row.getObject("passage_no"));return q;
        },args);
    }
    private int update(String name,Object[] args) throws Exception { Sql query=sql(name,args);return jdbc.update(query.text(),query.args()); }
    private record Sql(String text,Object[] args) {}
    private static Sql sql(String name,Object[] values) throws Exception {
        Method method=Arrays.stream(QuizAnswerMapper.class.getMethods()).filter(m->m.getName().equals(name)).findFirst().orElseThrow();
        String query=method.isAnnotationPresent(Select.class)?String.join(" ",method.getAnnotation(Select.class).value())
                :method.isAnnotationPresent(Insert.class)?String.join(" ",method.getAnnotation(Insert.class).value()):String.join(" ",method.getAnnotation(Update.class).value());
        Map<String,Object> parameters=new HashMap<>();var annotations=method.getParameterAnnotations();
        for(int index=0;index<annotations.length;index++)for(var annotation:annotations[index])if(annotation instanceof Param param)parameters.put(param.value(),values[index]);
        var matcher=Pattern.compile("#\\{([^}]+)}").matcher(query);List<Object> ordered=new ArrayList<>();StringBuilder rendered=new StringBuilder();
        while(matcher.find()){ordered.add(parameters.get(matcher.group(1)));matcher.appendReplacement(rendered,"?");}matcher.appendTail(rendered);
        return new Sql(rendered.toString(),ordered.toArray());
    }
}
