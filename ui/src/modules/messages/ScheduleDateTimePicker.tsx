import {
	type CalendarDate,
	fromAbsolute,
	Time,
	toCalendarDate,
	toCalendarDateTime,
	toTime,
	type ZonedDateTime,
} from "@internationalized/date";
import { RiCalendarLine } from "@remixicon/react";
import { type PointerEvent, useEffect, useRef, useState } from "react";
import { Calendar, Dialog, Popover, useLocale } from "react-aria-components";
import { Button } from "@/components/base/buttons/button";
import {
	MonthPanel,
	popoverClassName,
	triggerButtonClassName,
} from "@/components/base/date-picker/shared";
import { Tooltip, TooltipTrigger } from "@/components/base/tooltip/tooltip";
import {
	minuteOfDay,
	PAST_TIME_HINT,
	ScheduleTimeOptions,
	TAKEN_TIME_HINT,
} from "@/modules/messages/ScheduleTimeOptions";
import { cx } from "@/utils/cx";
import { useDismissOnOutsidePress } from "@/utils/use-dismiss-on-outside-press";

export const SCHEDULE_TIME_ZONE = "Asia/Dhaka";
const MIDNIGHT = new Time(0);
const MINUTE_MS = 60_000;
const MINUTES_PER_DAY = 24 * 60;
const MIN_LEAD_MINUTES = 10;
const LEAD_TIME_HINT = `At least ${MIN_LEAD_MINUTES} min ahead.`;

function nextMinute(now: number) {
	return (Math.floor(now / MINUTE_MS) + 1) * MINUTE_MS;
}

function earliestSendTime(now = Date.now()) {
	const currentMinute = Math.floor(now / MINUTE_MS);
	return fromAbsolute(
		(currentMinute + MIN_LEAD_MINUTES) * MINUTE_MS,
		SCHEDULE_TIME_ZONE,
	);
}

export interface ScheduleDateTime {
	date: CalendarDate;
	time: Time;
}

/** Resolves a picker value to the same Dhaka instant shown to the user. */
export function scheduleInstant({ date, time }: ScheduleDateTime): Date {
	return toCalendarDateTime(date, time).toDate(SCHEDULE_TIME_ZONE);
}

export function isSameSendTime(
	first: ScheduleDateTime,
	second: ScheduleDateTime,
) {
	return (
		first.date.compare(second.date) === 0 &&
		minuteOfDay(first.time) === minuteOfDay(second.time)
	);
}

function takenMinutesOn(
	date: CalendarDate | null,
	sendTimes: ScheduleDateTime[],
) {
	const taken = new Set<number>();

	for (const sendTime of sendTimes) {
		if (date?.compare(sendTime.date) === 0) {
			taken.add(minuteOfDay(sendTime.time));
		}
	}

	return taken;
}

function nextAvailableTime(
	preferred: Time,
	minimum: Time | undefined,
	taken: ReadonlySet<number>,
) {
	const first = minimum ? minuteOfDay(minimum) : 0;
	const start = Math.max(minuteOfDay(preferred), first);
	let minute = start;
	while (taken.has(minute)) {
		minute = minute + 1 < MINUTES_PER_DAY ? minute + 1 : first;
		if (minute === start) return null;
	}
	return new Time(Math.floor(minute / 60), minute % 60);
}

function firstAvailableSendTime(
	earliest: ZonedDateTime,
	sendTimes: ScheduleDateTime[],
): ScheduleDateTime {
	let date = toCalendarDate(earliest);
	let minimum = toTime(earliest);
	let time = nextAvailableTime(
		minimum,
		minimum,
		takenMinutesOn(date, sendTimes),
	);

	while (!time) {
		date = date.add({ days: 1 });
		minimum = MIDNIGHT;
		time = nextAvailableTime(minimum, minimum, takenMinutesOn(date, sendTimes));
	}
	return { date, time };
}

interface PickerDraft {
	date: CalendarDate | null;
	time: Time | null;
	earliest: ZonedDateTime;
}

