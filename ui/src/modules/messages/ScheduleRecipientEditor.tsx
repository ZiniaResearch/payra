import { type SubmitEvent, useEffect, useRef, useState } from "react";
import { Button } from "@/components/base/buttons/button";
import { Input } from "@/components/base/input/input";

interface ScheduleRecipientEditorProps {
	phone: string;
	isSaving: boolean;
	saveError: string | undefined;
	onSave: (name: string, phone: string) => string | undefined;
	onCancel: () => void;
}

export function ScheduleRecipientEditor({
	phone,
	isSaving,
	saveError,
	onSave,
	onCancel,
}: ScheduleRecipientEditorProps) {
	const [draftName, setDraftName] = useState("");
	const [draftPhone, setDraftPhone] = useState(phone);
	const [phoneError, setPhoneError] = useState<string>();
	const nameInput = useRef<HTMLInputElement>(null);

	useEffect(() => {
		// Let the list finish restoring focus after replacing the add row.
		const frame = requestAnimationFrame(() => nameInput.current?.focus());
		return () => cancelAnimationFrame(frame);
	}, []);

	function save(event: SubmitEvent<HTMLFormElement>) {
		event.preventDefault();
		if (!isSaving) setPhoneError(onSave(draftName, draftPhone));
	}

	return (
		<form
			className="flex min-w-0 flex-wrap items-start gap-1.5 sm:gap-2"
			aria-busy={isSaving}
			onSubmit={save}
			onKeyDown={(event) => {
				if (event.key !== "Escape") return;
				event.stopPropagation();
				if (!isSaving) onCancel();
			}}
		>
			<Input
				ref={nameInput}
				aria-label="Name (optional)"
				placeholder="Name (optional)"
				inputDir="auto"
				size="small"
				className="min-w-28 max-w-52 flex-1"
				fieldClassName="h-9"
				isReadOnly={isSaving}
				value={draftName}
				onChange={setDraftName}
			/>
			<Input
				aria-label="Phone number"
				placeholder="Phone number"
				type="tel"
				size="small"
				className="min-w-0 font-sans tabular-nums sm:text-body-2-regular"
				fieldClassName="h-9"
				inputClassName="text-body-2-regular tabular-nums"
				style={{ width: "calc(16ch + 2rem)" }}
				value={draftPhone}
				isReadOnly={isSaving}
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
					disabled={isSaving}
					onClick={onCancel}
				>
					Cancel
				</Button>
				<Button
					size="small"
					type="submit"
					className="px-1 sm:px-2"
					disabled={isSaving}
				>
					{isSaving ? "Saving…" : "Save"}
				</Button>
			</div>
			{saveError && (
				<p
					role="alert"
					className="w-full text-body-2-regular text-text-error-primary"
				>
					{saveError}
				</p>
			)}
		</form>
	);
}
