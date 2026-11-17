import { QueryClient } from "@tanstack/react-query";
import { isAxiosError, isCancel } from "axios";
import { AuthenticationRequiredError } from "@/modules/auth/refresh";

function retryQuery(failureCount: number, error: Error): boolean {
	if (error instanceof AuthenticationRequiredError || isCancel(error)) {
		return false;
	}

	if (!isAxiosError(error) || failureCount >= 2) {
		return false;
	}

	return !error.response || error.response.status >= 500;
}

export function getContext() {
	const queryClient = new QueryClient({
		defaultOptions: {
			queries: { retry: retryQuery },
			mutations: { retry: false },
		},
	});

	return {
		queryClient,
	};
}
export default function TanstackQueryProvider() {}