function isBeforeCutoff(
	{ date, time }: Pick<PickerDraft, "date" | "time">,
	cutoff: ZonedDateTime,
) {
	if (!date) return false;
	const day = date.compare(toCalendarDate(cutoff));
	return (
		day < 0 || (day === 0 && time !== null && time.compare(toTime(cutoff)) < 0)
	);
}

function refreshDraft(
	current: PickerDraft,
	earliest: ZonedDateTime,
	otherSendTimes: ScheduleDateTime[],
): PickerDraft {
	if (current.earliest.compare(earliest) === 0) return current;
	if (
		current.date &&
		current.time &&
		!isBeforeCutoff(current, current.earliest) &&
		isBeforeCutoff(current, earliest)
	) {
		return { ...firstAvailableSendTime(earliest, otherSendTimes), earliest };
	}
	return { ...current, earliest };
}

interface ScheduleDateTimePickerProps {
	rowNumber: number;
	value: ScheduleDateTime | null;
	otherSendTimes: ScheduleDateTime[];
	onChange: (value: ScheduleDateTime) => void;
}

export function ScheduleDateTimePicker({
	rowNumber,
	value,
	otherSendTimes,
	onChange,
}: ScheduleDateTimePickerProps) {
	const { locale } = useLocale();
	const triggerRef = useRef<HTMLButtonElement>(null);
	const popoverRef = useRef<HTMLElement>(null);
	const [isOpen, setIsOpen] = useState(false);
	const [pickerDraft, setPickerDraft] = useState<PickerDraft>(() => ({
		date: null,
		time: null,
		earliest: earliestSendTime(),
	}));
	const [hintTarget, setHintTarget] = useState<HTMLElement | null>(null);
	const { date: draftDate, time: draftTime, earliest } = pickerDraft;
	const currentMinute = earliest.subtract({ minutes: MIN_LEAD_MINUTES });
	const currentDate = toCalendarDate(currentMinute);
	const earliestDate = toCalendarDate(earliest);
	const earliestTime = toTime(earliest);
	const minDate = firstAvailableSendTime(earliest, otherSendTimes).date;
	const minTime =
		draftDate?.compare(earliestDate) === 0 ? earliestTime : undefined;
	const isTooEarly = isBeforeCutoff(pickerDraft, earliest);
	const takenMinutes = takenMinutesOn(draftDate, otherSendTimes);
	const draft =
		draftDate && draftTime ? { date: draftDate, time: draftTime } : null;
	const isDuplicate =
		draftTime !== null && takenMinutes.has(minuteOfDay(draftTime));
	const invalidReason = isTooEarly
		? isBeforeCutoff(pickerDraft, currentMinute)
			? PAST_TIME_HINT
			: LEAD_TIME_HINT
		: isDuplicate
			? TAKEN_TIME_HINT
			: undefined;
	const hoverHint = hintTarget?.dataset.unavailableReason;

	useDismissOnOutsidePress(isOpen, () => setIsOpen(false), [
		triggerRef,
		popoverRef,
	]);

	useEffect(() => {
		if (!isOpen) return;
		let timer: ReturnType<typeof setTimeout>;

		function refresh() {
			const now = Date.now();
			setPickerDraft((current) =>
				refreshDraft(current, earliestSendTime(now), otherSendTimes),
			);
			timer = setTimeout(refresh, nextMinute(now) - now);
		}

		refresh();
		return () => clearTimeout(timer);
	}, [isOpen, otherSendTimes]);

	function togglePicker() {
		setHintTarget(null);
		if (isOpen) {
			setIsOpen(false);
			return;
		}
		const currentEarliest = earliestSendTime();
		const initial =
			value && !isBeforeCutoff(value, currentEarliest)
				? value
				: firstAvailableSendTime(currentEarliest, otherSendTimes);
		setPickerDraft({ ...initial, earliest: currentEarliest });
		setIsOpen(true);
	}

	function chooseDate(date: CalendarDate) {
		const currentEarliest = earliestSendTime();
		const day = date.compare(toCalendarDate(currentEarliest));
		if (day < 0) return;

		const nextTime = nextAvailableTime(
			draftTime ?? toTime(currentEarliest),
			day === 0 ? toTime(currentEarliest) : undefined,
			takenMinutesOn(date, otherSendTimes),
		);
		if (!nextTime) return;
		setHintTarget(null);
		setPickerDraft({
			date,
			time: draftTime ? nextTime : null,
			earliest: currentEarliest,
		});
	}

	function showUnavailableHint(event: PointerEvent<HTMLDivElement>) {
		const target = event.target;
		setHintTarget(
			target instanceof Element
				? target.closest<HTMLElement>("[data-unavailable-reason]")
				: null,
		);
	}

	function closePicker() {
		setIsOpen(false);
		triggerRef.current?.focus();
	}

	function applySelection() {
		if (!draft || isDuplicate) return;
		const currentEarliest = earliestSendTime();
		if (isBeforeCutoff(draft, currentEarliest)) {
			setPickerDraft((current) =>
				refreshDraft(current, currentEarliest, otherSendTimes),
			);
			return;
		}
		onChange(draft);
		closePicker();
	}

	const label = value
		? new Intl.DateTimeFormat(locale, {
				dateStyle: "medium",
				timeStyle: "short",
				timeZone: SCHEDULE_TIME_ZONE,
				hour12: true,
			}).format(scheduleInstant(value))
		: "Select date and time";

	return (
		<>
			<button
				ref={triggerRef}
				type="button"
				className={cx(
					triggerButtonClassName,
					"min-w-0 flex-1 gap-2 text-start text-body-medium text-text-primary",
				)}
				aria-label={`Send time ${rowNumber}: ${label}`}
				aria-haspopup="dialog"
				aria-expanded={isOpen}
				onClick={togglePicker}
			>
				<RiCalendarLine
					aria-hidden
					className="size-5 shrink-0 text-foreground-icon-primary"
				/>
				<span className="truncate">{label}</span>
			</button>
			<Popover
				ref={popoverRef}
				triggerRef={triggerRef}
				isOpen={isOpen}
				onOpenChange={setIsOpen}
				isNonModal
				offset={4}
				placement="bottom start"
				className={popoverClassName}
			>
				<Dialog
					aria-label={`Choose date and time for send ${rowNumber}`}
					className="flex flex-col gap-2 p-2 outline-none"
					onPointerOver={showUnavailableHint}
					onPointerLeave={() => setHintTarget(null)}
					onScrollCapture={() => setHintTarget(null)}
				>
					<div className="flex flex-col gap-3 sm:flex-row">
						<Calendar
							aria-label="Send date"
							minValue={minDate}
							value={draftDate}
							onChange={chooseDate}
						>
							<MonthPanel
								offset={0}
								showPrev
								showNext
								getDisabledDateReason={(date) =>
									date.compare(currentDate) < 0
										? "This date has already passed."
										: date.compare(earliestDate) < 0
											? LEAD_TIME_HINT
											: "This date has no available send times."
								}
							/>
						</Calendar>
						<ScheduleTimeOptions
							value={draftTime}
							minimum={minTime}
							currentTime={
								draftDate?.compare(currentDate) === 0
									? toTime(currentMinute)
									: undefined
							}
							leadTimeHint={LEAD_TIME_HINT}
							takenMinutes={takenMinutes}
							invalidReason={invalidReason}
							onChange={(time) =>
								setPickerDraft((current) => ({ ...current, time }))
							}
						/>
					</div>
					{isOpen && hoverHint && (
						<TooltipTrigger isOpen>
							<Tooltip triggerRef={{ current: hintTarget }} offset={6}>
								{hoverHint}
							</Tooltip>
						</TooltipTrigger>
					)}
					<div className="flex justify-end gap-2 px-1 pt-1">
						<Button variant="secondary" onClick={closePicker}>
							Cancel
						</Button>
						<Button
							disabled={!draft || isTooEarly || isDuplicate}
							onClick={applySelection}
						>
							Apply
						</Button>
					</div>
				</Dialog>
			</Popover>
		</>
	);
}
