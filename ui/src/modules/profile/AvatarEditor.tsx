import { isAxiosError } from "axios";
import { useEffect, useState } from "react";
import { Avatar } from "@/components/base/avatar/avatar";
import { Button } from "@/components/base/buttons/button";
import { FileUpload } from "@/components/base/file-upload/file-upload";
import {
	Modal,
	ModalBody,
	ModalFooter,
	ModalHeader,
} from "@/components/base/modal/modal";
import { useUploadAvatar } from "./mutations";

const maxBytes = 5 * 1024 * 1024;
const imageExtensions = ["jpg", "jpeg", "png", "webp"];

export function AvatarEditor({ onClose }: { onClose: () => void }) {
	const [file, setFile] = useState<File | null>(null);
	const upload = useUploadAvatar();

	return (
		<Modal
			isOpen
			onOpenChange={(open) => !open && onClose()}
			isDismissable={!upload.isPending}
			isKeyboardDismissDisabled={upload.isPending}
		>
			<ModalHeader title="Change profile photo" showCloseButton={false} />
			<ModalBody>
				<div className="flex flex-col items-start gap-3">
					<FileUpload
						allowedExtensions={imageExtensions}
						maxBytes={maxBytes}
						disabled={upload.isPending}
						onFileSelect={(next) => {
							upload.reset();
							setFile(next);
						}}
					/>
					{file && (
						<div className="flex max-w-full items-center gap-3">
							<AvatarPreview file={file} />
							<span className="min-w-0 break-all">{file.name}</span>
						</div>
					)}
					{upload.isError && (
						<p role="alert" className="text-text-error-primary">
							{uploadError(upload.error)}
						</p>
					)}
				</div>
			</ModalBody>
			<ModalFooter>
				<Button
					variant="secondary"
					disabled={upload.isPending}
					onClick={onClose}
				>
					Cancel
				</Button>
				<Button
					disabled={!file || upload.isPending}
					onClick={() => {
						if (file) {
							upload.mutate(file, { onSuccess: onClose });
						}
					}}
				>
					{upload.isPending ? "Uploading…" : "Save photo"}
				</Button>
			</ModalFooter>
		</Modal>
	);
}

function AvatarPreview({ file }: { file: File }) {
	const [src, setSrc] = useState<string>();

	useEffect(() => {
		const url = URL.createObjectURL(file);
		setSrc(url);
		return () => URL.revokeObjectURL(url);
	}, [file]);

	return <Avatar src={src} alt="New photo preview" className="size-20" />;
}

function uploadError(error: Error | null) {
	const status = isAxiosError(error) ? error.response?.status : undefined;

	switch (status) {
		case 413:
			return "That image is larger than 5 MB. Choose a smaller image.";
		case 422:
			return "Choose a valid JPEG, PNG, or WebP up to 4096 × 4096 pixels.";
		case 429:
			return "Photo uploads are busy. Try saving again in a moment.";
		default:
			return "Could not confirm the upload. Check your profile or try saving again.";
	}
}
