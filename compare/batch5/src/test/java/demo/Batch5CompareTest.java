package demo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * toy-batch 의 02-1 / 02-2 scan·retry 케이스를 Spring Batch 5.2.4 로 똑같이 돌려서 Batch 6 과 비교한다.
 * (메인 프로젝트는 Batch 6. 이 폴더는 따로 빌드되는 Boot 3.5 / Batch 5.2 프로젝트)
 *
 *   A : write 13 이 잘못된 데이터 → skip (02-1 ftSkipJob)
 *   B : 100건 / chunk 100 / 4건 skip (02-2 Q4-2 ftScanIoJob)
 *   C : B 와 같은데 skipLimit 3 + 재시작 (02-2 Q4-3 ftScanSkipLimitJob)
 *   D : write 13 이 1번 Transient → write 재시도 (02-1 ftRetryWriteJob)
 *   E : 같은 청크 [6..10] 에서 process 8 skip + write 9 skip → SkipListener DB 기록 (02-1 ftSkipListenerContractJob)
 *   F : 같은 청크 [6..10] 에서 process 8 skip 후 write 9 가 skip 대상이 아닌 예외 → 스텝 실패. 8 의 skip 리스너가 불리는지
 *   G : C 와 같은데 reader 가 saveState(false) + 처리 여부 표시(아직 저장 안 된 것만 읽음). 재시작 때 ChunkMonitor 가 item 을 버리는지
 *
 * 실행: ./gradlew -p compare/batch5 test   → 로그의 RESULT 줄을 보면 된다
 */
@SpringBootTest(classes = Batch5CompareTest.App.class)
class Batch5CompareTest {

    @SpringBootApplication
    static class App {}

    static class Transient extends RuntimeException { Transient(String m) { super(m); } }
    static class Invalid extends RuntimeException { Invalid(String m) { super(m); } }

    @Autowired JobRepository jobRepository;
    @Autowired PlatformTransactionManager tm;
    @Autowired JdbcTemplate jdbc;
    @Autowired JobLauncher launcher;

    static final Map<String, Integer> attempts = new ConcurrentHashMap<>();
    static final List<Integer> writeCalls = Collections.synchronizedList(new ArrayList<>());
    static final List<String> skipsMemory = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void reset() {
        attempts.clear(); writeCalls.clear(); skipsMemory.clear();
        jdbc.update("DELETE FROM ft_result"); jdbc.update("DELETE FROM ft_skip_log");
    }

    static int attempt(String key) { return attempts.merge(key, 1, Integer::sum); }

    /** 1..total, saveState 면 read.count 저장 */
    static class Reader implements ItemStreamReader<Integer> {
        final int total; final boolean saveState; int cur;
        Reader(int total, boolean saveState) { this.total = total; this.saveState = saveState; }
        public void open(ExecutionContext c) { cur = saveState && c.containsKey("r.count") ? c.getInt("r.count") : 0; }
        public void update(ExecutionContext c) { if (saveState) c.putInt("r.count", cur); }
        public Integer read() { return cur >= total ? null : ++cur; }
    }

    ItemWriter<Integer> writer(Set<Integer> invalid, Map<Integer, Integer> transientTimes) {
        return chunk -> {
            writeCalls.add(chunk.size());
            for (Integer i : chunk) {
                int a = attempt("write:" + i);
                if (invalid.contains(i)) throw new Invalid("write " + i);
                if (transientTimes.getOrDefault(i, 0) >= a) throw new Transient("write " + i + " attempt " + a);
                jdbc.update("INSERT INTO ft_result(job_name, item_value) VALUES ('j', ?)", i);
            }
        };
    }

    ItemProcessor<Integer, Integer> processor() {
        return i -> { attempt("process:" + i); return i; };
    }

    SkipListener<Integer, Integer> skipListener() {
        return new SkipListener<>() {
            public void onSkipInProcess(Integer item, Throwable t) {
                skipsMemory.add("process:" + item);
                jdbc.update("INSERT INTO ft_skip_log(job_name, stage, item_value) VALUES ('j', 'process', ?)", item);
            }
            public void onSkipInWrite(Integer item, Throwable t) {
                skipsMemory.add("write:" + item);
                jdbc.update("INSERT INTO ft_skip_log(job_name, stage, item_value) VALUES ('j', 'write', ?)", item);
            }
        };
    }

