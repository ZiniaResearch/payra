"use client";

import {
	type ReactNode,
	type Ref,
	useContext,
	useLayoutEffect,
	useRef,
	useState,
} from "react";
import type { TextAreaProps as AriaTextAreaProps } from "react-aria-components";
import {
	Group as AriaGroup,
	TextArea as AriaTextArea,
} from "react-aria-components";
import { HintText } from "@/components/base/input/hint-text";
import {
	type InputSize,
	inputFieldClassName,
	TextField,
	TextFieldContext,
	type TextFieldProps,
} from "@/components/base/input/input";
import { Label } from "@/components/base/input/label";
import { cx, sortCx } from "@/utils/cx";

/**
 * Multiline sibling of `Input` — same React Aria plumbing, same field shell,
 * same tokens. It reuses `Input`'s `TextField`, `Label` and `HintText`, so a
 * textarea sitting next to an input in a form lines up to the pixel.
 *
 * Architecture (mirrors input.tsx):
 *
 *   TextareaBase — the styled shell. Renders <AriaGroup> + <AriaTextArea>.
 *                  State (hover / focus / disabled / invalid) arrives as
 *                  render-prop booleans from Aria.
 *   Textarea     — opinionated composition: TextField → Label → TextareaBase
 *                  → HintText (+ optional character counter). The component
 *                  most consumers use.
 *
 * What a textarea adds over the single-line field:
 *   - `rows` sets the resting height (3 lines by default)
 *   - `autoResize` grows the field a line at a time up to `maxRows`, then
 *     scrolls — the pattern the composer prompt uses
 *   - `resize` exposes the native grab handle. With `autoResize`, the dragged
 *     height becomes the minimum until the user drags again.
 *   - `showCount` pairs with `maxLength` for the counter settings pages want
 */

type TextareaSize = InputSize;

export type TextareaResize = "none" | "vertical";

/* -------------------------------------------------------------------------- */
/*  TextareaBase                                                               */
/* -------------------------------------------------------------------------- */

export interface TextareaBaseProps
	extends Omit<AriaTextAreaProps, "size" | "className" | "rows"> {
	size?: TextareaSize;
	className?: string;
	/** Resting height, in lines. Also the floor when `autoResize` is on. */
	rows?: number;
	/** Grow with the content instead of scrolling at `rows`. */
	autoResize?: boolean;
	/** Ceiling for `autoResize`, in lines. Past it the field scrolls. */
	maxRows?: number;
	/** Native resize handle. Defaults to `none` when `autoResize` is on. */
	resize?: TextareaResize;
	/** Class for the field shell. */
	fieldClassName?: string;
	/** Ref to the <textarea> element. */
	ref?: Ref<HTMLTextAreaElement>;
	/** Ref to the field shell wrapper. */
	groupRef?: Ref<HTMLDivElement>;
}

const textareaStyles = sortCx({
	textarea: [
		"block w-full min-w-0 bg-transparent border-0 outline-none m-0 p-0 px-1",
		"font-sans text-body-regular text-text-primary",
		"placeholder:text-text-tertiary",
		"focus:placeholder:text-text-primary",
		"disabled:text-input-disabled-text disabled:placeholder:text-input-disabled-text",
		"disabled:cursor-not-allowed",
		"aria-invalid:placeholder:text-text-error-placeholder",
	].join(" "),

	resize: {
		none: "resize-none",
		vertical: "resize-y",
	},

	footer: "flex w-full items-start justify-between gap-3",
	count:
		"ms-auto shrink-0 pt-px text-caption-1-medium text-text-tertiary tabular-nums",
});

/** Fallback line box for `text-body-regular` (14/20) before styles resolve. */
const FALLBACK_LINE_HEIGHT = 20;

