import { type DBSchema, openDB } from "idb";

export interface BrowserSession {
	session_id: string;
	refresh_token_hash: string;
	keyId: string;
}

interface BrowserKeyDatabase extends DBSchema {
	browserKeys: {
		key: string;
		value: CryptoKeyPair;
	};
	authSession: {
		key: "current";
		value: BrowserSession;
	};
}

function openKeyDatabase() {
	return openDB<BrowserKeyDatabase>("payra-auth", 1, {
		upgrade(db) {
			db.createObjectStore("browserKeys");
			db.createObjectStore("authSession");
		},
	});
}

export async function saveBrowserKey(keyPair: CryptoKeyPair): Promise<string> {
	const privateKey = keyPair.privateKey;
	// Safety: runtime checks below require ECDSA/P-256 before accepting the key.
	const algorithm = privateKey.algorithm as EcKeyAlgorithm;

	if (
		privateKey.type !== "private" ||
		privateKey.extractable ||
		algorithm.name !== "ECDSA" ||
		algorithm.namedCurve !== "P-256" ||
		!privateKey.usages.includes("sign")
	) {
		throw new Error("Expected a non-exportable P-256 signing key");
	}

	const keyId = window.crypto.randomUUID();
	const db = await openKeyDatabase();

	try {
		const transaction = db.transaction("browserKeys", "readwrite");
		await transaction.store.add(keyPair, keyId);
		await transaction.done;
		return keyId;
	} finally {
		db.close();
	}
}

export async function loadBrowserKey(
	keyId: string,
): Promise<CryptoKeyPair | undefined> {
	const db = await openKeyDatabase();

	try {
		return await db.get("browserKeys", keyId);
	} finally {
		db.close();
	}
}

export async function saveBrowserSession(
	session: BrowserSession,
): Promise<void> {
	const db = await openKeyDatabase();

	try {
		const transaction = db.transaction("authSession", "readwrite");
		await transaction.store.put(session, "current");
		await transaction.done;
	} finally {
		db.close();
	}
}

export async function loadBrowserSession(): Promise<
	BrowserSession | undefined
> {
	const db = await openKeyDatabase();
	try {
		return await db.get("authSession", "current");
	} finally {
		db.close();
	}
}

export async function clearBrowserSession(sessionId: string): Promise<void> {
	const db = await openKeyDatabase();

	try {
		const transaction = db.transaction(
			["authSession", "browserKeys"],
			"readwrite",
		);
		const sessions = transaction.objectStore("authSession");
		const current = await sessions.get("current");

		if (current?.session_id === sessionId) {
			await sessions.delete("current");
			await transaction.objectStore("browserKeys").delete(current.keyId);
		}

		await transaction.done;
	} finally {
		db.close();
	}
}
