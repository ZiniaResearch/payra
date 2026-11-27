import {
	type AxiosRequestConfig,
	type AxiosResponse,
	CanceledError,
	isAxiosError,
} from "axios";
import { clearBrowserSession } from "@/modules/auth/browser-key-store";
import {
	AUTH_LOCK,
	AuthenticationRequiredError,
	refreshSession,
	SessionRecoveredError,
} from "@/modules/auth/refresh";
import { notifySessionEnded } from "@/modules/auth/session-events";
import { api } from "./client";

/** Reads the error code from a Payra API response body. */
export function readApiErrorCode(body: unknown): string | undefined {
	if (typeof body !== "object" || body === null || !("error" in body)) {
		return undefined;
	}
	return typeof body.error === "string" ? body.error : undefined;
}

type AccessRecovery = "refresh" | "confirm";

async function requestWithAccess<T>(
	config: AxiosRequestConfig,
): Promise<AxiosResponse<T> | AccessRecovery> {
	try {
		return await api.request<T>(config);
	} catch (error) {
		if (!isAxiosError<unknown>(error) || error.response?.status !== 401) {
			throw error;
		}

		const code = readApiErrorCode(error.response.data);
		return code === "access_missing" || code === "access_expired"
			? "refresh"
			: "confirm";
	}
}

async function refreshAndRetry<T>(
	config: AxiosRequestConfig,
	recovery: AccessRecovery,
): Promise<T> {
	if (config.signal?.aborted) {
		throw new CanceledError();
	}

	const session = await refreshSession();
	const method = config.method?.toUpperCase() ?? "GET";

	if (recovery === "confirm" && method !== "GET" && method !== "HEAD") {
		throw new SessionRecoveredError();
	}

	try {
		return (await api.request<T>(config)).data;
	} catch (error) {
		if (!isAxiosError(error) || error.response?.status !== 401) {
			throw error;
		}

		await clearBrowserSession(session.session_id);
		notifySessionEnded(session.session_id);
		throw new AuthenticationRequiredError();
	}
}

export async function authenticatedRequest<T>(
	config: AxiosRequestConfig,
): Promise<T> {
	const response = await requestWithAccess<T>(config);

	if (typeof response !== "string") {
		return response.data;
	}

	return navigator.locks.request(AUTH_LOCK, () =>
		response === "confirm"
			? refreshAndRetry<T>(config, response)
			: authenticatedRequestUnderLock<T>(config),
	);
}

/** Use only while holding AUTH_LOCK. */
export async function authenticatedRequestUnderLock<T>(
	config: AxiosRequestConfig,
): Promise<T> {
	if (config.signal?.aborted) {
		throw new CanceledError();
	}

	const response = await requestWithAccess<T>(config);

	if (typeof response !== "string") {
		return response.data;
	}

	return refreshAndRetry<T>(config, response);
}
