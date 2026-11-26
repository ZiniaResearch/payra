import { type SubmitEvent, useEffect, useRef, useState } from "react";
import { Button } from "@/components/base/buttons/button";
import { Input } from "@/components/base/input/input";
import { cx } from "@/utils/cx";

interface ScheduleRecipientEditorProps {
	mode: "add" | "edit";
	name?: string;
	phone: string;
	onSave: (name: string, phone: string) => string | undefined;
	onCancel: () => void;
}

export function ScheduleRecipientEditor({
	mode,
	name,
	phone,
	onSave,
	onCancel,
}: ScheduleRecipientEditorProps) {
	const [draftName, setDraftName] = useState(name ?? "");
	const [draftPhone, setDraftPhone] = useState(phone);
	const [phoneError, setPhoneError] = useState<string>();
	const nameInput = useRef<HTMLInputElement>(null);
	const isAdding = mode === "add";

	useEffect(() => {
		// The list restores focus when the edit button is replaced by this form.
		const frame = requestAnimationFrame(() => nameInput.current?.focus());
		return () => cancelAnimationFrame(frame);
	}, []);

	function save(event: SubmitEvent<HTMLFormElement>) {
		event.preventDefault();
		const error = onSave(draftName, draftPhone);
		if (error) setPhoneError(error);
	}

	return (
		<form
			className={cx(
				"flex min-w-0 items-start gap-1.5 sm:gap-2",
				isAdding && "flex-wrap",
			)}
			onSubmit={save}
			onKeyDown={(event) => {
				if (event.key !== "Escape") return;
				event.stopPropagation();
				onCancel();
			}}
		>
			<Input
				ref={nameInput}
				placeholder={isAdding ? "Name (optional)" : "Name"}
				inputDir="auto"
				size="small"
				className={cx("max-w-52 flex-1", isAdding ? "min-w-28" : "min-w-16")}
				fieldClassName="h-9"
				value={draftName}
				onChange={setDraftName}
			/>
			<Input
				placeholder="Phone number"
				type="tel"
				size="small"
				className="min-w-0 font-sans tabular-nums sm:text-body-2-regular"
				fieldClassName="h-9"
				inputClassName="text-body-2-regular tabular-nums"
				style={{ width: "calc(16ch + 2rem)" }}
				value={draftPhone}
				isInvalid={phoneError !== undefined}
				errorMessage={phoneError}
				onChange={(value) => {
					setDraftPhone(value);
					setPhoneError(undefined);
				}}
			/>
			<div className="ms-auto flex shrink-0 gap-1.5 pt-0.5 sm:gap-2">
				<Button
					size="small"
					variant="secondary"
					className="px-1 sm:px-2"
					onClick={onCancel}
				>
					Cancel
				</Button>
				<Button size="small" type="submit" className="px-1 sm:px-2">
					Save
				</Button>
			</div>
		</form>
	);
}
