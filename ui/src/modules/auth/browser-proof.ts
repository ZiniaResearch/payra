import type { BrowserSession } from "./browser-key-store";

function base64Url(bytes: Uint8Array): string {
	const binary = Array.from(bytes, (byte) => String.fromCharCode(byte)).join(
		"",
	);
	return btoa(binary)
		.replaceAll("+", "-")
		.replaceAll("/", "_")
		.replace(/=+$/u, "");
}

export function createBrowserProof(
	session: BrowserSession,
	privateKey: CryptoKey,
	targetUrl: string,
	purpose: "refresh" | "logout",
): Promise<string> {
	return signBrowserProof(
		session.session_id,
		privateKey,
		targetUrl,
		purpose,
		session.refresh_token_hash,
	);
}

export async function createProfileDeletionProof(
	sessionId: string,
	privateKey: CryptoKey,
	targetUrl: string,
	nonce: string,
): Promise<string> {
	const nonceHash = await window.crypto.subtle.digest(
		"SHA-256",
		new TextEncoder().encode(nonce),
	);
	return signBrowserProof(
		sessionId,
		privateKey,
		targetUrl,
		"delete-profile",
		base64Url(new Uint8Array(nonceHash)),
	);
}

async function signBrowserProof(
	sessionId: string,
	privateKey: CryptoKey,
	targetUrl: string,
	purpose: "refresh" | "logout" | "delete-profile",
	tokenHash: string,
): Promise<string> {
	const encoder = new TextEncoder();
	const header = { alg: "ES256", typ: `payra-${purpose}-proof+jwt` };
	const claims = {
		htm: purpose === "delete-profile" ? "DELETE" : "POST",
		htu: targetUrl,
		iat: Math.floor(Date.now() / 1000),
		sid: sessionId,
		ath: tokenHash,
	};
	const signingInput = [
		base64Url(encoder.encode(JSON.stringify(header))),
		base64Url(encoder.encode(JSON.stringify(claims))),
	].join(".");

	const signature = await window.crypto.subtle.sign(
		{ name: "ECDSA", hash: "SHA-256" },
		privateKey,
		encoder.encode(signingInput),
	);

	return `${signingInput}.${base64Url(new Uint8Array(signature))}`;
}
