package me.timetablescheduler.domain.llm.evaluation;

import static me.timetablescheduler.domain.llm.evaluation.EvaluationSupport.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import me.timetablescheduler.domain.llm.config.OpenAiProperties;
import me.timetablescheduler.domain.llm.dto.*;
import me.timetablescheduler.domain.llm.external.OpenAiClient;
import me.timetablescheduler.domain.llm.service.LlmParsingService;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.global.exception.ApiException;
import org.springframework.http.*;
import org.springframework.http.client.*;
import org.springframework.web.client.RestClient;

final class LlmEvaluationRunner {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final OpenAiProperties properties;
    private final RestClient.Builder builder;
    private final GoldenScorer scorer = new GoldenScorer();

    LlmEvaluationRunner(OpenAiProperties properties, RestClient.Builder builder) {
        this.properties=properties; this.builder=builder;
    }

    static Map<String,Object> dataset() throws IOException {
        return dataset("v2");
    }

    static Map<String,Object> dataset(String version) throws IOException {
        if (!Set.of("v1", "v2").contains(version)) throw new IOException("UNKNOWN_DATASET_VERSION");
        try (InputStream in=LlmEvaluationRunner.class.getResourceAsStream("/llm/golden-dataset-"+version+".json")) {
            if (in==null) throw new IOException("GOLDEN_DATASET_MISSING");
            Map<String,Object> data=read(new String(in.readAllBytes(),StandardCharsets.UTF_8));
            List<Object> cases=list(data.get("cases"));
            int expectedCount = version.equals("v1") ? 40 : 46;
            if (cases.size()!=expectedCount) throw new IOException("INVALID_CASE_COUNT");
            for (int i=0;i<expectedCount;i++) if (!String.format("G%02d",i+1).equals(map(cases.get(i)).get("id"))) throw new IOException("INVALID_CASE_IDS");
            return data;
        }
    }

    Path run(Map<String,Object> data, int repeats, Path outputRoot, String mode) throws IOException {
        if (repeats!=1 && repeats!=3) throw new IllegalArgumentException("REPEATS_MUST_BE_1_OR_3");
        EvaluationReport report = new EvaluationReport(outputRoot,properties.apiKey());
        Map<String,Object> manifest = new LinkedHashMap<>();
        manifest.put("mode",mode); manifest.put("startedAt",ZonedDateTime.now(SEOUL).toString());
        manifest.put("datasetVersion",data.get("datasetVersion")); manifest.put("datasetSha256",hash(MAPPER.writeValueAsString(data)));
        manifest.put("datasetHashFormat", "Jackson canonical serialization of loaded dataset");
        manifest.put("configuredModel",properties.model()); manifest.put("repeats",repeats);
        manifest.put("runnerVersion","2.0.0");
        manifest.put("plannedRequests",list(data.get("cases")).size()*repeats); manifest.put("codeCommit",codeCommit());
        manifest.put("javaScope","LlmParsingService + Task.create (no persistence, no HTTP 201 verification)");
        manifest.put("semanticPolicy","Known title aliases / canonical clarification only; all other meanings require manual review");
        report.writeJson("manifest.json",manifest);
        List<Map<String,Object>> results=new ArrayList<>();
        boolean completed=false;
        try {
            for (Object item:list(data.get("cases"))) for (int repeat=1;repeat<=repeats;repeat++) {
                Map<String,Object> testCase=map(item), result;
                try { result=evaluate(testCase,repeat); }
                catch (RuntimeException failure) {
                    result=new LinkedHashMap<>();result.put("id",testCase.get("id"));result.put("type",testCase.get("type"));result.put("repeat",repeat);
                    result.put("rawStatus","ERROR");result.put("javaStatus","NOT_EVALUATED");result.put("schemaStatus","NOT_EVALUATED");
                    result.put("rawSemanticStatus","ERROR");
                    result.put("dtoStatus","NOT_EVALUATED");result.put("input",testCase.get("input"));result.put("reference",testCase.get("reference"));
                    result.put("failure","EVALUATOR_ERROR");result.put("failureType",failure.getClass().getSimpleName());
                    result.put("expectedRaw",map(testCase.get("llmExpected")).get("parsedTaskResponse"));result.put("expectedJava",testCase.get("javaExpected"));
                    result.put("rawFields",scorer.compare(map(result.get("expectedRaw")),null,map(map(testCase.get("llmExpected")).get("fieldRules"))));
                    result.put("javaFields",scorer.compare(map(map(testCase.get("javaExpected")).get("normalized")),null,map(map(testCase.get("javaExpected")).get("fieldRules"))));
                }
                results.add(result); report.appendCheckpoint(result);
                if (Thread.currentThread().isInterrupted()) throw new IOException("EVALUATION_INTERRUPTED");
            }
            completed=true;
        } finally {
            manifest.put("completed",completed);manifest.put("finishedAt",ZonedDateTime.now(SEOUL).toString());
            manifest.put("attemptedRequests",results.size());
            manifest.put("promptTemplateHashes",results.stream().map(r->r.get("promptTemplateSha256")).filter(Objects::nonNull).distinct().toList());
            manifest.put("schemaHashes",results.stream().map(r->r.get("schemaSha256")).filter(Objects::nonNull).distinct().toList());
            report.finish(manifest,results);
        }
        return report.directory;
    }

