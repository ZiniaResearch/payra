import {
	RiAddLine,
	RiDeleteBinLine,
	RiEditLine,
	RiMailLine,
} from "@remixicon/react";
import type { ComponentProps } from "react";
import { Avatar } from "@/components/base/avatar/avatar";
import { Button } from "@/components/base/buttons/button";
import { CloseButton } from "@/components/base/buttons/close-button";
import { IconButton } from "@/components/base/buttons/icon-button";
import { Modal, ModalBody, ModalFooter } from "@/components/base/modal/modal";
import { Skeleton } from "@/components/base/skeleton/skeleton";

type ProfileModalProps = Pick<
	ComponentProps<typeof Modal>,
	"isOpen" | "onOpenChange"
> & {
	name?: string;
	avatarUrl?: string;
	coverUrl?: string;
	email?: string;
	isLoading?: boolean;
};

export function ProfileModal({
	name,
	avatarUrl,
	coverUrl,
	email,
	isLoading = false,
	...props
}: ProfileModalProps) {
	const initials =
		Array.from(name?.trim() ?? "")[0]?.toLocaleUpperCase() ?? "?";

	return (
		<Modal
			{...props}
			size="lg"
			aria-label="Profile"
			className="relative gap-0 bg-background-secondary-default p-0"
		>
			{({ close }) => (
				<>
					<CloseButton
						size="sm"
						aria-label="Close profile"
						onClick={close}
						className="absolute end-4 top-4 z-20"
					/>
					<ModalBody className="p-0">
						<section
							aria-label="Profile information"
							className="flex flex-col gap-6"
						>
							<div className="relative mb-14">
								<div
									aria-hidden
									className="h-40 bg-background-tertiary-default bg-cover bg-center"
									style={{
										backgroundImage: coverUrl
											? `url(${JSON.stringify(coverUrl)})`
											: undefined,
									}}
								/>
								<IconButton
									icon={RiEditLine}
									size="small"
									aria-label="Add or edit cover image (not available yet)"
									title="Cover image editing is not available yet"
									disabled
									className="absolute bottom-3 end-3"
								/>
								<div className="absolute inset-x-0 -bottom-14 flex justify-center">
									<div className="relative">
										<Avatar
											role="img"
											aria-label="Profile image"
											src={avatarUrl}
											alt=""
											initials={initials}
											className="size-28 border-4 border-background-secondary-default text-title-2-medium"
										/>
										<IconButton
											icon={RiAddLine}
											size="small"
											aria-label="Change profile image (not available yet)"
											title="Profile image editing is not available yet"
											disabled
											className="absolute end-0 top-0 size-6 rounded-full"
										/>
									</div>
								</div>
							</div>
							<div className="flex flex-col items-center gap-8 px-6 pb-6">
								<div className="flex items-center justify-center gap-2">
									<h2 className="min-w-0 break-words text-center text-headline-medium text-text-primary">
										<bdi>
											{name ??
												(isLoading
													? "Loading account…"
													: "Account unavailable")}
										</bdi>
									</h2>
									<IconButton
										icon={RiEditLine}
										size="small"
										aria-label="Edit name (not available yet)"
										title="Name editing is not available yet"
										disabled
										className="border-0 bg-transparent shadow-none disabled:bg-transparent"
									/>
								</div>
								<dl className="w-full rounded-xl bg-background-primary-default p-3">
									<div className="flex flex-wrap items-center justify-between gap-2">
										<dt className="flex items-center gap-2 text-body-regular text-text-secondary">
											<RiMailLine
												className="size-4 text-foreground-icon-tertiary"
												aria-hidden
											/>
											Email
										</dt>
										<dd
											dir="ltr"
											className="min-w-0 break-all text-body-medium text-text-primary"
										>
											{isLoading ? (
												<output aria-label="Loading email" aria-busy="true">
													<Skeleton shape="text" className="h-4 w-40" />
												</output>
											) : (
												(email ?? "Email unavailable")
											)}
										</dd>
									</div>
								</dl>
							</div>
						</section>
					</ModalBody>
					<ModalFooter className="mt-0 flex-row justify-end px-6 pb-6">
						<Button
							variant="danger"
							size="small"
							leadingIcon={RiDeleteBinLine}
							title="Account deletion is not available yet"
							disabled
						>
							Delete account
						</Button>
					</ModalFooter>
				</>
			)}
		</Modal>
	);
}
