const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const demoPath = process.env.DEMO_HTML_PATH
	|| path.resolve(__dirname, "../../main/resources/static/demo.html");
const html = fs.readFileSync(demoPath, "utf8");
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)];
assert.ok(scripts.length > 0, "demo.html inline JavaScript를 찾을 수 있어야 합니다.");
const demoScript = scripts.at(-1)[1];

function jsonResponse(status, body) {
	return {
		status,
		ok: status >= 200 && status < 300,
		text: async () => body === undefined ? "" : JSON.stringify(body)
	};
}

function emptyResponse(status) {
	return {
		status,
		ok: status >= 200 && status < 300,
		text: async () => ""
	};
}

function deferred() {
	let resolve;
	let reject;
	const promise = new Promise((resolvePromise, rejectPromise) => {
		resolve = resolvePromise;
		reject = rejectPromise;
	});
	return { promise, resolve, reject };
}

// vm 컨텍스트에서 생성된 객체는 Node 테스트 러너의 객체와 prototype이 다르다.
// 실제 JSON API 결과와 동일한 값인지 비교하기 위해 JSON 경계로 정규화한다.
function asPlainJson(value) {
	return JSON.parse(JSON.stringify(value));
}

async function waitFor(predicate, description) {
	for (let attempt = 0; attempt < 20; attempt += 1) {
		if (predicate()) {
			return;
		}
		await new Promise((resolve) => setImmediate(resolve));
	}
	assert.fail(description);
}

class FakeClassList {
	constructor(element) {
		this.element = element;
	}

	toggle(name, force) {
		const names = new Set(this.element.className.split(/\s+/).filter(Boolean));
		const enabled = force === undefined ? !names.has(name) : force;
		if (enabled) {
			names.add(name);
		} else {
			names.delete(name);
		}
		this.element.className = [...names].join(" ");
		return enabled;
	}

	contains(name) {
		return this.element.className.split(/\s+/).includes(name);
	}
}

class FakeElement {
	constructor(id) {
		this.id = id;
		this.value = "";
		this.textContent = "";
		this.innerHTML = "";
		this.className = "";
		this.disabled = false;
		this.dataset = {};
		this.listeners = new Map();
		this.classList = new FakeClassList(this);
	}

	addEventListener(name, callback) {
		const callbacks = this.listeners.get(name) || [];
		callbacks.push(callback);
		this.listeners.set(name, callbacks);
	}

	dispatch(name) {
		for (const callback of this.listeners.get(name) || []) {
			callback({ preventDefault() {} });
		}
	}
}

function createHarness({
	accessToken = "access-old",
	refreshToken = "refresh-old",
	userName = "테스트 사용자",
	fetchImpl
} = {}) {
	const elements = new Map();
	const storage = new Map([
		["demoAccessToken", accessToken],
		["demoRefreshToken", refreshToken],
		["demoUserName", userName]
	].filter(([, value]) => value !== null));
	const documentListeners = new Map();
	const dynamicElements = new Map();
	const document = {
		getElementById(id) {
			if (!elements.has(id)) {
				elements.set(id, new FakeElement(id));
			}
			return elements.get(id);
		},
		addEventListener(name, callback) {
			documentListeners.set(name, callback);
		},
		querySelectorAll(selector) {
			if (selector === "[data-select-recommendation]") {
				const markup = document.getElementById("recommendationList").innerHTML;
				return [...markup.matchAll(/data-select-recommendation="([^"]+)"/g)].map((match) => {
					const key = `recommendation:${match[1]}`;
					if (!dynamicElements.has(key)) {
						const element = new FakeElement(key);
						element.dataset.selectRecommendation = match[1];
						dynamicElements.set(key, element);
					}
					return dynamicElements.get(key);
				});
			}
			if (selector === "[data-refresh-recommendation-state]") {
				const markup = document.getElementById("selectionResult").innerHTML;
				if (!markup.includes("data-refresh-recommendation-state")) {
					return [];
				}
				const key = "refresh-recommendation-state";
				if (!dynamicElements.has(key)) {
					dynamicElements.set(key, new FakeElement(key));
				}
				return [dynamicElements.get(key)];
			}
			return [];
		}
	};
	const localStorage = {
		getItem: (key) => storage.get(key) ?? null,
		setItem: (key, value) => storage.set(key, String(value)),
		removeItem: (key) => storage.delete(key)
	};
	const context = vm.createContext({
		alert() {},
		console,
		document,
		fetch: fetchImpl || (async () => {
			throw new Error("테스트가 예상하지 않은 네트워크 요청입니다.");
		}),
		localStorage,
		location: { reload() {} },
		window: { open() {} },
		setTimeout,
		clearTimeout
	});
	vm.runInContext(demoScript, context, { filename: demoPath });
	return {
		context,
		element: (id) => document.getElementById(id),
		state: () => vm.runInContext("state", context),
		call: (expression) => vm.runInContext(expression, context),
		recommendationButton: (id) => dynamicElements.get(`recommendation:${id}`),
		storage
	};
}

function task(id, title = "과제") {
	return {
		id,
		title,
		category: "STUDY",
		durationMinutes: 60,
		preferredDayOfWeek: "TUESDAY",
		preferredTimeRange: "LUNCH",
		status: "UNSCHEDULED"
	};
}

function recommendation(id) {
	return {
		id,
		rank: 1,
		taskId: 42,
		status: "PROPOSED",
		score: 120,
		recommendedStartAt: "2026-10-20T12:00:00",
		recommendedEndAt: "2026-10-20T13:00:00",
		reason: "기존 일정과 적당한 간격",
		syncRetryable: false
	};
}