    Map<String,Object> evaluate(Map<String,Object> c,int repeat) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",c.get("id"));result.put("type",c.get("type"));result.put("importance",c.get("importance"));result.put("repeat",repeat);
        result.put("input",c.get("input"));result.put("reference",c.get("reference"));result.put("evaluatedAt",ZonedDateTime.now(SEOUL).toString());
        Map<String,Object> rawExpected=map(map(c.get("llmExpected")).get("parsedTaskResponse"));
        result.put("expectedRaw",rawExpected);result.put("expectedJava",c.get("javaExpected"));
        OffsetDateTime reference=OffsetDateTime.parse(text(map(c.get("reference")).get("requestedAt")));
        Clock fixed=Clock.fixed(reference.toInstant(),SEOUL);
        Capture capture=new Capture();
        OpenAiClient client=new OpenAiClient(properties,MAPPER,builder.clone().requestInterceptor(capture));
        ParsedTaskResponse parsed=null;
        String clientError=null;
        long start=System.nanoTime();
        try {parsed=client.parseTask(text(c.get("input")),LocalDate.ofInstant(fixed.instant(),SEOUL));}
        catch (ApiException error) {clientError=error.getExceptionCode().name();}
        catch (RuntimeException error) {clientError=error.getClass().getSimpleName();}
        result.put("latencyMillis",(System.nanoTime()-start)/1_000_000);
        result.put("httpStatus",capture.status);result.put("clientError",clientError);
        result.put("dtoStatus",parsed!=null ? "PASS" : capture.outputText==null ? "NOT_EVALUATED" : "FAIL");
        result.put("model",capture.request.get("model"));
        String instructions=text(capture.request.get("instructions"));
        Map<String,Object> schema=map(map(map(capture.request.get("text")).get("format")).get("schema"));
        result.put("promptSha256",hash(instructions));result.put("schemaSha256",hash(MAPPER.writeValueAsString(schema)));
        result.put("promptTemplateSha256",hash(instructions.replace(reference.toLocalDate().toString(),"{REFERENCE_DATE}")));
        String failure=capture.failure;
        if (!instructions.contains(reference.toLocalDate().toString())) failure="REFERENCE_DATE_MISMATCH";
        if (schema.isEmpty()) failure="REQUEST_SCHEMA_MISSING";
        Map<String,Object> raw=null;
        List<String> schemaErrors=new ArrayList<>();
        if (capture.outputText!=null) {
            try {raw=read(capture.outputText);schemaErrors.addAll(schemaErrors(schema,raw));}
            catch (RuntimeException error) {schemaErrors.add("OUTPUT_JSON_INVALID"); if (failure==null) failure="INVALID_OUTPUT_JSON";}
        }
        if (capture.outputText==null && failure==null) failure="NO_OUTPUT_TEXT";
        result.put("rawOutputText",capture.outputText);result.put("actualRaw",raw);
        result.put("responseModel",capture.responseModel);result.put("usage",capture.usage);
        result.put("providerErrorCode",capture.providerErrorCode);result.put("providerErrorType",capture.providerErrorType);
        result.put("incompleteReason",capture.incompleteReason);
        result.put("schemaErrors",schemaErrors);
        result.put("schemaStatus",capture.outputText==null ? "NOT_EVALUATED" : schemaErrors.isEmpty() ? "PASS" : "FAIL");
        List<GoldenScorer.Grade> rawGrades=scorer.compare(rawExpected,raw,map(map(c.get("llmExpected")).get("fieldRules")));
        String rawStatus=failure!=null ? "ERROR" : schemaErrors.isEmpty() ? scorer.verdict(rawGrades) : "FAIL";
        result.put("rawFields",rawGrades);result.put("rawStatus",rawStatus);result.put("failure",failure);
        result.put("rawSemanticStatus",failure!=null ? "ERROR" : scorer.verdict(rawGrades.stream().filter(g->g.bucket().equals("SEMANTIC")).toList()));

