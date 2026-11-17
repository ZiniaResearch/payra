import { queryOptions } from "@tanstack/react-query";
import { authenticatedRequest } from "@/shared/api/request";

export interface CurrentAccount {
	account_id: string;
	email: string;
}

export function currentAccountQueryOptions() {
	return queryOptions({
		queryKey: ["account", "current"],
		queryFn: ({ signal }) =>
			authenticatedRequest<CurrentAccount>({
				method: "GET",
				url: "/api/profile",
				signal,
			}),
		enabled: !import.meta.env.SSR,
		staleTime: 60_000,
	});
}