test("401 응답은 refresh token으로 한 번 복구하고 원래 요청을 새 access token으로 재시도한다", async () => {
	const calls = [];
	const harness = createHarness({
		fetchImpl: async (url, options = {}) => {
			calls.push({ url, options });
			if (url === "/protected" && calls.filter((call) => call.url === "/protected").length === 1) {
				return jsonResponse(401, { message: "만료됨" });
			}
			if (url === "/api/auth/refresh") {
				return jsonResponse(200, {
					accessToken: "access-new",
					refreshToken: "refresh-new",
					name: "갱신 사용자"
				});
			}
			return jsonResponse(200, { result: "ok" });
		}
	});

	const result = await harness.call("request('/protected')");

	assert.deepEqual(asPlainJson(result), { result: "ok" });
	assert.deepEqual(calls.map((call) => call.url), ["/protected", "/api/auth/refresh", "/protected"]);
	assert.deepEqual(JSON.parse(calls[1].options.body), { refreshToken: "refresh-old" });
	assert.equal(calls[2].options.headers.Authorization, "Bearer access-new");
	assert.equal(harness.storage.get("demoAccessToken"), "access-new");
	assert.equal(harness.storage.get("demoRefreshToken"), "refresh-new");
});

test("401 복구가 실패하면 토큰과 이전 Task·추천을 지우고 재로그인을 안내한다", async () => {
	let callCount = 0;
	const harness = createHarness({
		fetchImpl: async (url) => {
			callCount += 1;
			return url === "/api/auth/refresh" ? emptyResponse(401) : emptyResponse(401);
		}
	});
	harness.element("naturalMessage").value = "새 입력은 유지되어야 합니다.";
	harness.call("state.currentTask = ({ id: 7, title: '이전 Task' })");
	harness.call("state.recommendations = [{ id: 8 }]");
	harness.call("state.selection = { ok: true, body: {} }");

	await assert.rejects(harness.call("request('/protected')"));

	assert.equal(callCount, 2);
	assert.equal(harness.state().accessToken, null);
	assert.equal(harness.state().refreshToken, null);
	assert.equal(harness.state().currentTask, null);
	assert.deepEqual(asPlainJson(harness.state().recommendations), []);
	assert.equal(harness.state().selection, null);
	assert.equal(harness.storage.has("demoAccessToken"), false);
	assert.equal(harness.storage.has("demoRefreshToken"), false);
	assert.equal(harness.element("naturalMessage").value, "새 입력은 유지되어야 합니다.");
	assert.equal(harness.element("authCard").classList.contains("is-hidden"), false);
	assert.equal(harness.element("logoutButton").classList.contains("is-hidden"), true);
	assert.match(harness.element("authMessage").textContent, /로그인/);
});

test("동시에 발생한 401은 refresh 요청 하나만 공유하고, 재시도 401은 무한 갱신하지 않는다", async () => {
	const refreshGate = deferred();
	const calls = [];
	const harness = createHarness({
		fetchImpl: async (url) => {
			calls.push(url);
			if (url === "/api/auth/refresh") {
				return refreshGate.promise;
			}
			const targetCalls = calls.filter((pathName) => pathName === url).length;
			return targetCalls === 1 ? jsonResponse(401, { message: "expired" }) : jsonResponse(200, { url });
		}
	});

	const first = harness.call("request('/one')");
	const second = harness.call("request('/two')");
	// RED 실행에서는 아직 refresh가 없을 수 있으므로, 미처리 rejection을 먼저 소비한다.
	first.catch(() => {});
	second.catch(() => {});
	try {
		await waitFor(
			() => calls.filter((url) => url === "/api/auth/refresh").length === 1,
			"동시 401은 refresh 요청 하나를 시작해야 합니다."
		);
	} finally {
		refreshGate.resolve(jsonResponse(200, { accessToken: "access-new", refreshToken: "refresh-new", name: "사용자" }));
	}

	assert.deepEqual(asPlainJson(await first), { url: "/one" });
	assert.deepEqual(asPlainJson(await second), { url: "/two" });
	assert.equal(calls.filter((url) => url === "/api/auth/refresh").length, 1);

	const retryCalls = [];
	const retryHarness = createHarness({
		fetchImpl: async (url) => {
			retryCalls.push(url);
			return url === "/api/auth/refresh"
				? jsonResponse(200, { accessToken: "access-new", refreshToken: "refresh-new", name: "사용자" })
				: jsonResponse(401, { message: "still expired" });
		}
	});
	await assert.rejects(retryHarness.call("request('/protected')"));
	assert.deepEqual(retryCalls, ["/protected", "/api/auth/refresh", "/protected"]);
});

test("로그인 실패와 403·500 응답은 refresh 또는 로그아웃으로 처리하지 않는다", async () => {
	for (const status of [401, 403, 500]) {
		const calls = [];
		const harness = createHarness({
			fetchImpl: async (url) => {
				calls.push(url);
				return jsonResponse(status, { message: "실패" });
			}
		});
		if (status === 401) {
			await harness.call("login()");
		} else {
			await assert.rejects(harness.call(`request('/protected-${status}')`));
		}
		assert.deepEqual(calls.filter((url) => url === "/api/auth/refresh"), []);
		assert.equal(harness.state().accessToken, "access-old");
	}
});

test("refresh를 허용하지 않은 401은 남은 인증 상태와 이전 분석 결과를 정리한다", async () => {
	const calls = [];
	const harness = createHarness({
		fetchImpl: async (url) => {
			calls.push(url);
			return emptyResponse(401);
		}
	});
	harness.call("state.currentTask = ({ id: 12, title: '이전 분석' })");
	harness.call("state.recommendations = [{ id: 13 }]");

	await assert.rejects(harness.call("request('/protected', {}, false)"));

	assert.deepEqual(calls, ["/protected"]);
	assert.equal(harness.state().accessToken, null);
	assert.equal(harness.state().currentTask, null);
	assert.deepEqual(asPlainJson(harness.state().recommendations), []);
	assert.match(harness.element("authMessage").textContent, /로그인/);

	const tokenlessCalls = [];
	const tokenlessHarness = createHarness({
		refreshToken: null,
		fetchImpl: async (url) => {
			tokenlessCalls.push(url);
			return emptyResponse(401);
		}
	});
	tokenlessHarness.call("state.currentTask = ({ id: 14, title: '갱신 토큰 없는 이전 분석' })");
	await assert.rejects(tokenlessHarness.call("request('/protected')"));
	assert.deepEqual(tokenlessCalls, ["/protected"]);
	assert.equal(tokenlessHarness.state().accessToken, null);
	assert.equal(tokenlessHarness.state().currentTask, null);
	assert.equal(tokenlessHarness.storage.has("demoAccessToken"), false);
});

