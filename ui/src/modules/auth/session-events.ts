export const AUTH_CHANNEL = "payra-auth";

export interface SessionEndedNotice {
	type: "session-ended";
	session_id: string | null;
}

export function notifySessionEnded(sessionId: string | null): void {
	if (!("BroadcastChannel" in window)) {
		return;
	}
	const channel = new BroadcastChannel(AUTH_CHANNEL);
	const notice: SessionEndedNotice = {
		type: "session-ended",
		session_id: sessionId,
	};
	channel.postMessage(notice);
	channel.close();
}

export interface SessionStartedNotice {
	type: "session-started";
}

export function notifySessionStarted(): void {
	if (!("BroadcastChannel" in window)) {
		return;
	}

	const channel = new BroadcastChannel(AUTH_CHANNEL);
	const notice: SessionStartedNotice = { type: "session-started" };
	channel.postMessage(notice);
	channel.close();
}
