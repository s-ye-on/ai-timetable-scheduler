package me.timetablescheduler.domain.llm.evaluation;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import me.timetablescheduler.domain.llm.config.OpenAiProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Tag("llm-live")
class LlmLiveEvaluationTest {
    @Test
    void 명시적으로_승인한_실제_LLM_평가만_실행한다() throws Exception {
        int repeats = validatePermission(System.getProperties(), System.getenv());
        OpenAiProperties properties = configuration(System.getenv("OPENAI_API_KEY"));
        var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(60));
        Path result = new LlmEvaluationRunner(properties, RestClient.builder().requestFactory(factory))
            .run(LlmEvaluationRunner.dataset(), repeats,
                Path.of(System.getProperty("llm.eval.output", "build/llm-evaluation")), "LIVE");
        System.out.println("평가 결과: " + result.toAbsolutePath());
    }

    static int validatePermission(Properties options, Map<String,String> environment) {
        if (!"live".equals(options.getProperty("llm.eval.task"))
            || !"true".equals(options.getProperty("llm.eval.enabled"))
            || !"true".equals(environment.get("LLM_EVAL_ALLOW_API"))
            || environment.getOrDefault("OPENAI_API_KEY", "").isBlank()) {
            throw new IllegalStateException("실제 API 평가의 명시적 실행 승인이 필요합니다.");
        }
        String repeats = options.getProperty("llm.eval.repeats", "1");
        if (!repeats.equals("1") && !repeats.equals("3"))
            throw new IllegalArgumentException("반복 횟수는 1 또는 3이어야 합니다.");
        return Integer.parseInt(repeats);
    }

    private static OpenAiProperties configuration(String apiKey) {
        return configuration(apiKey, new StandardEnvironment());
    }

    static OpenAiProperties configuration(String apiKey, StandardEnvironment environment) {
        if (!environment.containsProperty("spring.config.location")) {
            environment.getPropertySources().addLast(new MapPropertySource("evaluationMainConfig", Map.of(
                "spring.config.location", "optional:file:./src/main/resources/application.properties,optional:file:./src/main/resources/application.yaml")));
        }
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
        OpenAiProperties configured = Binder.get(environment).bind("openai", OpenAiProperties.class)
            .orElseThrow(() -> new IllegalStateException("서비스 OpenAI 설정을 읽을 수 없습니다."));
        if (configured.model() == null || configured.model().isBlank())
            throw new IllegalStateException("서비스 OpenAI 모델 설정이 필요합니다.");
        URI endpoint = URI.create(configured.baseUrl());
        if (!"https".equals(endpoint.getScheme()) || !"api.openai.com".equals(endpoint.getHost())
            || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
            || endpoint.getPort() != -1 || !java.util.List.of("", "/", "/v1", "/v1/").contains(endpoint.getPath()))
            throw new IllegalStateException("실제 평가는 공식 OpenAI HTTPS 주소만 허용합니다.");
        return new OpenAiProperties(apiKey, configured.model(), configured.baseUrl());
    }
}