        ParsedTaskResponse normalized=null;
        String javaError=null,javaMessage=null;
        Integer validationHttpStatus=null;
        boolean creatable=false;
        if (parsed!=null) {
            try {
                normalized=new LlmParsingService(new ReplayClient(parsed),fixed).parseTask(new LlmParseRequest(text(c.get("input"))));
                constructTask(normalized);creatable=true;
            } catch (ApiException error) {javaError=error.getExceptionCode().name();javaMessage=error.getExceptionCode().getMessage();validationHttpStatus=error.getExceptionCode().getStatus().value();}
            catch (RuntimeException error) {javaError="JAVA_EVALUATION_ERROR";}
        }
        Map<String,Object> actualJava=new LinkedHashMap<>();
        actualJava.put("normalized",normalized==null ? null : MAPPER.convertValue(normalized,Map.class));
        actualJava.put("taskCreatable",parsed==null ? null : creatable); actualJava.put("errorCode",javaError);actualJava.put("userMessage",javaMessage);
        actualJava.put("validationHttpStatus",validationHttpStatus);
        result.put("actualJava",actualJava);
        Map<String,Object> wantedJava=map(c.get("javaExpected"));
        List<GoldenScorer.Grade> javaGrades=wantedJava.get("normalized")==null ? new ArrayList<>() :
            scorer.compare(map(wantedJava.get("normalized")),normalized==null ? null : map(actualJava.get("normalized")),map(wantedJava.get("fieldRules")));
        String javaStatus="NOT_EVALUATED";
        if (parsed!=null) {
            boolean matches=Objects.equals(wantedJava.get("taskCreatable"),creatable) && Objects.equals(wantedJava.get("errorCode"),javaError);
            if (wantedJava.get("userMessage")!=null) matches &= Objects.equals(wantedJava.get("userMessage"),javaMessage);
            if (javaError!=null) matches &= Objects.equals(wantedJava.get("httpStatus"),validationHttpStatus);
            if (wantedJava.get("normalized")==null && normalized!=null) matches=false;
            javaStatus=!matches ? "FAIL" : javaGrades.isEmpty() ? "PASS" : scorer.verdict(javaGrades);
        }
        result.put("javaFields",javaGrades);result.put("javaStatus",javaStatus);
        result.put("criticalErrors",scorer.critical(c,rawGrades,creatable));
        result.put("manualReviewRequired",rawGrades.stream().anyMatch(g->g.status().equals("MANUAL")) || javaGrades.stream().anyMatch(g->g.status().equals("MANUAL")));
        result.put("rawErrorBlocked",(rawStatus.equals("FAIL") || rawStatus.equals("ERROR")) && validationHttpStatus!=null && validationHttpStatus>=400 && validationHttpStatus<500);
        return result;
    }

    private static void constructTask(ParsedTaskResponse p) {
        Task.create(new User("평가", "eval@example.com", "evaluation-only-not-login"),p.title(),p.category(),p.durationMinutes(),
            p.preferredDate(),p.preferredDayOfWeek(),p.preferredDateRange()==null?null:p.preferredDateRange().startDate(),
            p.preferredDateRange()==null?null:p.preferredDateRange().endDate(),p.preferredTimeRange(),p.deadline(),p.priority(),p.description(),
            p.dateRequired(),p.timeRequired(),p.preferredStartTime());
    }

    private static String codeCommit() {
        try {Process p=new ProcessBuilder("git","rev-parse","HEAD").start();return new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip();}
        catch(IOException e){return "unavailable";}
    }

    private static final class ReplayClient extends OpenAiClient {
        private final ParsedTaskResponse parsed;
        ReplayClient(ParsedTaskResponse parsed){this.parsed=parsed;}
        @Override public ParsedTaskResponse parseTask(String message,LocalDate reference){return parsed;}
    }

    private static final class Capture implements ClientHttpRequestInterceptor {
        Map<String,Object> request=Map.of(),usage=Map.of();
        String outputText,failure,responseModel,providerErrorCode,providerErrorType,incompleteReason;int status;
        @Override public ClientHttpResponse intercept(HttpRequest request,byte[] body,ClientHttpRequestExecution execution) throws IOException {
            this.request=read(new String(body,StandardCharsets.UTF_8));
            ClientHttpResponse response;
            try {response=execution.execute(request,body);}
            catch(IOException error){failure="TRANSPORT_ERROR";throw error;}
            status=response.getStatusCode().value();
            byte[] bytes;
            try { bytes=response.getBody().readAllBytes(); }
            catch (IOException failure) { this.failure="TRANSPORT_ERROR";response.close();throw failure; }
            if (status<200 || status>=300) {
                failure="HTTP_ERROR";
                try {inspectError(map(read(new String(bytes,StandardCharsets.UTF_8)).get("error")));}
                catch(RuntimeException ignored) { /* 오류 본문과 메시지는 기록하지 않는다. */ }
            }
            else {
                try {inspect(read(new String(bytes,StandardCharsets.UTF_8)));}
                catch(RuntimeException error){failure="INVALID_ENVELOPE";}
            }
            return new ClientHttpResponse() {
                public HttpStatusCode getStatusCode() throws IOException {return response.getStatusCode();}
                public String getStatusText() throws IOException {return response.getStatusText();}
                public HttpHeaders getHeaders(){return response.getHeaders();}
                public InputStream getBody(){return new ByteArrayInputStream(bytes);}
                public void close(){response.close();}
            };
        }

        private void inspect(Map<String,Object> envelope) {
            responseModel=text(envelope.get("model"));
            Map<String,Object> safeUsage=new LinkedHashMap<>();
            for(String key:List.of("input_tokens","output_tokens","total_tokens")) if(map(envelope.get("usage")).get(key) instanceof Number n)safeUsage.put(key,n);
            usage=safeUsage;
            String state=text(envelope.get("status"));
            if(!state.equals("completed")) failure=state.equals("incomplete")?"INCOMPLETE_RESPONSE":"UNEXPECTED_RESPONSE_STATUS";
            if(envelope.get("error")!=null) {failure="API_RESPONSE_ERROR";inspectError(map(envelope.get("error")));}
            incompleteReason=safeCode(map(envelope.get("incomplete_details")).get("reason"));
            for(Object output:list(envelope.get("output"))) for(Object content:list(map(output).get("content"))) {
                Map<String,Object> part=map(content);
                if(part.containsKey("refusal") || "refusal".equals(part.get("type"))) failure="MODEL_REFUSAL";
                if(outputText==null && part.get("text") instanceof String s && !s.isBlank()) outputText=s;
            }
        }

        private void inspectError(Map<String,Object> error) {
            providerErrorCode=safeCode(error.get("code"));
            providerErrorType=safeCode(error.get("type"));
        }

        private String safeCode(Object value) {
            if(value==null) return null;
            Set<String> allowed=Set.of("invalid_api_key","insufficient_quota","rate_limit_exceeded",
                "server_error","model_not_found","invalid_request_error","permission_denied",
                "invalid_json_schema","unsupported_parameter","context_length_exceeded",
                "max_output_tokens","content_filter","authentication_error","tokens","requests");
            return allowed.contains(text(value)) ? text(value) : "OTHER";
        }
    }
}