test("갱신 중 로그아웃하거나 새 로그인해도 늦은 refresh 응답이 현재 인증 상태를 덮어쓰지 않는다", async () => {
	for (const action of ["logout", "new-login"]) {
		const refreshGate = deferred();
		const harness = createHarness({
			fetchImpl: async (url) => url === "/api/auth/refresh"
				? refreshGate.promise
				: jsonResponse(401, { message: "expired" })
		});
		const requestPromise = harness.call("request('/protected')");
		requestPromise.catch(() => {});
		await waitFor(
			() => harness.call("refreshPromise !== null"),
			"401 뒤 refresh 요청이 진행되어야 합니다."
		);
		if (action === "logout") {
			harness.call("logout()");
		} else {
			harness.call("saveAuth({ accessToken: 'new-access', refreshToken: 'new-refresh' })");
		}
		refreshGate.resolve(jsonResponse(200, {
			accessToken: "late-access",
			refreshToken: "late-refresh",
			name: "늦은 응답"
		}));
		await assert.rejects(requestPromise);

		if (action === "logout") {
			assert.equal(harness.state().accessToken, null);
			assert.equal(harness.storage.has("demoAccessToken"), false);
		} else {
			assert.equal(harness.state().accessToken, "new-access");
			assert.equal(harness.state().refreshToken, "new-refresh");
			assert.equal(harness.storage.get("demoAccessToken"), "new-access");
		}
	}
});

test("새 자연어 분석이 실패하면 이전 Task와 추천 결과를 즉시 폐기한다", async () => {
	const responses = [
		jsonResponse(200, task(1, "첫 번째 요청")),
		jsonResponse(500, { message: "LLM 분석 실패" })
	];
	const harness = createHarness({ fetchImpl: async () => responses.shift() });
	harness.element("naturalMessage").value = "첫 번째 요청";
	await harness.call("parseNaturalLanguage()");
	harness.call("state.recommendations = [{ id: 10 }]");
	harness.call("state.selection = { ok: true, body: {} }");
	harness.element("naturalMessage").value = "두 번째 요청";

	await harness.call("parseNaturalLanguage()");

	assert.equal(harness.state().currentTask, null);
	assert.deepEqual(asPlainJson(harness.state().recommendations), []);
	assert.equal(harness.state().selection, null);
	assert.match(harness.element("parsedResult").innerHTML, /아직 분석 결과가 없습니다/);
	assert.match(harness.element("recommendationList").innerHTML, /아직 추천 후보가 없습니다/);
	assert.equal(harness.element("recommendationCount").textContent, "0개");
	assert.match(harness.element("taskMessage").textContent, /LLM 분석 실패/);
});

test("자연어 입력을 수정하면 기존 Task를 즉시 무효화하고 추천·선택 API 호출을 막는다", async () => {
	const calls = [];
	const harness = createHarness({ fetchImpl: async (url) => {
		calls.push(url);
		return jsonResponse(200, {});
	} });
	harness.call("bindEvents()");
	harness.call("state.currentTask = ({ id: 3, title: '이전 분석' })");
	harness.call("state.recommendations = [{ id: 4 }]");
	harness.element("naturalMessage").value = "변경된 요청";
	harness.element("naturalMessage").dispatch("input");

	assert.equal(harness.state().currentTask, null);
	assert.deepEqual(asPlainJson(harness.state().recommendations), []);
	await harness.call("generateRecommendations()");
	await harness.call("selectRecommendation(4)");
	assert.deepEqual(calls, []);
	assert.match(harness.element("taskMessage").textContent, /먼저 AI로 일정을 분석/);
});

test("입력 변경 뒤 늦게 도착한 분석·추천 응답은 현재 화면을 덮어쓰지 않는다", async () => {
	const parseGate = deferred();
	const harness = createHarness({ fetchImpl: async () => parseGate.promise });
	harness.call("bindEvents()");
	harness.element("naturalMessage").value = "A 요청";
	const parsePromise = harness.call("parseNaturalLanguage()");
	harness.element("naturalMessage").value = "B 요청";
	harness.element("naturalMessage").dispatch("input");
	parseGate.resolve(jsonResponse(200, task(1, "A 요청")));
	await parsePromise;
	assert.equal(harness.state().currentTask, null);
	assert.deepEqual(asPlainJson(harness.state().recommendations), []);

	const recommendationGate = deferred();
	const recommendationHarness = createHarness({
		fetchImpl: async (url) => url === "/tasks/natural-language"
			? jsonResponse(200, task(2, "A 요청"))
			: recommendationGate.promise
	});
	recommendationHarness.call("bindEvents()");
	recommendationHarness.element("naturalMessage").value = "A 요청";
	await recommendationHarness.call("parseNaturalLanguage()");
	const recommendPromise = recommendationHarness.call("generateRecommendations()");
	recommendationHarness.element("naturalMessage").value = "B 요청";
	recommendationHarness.element("naturalMessage").dispatch("input");
	recommendationGate.resolve(jsonResponse(200, { recommendations: [recommendation(5)] }));
	await recommendPromise;
	assert.equal(recommendationHarness.state().currentTask, null);
	assert.deepEqual(asPlainJson(recommendationHarness.state().recommendations), []);
	assert.match(recommendationHarness.element("recommendationList").innerHTML, /아직 추천 후보가 없습니다/);
});

