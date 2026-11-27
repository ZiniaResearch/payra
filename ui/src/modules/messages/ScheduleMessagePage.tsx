import { RiErrorWarningLine } from "@remixicon/react";
import { useIsMutating } from "@tanstack/react-query";
import { getRouteApi } from "@tanstack/react-router";
import { useRef, useState } from "react";
import { Button } from "@/components/base/buttons/button";
import { Divider } from "@/components/base/divider/divider";
import { SpinnerGlyph } from "@/components/base/spinner/spinner";
import { Textarea } from "@/components/base/textarea/textarea";
import { toast } from "@/components/base/toast/toast";
import { cx } from "@/utils/cx";
import { savedRecipientChangeKey, useScheduleSms } from "./mutations";
import type { RecipientId } from "./recipient";
import { scheduleInstant } from "./ScheduleDateTimePicker";
import { ScheduleRecipients } from "./ScheduleRecipients";
import {
	type ScheduleSendTimeRow,
	ScheduleSendTimes,
} from "./ScheduleSendTimes";
import { ScheduleSaveError } from "./schedule-api";

const protectedRoute = getRouteApi("/_protected");
const INITIAL_SEND_TIMES: readonly ScheduleSendTimeRow[] = [
	{ id: 0, value: null },
];

/** Opens a fresh scheduling draft for the current browser session. */
export function ScheduleMessagePage() {
	const { sessionId } = protectedRoute.useRouteContext();
	return sessionId ? (
		<ScheduleMessageForm key={sessionId} sessionId={sessionId} />
	) : null;
}

function ScheduleMessageForm({ sessionId }: { sessionId: string }) {
	const [message, setMessage] = useState("");
	const [recipientIds, setRecipientIds] = useState<ReadonlySet<RecipientId>>(
		() => new Set(),
	);
	const [sendTimes, setSendTimes] = useState(INITIAL_SEND_TIMES);
	const schedule = useScheduleSms();
	const pendingRecipientChanges = useIsMutating({
		mutationKey: savedRecipientChangeKey(sessionId),
	});
	const submitting = useRef(false);
	const appliedTimes = sendTimes
		.map(({ value }) => value)
		.filter((value) => value !== null);
	const canSchedule =
		pendingRecipientChanges === 0 &&
		message.trim().length > 0 &&
		recipientIds.size > 0 &&
		appliedTimes.length > 0 &&
		appliedTimes.length === sendTimes.length;
	const unconfirmed =
		schedule.error instanceof ScheduleSaveError &&
		schedule.error.outcome === "unconfirmed";

	function submitSchedule() {
		if (!canSchedule || submitting.current) return;
		submitting.current = true;
		schedule.mutate(
			{
				message,
				recipientIds: [...recipientIds],
				sendTimes: appliedTimes.map((time) =>
					scheduleInstant(time).toISOString(),
				),
			},
			{
				onSuccess: () => {
					setMessage("");
					setRecipientIds(new Set());
					setSendTimes(INITIAL_SEND_TIMES);
					toast.success("SMS scheduled.");
				},
				onSettled: () => {
					submitting.current = false;
				},
			},
		);
	}

	return (
		<section
			aria-labelledby="schedule-message-title"
			className="flex min-h-full flex-col gap-6 pt-4 pe-4 pb-4 ps-6 sm:pt-6 sm:pe-6 sm:pb-6 sm:ps-10"
		>
			<header className="flex flex-col gap-3">
				<h1 id="schedule-message-title" className="text-title-2-medium">
					Schedule SMS
				</h1>
				<Divider />
			</header>

			<div className="grid gap-6 lg:grid-cols-2" inert={schedule.isPending}>
				<div className="flex min-w-0 flex-col gap-6">
					<Textarea
						label="Message"
						name="message"
						placeholder="Write your SMS here…"
						rows={4}
						autoResize
						maxRows={8}
						resize="vertical"
						showCount
						value={message}
						onChange={setMessage}
					/>

					<ScheduleSendTimes
						sendTimes={sendTimes}
						setSendTimes={setSendTimes}
					/>
				</div>

				<div className="min-w-0 lg:border-s lg:border-separator-border lg:ps-6">
					<ScheduleRecipients
						sessionId={sessionId}
						selectedRecipients={recipientIds}
						setSelectedRecipients={setRecipientIds}
					/>
				</div>
			</div>

			<div className="flex flex-col items-end gap-3 sm:flex-row sm:items-center sm:justify-end">
				{schedule.error && (
					<p
						role="alert"
						className={cx(
							"flex items-start gap-1.5 text-body-2-regular",
							unconfirmed
								? "text-status-yellow-text"
								: "text-text-error-primary",
						)}
					>
						<RiErrorWarningLine className="size-4 shrink-0" aria-hidden />
						{schedule.error.message}
					</p>
				)}
				<Button
					className="shrink-0"
					aria-busy={schedule.isPending}
					disabled={!canSchedule || schedule.isPending}
					onClick={submitSchedule}
				>
					{schedule.isPending ? (
						<span className="inline-flex items-center gap-2">
							<SpinnerGlyph size="sm" className="text-current" />
							Scheduling…
						</span>
					) : (
						"Schedule SMS"
					)}
				</Button>
			</div>
		</section>
	);
}