    Job skipJob(String name, int total, int chunk, Set<Integer> invalid, int skipLimit, boolean saveState) {
        Step step = new StepBuilder(name + ".step", jobRepository)
                .<Integer, Integer>chunk(chunk, tm)
                .reader(new Reader(total, saveState)).processor(processor()).writer(writer(invalid, Map.of()))
                .faultTolerant().skip(Invalid.class).skipLimit(skipLimit).listener(skipListener())
                .build();
        return new JobBuilder(name, jobRepository).start(step).build();
    }

    List<Integer> saved() { return jdbc.queryForList("SELECT item_value FROM ft_result ORDER BY item_value", Integer.class); }
    List<Integer> skipLog() { return jdbc.queryForList("SELECT item_value FROM ft_skip_log ORDER BY item_value", Integer.class); }
    JobParameters params() { return new JobParametersBuilder().addLong("t", System.nanoTime()).toJobParameters(); }

    void print(String title, JobExecution e) {
        StepExecution s = e.getStepExecutions().iterator().next();
        System.out.printf("RESULT %s status=%s read=%d write=%d writeSkip=%d commit=%d rollback=%d writeCalls=%d%n",
                title, e.getStatus(), s.getReadCount(), s.getWriteCount(), s.getWriteSkipCount(), s.getCommitCount(), s.getRollbackCount(), writeCalls.size());
    }

    @Test
    void A_ftSkipJob_와_같은_write_13_skip() throws Exception {
        JobExecution e = launcher.run(skipJob("A", 20, 5, Set.of(13), 10, false), params());
        print("A", e);
        List<Integer> s = saved();
        System.out.println("RESULT A saved=" + s.size() + " dup=" + (s.size() != new HashSet<>(s).size())
                + " writeCalls=" + writeCalls
                + " process(11..15)=" + List.of(11, 12, 13, 14, 15).stream().map(i -> attempts.getOrDefault("process:" + i, 0)).toList()
                + " write(11,13,15)=" + List.of(11, 13, 15).stream().map(i -> attempts.getOrDefault("write:" + i, 0)).toList()
                + " skipMemory=" + skipsMemory + " skipLogTable=" + skipLog());
    }

    @Test
    void B_ftScanIoJob_과_같은_100건_4건_skip() throws Exception {
        JobExecution e = launcher.run(skipJob("B", 100, 100, Set.of(10, 30, 50, 70), 10, false), params());
        print("B", e);
        System.out.println("RESULT B saved=" + saved().size() + " firstCall=" + writeCalls.get(0)
                + " process(1)=" + attempts.get("process:1") + " process(100)=" + attempts.get("process:100"));
    }

    @Test
    void C_ftScanSkipLimitJob_과_같은_skipLimit_3_그리고_재시작() throws Exception {
        Job job = skipJob("C", 100, 100, Set.of(10, 30, 50, 70), 3, true);
        JobParameters p = params();
        JobExecution e1 = launcher.run(job, p);
        print("C1", e1);
        List<Integer> s1 = saved();
        System.out.println("RESULT C1 saved=" + s1.size() + " last=" + (s1.isEmpty() ? null : s1.get(s1.size() - 1))
                + " context=" + e1.getStepExecutions().iterator().next().getExecutionContext());
        writeCalls.clear();
        JobExecution e2 = launcher.run(job, p);
        print("C2", e2);
        List<Integer> s2 = saved();
        List<Integer> missing = new ArrayList<>();
        for (int i = 1; i <= 100; i++) if (!s2.contains(i)) missing.add(i);
        System.out.println("RESULT C2 saved=" + s2.size() + " missing=" + missing);
    }

    @Test
    void D_ftRetryWriteJob_과_같은_write_13_Transient_1번_재시도() throws Exception {
        Step step = new StepBuilder("D.step", jobRepository)
                .<Integer, Integer>chunk(5, tm)
                .reader(new Reader(20, false)).processor(processor()).writer(writer(Set.of(), Map.of(13, 1)))
                .faultTolerant().retry(Transient.class).retryLimit(3)
                .build();
        JobExecution e = launcher.run(new JobBuilder("D", jobRepository).start(step).build(), params());
        print("D", e);
        List<Integer> s = saved();
        System.out.println("RESULT D saved=" + s.size() + " distinct=" + new HashSet<>(s).size()
                + " writeCalls=" + writeCalls
                + " process(11..15)=" + List.of(11, 12, 13, 14, 15).stream().map(i -> attempts.getOrDefault("process:" + i, 0)).toList());
    }

