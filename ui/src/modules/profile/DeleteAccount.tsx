import { RiDeleteBinLine } from "@remixicon/react";
import { isAxiosError } from "axios";
import { useId, useRef, useState } from "react";
import { Button } from "@/components/base/buttons/button";
import { ProfileDeletionError, useDeleteProfile } from "./mutations";

export function DeleteAccount({ accountId }: { accountId?: string }) {
	const deletion = useDeleteProfile();
	const titleId = useId();
	const trigger = useRef<HTMLButtonElement>(null);
	const [isConfirming, setIsConfirming] = useState(false);

	function cancel() {
		deletion.reset();
		setIsConfirming(false);
		requestAnimationFrame(() => trigger.current?.focus());
	}

	function removeAccount() {
		if (accountId && !deletion.isPending) {
			deletion.mutate(accountId);
		}
	}

	if (!isConfirming) {
		return (
			<Button
				ref={trigger}
				variant="danger"
				size="small"
				leadingIcon={RiDeleteBinLine}
				disabled={!accountId}
				onClick={() => setIsConfirming(true)}
			>
				Delete account
			</Button>
		);
	}

	return (
		<section
			aria-labelledby={titleId}
			className="flex w-full flex-col gap-3 border-t border-separator-border pt-4 text-body-regular text-text-secondary"
		>
			<div className="flex flex-col gap-1">
				<h2 id={titleId} className="text-headline-medium text-text-primary">
					Delete your account?
				</h2>
				<p>
					Permanently delete your Payra account and any scheduled messages.
					<br />
					You’ll be signed out on all devices.
				</p>
			</div>
			{deletion.isError && (
				<p role="alert" className="text-text-error-primary">
					{deletionError(deletion.error)}
				</p>
			)}
			<div className="flex flex-wrap justify-end gap-2">
				<Button
					variant="secondary"
					size="small"
					autoFocus
					disabled={deletion.isPending}
					onClick={cancel}
				>
					Cancel
				</Button>
				<Button
					variant="danger"
					size="small"
					disabled={!accountId || deletion.isPending}
					onClick={removeAccount}
				>
					{deletion.isPending ? "Deleting…" : "Delete permanently"}
				</Button>
			</div>
		</section>
	);
}

function deletionError(error: Error) {
	if (error instanceof ProfileDeletionError) {
		return error.message;
	}

	const code = isAxiosError<{ error: string }>(error)
		? error.response?.data?.error
		: undefined;

	switch (code) {
		case "deletion_challenge_invalid":
			return "This confirmation expired. Try deleting again.";
		case "deletion_session_inactive":
			return "This Payra session is no longer active. Sign in again.";
		case "deletion_proof_invalid":
			return "This browser could not verify the request. Sign in again.";
		default:
			return "Could not confirm account deletion. Reload and check your account before trying again.";
	}
}