test("401 복구 중 자연어 입력이 바뀌면 이전 Task의 추천 요청을 재시도하지 않는다", async () => {
	const refreshGate = deferred();
	const calls = [];
	const harness = createHarness({
		fetchImpl: async (url) => {
			calls.push(url);
			if (url === "/tasks/natural-language") {
				return jsonResponse(200, task(9, "A 요청"));
			}
			if (url === "/api/auth/refresh") {
				return refreshGate.promise;
			}
			return jsonResponse(401, { message: "expired" });
		}
	});
	harness.call("bindEvents()");
	harness.element("naturalMessage").value = "A 요청";
	await harness.call("parseNaturalLanguage()");
	const recommendationPromise = harness.call("generateRecommendations()");
	await waitFor(
		() => calls.filter((url) => url === "/api/auth/refresh").length === 1,
		"추천 요청의 401은 refresh 요청을 시작해야 합니다."
	);
	assert.equal(calls.filter((url) => url === "/tasks/9/recommendations").length, 1);

	harness.element("naturalMessage").value = "B 요청";
	harness.element("naturalMessage").dispatch("input");
	refreshGate.resolve(jsonResponse(200, { accessToken: "access-new", refreshToken: "refresh-new", name: "사용자" }));
	await recommendationPromise;

	assert.equal(calls.filter((url) => url === "/tasks/9/recommendations").length, 1);
	assert.equal(harness.state().currentTask, null);
	assert.deepEqual(asPlainJson(harness.state().recommendations), []);
});

test("정상 분석·추천·선택 흐름은 올바른 Task와 후보 endpoint를 호출하고 선택 결과를 표시한다", async () => {
	const calls = [];
	const harness = createHarness({
		fetchImpl: async (url, options = {}) => {
			calls.push({ url, options });
			if (url === "/tasks/natural-language") {
				return jsonResponse(200, task(42, "정상 요청"));
			}
			if (url === "/tasks/42/recommendations") {
				return jsonResponse(200, { recommendations: [recommendation(77)] });
			}
			if (url === "/recommendations/77/select") {
				return jsonResponse(200, {
					status: "SYNCED",
					recommendedStartAt: "2026-10-20T12:00:00",
					recommendedEndAt: "2026-10-20T13:00:00"
				});
			}
			if (url === "/tasks/42") return jsonResponse(200, { ...task(42, "정상 요청"), status: "SCHEDULED" });
			if (url === "/tasks/42/recommendations?includeHistory=true") return jsonResponse(200, [{ ...recommendation(77), status: "SYNCED" }]);
			throw new Error(`예상하지 않은 요청: ${url}`);
		}
	});
	harness.call("bindEvents()");
	harness.element("naturalMessage").value = "정상 요청";
	harness.call("state.calendarConnected = true");
	await harness.call("parseNaturalLanguage()");
	await harness.call("generateRecommendations()");
	assert.equal(harness.element("flowBadge").textContent, "RECOMMENDED");
	assert.match(harness.element("recommendationList").innerHTML, /추천 1순위/);
	await harness.call("selectRecommendation(77)");

	assert.deepEqual(calls.map((call) => call.url), [
		"/tasks/natural-language",
		"/tasks/42/recommendations",
		"/recommendations/77/select",
		"/tasks/42",
		"/tasks/42/recommendations?includeHistory=true"
	]);
	assert.deepEqual(JSON.parse(calls[0].options.body), { message: "정상 요청" });
	assert.equal(harness.state().currentTask.id, 42);
	assert.equal(harness.state().recommendations[0].id, 77);
	assert.equal(harness.state().selection.body.status, "SYNCED");
	assert.equal(harness.state().isParsing, false);
	assert.equal(harness.state().isRecommending, false);
	assert.equal(harness.state().isSelecting, false);
	assert.equal(harness.element("naturalMessage").disabled, false);
	assert.match(harness.element("selectionResult").innerHTML, /Google Calendar 등록 완료/);
});

test("새로고침 후 기존 Task와 추천 이력을 서버에서 복원하고, 확정 후보의 중복 선택을 막는다", async () => {
	const calls = [];
	const scheduledTask = { ...task(42, "복원할 일정"), status: "SCHEDULED" };
	const syncedRecommendation = { ...recommendation(77), status: "SYNCED" };
	const harness = createHarness({
		fetchImpl: async (url) => {
			calls.push(url);
			if (url === "/tasks") return jsonResponse(200, [scheduledTask]);
			if (url === "/tasks/42") return jsonResponse(200, scheduledTask);
			if (url === "/tasks/42/recommendations?includeHistory=true") return jsonResponse(200, [syncedRecommendation]);
			throw new Error(`예상하지 않은 요청: ${url}`);
		}
	});

	await harness.call("loadExistingTasks()");
	await harness.call("restoreTask(42)");
	await harness.call("selectRecommendation(77)");

	assert.deepEqual(calls, [
		"/tasks",
		"/tasks/42",
		"/tasks/42/recommendations?includeHistory=true"
	]);
	assert.equal(harness.state().currentTask.status, "SCHEDULED");
	assert.equal(harness.state().recommendations[0].status, "SYNCED");
	assert.match(harness.element("recommendationList").innerHTML, /이미 동기화됨/);
});

