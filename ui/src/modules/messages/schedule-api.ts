import { isAxiosError } from "axios";
import {
	AuthenticationRequiredError,
	SessionRecoveredError,
} from "@/modules/auth/refresh";
import { authenticatedRequest, readApiErrorCode } from "@/shared/api/request";
import type { RecipientId } from "./recipient";

/** The message, saved contacts, and UTC send times of a complete draft. */
export interface ScheduleInput {
	readonly message: string;
	readonly recipientIds: readonly RecipientId[];
	readonly sendTimes: readonly string[];
}

/** A failure or an unconfirmed outcome that can be shown on the schedule form. */
export class ScheduleSaveError extends Error {
	/** Identifies this error at the scheduling boundary. */
	readonly _tag = "ScheduleSaveError" as const;

	/** Describes whether Payra could confirm that it did not save the schedule. */
	readonly outcome: "rejected" | "unconfirmed";

	constructor(outcome: ScheduleSaveError["outcome"], message: string) {
		super(message);
		this.outcome = outcome;
	}
}

type ScheduleResult =
	| { readonly _tag: "scheduled" }
	| {
			readonly _tag: "error";
			readonly error:
				| ScheduleSaveError
				| AuthenticationRequiredError
				| SessionRecoveredError;
	  };

function unconfirmedSchedule() {
	return new ScheduleSaveError(
		"unconfirmed",
		"We couldn’t confirm whether your SMS was scheduled.",
	);
}

function scheduleError(error: unknown) {
	if (
		error instanceof AuthenticationRequiredError ||
		error instanceof SessionRecoveredError
	) {
		return error;
	}

	const response = isAxiosError<unknown>(error) ? error.response : undefined;
	const code = readApiErrorCode(response?.data);

	switch (code) {
		case "invalid_recipient":
			return new ScheduleSaveError(
				"rejected",
				"A selected recipient is no longer available. Choose your recipients again.",
			);
		case "message_too_long":
			return new ScheduleSaveError(
				"rejected",
				"Shorten your message to 5 SMS parts or fewer.",
			);
		case "send_time_too_soon":
			return new ScheduleSaveError(
				"rejected",
				"One of your send times is now too close. Choose a later time.",
			);
	}

	if (
		code === "sms_scheduling_unavailable" ||
		response?.status === 413 ||
		response?.status === 422
	) {
		return new ScheduleSaveError("rejected", "Couldn’t save your schedule.");
	}

	return unconfirmedSchedule();
}

/** Submits a draft and confirms the receipt without retrying an uncertain save. */
export async function saveSchedule(
	input: ScheduleInput,
): Promise<ScheduleResult> {
	try {
		const response = await authenticatedRequest<unknown>({
			method: "POST",
			url: "/api/sms/jobs",
			headers: { "X-Requested-With": "XmlHttpRequest" },
			data: {
				message: input.message,
				recipient_ids: input.recipientIds,
				send_times: input.sendTimes,
			},
		});
		const ids =
			typeof response === "object" && response !== null && "job_ids" in response
				? response.job_ids
				: undefined;
		if (
			Array.isArray(ids) &&
			ids.length === input.recipientIds.length * input.sendTimes.length &&
			ids.every((id: unknown) => typeof id === "string" && id.length > 0)
		) {
			return { _tag: "scheduled" };
		}
		return { _tag: "error", error: unconfirmedSchedule() };
	} catch (error) {
		return { _tag: "error", error: scheduleError(error) };
	}
}
