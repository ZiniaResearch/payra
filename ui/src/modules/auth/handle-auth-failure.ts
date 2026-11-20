import type { QueryClient } from "@tanstack/react-query";
import type { AnyRouter } from "@tanstack/react-router";
import { loadBrowserSession } from "./browser-key-store";
import {
	AUTH_LOCK,
	AuthenticationRequiredError,
	SessionRecoveredError,
} from "./refresh";

export function configureAuthFailureHandling(
	queryClient: QueryClient,
	router: AnyRouter,
): void {
	let cleanup: Promise<void> | undefined;

	function onError(error: Error): Promise<void> | undefined {
		if (import.meta.env.SSR || router.state.location.pathname === "/login") {
			return;
		}

		if (error instanceof SessionRecoveredError) {
			void queryClient.resetQueries();
			return;
		}

		if (!(error instanceof AuthenticationRequiredError)) {
			return;
		}

		if (!cleanup) {
			cleanup = Promise.resolve(
				navigator.locks.request(AUTH_LOCK, async () => {
					if (await loadBrowserSession()) {
						// A newer sign-in finished after the failed session was cleared.
						void queryClient.resetQueries();
						return;
					}

					await queryClient.cancelQueries();
					queryClient.clear();
					router.clearCache();
					await router.navigate({ to: "/login", replace: true });
					router.clearCache();
				}),
			)
				.catch(() => {
					console.error(
						"Could not finish authentication cleanup. Reload this page.",
					);
				})
				.finally(() => {
					cleanup = undefined;
				});
		}

		return cleanup;
	}

	queryClient.getQueryCache().config.onError = onError;
	queryClient.getMutationCache().config.onError = onError;
}
