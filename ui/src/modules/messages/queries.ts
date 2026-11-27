import { queryOptions } from "@tanstack/react-query";
import { authenticatedRequest } from "@/shared/api/request";
import { parseSavedRecipient, RECIPIENTS_PATH } from "./recipient";

export function savedRecipientsQueryOptions(sessionId: string) {
	return queryOptions({
		queryKey: ["sms", "recipients", sessionId],
		queryFn: async ({ signal }) => {
			const recipients = await authenticatedRequest<unknown>({
				method: "GET",
				url: RECIPIENTS_PATH,
				signal,
			});
			if (!Array.isArray(recipients)) {
				throw new Error("Invalid saved recipients response.");
			}
			return recipients.map(parseSavedRecipient);
		},
		staleTime: 60_000,
	});
}
