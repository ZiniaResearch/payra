export async function generateBrowserKey() {
	const keyPair = await window.crypto.subtle.generateKey(
		{ name: "ECDSA", namedCurve: "P-256" },
		false,
		["sign", "verify"],
	);

	const publicKey = await window.crypto.subtle.exportKey(
		"jwk",
		keyPair.publicKey,
	);

	return { keyPair, publicKey };
}
