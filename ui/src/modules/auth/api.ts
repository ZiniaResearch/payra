import { api } from "@/shared/api/client";

export interface SessionResponse {
	session_id: string;
	refresh_token_hash: string;
}

export async function signInWithGoogle(
	code: string,
	publicKey: JsonWebKey,
): Promise<SessionResponse> {
	const response = await api.post<SessionResponse>(
		"/api/auth/google",
		{ code, browser_public_key: publicKey },
		{ headers: { "X-Requested-With": "XmlHttpRequest" } },
	);

	return response.data;
}
