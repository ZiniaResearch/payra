import { RiEditLine } from "@remixicon/react";
import { isAxiosError } from "axios";
import { type SubmitEvent, useEffect, useRef, useState } from "react";

import { Button } from "@/components/base/buttons/button";
import { IconButton } from "@/components/base/buttons/icon-button";
import { Input } from "@/components/base/input/input";
import { useUpdateProfileName } from "./mutations";

interface ProfileNameProps {
	name?: string;
	isLoading: boolean;
}

interface ProfileErrorResponse {
	error: string;
}

export function ProfileName({ name, isLoading }: ProfileNameProps) {
	const [draft, setDraft] = useState<string | null>(null);
	const editButton = useRef<HTMLButtonElement>(null);
	const input = useRef<HTMLInputElement>(null);
	const updateName = useUpdateProfileName();
	const isEditing = draft !== null;

	const invalidName =
		isAxiosError<ProfileErrorResponse>(updateName.error) &&
		updateName.error.response?.data?.error === "invalid_profile_name";

	useEffect(() => {
		if (isEditing) {
			input.current?.focus();
		}
	}, [isEditing]);

	function startEditing() {
		if (isEditing) {
			input.current?.focus();
			return;
		}

		if (name === undefined) {
			return;
		}

		updateName.reset();
		setDraft(name);
	}

	function finishEditing() {
		setDraft(null);
		editButton.current?.focus();
	}

	function saveName(event: SubmitEvent<HTMLFormElement>) {
		event.preventDefault();

		if (draft === null || draft === name || updateName.isPending) {
			return;
		}

		updateName.mutate(draft, { onSuccess: finishEditing });
	}

	return (
		<div className="flex w-full flex-col items-center gap-3">
			<div className="flex min-w-0 max-w-full items-center justify-center gap-2">
				<h2 className="min-w-0 break-words text-center text-headline-medium text-text-primary">
					<bdi>
						{name ?? (isLoading ? "Loading account…" : "Account unavailable")}
					</bdi>
				</h2>
				<IconButton
					ref={editButton}
					icon={RiEditLine}
					size="small"
					aria-label="Edit name"
					aria-expanded={isEditing}
					disabled={name === undefined}
					onClick={startEditing}
					className="border-0 bg-transparent shadow-none disabled:bg-transparent"
				/>
			</div>

			{draft !== null && (
				<form
					className="flex w-full flex-col gap-3"
					aria-busy={updateName.isPending}
					onSubmit={saveName}
				>
					<Input
						ref={input}
						label="Display name"
						inputDir="auto"
						value={draft}
						isReadOnly={updateName.isPending}
						isInvalid={invalidName}
						errorMessage="Enter 1–80 characters without control characters."
						onChange={(value) => {
							setDraft(value);

							if (updateName.isError) {
								updateName.reset();
							}
						}}
					/>

					{updateName.isError && !invalidName && (
						<p
							className="text-body-regular text-text-error-primary"
							role="alert"
						>
							Could not save your name. Try again.
						</p>
					)}

					<div className="flex justify-end gap-2">
						<Button
							variant="secondary"
							size="small"
							disabled={updateName.isPending}
							onClick={finishEditing}
						>
							Cancel
						</Button>
						<Button
							type="submit"
							size="small"
							disabled={updateName.isPending || draft === name}
						>
							{updateName.isPending ? "Saving…" : "Save"}
						</Button>
					</div>
				</form>
			)}
		</div>
	);
}