test("동기화 재시도 가능 후보는 동일 select API로 복구하고, 자동 복구 불가 후보는 요청하지 않는다", async () => {
	const calls = [];
	const scheduledTask = { ...task(42, "재시도 일정"), status: "SCHEDULED" };
	const retryable = { ...recommendation(77), status: "SYNC_FAILED", syncRetryable: true };
	const synced = { ...retryable, status: "SYNCED", syncRetryable: false };
	const harness = createHarness({
		fetchImpl: async (url) => {
			calls.push(url);
			if (url === "/recommendations/77/select") return jsonResponse(200, synced);
			if (url === "/tasks/42") return jsonResponse(200, scheduledTask);
			if (url === "/tasks/42/recommendations?includeHistory=true") return jsonResponse(200, [synced]);
			throw new Error(`예상하지 않은 요청: ${url}`);
		}
	});
	harness.element("naturalMessage").value = "재시도 일정";
	harness.call("state.calendarConnected = true");
	harness.call(`state.currentTask = ${JSON.stringify(scheduledTask)}`);
	harness.call("state.parsedInput = '재시도 일정'");
	harness.call(`state.recommendations = ${JSON.stringify([retryable])}`);

	await harness.call("selectRecommendation(77)");
	assert.deepEqual(calls, [
		"/recommendations/77/select",
		"/tasks/42",
		"/tasks/42/recommendations?includeHistory=true"
	]);
	assert.equal(harness.state().recommendations[0].status, "SYNCED");

	const legacyCalls = [];
	const legacyHarness = createHarness({
		fetchImpl: async (url) => {
			legacyCalls.push(url);
			throw new Error(`요청하면 안 됩니다: ${url}`);
		}
	});
	legacyHarness.element("naturalMessage").value = "수동 확인 일정";
	legacyHarness.call("state.calendarConnected = true");
	legacyHarness.call(`state.currentTask = ${JSON.stringify({ ...task(42, "수동 확인 일정"), status: "SCHEDULED" })}`);
	legacyHarness.call("state.parsedInput = '수동 확인 일정'");
	legacyHarness.call(`state.recommendations = ${JSON.stringify([{ ...recommendation(88), status: "SYNC_FAILED", syncRetryable: false }])}`);
	await legacyHarness.call("selectRecommendation(88)");
	assert.deepEqual(legacyCalls, []);
	assert.match(legacyHarness.element("selectionResult").innerHTML, /수동 확인/);
});

test("선택 요청의 응답이 유실되어도 서버 조회로 동기화 완료 상태를 복구한다", async () => {
	const calls = [];
	const scheduledTask = { ...task(42, "응답 유실 일정"), status: "SCHEDULED" };
	const synced = { ...recommendation(77), status: "SYNCED" };
	const harness = createHarness({
		fetchImpl: async (url) => {
			calls.push(url);
			if (url === "/recommendations/77/select") throw new Error("응답 유실");
			if (url === "/tasks/42") return jsonResponse(200, scheduledTask);
			if (url === "/tasks/42/recommendations?includeHistory=true") return jsonResponse(200, [synced]);
			throw new Error(`예상하지 않은 요청: ${url}`);
		}
	});
	harness.element("naturalMessage").value = "응답 유실 일정";
	harness.call("state.calendarConnected = true");
	harness.call(`state.currentTask = ${JSON.stringify(task(42, "응답 유실 일정"))}`);
	harness.call("state.parsedInput = '응답 유실 일정'");
	harness.call(`state.recommendations = ${JSON.stringify([recommendation(77)])}`);

	await harness.call("selectRecommendation(77)");

	assert.deepEqual(calls, [
		"/recommendations/77/select",
		"/tasks/42",
		"/tasks/42/recommendations?includeHistory=true"
	]);
	assert.equal(harness.state().selection.body.status, "SYNCED");
	assert.match(harness.element("selectionResult").innerHTML, /Google Calendar 등록 완료/);
});

test("Calendar 연결 뒤 기존 후보를 검증 완료로 표시하지 않고 최신 검증을 안내한다", async () => {
	const harness = createHarness();
	harness.call("state.calendarConnected = true");
	harness.call(`state.recommendations = ${JSON.stringify([recommendation(77)])}`);
	harness.call("renderRecommendations()");
	harness.call("renderRecommendationConditions()");

	assert.doesNotMatch(harness.element("recommendationList").innerHTML, /Google Calendar 일정과 겹치지 않음/);
	assert.match(harness.element("recommendationList").innerHTML, /최신 조회로 다시 검증/);
	assert.match(harness.element("recommendationConditionList").innerHTML, /선택 직전에 최신 충돌 재검증/);
});

test("선택 뒤 서버 상태 조회도 실패하면 중복 선택을 막고 서버 재확인을 안내한다", async () => {
	const calls = [];
	const harness = createHarness({
		fetchImpl: async (url) => {
			calls.push(url);
			if (url === "/recommendations/77/select") throw new Error("응답 유실");
			throw new Error("서버 조회 실패");
		}
	});
	harness.element("naturalMessage").value = "확인 필요 일정";
	harness.call("state.calendarConnected = true");
	harness.call(`state.currentTask = ${JSON.stringify(task(42, "확인 필요 일정"))}`);
	harness.call("state.parsedInput = '확인 필요 일정'");
	harness.call(`state.recommendations = ${JSON.stringify([recommendation(77)])}`);

	await harness.call("selectRecommendation(77)");
	await harness.call("selectRecommendation(77)");

	assert.equal(calls.filter((url) => url === "/recommendations/77/select").length, 1);
	assert.equal(harness.state().needsServerConfirmation, true);
	assert.match(harness.element("selectionResult").innerHTML, /서버 상태 확인/);
});

test("저장된 Task 자동 복원은 GET 조회만 사용하고, 늦은 이전 계정 응답은 새 로그인 상태를 덮어쓰지 않는다", async () => {
	const calls = [];
	const taskListGate = deferred();
	const harness = createHarness({
		fetchImpl: async (url) => {
			calls.push(url);
			return taskListGate.promise;
		}
	});
	harness.storage.set("demoCurrentTaskId", "42");
	const loading = harness.call("loadExistingTasks(true)");
	harness.call("saveAuth({ accessToken: 'new-access', refreshToken: 'new-refresh' })");
	taskListGate.resolve(jsonResponse(200, [task(42, "이전 계정 Task")]));
	await loading;

	assert.deepEqual(calls, ["/tasks"]);
	assert.equal(harness.state().accessToken, "new-access");
	assert.deepEqual(asPlainJson(harness.state().existingTasks), []);
	assert.equal(harness.storage.has("demoCurrentTaskId"), false);
});

test("Google Calendar 미연동 상태에서는 후보 선택 요청을 보내지 않고 연결 필요를 안내한다", async () => {
	const calls = [];
	const harness = createHarness({
		fetchImpl: async (url) => {
			calls.push(url);
			throw new Error(`요청하면 안 됩니다: ${url}`);
		}
	});
	harness.element("naturalMessage").value = "미연동 일정";
	harness.call(`state.currentTask = ${JSON.stringify(task(42, "미연동 일정"))}`);
	harness.call("state.parsedInput = '미연동 일정'");
	harness.call(`state.recommendations = ${JSON.stringify([recommendation(77)])}`);
	harness.call("renderRecommendations()");

	await harness.call("selectRecommendation(77)");

	assert.deepEqual(calls, []);
	assert.match(harness.element("recommendationList").innerHTML, /Google Calendar 연결 필요/);
	assert.match(harness.element("selectionResult").innerHTML, /Google Calendar 연결이 필요합니다/);
});

