package me.timetablescheduler.domain.llm.external;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiClientSchemaTest {

	@Test
	void Structured_Outputs_스키마는_의도보존_필드를_필수키와_허용타입에_포함한다() throws Exception {
		Method schemaMethod = OpenAiClient.class.getDeclaredMethod("createParsedTaskResponseSchema");
		schemaMethod.setAccessible(true);

		@SuppressWarnings("unchecked")
		Map<String, Object> schema = (Map<String, Object>) schemaMethod.invoke(new OpenAiClient());
		@SuppressWarnings("unchecked")
		Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
		@SuppressWarnings("unchecked")
		List<String> required = (List<String>) schema.get("required");

		assertFalse((Boolean) schema.get("additionalProperties"));
		assertTrue(required.containsAll(List.of(
			"dateRequired", "timeRequired", "preferredStartTime", "clarificationReason"
		)));
		assertEquals(properties.keySet(), Set.copyOf(required));

		@SuppressWarnings("unchecked")
		Map<String, Object> dateRequired = (Map<String, Object>) properties.get("dateRequired");
		assertEquals(List.of("boolean", "null"), dateRequired.get("type"));
	}
}
