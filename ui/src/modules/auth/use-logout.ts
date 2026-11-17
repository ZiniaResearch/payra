import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "@tanstack/react-router";
import { useCallback, useEffect, useState } from "react";
import { loadBrowserSession } from "./browser-key-store";
import { LogoutVerificationError, logoutSession } from "./logout";
import { AUTH_LOCK } from "./refresh";
import { AUTH_CHANNEL, type SessionEndedNotice } from "./session-events";

export function useLogout() {
	const router = useRouter();
	const queryClient = useQueryClient();
	const [pending, setPending] = useState(false);
	const [error, setError] = useState("");

	const clearAccountCache = useCallback(async () => {
		await queryClient.cancelQueries();
		queryClient.clear();
		router.clearCache();
	}, [queryClient, router]);

	const leaveProtectedView = useCallback(async () => {
		await clearAccountCache();
		await router.navigate({ to: "/login", replace: true });
		router.clearCache();
	}, [clearAccountCache, router]);

	const reloadProtectedView = useCallback(async () => {
		await clearAccountCache();
		await router.invalidate({ sync: true });
	}, [clearAccountCache, router]);

	useLogoutSync(leaveProtectedView, reloadProtectedView, setError);

	async function logout() {
		setPending(true);
		setError("");

		try {
			await navigator.locks.request(AUTH_LOCK, async () => {
				await logoutSession();
				await leaveProtectedView();
			});
		} catch (cause) {
			setError(
				cause instanceof LogoutVerificationError
					? cause.message
					: "Could not finish signing out. Check your connection and try again.",
			);
		} finally {
			setPending(false);
		}
	}

	return { logout, pending, error };
}

function useLogoutSync(
	leaveProtectedView: () => Promise<void>,
	reloadProtectedView: () => Promise<void>,
	onError: (message: string) => void,
) {
	useEffect(() => {
		if (!("BroadcastChannel" in window)) {
			return;
		}

		const channel = new BroadcastChannel(AUTH_CHANNEL);
		let active = true;

		async function synchronizeLogout(sessionId: string | null) {
			try {
				const shouldReload = await navigator.locks.request(
					AUTH_LOCK,
					async () => {
						if (!active) {
							return false;
						}

						const current = await loadBrowserSession();
						if (current && current.session_id !== sessionId) {
							return true;
						}

						await leaveProtectedView();
						return false;
					},
				);

				if (!shouldReload || !active) {
					return;
				}

				await reloadProtectedView();
			} catch {
				if (!active) {
					return;
				}

				onError("Could not synchronize sign-out. Reload this page.");
			}
		}

		function onMessage(event: MessageEvent<SessionEndedNotice>) {
			if (event.data?.type !== "session-ended") {
				return;
			}

			void synchronizeLogout(event.data.session_id);
		}

		channel.addEventListener("message", onMessage);
		return () => {
			active = false;
			channel.removeEventListener("message", onMessage);
			channel.close();
		};
	}, [leaveProtectedView, reloadProtectedView, onError]);
}
