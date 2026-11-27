import { RiAddLine, RiCloseLine } from "@remixicon/react";
import { type Dispatch, type SetStateAction, useRef } from "react";
import { Focusable } from "react-aria-components";
import { Button } from "@/components/base/buttons/button";
import { IconButton } from "@/components/base/buttons/icon-button";
import { Tooltip, TooltipTrigger } from "@/components/base/tooltip/tooltip";
import {
	isSameSendTime,
	SCHEDULE_TIME_ZONE,
	type ScheduleDateTime,
	ScheduleDateTimePicker,
} from "@/modules/messages/ScheduleDateTimePicker";

const MAX_SEND_TIMES = 5;

/** A picker row can be empty until its date and time are applied. */
export interface ScheduleSendTimeRow {
	readonly id: number;
	readonly value: ScheduleDateTime | null;
}

function getAddTimeBlockReason(rows: readonly ScheduleSendTimeRow[]) {
	if (rows.length >= MAX_SEND_TIMES) {
		return `Maximum of ${MAX_SEND_TIMES} send times reached.`;
	}
	if (rows.some(({ value }) => value === null)) {
		return "Set a date and time first.";
	}
}

/** Edits the schedule draft's applied and unfinished send times. */
export function ScheduleSendTimes({
	sendTimes,
	setSendTimes,
}: {
	readonly sendTimes: readonly ScheduleSendTimeRow[];
	readonly setSendTimes: Dispatch<
		SetStateAction<readonly ScheduleSendTimeRow[]>
	>;
}) {
	const nextSendTimeId = useRef(1);
	const addTimeBlockReason = getAddTimeBlockReason(sendTimes);
	const canClearSendTimes = sendTimes.length > 1 || sendTimes[0]?.value != null;

	function addSendTime() {
		const id = nextSendTimeId.current++;
		setSendTimes((current) =>
			getAddTimeBlockReason(current)
				? current
				: [...current, { id, value: null }],
		);
	}

	function removeSendTime(id: number) {
		setSendTimes((current) => current.filter((row) => row.id !== id));
	}

	function clearSendTimes() {
		setSendTimes([{ id: nextSendTimeId.current++, value: null }]);
	}

	function changeSendTime(id: number, value: ScheduleDateTime) {
		setSendTimes((current) => {
			if (
				current.some(
					(row) =>
						row.id !== id && row.value && isSameSendTime(row.value, value),
				)
			) {
				return current;
			}
			return current.map((row) => (row.id === id ? { id, value } : row));
		});
	}

	return (
		<section
			aria-labelledby="schedule-send-times"
			className="flex flex-col gap-3"
		>
			<div className="flex flex-wrap items-baseline justify-between gap-2">
				<h2 id="schedule-send-times" className="text-headline-medium">
					Send times
				</h2>
				<p className="text-caption-1-medium text-text-secondary">
					{SCHEDULE_TIME_ZONE}
				</p>
			</div>

			{sendTimes.map(({ id, value }, index) => (
				<div key={id} className="flex min-w-0 items-center gap-2">
					<ScheduleDateTimePicker
						rowNumber={index + 1}
						value={value}
						otherSendTimes={sendTimes.flatMap((row) =>
							row.id !== id && row.value ? [row.value] : [],
						)}
						onChange={(newValue) => changeSendTime(id, newValue)}
					/>
					{sendTimes.length > 1 && (
						<IconButton
							icon={RiCloseLine}
							aria-label={`Remove send time ${index + 1}`}
							onClick={() => removeSendTime(id)}
						/>
					)}
				</div>
			))}

			<div className="flex flex-wrap items-center gap-2">
				<TooltipTrigger
					isDisabled={!addTimeBlockReason}
					shouldCloseOnPress={false}
				>
					<Focusable>
						<Button
							variant="secondary"
							leadingIcon={RiAddLine}
							aria-disabled={addTimeBlockReason ? true : undefined}
							onClick={addTimeBlockReason ? undefined : addSendTime}
						>
							Add another time
						</Button>
					</Focusable>
					{addTimeBlockReason && <Tooltip>{addTimeBlockReason}</Tooltip>}
				</TooltipTrigger>
				<Button
					variant="ghost"
					className="bg-transparent text-text-secondary hover:bg-background-secondary-hover active:bg-background-secondary-hover disabled:bg-transparent disabled:text-text-tertiary"
					disabled={!canClearSendTimes}
					aria-label="Clear all send times"
					onClick={clearSendTimes}
				>
					Clear All
				</Button>
			</div>
		</section>
	);
}
