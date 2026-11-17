import { isAxiosError } from "axios";
import { api } from "@/shared/api/client";
import {
	type BrowserSession,
	clearBrowserSession,
	loadBrowserKey,
	loadBrowserSession,
} from "./browser-key-store";
import { createBrowserProof } from "./browser-proof";
import { notifySessionEnded } from "./session-events";

export class LogoutVerificationError extends Error {
	constructor() {
		super(
			"Sign-out could not be verified. Sign in again if your browser key or session data is missing.",
		);
		this.name = "LogoutVerificationError";
	}
}

async function createLogoutProof(
	session: BrowserSession | undefined,
	targetUrl: string,
): Promise<string> {
	if (!session) {
		return "";
	}

	const keyPair = await loadBrowserKey(session.keyId);
	if (!keyPair) {
		return "";
	}

	return createBrowserProof(session, keyPair.privateKey, targetUrl, "logout");
}

export async function logoutSession(): Promise<void> {
	const session = await loadBrowserSession();
	const path = "/api/auth/logout";
	const targetUrl = new URL(api.getUri({ url: path }), window.location.origin)
		.href;
	const proof = await createLogoutProof(session, targetUrl);

	try {
		await api.post<void>(
			path,
			{ proof },
			{ headers: { "X-Requested-With": "XmlHttpRequest" } },
		);
	} catch (error) {
		if (!isAxiosError(error) || error.response?.status !== 401) {
			throw error;
		}

		throw new LogoutVerificationError();
	}

	if (session) {
		await clearBrowserSession(session.session_id);
	}
	notifySessionEnded(session?.session_id ?? null);
}
