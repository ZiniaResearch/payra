import { useMutation, useQueryClient } from "@tanstack/react-query";

import { authenticatedRequest } from "@/shared/api/request";
import { type CurrentAccount, currentAccountQueryOptions } from "./queries";

export function useUpdateProfileName() {
	const queryClient = useQueryClient();
	const { queryKey } = currentAccountQueryOptions();

	return useMutation({
		networkMode: "always",
		mutationFn: (name: string) =>
			authenticatedRequest<CurrentAccount>({
				method: "PATCH",
				url: "/api/profile",
				headers: { "X-Requested-With": "XmlHttpRequest" },
				data: { name },
			}),
		onSuccess: (profile) => {
			const current = queryClient.getQueryData(queryKey);

			if (!current || current.account_id !== profile.account_id) {
				return;
			}

			queryClient.setQueryData(queryKey, { ...current, name: profile.name });
			void queryClient.invalidateQueries({ queryKey, exact: true });
		},
	});
}