export function TextareaBase({
	size: sizeProp,
	rows = 3,
	autoResize = false,
	maxRows,
	resize = autoResize ? "none" : "vertical",
	fieldClassName,
	className,
	ref,
	groupRef,
	onInput,
	...textareaProps
}: TextareaBaseProps) {
	const ctx = useContext(TextFieldContext);
	const size: TextareaSize = sizeProp ?? ctx.size ?? "medium";
	const innerRef = useRef<HTMLTextAreaElement | null>(null);
	const fittedHeight = useRef("");
	const manualHeight = useRef(0);

	const attachRef = (node: HTMLTextAreaElement | null) => {
		innerRef.current = node;
		if (typeof ref === "function") ref(node);
		else if (ref) ref.current = node;
	};

	// Measure from zero so the field shrinks back as well as grows. Reading the
	// computed line box keeps the floor and ceiling honest when a consumer
	// overrides the type scale through `className`.
	const fit = () => {
		const field = innerRef.current;
		if (!field) return;
		if (!autoResize) {
			field.style.height = "";
			field.style.minHeight = "";
			field.style.maxHeight = "";
			field.style.overflowY = "";
			fittedHeight.current = "";
			manualHeight.current = 0;
			return;
		}
		const canDrag = resize === "vertical";
		// The browser writes the height chosen with its resize handle inline.
		if (!canDrag) manualHeight.current = 0;
		else if (field.style.height !== fittedHeight.current) {
			manualHeight.current = parseFloat(field.style.height) || 0;
		}
		const line =
			parseFloat(window.getComputedStyle(field).lineHeight) ||
			FALLBACK_LINE_HEIGHT;
		const minimum = rows * line;
		const ceiling = maxRows ? maxRows * line : Infinity;
		field.style.minHeight = canDrag ? `${Math.min(minimum, ceiling)}px` : "";
		field.style.overflowY = "hidden";
		field.style.height = "0px";
		const next = Math.max(field.scrollHeight, minimum, manualHeight.current);
		fittedHeight.current = `${Math.min(next, ceiling)}px`;
		field.style.height = fittedHeight.current;
		field.style.maxHeight = maxRows ? `${ceiling}px` : "";
		field.style.overflowY = canDrag || next > ceiling ? "auto" : "hidden";
	};

	// No dependency list: typing on an uncontrolled field re-renders nothing,
	// so `onInput` handles keystrokes and this catches every other reason the
	// value moved (a controlled parent, a form reset, an autofill).
	useLayoutEffect(fit);

	return (
		<AriaGroup
			ref={groupRef}
			className={(state) =>
				cx(
					inputFieldClassName(state, size),
					"h-auto flex-col items-stretch",
					ctx.fieldClassName,
					fieldClassName,
				)
			}
		>
			<AriaTextArea
				ref={attachRef}
				rows={rows}
				{...textareaProps}
				onInput={(event) => {
					fit();
					onInput?.(event);
				}}
				className={cx(
					textareaStyles.textarea,
					textareaStyles.resize[resize],
					ctx.inputClassName,
					className,
				)}
			/>
		</AriaGroup>
	);
}

TextareaBase.displayName = "TextareaBase";

/* -------------------------------------------------------------------------- */
/*  Textarea (composed)                                                        */
/* -------------------------------------------------------------------------- */

export interface TextareaProps
	extends Omit<TextFieldProps, "children">,
		Pick<
			TextareaBaseProps,
			| "rows"
			| "autoResize"
			| "maxRows"
			| "resize"
			| "fieldClassName"
			| "groupRef"
			| "ref"
		> {
	label?: ReactNode;
	hint?: ReactNode;
	/** Show an info icon next to the label. Replace with tooltip when Tooltip lands. */
	tooltip?: boolean | string;
	placeholder?: string;
	/** Hard character limit, enforced by the browser. */
	maxLength?: number;
	/** Show the character counter under the field (`12/280` with `maxLength`). */
	showCount?: boolean;
}

export function Textarea({
	label,
	hint,
	tooltip,
	placeholder,
	rows,
	autoResize,
	maxRows,
	resize,
	maxLength,
	showCount = false,
	fieldClassName,
	ref,
	groupRef,
	className,
	value,
	defaultValue,
	onChange,
	...textFieldProps
}: TextareaProps) {
	// The counter needs the value, and React Aria's render props don't carry it.
	// Controlled fields read straight from the prop; uncontrolled ones keep the
	// length here so a keystroke re-renders the counter and nothing else.
	const [typedLength, setTypedLength] = useState(
		() => (defaultValue ?? "").length,
	);
	const count = value !== undefined ? value.length : typedLength;

	return (
		<TextField
			{...textFieldProps}
			value={value}
			defaultValue={defaultValue}
			onChange={(next) => {
				if (showCount && value === undefined) setTypedLength(next.length);
				onChange?.(next);
			}}
			className={className}
			// Don't clobber an explicit aria-label; fall back to the placeholder
			// only for unlabelled fields that don't provide one.
			aria-label={
				textFieldProps["aria-label"] ??
				(!label && typeof placeholder === "string" ? placeholder : undefined)
			}
		>
			{({ isRequired, isInvalid }) => (
				<>
					{label && (
						<Label
							isRequired={isRequired}
							isInvalid={isInvalid}
							tooltip={tooltip}
						>
							{label}
						</Label>
					)}
					<TextareaBase
						ref={ref}
						groupRef={groupRef}
						placeholder={placeholder}
						rows={rows}
						autoResize={autoResize}
						maxRows={maxRows}
						resize={resize}
						maxLength={maxLength}
						fieldClassName={fieldClassName}
					/>
					{(hint || showCount) && (
						<div className={textareaStyles.footer}>
							{hint && <HintText isInvalid={isInvalid}>{hint}</HintText>}
							{showCount && (
								<span
									className={cx(
										textareaStyles.count,
										isInvalid && "text-text-error-primary",
									)}
								>
									{maxLength ? `${count}/${maxLength}` : count}
								</span>
							)}
						</div>
					)}
				</>
			)}
		</TextField>
	);
}

Textarea.displayName = "Textarea";
