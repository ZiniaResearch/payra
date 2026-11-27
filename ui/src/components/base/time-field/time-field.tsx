"use client";

import { RiTimeLine } from "@remixicon/react";
import type { ComponentType, ReactNode } from "react";
import type {
	TimeFieldProps as AriaTimeFieldProps,
	TimeValue,
} from "react-aria-components";
import {
	DateInput as AriaDateInput,
	DateSegment as AriaDateSegment,
	TimeField as AriaTimeField,
} from "react-aria-components";
import { HintText } from "@/components/base/input/hint-text";
import type { InputSize } from "@/components/base/input/input";
import { inputFieldClassName } from "@/components/base/input/input";
import { Label } from "@/components/base/input/label";
import { cx } from "@/utils/cx";

/**
 * Time field — a time of day typed segment by segment: a meeting start, an
 * opening hour, a reminder.
 *
 *   <TimeField label="Starts at" defaultValue={new Time(9, 30)} />
 *   <TimeField label="Alarm" hourCycle={24} granularity="second" />
 *
 * Input's field (surface, hover and focus rings, invalid and disabled
 * states, two sizes) with a clock icon. Built on React Aria's TimeField:
 * the hour, minute and AM/PM segments follow the locale's format and hour
 * cycle, typing fills a segment and moves on, Up and Down change the focused
 * segment, and a value outside `minValue` and `maxValue` marks the field
 * invalid. The focused segment fills with the accent colour.
 *
 * Values are `Time` objects from @internationalized/date.
 */

type IconComponent = ComponentType<{
	className?: string;
	"aria-hidden"?: boolean | "true" | "false";
}>;

export interface TimeFieldProps<T extends TimeValue>
	extends Omit<AriaTimeFieldProps<T>, "className" | "children"> {
	label?: ReactNode;
	hint?: ReactNode;
	/** Show an info icon next to the label. */
	tooltip?: boolean | string;
	/** 36px (default) or 32px. */
	size?: InputSize;
	/** The clock by default; `null` hides it. */
	icon?: IconComponent | null;
	className?: string;
	fieldClassName?: string;
}

const SEGMENT = [
	"rounded-md px-0.5 text-body-regular text-text-primary tabular-nums caret-transparent outline-none",
	"transition-colors duration-100 ease-out",
	"data-[placeholder]:text-text-tertiary",
	"data-[type=literal]:px-0 data-[type=literal]:text-text-tertiary",
	"data-[focused]:bg-accent-500 data-[focused]:text-text-white",
	"data-[disabled]:text-input-disabled-text",
].join(" ");

export function TimeField<T extends TimeValue>({
	label,
	hint,
	tooltip,
	size = "medium",
	icon: Icon = RiTimeLine,
	className,
	fieldClassName,
	...props
}: TimeFieldProps<T>) {
	return (
		<AriaTimeField
			{...props}
			className={cx("group flex w-full flex-col items-start gap-1", className)}
		>
			{({ isInvalid }) => (
				<>
					{label ? (
						<Label
							isRequired={props.isRequired}
							isInvalid={isInvalid}
							tooltip={tooltip}
						>
							{label}
						</Label>
					) : null}
					<div className="relative w-full">
						<AriaDateInput
							className={(state) =>
								cx(
									inputFieldClassName(state, size),
									// Inline, not flex: React Aria wraps the digits in bidi
									// isolates, which only order inline content, so RTL times
									// keep reading 9:00 rather than 00:9.
									// The shell's own line height sets the line box, so it
									// takes the segments' text style to stay 36px tall.
									"peer block cursor-text whitespace-nowrap text-body-regular",
									// Line the first segment up with Input's text: after the
									// icon at 34px, or at 12px without one.
									Icon ? "ps-[34px]" : "ps-3",
									fieldClassName,
								)
							}
						>
							{(segment) => (
								<AriaDateSegment segment={segment} className={SEGMENT} />
							)}
						</AriaDateInput>
						{Icon ? (
							<Icon
								aria-hidden
								className={cx(
									"pointer-events-none absolute start-2 top-1/2 size-5 -translate-y-1/2 text-foreground-icon-tertiary",
									"peer-data-[invalid]:text-foreground-icon-error peer-data-[disabled]:text-input-disabled-foreground",
								)}
							/>
						) : null}
					</div>
					{hint ? <HintText isInvalid={isInvalid}>{hint}</HintText> : null}
				</>
			)}
		</AriaTimeField>
	);
}
