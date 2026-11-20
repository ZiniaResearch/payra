import { isAxiosError } from "axios";
import { api } from "@/shared/api/client";
import type { SessionResponse } from "./api";
import {
	type BrowserSession,
	clearBrowserSession,
	loadBrowserKey,
	loadBrowserSession,
	saveBrowserSession,
} from "./browser-key-store";
import { createBrowserProof } from "./browser-proof";
import { notifySessionEnded } from "./session-events";

export const AUTH_LOCK = "payra-refresh";

export class AuthenticationRequiredError extends Error {
	constructor() {
		super("Sign in again to continue.");
		this.name = "AuthenticationRequiredError";
	}
}

export class SessionRecoveredError extends Error {
	constructor() {
		super(
			"Your session was recovered. Check the current account before trying again.",
		);
		this.name = "SessionRecoveredError";
	}
}

export async function refreshSession(): Promise<BrowserSession> {
	const session = await loadBrowserSession();
	if (!session) {
		notifySessionEnded(null);
		throw new AuthenticationRequiredError();
	}

	const keyPair = await loadBrowserKey(session.keyId);
	if (!keyPair) {
		await clearBrowserSession(session.session_id);
		notifySessionEnded(session.session_id);
		throw new AuthenticationRequiredError();
	}

	const path = "/api/auth/refresh";
	const refreshUrl = new URL(api.getUri({ url: path }), window.location.origin)
		.href;
	const proof = await createBrowserProof(
		session,
		keyPair.privateKey,
		refreshUrl,
		"refresh",
	);

	try {
		const response = await api.post<SessionResponse>(
			path,
			{ proof },
			{ headers: { "X-Requested-With": "XmlHttpRequest" } },
		);
		if (response.data.session_id !== session.session_id) {
			throw new Error("Refresh returned a different session.");
		}

		const updated: BrowserSession = {
			session_id: response.data.session_id,
			refresh_token_hash: response.data.refresh_token_hash,
			keyId: session.keyId,
		};
		await saveBrowserSession(updated);
		return updated;
	} catch (error) {
		if (isAxiosError(error) && error.response?.status === 401) {
			await clearBrowserSession(session.session_id);
			notifySessionEnded(session.session_id);
			throw new AuthenticationRequiredError();
		}

		throw error;
	}
}
