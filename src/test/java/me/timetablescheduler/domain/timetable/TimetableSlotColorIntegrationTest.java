package me.timetablescheduler.domain.timetable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import me.timetablescheduler.auth.AuthService;
import me.timetablescheduler.auth.dto.AuthRequest;
import tools.jackson.databind.json.JsonMapper;
import jakarta.persistence.EntityManager;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TimetableSlotColorIntegrationTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	AuthService authService;
	@Autowired
	EntityManager entityManager;

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@ParameterizedTest
	@ValueSource(strings = {"BLUE", "MINT", "PEACH", "LAVENDER", "ROSE", "LEMON", "LIME", "AQUA", "APRICOT", "SLATE"})
	void 열_가지_색상을_생성_수정하고_단건_목록에서_재조회한다(String color) throws Exception {
		String token = 사용자_생성("color@example.test");
		long id = 생성(token, "MONDAY", "\"color\":\"" + color + "\",");
		mvc.perform(get("/timetable-slots/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk()).andExpect(jsonPath("$.color").value(color));
		String changed = color.equals("BLUE") ? "MINT" : "BLUE";
		수정(token, id, "\"color\":\"" + changed + "\",")
			.andExpect(status().isOk()).andExpect(jsonPath("$.color").value(changed));
		수정(token, id, "\"color\":\"" + color + "\",")
			.andExpect(status().isOk()).andExpect(jsonPath("$.color").value(color));
		mvc.perform(get("/timetable-slots").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk()).andExpect(jsonPath("$[0].color").value(color));
	}

	@Test
	void 생성_색상_생략과_null은_BLUE이며_수정_생략과_null은_저장값을_유지한다() throws Exception {
		String token = 사용자_생성("legacy@example.test");
		long omitted = 생성(token, "MONDAY", "");
		long explicitNull = 생성(token, "TUESDAY", "\"color\":null,");
		for (long id : new long[]{omitted, explicitNull}) {
			mvc.perform(get("/timetable-slots/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.color").value("BLUE"));
		}
		수정(token, omitted, "\"color\":\"ROSE\",").andExpect(status().isOk());
		수정(token, omitted, "").andExpect(status().isOk()).andExpect(jsonPath("$.color").value("ROSE"));
		수정(token, omitted, "\"color\":null,").andExpect(status().isOk()).andExpect(jsonPath("$.color").value("ROSE"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"\"PURPLE\"", "\"blue\"", "\"\"", "\" BLUE \"", "0", "1", "true", "{}", "[]"})
	void 잘못된_색상은_생성과_수정에서_400이며_기존_색상을_보존한다(String value) throws Exception {
		String token = 사용자_생성("invalid@example.test");
		long id = 생성(token, "MONDAY", "\"color\":\"AQUA\",");
		mvc.perform(post("/timetable-slots").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON).content(요청("TUESDAY", "\"color\":" + value + ",")))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		수정(token, id, "\"color\":" + value + ",").andExpect(status().isBadRequest());
		mvc.perform(get("/timetable-slots").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].color").value("AQUA"));
	}

	@Test
	void 같은_과목의_슬롯은_독립된_색상을_유지하며_타인_슬롯에_접근할_수_없다() throws Exception {
		String owner = 사용자_생성("owner@example.test");
		String other = 사용자_생성("other@example.test");
		long first = 생성(owner, "MONDAY", "\"color\":\"MINT\",");
		long second = 생성(owner, "TUESDAY", "\"color\":\"PEACH\",");
		수정(owner, first, "\"color\":\"SLATE\",").andExpect(status().isOk());
		mvc.perform(get("/timetable-slots/{id}", second).header(HttpHeaders.AUTHORIZATION, "Bearer " + owner))
			.andExpect(status().isOk()).andExpect(jsonPath("$.color").value("PEACH"));
		mvc.perform(get("/timetable-slots/{id}", first).header(HttpHeaders.AUTHORIZATION, "Bearer " + other))
			.andExpect(status().isNotFound());
		수정(other, first, "\"color\":\"BLUE\",").andExpect(status().isNotFound());
		mvc.perform(get("/timetable-slots").header(HttpHeaders.AUTHORIZATION, "Bearer " + other))
			.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		mvc.perform(delete("/timetable-slots/{id}", first).header(HttpHeaders.AUTHORIZATION, "Bearer " + other))
			.andExpect(status().isNotFound());
		mvc.perform(get("/timetable-slots/{id}", first).header(HttpHeaders.AUTHORIZATION, "Bearer " + owner))
			.andExpect(status().isOk()).andExpect(jsonPath("$.color").value("SLATE"));
	}

	@Test
	void 색상이_달라도_기존_수업_충돌_검증과_인증이_유지된다() throws Exception {
		String token = 사용자_생성("overlap@example.test");
		생성(token, "MONDAY", "\"color\":\"MINT\",");
		mvc.perform(post("/timetable-slots").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON).content(요청("MONDAY", "\"color\":\"ROSE\",")))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT_TIMETABLE_SLOT"));
		mvc.perform(post("/timetable-slots").contentType(MediaType.APPLICATION_JSON).content(요청("TUESDAY", "")))
			.andExpect(status().isUnauthorized());
	}

	private String 사용자_생성(String email) {
		return authService.register(new AuthRequest.Register("색상 검증", email, "password123")).accessToken();
	}

	private long 생성(String token, String day, String colorField) throws Exception {
		MvcResult result = mvc.perform(post("/timetable-slots").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON).content(요청(day, colorField)))
			.andExpect(status().isCreated()).andReturn();
		long id = JSON.readTree(result.getResponse().getContentAsString()).get("id").asLong();
		assertThat(id).isPositive();
		entityManager.flush();
		entityManager.clear();
		return id;
	}

	private org.springframework.test.web.servlet.ResultActions 수정(String token, long id, String colorField) throws Exception {
		var result = mvc.perform(put("/timetable-slots/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON).content(요청("MONDAY", colorField)));
		entityManager.flush();
		entityManager.clear();
		return result;
	}

	private String 요청(String day, String colorField) {
		return """
			{"subjectName":"같은 과목","dayOfWeek":"%s",%s
			 "location":"공학관","startTime":"09:00:00","endTime":"10:00:00"}
			""".formatted(day, colorField);
	}
}