test("복원한 미배정 Task에서도 기존 추천 생성 API로 작업을 재개한다", async () => {
	const calls = [];
	const harness = createHarness({ fetchImpl: async (url, options = {}) => {
		calls.push({ url, method: options.method || 'GET' });
		if (url === '/tasks/42') return jsonResponse(200, task(42));
		if (url.endsWith('?includeHistory=true')) return jsonResponse(200, []);
		if (url === '/tasks/42/recommendations') return jsonResponse(200, { recommendations: [recommendation(77)] });
		throw new Error('예상하지 않은 API');
	} });
	await harness.call('restoreTask(42)');
	await harness.call('generateRecommendations()');
	assert.deepEqual(calls, [
		{ url: '/tasks/42', method: 'GET' },
		{ url: '/tasks/42/recommendations?includeHistory=true', method: 'GET' },
		{ url: '/tasks/42/recommendations', method: 'POST' }
	]);
	assert.equal(harness.state().recommendations[0].id, 77);
});

test("select 성공 응답 뒤 조회가 실패해도 완료로 단정하지 않고 확인 버튼을 제공한다", async () => {
	const harness = createHarness({ fetchImpl: async (url) => {
		if (url === '/recommendations/77/select') return jsonResponse(200, { ...recommendation(77), status: 'SYNCED' });
		throw new Error('상태 조회 장애');
	} });
	harness.call(`state.currentTask = ${JSON.stringify(task(42))}; state.restoredTask = true; state.calendarConnected = true; state.recommendations = [${JSON.stringify(recommendation(77))}];`);
	await harness.call('selectRecommendation(77)');
	assert.equal(harness.state().needsServerConfirmation, true);
	assert.match(harness.element('selectionResult').innerHTML, /확인/);
	assert.match(harness.element('selectionResult').innerHTML, /data-refresh-recommendation-state/);
	assert.doesNotMatch(harness.element('selectionResult').innerHTML, /등록 완료/);
	assert.equal(harness.element('recommendButton').disabled, true);
});

test("확정 진행 중 중복 선택을 차단하고 완료된 Task의 재추천 버튼도 잠근다", async () => {
	const pending = deferred();
	const calls = [];
	const harness = createHarness({ fetchImpl: async (url) => {
		calls.push(url);
		if (url === '/recommendations/77/select') return pending.promise;
		if (url === '/tasks/42') return jsonResponse(200, { ...task(42), status: 'SCHEDULED' });
		return jsonResponse(200, [{ ...recommendation(77), status: 'SYNCED' }]);
	} });
	harness.call(`state.currentTask = ${JSON.stringify(task(42))}; state.restoredTask = true; state.calendarConnected = true; state.recommendations = [${JSON.stringify(recommendation(77))}]; renderRecommendations();`);
	const first = harness.call('selectRecommendation(77)');
	assert.equal(harness.recommendationButton(77).disabled, true);
	await harness.call('selectRecommendation(77)');
	pending.resolve(jsonResponse(200, { ...recommendation(77), status: 'SYNCED' }));
	await first;
	await harness.call('selectRecommendation(77)');
	assert.equal(calls.filter(url => url === '/recommendations/77/select').length, 1);
	assert.equal(harness.state().currentTask.status, 'SCHEDULED');
	assert.equal(harness.element('recommendButton').disabled, true);
	assert.match(harness.element('recommendationList').innerHTML, /이미 동기화됨/);
});

test("늦은 Task 복원과 초기 목록 응답이 새 입력 분석 결과를 덮어쓰지 않는다", async () => {
	for (const operation of ['restoreTask(42)', 'loadExistingTasks(true)']) {
		const pending = deferred();
		const calls = [];
		const harness = createHarness({ fetchImpl: async (url) => {
			calls.push(url);
			if (url === '/tasks/natural-language') return jsonResponse(201, task(99, '새 입력'));
			return pending.promise;
		} });
		harness.call('bindEvents()');
		harness.storage.set('demoCurrentTaskId', '42');
		const restoring = harness.call(operation);
		harness.element('naturalMessage').value = '새 입력';
		harness.element('naturalMessage').dispatch('input');
		await harness.call('parseNaturalLanguage()');
		pending.resolve(jsonResponse(200, operation.startsWith('load') ? [task(42)] : task(42)));
		await restoring;
		assert.equal(harness.state().currentTask.id, 99);
		assert.equal(harness.storage.get('demoCurrentTaskId'), '99');
		assert.equal(harness.state().isRestoring, false);
		assert.equal(calls.filter(url => url.startsWith('/tasks/42') && operation.startsWith('load')).length, 0);
	}
});

test("페이지 초기화는 저장된 Task를 서버 GET으로만 복원하며 Calendar 등록을 재실행하지 않는다", async () => {
	const calls = [];
	const scheduled = { ...task(42), status: 'SCHEDULED' };
	const synced = { ...recommendation(77), status: 'SYNCED' };
	const harness = createHarness({ fetchImpl: async (url, options = {}) => {
		calls.push({ url, method: options.method || 'GET' });
		if (url === '/tasks') return jsonResponse(200, [scheduled]);
		if (url === '/tasks/42') return jsonResponse(200, scheduled);
		if (url.endsWith('?includeHistory=true')) return jsonResponse(200, [synced]);
		if (url === '/api/calendar/status') return jsonResponse(200, { connected: true });
		if (url === '/preferences') return jsonResponse(200, { preferredTimeRange: 'LUNCH', scheduleStartTime: '08:00', scheduleEndTime: '22:00', minimumGapMinutes: 10 });
		if (url === '/timetable-slots') return jsonResponse(200, []);
		throw new Error('예상하지 않은 초기화 요청');
	} });
	harness.storage.set('demoCurrentTaskId', '42');
	await harness.call('loadInitialData()');
	assert.ok(calls.every(call => call.method === 'GET'));
	assert.ok(calls.some(call => call.url.endsWith('?includeHistory=true')));
	assert.equal(harness.state().currentTask.status, 'SCHEDULED');
	assert.equal(harness.state().selection.body.status, 'SYNCED');
	assert.match(harness.element('selectionResult').innerHTML, /등록 완료/);
	assert.equal(harness.element('recommendButton').disabled, true);
});

