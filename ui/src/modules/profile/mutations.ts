import { useMutation, useQueryClient } from "@tanstack/react-query";

import { authenticatedRequest } from "@/shared/api/request";
import { type CurrentAccount, currentAccountQueryOptions } from "./queries";

const writeHeaders = { "X-Requested-With": "XmlHttpRequest" };

export function useUpdateProfileName() {
	return useProfileChange(
		(name: string) => patchProfile({ name }),
		(profile) => ({ name: profile.name }),
	);
}

function patchProfile(data: { name: string }) {
	return authenticatedRequest<CurrentAccount>({
		method: "PATCH",
		url: "/api/profile",
		headers: writeHeaders,
		data,
	});
}

function useProfileChange<T>(
	mutationFn: (value: T) => Promise<CurrentAccount>,
	changedFields: (profile: CurrentAccount) => Partial<CurrentAccount>,
) {
	const queryClient = useQueryClient();
	const { queryKey } = currentAccountQueryOptions();

	return useMutation({
		networkMode: "always",
		gcTime: 0,
		mutationFn,
		onSuccess: async (profile) => {
			const current = queryClient.getQueryData(queryKey);
			if (current?.account_id !== profile.account_id) {
				return;
			}

			await queryClient.cancelQueries({ queryKey, exact: true });

			queryClient.setQueryData(queryKey, (cached) =>
				cached?.account_id === profile.account_id
					? { ...cached, ...changedFields(profile) }
					: cached,
			);
		},
	});
}
