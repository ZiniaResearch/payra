import type { QueryClient } from "@tanstack/react-query";
import type { AnyRouter } from "@tanstack/react-router";
import { AUTH_LOCK, AuthenticationRequiredError } from "./refresh";

export function configureAuthFailureHandling(
	queryClient: QueryClient,
	router: AnyRouter,
): void {
	let cleanup: Promise<void> | undefined;

	function onError(error: Error): Promise<void> | undefined {
		if (
			import.meta.env.SSR ||
			!(error instanceof AuthenticationRequiredError) ||
			router.state.location.pathname === "/login"
		) {
			return;
		}

		if (!cleanup) {
			cleanup = Promise.resolve(
				navigator.locks.request(AUTH_LOCK, async () => {
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