test("예약 ID가 없는 실패 이력은 Calendar 미연동이어도 연결 대신 수동 확인을 안내한다", async () => {
	const harness = createHarness({ fetchImpl: async () => { throw new Error('외부 호출 금지'); } });
	harness.call(`state.currentTask = ${JSON.stringify({ ...task(42), status: 'SCHEDULED' })}; state.restoredTask = true; state.calendarConnected = false; state.recommendations = [${JSON.stringify({ ...recommendation(77), status: 'SYNC_FAILED', syncRetryable: false })}]; renderRecommendations();`);
	assert.match(harness.element('recommendationList').innerHTML, /수동 확인/);
	await harness.call('selectRecommendation(77)');
	assert.match(harness.element('selectionResult').innerHTML, /수동 확인/);
});

test("이전 계정의 늦은 Calendar 조회 실패가 새 계정의 연결 상태를 덮어쓰지 않는다", async () => {
	const old = deferred();
	let reads = 0;
	const harness = createHarness({ fetchImpl: async () => ++reads === 1 ? old.promise : jsonResponse(200, { connected: true }) });
	const loading = harness.call('refreshCalendarStatus()');
	harness.call("saveAuth({ accessToken: 'account-b-access', refreshToken: 'account-b-refresh' })");
	await harness.call('refreshCalendarStatus()');
	assert.equal(harness.state().calendarConnected, true);
	old.resolve(jsonResponse(200, { connected: false }));
	await loading;
	assert.equal(harness.state().calendarConnected, true);
	assert.equal(harness.element('calendarText').textContent, '연결됨');
});

test("기존 Task를 드롭다운에서 선택하면 불러오기 버튼이 활성화된다", () => {
	const harness = createHarness();
	harness.call('bindEvents(); updateTaskControls();');
	assert.equal(harness.element('loadTaskButton').disabled, true);
	harness.element('existingTaskSelect').value = '42';
	harness.element('existingTaskSelect').dispatch('change');
	assert.equal(harness.element('loadTaskButton').disabled, false);
});

test("Task 복원 중 계정을 바꿔도 로딩 잠금이 남지 않고 새 계정 Task를 불러올 수 있다", async () => {
	const old = deferred();
	const harness = createHarness({ fetchImpl: async (url) => {
		if (url.startsWith('/tasks/42')) return old.promise;
		if (url === '/tasks/99') return jsonResponse(200, task(99));
		return jsonResponse(200, []);
	} });
	const loading = harness.call('restoreTask(42)');
	harness.call("saveAuth({ accessToken: 'new-account', refreshToken: 'new-refresh' })");
	const loadingAfterSwitch = harness.state().isRestoring;
	await harness.call('restoreTask(99)');
	old.resolve(jsonResponse(200, task(42)));
	await loading;
	assert.equal(loadingAfterSwitch, false);
	assert.equal(harness.state().currentTask.id, 99);
	assert.equal(harness.state().isRestoring, false);
});

test("추천 유형·적합도·서버 이유를 그대로 표시하고 원조건과 대안을 구분한다", () => {
	const harness = createHarness();
	const exact = {
		...recommendation(77),
		rank: 1,
		score: 120,
		matchType: "EXACT",
		reason: "요청한 목요일 19:00 시작 조건을 충족했습니다."
	};
	const alternative = {
		...recommendation(78),
		rank: 2,
		score: 98,
		matchType: "DATE_CHANGED",
		reason: "<img src=x onerror=alert(1)> 날짜를 변경한 대안입니다."
	};

	harness.call(`state.recommendations = ${JSON.stringify([exact, alternative])}; renderRecommendations();`);

	const markup = harness.element("recommendationList").innerHTML;
	assert.match(markup, /정확히 일치하는 시간대가 존재해요!/);
	assert.match(markup, /다른 대안도 있어요/);
	assert.match(markup, /추천 적합도<\/dt><dd>85\.7점/);
	assert.doesNotMatch(markup, /내부 원점수|120점/);
	assert.match(markup, /요청한 목요일 19:00 시작 조건을 충족했습니다/);
	assert.match(markup, /&lt;img src=x onerror=alert\(1\)&gt;/);
	assert.doesNotMatch(markup, /<img src=x onerror=alert\(1\)>/);
	assert.ok(markup.indexOf("추천 1순위") < markup.indexOf("추천 2순위"));
});

test("고정_시작_안내는_계산_이력_없이_서버에서_복원한_날짜와_시각으로_표시한다", () => {
	const harness = createHarness();
	const fixed = { ...task(45), preferredDate: "2026-11-01", preferredDayOfWeek: null, preferredTimeRange: null, preferredStartTime: "00:31:00", dateRequired: true, timeRequired: true };
	for (const restored of [false, true]) {
		harness.call(`state.currentTask = ${JSON.stringify(fixed)}; state.restoredTask = ${restored}; renderParsedResult();`);
		assert.match(harness.element("parsedResult").innerHTML, /날짜와 시작 시각이 고정된 일정/);
		assert.match(harness.element("parsedResult").innerHTML, /충돌하면 다른 시간으로 변경하지 않습니다/);
		assert.match(harness.element("parsedResult").innerHTML, /00:31/);
	}
	harness.call(`state.currentTask = ${JSON.stringify(task(46))}; renderParsedResult();`);
	assert.doesNotMatch(harness.element("parsedResult").innerHTML, /날짜와 시작 시각이 고정된 일정/);
});

