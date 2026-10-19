package me.timetablescheduler.domain.llm.evaluation;

import static me.timetablescheduler.domain.llm.evaluation.EvaluationSupport.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

final class EvaluationReport {
    final Path directory;
    private final String secret;
    EvaluationReport(Path root,String secret) throws IOException {
        this.secret=secret;
        Files.createDirectories(root);
        directory=Files.createDirectory(root.resolve(ZonedDateTime.now(LlmEvaluationRunner.SEOUL)
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))+"-"+UUID.randomUUID().toString().substring(0,8)));
    }
    void writeJson(String name,Object value) throws IOException {
        Files.writeString(directory.resolve(name),MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(clean(value))+"\n",StandardCharsets.UTF_8);
    }
    void appendCheckpoint(Object value) throws IOException {
        Files.writeString(directory.resolve("cases.jsonl"),MAPPER.writeValueAsString(clean(value))+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    void finish(Map<String,Object> manifest,List<Map<String,Object>> results) throws IOException {
        writeJson("manifest.json",manifest);writeJson("cases.json",results);
        Map<String,Object> summary=summary(results);
        writeJson("summary.json",summary);
        List<List<Object>> metrics=new ArrayList<>();
        metrics.add(List.of("metric","value"));
        flattenMetrics("",summary,metrics);
        csv("summary.csv",metrics);
        List<List<Object>> cases=new ArrayList<>(),fields=new ArrayList<>(),manual=new ArrayList<>();
        cases.add(List.of("id","repeat","type","input","reference","model","responseModel","promptSha256","schemaSha256",
            "rawStatus","rawSemanticStatus","javaStatus","schemaStatus","dtoStatus","httpStatus","failure",
            "providerErrorCode","providerErrorType","incompleteReason","criticalErrors","expectedRaw","actualRaw","expectedJava","actualJava"));
        fields.add(List.of("id","repeat","stage","field","status","bucket","expected","actual","reason"));
        manual.add(fields.getFirst());
        for(Map<String,Object> r:results) {
            List<Object> row=new ArrayList<>();
            for(Object key:cases.getFirst()) row.add(r.get(text(key)));
            cases.add(row);
            for(String stage:List.of("raw","java")) for(Object item:list(MAPPER.convertValue(r.getOrDefault(stage+"Fields",List.of()),Object.class))) {
                Map<String,Object> g=map(item);List<Object> detail=Arrays.asList(r.get("id"),r.get("repeat"),stage,g.get("field"),g.get("status"),g.get("bucket"),g.get("expected"),g.get("actual"),g.get("reason"));
                fields.add(detail);if("MANUAL".equals(g.get("status"))) manual.add(detail);
            }
        }
        csv("cases.csv",cases);csv("fields.csv",fields);csv("manual-review.csv",manual);
    }

    private void flattenMetrics(String prefix,Map<String,Object> values,List<List<Object>> rows) {
        values.forEach((key,value)->{
            String path=prefix.isEmpty()?key:prefix+"."+key;
            if(value instanceof Map<?,?>) flattenMetrics(path,map(value),rows);
            else rows.add(Arrays.asList(path,value));
        });
    }

    static Map<String,Object> summary(List<Map<String,Object>> results) {
        Map<String,Object> summary=new LinkedHashMap<>();
        summary.put("attempts",results.size());
        for(String metric:List.of("rawStatus","rawSemanticStatus","javaStatus","schemaStatus","dtoStatus")) summary.put(metric,counts(results.stream().map(r->text(r.get(metric))).toList()));
        Map<String,Object> types=new LinkedHashMap<>();
        for(String type:List.of("NORMAL","BOUNDARY","AMBIGUOUS","UNSUPPORTED")) {
            List<Map<String,Object>> group=results.stream().filter(r->type.equals(r.get("type"))).toList();
            types.put(type,Map.of("raw",counts(group.stream().map(r->text(r.get("rawStatus"))).toList()),
                "rawSemantic",counts(group.stream().map(r->text(r.get("rawSemanticStatus"))).toList()),
                "java",counts(group.stream().map(r->text(r.get("javaStatus"))).toList())));
        }
        summary.put("byType",types);
        Map<String,List<String>> fieldStatuses=new TreeMap<>(), contractStatuses=new TreeMap<>();
        for(Map<String,Object> r:results) for(String stage:List.of("raw","java")) {
            Object converted=MAPPER.convertValue(r.getOrDefault(stage+"Fields",List.of()),Object.class);
            for(Object item:list(converted)) {
                Map<String,Object> g=map(item);
                String key=stage+"."+g.get("field"),status=text(g.get("status"));
                fieldStatuses.computeIfAbsent(key,k->new ArrayList<>()).add("SEMANTIC".equals(g.get("bucket"))?status:"EXCLUDED");
                if(!"SEMANTIC".equals(g.get("bucket")))
                    contractStatuses.computeIfAbsent(key,k->new ArrayList<>()).add(status.equals("EXCLUDED")?"PASS":status);
            }
        }
        Map<String,Object> byField=new TreeMap<>();fieldStatuses.forEach((key,value)->byField.put(key,counts(value)));
        summary.put("byField",byField);
        Map<String,Object> byContractField=new TreeMap<>();
        contractStatuses.forEach((key,value)->byContractField.put(key,counts(value)));
        summary.put("byContractField",byContractField);
        for (String metric : List.of("taskCreation", "clarification")) {
            List<String> statuses = new ArrayList<>();
            for (Map<String,Object> r : results) {
                Map<String,Object> expected=map(r.get("expectedJava")), actual=map(r.get("actualJava"));
                if (actual.get("taskCreatable")==null) {statuses.add("NOT_EVALUATED");continue;}
                boolean matches = metric.equals("taskCreation")
                    ? Objects.equals(expected.get("taskCreatable"),actual.get("taskCreatable"))
                    : Objects.equals(expected.get("errorCode"),actual.get("errorCode"));
                statuses.add(matches ? "PASS" : "FAIL");
            }
            summary.put(metric,counts(statuses));
        }
        summary.put("criticalErrorAttempts",results.stream().filter(r->!list(r.get("criticalErrors")).isEmpty()).count());
        summary.put("manualReviewAttempts",results.stream().filter(r->Boolean.TRUE.equals(r.get("manualReviewRequired"))).count());
        summary.put("rawErrorBlockedAttempts",results.stream().filter(r->Boolean.TRUE.equals(r.get("rawErrorBlocked"))).count());
        summary.put("rateDefinition","accuracy=PASS/(all attempts excluding only EXCLUDED). MANUAL, ERROR and NOT_EVALUATED never count as correct. coverage=(PASS+FAIL)/denominator.");
        return summary;
    }

    private static Map<String,Object> counts(List<String> statuses) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String status:List.of("PASS","FAIL","MANUAL","ERROR","NOT_EVALUATED","EXCLUDED")) result.put(status,statuses.stream().filter(status::equals).count());
        long denominator=statuses.stream().filter(s->!s.equals("EXCLUDED")).count();
        long pass=statuses.stream().filter("PASS"::equals).count(),fail=statuses.stream().filter("FAIL"::equals).count();
        result.put("denominator",denominator);result.put("accuracy",denominator==0?null:(double)pass/denominator);
        result.put("automaticCoverage",denominator==0?null:(double)(pass+fail)/denominator);
        return result;
    }

    private Object clean(Object value) {
        Object converted=MAPPER.convertValue(value,Object.class);
        return scrub(converted);
    }
    private Object scrub(Object value) {
        if(value instanceof String s) {
            if(secret!=null && !secret.isBlank())s=s.replace(secret,"[REDACTED]");
            return s.replaceAll("(?i)Bearer\\s+[^\\s\"']+","Bearer [REDACTED]")
                .replaceAll("sk-[A-Za-z0-9_-]{8,}","[REDACTED]");
        }
        if(value instanceof Map<?,?> m){Map<String,Object> result=new LinkedHashMap<>();m.forEach((k,v)->result.put(text(scrub(text(k))),scrub(v)));return result;}
        if(value instanceof List<?> l)return l.stream().map(this::scrub).toList();
        return value;
    }
    private void csv(String name,List<List<Object>> rows) throws IOException {
        StringBuilder csv=new StringBuilder();
        for(List<Object> row:rows){
            List<String> cells=new ArrayList<>();
            for(Object v:row){Object safe=clean(v);String s=safe==null?"":safe instanceof String ? text(safe):MAPPER.writeValueAsString(safe);
                if(!s.stripLeading().isEmpty() && "=+@-".indexOf(s.stripLeading().charAt(0))>=0)s="'"+s;
                cells.add("\""+s.replace("\"","\"\"")+"\"");}
            csv.append(String.join(",",cells)).append("\r\n");
        }
        Files.writeString(directory.resolve(name),csv,StandardCharsets.UTF_8);
    }
}
