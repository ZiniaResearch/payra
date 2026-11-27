import { useMutation, useQueryClient } from "@tanstack/react-query";
import { isAxiosError } from "axios";
import {
	AuthenticationRequiredError,
	SessionRecoveredError,
} from "@/modules/auth/refresh";
import { authenticatedRequest, readApiErrorCode } from "@/shared/api/request";
import { savedRecipientsQueryOptions } from "./queries";
import { parseSavedRecipient, RECIPIENTS_PATH } from "./recipient";
import { type ScheduleInput, saveSchedule } from "./schedule-api";

interface SaveRecipientInput {
	phone: string;
	name: string | null;
}

export function useSaveRecipient(sessionId: string) {
	const queryClient = useQueryClient();
	const { queryKey } = savedRecipientsQueryOptions(sessionId);

	return useMutation({
		networkMode: "always",
		gcTime: 0,
		mutationFn: async (data: SaveRecipientInput) =>
			parseSavedRecipient(
				await authenticatedRequest<unknown>({
					method: "POST",
					url: RECIPIENTS_PATH,
					headers: { "X-Requested-With": "XmlHttpRequest" },
					data,
				}),
			),
		onSuccess: async (saved) => {
			await queryClient.cancelQueries({ queryKey, exact: true });
			queryClient.setQueryData(queryKey, (current) =>
				current
					? [...current.filter(({ id }) => id !== saved.id), saved].sort(
							(left, right) => left.phone.localeCompare(right.phone),
						)
					: current,
			);
		},
	});
}

export function recipientSaveErrorMessage(error: Error): string {
	if (
		error instanceof AuthenticationRequiredError ||
		error instanceof SessionRecoveredError
	) {
		return error.message;
	}

	if (isAxiosError<unknown>(error)) {
		switch (readApiErrorCode(error.response?.data)) {
			case "invalid_recipient":
				return "Enter a valid Bangladeshi mobile number.";
			case "invalid_recipient_name":
				return "Enter a valid contact name.";
		}
	}

	return "Could not save the recipient. Try again.";
}

/** Saves a schedule once and exposes the request state to the composer. */
export function useScheduleSms() {
	return useMutation({
		networkMode: "always",
		retry: false,
		gcTime: 0,
		mutationFn: async (input: ScheduleInput) => {
			const result = await saveSchedule(input);
			if (result._tag === "error") throw result.error;
		},
	});
}