test("분석·복원 화면은 정확한 목표 시각과 필수 조건을 요약하고 기존 null 의미를 보존한다", () => {
	const harness = createHarness();
	const explicit = {
		...task(42, "알고리즘 공부"),
		preferredDayOfWeek: "THURSDAY",
		preferredTimeRange: "EVENING",
		preferredStartTime: "19:00:00",
		dateRequired: true,
		timeRequired: true
	};

	harness.call(`state.currentTask = ${JSON.stringify(explicit)}; renderParsedResult();`);
	assert.match(harness.element("parsedResult").innerHTML, /목표 시작 시각<\/dt><dd>19:00/);
	assert.match(harness.element("parsedResult").innerHTML, /날짜 조건<\/dt><dd>필수/);
	assert.match(harness.element("parsedResult").innerHTML, /시간 조건<\/dt><dd>필수/);
	assert.match(harness.element("parsedResult").innerHTML, /요청 날짜<\/dt><dd>목요일/);

	const dateAndRange = {
		...task(44, "기간이 있는 Task"),
		preferredDate: "2026-10-22",
		preferredDayOfWeek: null,
		preferredStartDate: "2026-10-22",
		preferredEndDate: "2026-11-04",
		dateRequired: false,
		timeRequired: false
	};
	harness.call(`state.currentTask = ${JSON.stringify(dateAndRange)}; renderParsedResult();`);
	assert.match(harness.element("parsedResult").innerHTML, /2026-10-22 · 허용 기간 2026-10-22 ~ 2026-11-04/);

	harness.call(`state.currentTask = ${JSON.stringify(task(43, "기존 Task"))}; renderParsedResult();`);
	assert.match(harness.element("parsedResult").innerHTML, /목표 시작 시각<\/dt><dd>지정하지 않음/);
	assert.match(harness.element("parsedResult").innerHTML, /날짜 조건<\/dt><dd>기존 Task: 날짜 조건 제한/);
	assert.match(harness.element("parsedResult").innerHTML, /시간 조건<\/dt><dd>기존 Task: 시간대 가점/);
});


test("서버가_확인한_원조건_제외_사유를_표시하고_입력_변경시_지운다", async () => {
    const message = "요청하신 조건에 맞는 후보 대신 다른 대안을 추천했어요. Google Calendar 일정과 겹쳐요.";
    const harness = createHarness({ fetchImpl: async () => jsonResponse(200, { message, recommendations: [recommendation(77)] }) });
    harness.element("naturalMessage").value = "공부";
    harness.call(`state.currentTask = ${JSON.stringify(task(42))}; state.parsedInput = "공부";`);
    await harness.call("generateRecommendations()");
    assert.equal(harness.element("recommendationMessage").textContent, message);
    harness.call("invalidateAnalysis()");
    assert.equal(harness.element("recommendationMessage").textContent, "");
});

test("Calendar_조회_실패를_충돌_안내로_바꾸지_않는다", async () => {
    const harness = createHarness({ fetchImpl: async () => jsonResponse(502, { code: "GOOGLE_CALENDAR_FREE_BUSY_FAILED", message: "Google Calendar 일정을 조회하지 못했습니다." }) });
    harness.element("naturalMessage").value = "공부";
    harness.call(`state.currentTask = ${JSON.stringify(task(42))}; state.parsedInput = "공부";`);
    await harness.call("generateRecommendations()");
    assert.match(harness.element("taskMessage").textContent, /조회하지 못했습니다/);
    assert.equal(harness.element("recommendationMessage").textContent, "");
    assert.doesNotMatch(harness.element("recommendationList").innerHTML, /일정과 겹쳐/);
});


test("원조건_제외_사유는_텍스트로_표시하고_새_추천_시작과_실패시_지운다", async () => {
    const pending = deferred();
    const harness = createHarness({ fetchImpl: async () => pending.promise });
    harness.element("naturalMessage").value = "공부";
    harness.call(`state.currentTask = ${JSON.stringify(task(42))}; state.parsedInput = "공부"; state.recommendationMessage = "이전 충돌"; renderRecommendations();`);
    const loading = harness.call("generateRecommendations()");
    assert.equal(harness.element("recommendationMessage").textContent, "");
    pending.resolve(jsonResponse(502, { message: "Google Calendar 일정을 조회하지 못했습니다." }));
    await loading;
    assert.equal(harness.element("recommendationMessage").textContent, "");
    harness.call('state.recommendationMessage = "<img src=x onerror=alert(1)>"; renderRecommendations();');
    assert.equal(harness.element("recommendationMessage").textContent, "<img src=x onerror=alert(1)>");
    assert.equal(harness.element("recommendationMessage").innerHTML, "");
});

test("추천_응답이_늦게_도착해도_다른_입력의_충돌_사유로_표시하지_않는다", async () => {
    const pending = deferred();
    const harness = createHarness({ fetchImpl: async () => pending.promise });
    harness.element("naturalMessage").value = "공부";
    harness.call(`state.currentTask = ${JSON.stringify(task(42))}; state.parsedInput = "공부";`);
    const loading = harness.call("generateRecommendations()");
    harness.call("invalidateAnalysis()");
    pending.resolve(jsonResponse(200, { message: "이전 Task의 Calendar 충돌", recommendations: [recommendation(77)] }));
    await loading;
    assert.equal(harness.element("recommendationMessage").textContent, "");
});

test("저장_이력_재조회는_저장되지_않은_충돌_사유를_재구성하지_않는다", async () => {
    const harness = createHarness({ fetchImpl: async (url) => url === "/tasks/42"
        ? jsonResponse(200, task(42)) : jsonResponse(200, [recommendation(77)]) });
    harness.call('state.recommendationMessage = "이전 Task의 Calendar 충돌"; renderRecommendations();');
    await harness.call("restoreTask(42)");
    assert.equal(harness.element("recommendationMessage").textContent, "");
    assert.doesNotMatch(harness.element("recommendationList").innerHTML, /일정과 겹쳐요/);
});
