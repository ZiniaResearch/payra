import { useMutation, useQueryClient } from "@tanstack/react-query";
import { isAxiosError } from "axios";
import {
	AuthenticationRequiredError,
	SessionRecoveredError,
} from "@/modules/auth/refresh";
import { authenticatedRequest, readApiErrorCode } from "@/shared/api/request";
import { savedRecipientsQueryOptions } from "./queries";
import {
	parseSavedRecipient,
	RECIPIENTS_PATH,
	type RecipientFields,
	type RecipientId,
	type SavedRecipient,
} from "./recipient";
import { type ScheduleInput, saveSchedule } from "./schedule-api";

interface UpdateRecipientInput {
	readonly id: RecipientId;
	readonly changes: Partial<RecipientFields>;
}

function useUpdateRecipientsCache(sessionId: string) {
	const queryClient = useQueryClient();
	const { queryKey } = savedRecipientsQueryOptions(sessionId);

	return async (
		update: (current: readonly SavedRecipient[]) => SavedRecipient[],
	) => {
		await queryClient.cancelQueries({ queryKey, exact: true });
		queryClient.setQueryData(queryKey, (current) =>
			current ? update(current) : current,
		);
	};
}

function upsertSavedRecipient(
	current: readonly SavedRecipient[],
	saved: SavedRecipient,
) {
	// The API returns recipients ordered by their normalized phone numbers.
	const next = current.filter(({ id }) => id !== saved.id);
	const index = next.findIndex(({ phone }) => phone > saved.phone);
	next.splice(index === -1 ? next.length : index, 0, saved);
	return next;
}

async function writeRecipient(
	method: "POST" | "PATCH",
	url: string,
	data: Partial<RecipientFields>,
) {
	return parseSavedRecipient(
		await authenticatedRequest<unknown>({
			method,
			url,
			headers: { "X-Requested-With": "XmlHttpRequest" },
			data,
		}),
	);
}

/** Saves a new recipient and adds the server response to the session's list. */
export function useSaveRecipient(sessionId: string) {
	const updateCache = useUpdateRecipientsCache(sessionId);
	return useMutation({
		networkMode: "always",
		gcTime: 0,
		mutationFn: (data: RecipientFields) =>
			writeRecipient("POST", RECIPIENTS_PATH, data),
		onSuccess: (saved) =>
			updateCache((current) => upsertSavedRecipient(current, saved)),
	});
}

/** Identifies pending changes to saved recipients for this session. */
export function savedRecipientChangeKey(sessionId: string) {
	return ["sms", "recipients", sessionId, "change"] as const;
}

/** Updates a saved recipient and refreshes its cached details by ID. */
export function useUpdateRecipient(sessionId: string) {
	const updateCache = useUpdateRecipientsCache(sessionId);
	return useMutation({
		mutationKey: [...savedRecipientChangeKey(sessionId), "update"],
		networkMode: "always",
		retry: false,
		gcTime: 0,
		mutationFn: ({ id, changes }: UpdateRecipientInput) =>
			writeRecipient("PATCH", `${RECIPIENTS_PATH}/${id}`, changes),
		onSuccess: (saved) =>
			updateCache((current) => upsertSavedRecipient(current, saved)),
	});
}

/** Removes the saved recipient from the cache and draft before the mutation settles. */
export function useDeleteRecipient(
	sessionId: string,
	onDeleted: (id: RecipientId) => void,
) {
	const updateCache = useUpdateRecipientsCache(sessionId);
	return useMutation({
		mutationKey: [...savedRecipientChangeKey(sessionId), "delete"],
		networkMode: "always",
		retry: false,
		gcTime: 0,
		mutationFn: async (id: RecipientId) => {
			try {
				await authenticatedRequest<void>({
					method: "DELETE",
					url: `${RECIPIENTS_PATH}/${id}`,
					headers: { "X-Requested-With": "XmlHttpRequest" },
				});
			} catch (error) {
				// A retry can find the recipient already removed after a lost reply.
				const response = isAxiosError<unknown>(error)
					? error.response
					: undefined;
				if (
					response?.status !== 404 ||
					readApiErrorCode(response.data) !== "recipient_not_found"
				) {
					throw error;
				}
			}
		},
		onSuccess: async (_result, id) => {
			await updateCache((current) =>
				current.filter((recipient) => recipient.id !== id),
			);
			onDeleted(id);
		},
	});
}

/** Returns the message to show when a recipient write fails. */
export function recipientErrorMessage(
	error: unknown,
	action: "save" | "delete",
): string {
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
			case "recipient_phone_already_saved":
				return "This phone number is already saved.";
			case "recipient_not_found":
				return "This recipient is no longer available.";
		}
	}

	return `Could not ${action} the recipient. Try again.`;
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
