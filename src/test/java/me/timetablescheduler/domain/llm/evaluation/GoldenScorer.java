package me.timetablescheduler.domain.llm.evaluation;

import static me.timetablescheduler.domain.llm.evaluation.EvaluationSupport.*;
import java.text.Normalizer;
import java.time.LocalTime;
import java.util.*;

final class GoldenScorer {
    record Grade(String field, String status, String bucket, Object expected, Object actual, String reason) {}

    List<Grade> compare(Map<String, Object> expected, Map<String, Object> actual, Map<String, Object> rules) {
        List<Grade> grades = new ArrayList<>();
        for (var e : expected.entrySet()) {
            String field = e.getKey(); Object wanted = e.getValue();
            Map<String, Object> rule = map(rules.get(field));
            String bucket = text(rule.getOrDefault("scoreBucket", "SEMANTIC"));
            if (actual == null) { grades.add(new Grade(field,"NOT_EVALUATED",bucket,wanted,null,"NO_OUTPUT")); continue; }
            Object value = actual.get(field);
            String status, reason;
            if (!actual.containsKey(field)) { status="FAIL";reason="MISSING_FIELD"; }
            else if ("ONE_OF".equals(rule.get("mode"))) {
                status=list(rule.get("values")).stream().anyMatch(v -> equivalent(field,v,value)) ? "PASS" : "FAIL"; reason="ALLOWED_VALUES";
            } else if ("SEMANTIC".equals(rule.get("mode"))) {
                if (!(value instanceof String) || text(value).isBlank()) {status="FAIL";reason="EMPTY_TITLE";}
                else {
                    status=list(rule.get("acceptedExamples")).stream().anyMatch(v -> normalize(text(v)).equals(normalize(text(value)))) ? "PASS" : "MANUAL";
                    reason=status.equals("PASS") ? "KNOWN_ALIAS" : "TITLE_SEMANTIC_REVIEW";
                }
            } else if ("NON_BLANK_SEMANTIC".equals(rule.get("mode"))) {
                if (!(value instanceof String) || text(value).isBlank()) {status="FAIL";reason="MISSING_CLARIFICATION";}
                else {status=normalize(text(wanted)).equals(normalize(text(value))) ? "PASS" : "MANUAL";reason="CLARIFICATION_CONCEPT_REVIEW";}
            } else {status=equivalent(field,wanted,value) ? "PASS" : "FAIL";reason="EXACT";}
            if (!bucket.equals("SEMANTIC") && status.equals("PASS")) status="EXCLUDED";
            grades.add(new Grade(field,status,bucket,wanted,value,reason));
        }
        return grades;
    }

    String verdict(List<Grade> grades) {
        if (grades.stream().anyMatch(g -> g.status.equals("FAIL"))) return "FAIL";
        if (grades.stream().anyMatch(g -> g.status.equals("MANUAL"))) return "MANUAL";
        if (grades.stream().anyMatch(g -> g.status.equals("NOT_EVALUATED"))) return "NOT_EVALUATED";
        return "PASS";
    }

    List<String> critical(Map<String,Object> testCase, List<Grade> grades, boolean taskCreatable) {
        Map<String,Object> expected = map(map(testCase.get("llmExpected")).get("parsedTaskResponse"));
        List<String> issues = new ArrayList<>();
        for (Grade g : grades) if (g.status.equals("FAIL")) {
            boolean hard = (g.field.equals("dateRequired") || g.field.equals("timeRequired")) && Boolean.TRUE.equals(g.expected);
            boolean exact = g.field.equals("preferredStartTime") && (g.expected != null || expected.get("clarificationReason") != null);
            boolean bound = (g.field.equals("preferredDateRange") || g.field.equals("deadline")) && g.expected != null;
            boolean date = (g.field.equals("preferredDate") || g.field.equals("preferredDayOfWeek")) && g.expected != null && Boolean.TRUE.equals(expected.get("dateRequired"));
            boolean missing = g.field.equals("title") && g.expected == null;
            boolean relative = g.field.equals("relativeStartMode") || g.field.equals("startOffsetMinutes");
            if (hard || exact || bound || date || missing || relative || g.field.equals("clarificationReason") || testCase.get("id").equals("G28") && g.field.equals("durationMinutes")) issues.add(g.field+":"+g.reason);
        }
        if (!Boolean.TRUE.equals(map(testCase.get("javaExpected")).get("taskCreatable")) && taskCreatable) issues.add("UNSAFE_TASK_ACCEPTED");
        return issues;
    }

    static boolean equivalent(String field, Object expected, Object actual) {
        if (Objects.equals(expected,actual)) return true;
        if (expected == null || actual == null) return false;
        if (field.equals("preferredStartTime") && expected instanceof String && actual instanceof String) {
            try { return LocalTime.parse(text(expected)).equals(LocalTime.parse(text(actual))); }
            catch (RuntimeException e) { return false; }
        }
        if (expected instanceof Number a && actual instanceof Number b) return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString()))==0;
        return false;
    }

    private static String normalize(String s) { return Normalizer.normalize(s,Normalizer.Form.NFKC).strip().replaceAll("\\s+", ""); }
}
