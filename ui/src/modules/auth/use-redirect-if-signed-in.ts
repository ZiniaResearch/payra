import { useRouter } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { loadBrowserSession } from "./browser-key-store";
import { AUTH_CHANNEL, type SessionStartedNotice } from "./session-events";

export function useRedirectIfSignedIn() {
	const router = useRouter();
	const [canShowPage, setCanShowPage] = useState(false);

	useEffect(() => {
		let active = true;
		let redirecting = false;
		const channel =
			"BroadcastChannel" in window
				? new BroadcastChannel(AUTH_CHANNEL)
				: undefined;

		async function checkSession() {
			const session = await loadBrowserSession().catch(() => undefined);
			if (!active || redirecting) return;
			if (session) {
				redirecting = true;
				await router.navigate({ to: "/messages/new", replace: true });
				return;
			}

			setCanShowPage(true);
		}

		function onMessage(event: MessageEvent<SessionStartedNotice>) {
			if (event.data?.type === "session-started") {
				void checkSession();
			}
		}

		function onVisibilityChange() {
			if (document.visibilityState === "visible") {
				void checkSession();
			}
		}

		channel?.addEventListener("message", onMessage);
		document.addEventListener("visibilitychange", onVisibilityChange);
		void checkSession();

		return () => {
			active = false;
			channel?.removeEventListener("message", onMessage);
			channel?.close();
			document.removeEventListener("visibilitychange", onVisibilityChange);
		};
	}, [router]);

	return canShowPage;
}
