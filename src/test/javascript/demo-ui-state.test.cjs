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
		querySelectorAll() {
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
		status: "PARSED"
	};
}

function recommendation(id) {
	return {
		id,
		rank: 1,
		status: "RECOMMENDED",
		score: 120,
		recommendedStartAt: "2026-10-20T12:00:00",
		recommendedEndAt: "2026-10-20T13:00:00",
		reason: "기존 일정과 적당한 간격"
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
			throw new Error(`예상하지 않은 요청: ${url}`);
		}
	});
	harness.call("bindEvents()");
	harness.element("naturalMessage").value = "정상 요청";
	await harness.call("parseNaturalLanguage()");
	await harness.call("generateRecommendations()");
	assert.equal(harness.element("flowBadge").textContent, "RECOMMENDED");
	assert.match(harness.element("recommendationList").innerHTML, /추천 1순위/);
	await harness.call("selectRecommendation(77)");

	assert.deepEqual(calls.map((call) => call.url), [
		"/tasks/natural-language",
		"/tasks/42/recommendations",
		"/recommendations/77/select"
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
