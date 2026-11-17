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

export async function createBrowserProof(
	session: BrowserSession,
	privateKey: CryptoKey,
	targetUrl: string,
	purpose: "refresh" | "logout",
): Promise<string> {
	const encoder = new TextEncoder();
	const header = { alg: "ES256", typ: `payra-${purpose}-proof+jwt` };
	const claims = {
		htm: "POST",
		htu: targetUrl,
		iat: Math.floor(Date.now() / 1000),
		sid: session.session_id,
		ath: session.refresh_token_hash,
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
