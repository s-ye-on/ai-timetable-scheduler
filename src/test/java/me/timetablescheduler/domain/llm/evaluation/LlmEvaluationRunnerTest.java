package me.timetablescheduler.domain.llm.evaluation;

import static me.timetablescheduler.domain.llm.evaluation.EvaluationSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import me.timetablescheduler.domain.llm.config.OpenAiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LlmEvaluationRunnerTest {
    private static final String KEY = "sk-evaluation-fake-not-a-real-api-key";
    @TempDir Path directory;
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final LlmEvaluationRunner runner = new LlmEvaluationRunner(
        new OpenAiProperties(KEY,"stub-service-model","https://openai.test/v1"),builder);

    @ParameterizedTest
    @CsvSource({"v2,46,1", "v2,46,3", "v3,56,1", "v3,56,3"})
    void 전체_데이터셋을_고정_시각으로_평가하고_반복별_결과를_저장한다(String version, int count, int repeats) throws Exception {
        Map<String,Object> data=LlmEvaluationRunner.dataset(version);
        for(Object item:list(data.get("cases"))) for(int i=0;i<repeats;i++) {
            Map<String,Object> c=map(item);
            String date=OffsetDateTime.parse(text(map(c.get("reference")).get("requestedAt"))).toLocalDate().toString();
            server.expect(requestTo("https://openai.test/v1/responses"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(date)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("stub-service-model")))
                .andRespond(withSuccess(envelope(raw(c)),MediaType.APPLICATION_JSON));
        }
        Path output=runner.run(data,repeats,directory,"STUB");
        server.verify();
        List<Object> cases=list(MAPPER.readValue(Files.readString(output.resolve("cases.json")),Object.class));
        assertEquals(count*repeats,cases.size());
        for(Object item:cases) {
            Map<String,Object> c=map(item);
            assertEquals("PASS",c.get("schemaStatus"),text(c.get("id")));
            assertEquals("PASS",c.get("rawStatus"),text(c.get("id")));
            assertEquals("PASS",c.get("javaStatus"),text(c.get("id"))+": "+MAPPER.writeValueAsString(c.get("actualJava")));
            assertTrue(list(c.get("criticalErrors")).isEmpty(),text(c.get("id")));
            assertNotNull(c.get("promptSha256"));
            assertNotNull(c.get("schemaSha256"));
        }
        Map<String,Object> summary=read(Files.readString(output.resolve("summary.json")));
        assertEquals(1.0,map(summary.get("rawStatus")).get("accuracy"));
        assertEquals(1.0,map(summary.get("javaStatus")).get("accuracy"));
        assertEquals(count*repeats,Files.readAllLines(output.resolve("cases.jsonl")).size());
        assertTrue(Files.exists(output.resolve("cases.csv")));
        assertTrue(Files.exists(output.resolve("fields.csv")));
        assertTrue(Files.readString(output.resolve("summary.csv")).contains("rawStatus.accuracy"));
        assertTrue(Files.exists(output.resolve("manual-review.csv")));
        assertEquals("STUB",read(Files.readString(output.resolve("manifest.json"))).get("mode"));
        assertEquals(data.get("datasetVersion"),read(Files.readString(output.resolve("manifest.json"))).get("datasetVersion"));
    }

    @Test
    void 필수_조건_손실은_중대_오류이고_Java_차단을_별도로_평가한다() throws Exception {
        Map<String,Object> c=caseAt(10), changed=raw(c);
        changed.put("dateRequired",false);
        respond(changed);
        Map<String,Object> result=runner.evaluate(c,1);
        assertEquals("FAIL",result.get("rawStatus"));
        assertFalse(list(result.get("criticalErrors")).isEmpty());
        assertEquals(false,map(result.get("actualJava")).get("taskCreatable"));
        assertEquals(true,result.get("rawErrorBlocked"));
        server.verify();
    }

    @Test
    void 정확한_시각_변경은_중대_오류로_집계한다() throws Exception {
        Map<String,Object> c=caseAt(4), changed=raw(c);
        changed.put("preferredStartTime","19:00");
        respond(changed);
        Map<String,Object> result=runner.evaluate(c,1);
        assertEquals("FAIL",result.get("rawStatus"));
        assertTrue(list(result.get("criticalErrors")).stream().anyMatch(e->text(e).startsWith("preferredStartTime:")));
        server.verify();
    }

    @Test
    void 스키마의_필수키_누락은_명시적_null과_구분한다() throws Exception {
        Map<String,Object> c=caseAt(7), changed=raw(c);
        changed.remove("deadline");
        respond(changed);
        Map<String,Object> result=runner.evaluate(c,1);
        assertEquals("FAIL",result.get("schemaStatus"));
        assertEquals("FAIL",result.get("rawStatus"));
        assertEquals("PASS",result.get("javaStatus"));
        assertTrue(list(result.get("schemaErrors")).contains("$.deadline:MISSING"));
        server.verify();
    }

    @Test
    void 계약_전용_필드는_오답이어도_의미_정확도에서_제외하고_계약_오류로_집계한다() throws Exception {
        Map<String,Object> c=caseAt(4), changed=raw(c);
        changed.put("preferredTimeRange","MORNING");
        respond(changed);
        Map<String,Object> result=runner.evaluate(c,1);
        assertEquals("FAIL",result.get("rawStatus"));
        assertEquals("PASS",result.get("rawSemanticStatus"));
        Map<String,Object> summary=EvaluationReport.summary(List.of(result));
        assertEquals(0L,map(map(summary.get("byField")).get("raw.preferredTimeRange")).get("denominator"));
        assertEquals(1L,map(map(summary.get("byContractField")).get("raw.preferredTimeRange")).get("FAIL"));
        server.verify();
    }

    @Test
    void 새_제목과_확인_문구의_의미는_수동_검토하고_정답률에_포함하지_않는다() throws Exception {
        Map<String,Object> titleCase=caseAt(4), title=raw(titleCase);
        title.put("title","새로운 학습 이름");
        respond(title);
        Map<String,Object> clarificationCase=caseAt(30), clarification=raw(clarificationCase);
        clarification.put("clarificationReason","하나의 요일을 골라 주시겠어요?");
        respond(clarification);
        Map<String,Object> titleResult=runner.evaluate(titleCase,1);
        assertEquals("MANUAL",titleResult.get("rawStatus"));
        assertEquals("MANUAL",titleResult.get("javaStatus"));
        assertEquals(true,titleResult.get("manualReviewRequired"));
        Map<String,Object> clarificationResult=runner.evaluate(clarificationCase,1);
        assertEquals("MANUAL",clarificationResult.get("rawStatus"));
        assertEquals("PASS",clarificationResult.get("javaStatus"));
        Map<String,Object> summary=EvaluationReport.summary(List.of(titleResult,clarificationResult));
        assertEquals(0.0,map(summary.get("rawStatus")).get("accuracy"));
        assertEquals(2L,map(summary.get("rawStatus")).get("denominator"));
        server.verify();
    }

    @Test
    void HTTP_실패_거절_미완성_잘못된_JSON_이후에도_다음_문장을_평가한다() throws Exception {
        List<Object> inputs=new ArrayList<>(list(LlmEvaluationRunner.dataset().get("cases")).subList(0,5));
        server.expect(requestTo("https://openai.test/v1/responses"))
            .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .body(MAPPER.writeValueAsString(Map.of("error",Map.of("code","insufficient_quota","type",KEY,"message",KEY))))
                .contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://openai.test/v1/responses"))
            .andRespond(withSuccess("{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"refusal\",\"refusal\":\"secret\"}]}]}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://openai.test/v1/responses"))
            .andRespond(withSuccess("{\"status\":\"incomplete\",\"output\":[]}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://openai.test/v1/responses"))
            .andRespond(withSuccess(envelopeText("{bad json"),MediaType.APPLICATION_JSON));
        respond(raw(map(inputs.getLast())));
        Path output=runner.run(Map.of("datasetVersion","failure-stub","cases",inputs),1,directory,"STUB");
        List<Object> cases=list(MAPPER.readValue(Files.readString(output.resolve("cases.json")),Object.class));
        assertEquals(List.of("HTTP_ERROR","MODEL_REFUSAL","INCOMPLETE_RESPONSE","INVALID_OUTPUT_JSON"),
            cases.subList(0,4).stream().map(x->map(x).get("failure")).toList());
        assertEquals("FAIL",map(cases.get(3)).get("schemaStatus"));
        assertEquals("insufficient_quota",map(cases.getFirst()).get("providerErrorCode"));
        assertEquals("OTHER",map(cases.getFirst()).get("providerErrorType"));
        assertEquals("PASS",map(cases.getLast()).get("javaStatus"));
        assertEquals(5,Files.readAllLines(output.resolve("cases.jsonl")).size());
        Map<String,Object> summary=read(Files.readString(output.resolve("summary.json")));
        assertEquals(5,((Number)map(map(summary.get("byField")).get("raw.title")).get("denominator")).intValue());
        for(Path file:Files.list(output).toList()) assertFalse(Files.readString(file).contains(KEY));
        server.verify();
    }

    @Test
    void 전송_실패도_민감한_예외_메시지_없이_기록한다() throws Exception {
        server.expect(requestTo("https://openai.test/v1/responses")).andRespond(withException(new IOException(KEY)));
        Map<String,Object> result=runner.evaluate(caseAt(7),1);
        assertEquals("TRANSPORT_ERROR",result.get("failure"));
        assertEquals("ERROR",result.get("rawStatus"));
        assertEquals("NOT_EVALUATED",result.get("javaStatus"));
        assertFalse(MAPPER.writeValueAsString(result).contains(KEY));
        server.verify();
    }

    @Test
    void 스키마는_잘못된_타입_추가키_중복키를_허용하지_않는다() {
        Map<String,Object> schema=Map.of("type","object","required",List.of("value"),"additionalProperties",false,
            "properties",Map.of("value",Map.of("type",List.of("integer","null"))));
        assertTrue(schemaErrors(schema,Map.of("value",1)).isEmpty());
        assertFalse(schemaErrors(schema,Map.of("value","1")).isEmpty());
        assertFalse(schemaErrors(schema,Map.of("value",1,"extra",true)).isEmpty());
        assertFalse(schemaErrors(Map.of("type","integer","minimum",1),2).isEmpty());
        assertThrows(RuntimeException.class,()->read("{\"a\":1,\"a\":2}"));
    }

    @Test
    void 시간의_동등성은_초를_임의로_버리지_않는다() {
        assertTrue(GoldenScorer.equivalent("preferredStartTime","19:10","19:10:00"));
        assertFalse(GoldenScorer.equivalent("preferredStartTime","19:10","19:10:30"));
    }

    @Test
    void 결과_파일은_키와_Bearer를_가리고_CSV_수식과_줄바꿈을_안전하게_저장한다() throws Exception {
        EvaluationReport report=new EvaluationReport(directory,KEY);
        Map<String,Object> r=new LinkedHashMap<>();
        r.put("id","=formula\n\"quoted\"");
        r.put("rawStatus","ERROR");r.put("javaStatus","NOT_EVALUATED");r.put("schemaStatus","NOT_EVALUATED");
        r.put("expectedRaw",Map.of(KEY,"Bearer\ttoken-value "+KEY));
        report.appendCheckpoint(r);report.finish(Map.of("mode","STUB"),List.of(r));
        for(Path file:Files.list(report.directory).toList()) {
            String contents=Files.readString(file);
            assertFalse(contents.contains(KEY));
            assertFalse(contents.contains("token-value"));
        }
        String csv=Files.readString(report.directory.resolve("cases.csv"));
        assertTrue(csv.contains("\"'=formula\n\"\"quoted\"\"\""));
    }

    @Test
    void 실제_호출은_모든_실행_조건이_맞아야만_허용한다() {
        Properties p=new Properties();
        p.setProperty("llm.eval.task","live");p.setProperty("llm.eval.enabled","true");
        Map<String,String> env=Map.of("LLM_EVAL_ALLOW_API","true","OPENAI_API_KEY",KEY);
        assertEquals(1,LlmLiveEvaluationTest.validatePermission(p,env));
        p.setProperty("llm.eval.repeats","3");
        assertEquals(3,LlmLiveEvaluationTest.validatePermission(p,env));
        for(String option:List.of("llm.eval.task","llm.eval.enabled")) {
            Properties missing=new Properties();missing.putAll(p);missing.remove(option);
            assertThrows(IllegalStateException.class,()->LlmLiveEvaluationTest.validatePermission(missing,env));
        }
        for(String option:env.keySet()) {
            Map<String,String> missing=new HashMap<>(env);missing.remove(option);
            assertThrows(IllegalStateException.class,()->LlmLiveEvaluationTest.validatePermission(p,missing));
        }
        p.setProperty("llm.eval.repeats","2");
        assertThrows(IllegalArgumentException.class,()->LlmLiveEvaluationTest.validatePermission(p,env));
    }

    @Test
    void 서비스_설정과_프로파일은_읽되_서버와_DB는_기동하지_않는다() throws Exception {
        Path config=directory.resolve("application.properties");
        Files.writeString(config,"openai.model=base-model\nopenai.base-url=https://api.openai.com/v1\n");
        Files.writeString(directory.resolve("application-eval.properties"),"openai.model=profile-model\n");
        var environment=new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().remove("systemProperties");
        environment.getPropertySources().remove("systemEnvironment");
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("stub",Map.of(
            "spring.config.location",config.toUri().toString(),"spring.profiles.active","eval")));
        OpenAiProperties configResult=LlmLiveEvaluationTest.configuration(KEY,environment);
        assertEquals("profile-model",configResult.model());
        assertEquals("https://api.openai.com/v1",configResult.baseUrl());
        assertEquals(KEY,configResult.apiKey());
        // 실제 API 통신은 configuration에서 일어나지 않는다.
        server.verify();
    }

    @Test
    void 키를_전달할_수_있는_주소는_공식_HTTPS_엔드포인트로_제한한다() {
        for(String url:List.of("http://api.openai.com/v1","https://example.com/v1",
            "https://user@api.openai.com/v1","https://api.openai.com/v1?key=unexpected")) {
            var environment=new org.springframework.core.env.StandardEnvironment();
            environment.getPropertySources().remove("systemProperties");
            environment.getPropertySources().remove("systemEnvironment");
            environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("stub",Map.of(
                "spring.config.location","optional:file:/does-not-exist.properties",
                "openai.model","stub-model","openai.base-url",url)));
            assertThrows(IllegalStateException.class,()->LlmLiveEvaluationTest.configuration(KEY,environment));
        }
        server.verify();
    }

    @Test
    void 상대_간격_변경은_실제_계산_결과와_중대_오류에_반영한다() throws Exception {
        Map<String,Object> c=caseAt(38), changed=raw(c);
        changed.put("startOffsetMinutes",90);
        respond(changed);
        Map<String,Object> result=runner.evaluate(c,1);
        assertEquals("FAIL",result.get("rawStatus"));
        assertEquals("FAIL",result.get("javaStatus"));
        assertTrue(list(result.get("criticalErrors")).stream().anyMatch(e->text(e).startsWith("startOffsetMinutes:")));
        assertEquals("11:30:00",text(map(map(result.get("actualJava")).get("normalized")).get("preferredStartTime")));
        server.verify();
    }

    @Test
    void G47_원문_변경은_LLM_오답으로_남기고_Java만_원문을_보존한다() throws Exception {
        Map<String,Object> c=map(list(LlmEvaluationRunner.dataset("v3").get("cases")).get(46));
        Map<String,Object> changed=raw(c);
        changed.put("description",text(c.get("input")).substring(3));
        respond(changed);
        Map<String,Object> result=runner.evaluate(c,1);
        assertEquals("FAIL",result.get("rawStatus"));
        assertEquals(changed.get("description"),map(result.get("actualRaw")).get("description"));
        assertEquals("PASS",result.get("javaStatus"));
        assertEquals(c.get("input"),map(map(result.get("actualJava")).get("normalized")).get("description"));
        server.verify();
    }

    @Test
    void v3는_v2의_46개_사례를_그대로_보존하고_56개를_읽는다() throws Exception {
        Map<String,Object> previous=LlmEvaluationRunner.dataset("v2");
        Map<String,Object> current=LlmEvaluationRunner.dataset("v3");
        assertEquals("3.0.0",current.get("datasetVersion"));
        assertEquals(56,list(current.get("cases")).size());
        assertEquals(list(previous.get("cases")),list(current.get("cases")).subList(0,46));
    }

    @Test
    void 이전_데이터셋은_보존하고_새_정책_평가는_v2로_구분한다() throws Exception {
        assertEquals(40,list(LlmEvaluationRunner.dataset("v1").get("cases")).size());
        assertEquals("2.0.0",LlmEvaluationRunner.dataset().get("datasetVersion"));
        assertEquals(46,list(LlmEvaluationRunner.dataset().get("cases")).size());
        assertThrows(IOException.class,()->LlmEvaluationRunner.dataset("v4"));
    }

    @Test
    void 실제_평가_설정은_기본_v2와_명시적으로_선택한_버전을_구분한다() throws Exception {
        Properties options=new Properties();
        assertEquals("2.0.0",LlmLiveEvaluationTest.dataset(options).get("datasetVersion"));
        for(String version:List.of("v1","v2","v3")) {
            options.setProperty("llm.eval.dataset",version);
            assertEquals(LlmEvaluationRunner.dataset(version),LlmLiveEvaluationTest.dataset(options));
        }
        options.setProperty("llm.eval.dataset","../v3");
        assertThrows(IOException.class,()->LlmLiveEvaluationTest.dataset(options));
    }

    @ParameterizedTest
    @ValueSource(ints={55,56})
    void 활동_확인_요청은_분류_누락보다_먼저_처리하고_Task를_생성하지_않는다(int number) throws Exception {
        Map<String,Object> c=map(list(LlmEvaluationRunner.dataset("v3").get("cases")).get(number-1));
        respond(raw(c));
        Map<String,Object> result=runner.evaluate(c,1);
        Map<String,Object> actual=map(result.get("actualJava"));
        assertEquals(number==55?"MISSING_LLM_TITLE":"LLM_CLARIFICATION_REQUIRED",actual.get("errorCode"));
        assertEquals(400,actual.get("validationHttpStatus"));
        assertEquals(false,actual.get("taskCreatable"));
        assertNull(actual.get("normalized"));
        assertEquals("PASS",result.get("javaStatus"));
        server.verify();
    }

    @Test
    void 새_평가_보고서는_이전_결과를_덮어쓰지_않는다() throws Exception {
        Map<String,Object> c=caseAt(7);
        respond(raw(c));respond(raw(c));
        Map<String,Object> data=Map.of("datasetVersion","stub","cases",List.of(c));
        Path first=runner.run(data,1,directory,"STUB");
        byte[] original=Files.readAllBytes(first.resolve("summary.json"));
        Path second=runner.run(data,1,directory,"STUB");
        assertNotEquals(first,second);
        assertArrayEquals(original,Files.readAllBytes(first.resolve("summary.json")));
        server.verify();
    }

    private Map<String,Object> caseAt(int number) throws IOException {
        return map(list(LlmEvaluationRunner.dataset().get("cases")).get(number-1));
    }
    private Map<String,Object> raw(Map<String,Object> c) {
        return new LinkedHashMap<>(map(map(c.get("llmExpected")).get("parsedTaskResponse")));
    }
    private void respond(Map<String,Object> raw) {
        server.expect(requestTo("https://openai.test/v1/responses"))
            .andRespond(withSuccess(envelope(raw),MediaType.APPLICATION_JSON));
    }
    private String envelope(Map<String,Object> raw) {return envelopeText(MAPPER.writeValueAsString(raw));}
    private String envelopeText(String raw) {
        return MAPPER.writeValueAsString(Map.of("status","completed","model","stub-service-model",
            "usage",Map.of("input_tokens",100,"output_tokens",50,"total_tokens",150),
            "output",List.of(Map.of("content",List.of(Map.of("type","output_text","text",raw))))));
    }
}
