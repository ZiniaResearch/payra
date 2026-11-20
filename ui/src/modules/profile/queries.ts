import { queryOptions } from "@tanstack/react-query";

import { authenticatedRequest } from "@/shared/api/request";

export interface CurrentAccount {
	account_id: string;
	email: string;
	name: string;
	avatar_url: string;
	cover_url: string;
	cover_id: string | null;
}

export interface CoverPreset {
	id: string;
	label: string;
	url: string;
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

export function coverPresetsQueryOptions() {
	return queryOptions({
		queryKey: ["profile", "covers"],
		queryFn: ({ signal }) =>
			authenticatedRequest<CoverPreset[]>({
				method: "GET",
				url: "/api/profile/covers",
				signal,
			}),
		staleTime: 60_000,
	});
}
