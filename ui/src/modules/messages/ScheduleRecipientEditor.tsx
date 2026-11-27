import { type SubmitEvent, useEffect, useRef, useState } from "react";
import { Button } from "@/components/base/buttons/button";
import { Input } from "@/components/base/input/input";
import { SpinnerGlyph } from "@/components/base/spinner/spinner";
import { normalizeRecipientPhone, type RecipientFields } from "./recipient";

interface ScheduleRecipientEditorProps {
	readonly initialPhone: string;
	readonly initialName?: string | null;
	readonly isSaving: boolean;
	readonly saveError: string | undefined;
	readonly onSave: (fields: RecipientFields) => void;
	readonly onCancel: () => void;
}

/** Edits a recipient's phone number and optional name in the list. */
export function ScheduleRecipientEditor({
	initialPhone,
	initialName,
	isSaving,
	saveError,
	onSave,
	onCancel,
}: ScheduleRecipientEditorProps) {
	const [draftName, setDraftName] = useState(initialName ?? "");
	const [draftPhone, setDraftPhone] = useState(initialPhone);
	const [phoneError, setPhoneError] = useState<string>();
	const nameInput = useRef<HTMLInputElement>(null);

	useEffect(() => {
		// Let the list finish restoring focus after replacing the row.
		const frame = requestAnimationFrame(() => nameInput.current?.focus());
		return () => cancelAnimationFrame(frame);
	}, []);

	function save(event: SubmitEvent<HTMLFormElement>) {
		event.preventDefault();
		if (isSaving) return;
		const phone = normalizeRecipientPhone(draftPhone);
		if (!phone) {
			setPhoneError("Enter a valid Bangladeshi mobile number.");
			return;
		}
		onSave({ phone, name: draftName.trim() || null });
	}

	return (
		<form
			className="flex min-w-0 flex-wrap items-start gap-1.5 sm:gap-2"
			aria-busy={isSaving}
			onSubmit={save}
			onKeyDown={(event) => {
				if (event.key === "Enter") {
					// Keep native form submission from being intercepted by the grid.
					event.stopPropagation();
					return;
				}
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
					{isSaving ? (
						<span className="inline-flex items-center gap-1.5">
							<SpinnerGlyph size="xs" className="text-current" />
							Saving…
						</span>
					) : (
						"Save"
					)}
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
