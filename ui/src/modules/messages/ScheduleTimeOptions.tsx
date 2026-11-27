import { Time } from "@internationalized/date";
import { useId, useLayoutEffect, useRef } from "react";
import { ListBox } from "react-aria-components";
import { SelectItem } from "@/components/base/select/select";
import { TimeField } from "@/components/base/time-field/time-field";
import {
	SCROLL_FADE_DISTANCE,
	ScrollFade,
	useScrollFade,
} from "@/components/foundations/scroll-fade/scroll-fade";
import { cx } from "@/utils/cx";

const twoDigits = (value: number) => String(value).padStart(2, "0");
const HOURS = Array.from({ length: 12 }, (_, index) => twoDigits(index + 1));
const MINUTES = Array.from({ length: 60 }, (_, index) => twoDigits(index));
const PERIODS = ["AM", "PM"];

export const PAST_TIME_HINT = "This time has already passed.";
export const TAKEN_TIME_HINT = "This time is already selected.";

export function minuteOfDay(time: Time) {
	return time.hour * 60 + time.minute;
}

interface ScheduleTimeOptionsProps {
	value: Time | null;
	minimum?: Time;
	currentTime?: Time;
	leadTimeHint: string;
	takenMinutes: ReadonlySet<number>;
	invalidReason?: string;
	onChange: (value: Time | null) => void;
}

export function ScheduleTimeOptions({
	value,
	minimum,
	currentTime,
	leadTimeHint,
	takenMinutes,
	invalidReason,
	onChange,
}: ScheduleTimeOptionsProps) {
	const invalidHintId = useId();
	const hour = value?.hour ?? minimum?.hour ?? 9;
	const minute = value?.minute ?? minimum?.minute ?? 0;
	const afternoon = hour >= 12;
	const earliestHour = minimum?.hour ?? 0;
	const hourOffset = afternoon ? 12 : 0;

	function earlyTimeHint(lastMinute: number) {
		return currentTime && lastMinute < minuteOfDay(currentTime)
			? PAST_TIME_HINT
			: leadTimeHint;
	}

	function minuteUnavailableReason(option: string) {
		const optionMinute = Number(option);
		const dayMinute = hour * 60 + optionMinute;
		if (minimum && dayMinute < minuteOfDay(minimum))
			return earlyTimeHint(dayMinute);
		if (takenMinutes.has(dayMinute)) return TAKEN_TIME_HINT;
	}

	function selectTime(selectedHour: number, selectedMinute: number) {
		const next = new Time(selectedHour, selectedMinute);
		onChange(minimum && next.compare(minimum) < 0 ? minimum : next);
	}

	return (
		<section
			aria-label="Time of day"
			className="flex min-w-0 flex-col gap-3 border-t border-separator-border p-2 sm:w-56 sm:border-s sm:border-t-0"
		>
			<div data-unavailable-reason={invalidReason}>
				<TimeField
					label="Time"
					value={value}
					minValue={minimum}
					onChange={onChange}
					hourCycle={12}
					isInvalid={invalidReason !== undefined}
					aria-describedby={invalidReason ? invalidHintId : undefined}
				/>
				{invalidReason && (
					<span id={invalidHintId} className="sr-only">
						{invalidReason}
					</span>
				)}
			</div>
			<div className="grid grid-cols-3 gap-1">
				<TimeColumn
					label="Hour"
					options={HOURS}
					selected={value ? twoDigits(hour % 12 || 12) : null}
					unavailableReason={(item) => {
						const optionHour = (Number(item) % 12) + hourOffset;
						return optionHour < earliestHour
							? earlyTimeHint(optionHour * 60 + 59)
							: undefined;
					}}
					onSelect={(item) =>
						selectTime((Number(item) % 12) + hourOffset, minute)
					}
				/>
				<TimeColumn
					label="Minute"
					options={MINUTES}
					selected={value ? twoDigits(minute) : null}
					unavailableReason={minuteUnavailableReason}
					onSelect={(item) => selectTime(hour, Number(item))}
				/>
				<TimeColumn
					label="AM or PM"
					options={PERIODS}
					selected={value ? (afternoon ? "PM" : "AM") : null}
					unavailableReason={(item) =>
						item === "AM" && earliestHour >= 12
							? earlyTimeHint(12 * 60 - 1)
							: undefined
					}
					onSelect={(item) =>
						selectTime((hour % 12) + (item === "PM" ? 12 : 0), minute)
					}
				/>
			</div>
		</section>
	);
}

interface TimeColumnProps {
	label: string;
	options: string[];
	selected: string | null;
	unavailableReason: (option: string) => string | undefined;
	onSelect: (option: string) => void;
}

function TimeColumn({
	label,
	options,
	selected,
	unavailableReason,
	onSelect,
}: TimeColumnProps) {
	const listRef = useRef<HTMLDivElement>(null);
	const fade = useScrollFade();
	const reasons = new Map(
		options.map((option) => [option, unavailableReason(option)]),
	);

	useLayoutEffect(() => {
		if (selected === null) return;
		const frame = requestAnimationFrame(() => {
			const list = listRef.current;
			const option = list?.querySelector<HTMLElement>('[aria-selected="true"]');
			if (!list || !option) return;
			const optionTop = option.offsetTop;
			const optionBottom = optionTop + option.offsetHeight;
			const isVisible =
				optionBottom > list.scrollTop &&
				optionTop < list.scrollTop + list.clientHeight;
			if (isVisible) return;
			// Leave room below the selected option for the next available values.
			list.scrollTop = Math.max(0, optionTop - SCROLL_FADE_DISTANCE);
		});
		return () => cancelAnimationFrame(frame);
	}, [selected]);

	return (
		<div className="flex min-w-0 flex-col gap-1">
			<span className="text-center text-caption-1-medium text-text-secondary">
				{label}
			</span>
			<div className="relative">
				<ListBox
					ref={listRef}
					aria-label={label}
					selectionMode="single"
					disallowEmptySelection
					disabledKeys={options.filter((option) => reasons.get(option))}
					selectedKeys={selected === null ? [] : [selected]}
					onSelectionChange={(selection) => {
						if (selection === "all") return;
						const option = selection.values().next().value;
						if (typeof option === "string") onSelect(option);
					}}
					onScroll={fade.onScroll}
					className={cx(
						"relative flex max-h-32 flex-col gap-1 overflow-x-hidden overflow-y-auto overscroll-contain rounded-lg p-1 outline-none sm:max-h-48",
						options.length > 2 &&
							"pe-3 [scrollbar-gutter:stable] [scrollbar-width:thin]",
					)}
				>
					{options.map((option) => (
						<SelectItem
							key={option}
							id={option}
							data-unavailable-reason={reasons.get(option)}
							className={({ isSelected, isDisabled }) =>
								cx(
									"justify-center px-1 tabular-nums",
									isSelected && !isDisabled && "bg-accent-600 text-text-white",
								)
							}
						>
							{option}
						</SelectItem>
					))}
				</ListBox>
				<ScrollFade
					progress={fade.progress}
					surfaceClassName="from-background-secondary-default"
				/>
			</div>
		</div>
	);
}
