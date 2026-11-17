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
} from "@/modules/auth/refresh";
import { notifySessionEnded } from "@/modules/auth/session-events";
import { api } from "./client";

interface ApiErrorResponse {
	error: string;
}

async function requestWithAccess<T>(
	config: AxiosRequestConfig,
): Promise<AxiosResponse<T> | null> {
	try {
		return await api.request<T>(config);
	} catch (error) {
		if (
			!isAxiosError<ApiErrorResponse>(error) ||
			error.response?.status !== 401
		) {
			throw error;
		}

		const code = error.response.data?.error;

		if (code === "access_missing" || code === "access_expired") {
			return null;
		}

		throw new AuthenticationRequiredError();
	}
}

async function refreshAndRetry<T>(config: AxiosRequestConfig): Promise<T> {
	const session = await refreshSession();

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

	if (response) {
		return response.data;
	}

	return navigator.locks.request(AUTH_LOCK, async () => {
		if (config.signal?.aborted) {
			throw new CanceledError();
		}

		const recheck = await requestWithAccess<T>(config);

		if (recheck) {
			return recheck.data;
		}

		return refreshAndRetry<T>(config);
	});
}