    @Test
    void E_같은_청크에서_process_skip_과_write_skip() throws Exception {
        ItemProcessor<Integer, Integer> processor = i -> {
            attempt("process:" + i);
            if (i == 8) throw new Invalid("process 8");
            return i;
        };
        Step step = new StepBuilder("E.step", jobRepository)
                .<Integer, Integer>chunk(5, tm)
                .reader(new Reader(20, false)).processor(processor).writer(writer(Set.of(9), Map.of()))
                .faultTolerant().skip(Invalid.class).skipLimit(10).listener(skipListener())
                .build();
        JobExecution e = launcher.run(new JobBuilder("E", jobRepository).start(step).build(), params());
        print("E", e);
        List<String> log = jdbc.queryForList("SELECT stage || ':' || item_value FROM ft_skip_log ORDER BY item_value", String.class);
        System.out.println("RESULT E saved=" + saved().size() + " skipMemory=" + skipsMemory + " skipLogTable=" + log);
    }

    @Test
    void F_process_skip_후_같은_청크가_skip_불가_예외로_실패() throws Exception {
        ItemProcessor<Integer, Integer> processor = i -> {
            attempt("process:" + i);
            if (i == 8) throw new Invalid("process 8");
            return i;
        };
        Step step = new StepBuilder("F.step", jobRepository)
                .<Integer, Integer>chunk(5, tm)
                .reader(new Reader(20, false)).processor(processor).writer(writer(Set.of(), Map.of(9, 99)))
                .faultTolerant().skip(Invalid.class).skipLimit(10).listener(skipListener())
                .build();
        JobExecution e = launcher.run(new JobBuilder("F", jobRepository).start(step).build(), params());
        print("F", e);
        List<String> log = jdbc.queryForList("SELECT stage || ':' || item_value FROM ft_skip_log ORDER BY item_value", String.class);
        System.out.println("RESULT F saved=" + saved().size() + " skipMemory=" + skipsMemory + " skipLogTable=" + log);
    }

    /** saveState=false, open 때 ft_result 에 없는 item 만 읽는 reader (process indicator 흉내) */
    class UndeliveredReader implements ItemStreamReader<Integer> {
        final int total; Iterator<Integer> it;
        UndeliveredReader(int total) { this.total = total; }
        public void open(ExecutionContext c) {
            List<Integer> done = saved();
            List<Integer> todo = new ArrayList<>();
            for (int i = 1; i <= total; i++) if (!done.contains(i)) todo.add(i);
            it = todo.iterator();
        }
        public Integer read() { return it.hasNext() ? it.next() : null; }
    }

    @Test
    void G_saveState_false_reader_재시작() throws Exception {
        Set<Integer> invalid = new HashSet<>(Set.of(10, 30, 50, 70));
        ItemWriter<Integer> w = chunk -> {
            writeCalls.add(chunk.size());
            for (Integer i : chunk) {
                if (invalid.contains(i)) throw new Invalid("write " + i);
                jdbc.update("INSERT INTO ft_result(job_name, item_value) VALUES ('j', ?)", i);
            }
        };
        Step step = new StepBuilder("G.step", jobRepository)
                .<Integer, Integer>chunk(100, tm)
                .reader(new UndeliveredReader(100)).processor(processor()).writer(w)
                .faultTolerant().skip(Invalid.class).skipLimit(3)
                .build();
        Job job = new JobBuilder("G", jobRepository).start(step).build();
        JobParameters p = params();
        JobExecution e1 = launcher.run(job, p);
        print("G1", e1);
        System.out.println("RESULT G1 saved=" + saved().size() + " context=" + e1.getStepExecutions().iterator().next().getExecutionContext());
        invalid.clear();
        JobExecution e2 = launcher.run(job, p);
        print("G2", e2);
        List<Integer> s2 = saved();
        List<Integer> missing = new ArrayList<>();
        for (int i = 1; i <= 100; i++) if (!s2.contains(i)) missing.add(i);
        System.out.println("RESULT G2 saved=" + s2.size() + " missing=" + missing);
    }
}
